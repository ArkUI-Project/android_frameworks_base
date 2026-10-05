/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import static org.arkui.wheel.WheelPreferences.*;
import static org.arkui.wheel.WheelStyle.*;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserManager;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import org.arkui.wheel.protocol.WheelProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground-user bound service. Owns overlays but has no privileged task/input permissions. */
public final class WheelService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {
    static final String PREVIEW = "org.arkui.wheel.PREVIEW";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper(), this::message));
    private final List<Trigger> triggers = new ArrayList<>();
    private final Runnable reload = this::load;
    private Messenger host;
    private IBinder.DeathRecipient death;
    private WheelPreferences prefs;
    private WindowManager windows;
    private Context themed;
    private WheelPanel panel;
    private boolean capturing, externalRecognition;
    private final Runnable captureTimeout = () -> { capturing = false; refresh(); };
    private List<WheelCatalog.Item> apps = List.of();
    private Set<String> excluded = Set.of();
    private String foreground = "";
    private String[] recent = new String[0];
    private boolean allowed, destroyed, loaded, loading, dirty = true;
    private int hostUid = -1, generation;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (PREVIEW.equals(action)) open(prefs.get(SIDE, 0, 0, 2) == 1);
            else if (Intent.ACTION_SCREEN_OFF.equals(action)) hide();
            else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) { hide(); refresh(); }
            else if (Intent.ACTION_USER_PRESENT.equals(action)) refresh();
            else {
                if (Intent.ACTION_PACKAGE_REMOVED.equals(action) && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                invalidateCatalog();
            }
        }
    };
    @Override public void onCreate() {
        super.onCreate(); prefs = new WheelPreferences(this);
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        themed = new ContextThemeWrapper(createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null), R.style.WheelTheme);
        windows = themed.getSystemService(WindowManager.class);
        try { hostUid = getPackageManager().getPackageUidAsUser("com.android.systemui", android.os.UserHandle.USER_SYSTEM); }
        catch (PackageManager.NameNotFoundException ignored) { }
        excluded = Set.copyOf(prefs.list(EXCLUDED));
        prefs.store.registerOnSharedPreferenceChangeListener(this);
        IntentFilter state = new IntentFilter(PREVIEW);
        state.addAction(Intent.ACTION_SCREEN_OFF); state.addAction(Intent.ACTION_USER_PRESENT);
        state.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        registerReceiver(receiver, state, Context.RECEIVER_NOT_EXPORTED);
        IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED); packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package"); registerReceiver(receiver, packages, Context.RECEIVER_NOT_EXPORTED);
        load();
    }
    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { allowed = false; hide(); detach(); return false; }
    private boolean message(Message message) {
        if (destroyed || message.sendingUid != hostUid) return true;
        if (message.what == WheelProtocol.ATTACH) {
            detach(); host = message.replyTo;
            if (host != null) {
                death = () -> main.post(() -> { allowed = false; hide(); detach(); });
                try { host.getBinder().linkToDeath(death, 0); configuration(); }
                catch (RemoteException e) { detach(); }
            }
        } else if (message.what == WheelProtocol.RECOGNITION_VISIBILITY && host != null) {
            externalRecognition = message.arg1 == 1;
            if (externalRecognition) hide();
            refresh();
        } else if (message.what == WheelProtocol.ENVIRONMENT && host != null) {
            Bundle state = message.getData();
            boolean next = state.getBoolean(WheelProtocol.ALLOWED);
            String pkg = state.getString(WheelProtocol.FOREGROUND, "");
            boolean changed = next != allowed || !pkg.equals(foreground);
            allowed = next; foreground = pkg;
            if (changed) {
                List<String> dismissed = prefs.list(DISMISSED_RECENT);
                if (dismissed.remove(pkg)) prefs.putList(DISMISSED_RECENT, dismissed);
            }
            String[] value = state.getStringArray(WheelProtocol.RECENT);
            recent = value == null ? new String[0] : value;
            if (changed) { hide(); refresh(); }
        }
        return true;
    }
    private void detach() {
        if (host != null && death != null) host.getBinder().unlinkToDeath(death, 0);
        host = null; death = null; externalRecognition = false;
    }
    private void send(int what, Bundle data) {
        if (host == null) return;
        Message m = Message.obtain(null, what); m.setData(data);
        try { host.send(m); } catch (RemoteException e) { allowed = false; hide(); detach(); }
    }
    private void configuration() {
        Bundle data = new Bundle(); data.putBoolean(WheelProtocol.ENABLED, prefs.enabled());
        send(WheelProtocol.CONFIGURATION, data);
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (DISMISSED_RECENT.equals(key)) return;
        excluded = Set.copyOf(prefs.list(EXCLUDED));
        hide(); configuration();
        if (!prefs.enabled()) {
            generation++; dirty = true; loaded = false; apps = List.of(); main.removeCallbacks(reload);
        } else if (!loaded) load();
        refresh();
    }
    private void invalidateCatalog() {
        generation++; dirty = true; loaded = false; hide(); main.removeCallbacks(reload);
        if (prefs.enabled()) main.postDelayed(reload, 180);
    }
    private void load() {
        if (destroyed || !prefs.enabled() || loading || !dirty) return;
        loading = true; dirty = false;
        int token = generation;
        Context snapshot = createConfigurationContext(new Configuration(getResources().getConfiguration()));
        loader.execute(() -> {
            List<WheelCatalog.Item> result;
            try { result = WheelCatalog.load(snapshot); } catch (RuntimeException e) { result = List.of(); }
            final List<WheelCatalog.Item> found = result;
            main.post(() -> {
                loading = false;
                if (destroyed) return;
                if (token == generation && prefs.enabled()) { apps = found; loaded = true; refresh(); }
                if (dirty) { main.removeCallbacks(reload); main.postDelayed(reload, 180); }
            });
        });
    }
    private boolean canShow() {
        return !destroyed && !externalRecognition && host != null && allowed && loaded && prefs.enabled()
                && !excluded.contains(foreground)
                && getSystemService(PowerManager.class).isInteractive()
                && !getSystemService(KeyguardManager.class).isKeyguardLocked()
                && getSystemService(UserManager.class).isUserUnlocked()
                && !getSystemService(UserManager.class).isManagedProfile()
                && (prefs.on(LANDSCAPE, false) || getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE);
    }
    private void refresh() {
        if (!canShow()) { hide(); return; }
        if (capturing) return;
        if (!triggers.isEmpty()) return;
        int side = prefs.get(SIDE, 0, 0, 2);
        if (side != 1) addTrigger(false);
        if (side != 0) addTrigger(true);
    }
    private void addTrigger(boolean left) {
        WindowMetrics metrics = windows.getCurrentWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets safe = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        int height = dp(this, prefs.get(TRIGGER_SIZE, 72, 40, 160));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dp(this, 24), height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT; lp.setFitInsetsTypes(0); lp.setTitle("ArkWheel:trigger");
        lp.x = left ? safe.left : bounds.width() - safe.right - lp.width;
        int span = Math.max(0, bounds.height() - safe.top - safe.bottom - height);
        lp.y = safe.top + Math.round(span * (1 - prefs.get(POSITION, 0, 0, 70) / 100f));
        Trigger trigger = new Trigger(themed, left);
        try { windows.addView(trigger, lp); triggers.add(trigger); }
        catch (WindowManager.BadTokenException | IllegalStateException ignored) { }
    }
    private void open(boolean left) {
        if (!canShow() || panel != null) return;
        if (prefs.on(HAPTICS, true) && !triggers.isEmpty()) triggers.get(0).performHapticFeedback(HapticFeedbackConstants.GESTURE_START);
        WheelPanel created = new WheelPanel(themed, prefs, apps, recent, left, new WheelPanel.Callback() {
            @Override public void launch(WheelCatalog.Item item, boolean alternate) {
                if (!canShow()) return;
                if (item.action == WheelProtocol.RECOGNIZE) {
                    hide(); capturing = true;
                    Bundle data = new Bundle(); data.putInt("action", WheelProtocol.RECOGNIZE);
                    send(WheelProtocol.ACTION, data);
                    main.postDelayed(captureTimeout, 6000); return;
                }
                Bundle data = new Bundle();
                if (item.component == null) { data.putInt("action", item.action); send(WheelProtocol.ACTION, data); }
                else {
                    data.putString(WheelProtocol.COMPONENT, item.id);
                    data.putBoolean(WheelProtocol.SMALL, (prefs.get(LAUNCH_MODE, 0, 0, 1) == 0) != alternate);
                    send(WheelProtocol.LAUNCH, data);
                }
            }
            @Override public void manage() { if (canShow()) send(WheelProtocol.MANAGE, Bundle.EMPTY); }
            @Override public void closed(WheelPanel source) { if (panel == source) panel = null; refresh(); }
        });
        panel = created;
        try { panel.show(windows); }
        catch (WindowManager.BadTokenException | IllegalStateException e) { panel = null; }
    }
    private final class Trigger extends View {
        private final boolean left;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int slop;
        private float x, y;
        private boolean tracking, opened;
        private final Runnable hold = this::openHeld;
        private void openHeld() { if (tracking && canShow()) { open(left); opened = panel != null; } }
        Trigger(Context c, boolean fromLeft) {
            super(c); left = fromLeft; slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setContentDescription(getString(R.string.open_description)); setFocusable(true);
            setOnClickListener(v -> open(left));
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { setSystemGestureExclusionRects(List.of(new Rect(0, 0, w, h))); }
        @Override protected void onDraw(Canvas c) {
            paint.setColor(primary(getContext())); paint.setAlpha(prefs.get(TRIGGER_OPACITY, 40, 0, 100) * 255 / 100);
            float start = left ? dp(getContext(), 2) : getWidth() - dp(getContext(), 5);
            c.drawRoundRect(start, dp(getContext(), 10), start + dp(getContext(), 3), getHeight() - dp(getContext(), 10), dp(getContext(), 2), dp(getContext(), 2), paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                x = event.getRawX(); y = event.getRawY(); tracking = true; opened = false;
                if (prefs.get(INVOCATION, 0, 0, 2) == 2) postDelayed(hold, prefs.get(HOLD, 250, 150, 800));
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL || event.getPointerCount() != 1) {
                removeCallbacks(hold); tracking = false; if (opened && panel != null) panel.cancelDrag(); return true;
            }
            if (!tracking) return true;
            float dx = (event.getRawX() - x) * (left ? 1 : -1), dy = event.getRawY() - y;
            if (action == MotionEvent.ACTION_MOVE) {
                if (Math.hypot(dx, dy) > slop) removeCallbacks(hold);
                if (!opened && prefs.get(INVOCATION, 0, 0, 2) != 2 && dx > slop && dx > Math.abs(dy) * .4f) {
                    open(left); opened = panel != null;
                }
                if (opened && panel != null) panel.dragTo(event.getRawX(), event.getRawY());
            } else if (action == MotionEvent.ACTION_UP) {
                removeCallbacks(hold); tracking = false;
                if (opened && panel != null) panel.releaseDrag(event.getRawX(), event.getRawY());
                else if (Math.hypot(dx, dy) <= slop && prefs.get(INVOCATION, 0, 0, 2) == 0) performClick();
            }
            return true;
        }
        @Override protected void onDetachedFromWindow() { removeCallbacks(hold); tracking = false; super.onDetachedFromWindow(); }
    }
    private void hide() {
        capturing = false; main.removeCallbacks(captureTimeout);
        if (panel != null) { WheelPanel old = panel; panel = null; old.dismissImmediately(); }
        for (Trigger trigger : triggers) windows.removeViewImmediate(trigger);
        triggers.clear();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); invalidateCatalog();
    }
    @Override public void onDestroy() {
        destroyed = true; hide(); detach(); prefs.store.unregisterOnSharedPreferenceChangeListener(this);
        unregisterReceiver(receiver); loader.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
