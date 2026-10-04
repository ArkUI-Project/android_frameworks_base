/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.server.wm.WindowResizingAlgorithm.BOUNDARY_GAP;

import static com.android.internal.arkui.DebugConstants.DEBUG_POP_UP;

import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.IWindow;
import android.view.InsetsState;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.MotionEvent;

import com.android.internal.R;

import com.android.server.wm.Transition.ChangeInfo.PopUpViewInfo;

import java.util.Arrays;
import java.util.List;

class TaskWindowSurfaceInfo {

    private static final String TAG = "TaskWindowSurfaceInfo";

    private static final int DENSITY_DEFAULT = 420;

    private static final float SLIDE_TO_EDGE_THRESHOLD_RATIO = 0.65f;
    private static final float DOCKED_ALPHA = 0f;
    private static final float NORMAL_ALPHA = 1.0f;

    private final List<String> mForceUpdateDpiList;

    private final PopUpAnimationController mPopUpAnimationController;

    private final Configuration mConfiguration = new Configuration();
    private final TransitionInfoExt mTransitionInfoExt = new TransitionInfoExt();
    private boolean mTransitionGeometryPrepared;
    private final Rect mGestureStartBounds = new Rect();
    private long mGestureStartTime;
    private final Point mWindowCenterPosition;
    private final PointF mPinnedWindowVerticalPosRatio;
    private final Rect mWindowBoundaryGap;

    private float mCornerRadius;
    private float mPinnedWindowCornerRadius;
    private float mMiniWindowCornerRadius;
    private boolean mIsPinnedWindowSmall = true;


    private float mWindowSurfaceScale;
    private float mWindowSurfaceScaleFactor;

    final WindowManagerService mService;
    final Task mTask;

    int mFreezedWindowingMode = WINDOWING_MODE_UNDEFINED;

    private boolean mIsDockedToEdge = false;
    // Guarded by the WM global lock; applied only after the docking animation finishes.
    private boolean mDockedInBackground;
    private boolean mIsDockedLeft = true;
    private float mOriginalScaleBeforeDock = 1.0f;
    private float mOriginalCornerRadiusBeforeDock = 0f;

    private int mLastSurfaceX = Integer.MIN_VALUE;
    private int mLastSurfaceY = Integer.MIN_VALUE;
    private float mLastSurfaceScale = -1.0f;
    private final int[] mAnimationPosition = new int[2];
    private final float[] mAnimationScale = new float[1];
    private boolean mSurfaceAnimating;
    private boolean mNeedsFullscreenSurfaceReset;

    TaskWindowSurfaceInfo(Task task) {
        mTask = task;
        mService = task.mWmService;
        mIsPinnedWindowSmall = PopUpSettingsConfig.getInstance().isPinnedSmallByDefault();

        mConfiguration.setTo(mTask.getConfiguration());

        mForceUpdateDpiList = Arrays.asList(mService.mContext.getResources()
                .getStringArray(R.array.config_popUpView_forceUpdateDpiPackages));

        mWindowCenterPosition = new Point();
        mPinnedWindowVerticalPosRatio = new PointF(0.2f, 0.2f);
        setWindowSurfaceScale(1.0f);
        mWindowSurfaceScaleFactor = 1.0f;
        mWindowBoundaryGap = new Rect(0, BOUNDARY_GAP, BOUNDARY_GAP, 0);

        mMiniWindowCornerRadius = mService.mContext.getResources()
                .getDimensionPixelSize(R.dimen.mini_window_corner_radius);
        mPinnedWindowCornerRadius = mService.mContext.getResources()
                .getDimensionPixelSize(R.dimen.pinned_window_corner_radius);
        mCornerRadius = mMiniWindowCornerRadius;

        mPopUpAnimationController = new PopUpAnimationController(mService);
        mPopUpAnimationController.setTask(task);
    }

    TaskWindowSurfaceInfo(TaskWindowSurfaceInfo other, int preFreezedWindowingMode) {
        mTask = other.mTask;
        mService = other.mService;

        mFreezedWindowingMode = preFreezedWindowingMode;
        mConfiguration.setTo(other.mConfiguration);

        mForceUpdateDpiList = other.mForceUpdateDpiList;

        mWindowCenterPosition = other.getWindowCenterPosition();
        mPinnedWindowVerticalPosRatio = other.getPinnedWindowVerticalPosRatioPointF();
        mWindowSurfaceScale = other.getWindowSurfaceScale();
        mWindowSurfaceScaleFactor = other.getWindowSurfaceScaleFactor();
        mWindowBoundaryGap = other.getWindowBoundaryGap();

        mCornerRadius = other.getCornerRadius();
        mIsPinnedWindowSmall = other.isPinnedWindowSmall();

        // Frozen geometry never owns an animation; reuse its owner's controller.
        mPopUpAnimationController = other.mPopUpAnimationController;
    }

    void setFrozenTaskBounds(Rect bounds) {
        mConfiguration.windowConfiguration.setBounds(bounds);
    }

    void setWindowCenterPosition(Point pos) {
        mWindowCenterPosition.set(pos.x, pos.y);
    }

    Point getWindowCenterPosition() {
        return new Point(mWindowCenterPosition.x, mWindowCenterPosition.y);
    }

    void setWindowPlacementFrom(TaskWindowSurfaceInfo other) {
        final Rect bounds = mTask.getBounds();
        if (bounds.isEmpty() || mTask.mDisplayContent == null) {
            return;
        }
        final Rect displayBounds = mTask.mDisplayContent.getBounds();
        mWindowCenterPosition.set(other.mWindowCenterPosition);
        final int orientation = mTask.getConfiguration().orientation;
        final int otherOrientation = other.mConfiguration.orientation;
        final float defaultScale;
        final float otherDefaultScale;
        if (mTask.getWindowConfiguration().isPinnedExtWindowMode()) {
            mWindowBoundaryGap.set(other.mWindowBoundaryGap);
            mPinnedWindowVerticalPosRatio.set(other.mPinnedWindowVerticalPosRatio);
            mIsPinnedWindowSmall = other.mIsPinnedWindowSmall;
            mWindowSurfaceScaleFactor = 1f;
            defaultScale = WindowResizingAlgorithm.getDefaultPinnedWindowScale(
                    orientation, mIsPinnedWindowSmall);
            otherDefaultScale = WindowResizingAlgorithm.getDefaultPinnedWindowScale(
                    otherOrientation, mIsPinnedWindowSmall);
        } else {
            resetWindowBoundaryGap();
            mWindowSurfaceScaleFactor = WindowResizingAlgorithm
                    .getPositionAndScaleFactorForTask(bounds, displayBounds,
                            mWindowCenterPosition, 1f, false, new Point());
            final int rotation = mTask.mDisplayContent.getRotation();
            defaultScale = WindowResizingAlgorithm.getDefaultMiniWindowScale(
                    orientation, rotation);
            otherDefaultScale = WindowResizingAlgorithm.getDefaultMiniWindowScale(
                    otherOrientation, rotation);
        }
        // Transfer the user's size relative to that mode's orientation-specific default.
        // Fitting each app inside the other's rectangle would shrink mixed orientations
        // again on every swap, and rounding the visible rectangle would accumulate drift.
        mWindowSurfaceScale = other.mWindowSurfaceScale * defaultScale / otherDefaultScale;
        // An earlier entry gesture must not override the start of this mode exchange.
        mGestureStartBounds.setEmpty();
    }

