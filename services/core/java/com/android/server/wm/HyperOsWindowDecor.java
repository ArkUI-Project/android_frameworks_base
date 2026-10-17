/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.wm;

import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.R;
import com.android.internal.arkui.SmallWindowBounds;
import com.android.server.UiThread;

/**
 * Per-task chrome and input for HyperOS windows. The app retains its normal input channel;
 * only the handles (or the entire minimized preview) belong to this trusted overlay.
 * Views live on UiThread and receive snapshots made under the WM lock. Window attachment
 * and layout run outside that lock; pre-draw validates and merges only the surface transaction.
 */
final class HyperOsWindowDecor {
    private static final String TAG = "HyperOsWindowDecor";
    private static final String TITLE_PREFIX = TAG + ":";
    private static final HyperOsWindowDecor INSTANCE = new HyperOsWindowDecor();

    private final Handler mUiHandler = UiThread.getHandler();
    private final SparseArray<DecorView> mViews = new SparseArray<>();
    private final ArraySet<Task> mPending = new ArraySet<>();
    // Guarded by the WM lock. Geometry and the app transaction must reach the same frame.
    private final SparseArray<SurfaceFrame> mFrames = new SparseArray<>();
    private final Region mHitTestRegion = new Region();
    private WindowManagerService mService;

    static HyperOsWindowDecor getInstance() {
        return INSTANCE;
    }

    void init(WindowManagerService service) {
        mService = service;
    }

    /** Takes ownership of an independent transaction, never the WM sync transaction. */
    boolean applyTransaction(Task task, SurfaceControl.Transaction transaction) {
        return applyTransaction(task, transaction, null, 0f);
    }

    boolean applyTransaction(Task task, SurfaceControl.Transaction transaction,
            Rect surfaceBounds, float surfaceScale) {
        return applyTransaction(task, transaction, surfaceBounds, surfaceScale, Float.NaN);
    }

    boolean applyTransaction(Task task, SurfaceControl.Transaction transaction,
            Rect surfaceBounds, float surfaceScale, float decorExpansion) {
        synchronized (mService.mGlobalLock) {
            if (!canShow(task) || task.mSurfaceControl == null
                    || !task.mSurfaceControl.isValid()) {
                transaction.close();
                return false;
            }
            final Snapshot snapshot = new Snapshot(task,
                    task.mWindowContainerExt.getTaskWindowSurfaceInfo(),
                    surfaceBounds, surfaceScale, decorExpansion);
            if (snapshot.expansion == 1f && !snapshot.closing) {
                // Round the complete outer window, with content joining both bands at
                // straight seams. A crop equal to app bounds rounds those seams instead.
                final int width = task.getBounds().width();
                final int header = Math.round(width * SmallWindowBounds.HYPER_OS_HEADER_HEIGHT_RATIO);
                final int footer = Math.round(width * SmallWindowBounds.HYPER_OS_FOOTER_HEIGHT_RATIO);
                transaction.setCrop(task.mSurfaceControl,
                                new Rect(0, -header, width, task.getBounds().height() + footer))
                        .setCornerRadius(task.mSurfaceControl,
                                width * SmallWindowBounds.HYPER_OS_CORNER_RADIUS_RATIO);
            } else if (!snapshot.closing) {
                transaction.setWindowCrop(task.mSurfaceControl,
                                task.getBounds().width(), task.getBounds().height())
                        .setCornerRadius(task.mSurfaceControl, task.mWindowContainerExt
                                .getTaskWindowSurfaceInfo().getHyperOsPreviewCornerRadius()
                                * (1f - snapshot.expansion));
            }
            final SurfaceFrame previous = mFrames.get(task.mTaskId);
            if (previous != null && previous.surface == task.mSurfaceControl
                    && previous.snapshot.windowingMode == snapshot.windowingMode
                    && previous.snapshot.closeGeneration == snapshot.closeGeneration) {
                previous.transaction.merge(transaction);
                transaction.close();
                previous.snapshot = snapshot;
            } else {
                if (previous != null) previous.transaction.close();
                mFrames.put(task.mTaskId, new SurfaceFrame(task.mSurfaceControl,
                        transaction, snapshot));
            }
            update(task);
            return true;
        }
    }

    private static final class SurfaceFrame {
        final SurfaceControl surface;
        final SurfaceControl.Transaction transaction;
        Snapshot snapshot;

        SurfaceFrame(SurfaceControl surface, SurfaceControl.Transaction transaction,
                Snapshot snapshot) {
            this.surface = surface;
            this.transaction = transaction;
            this.snapshot = snapshot;
        }
    }

    void update(Task task) {
        if (task == null || mService == null) return;
        synchronized (mPending) {
            if (!mPending.add(task)) return;
        }
        mUiHandler.post(() -> {
            synchronized (mPending) {
                mPending.remove(task);
            }
            refresh(task);
        });
    }

