/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The AviumUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static com.android.internal.arkui.DebugConstants.DEBUG_POP_UP;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Point;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewConfiguration;

import com.android.internal.R;

class MiniWindowEdgeBarHelper {

    private static final String TAG = "MiniWindowEdgeBarHelper";

    private static final int EDGE_BAR_COLOR = Color.parseColor("#FFF0F2F2");

    private static final int DRAG_SCALE_TYPE_NONE = 0;
    private static final int DRAG_SCALE_TYPE_TO_PINNED = 1;
    private static final int DRAG_SCALE_TYPE_TO_FULL = 2;

    private final RectF mBarBounds = new RectF();
    private final RectF mBarTouchBounds = new RectF();

    private Paint mPaint;
    private Task mTask;
    private TaskWindowSurfaceInfo mTaskWindowSurfaceInfo;
    private View mView;

    private int mBarHeight;
    private int mBarMargin;
    private int mBarRadius;
    private int mBarWidth;

    private float mDragSlop;

    private boolean mScrolling = false;
    private boolean mListening = false;
    private boolean mSurfaceAnimating;

    void setSurfaceAnimating(boolean animating) {
        if (mView == null) return;
        mView.post(() -> {
            mSurfaceAnimating = animating;
            if (animating) mBarTouchBounds.setEmpty();
            else updateBounds();
            mView.invalidate();
        });
    }

    private int mDragScaleType = DRAG_SCALE_TYPE_NONE;
    private float mStartScale = 1.0f;
    private Point mStartCenterPos = new Point();

    void onDragResizeChanged(float scale, Rect displayBound, boolean isLandscape) {
        if (mListening && mView != null) {
            mView.postOnAnimation(() -> {
                updateBoundsOnDrag(scale, displayBound, isLandscape);
                mView.invalidate();
            });
        }
    }

    void onResizeChanged() {
        if (mView != null) {
            mView.postOnAnimation(() -> {
                updateBounds();
                mView.invalidate();
            });
        }
    }

    void onVisibilityChanged(int visibility) {
        if (visibility == View.VISIBLE) {
            mListening = true;
            updateBounds();
        } else {
            mView.removeCallbacks(mStartMoving);
            mScrolling = false;
            mTouchStarted = false;
            mListening = false;
        }
    }

    void onOrientationChanged() {
        if (mListening && mView != null) {
            mView.postOnAnimation(() -> {
                updateBounds();
                mView.invalidate();
            });
        }
    }

    void onDraw(Canvas canvas) {
        if (!mSurfaceAnimating && !mBarBounds.isEmpty()) {
            canvas.drawRoundRect(mBarBounds, mBarRadius, mBarRadius, mPaint);
        }
    }

    private float mStartX;
    private float mStartY;
    private float mCurrentScale = 1.0f;
    private boolean mTouchStarted;
    private boolean mMoving;
    private boolean mResizeHorizontally;
    private static final float MIN_SCALE = 0.35f;
    private static final float MAX_SCALE = 1.3f;

    private final Runnable mStartMoving = () -> {
        if (mTouchStarted && !mScrolling && mTask != null && mTaskWindowSurfaceInfo != null) {
            mMoving = true;
            PopUpWindowController.getInstance().startMovingTask(mTask, mStartX, mStartY);
            PopUpWindowController.getInstance().triggerVibrate();
        }
    };

    boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        return true;
    }

    private void handleActionDown(MotionEvent event) {
        mTouchStarted = mBarTouchBounds.contains(event.getRawX(), event.getRawY())
                && mTask != null && mTaskWindowSurfaceInfo != null;
        if (!mTouchStarted) {
            return;
        }
        mTaskWindowSurfaceInfo.cancelPopUpViewAnimation();
        mStartX = event.getRawX();
        mStartY = event.getRawY();
        mScrolling = false;
        mMoving = false;
        mDragScaleType = DRAG_SCALE_TYPE_NONE;
        final int rotation = mTask.getWindowConfiguration().getDisplayRotation();
        mResizeHorizontally = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        if (mTaskWindowSurfaceInfo != null) {
            mStartScale = mTaskWindowSurfaceInfo.getWindowSurfaceScale();
            mCurrentScale = mStartScale;
            mStartCenterPos = mTaskWindowSurfaceInfo.getWindowCenterPosition();
        }
        mView.postDelayed(mStartMoving, ViewConfiguration.getLongPressTimeout());
    }

    private void handleActionMove(MotionEvent event) {
        if (!mTouchStarted || mTaskWindowSurfaceInfo == null || mTask == null) {
            return;
        }
        if (mMoving) {
            PopUpWindowController.getInstance().updateMovingTask(
                    mTask, event.getRawX(), event.getRawY());
            onResizeChanged();
            return;
        }
        if (!mScrolling) {
            if (Math.hypot(event.getRawX() - mStartX, event.getRawY() - mStartY) > mDragSlop) {
                mView.removeCallbacks(mStartMoving);
                mScrolling = true;
            } else {
                return;
            }
        }

        float dragDistance = mResizeHorizontally
                ? event.getRawX() - mStartX : event.getRawY() - mStartY;
        float displaySize = mResizeHorizontally
                ? mView.getContext().getResources().getDisplayMetrics().widthPixels
                : mView.getContext().getResources().getDisplayMetrics().heightPixels;

        float scaleDelta = (dragDistance / displaySize) * 1.2f;
        float newScale = mStartScale + scaleDelta;
        newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
        mCurrentScale = newScale;

        int newDragScaleType = DRAG_SCALE_TYPE_NONE;
        // Relative thresholds keep all size presets usable in either orientation.
        if (newScale <= mStartScale * 0.8f) {
            newDragScaleType = DRAG_SCALE_TYPE_TO_PINNED;
        } else if (newScale >= Math.min(1.15f, mStartScale * 1.2f)) {
            newDragScaleType = DRAG_SCALE_TYPE_TO_FULL;
        }

        if (newDragScaleType != mDragScaleType) {
            mDragScaleType = newDragScaleType;
            if (mDragScaleType != DRAG_SCALE_TYPE_NONE) {
                PopUpWindowController.getInstance().triggerVibrate();
            }
        }

        applyWindowScale(newScale);
    }

    private void applyWindowScale(float scale) {
        if (mTask == null || mTask.mSurfaceControl == null || mTaskWindowSurfaceInfo == null) {
            return;
        }

        Rect displayBound = new Rect();
        if (mTask.mDisplayContent != null) {
            mTask.mDisplayContent.getBounds(displayBound);
        }

        Point pos = new Point();
        Rect bounds = mTask.getBounds();
        float scaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                bounds, displayBound, mStartCenterPos, scale,
                false, pos);

        mTaskWindowSurfaceInfo.setWindowSurfaceScale(scale);
        mTaskWindowSurfaceInfo.setWindowSurfaceScaleFactor(scaleFactor);

        SurfaceControl.Transaction t = mTask.getSyncTransaction();
        SurfaceControl leash = mTask.mSurfaceControl;
        if (leash != null && leash.isValid()) {
            float realScale = mTaskWindowSurfaceInfo.getWindowSurfaceRealScale();
            t.setPosition(leash, pos.x, pos.y)
             .setScale(leash, realScale, realScale)
             .apply();
        }

        final int displayRotation = mTask.mDisplayContent != null ? mTask.mDisplayContent.getRotation() : Surface.ROTATION_0;
        final boolean isLandscape = displayRotation == Surface.ROTATION_90
                || displayRotation == Surface.ROTATION_270;
        DimmerWindow.getInstance().onDragResizeChanged(scale,
                mTaskWindowSurfaceInfo.getTaskWindowSurfaceBoundsOnDrag(displayBound), isLandscape);
    }

    boolean onTouchEvent(MotionEvent event) {
        final Task task = mTask;
        if (task == null) {
            return false;
        }
        synchronized (task.mAtmService.mGlobalLock) {
            return onTouchEventLocked(event);
        }
    }

    private boolean onTouchEventLocked(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handleActionDown(event);
                return mTouchStarted;

            case MotionEvent.ACTION_MOVE:
                handleActionMove(event);
                return mTouchStarted;

            case MotionEvent.ACTION_UP:
                mView.removeCallbacks(mStartMoving);
                if (mTouchStarted) {
                    mTouchStarted = false;
                    if (mMoving) {
                        mMoving = false;
                        PopUpWindowController.getInstance().finishMovingTask(mTask);
                        onResizeChanged();
                    } else if (mScrolling) {
                        handleDragEnd();
                    }
                    mScrolling = false;
                    return true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                mView.removeCallbacks(mStartMoving);
                if (mTouchStarted) {
                    mTouchStarted = false;
                    if (mMoving) {
                        mMoving = false;
                        PopUpWindowController.getInstance().cancelMovingTask(mTask);
                        onResizeChanged();
                    } else if (mScrolling) {
                        settleWindowScale(mStartScale);
                    }
                    mScrolling = false;
                    return true;
                }
                break;
        }
        return false;
    }

    private void handleDragEnd() {
        if (mTask == null) {
            return;
        }

        switch (mDragScaleType) {
            case DRAG_SCALE_TYPE_TO_PINNED:
                PopUpWindowController.getInstance().enterPinnedWindowingMode(mTask);
                break;

            case DRAG_SCALE_TYPE_TO_FULL:
                PopUpWindowController.getInstance().exitMiniWindowingMode();
                break;

            case DRAG_SCALE_TYPE_NONE:
            default:
                settleWindowScale(mCurrentScale);
                break;
        }

        mDragScaleType = DRAG_SCALE_TYPE_NONE;
    }

    private void settleWindowScale(float targetScale) {
        if (mTaskWindowSurfaceInfo == null || mTask == null || mTask.mDisplayContent == null) {
            return;
        }

        Rect displayBound = new Rect();
        if (mTask.mDisplayContent != null) {
            mTask.mDisplayContent.getBounds(displayBound);
        }

        final int displayRotation = mTask.mDisplayContent.getRotation();
        final boolean isLandscape = displayRotation == Surface.ROTATION_90
                || displayRotation == Surface.ROTATION_270;

        Point currentCenterPos = mTaskWindowSurfaceInfo.getWindowCenterPosition();
        Point startPos = new Point();
        Rect bounds = mTask.getBounds();
        float startScale = mTaskWindowSurfaceInfo.getWindowSurfaceScale();
        float startScaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                bounds, displayBound, currentCenterPos, startScale,
                false, startPos);

        Point endPos = new Point();
        float endScaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                bounds, displayBound, currentCenterPos, targetScale,
                false, endPos);

        float startWinScale = startScale * startScaleFactor;
        float endWinScale = targetScale * endScaleFactor;

        mTaskWindowSurfaceInfo.setWindowSurfaceScale(targetScale);
        mTaskWindowSurfaceInfo.setWindowSurfaceScaleFactor(endScaleFactor);

        mTaskWindowSurfaceInfo.resizeWindowWithAnimation(startPos, endPos,
                bounds.width(), bounds.height(),
                startWinScale, endWinScale, displayBound, isLandscape);
    }

    void updateResources() {
        if (getContext() == null) {
            return;
        }
        mBarWidth = getContext().getResources().getDimensionPixelSize(R.dimen.mini_window_bar_width);
        mBarHeight = getContext().getResources().getDimensionPixelSize(R.dimen.mini_window_bar_height);
        mBarRadius = getContext().getResources().getDimensionPixelSize(R.dimen.mini_window_bar_radius);
        mBarMargin = getContext().getResources().getDimensionPixelSize(R.dimen.mini_window_bar_margin);
    }

    RectF getBarTouchBounds() {
        return new RectF(mBarTouchBounds);
    }

    void setUp(View view) {
        mView = view;
        updateResources();

        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setColor(EDGE_BAR_COLOR);
        mPaint.setStyle(Style.FILL);

        mDragSlop = (float) ViewConfiguration.get(getContext()).getScaledTouchSlop();

        updateBounds();
    }

    void setTask(Task task) {
        mSurfaceAnimating = false;
        if (mView != null) {
            mView.removeCallbacks(mStartMoving);
        }
        if (mMoving && mTask != null) {
            PopUpWindowController.getInstance().cancelMovingTask(mTask);
        }
        mMoving = false;
        mTouchStarted = false;
        mTask = task;
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "setTask: " + (task != null));
        }
        if (mTask != null) {
            mTaskWindowSurfaceInfo = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
        } else {
            mTaskWindowSurfaceInfo = null;
        }
        mScrolling = false;
        mStartY = 0f;
        mDragScaleType = 0;
        onResizeChanged();
    }

    private void updateBoundsOnDrag(float scale, Rect bounds, boolean isLandscape) {
        final RectF barBounds = new RectF();
        final float height = mBarHeight * scale;
        final float margin = mBarMargin * scale;
        final float width = mBarWidth * scale;
        if (isLandscape) {
            barBounds.left = bounds.right + margin;
            barBounds.top = bounds.top + ((bounds.height() - width) / 2);
            barBounds.right = barBounds.left + height;
            barBounds.bottom = barBounds.top + width;

            mBarTouchBounds.left = bounds.right;
            mBarTouchBounds.top = barBounds.top - margin * 3;
            mBarTouchBounds.right = barBounds.right + margin * 4;
            mBarTouchBounds.bottom = barBounds.bottom + margin * 3;
        } else {
            barBounds.left = bounds.left + ((bounds.width() - width) / 2);
            barBounds.top = bounds.bottom + margin;
            barBounds.right = barBounds.left + width;
            barBounds.bottom = barBounds.top + height;

            mBarTouchBounds.left = bounds.left - margin * 3;
            mBarTouchBounds.top = bounds.bottom;
            mBarTouchBounds.right = bounds.right + margin * 3;
            mBarTouchBounds.bottom = barBounds.bottom + margin * 4;
        }
        mBarBounds.set(barBounds);
    }

    private void updateBounds() {
        final Task rootTask = mTask != null ? mTask.getRootTask() : null;
        if (rootTask == null || mTaskWindowSurfaceInfo == null || mView == null) {
            return;
        }
        if (!rootTask.getWindowConfiguration().isMiniExtWindowMode()) {
            return;
        }
        final RectF barBounds = new RectF();
        final Rect displayBound = new Rect();
        if (rootTask.mDisplayContent != null) {
            rootTask.mDisplayContent.getBounds(displayBound);
        }
        if (displayBound.isEmpty()) {
            return;
        }
        final Point pos = new Point();
        final Rect bound = rootTask.getBounds();
        final float scale = mTaskWindowSurfaceInfo.getWindowSurfaceScale();
        final float scaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                bound, displayBound, mTaskWindowSurfaceInfo.getWindowCenterPosition(), scale,
                false, pos);
        final float realScale = scale * scaleFactor;
        final Rect bounds = new Rect(0, 0, bound.width(), bound.height());
        bounds.scale(realScale);
        bounds.offsetTo(pos.x, pos.y);

        final int displayRotation = rootTask.getWindowConfiguration().getDisplayRotation();
        final boolean isLandscape = displayRotation == Surface.ROTATION_90
                || displayRotation == Surface.ROTATION_270;

        final float height = mBarHeight * scale;
        final float margin = mBarMargin * scale;
        final float width = mBarWidth * scale;
        if (isLandscape) {
            barBounds.left = bounds.right + margin;
            barBounds.top = bounds.top + ((bounds.height() - width) / 2);
            barBounds.right = barBounds.left + height;
            barBounds.bottom = barBounds.top + width;

            mBarTouchBounds.left = bounds.right;
            mBarTouchBounds.top = barBounds.top - margin * 3;
            mBarTouchBounds.right = barBounds.right + margin * 4;
            mBarTouchBounds.bottom = barBounds.bottom + margin * 3;
        } else {
            barBounds.left = bounds.left + ((bounds.width() - width) / 2);
            barBounds.top = bounds.bottom + margin;
            barBounds.right = barBounds.left + width;
            barBounds.bottom = barBounds.top + height;

            mBarTouchBounds.left = bounds.left - margin * 3;
            mBarTouchBounds.top = bounds.bottom;
            mBarTouchBounds.right = bounds.right + margin * 3;
            mBarTouchBounds.bottom = barBounds.bottom + margin * 4;
        }
        mBarBounds.set(barBounds);
    }

    private boolean passedSlop(float startX, float startY, float x, float y) {
        return Math.abs(x - startX) > mDragSlop ||
                Math.abs(y - startY) > mDragSlop;
    }

    private Context getContext() {
        if (mView == null) {
            return null;
        }
        return mView.getContext();
    }
}