    PopUpViewInfo getPopUpViewInfo() {
        if (!mTransitionGeometryPrepared && mTask.getWindowConfiguration().isMiniExtWindowMode()
                && mTask.mDisplayContent != null) {
            // Notification launches can join an OPEN transition without a separate
            // resize freeze. Supply their resting geometry to Shell as well.
            mTransitionInfoExt.setupPopUpViewInfo(this, this,
                    mTask.mDisplayContent.getDisplayInfo());
        }
        mTransitionGeometryPrepared = false;
        final PopUpViewInfo info = mTransitionInfoExt.getPopUpViewInfo();
        if (!mGestureStartBounds.isEmpty() && !info.mWindowCrop.isEmpty()
                && android.os.SystemClock.uptimeMillis() - mGestureStartTime < 1500) {
            info.mStartPos.set(mGestureStartBounds.left, mGestureStartBounds.top);
            info.mStartScale = (float) mGestureStartBounds.width() / info.mWindowCrop.width();
            info.mStartCornerRadius = info.mEndCornerRadius;
        }
        mGestureStartBounds.setEmpty();
        return info;
    }

    void setGestureStartBounds(Rect bounds) {
        mGestureStartBounds.set(bounds);
        mGestureStartTime = android.os.SystemClock.uptimeMillis();
    }