    void remove(Task task) {
        if (task == null || mService == null) return;
        synchronized (mService.mGlobalLock) {
            final SurfaceFrame frame = mFrames.get(task.mTaskId);
            if (frame != null) {
                frame.transaction.close();
                mFrames.remove(task.mTaskId);
            }
        }
        mUiHandler.post(() -> removeView(task.mTaskId));
    }

    void clear() {
        if (mService == null) return;
        synchronized (mService.mGlobalLock) {
            for (int i = 0; i < mFrames.size(); i++) mFrames.valueAt(i).transaction.close();
            mFrames.clear();
        }
        mUiHandler.post(() -> {
            for (int i = mViews.size() - 1; i >= 0; i--) removeView(mViews.keyAt(i));
        });
    }

    /** Called with the WM lock held; use the input region published by the decor window. */
    boolean containsInputPoint(Task task, int x, int y) {
        final DisplayContent display = task.getDisplayContent();
        if (display == null) return false;
        final String title = TITLE_PREFIX + task.mTaskId;
        final WindowState window = display.getWindow(w ->
                w.getOwningUid() == Process.SYSTEM_UID
                        && w.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        && title.contentEquals(w.mAttrs.getTitle()));
        if (window == null || !window.isVisible()
                || (window.mAttrs.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
            return false;
        }
        window.getTouchableRegion(mHitTestRegion);
        return mHitTestRegion.contains(x, y);
    }

    /** Called during layer assignment with the WM lock held. */
    boolean assignLayer(WindowToken token, SurfaceControl.Transaction transaction) {
        if (token.windowType != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                || token.getChildCount() == 0) return false;
        final WindowState window = token.getTopChild();
        final String title = window.mAttrs.getTitle().toString();
        if (window.getOwningUid() != Process.SYSTEM_UID || !title.startsWith(TITLE_PREFIX)) {
            return false;
        }
        if (token.mSurfaceControl == null) return true;
        final Task task;
        try {
            task = mService.mRoot.anyTaskForId(Integer.parseInt(
                    title.substring(TITLE_PREFIX.length())),
                    RootWindowContainer.MATCH_ATTACHED_TASK_ONLY);
        } catch (NumberFormatException e) {
            transaction.hide(token.mSurfaceControl);
            return true;
        }
        if (!canShow(task) || task.mSurfaceControl == null
                || !task.mSurfaceControl.isValid()) {
            transaction.hide(token.mSurfaceControl);
        } else {
            // Relative layers follow task Z order: a lower window's handle cannot cover
            // another app window, and moving one task to front brings its chrome with it.
            // Activity and TaskFragment children also occupy task-relative layers. Keep
            // chrome above the entire stack so expanded apps cannot intercept its handles.
            transaction.setRelativeLayer(token.mSurfaceControl, task.mSurfaceControl,
                            Integer.MAX_VALUE)
                    .show(token.mSurfaceControl);
        }
        return true;
    }

    private boolean canShow(Task task) {
        return task != null && task.isAttached() && task.isRootTask()
                && task.showToCurrentUser() && task.isAlwaysOnTop()
                && task.getWindowConfiguration().isPopUpWindowMode()
                && PopUpSettingsConfig.getInstance().isEnabled()
                && PopUpSettingsConfig.getInstance().isHyperOsMode()
                && !mService.isKeyguardLocked()
                && !mService.mAtmService.isSleepingOrShuttingDownLocked();
    }

    private void refresh(Task task) {
        final Snapshot snapshot;
        final SurfaceFrame frame;
        synchronized (mService.mGlobalLock) {
            frame = mFrames.get(task.mTaskId);
            mFrames.remove(task.mTaskId);
            final TaskWindowSurfaceInfo info = task.mWindowContainerExt.getTaskWindowSurfaceInfo();
            if (!canShow(task) || info == null || task.mDisplayContent == null
                    || (!task.isVisible() && !info.isDockedToEdge())
                    || (frame != null && (frame.surface != task.mSurfaceControl
                            || frame.snapshot.closeGeneration != info.getHyperOsCloseGeneration()
                            || frame.snapshot.windowingMode != task.getWindowingMode()))) {
                if (info != null) info.invalidateHyperOsSurface();
                snapshot = null;
            } else {
                snapshot = frame != null ? frame.snapshot : new Snapshot(task, info);
            }
        }
        if (snapshot == null || snapshot.bounds.isEmpty()) {
            if (frame != null) frame.transaction.close();
            removeView(task.mTaskId);
            return;
        }
        DecorView view = mViews.get(task.mTaskId);
        if (frame == null && view != null
                && !snapshot.docked && view.mSnapshot.display.equals(snapshot.display)
                && view.mSnapshot.docked == snapshot.docked
                && view.mSnapshot.displayId == snapshot.displayId) {
            // Animation callbacks can publish the next position before its surface
            // transaction is ready. A state-only refresh must keep displayed geometry.
            snapshot.bounds.set(view.mSnapshot.bounds);
            snapshot.radius = view.mSnapshot.radius;
            snapshot.expansion = view.mSnapshot.expansion;
        }
        if (view != null && view.mSnapshot.displayId != snapshot.displayId) {
            removeView(task.mTaskId);
            view = null;
        }
        if (view == null) {
            final Context uiContext = ActivityThread.currentActivityThread().getSystemUiContext();
            final android.hardware.display.DisplayManager displays =
                    uiContext.getSystemService(android.hardware.display.DisplayManager.class);
            final android.view.Display display = displays.getDisplay(snapshot.displayId);
            if (display == null) {
                if (frame != null) frame.transaction.close();
                return;
            }
            view = new DecorView(uiContext.createDisplayContext(display), task, snapshot);
            final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            params.format = PixelFormat.TRANSLUCENT;
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            params.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                    | WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED;
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            params.setTitle(TITLE_PREFIX + task.mTaskId);
            try {
                view.windowManager.addView(view, params);
                mViews.put(task.mTaskId, view);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Unable to attach task decoration " + task.mTaskId, e);
            }
        } else {
            view.update(snapshot);
        }
        if (frame != null) {
            if (view.getViewRootImpl() != null) {
                view.applyWithNextDraw(frame);
            } else {
                frame.transaction.apply();
                frame.transaction.close();
            }
        }
    }

    private void removeView(int taskId) {
        final DecorView view = mViews.get(taskId);
        if (view == null) return;
        mViews.remove(taskId);
        view.cancelGesture();
        if (view.isAttachedToWindow()) view.windowManager.removeView(view);
    }

    private static final class Snapshot {
        final Rect bounds;
        final Rect display;
        final Rect available;
        final boolean pinned;
        final boolean docked;
        final boolean dockedLeft;
        final boolean animating;
        final boolean transitionAnimating;
        final boolean closing;
        final int displayId;
        final int windowingMode;
        final int closeGeneration;
        final String packageName;
        final float scale;
        float radius;
        float expansion;

        Snapshot(Task task, TaskWindowSurfaceInfo info) {
            this(task, info, null, 0f, Float.NaN);
        }

        Snapshot(Task task, TaskWindowSurfaceInfo info, Rect surfaceBounds, float surfaceScale,
                float decorExpansion) {
            bounds = surfaceBounds != null && !info.isDockedToEdge()
                    ? new Rect(surfaceBounds) : info.getHyperOsDecorBounds();
            display = new Rect(task.mDisplayContent.getBounds());
            final Insets safe = task.mDisplayContent.getInsetsStateController().getRawInsetsState()
                    .calculateInsets(display, display, WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout(), false);
            available = new Rect(display);
            available.inset(safe.left, safe.top, safe.right, safe.bottom);
            displayId = task.getDisplayId();
            windowingMode = task.getWindowingMode();
            closeGeneration = info.getHyperOsCloseGeneration();
            pinned = task.getWindowConfiguration().isPinnedExtWindowMode();
            docked = info.isDockedToEdge();
            dockedLeft = info.isDockedLeft();
            animating = info.isSurfaceAnimating();
            transitionAnimating = info.isDecorTransitionAnimating();
            closing = info.isHyperOsClosing();
            scale = info.getWindowSurfaceScale();
            expansion = Float.isNaN(decorExpansion)
                    ? info.getHyperOsDecorExpansion() : decorExpansion;
            final float realScale = surfaceBounds != null ? surfaceScale
                    : (float) bounds.width() / Math.max(1, task.getBounds().width());
            radius = info.getHyperOsPreviewCornerRadius() * realScale * (1f - expansion);
            final ActivityRecord activity = task.getTopNonFinishingActivity();
            packageName = activity != null ? activity.packageName : "";
        }
    }

    private final class DecorView extends View
            implements ViewTreeObserver.OnComputeInternalInsetsListener,
            ViewTreeObserver.OnPreDrawListener {
        private static final int NONE = 0;
        private static final int MOVE = 1;
        private static final int BOTTOM = 2;
        private static final int RESIZE_LEFT = 3;
        private static final int RESIZE_RIGHT = 4;
        private static final int PREVIEW = 5;
        private static final int DOCK = 6;
        private static final int MENU = 7;
        private static final int TARGET_NONE = 0;
        private static final int TARGET_FULLSCREEN = 1;
        private static final int TARGET_CLOSE = 2;
        private static final int TARGET_MINIMIZE = 3;

        final WindowManager windowManager;
        private final Task mTask;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mContentOutline = new Path();
        private final RectF mTop = new RectF();
        private final RectF mBottom = new RectF();
        private final RectF mMenu = new RectF();
        private final RectF mLeftCorner = new RectF();
        private final RectF mRightCorner = new RectF();
        private final Rect mStartBounds = new Rect();
        private final Region mTouchRegion = new Region();
        private float mDensity;
        private int mSlop;
        private Snapshot mSnapshot;
        private Drawable mIcon;
        private String mIconPackage = "";
        private boolean mMenuVisible;
        private boolean mMoved;
        private boolean mMovingTask;
        private boolean mClosing;
        private int mGesture;
        private int mTarget;
        private float mStartX;
        private float mStartY;
        private float mStartScale;
        private Point mStartCenter;
        private VelocityTracker mVelocity;
        private int mSurfaceColor;
        private int mHandleColor;
        private int mTextColor;
        private int mPreviewColor;
        private int mOutlineColor;
        private SurfaceFrame mDrawFrame;

        DecorView(Context context, Task task, Snapshot snapshot) {
            super(context);
            mTask = task;
            windowManager = context.getSystemService(WindowManager.class);
            mDensity = context.getResources().getDisplayMetrics().density;
            mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            updateColors();
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setFocusable(true);
            setWillNotDraw(false);
            update(snapshot);
        }

        private float dp(float value) {
            return value * mDensity;
        }

        private void updateColors() {
            mSurfaceColor = getContext().getColor(R.color.materialColorSurfaceContainerLow);
            mHandleColor = getContext().getColor(R.color.materialColorOnSurfaceVariant);
            mTextColor = getContext().getColor(R.color.materialColorOnSurface);
            mPreviewColor = getContext().getColor(R.color.materialColorSurfaceContainerHigh);
            mOutlineColor = getContext().getColor(R.color.materialColorPrimary);
        }

        void applyWithNextDraw(SurfaceFrame frame) {
            if (mDrawFrame != null) {
                if (mDrawFrame.surface == frame.surface
                        && mDrawFrame.snapshot.windowingMode == frame.snapshot.windowingMode
                        && mDrawFrame.snapshot.closeGeneration == frame.snapshot.closeGeneration) {
                    mDrawFrame.transaction.merge(frame.transaction);
                    frame.transaction.close();
                    mDrawFrame.snapshot = frame.snapshot;
                } else {
                    mDrawFrame.transaction.close();
                    mDrawFrame = frame;
                }
            } else {
                mDrawFrame = frame;
            }
            invalidate();
        }

        @Override
        public boolean onPreDraw() {
            if (mDrawFrame == null) return true;
            final SurfaceFrame frame = mDrawFrame;
            synchronized (mService.mGlobalLock) {
                final TaskWindowSurfaceInfo info = liveInfo();
                if (info == null || frame.surface != mTask.mSurfaceControl
                        || frame.snapshot.windowingMode != mTask.getWindowingMode()
                        || frame.snapshot.closeGeneration != info.getHyperOsCloseGeneration()) {
                    frame.transaction.close();
                    mDrawFrame = null;
                    if (info != null) info.invalidateHyperOsSurface();
                    mService.requestTraversal();
                    return true;
                }
                // The first relayout initializes the renderer. Waiting until pre-draw
                // prevents applyTransactionOnDraw's software/immediate fallback on entry.
                if (!getViewRootImpl().isHardwareEnabled()) {
                    postInvalidateOnAnimation();
                    return false;
                }
                getViewRootImpl().applyTransactionOnDraw(frame.transaction);
                frame.transaction.close();
                mDrawFrame = null;
            }
            return true;
        }

        void update(Snapshot snapshot) {
            final boolean changed = mSnapshot == null
                    || !mSnapshot.bounds.equals(snapshot.bounds)
                    || !mSnapshot.display.equals(snapshot.display)
                    || !mSnapshot.available.equals(snapshot.available)
                    || mSnapshot.pinned != snapshot.pinned
                    || mSnapshot.docked != snapshot.docked
                    || mSnapshot.animating != snapshot.animating
                    || mSnapshot.transitionAnimating != snapshot.transitionAnimating
                    || mSnapshot.expansion != snapshot.expansion
                    || mSnapshot.radius != snapshot.radius
                    || mSnapshot.closing != snapshot.closing
                    || !mSnapshot.packageName.equals(snapshot.packageName);
            mSnapshot = snapshot;
            mClosing = snapshot.closing;
            if (!mIconPackage.equals(snapshot.packageName)) {
                mIconPackage = snapshot.packageName;
                try {
                    mIcon = getContext().getPackageManager().getApplicationIcon(mIconPackage);
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    mIcon = getContext().getPackageManager().getDefaultActivityIcon();
                }
            }
            if (snapshot.pinned || snapshot.docked) mMenuVisible = false;
            if (changed) {
                updateRegions();
                requestLayout();
                setContentDescription(getContext().getString(snapshot.pinned || snapshot.docked
                        ? R.string.hyperos_window_restore : R.string.hyperos_window_controls));
                invalidate();
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnComputeInternalInsetsListener(this);
            getViewTreeObserver().addOnPreDrawListener(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
            getViewTreeObserver().removeOnPreDrawListener(this);
            if (mDrawFrame != null) {
                mDrawFrame.transaction.close();
                mDrawFrame = null;
                synchronized (mService.mGlobalLock) {
                    final TaskWindowSurfaceInfo info = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
                    if (info != null) info.invalidateHyperOsSurface();
                }
            }
            cancelGesture();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onConfigurationChanged(Configuration config) {
            super.onConfigurationChanged(config);
            cancelGesture();
            mDensity = getContext().getResources().getDisplayMetrics().density;
            mSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            updateColors();
            updateRegions();
            requestLayout();
            invalidate();
            HyperOsWindowDecor.this.update(mTask);
        }

        private void updateRegions() {
            final Rect b = mSnapshot.bounds;
            final float band = b.width() * SmallWindowBounds.HYPER_OS_HEADER_HEIGHT_RATIO;
            final float bottomBand = b.width() * SmallWindowBounds.HYPER_OS_FOOTER_HEIGHT_RATIO;
            mTop.set(b.left, Math.max(mSnapshot.display.top, b.top - band), b.right, b.top);
            mBottom.set(b.left, b.bottom, b.right, b.bottom + bottomBand);
            final float corner = dp(25);
            mLeftCorner.set(b.left - dp(10), b.bottom - dp(5),
                    b.left + corner, b.bottom + bottomBand + dp(10));
            mRightCorner.set(b.right - corner, b.bottom - dp(5),
                    b.right + dp(10), b.bottom + bottomBand + dp(10));
            final float menuWidth = Math.min(dp(216), mSnapshot.display.width() - dp(16));
            final float menuLeft = Math.max(mSnapshot.display.left + dp(8),
                    Math.min(b.centerX() - menuWidth / 2,
                            mSnapshot.display.right - dp(8) - menuWidth));
            final float menuTop = Math.max(mSnapshot.display.top + dp(28),
                    mTop.top - dp(64));
            mMenu.set(menuLeft, menuTop, menuLeft + menuWidth, menuTop + dp(56));
            mTouchRegion.setEmpty();
            if (mClosing || (mSnapshot.animating && mGesture == NONE
                    && !mSnapshot.docked)) return;
            if (mSnapshot.pinned || mSnapshot.docked) {
                mTouchRegion.set(b);
                if (!mSnapshot.docked) {
                    final RectF preview = new RectF(b);
                    final float surround = b.width() * SmallWindowBounds.HYPER_OS_PREVIEW_BORDER_RATIO;
                    preview.inset(-surround, -surround);
                    addTouch(preview);
                }
                mTouchRegion.op(mSnapshot.display, Region.Op.INTERSECT);
            } else {
                addTouch(mTop);
                addTouch(mBottom);
                addTouch(mLeftCorner);
                addTouch(mRightCorner);
                if (mMenuVisible) addTouch(mMenu);
            }
        }

        private void addTouch(RectF rect) {
            final Rect rounded = new Rect();
            rect.roundOut(rounded);
            mTouchRegion.op(rounded, Region.Op.UNION);
        }

        @Override
        public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo insets) {
            insets.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            insets.touchableRegion.set(mTouchRegion);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final Rect b = mSnapshot.bounds;
            if (mClosing || (mSnapshot.transitionAnimating && mGesture == NONE
                    && !mSnapshot.docked)) return;
            final float radius = b.width() * SmallWindowBounds.HYPER_OS_CORNER_RADIUS_RATIO;
            mPaint.setColor(mSurfaceColor);
            mPaint.setStyle(Paint.Style.FILL);
            if (mSnapshot.docked) {
                // Most of this square card sits offscreen, including part of the icon.
                // Centering a tiny icon in the visible strip produces a different pill.
                final float dockRadius = b.width() * .20f;
                canvas.drawRoundRect(new RectF(b), dockRadius, dockRadius, mPaint);
                drawIcon(canvas, b.centerX(), b.centerY(), b.width() * .71f);
                return;
            }
            // Morph the preview surround into caption/footer bands on the very same frame
            // as the app transform. Controls fade in as the bands grow, without a blank frame.
            final float expansion = mSnapshot.expansion;
            final float surround = b.width() * SmallWindowBounds.HYPER_OS_PREVIEW_BORDER_RATIO
                    * (1f - expansion);
            final RectF frame = new RectF(b.left - surround,
                    b.top - surround - (b.top - mTop.top) * expansion,
                    b.right + surround,
                    b.bottom + surround + (mBottom.bottom - b.bottom) * expansion);
            final float outerRadius = mSnapshot.radius + surround + radius * expansion;
            mPaint.setAlpha(Math.round(150 + 105 * expansion));
            mContentOutline.rewind();
            mContentOutline.addRoundRect(new RectF(b), mSnapshot.radius,
                    mSnapshot.radius, Path.Direction.CW);
            canvas.save();
            canvas.clipOutPath(mContentOutline);
            canvas.drawRoundRect(frame, outerRadius, outerRadius, mPaint);
            canvas.restore();
            mPaint.setAlpha(255);
            if (expansion == 0f) return;
            mPaint.setColor(mHandleColor);
            mPaint.setAlpha(Math.round(255 * expansion));
            final float dot = b.width() * .007f;
            for (int i = -1; i <= 1; i++) {
                canvas.drawCircle(b.centerX() + i * dot * 4,
                        (frame.top + b.top) / 2f, dot, mPaint);
            }
            final float halfBar = b.width() * .16f;
            final float barRadius = Math.max(1f, b.width() * .006f);
            final float barY = (b.bottom + frame.bottom) / 2f;
            canvas.drawRoundRect(b.centerX() - halfBar, barY - barRadius,
                    b.centerX() + halfBar, barY + barRadius,
                    barRadius, barRadius, mPaint);
            mPaint.setAlpha(255);
            if (mTarget == TARGET_FULLSCREEN) {
                mPaint.setColor(mPreviewColor);
                canvas.drawRoundRect(frame, radius, radius, mPaint);
                drawIcon(canvas, b.centerX(), b.centerY() - dp(12), dp(24));
                drawText(canvas, R.string.hyperos_window_fullscreen,
                        b.centerX(), b.centerY() + dp(22), 12);
                mPaint.setColor(mOutlineColor);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(dp(2));
                final RectF display = new RectF(mSnapshot.display);
                display.inset(dp(2), dp(2));
                canvas.drawRoundRect(display, dp(32), dp(32), mPaint);
                mPaint.setStyle(Paint.Style.FILL);
            }
            if (mMenuVisible) drawMenu(canvas);
        }

        private void drawIcon(Canvas canvas, float x, float y, float size) {
            if (mIcon == null) return;
            mIcon.setBounds(Math.round(x - size / 2), Math.round(y - size / 2),
                    Math.round(x + size / 2), Math.round(y + size / 2));
            mIcon.draw(canvas);
        }

        private void drawText(Canvas canvas, int resource, float x, float y, int size) {
            mPaint.setColor(mTextColor);
            mPaint.setTextAlign(Paint.Align.CENTER);
            mPaint.setTextSize(dp(size));
            canvas.drawText(getContext().getString(resource), x, y, mPaint);
        }

        private void drawMenu(Canvas canvas) {
            mPaint.setColor(mPreviewColor);
            canvas.drawRoundRect(mMenu, dp(20), dp(20), mPaint);
            final int[] labels = {R.string.hyperos_window_minimize,
                    R.string.hyperos_window_fullscreen, R.string.hyperos_window_close};
            for (int i = 0; i < labels.length; i++) {
                final float x = mMenu.left + mMenu.width() * (i + .5f) / 3;
                drawText(canvas, labels[i], x, mMenu.centerY() + dp(4), 12);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                setMenuVisible(false);
                return true;
            }
            final float x = event.getRawX();
            final float y = event.getRawY();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) return begin(x, y, event);
            if (mGesture == NONE) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                cancelGesture();
                return true;
            }
            if (mVelocity != null) mVelocity.addMovement(event);
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                if (!mMoved && Math.hypot(x - mStartX, y - mStartY) > mSlop) mMoved = true;
                if (mMoved) drag(x, y);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                // UP can contain the last movement sample, including a crossed threshold.
                if (!mMoved && Math.hypot(x - mStartX, y - mStartY) > mSlop) mMoved = true;
                if (mMoved) drag(x, y);
                finish(x, y);
            }
            return true;
        }

        private boolean begin(float x, float y, MotionEvent event) {
            if (!mTouchRegion.contains((int) x, (int) y)) return false;
            cancelGesture();
            mGesture = mSnapshot.docked ? DOCK : mSnapshot.pinned ? PREVIEW
                    : mMenuVisible && mMenu.contains(x, y) ? MENU
                    : mLeftCorner.contains(x, y) ? RESIZE_LEFT
                    : mRightCorner.contains(x, y) ? RESIZE_RIGHT
                    : mTop.contains(x, y) ? MOVE : BOTTOM;
            mStartX = x;
            mStartY = y;
            mStartBounds.set(mSnapshot.bounds);
            mStartScale = mSnapshot.scale;
            mStartCenter = new Point(mStartBounds.centerX(), mStartBounds.centerY());
            mVelocity = VelocityTracker.obtain();
            mVelocity.addMovement(event);
            PopUpWindowController.getInstance().focusHyperOsTask(mTask);
            return true;
        }

        private void drag(float x, float y) {
            if (mGesture == MOVE || mGesture == PREVIEW) {
                if (!mMovingTask) {
                    mMovingTask = true;
                    setMenuVisible(false);
                    PopUpWindowController.getInstance().startMovingTask(mTask, mStartX, mStartY);
                }
                PopUpWindowController.getInstance().updateMovingTask(mTask, x, y);
                setTarget(mGesture == MOVE && y < mSnapshot.display.top + dp(36)
                        ? TARGET_FULLSCREEN : TARGET_NONE);
            } else if (mGesture == RESIZE_LEFT || mGesture == RESIZE_RIGHT) {
                final float dx = (x - mStartX) * (mGesture == RESIZE_LEFT ? -1 : 1);
                final float dy = y - mStartY;
                final float ratio = 1f + (dx / mStartBounds.width()
                        + dy / mStartBounds.height()) * .5f;
                final float frameHeight = mStartBounds.height() + mStartBounds.width()
                        * (SmallWindowBounds.HYPER_OS_HEADER_HEIGHT_RATIO
                                + SmallWindowBounds.HYPER_OS_FOOTER_HEIGHT_RATIO);
                final float fit = Math.min(
                        (mSnapshot.available.width() - dp(24)) / mStartBounds.width(),
                        (mSnapshot.available.height() - dp(24)) / frameHeight);
                final float maximum = Math.max(.22f, Math.min(.94f, mStartScale * fit));
                final float scale = Math.max(.22f, Math.min(maximum, mStartScale * ratio));
                final float relative = scale / mStartScale;
                final float width = mStartBounds.width() * relative;
                final float height = mStartBounds.height() * relative;
                final float left = mGesture == RESIZE_LEFT
                        ? mStartBounds.right - width : mStartBounds.left;
                applyScale(scale, new Point(Math.round(left + width / 2),
                        Math.round(mStartBounds.top + height / 2)));
                setTarget(scale <= .40f ? TARGET_MINIMIZE : TARGET_NONE);
            } else if (mGesture == BOTTOM) {
                final float dy = y - mStartY;
                final float threshold = Math.max(dp(28),
                        Math.min(dp(64), (mSnapshot.display.bottom - mStartY - dp(24)) * .6f));
                setTarget(dy > threshold ? TARGET_FULLSCREEN
                        : dy < -dp(48) ? TARGET_CLOSE : TARGET_NONE);
                final float ratio = Math.max(.72f, Math.min(1.08f,
                        1 + dy / Math.max(1, mStartBounds.height()) * .5f));
                final float height = mStartBounds.height() * ratio;
                applyScale(mStartScale * ratio, new Point(mStartBounds.centerX(),
                        Math.round(mStartBounds.top + height / 2)));
            }
        }

        private void setTarget(int target) {
            if (mTarget == target) return;
            mTarget = target;
            if (target != TARGET_NONE) PopUpWindowController.getInstance().triggerVibrate();
            invalidate();
        }

        private void applyScale(float scale, Point center) {
            synchronized (mService.mGlobalLock) {
                final TaskWindowSurfaceInfo info = liveInfo();
                if (info == null) return;
                info.cancelPopUpViewAnimation();
                info.setWindowCenterPosition(center);
                info.setWindowSurfaceScale(scale);
                info.resetWindowBoundaryGap();
                final Rect bounds = info.getTaskWindowSurfaceBounds();
                final float realScale = info.getWindowSurfaceRealScale();
                final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
                t.setPosition(mTask.mSurfaceControl, bounds.left, bounds.top)
                            .setScale(mTask.mSurfaceControl, realScale, realScale)
                            .setCornerRadius(mTask.mSurfaceControl, info.getCornerRadius());
                HyperOsWindowDecor.this.applyTransaction(mTask, t);
                mService.requestTraversal();
            }
            HyperOsWindowDecor.this.update(mTask);
        }

        private TaskWindowSurfaceInfo liveInfo() {
            if (!canShow(mTask) || mTask.mSurfaceControl == null
                    || !mTask.mSurfaceControl.isValid()) return null;
            return mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
        }

        private void finish(float x, float y) {
            final int gesture = mGesture;
            final boolean moved = mMoved;
            int target = mTarget;
            if (mVelocity != null && gesture == BOTTOM && moved) {
                mVelocity.computeCurrentVelocity(1000);
                if (mVelocity.getYVelocity() < -dp(900) && y < mStartY - mSlop) {
                    target = TARGET_CLOSE;
                }
            }
            if (mMovingTask) {
                if (target == TARGET_FULLSCREEN) {
                    synchronized (mService.mGlobalLock) {
                        final TaskWindowSurfaceInfo info = liveInfo();
                        if (info != null) info.resetDraggingState();
                    }
                } else {
                    PopUpWindowController.getInstance().finishMovingTask(mTask);
                }
            }
            resetGesture();
            if (gesture == DOCK && !moved) {
                synchronized (mService.mGlobalLock) {
                    final TaskWindowSurfaceInfo info = liveInfo();
                    if (info != null) info.restoreFromDocked();
                }
            } else if (gesture == PREVIEW && !moved) {
                PopUpWindowController.getInstance().enterMiniWindowingMode(mTask);
            } else if (gesture == MOVE && !moved) {
                setMenuVisible(!mMenuVisible);
            } else if (gesture == MENU && !moved && mMenu.contains(x, y)) {
                final int index = Math.min(2, (int) ((x - mMenu.left) * 3 / mMenu.width()));
                performTarget(index == 0 ? TARGET_MINIMIZE
                        : index == 1 ? TARGET_FULLSCREEN : TARGET_CLOSE);
            } else if (moved && target != TARGET_NONE) {
                if (target == TARGET_MINIMIZE
                        && (gesture == RESIZE_LEFT || gesture == RESIZE_RIGHT)) {
                    synchronized (mService.mGlobalLock) {
                        final TaskWindowSurfaceInfo info = liveInfo();
                        if (info != null) info.preserveHyperOsMiniPlacement(mStartCenter, mStartScale);
                    }
                }
                performTarget(target);
            } else if (moved && gesture == BOTTOM) {
                settleToStart();
            } else if (moved && (gesture == RESIZE_LEFT || gesture == RESIZE_RIGHT)) {
                synchronized (mService.mGlobalLock) {
                    final TaskWindowSurfaceInfo info = liveInfo();
                    if (info != null) info.finishHyperOsResize();
                }
            }
            HyperOsWindowDecor.this.update(mTask);
        }

        private void performTarget(int target) {
            setMenuVisible(false);
            switch (target) {
                case TARGET_MINIMIZE:
                    PopUpWindowController.getInstance().enterPinnedWindowingMode(mTask);
                    break;
                case TARGET_FULLSCREEN:
                    PopUpWindowController.getInstance().exitMiniWindowingMode(mTask);
                    break;
                case TARGET_CLOSE:
                    mClosing = true;
                    updateRegions();
                    requestLayout();
                    invalidate();
                    PopUpWindowController.getInstance().closeHyperOsTask(mTask);
                    break;
            }
        }

        private void settleToStart() {
            synchronized (mService.mGlobalLock) {
                final TaskWindowSurfaceInfo info = liveInfo();
                if (info == null || mStartCenter == null) return;
                final Rect from = info.getTaskWindowSurfaceBounds();
                final float fromScale = info.getWindowSurfaceRealScale();
                info.setWindowCenterPosition(mStartCenter);
                info.setWindowSurfaceScale(mStartScale);
                final Rect to = info.getTaskWindowSurfaceBounds();
                info.playToggleResizeWindowAnimation(new Point(from.left, from.top),
                        new Point(to.left, to.top), fromScale, info.getWindowSurfaceRealScale(),
                        () -> HyperOsWindowDecor.this.update(mTask));
            }
        }

        void cancelGesture() {
            if (mMovingTask) PopUpWindowController.getInstance().cancelMovingTask(mTask);
            else if (mMoved && (mGesture == BOTTOM || mGesture == RESIZE_LEFT
                    || mGesture == RESIZE_RIGHT)) settleToStart();
            resetGesture();
        }

        private void resetGesture() {
            mGesture = NONE;
            mMoved = false;
            mMovingTask = false;
            mTarget = TARGET_NONE;
            if (mVelocity != null) {
                mVelocity.recycle();
                mVelocity = null;
            }
            if (mSnapshot != null) {
                updateRegions();
                requestLayout();
                invalidate();
            }
        }

        private void setMenuVisible(boolean visible) {
            if (mMenuVisible == visible) return;
            mMenuVisible = visible;
            updateRegions();
            requestLayout();
            invalidate();
        }

        @Override
        public boolean performClick() {
            super.performClick();
            PopUpWindowController.getInstance().focusHyperOsTask(mTask);
            if (mSnapshot.docked) {
                synchronized (mService.mGlobalLock) {
                    final TaskWindowSurfaceInfo info = liveInfo();
                    if (info != null) info.restoreFromDocked();
                }
            } else if (mSnapshot.pinned) {
                PopUpWindowController.getInstance().enterMiniWindowingMode(mTask);
            } else {
                setMenuVisible(!mMenuVisible);
            }
            return true;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_EXPAND,
                    getContext().getString(R.string.hyperos_window_fullscreen)));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_COLLAPSE,
                    getContext().getString(R.string.hyperos_window_minimize)));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_DISMISS,
                    getContext().getString(R.string.hyperos_window_close)));
            info.setBoundsInScreen(new Rect(mSnapshot.bounds));
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_EXPAND) {
                performTarget(TARGET_FULLSCREEN);
            } else if (action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
                performTarget(TARGET_MINIMIZE);
            } else if (action == AccessibilityNodeInfo.ACTION_DISMISS) {
                performTarget(TARGET_CLOSE);
            } else if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                return performClick();
            } else {
                return super.performAccessibilityAction(action, arguments);
            }
            return true;
        }
    }
}
