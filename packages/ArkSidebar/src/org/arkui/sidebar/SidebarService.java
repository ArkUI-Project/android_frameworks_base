/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.sidebar;

import static com.android.internal.arkui.SidebarContract.*;
import static org.arkui.sidebar.SidebarStyle.*;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserManager;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.Toast;

import com.android.internal.arkui.IArkSidebar;
import com.android.internal.arkui.IArkSidebarHost;
import com.android.internal.graphics.ColorUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.arkui.game.protocol.GameProtocol;

/** Bound only by the foreground user's SystemUI; never runs a persistent or cross-user process. */
public final class SidebarService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final List<Handle> handles = new ArrayList<>();
    private SidebarPreferences prefs;
    private WindowManager windows;
    private Context windowContext;
    private IArkSidebarHost host;
    private IBinder.DeathRecipient hostDeath;
    private Bundle lastConfiguration;
    private List<AppCatalog.App> apps = new ArrayList<>();
    private Set<String> excludedApps = Set.of();
    private String foreground = "";
    private String gameForeground = "";
    private boolean gameActive;
    private Configuration overlayConfiguration;
    private final android.content.ComponentCallbacks windowChanges=new android.content.ComponentCallbacks() {
        @Override public void onConfigurationChanged(Configuration config) {
            int changed=overlayConfiguration.updateFrom(config);
            if (destroyed || (changed & (ActivityInfo.CONFIG_ORIENTATION|ActivityInfo.CONFIG_SCREEN_SIZE
                    |ActivityInfo.CONFIG_DENSITY|ActivityInfo.CONFIG_UI_MODE|ActivityInfo.CONFIG_LOCALE
                    |ActivityInfo.CONFIG_LAYOUT_DIRECTION|ActivityInfo.CONFIG_ASSETS_PATHS))==0) return;
            // WindowContext receives display geometry after the Service's configuration callback.
            hideAll(); refreshHandles(); notifyConfiguration();
        }
        @Override public void onLowMemory() { }
    };
    private final BroadcastReceiver gameState = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (GameProtocol.OPEN_APPS.equals(i.getAction())) {
                showPanel(i.getBooleanExtra("left",true),windows.getCurrentWindowMetrics().getBounds().height()*.4f,true);
                return;
            }
            gameActive = i.getBooleanExtra("active", false);
            gameForeground = i.getStringExtra("package");
        }
    };
    private String[] recent = new String[0];
    private boolean available, destroyed, recognizing;
    private final Runnable recognitionTimeout = this::finishRecognitionCapture;
    private SidebarPanel panel;
    private Operation pendingOperation;
    private int loadGeneration;
    private boolean appsDirty = true, appsLoading, appsLoaded;
    private Configuration catalogConfiguration;
    private final Runnable reloadApps = this::loadApps;
    private int handleSide = -1, handleHeight, handleOpacity;

    private final IArkSidebar.Stub binder = new IArkSidebar.Stub() {
        @Override public void attachHost(IArkSidebarHost connection) {
            main.post(() -> {
                if (destroyed) return;
                detachHost(); host = connection;
                if (host == null) return;
                hostDeath = () -> main.post(() -> { available = false; detachHost(); hideAll(); });
                try { host.asBinder().linkToDeath(hostDeath, 0); notifyConfiguration(); }
                catch (RemoteException e) { detachHost(); }
            });
        }
        @Override public void updateEnvironment(boolean allowed, String pkg, String[] packages) {
            main.post(() -> {
                if (destroyed) return;
                boolean changed = available != allowed || !foreground.equals(pkg == null ? "" : pkg);
                available = allowed; foreground = pkg == null ? "" : pkg;
                recent = packages == null ? new String[0] : packages.clone();
                if (changed) {
                    pendingOperation = null;
                    recognizing = false; main.removeCallbacks(recognitionTimeout);
                    hidePanel(); refreshHandles(); notifyConfiguration();
                }
            });
        }
        @Override public void openFromGesture(boolean left, float y) {
            main.post(() -> {
                if (!destroyed && prefs.get(MODE, HANDLE, 0, 2) != HANDLE) showPanel(left, y);
            });
        }
        @Override public void recognitionCaptureFinished() {
            main.post(SidebarService.this::finishRecognitionCapture);
        }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // Replacement sends REMOVED followed by ADDED; load only the installed version.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction())
                    && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            invalidateApps(150);
        }
    };
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) hideAll();
            else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(i.getAction())) {
                pendingOperation = null;
                hidePanel(); refreshHandles();
            } else refreshHandles();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        windowContext = new ContextThemeWrapper(createWindowContext(display,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null), R.style.SidebarTheme);
        windows = windowContext.getSystemService(WindowManager.class);
        prefs = new SidebarPreferences(this);
        overlayConfiguration=new Configuration(windowContext.getResources().getConfiguration());
        windowContext.registerComponentCallbacks(windowChanges);
        IntentFilter gameFilter=new IntentFilter(GameProtocol.SIDEBAR_STATE); gameFilter.addAction(GameProtocol.OPEN_APPS);
        registerReceiver(gameState, gameFilter,
                GameProtocol.PERMISSION, main, Context.RECEIVER_EXPORTED);
        excludedApps = Set.copyOf(prefs.list(EXCLUDED));
        catalogConfiguration = new Configuration(getResources().getConfiguration());
        prefs.store.registerOnSharedPreferenceChangeListener(this);
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED); pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED); pf.addDataScheme("package");
        registerReceiver(packages, pf, Context.RECEIVER_NOT_EXPORTED);
        IntentFilter sf = new IntentFilter();
        sf.addAction(Intent.ACTION_SCREEN_OFF); sf.addAction(Intent.ACTION_USER_PRESENT);
        sf.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        registerReceiver(screen, sf, Context.RECEIVER_NOT_EXPORTED);
        loadApps();
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public boolean onUnbind(Intent intent) { available = false; hideAll(); detachHost(); return false; }

    private void detachHost() {
        if (host != null && hostDeath != null) host.asBinder().unlinkToDeath(hostDeath, 0);
        host = null; hostDeath = null; lastConfiguration = null;
    }

    private void notifyConfiguration() {
        if (host == null) return;
        Bundle configuration = prefs.configuration();
        configuration.putInt(READY, canShow() && panel == null && !recognizing ? 1 : 0);
        if (lastConfiguration != null) {
            boolean changed = false;
            for (String key : configuration.keySet()) {
                if (configuration.getInt(key) != lastConfiguration.getInt(key)) {
                    changed = true; break;
                }
            }
            if (!changed) return;
        }
        try { host.configurationChanged(configuration); lastConfiguration = configuration; }
        catch (RemoteException e) { available = false; detachHost(); hideAll(); }
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences shared, String key) {
        if (SidebarPreferences.isPositionKey(key)) {
            for (Handle handle : handles) handle.updateSavedPosition();
            return;
        }
        if (key == null || EXCLUDED.equals(key)) excludedApps = Set.copyOf(prefs.list(EXCLUDED));
        if (key == null || ENABLED.equals(key)) {
            if (prefs.enabled(ENABLED)) scheduleAppsLoad(0);
            else {
                ++loadGeneration; appsDirty = true; appsLoaded = false;
                main.removeCallbacks(reloadApps);
                apps = List.of();
            }
        }
        pendingOperation = null;
        notifyConfiguration(); hidePanel(); refreshHandles();
    }

    private void invalidateApps(long delay) {
        ++loadGeneration; appsDirty = true; appsLoaded = false;
        hideAll(); notifyConfiguration();
        scheduleAppsLoad(delay);
    }

    private void scheduleAppsLoad(long delay) {
        main.removeCallbacks(reloadApps);
        if (!destroyed && prefs.enabled(ENABLED)) main.postDelayed(reloadApps, delay);
    }

    private void loadApps() {
        if (destroyed || !prefs.enabled(ENABLED) || appsLoading || !appsDirty) return;
        appsLoading = true; appsDirty = false;
        int generation = loadGeneration;
        Context loadContext = createConfigurationContext(new Configuration(catalogConfiguration));
        loader.execute(() -> {
            List<AppCatalog.App> loaded;
            try { loaded = AppCatalog.load(loadContext); }
            catch (RuntimeException e) { loaded = new ArrayList<>(); }
            final List<AppCatalog.App> result = loaded;
            main.post(() -> {
                appsLoading = false;
                if (destroyed) return;
                if (generation == loadGeneration && prefs.enabled(ENABLED)) {
                    apps = result; appsLoaded = true;
                    refreshHandles(); notifyConfiguration();
                }
                // Invalidations during a scan produce at most one follow-up scan.
                if (appsDirty) scheduleAppsLoad(150);
            });
        });
    }

    private boolean canShow() {
        return !destroyed && appsLoaded && host != null && available && prefs.enabled(ENABLED)
                && getSystemService(PowerManager.class).isInteractive()
                && !getSystemService(KeyguardManager.class).isKeyguardLocked()
                && getSystemService(UserManager.class).isUserUnlocked()
                && !getSystemService(UserManager.class).isManagedProfile()
                && !excludedApps.contains(foreground)
                && (prefs.enabled(LANDSCAPE)
                        || getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE);
    }

    private WindowManager.LayoutParams overlay(int width, int height, String title) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.setTitle(title);
        lp.setFitInsetsTypes(0);
        lp.setCanPlayMoveAnimation(false);
        return lp;
    }

    private void refreshHandles() {
        boolean visible = canShow() && panel == null && !recognizing
                && prefs.get(MODE, HANDLE, 0, 2) != BACK_HOLD;
        int side = prefs.get(SIDE, 0, 0, 2);
        int height = prefs.get(HANDLE_HEIGHT, 68, 36, 160);
        int opacity = prefs.get(HANDLE_OPACITY, 45, 10, 100);
        if (visible && handleSide == side && handleHeight == height
                && handles.size() == (side == 2 ? 2 : 1)) {
            for (Handle handle : handles) {
                handle.updateSavedPosition();
                if (handleOpacity != opacity) handle.invalidate();
            }
            handleOpacity = opacity;
            return;
        }
        for (View view : handles) windows.removeViewImmediate(view);
        handles.clear();
        if (!visible) return;
        handleSide = side; handleHeight = height; handleOpacity = opacity;
        if (side != 1) addHandle(true);
        if (side != 0) addHandle(false);
    }

    private void addHandle(boolean left) {
        int height = dp(this, prefs.get(HANDLE_HEIGHT, 68, 36, 160) + 16);
        WindowManager.LayoutParams lp = overlay(dp(this, 20), height, "ArkSidebar:handle");
        WindowMetrics metrics = windows.getCurrentWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets safe = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        int span = Math.max(0, bounds.height() - safe.top - safe.bottom - height);
        lp.x = left ? safe.left : bounds.width() - safe.right - lp.width;
        lp.y = safe.top + Math.round(span * prefs.position());
        Handle handle = new Handle(windowContext, left, lp, safe.top, span,
                left ? safe.left : bounds.width() - safe.right);
        try { windows.addView(handle, lp); handles.add(handle); }
        catch (WindowManager.BadTokenException | IllegalStateException ignored) { }
    }

    private final class Handle extends View {
        private static final long REPOSITION_HOLD_MS = 350;
        private static final int IDLE = 0, PRESSED = 1, OPENING = 2,
                REPOSITIONING = 3, CANCELLED = 4;
        private final boolean left;
        private final WindowManager.LayoutParams lp;
        private final int top, span, edge, touchSlop;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int idleColor, activeColor;
        private final int idleWidth, expandedWidthDelta, idleInset, idleVerticalInset;
        private final Runnable longPress = this::beginReposition;
        private final Runnable applyPosition = this::applyPosition;
        private boolean positionUpdatePending;
        private float downX, downY, startY, expansion, edgeOffset;
        private int gesture = IDLE, pointerId = MotionEvent.INVALID_POINTER_ID;
        private ValueAnimator shapeAnimator;

        Handle(Context context, boolean left, WindowManager.LayoutParams lp,
                int top, int span, int edge) {
            super(context);
            this.left = left; this.lp = lp; this.top = top; this.span = span; this.edge = edge;
            idleColor = context.getColor(com.android.internal.R.color.materialColorOnSurfaceVariant);
            activeColor = context.getColor(com.android.internal.R.color.materialColorPrimary);
            idleWidth = dp(context, 3); expandedWidthDelta = dp(context, 25);
            idleInset = dp(context, 2); idleVerticalInset = dp(context, 8);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setFocusable(true);
            setContentDescription(getString(R.string.handle_description));
            setOnClickListener(v -> showPanel(left, lp.y + getHeight() / 2f));
            setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, getString(R.string.handle_move_down)));
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, getString(R.string.handle_move_up)));
                }
                @Override public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                    if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                        int value = prefs.get(POSITION, 35, 0, 100)
                                + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 5 : -5);
                        prefs.put(POSITION, Math.max(0, Math.min(100, value))); return true;
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
        }
        void updateSavedPosition() {
            if (gesture != IDLE || shapeAnimator != null || !isAttachedToWindow()) return;
            int y = top + Math.round(span * prefs.position());
            if (lp.y == y) return;
            lp.y = y;
            windows.updateViewLayout(this, lp);
        }
        private void schedulePositionUpdate() {
            if (positionUpdatePending) return;
            positionUpdatePending = true;
            postOnAnimation(applyPosition);
        }
        private void applyPosition() {
            positionUpdatePending = false;
            if (isAttachedToWindow()) windows.updateViewLayout(this, lp);
        }
        private void flushPositionUpdate() {
            if (positionUpdatePending) removeCallbacks(applyPosition);
            applyPosition();
        }
        private void resizeTouchWindow(int width) {
            if (!isAttachedToWindow() || lp.width == width) return;
            lp.width = width;
            lp.x = left ? edge : edge - width;
            windows.updateViewLayout(this, lp);
        }
        private void beginReposition() {
            if (gesture != PRESSED || !isAttachedToWindow() || !canShow()) return;
            gesture = REPOSITIONING;
            resizeTouchWindow(dp(getContext(), 44));
            if (prefs.enabled(HAPTICS)) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            animateShape(true);
        }
        private void cancelShapeAnimation() {
            ValueAnimator previous = shapeAnimator;
            shapeAnimator = null;
            if (previous != null) previous.cancel();
        }
        private void animateShape(boolean expanded) {
            cancelShapeAnimation();
            float fromExpansion = expansion, fromOffset = edgeOffset;
            float target = expanded ? 1 : 0;
            if (expansion == target && (expanded || edgeOffset == 0)) {
                if (!expanded) { resizeTouchWindow(dp(getContext(), 20)); updateSavedPosition(); }
                return;
            }
            long duration = (expanded ? 160 : 200) * prefs.get(ANIMATION, 100, 0, 200) / 100;
            if (duration == 0 || !ValueAnimator.areAnimatorsEnabled()) {
                expansion = target;
                if (!expanded) edgeOffset = 0;
                invalidate();
                if (!expanded) { resizeTouchWindow(dp(getContext(), 20)); updateSavedPosition(); }
                return;
            }
            ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
            shapeAnimator = animator;
            animator.setDuration(duration);
            animator.setInterpolator(new PathInterpolator(.2f, 0, .2f, 1));
            animator.addUpdateListener(a -> {
                float progress = (float) a.getAnimatedValue();
                expansion = fromExpansion + (target - fromExpansion) * progress;
                if (!expanded) edgeOffset = fromOffset * (1 - progress);
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (shapeAnimator != animation) return;
                    shapeAnimator = null;
                    if (!expanded) {
                        resizeTouchWindow(dp(getContext(), 20));
                        updateSavedPosition();
                    }
                }
            });
            animator.start();
        }
        private void cancelTouch() {
            main.removeCallbacks(longPress);
            if (gesture == REPOSITIONING) {
                lp.y = Math.round(startY);
                flushPositionUpdate();
            }
            gesture = CANCELLED;
            animateShape(false);
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            setSystemGestureExclusionRects(List.of(new Rect(0, 0, w, h)));
        }
        @Override protected void onDraw(Canvas canvas) {
            int idleAlpha = handleOpacity * 255 / 100;
            paint.setColor(ColorUtils.blendARGB((idleColor & 0x00FFFFFF) | (idleAlpha << 24),
                    activeColor, expansion));
            float width = idleWidth + expandedWidthDelta * expansion;
            float inset = idleInset + edgeOffset;
            float x = left ? inset : getWidth() - inset - width;
            float y = idleVerticalInset - idleInset * expansion;
            float radius = width / 2f;
            canvas.drawRoundRect(x, y, x + width, getHeight() - y, radius, radius, paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                main.removeCallbacks(longPress);
                if (gesture == REPOSITIONING) cancelTouch();
                downX = e.getRawX(); downY = e.getRawY(); startY = lp.y;
                pointerId = e.getPointerId(0); gesture = PRESSED;
                main.postDelayed(longPress, REPOSITION_HOLD_MS);
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                    || e.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
                cancelTouch();
                return true;
            }
            if (gesture == IDLE) return true;
            if (e.getPointerId(0) != pointerId || e.getPointerCount() != 1) {
                cancelTouch();
            }
            float dx = (e.getRawX() - downX) * (left ? 1 : -1);
            float dy = e.getRawY() - downY;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (gesture == REPOSITIONING) {
                        int y = Math.max(top, Math.min(top + span, Math.round(startY + dy)));
                        if (lp.y != y) { lp.y = y; schedulePositionUpdate(); }
                        edgeOffset = Math.min(dp(getContext(), 12), Math.max(0, dx) * .15f);
                        invalidate();
                    } else if (gesture == PRESSED && Math.hypot(dx, dy) > touchSlop) {
                        main.removeCallbacks(longPress);
                        gesture = dx > touchSlop && dx > Math.abs(dy) ? OPENING : CANCELLED;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(longPress);
                    boolean repositioned = gesture == REPOSITIONING;
                    boolean open = (gesture == OPENING && dx > dp(getContext(), 18))
                            || (gesture == PRESSED && Math.hypot(dx, dy) <= touchSlop);
                    if (repositioned) {
                        int y = Math.max(top, Math.min(top + span, Math.round(startY + dy)));
                        boolean changed = lp.y != y;
                        lp.y = y;
                        if (changed || positionUpdatePending) flushPositionUpdate();
                        prefs.putPosition(span == 0 ? .5f : (lp.y - top) / (float) span);
                    }
                    gesture = IDLE; pointerId = MotionEvent.INVALID_POINTER_ID;
                    if (open) performClick();
                    else animateShape(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelTouch();
                    gesture = IDLE; pointerId = MotionEvent.INVALID_POINTER_ID;
                    return true;
            }
            return true;
        }
        @Override protected void onDetachedFromWindow() {
            main.removeCallbacks(longPress);
            removeCallbacks(applyPosition); positionUpdatePending = false;
            cancelShapeAnimation();
            gesture = IDLE; pointerId = MotionEvent.INVALID_POINTER_ID;
            super.onDetachedFromWindow();
        }
    }

    private void showPanel(boolean left, float y) {
        showPanel(left,y,false);
    }
    private void showPanel(boolean left, float y, boolean appsOnly) {
        if (!canShow() || panel != null || recognizing) return;
        if (!appsOnly && gameActive && foreground.equals(gameForeground)) {
            sendBroadcast(new Intent(GameProtocol.OPEN).setPackage(GameProtocol.PACKAGE)
                    .putExtra("left", left), GameProtocol.PERMISSION);
            return;
        }
        final SidebarPanel[] created = new SidebarPanel[1];
        created[0] = new SidebarPanel(windowContext, prefs, apps,
                AppCatalog.rail(apps, prefs, recent), left, y,
                new SidebarPanel.Actions() {
                    public void launch(AppCatalog.App app, boolean small) {
                        afterClose(() -> { if (host != null) host.launchApplication(app.component, small); });
                    }
                    public void edit() {
                        afterClose(() -> { if (host != null) host.launchApplication(SETTINGS, false); });
                    }
                    public void recognize() {
                        recognizing = true;
                        main.postDelayed(recognitionTimeout, 7000);
                        refreshHandles(); notifyConfiguration();
                        afterClose(() -> { if (host != null) host.recognizeScreen(); });
                    }
                }, () -> {
                    if (panel == created[0]) panel = null;
                    refreshHandles(); notifyConfiguration();
                    main.post(this::runPendingOperation);
                });
        panel = created[0];
        refreshHandles();
        try { panel.show(); notifyConfiguration(); }
        catch (WindowManager.BadTokenException | IllegalStateException e) { panel = null; refreshHandles(); }
    }

    private interface Operation { void run() throws RemoteException; }
    private void finishRecognitionCapture() {
        if (!recognizing) return;
        recognizing = false; main.removeCallbacks(recognitionTimeout);
        refreshHandles(); notifyConfiguration();
    }
    private void afterClose(Operation action) {
        pendingOperation = action;
        if (panel == null) main.post(this::runPendingOperation);
    }
    private void runPendingOperation() {
        if (panel != null) return;
        Operation action = pendingOperation; pendingOperation = null;
        if (action == null || !canShow()) return;
        try { action.run(); }
        catch (RemoteException | SecurityException | android.content.ActivityNotFoundException e) {
            finishRecognitionCapture();
            Toast.makeText(this, R.string.unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void hidePanel() {
        if (panel != null) { panel.dismissImmediately(); panel = null; }
    }
    private void hideAll() {
        pendingOperation = null;
        recognizing = false; main.removeCallbacks(recognitionTimeout);
        hidePanel();
        for (View handle : handles) windows.removeViewImmediate(handle);
        handles.clear();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        int changed = catalogConfiguration.updateFrom(config);
        if ((changed & (ActivityInfo.CONFIG_LOCALE | ActivityInfo.CONFIG_DENSITY
                | ActivityInfo.CONFIG_UI_MODE | ActivityInfo.CONFIG_ASSETS_PATHS)) != 0) invalidateApps(0);
        else { hideAll(); refreshHandles(); notifyConfiguration(); }
    }
    @Override public void onDestroy() {
        destroyed = true; hideAll(); detachHost();
        windowContext.unregisterComponentCallbacks(windowChanges);
        prefs.store.unregisterOnSharedPreferenceChangeListener(this);
        unregisterReceiver(packages); unregisterReceiver(screen);
        unregisterReceiver(gameState);
        loader.shutdownNow(); main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