    void setWindowSurfaceScaleDrag(float scale, Rect displayBound, boolean isLandscape) {
        if (mWindowSurfaceScale != scale) {
            mWindowSurfaceScale = scale;
            if (mTask.getWindowConfiguration().isPinnedExtWindowMode()) {
                final Rect result = new Rect();
                final Point pos = new Point();
                final Rect bound = mTask.getBounds();
                WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                        bound, displayBound, mWindowCenterPosition, mWindowSurfaceScale,
                        false, pos);
                result.set(0, 0, bound.width(), bound.height());
                result.scale(getWindowSurfaceRealScale());
                result.offsetTo(pos.x, pos.y);
                DimmerWindow.getInstance().onDragResizeChanged(scale, result, isLandscape);
            } else {
                DimmerWindow.getInstance().onDragResizeChanged(scale,
                        getTaskWindowSurfaceBoundsOnDrag(displayBound), isLandscape);
            }
        }
    }

    void setWindowSurfaceScale(float scale) {
        if (scale == WindowResizingAlgorithm.PINNED_WINDOW_SCALE_SMALL_PORT
                || scale == WindowResizingAlgorithm.PINNED_WINDOW_SCALE_SMALL_LAND) {
            mIsPinnedWindowSmall = true;
        } else if (scale == WindowResizingAlgorithm.PINNED_WINDOW_SCALE_LARGE_PORT
                || scale == WindowResizingAlgorithm.PINNED_WINDOW_SCALE_LARGE_LAND) {
            mIsPinnedWindowSmall = false;
        }
        if (mWindowSurfaceScale != scale) {
            mWindowSurfaceScale = scale;
            DimmerWindow.getInstance().onResizeChanged();
        }
    }

    float getWindowSurfaceScale() {
        return mWindowSurfaceScale;
    }

    float getWindowSurfaceRealScale() {
        return mWindowSurfaceScale * mWindowSurfaceScaleFactor;
    }

    float getWindowSurfaceRealScale(float scale) {
        return scale * mWindowSurfaceScaleFactor;
    }

    void setWindowSurfaceScaleFactor(float factor) {
        mWindowSurfaceScaleFactor = factor;
    }

    float getWindowSurfaceScaleFactor() {
        return mWindowSurfaceScaleFactor;
    }

    void resetWindowBoundaryGap() {
        mWindowBoundaryGap.setEmpty();
    }

    float getCornerRadius() {
        return mCornerRadius;
    }

    void setCornerRadius(float cornerRadius) {
        mCornerRadius = cornerRadius;
    }

    float getMiniWindowCornerRadius() {
        return mMiniWindowCornerRadius;
    }

    void setPinnedWindowVerticalPosRatio(Point pos, Rect displayBound, boolean isDrag) {
        if (displayBound != null && displayBound.height() > 0) {
            final float ratio = (float) pos.y / displayBound.height();
            if (isDrag) {
                mPinnedWindowVerticalPosRatio.set(ratio, ratio);
            } else if (displayBound.height() > displayBound.width()) {
                mPinnedWindowVerticalPosRatio.x = ratio;
            } else {
                mPinnedWindowVerticalPosRatio.y = ratio;
            }
        }
    }

    float getPinnedWindowVerticalPosRatio(Rect displayBound) {
        return displayBound.height() > displayBound.width() ?
                mPinnedWindowVerticalPosRatio.x : mPinnedWindowVerticalPosRatio.y;
    }

    PointF getPinnedWindowVerticalPosRatioPointF() {
        return new PointF(mPinnedWindowVerticalPosRatio.x, mPinnedWindowVerticalPosRatio.y);
    }

    void resetWindowBoundaryGapToOrigin() {
        mWindowBoundaryGap.set(0, BOUNDARY_GAP * 2, BOUNDARY_GAP, 0);
    }

    void setWindowBoundaryGap(int left, int top, int right, int bottom) {
        mWindowBoundaryGap.set(left, top, right, bottom);
    }

    Rect getWindowBoundaryGap() {
        return new Rect(mWindowBoundaryGap.left, mWindowBoundaryGap.top,
                mWindowBoundaryGap.right, mWindowBoundaryGap.bottom);
    }

    Rect getSurfaceTaskBounds() {
        return mFreezedWindowingMode != WINDOWING_MODE_UNDEFINED
                ? mConfiguration.windowConfiguration.getBounds() : mTask.getBounds();
    }

    Rect getTaskWindowSurfaceBounds() {
        int windowingMode = mFreezedWindowingMode;
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            windowingMode = mTask.getConfiguration().windowConfiguration.getWindowingMode();
        }
        final Rect result = new Rect();
        if (WindowConfiguration.isPopUpWindowMode(windowingMode)) {
            final Rect displayBound = new Rect();
            if (mTask.mDisplayContent != null) {
                mTask.mDisplayContent.getBounds(displayBound);
            }
            final Point pos = new Point();
            final Rect bound = getSurfaceTaskBounds();
            final boolean isPinned = WindowConfiguration.isPinnedExtWindowMode(windowingMode);
            if (isPinned) {
                if (mIsDragging) {
                    final Point center = getWindowCenterPosition();
                    result.set(0, 0, bound.width(), bound.height());
                    result.scale(mWindowSurfaceScale);
                    result.offsetTo(center.x - result.width() / 2,
                            center.y - result.height() / 2);
                } else {
                    final float verticalPosRatio = getPinnedWindowVerticalPosRatio(displayBound);
                    WindowResizingAlgorithm.getCenterByBoundaryGap(bound, displayBound,
                            getWindowBoundaryGap(), verticalPosRatio, getWindowCenterPosition(),
                            getWindowSurfaceScale(), pos);
                    result.set(0, 0, bound.width(), bound.height());
                    result.scale(mWindowSurfaceScale);
                    result.offsetTo(pos.x - result.width() / 2, pos.y - result.height() / 2);
                }
            } else {
                mWindowSurfaceScaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                        bound, displayBound, mWindowCenterPosition, mWindowSurfaceScale,
                        false, pos);
                result.set(0, 0, bound.width(), bound.height());
                result.scale(getWindowSurfaceRealScale());
                result.offsetTo(pos.x, pos.y);
            }
        }
        return result;
    }

    Rect getTaskWindowSurfaceBoundsOnDrag(Rect displayBound) {
        final Rect result = new Rect();
        final Point pos = new Point();
        final Rect bound = mTask.getBounds();
        final boolean isPinned = mTask.getWindowConfiguration().isPinnedExtWindowMode();
        if (!isPinned) {
            mWindowSurfaceScaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                    bound, displayBound, mWindowCenterPosition, mWindowSurfaceScale,
                    false, pos);
        } else {
            WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                    bound, displayBound, mWindowCenterPosition, mWindowSurfaceScale,
                    false, pos);
        }
        result.set(0, 0, bound.width(), bound.height());
        result.scale(getWindowSurfaceRealScale());
        result.offsetTo(pos.x, pos.y);
        return result;
    }

    void onWindowingModeChanged(int preWindowMode) {
        final WindowConfiguration winConfig = mTask.getConfiguration().windowConfiguration;
        final boolean isMiniWindow = winConfig.isMiniExtWindowMode();
        final boolean isPopUpWindow = winConfig.isPopUpWindowMode();
        final boolean isPrevMiniWindow = WindowConfiguration.isMiniExtWindowMode(preWindowMode);
        final boolean isPrevPopUpWindow = WindowConfiguration.isPopUpWindowMode(preWindowMode);
        if (!winConfig.isPinnedExtWindowMode()) {
            // Fullscreen and interactive small windows must not inherit the docked policy.
            mDockedInBackground = false;
            mIsDockedToEdge = false;
        }
        if (isPopUpWindow && !isPrevPopUpWindow) {
            mIsPinnedWindowSmall = PopUpSettingsConfig.getInstance().isPinnedSmallByDefault();
        }
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "onWindowingModeChanged " + preWindowMode + "->"
                    + winConfig.getWindowingMode() + " mTask=" + mTask);
        }
        if (!isPopUpWindow && isPrevPopUpWindow) {
            mNeedsFullscreenSurfaceReset = true;
            PopUpWindowController.getInstance().releaseTaskMute(mTask);
            resetDraggingState();
            final IWindow window = getIWindow();
            if (window != null) {
                finishTaskPositioning(window);
            }
            if (cancelPopUpViewAnimation()) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "cancel PopUpViewAnimation when exit PopupView. mTask=" + mTask);
                }
            }
        }
        if (isMiniWindow) {
            resetDraggingState();
            if (isPrevPopUpWindow && !isPrevMiniWindow) {
            } else {
                final Rect displayBound = new Rect();
                if (mTask.mDisplayContent != null) {
                    mTask.mDisplayContent.getBounds(displayBound);
                    final Point pos = new Point(displayBound.centerX(), displayBound.centerY());
                    setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultMiniWindowScale(
                            mTask.getConfiguration().orientation, mTask.mDisplayContent.getRotation()));
                    setWindowCenterPosition(pos);
                }
            }
            mCornerRadius = mMiniWindowCornerRadius;
        } else if (mTask.getWindowConfiguration().isPinnedExtWindowMode()) {
            mCornerRadius = mPinnedWindowCornerRadius;
            if (!isPrevPopUpWindow) {
                resetWindowBoundaryGapToOrigin();
                final Rect displayBound = new Rect();
                if (mTask.mDisplayContent != null) {
                    mTask.mDisplayContent.getBounds(displayBound);
                }
                if (!displayBound.isEmpty()) {
                    setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultPinnedWindowScale(
                            mTask.getConfiguration().orientation, mIsPinnedWindowSmall));
                    final Point pos = new Point();
                    final Rect bounds = mTask.getBounds();
                    WindowResizingAlgorithm.getCenterByBoundaryGap(
                            bounds, displayBound, getWindowBoundaryGap(),
                            getPinnedWindowVerticalPosRatio(displayBound),
                            getWindowCenterPosition(),
                            getWindowSurfaceScale(), pos);
                    setWindowCenterPosition(pos);
                    setWindowSurfaceScaleFactor(1.0f);
                }
            }
        }
        if (isPopUpWindow || isPrevPopUpWindow) {
            final SurfaceControl surfaceControl = mTask.getSurfaceControl();
            if (surfaceControl != null && surfaceControl.isValid()) {
                mService.mTransactionFactory.get().setTrustedOverlay(surfaceControl, isPopUpWindow).apply();
            }
            updateDensityIfNeed(isPrevPopUpWindow && !isPopUpWindow);
        }
    }

    private void updateDensityIfNeed(boolean isExitPopUpView) {
        if (mTask.getWindowConfiguration().isPopUpWindowMode()) {
            final int initDensity = mTask.mDisplayContent == null ?
                    DENSITY_DEFAULT : mTask.mDisplayContent.mInitialDisplayDensity;
            if (mTask.getConfiguration().densityDpi < initDensity) {
                final ActivityRecord topActivity = mTask.topRunningActivityLocked();
                if (topActivity == null || !mForceUpdateDpiList.contains(topActivity.packageName)) {
                    return;
                }
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "force update density for " + mTask +
                            " densityDpi=" + mTask.getConfiguration().densityDpi);
                }
                mTask.getRequestedOverrideConfiguration().densityDpi = initDensity;
                mTask.onRequestedOverrideConfigurationChanged(mTask.getRequestedOverrideConfiguration());
            }
        } else if (isExitPopUpView && mTask.getRequestedOverrideConfiguration().densityDpi != 0) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "reset density for " + mTask +
                        " densityDpi=" + mTask.getRequestedOverrideConfiguration().densityDpi);
            }
            mTask.getRequestedOverrideConfiguration().densityDpi = 0;
            mTask.onRequestedOverrideConfigurationChanged(mTask.getRequestedOverrideConfiguration());
        }
    }

    void onRotationChanged() {
        if (mTask.mDisplayContent == null) {
            return;
        }
        final Rect displayBound = new Rect();
        mTask.mDisplayContent.getBounds(displayBound);
        if (displayBound.isEmpty()) {
            return;
        }

        if (mTask.getConfiguration().windowConfiguration.isMiniExtWindowMode()) {
            final Point pos = new Point(displayBound.width() / 2, displayBound.height() / 2);
            setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultMiniWindowScale(
                    mTask.getConfiguration().orientation, mTask.mDisplayContent.getRotation()));
            setWindowCenterPosition(pos);
        } else if (mTask.getConfiguration().windowConfiguration.isPinnedExtWindowMode()) {
            if (mIsDockedToEdge) {
                updateDockedPositionAfterRotation(displayBound);
            } else {
                setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultPinnedWindowScale(
                        mTask.getConfiguration().orientation, mIsPinnedWindowSmall));
                final Point pos = new Point();
                WindowResizingAlgorithm.getCenterByBoundaryGap(
                        mTask.getBounds(), displayBound, getWindowBoundaryGap(),
                        getPinnedWindowVerticalPosRatio(displayBound),
                        getWindowCenterPosition(),
                        getWindowSurfaceScale(), pos);
                setWindowCenterPosition(pos);
                setWindowSurfaceScaleFactor(1.0f);
                final Rect surfaceBounds = getTaskWindowSurfaceBounds();
                PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                        mTask, surfaceBounds.left, surfaceBounds.top,
                        mTask.getBounds().width(), mTask.getBounds().height(), mWindowSurfaceScale);
                mLastSurfaceX = Integer.MIN_VALUE;
                mLastSurfaceY = Integer.MIN_VALUE;
                mLastSurfaceScale = -1.0f;
            }
        }
    }

    private int dockedTouchWidth() {
        return Math.round(24 * mService.mContext.getResources().getDisplayMetrics().density);
    }

    private int dockedTouchHeight() {
        return Math.round(96 * mService.mContext.getResources().getDisplayMetrics().density);
    }

    private Rect getDockedBounds(Rect display) {
        final int width = dockedTouchWidth();
        final int height = Math.min(dockedTouchHeight(), display.height());
        final int left = mIsDockedLeft ? display.left : display.right - width;
        final int top = Math.max(display.top, Math.min(
                mWindowCenterPosition.y - height / 2, display.bottom - height));
        return new Rect(left, top, left + width, top + height);
    }

    boolean isDockedLeft() {
        return mIsDockedLeft;
    }

    private void updateDockedPositionAfterRotation(Rect displayBound) {
        final Rect dock = getDockedBounds(displayBound);
        mWindowCenterPosition.set(dock.centerX(), dock.centerY());
        applyDockedSurfaceState(mTask.getSyncTransaction());
    }

    void onConfigurationChanged() {
        final Configuration newConfig = mTask.getConfiguration();
        final long diff = mConfiguration.windowConfiguration.diff(newConfig.windowConfiguration, false);
        if ((WindowConfiguration.WINDOW_CONFIG_BOUNDS & diff) != 0
                || (WindowConfiguration.WINDOW_CONFIG_ROTATION & diff) != 0) {
            if (mTask.getWindowConfiguration().isMiniExtWindowMode()
                    && mTask.mDisplayContent != null) {
                setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultMiniWindowScale(
                        mTask.getConfiguration().orientation, mTask.mDisplayContent.getRotation()));
            }
            DimmerWindow.getInstance().onResizeChanged();
        }
        if ((mConfiguration.diff(newConfig) & ActivityInfo.CONFIG_DENSITY) != 0) {
            updateDensityIfNeed(false);
        }
        mConfiguration.setTo(newConfig);
    }

    void onPrepareSurfaces(SurfaceControl.Transaction t) {
        final WindowConfiguration winConfig = mTask.getConfiguration().windowConfiguration;
        // Descendants inherit the mode, but their positions remain relative to the root task.
        // Applying another popup transform here scales and crops the app a second time.
        if (winConfig.isPopUpWindowMode() && !mTask.isRootTask()) {
            return;
        }
        final boolean isPinned = winConfig.isPinnedExtWindowMode();
        final boolean animating = mPopUpAnimationController.isAnimating();
        if (isPinned && !mIsDragging && !mIsDockedToEdge) {
            if (mPopUpAnimationController.getCurrentAnimPosition(mAnimationPosition, mAnimationScale)) {
                PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                        mTask, mAnimationPosition[0], mAnimationPosition[1], mTask.getBounds().width(),
                        mTask.getBounds().height(), mAnimationScale[0]);
                if (!animating) {
                    mPopUpAnimationController.clearAnimPosition();
                }
            }
        }

        final boolean hasAnimationLeash = hasTaskSurfaceAnimationLeash() ||
                animating ||
                mTask.mTransitionController.inPlayingTransition(mTask);
        if (mNeedsFullscreenSurfaceReset && !hasAnimationLeash
                && !mTask.mTransitionController.isCollecting(mTask)
                && !winConfig.isPopUpWindowMode()
                && mTask.mSurfaceControl != null && mTask.mSurfaceControl.isValid()) {
            mTask.resetSurfaceControlTransforms();
            // Dragging writes the surface directly without changing WindowContainer's cache.
            // Force a parent-relative position write even if fullscreen bounds are unchanged.
            mTask.mLastSurfacePosition.set(Integer.MIN_VALUE, Integer.MIN_VALUE);
            mTask.updateSurfacePosition(t);
            mWindowSurfaceScale = 1f;
            mWindowSurfaceScaleFactor = 1f;
            mNeedsFullscreenSurfaceReset = false;
        }
        if (hasAnimationLeash != mSurfaceAnimating) {
            mSurfaceAnimating = hasAnimationLeash;
            DimmerWindow.getInstance().onTaskSurfaceAnimationChanged(mTask, hasAnimationLeash);
        }
        if (winConfig.isPopUpWindowMode() && !hasAnimationLeash &&
                !isWindowPositioningLocked() && !mIsDragging && !isPinned) {
            final Rect displayBound = new Rect();
            if (mTask.mDisplayContent != null) {
                mTask.mDisplayContent.getBounds(displayBound);
            }
            final Point pos = new Point();
            final Rect bound = mTask.getBounds();
            mWindowSurfaceScaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                    bound, displayBound, mWindowCenterPosition, mWindowSurfaceScale,
                    false, pos);
            t.setPosition(mTask.mSurfaceControl, pos.x, pos.y)
                    .setWindowCrop(mTask.mSurfaceControl, bound.width(), bound.height())
                    .setCornerRadius(mTask.mSurfaceControl, mCornerRadius)
                    .setScale(mTask.mSurfaceControl, getWindowSurfaceRealScale(), getWindowSurfaceRealScale());
            if (pos.x != mLastSurfaceX || pos.y != mLastSurfaceY
                    || mLastSurfaceScale != getWindowSurfaceRealScale()) {
                mLastSurfaceX = pos.x;
                mLastSurfaceY = pos.y;
                mLastSurfaceScale = getWindowSurfaceRealScale();
                DimmerWindow.getInstance().onResizeChanged();
            }
            if (mIsDockedToEdge) {
                t.setAlpha(mTask.mSurfaceControl, DOCKED_ALPHA);
            } else {
                t.setAlpha(mTask.mSurfaceControl, NORMAL_ALPHA);
            }
            t.show(mTask.mSurfaceControl);
        }
        if (isPinned && !hasAnimationLeash && !isWindowPositioningLocked() && !mIsDragging
                && mTask.mSurfaceControl != null && mTask.mSurfaceControl.isValid()) {
            if (mIsDockedToEdge) {
                applyDockedSurfaceState(t);
                return;
            }
            final Rect surfaceBounds = getTaskWindowSurfaceBounds();
            // A transition can reset the matrix without changing our cached bounds.
            // Reapply the complete resting state after animation ownership returns to WM.
            {
                t.setPosition(mTask.mSurfaceControl, surfaceBounds.left, surfaceBounds.top)
                        .setWindowCrop(mTask.mSurfaceControl, mTask.getBounds().width(), mTask.getBounds().height())
                        .setCornerRadius(mTask.mSurfaceControl, mCornerRadius)
                        .setScale(mTask.mSurfaceControl, mWindowSurfaceScale, mWindowSurfaceScale)
                        .setAlpha(mTask.mSurfaceControl, mIsDockedToEdge ? DOCKED_ALPHA : NORMAL_ALPHA)
                        .show(mTask.mSurfaceControl);
                if (surfaceBounds.left != mLastSurfaceX || surfaceBounds.top != mLastSurfaceY
                        || mLastSurfaceScale != mWindowSurfaceScale) {
                    PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                            mTask, surfaceBounds.left, surfaceBounds.top,
                            mTask.getBounds().width(), mTask.getBounds().height(), mWindowSurfaceScale);
                }
                mLastSurfaceX = surfaceBounds.left;
                mLastSurfaceY = surfaceBounds.top;
                mLastSurfaceScale = mWindowSurfaceScale;
            }
        }
    }

    private void applyDockedSurfaceState(SurfaceControl.Transaction t) {
        if (mTask.mDisplayContent == null) return;
        final Rect dock = getDockedBounds(mTask.mDisplayContent.getBounds());
        // The app is entirely hidden. The trusted overlay draws a neutral edge tab,
        // so no cropped app contents remain visible at the screen edge.
        t.setAlpha(mTask.mSurfaceControl, 0f);
        PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                mTask, dock.left, dock.top, dock.width(), dock.height(), 1f);
    }

    void scheduleTransition(TaskWindowSurfaceInfo freezeTaskWindowSurfaceInfo, DisplayInfo displayInfo) {
        mTransitionInfoExt.setupPopUpViewInfo(freezeTaskWindowSurfaceInfo, this, displayInfo);
        mTransitionGeometryPrepared = true;
        if (!mTask.mTransitionController.isShellTransitionsEnabled()) {
            final PopUpViewInfo info = mTransitionInfoExt.getPopUpViewInfo();
            final Rect displayBound = new Rect(0, 0, displayInfo.appWidth, displayInfo.appHeight);
            final int boundWidth = info.mWindowCrop.width();
            final int boundHeight = info.mWindowCrop.height();
            final boolean isLandscape = displayInfo.rotation == Surface.ROTATION_90
                    || displayInfo.rotation == Surface.ROTATION_270;
            mPopUpAnimationController.playResizeAnimation(
                    info.mStartPos, info.mEndPos, boundWidth, boundHeight,
                    info.mStartScale, info.mEndScale, displayBound, isLandscape, this);
        }
    }

    boolean isCrossOverAnimating() {
        return mPopUpAnimationController.isCrossOverAnimating();
    }

    void playExitAnimation(boolean isFromLeaveButton, float startScale,
            PopUpAnimationController.OnAnimationEndCallback callback) {
        mPopUpAnimationController.playExitAnimation(
                isFromLeaveButton, startScale, callback);
    }

    void resizeWindowWithAnimation(Point startPos, Point endPos, int boundWidth,
            int boundHeight, float startWinScale, float endWinScale,
            Rect displayBound, boolean isLandscape) {
        mPopUpAnimationController.playResizeAnimation(startPos, endPos,
                boundWidth, boundHeight, startWinScale, endWinScale,
                displayBound, isLandscape, this);
    }

    void playToggleResizeWindowAnimation(Point startPos, Point endPos, float startWinScale,
            float endWinScale, PopUpAnimationController.OnAnimationEndCallback callback) {
        mPopUpAnimationController.playToggleResizeWindowAnimation(
                startPos, endPos, startWinScale, endWinScale, callback);
    }

    void flingWindowToEdge(Point startPos, Point endPos, int boundWidth, int boundHeight,
            float winScale, float velX, float velY) {
        mPopUpAnimationController.playSpringAnimation(
                startPos, endPos, boundWidth, boundHeight, winScale, velX, velY);
    }

    boolean cancelPopUpViewAnimation() {
        return mPopUpAnimationController.cancelAnimation();
    }

    private void finishTaskPositioning(IWindow window) {
        try {
            if (mService != null && window != null) {
                synchronized (mService.mGlobalLock) {
                }
            }
        } catch (Exception e) {
            if (DEBUG_POP_UP) {
                Slog.w(TAG, "Failed to finish task positioning", e);
            }
        }
    }

    private boolean isWindowPositioningLocked() {
        try {
            synchronized (mService.mGlobalLock) {
                return false;
            }
        } catch (Exception e) {
            if (DEBUG_POP_UP) {
                Slog.w(TAG, "Failed to check window positioning lock state", e);
            }
            return false;
        }
    }

    private boolean mIsDragging = false;
    private float mDragStartX, mDragStartY;
    private int mDragStartLeft, mDragStartTop;
    private Point mDragStartCenterPosition;
    private float mDragLastX, mDragLastY;
    private long mDragStartTime;
    private android.view.VelocityTracker mVelocityTracker;

    void startMoving(float x, float y) {
        final Rect bounds = getTaskWindowSurfaceBounds();
        final int[] animXY = new int[2];
        final float[] animScale = new float[1];
        if (mPopUpAnimationController.getCurrentAnimPosition(animXY, animScale)) {
            bounds.offsetTo(animXY[0], animXY[1]);
            final Point pos = new Point(bounds.left + bounds.width() / 2,
                    bounds.top + bounds.height() / 2);
            setWindowCenterPosition(pos);
            if (mTask.getWindowConfiguration().isPinnedExtWindowMode()) {
                final Rect displayBound = new Rect();
                if (mTask.mDisplayContent != null) {
                    mTask.mDisplayContent.getBounds(displayBound);
                }
                setPinnedWindowVerticalPosRatio(pos, displayBound, true);
            }
            mPopUpAnimationController.clearAnimPosition();
        }
        cancelPopUpViewAnimation();
        mIsDragging = true;
        mDragStartX = x;
        mDragStartY = y;
        mDragLastX = x;
        mDragLastY = y;
        mDragStartTime = android.os.SystemClock.uptimeMillis();
        mDragStartCenterPosition = getWindowCenterPosition();
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
        }
        mVelocityTracker = android.view.VelocityTracker.obtain();
        final long now = android.os.SystemClock.uptimeMillis();
        final MotionEvent syntheticEvent = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        mVelocityTracker.addMovement(syntheticEvent);
        syntheticEvent.recycle();
        mDragStartLeft = bounds.left;
        mDragStartTop = bounds.top;
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "startMoving: startX=" + x + " startY=" + y + " bounds=" + bounds
                    + " mDragStartLeft=" + mDragStartLeft + " mDragStartTop=" + mDragStartTop);
        }
    }

    void moveTo(float x, float y) {
        if (!mIsDragging) {
            return;
        }
        final float dx = x - mDragStartX;
        final float dy = y - mDragStartY;
        final int newLeft = (int) (mDragStartLeft + dx);
        final int newTop = (int) (mDragStartTop + dy);
        final boolean isPinned = mTask.getWindowConfiguration().isPinnedExtWindowMode();
        final float scale = isPinned ? mWindowSurfaceScale : getWindowSurfaceRealScale();

        final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
        if (mTask.mSurfaceControl != null && mTask.mSurfaceControl.isValid()) {
            t.setPosition(mTask.mSurfaceControl, newLeft, newTop)
             .setScale(mTask.mSurfaceControl, scale, scale);
            t.apply();
        }
        final int scaledWidth = (int) (mTask.getBounds().width() * scale);
        final int scaledHeight = (int) (mTask.getBounds().height() * scale);
        mWindowCenterPosition.set(newLeft + scaledWidth / 2, newTop + scaledHeight / 2);
        if (isPinned) {
            final Rect displayBound = new Rect();
            if (mTask.mDisplayContent != null) {
                mTask.mDisplayContent.getBounds(displayBound);
            }
            setPinnedWindowVerticalPosRatio(mWindowCenterPosition, displayBound, true);
            PinnedWindowOverlayController.getInstance().updateOverlayPosition(mTask, newLeft, newTop,
                    mTask.getBounds().width(), mTask.getBounds().height(), scale);
        }

        if (DEBUG_POP_UP) {
            Slog.d(TAG, "moveTo: newLeft=" + newLeft + " newTop=" + newTop + " scale=" + getWindowSurfaceRealScale());
        }
    }

    void finishMoving() {
        if (!mIsDragging) {
            return;
        }
        final Rect surfaceBounds = getTaskWindowSurfaceBounds();

        mIsDragging = false;

        if (mVelocityTracker != null) {
            mVelocityTracker.computeCurrentVelocity(1000,
                    android.view.ViewConfiguration.get(mService.mContext).getScaledMaximumFlingVelocity());
        }
        final float xVelocity = mVelocityTracker != null ? mVelocityTracker.getXVelocity() : 0.0f;
        final float yVelocity = mVelocityTracker != null ? mVelocityTracker.getYVelocity() : 0.0f;
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }

        final Rect displayBound = new Rect();
        if (mTask.mDisplayContent != null) {
            mTask.mDisplayContent.getBounds(displayBound);
        }
        final Point startPos = new Point(surfaceBounds.left, surfaceBounds.top);
        final Point currentCenterPos = new Point(
                surfaceBounds.left + surfaceBounds.width() / 2,
                surfaceBounds.top + surfaceBounds.height() / 2);

        final float winScale = getWindowSurfaceScale();
        final Rect bounds = mTask.getBounds();
        final boolean isPinned = mTask.getWindowConfiguration().isPinnedExtWindowMode();

        if (isPinned) {
            setPinnedWindowVerticalPosRatio(currentCenterPos, displayBound, true);
            if (PopUpSettingsConfig.getInstance().isEdgeDockEnabled()
                    && shouldSlideToEdge(surfaceBounds, displayBound, xVelocity, yVelocity)) {
                dockToEdge(surfaceBounds, displayBound);
                return;
            }
        }

        final Rect boundaryGap = isPinned
                ? WindowResizingAlgorithm.getPinnedBoundaryGapAfterMoving(
                        displayBound, surfaceBounds, xVelocity, yVelocity)
                : WindowResizingAlgorithm.getBoundaryGapAfterMoving(
                        displayBound.width(), displayBound, surfaceBounds,
                        currentCenterPos.x, currentCenterPos.y, xVelocity, yVelocity);

        setWindowBoundaryGap(boundaryGap.left, boundaryGap.top, boundaryGap.right, boundaryGap.bottom);

        final Point endPos = new Point();
        WindowResizingAlgorithm.getCenterByBoundaryGap(bounds, displayBound, boundaryGap,
                getPinnedWindowVerticalPosRatio(displayBound), currentCenterPos, winScale, endPos);

        setWindowCenterPosition(endPos);
        if (isPinned) {
            setPinnedWindowVerticalPosRatio(endPos, displayBound, true);
        }

        final float realScale = isPinned ? mWindowSurfaceScale : getWindowSurfaceRealScale();
        endPos.x -= bounds.width() * realScale / 2;
        endPos.y -= bounds.height() * realScale / 2;

        flingWindowToEdge(startPos, endPos, bounds.width(), bounds.height(), realScale, xVelocity, yVelocity);

        if (DEBUG_POP_UP) {
            Slog.d(TAG, "finishMoving: surfaceBounds=" + surfaceBounds + " startPos=" + startPos
                    + " endPos=" + endPos + " boundaryGap=" + boundaryGap
                    + " velX=" + xVelocity + " velY=" + yVelocity
                    + " realScale=" + realScale);
        }
    }

    private boolean shouldSlideToEdge(Rect surfaceBounds, Rect displayBound, float xVelocity, float yVelocity) {
        if (mIsDockedToEdge) {
            return false;
        }
        final int threshold = (int) (displayBound.width() * SLIDE_TO_EDGE_THRESHOLD_RATIO);
        final boolean nearLeftEdge = surfaceBounds.left < threshold;
        final boolean nearRightEdge = surfaceBounds.right > displayBound.width() - threshold;
        final boolean fastLeftSwipe = xVelocity < -3000;
        final boolean fastRightSwipe = xVelocity > 3000;
        final boolean isHorizontalSwipe = Math.abs(xVelocity) > Math.abs(yVelocity) * 2;
        final boolean isOutwardSwipe = (nearLeftEdge && fastLeftSwipe) || (nearRightEdge && fastRightSwipe);
        return isHorizontalSwipe && isOutwardSwipe && (surfaceBounds.left < 0 || surfaceBounds.right > displayBound.width());
    }

    private void dockToEdge(Rect surfaceBounds, Rect displayBound) {
        cancelPopUpViewAnimation();
        mPopUpAnimationController.clearAnimPosition();
        mIsDockedLeft = surfaceBounds.centerX() < displayBound.centerX();
        mIsDockedToEdge = true;
        mOriginalScaleBeforeDock = getWindowSurfaceScale();
        mOriginalCornerRadiusBeforeDock = getCornerRadius();
        mWindowCenterPosition.set(surfaceBounds.centerX(), surfaceBounds.centerY());
        final Rect dock = getDockedBounds(displayBound);
        mWindowCenterPosition.set(dock.centerX(), dock.centerY());
        final int offscreenX = mIsDockedLeft
                ? displayBound.left - surfaceBounds.width() : displayBound.right;
        mPopUpAnimationController.playDockAnimation(
                surfaceBounds.left, surfaceBounds.top, offscreenX, surfaceBounds.top,
                mTask.getBounds().width(), mTask.getBounds().height(),
                mTask.getBounds().width(), mTask.getBounds().height(),
                1f, 0f, mWindowSurfaceScale, mTask, this);
        PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                mTask, dock.left, dock.top, dock.width(), dock.height(), 1f);
        PinnedWindowOverlayController.getInstance().setDockedState(true);
    }

    void restoreFromDocked() {
        synchronized (mService.mGlobalLock) {
            restoreFromDockedLocked();
        }
    }

    private void restoreFromDockedLocked() {
        if (!mIsDockedToEdge || mTask.mDisplayContent == null
                || mPopUpAnimationController.isAnimating()) return;
        final Rect display = mTask.mDisplayContent.getBounds();
        final Rect dock = getDockedBounds(display);
        mIsDockedToEdge = false;
        final float scale = mOriginalScaleBeforeDock;
        setWindowSurfaceScale(scale);
        final int width = Math.round(mTask.getBounds().width() * scale);
        final int height = Math.round(mTask.getBounds().height() * scale);
        final int left = mIsDockedLeft ? display.left + BOUNDARY_GAP
                : display.right - BOUNDARY_GAP - width;
        final int top = Math.max(display.top + BOUNDARY_GAP,
                Math.min(dock.centerY() - height / 2, display.bottom - BOUNDARY_GAP - height));
        setWindowBoundaryGap(mIsDockedLeft ? BOUNDARY_GAP : 0, 0,
                mIsDockedLeft ? 0 : BOUNDARY_GAP, 0);
        setWindowCenterPosition(new Point(left + width / 2, top + height / 2));
        setPinnedWindowVerticalPosRatio(getWindowCenterPosition(), display, true);
        mPopUpAnimationController.playRestoreAnimation(
                mIsDockedLeft ? display.left - width : display.right, top, left, top,
                mTask.getBounds().width(), mTask.getBounds().height(),
                mTask.getBounds().width(), mTask.getBounds().height(),
                0f, 1f, scale, mOriginalCornerRadiusBeforeDock, mTask, this);
        PinnedWindowOverlayController.getInstance().setDockedState(false);
        PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                mTask, left, top, mTask.getBounds().width(), mTask.getBounds().height(), scale);
        updateDockedForegroundState();
    }

    boolean isDockedToEdge() {
        return mIsDockedToEdge;
    }

    boolean isDockedInBackground() {
        return mDockedInBackground && mIsDockedToEdge
                && mTask.getWindowConfiguration().isPinnedExtWindowMode();
    }

    /** Reconcile the lifecycle without removing the independent edge-tab overlay or the task. */
    void updateDockedForegroundState() {
        synchronized (mService.mGlobalLock) {
            if (!mTask.isAttached()) return;
            final boolean docked = mIsDockedToEdge
                    && mTask.getWindowConfiguration().isPinnedExtWindowMode();
            if (docked && mPopUpAnimationController.isAnimating()) {
                // The animation-end callback retries with the latest setting. Hiding activities
                // now would remove their content before the dock animation reaches the edge.
                return;
            }
            final PopUpSettingsConfig settings = PopUpSettingsConfig.getInstance();
            if (docked && !settings.isEdgeDockEnabled()) {
                restoreFromDockedLocked();
                return;
            }
            final boolean background = docked && !settings.isEdgeDockForeground();
            if (mDockedInBackground == background) return;
            mDockedInBackground = background;

            mService.mAtmService.deferWindowLayout();
            try {
                if (background) {
                    mTask.forAllLeafTasksAndLeafTaskFragments(fragment -> {
                        if (fragment.getResumedActivity() != null
                                && fragment.getPausingActivity() == null) {
                            // Do not turn this explicit stash into an automatic PiP launch.
                            fragment.startPausing(false /* userLeaving */, false /* uiSleeping */,
                                    null /* resuming */, "small-window-edge-dock");
                        }
                    }, true /* traverseTopToBottom */);
                }
                // The visibility gate lets normal pause/stop and process-importance accounting
                // handle the background case, and restart/resume it when the user restores it.
                mTask.mRootWindowContainer.ensureActivitiesVisible();
                mTask.mRootWindowContainer.resumeFocusedTasksTopActivities();
            } finally {
                mService.mAtmService.continueWindowLayout();
            }
        }
    }

    void updateMoving(float x, float y) {
        mDragLastX = x;
        mDragLastY = y;
        if (mVelocityTracker != null) {
            final long now = android.os.SystemClock.uptimeMillis();
            final MotionEvent syntheticEvent = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_MOVE, x, y, 0);
            mVelocityTracker.addMovement(syntheticEvent);
            syntheticEvent.recycle();
        }
        moveTo(x, y);
    }

    void cancelMoving() {
        if (!mIsDragging) {
            return;
        }
        mIsDragging = false;
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
        if (mDragStartCenterPosition != null) {
            mWindowCenterPosition.set(mDragStartCenterPosition.x, mDragStartCenterPosition.y);
        }

        final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
        if (mTask.mSurfaceControl != null && mTask.mSurfaceControl.isValid()) {
            t.setPosition(mTask.mSurfaceControl, mDragStartLeft, mDragStartTop);
            t.apply();
        }
        if (mTask.getWindowConfiguration().isPinnedExtWindowMode()) {
            final Rect displayBounds = new Rect();
            if (mTask.mDisplayContent != null) {
                mTask.mDisplayContent.getBounds(displayBounds);
            }
            setPinnedWindowVerticalPosRatio(mWindowCenterPosition, displayBounds, true);
            PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                    mTask, mDragStartLeft, mDragStartTop, mTask.getBounds().width(),
                    mTask.getBounds().height(), getWindowSurfaceRealScale());
        }
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "cancelMoving: restored to start position");
        }
    }

    void toggleResize() {
        if (!mTask.getWindowConfiguration().isPinnedExtWindowMode()
                || mIsDockedToEdge || mPopUpAnimationController.isAnimating()) return;
        final Rect start = getTaskWindowSurfaceBounds();
        final float startScale = getWindowSurfaceScale();
        mIsPinnedWindowSmall = !mIsPinnedWindowSmall;
        final float endScale = WindowResizingAlgorithm.getDefaultPinnedWindowScale(
                mTask.getConfiguration().orientation, mIsPinnedWindowSmall);
        setWindowSurfaceScale(endScale);
        final Rect end = getTaskWindowSurfaceBounds();
        setWindowCenterPosition(new Point(end.centerX(), end.centerY()));
        playToggleResizeWindowAnimation(new Point(start.left, start.top),
                new Point(end.left, end.top), startScale, endScale, () -> {
                    synchronized (mService.mGlobalLock) {
                        mService.requestTraversal();
                    }
                });
    }

    boolean isPinnedWindowSmall() {
        return mIsPinnedWindowSmall;
    }

    void resetDraggingState() {
        if (mIsDragging) {
            mIsDragging = false;
            if (mVelocityTracker != null) {
                mVelocityTracker.recycle();
                mVelocityTracker = null;
            }
        }
    }

    private boolean hasTaskSurfaceAnimationLeash() {
        try {
            if (mTask.mSurfaceAnimator != null) {
                return mTask.mSurfaceAnimator.hasLeash();
            }
            return false;
        } catch (Exception e) {
            if (DEBUG_POP_UP) {
                Slog.w(TAG, "Failed to check animation leash state", e);
            }
            return false;
        }
    }

    private IWindow getIWindow() {
        synchronized (mService.mAtmService.mGlobalLock) {
            if (mTask != null && mTask.getTopVisibleAppMainWindow() != null) {
                final IWindow iWindow = mTask.getTopVisibleAppMainWindow().getIWindow();
                return iWindow;
            }
            return null;
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(1024);
        sb.append("Task=");
        sb.append(mTask);
        sb.append(" {mTask.windowingMode=");
        sb.append(mTask.getConfiguration().windowConfiguration.getWindowingMode());
        sb.append("}");
        sb.append(" {mFreezedWindowingMode=");
        sb.append(mFreezedWindowingMode);
        sb.append(" mWindowCenterPosition=");
        sb.append(mWindowCenterPosition);
        sb.append(" mWindowSurfaceScale=");
        sb.append(mWindowSurfaceScale);
        sb.append(" mWindowSurfaceScaleFactor=");
        sb.append(mWindowSurfaceScaleFactor);
        sb.append(" mWindowBoundaryGap=");
        sb.append(mWindowBoundaryGap);
        sb.append(" mCornerRadius=");
        sb.append(mCornerRadius);
        sb.append("}");
        return sb.toString();
    }
}
