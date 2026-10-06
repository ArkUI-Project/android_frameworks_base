/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static com.android.internal.arkui.DebugConstants.DEBUG_POP_UP;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.util.Slog;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import com.android.internal.dynamicanimation.animation.DynamicAnimation;
import com.android.internal.dynamicanimation.animation.FloatValueHolder;
import com.android.internal.dynamicanimation.animation.SpringAnimation;
import com.android.internal.dynamicanimation.animation.SpringForce;

import com.android.server.AnimationThread;
import com.android.server.wm.SurfaceAnimationThread;
import com.android.server.wm.Task;
import com.android.server.wm.WindowManagerService;

class PopUpAnimationController {

    private static final String TAG = "PopUpAnimationController";

    private static final long ANIMATION_SCALE_DURATION = 336L;
    private static final long ANIMATION_RESIZE = 200L;
    static final long ANIMATION_CROSS_OVER_EXIT_DURATION = 150L;

    private static final float DAMPING_RATIO = 0.75f;
    private static final float STPRING_STIFFNESS = 200.0f;
    private static final PathInterpolator HYPER_OS_INTERPOLATOR =
            new PathInterpolator(.16f, 1f, .30f, 1f);

    private final WindowManagerService mService;
    private final Handler mSurfaceAnimationHandler;

    private final Choreographer.FrameCallback mFrameCallback;
    private final Choreographer.FrameCallback mFrameCancelCallback;
    private final Choreographer.FrameCallback mFrameCrossOverCallback;
    private final Choreographer.FrameCallback mFrameExitCallback;
    private final Choreographer.FrameCallback mFrameToggleResizeCallback;

    private final Object mLock = new Object();
    private final Object mCancelLock = new Object();
    private final Handler mAnimationThreadHandler = AnimationThread.getHandler();

    private boolean mApplyScheduled;
    private int mBoundHeight;
    private int mBoundWidth;
    private Rect mBounds;

    private volatile int mCurrentAnimX;
    private volatile int mCurrentAnimY;
    private volatile float mCurrentAnimScale;
    private volatile boolean mHasAnimPosition;

    // Publish each HyperOS frame atomically. Some animation-end callbacks hold only
    // mCancelLock, so taking mLock here would invert the frame callback's lock order.
    private volatile AnimationPosition mHyperOsAnimationPosition;

    private static final class AnimationPosition {
        final int x;
        final int y;
        final float scale;
        final float decorExpansion;
        final Rect bounds;

        AnimationPosition(float x, float y, float scale, Rect taskBounds) {
            this(x, y, scale, taskBounds, Float.NaN);
        }

        AnimationPosition(float x, float y, float scale, Rect taskBounds, float decorExpansion) {
            this.x = Math.round(x);
            this.y = Math.round(y);
            this.scale = scale;
            this.decorExpansion = decorExpansion;
            bounds = new Rect(this.x, this.y,
                    this.x + Math.round(taskBounds.width() * scale),
                    this.y + Math.round(taskBounds.height() * scale));
        }
    }

    private OnAnimationEndCallback mCallback;
    // Initialized and accessed only on SurfaceAnimationThread.
    private Choreographer mChoreographer;
    private SurfaceControl.Transaction mFrameTransaction;
    private Task mTask;

    private ValueAnimator mCrossOverAnimator;
    private ValueAnimator mToggleResizeAnimator;
    private ValueAnimator mWindowExitAnimator;
    private ValueAnimator mDockAnimator;

    private Point mStartPos;
    private float mStartScale;
    private Point mEndPos;
    private float mEndScale;
    private float mStartDecorExpansion = Float.NaN;
    private float mEndDecorExpansion = Float.NaN;
    private float mWindowScale;
    private float mVelX;
    private float mVelY;

    private volatile boolean mIsAnimating;
    private volatile boolean mIsCancelling;
    private boolean mIsFromLeaveButton;
    private float mLastAnimatingScale;
    private int mRunningSpringAnimations;

    private SpringAnimation mSpringAnimationX;
    private SpringAnimation mSpringAnimationY;

    private FloatValueHolder mValueHolderX;
    private FloatValueHolder mValueHolderY;

    interface OnAnimationEndCallback {
        void onAnimationEnded();
    }

    PopUpAnimationController(WindowManagerService wms) {
        mSurfaceAnimationHandler = SurfaceAnimationThread.getHandler();

        mFrameCallback = frameTimeNanos -> {
            startAnimations();
        };
        mFrameExitCallback = frameTimeNanos -> {
            startExitAnimation();
        };
        mFrameToggleResizeCallback = frameTimeNanos -> {
            startToggleResizeAnimation();
        };
        mFrameCrossOverCallback = frameTimeNanos -> {
            startCrossOverAnimation();
        };
        mFrameCancelCallback = frameTimeNanos -> {
            if (isAnimating()) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "cancelAnimation()");
                }
                if (mSpringAnimationX != null) {
                    mSpringAnimationX.cancel();
                }
                if (mSpringAnimationY != null) {
                    mSpringAnimationY.cancel();
                }
                if (mWindowExitAnimator != null) {
                    mWindowExitAnimator.cancel();
                }
                if (mToggleResizeAnimator != null) {
                    mToggleResizeAnimator.cancel();
                }
                if (mCrossOverAnimator != null) {
                    mCrossOverAnimator.cancel();
                }
                if (mDockAnimator != null) {
                    mDockAnimator.cancel();
                }
                synchronized (mLock) {
                    synchronized (mCancelLock) {
                        if (PopUpSettingsConfig.getInstance().isHyperOsMode()
                                && mFrameTransaction != null) {
                            // A traversal callback may still be queued after cancellation.
                            // Never restore the old position over the next drag frame.
                            mFrameTransaction.clear();
                            mHyperOsAnimationPosition = null;
                        }
                        mIsCancelling = false;
                    }
                }
            }
        };
        mService = wms;
        mBounds = new Rect();
        mLastAnimatingScale = -1.0f;
    }

    private Choreographer getSurfaceChoreographer() {
        if (mChoreographer == null) {
            mChoreographer = Choreographer.getSfInstance();
        }
        return mChoreographer;
    }

    private void runOnSurfaceAnimationThread(Runnable callback) {
        if (mSurfaceAnimationHandler.getLooper().isCurrentThread()) {
            callback.run();
        } else {
            mSurfaceAnimationHandler.post(callback);
        }
    }

    private void postFrameCallback(Choreographer.FrameCallback callback) {
        // Tasks are constructed under the WM lock. The surface thread can be waiting for
        // that same lock to publish a frame, so never wait for its initialization here.
        // Initialize on its Looper before use, including requests made just after construction.
        runOnSurfaceAnimationThread(() ->
                getSurfaceChoreographer().postFrameCallback(callback));
    }

    void setTask(Task task) {
        mTask = task;
        if (mTask != null) {
            mFrameTransaction = mService.mTransactionFactory.get();
        }
    }

    private void updateHyperOsPosition(float x, float y, float scale) {
        updateHyperOsPosition(x, y, scale, Float.NaN);
    }

    private void updateHyperOsPosition(float x, float y, float scale, float decorExpansion) {
        if (!PopUpSettingsConfig.getInstance().isHyperOsMode()) return;
        mHyperOsAnimationPosition = new AnimationPosition(
                x, y, scale, mTask.getBounds(), decorExpansion);
        HyperOsWindowDecor.getInstance().update(mTask);
    }

    private void updateOverlayPosition(int x, int y, int width, int height, float scale) {
        if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
            updateHyperOsPosition(x, y, scale);
        } else {
            PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                    mTask, x, y, width, height, scale);
        }
    }

    private void notifyHyperOsAnimationFinished() {
        if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
            // The final frame can be consumed by UiThread before the animator clears
            // its running state. Publish once more so chrome input is enabled again.
            HyperOsWindowDecor.getInstance().update(mTask);
        }
    }

    private void setTaskPosition(int x, int y, float scale, int boundWidth, int boundHeight) {
        if (mTask != null && mFrameTransaction != null) {
            final SurfaceControl leash = mTask.mSurfaceControl;
            if (leash != null && leash.isValid()) {
                final float cornerRadius = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo().getCornerRadius();
                mFrameTransaction.setPosition(leash, x, y)
                        .setWindowCrop(leash, boundWidth, boundHeight)
                        .setScale(leash, scale, scale)
                        .setCornerRadius(leash, cornerRadius)
                        .show(leash);
                updateHyperOsPosition(x, y, scale);
            }
            mCurrentAnimX = x;
            mCurrentAnimY = y;
            mCurrentAnimScale = scale;
            mHasAnimPosition = true;
        }
    }

    private void scheduleApplyTransaction() {
        synchronized (mLock) {
            if (!mApplyScheduled && mFrameTransaction != null) {
                mApplyScheduled = true;
                runOnSurfaceAnimationThread(() -> getSurfaceChoreographer().postCallback(
                        Choreographer.CALLBACK_TRAVERSAL, () -> {
                            synchronized (mLock) {
                                mApplyScheduled = false;
                            }
                            applyFrameTransaction();
                        }, null));
            }
        }
    }

    /** Called outside the animation locks: decoration snapshots take the WM lock. */
    private void applyFrameTransaction() {
        if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
            final SurfaceControl.Transaction frame = mService.mTransactionFactory.get();
            final AnimationPosition position;
            final boolean cancelling;
            synchronized (mLock) {
                synchronized (mCancelLock) {
                    frame.merge(mFrameTransaction);
                    position = mHyperOsAnimationPosition;
                    cancelling = mIsCancelling;
                }
            }
            if (cancelling || position == null) {
                frame.close();
                return;
            }
            HyperOsWindowDecor.getInstance().applyTransaction(
                    mTask, frame, position.bounds, position.scale, position.decorExpansion);
        } else {
            mFrameTransaction.apply();
        }
    }

    private Point getCrossOverPosition(float scale) {
        final Point pos = new Point();
        if (mTask != null) {
            final Point center = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo().getWindowCenterPosition();
            final float s = scale / 2.0f;
            pos.set(center.x - (int) (mBounds.width() * s), center.y - (int) (mBounds.height() * s));
        }
        return pos;
    }

    private void startCrossOverAnimation() {
        final boolean hyperOs = PopUpSettingsConfig.getInstance().isHyperOsMode();
        final float startScale = mStartScale;
        final float endScale = mEndScale;
        mCrossOverAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        mCrossOverAnimator.addUpdateListener(animation -> {
            synchronized (mLock) {
                final float progress = (float) animation.getAnimatedValue();
                final float currentScale = startScale + ((endScale - startScale) * progress);
                synchronized (mCancelLock) {
                    if (!mIsCancelling && mTask != null && mFrameTransaction != null) {
                        final SurfaceControl leash = mTask.mSurfaceControl;
                        if (leash != null && leash.isValid()) {
                            final Point pos = getCrossOverPosition(currentScale);
                            final float cornerRadius = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo().getCornerRadius();
                            mFrameTransaction.setPosition(leash, pos.x, pos.y);
                            mFrameTransaction.setScale(leash, currentScale, currentScale);
                            mFrameTransaction.setCornerRadius(leash, cornerRadius);
                            updateHyperOsPosition(pos.x, pos.y, currentScale);
                        }
                        mLastAnimatingScale = currentScale;
                    }
                }
                scheduleApplyTransaction();
            }
        });
        mCrossOverAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "startCrossOverAnimation: onAnimationStart");
                }
                mIsAnimating = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "startCrossOverAnimation: onAnimationEnd mIsAnimating=" + mIsAnimating);
                }
                synchronized (mCancelLock) {
                    if (!mIsCancelling && mTask != null && mFrameTransaction != null) {
                        final SurfaceControl leash = mTask.mSurfaceControl;
                        if (leash != null && leash.isValid()) {
                            final Point pos = getCrossOverPosition(endScale);
                            final float cornerRadius = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo().getCornerRadius();
                            mFrameTransaction.setPosition(leash, pos.x, pos.y);
                            mFrameTransaction.setScale(leash, endScale, endScale);
                            mFrameTransaction.setCornerRadius(leash, cornerRadius);
                            updateHyperOsPosition(pos.x, pos.y, endScale);
                        }
                        mLastAnimatingScale = -1.0f;
                    }
                }
                scheduleApplyTransaction();
                synchronized (mLock) {
                    mIsAnimating = false;
                }
                notifyHyperOsAnimationFinished();
            }
        });
        mCrossOverAnimator.setDuration(hyperOs ? 220L : ANIMATION_CROSS_OVER_EXIT_DURATION);
        mCrossOverAnimator.setInterpolator(hyperOs ? HYPER_OS_INTERPOLATOR
                : new PathInterpolator(0.17f, 0.0f, 0.83f, 1.0f));
        mCrossOverAnimator.start();
    }

    void playExitAnimation(boolean isFromLeaveButton, float startScale, OnAnimationEndCallback callback) {
        cancelAnimation();
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "playExitAnimation");
        }
        mIsFromLeaveButton = isFromLeaveButton;
        if (mTask != null) {
            mTask.getBounds(mBounds);
        }
        mStartScale = startScale;
        mCallback = callback;
        if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
            // The closing guard must not hide the app while its first frame is queued.
            mIsAnimating = true;
        }
        postFrameCallback(mFrameExitCallback);
    }

    private Point getPosition(float scale) {
        final Point pos = new Point();
        if (mTask != null) {
            final Point center = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo().getWindowCenterPosition();
            final float s = scale / 2.0f;
            pos.set(center.x - (int) (mBounds.width() * s), center.y - (int) (mBounds.height() * s));
        }
        return pos;
    }

    private void startExitAnimation() {
        final boolean hyperOs = PopUpSettingsConfig.getInstance().isHyperOsMode();
        final float startAlpha = mIsFromLeaveButton && !hyperOs ? 0.5f : 1.0f;
        final float startScale = mStartScale;
        final float endScale = hyperOs ? mStartScale * .78f
                : mIsFromLeaveButton ? 0.0f : mStartScale;
        mWindowExitAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        mWindowExitAnimator.addUpdateListener(animation -> {
            synchronized (mLock) {
                final float progress = (float) animation.getAnimatedValue();
                final float currentAlpha = startAlpha + (0.0f - startAlpha) * progress;
                final float currentScale = startScale + (endScale - startScale) * progress;
                synchronized (mCancelLock) {
                    if (!mIsCancelling && mTask != null && mFrameTransaction != null) {
                        final SurfaceControl leash = mTask.mSurfaceControl;
                        if (leash != null && leash.isValid()) {
                            final Point pos = getPosition(currentScale);
                            mFrameTransaction.setPosition(leash, pos.x, pos.y);
                            mFrameTransaction.setAlpha(leash, currentAlpha);
                            mFrameTransaction.setScale(leash, currentScale, currentScale);
                            updateHyperOsPosition(pos.x, pos.y, currentScale);
                        }
                    }
                }
                scheduleApplyTransaction();
            }
        });
        mWindowExitAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "startExitAnimation: onAnimationStart");
                }
                mIsAnimating = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "startExitAnimation: onAnimationEnd mIsAnimating=" + mIsAnimating);
                }
                synchronized (mCancelLock) {
                    if (!mIsCancelling) {
                        if (mTask != null && mFrameTransaction != null) {
                            final SurfaceControl leash = mTask.mSurfaceControl;
                            if (leash != null && leash.isValid()) {
                                final Point pos = getPosition(endScale);
                                mFrameTransaction.setPosition(leash, pos.x, pos.y);
                                mFrameTransaction.setAlpha(leash, 0.0f);
                                mFrameTransaction.setScale(leash, endScale, endScale);
                                updateHyperOsPosition(pos.x, pos.y, endScale);
                            }
                        }
                        mService.mAnimationHandler.post(() -> {
                            if (mCallback != null) {
                                mCallback.onAnimationEnded();
                            }
                        });
                    }
                }
                scheduleApplyTransaction();
                synchronized (mLock) {
                    mIsAnimating = false;
                }
                notifyHyperOsAnimationFinished();
            }
        });
        mWindowExitAnimator.setDuration(hyperOs ? 220L : ANIMATION_CROSS_OVER_EXIT_DURATION);
        mWindowExitAnimator.setInterpolator(hyperOs ? HYPER_OS_INTERPOLATOR
                : new PathInterpolator(0.17f, 0.0f, 0.83f, 1.0f));
        mWindowExitAnimator.start();
    }

    void playToggleResizeWindowAnimation(Point startPos, Point endPos,
            float startWinScale, float endWinScale, OnAnimationEndCallback callback) {
        playToggleResizeWindowAnimation(startPos, endPos, startWinScale, endWinScale,
                Float.NaN, Float.NaN, callback);
    }

    void playToggleResizeWindowAnimation(Point startPos, Point endPos,
            float startWinScale, float endWinScale, float startExpansion, float endExpansion,
            OnAnimationEndCallback callback) {
        cancelAnimation();
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "playToggleResizeWindowAnimation");
        }
        if (mTask != null) {
            mTask.getBounds(mBounds);
        }
        mStartPos = startPos;
        mEndPos = endPos;
        mStartScale = startWinScale;
        mEndScale = endWinScale;
        mStartDecorExpansion = startExpansion;
        mEndDecorExpansion = endExpansion;
        mCallback = callback;
        mIsAnimating = true;
        updateHyperOsPosition(startPos.x, startPos.y, startWinScale, startExpansion);
        postFrameCallback(mFrameToggleResizeCallback);
    }

    private void startToggleResizeAnimation() {
        final boolean hyperOs = PopUpSettingsConfig.getInstance().isHyperOsMode();
        final Point startPos = mStartPos;
        final Point endPos = mEndPos;
        final float startScale = mStartScale;
        final float endScale = mEndScale;
        final float startExpansion = mStartDecorExpansion;
        final float endExpansion = mEndDecorExpansion;
        final OnAnimationEndCallback callback = mCallback;
        mToggleResizeAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        mToggleResizeAnimator.addUpdateListener(animation -> {
            synchronized (mLock) {
                final float progress = (float) animation.getAnimatedValue();
                final float currentPosX = startPos.x + (endPos.x - startPos.x) * progress;
                final float currentPosY = startPos.y + (endPos.y - startPos.y) * progress;
                final float currentScale = startScale + ((endScale - startScale) * progress);
                mCurrentAnimX = Math.round(currentPosX);
                mCurrentAnimY = Math.round(currentPosY);
                mCurrentAnimScale = currentScale;
                mHasAnimPosition = true;
                if (!hyperOs) {
                    updateOverlayPosition(mCurrentAnimX, mCurrentAnimY,
                            mBounds.width(), mBounds.height(), currentScale);
                }
                synchronized (mCancelLock) {
                    if (!mIsCancelling && mTask != null && mFrameTransaction != null) {
                        final SurfaceControl leash = mTask.mSurfaceControl;
                        if (leash != null && leash.isValid()) {
                            final float cornerRadius = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo().getCornerRadius();
                            mFrameTransaction.setPosition(leash, currentPosX, currentPosY);
                            mFrameTransaction.setScale(leash, currentScale, currentScale);
                            mFrameTransaction.setCornerRadius(leash, cornerRadius);
                            updateHyperOsPosition(currentPosX, currentPosY, currentScale,
                                    startExpansion + (endExpansion - startExpansion) * progress);
                        }
                    }
                }
                scheduleApplyTransaction();
            }
        });
        mToggleResizeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "startToggleResizeAnimation: onAnimationStart");
                }
                mIsAnimating = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "startToggleResizeAnimation: onAnimationEnd mIsAnimating=" + mIsAnimating);
                }
                synchronized (mCancelLock) {
                    if (!mIsCancelling) {
                        if (mTask != null && mFrameTransaction != null) {
                            final SurfaceControl leash = mTask.mSurfaceControl;
                            if (leash != null && leash.isValid()) {
                                final float cornerRadius = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo().getCornerRadius();
                                mFrameTransaction.setPosition(leash, endPos.x, endPos.y);
                                mFrameTransaction.setScale(leash, endScale, endScale);
                                mFrameTransaction.setCornerRadius(leash, cornerRadius);
                                updateHyperOsPosition(endPos.x, endPos.y, endScale, endExpansion);
                            }
                        }
                        mService.mAnimationHandler.post(() -> {
                            if (callback != null) {
                                callback.onAnimationEnded();
                            }
                        });
                    }
                }
                scheduleApplyTransaction();
                synchronized (mLock) {
                    mIsAnimating = false;
                }
                notifyHyperOsAnimationFinished();
            }
        });
        mToggleResizeAnimator.setDuration(hyperOs
                ? (Float.isNaN(endExpansion) ? 320L : endExpansion == 1f ? 420L : 360L)
                : ANIMATION_RESIZE);
        mToggleResizeAnimator.setInterpolator(hyperOs ? HYPER_OS_INTERPOLATOR
                : new PathInterpolator(0.2f, 0f, 0f, 1f));
        mToggleResizeAnimator.start();
    }

    void playResizeAnimation(Point startPos, Point endPos,
            int boundWidth, int boundHeight,
            float startWinScale, float endWinScale,
            Rect displayBound, boolean isLandscape, TaskWindowSurfaceInfo info) {
        final boolean hyperOs = PopUpSettingsConfig.getInstance().isHyperOsMode();
        final ValueAnimator windowScaleAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        windowScaleAnimator.addUpdateListener(animation -> {
            final float progress = (float) animation.getAnimatedValue();
            final float currentScale = startWinScale + (endWinScale - startWinScale) * progress;
            final int x = (int) (startPos.x + (endPos.x - startPos.x) * progress);
            final int y = (int) (startPos.y + (endPos.y - startPos.y) * progress);
            info.setWindowSurfaceScaleDrag(currentScale / info.getWindowSurfaceScaleFactor(),
                    displayBound, isLandscape);
            doWindowTransition(x, y, currentScale, boundWidth, boundHeight);
        });
        windowScaleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "playResizeAnimation: onAnimationStart");
                }
                mIsAnimating = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "playResizeAnimation: onAnimationEnd mIsAnimating=" + mIsAnimating);
                }
                if (mIsAnimating) {
                    info.setWindowSurfaceScaleDrag(endWinScale / info.getWindowSurfaceScaleFactor(),
                            displayBound, isLandscape);
                    doWindowTransition(endPos.x, endPos.y, endWinScale, boundWidth, boundHeight);
                    mIsAnimating = false;
                }
                notifyHyperOsAnimationFinished();
            }
        });
        windowScaleAnimator.setDuration(hyperOs ? 320L : ANIMATION_SCALE_DURATION);
        windowScaleAnimator.setInterpolator(hyperOs ? HYPER_OS_INTERPOLATOR
                : new AccelerateDecelerateInterpolator());
        windowScaleAnimator.start();
    }

    void playSpringAnimation(Point startPos, Point endPos,
            int boundWidth, int boundHeight, float winScale, float velX, float velY) {
        playSpringAnimation(startPos, endPos, boundWidth, boundHeight, winScale, velX, velY, null);
    }

    void playSpringAnimation(Point startPos, Point endPos,
            int boundWidth, int boundHeight, float winScale, float velX, float velY,
            OnAnimationEndCallback callback) {
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "playSpringAnimation");
        }
        mIsAnimating = true;
        mCallback = callback;
        updateValueHolder(startPos.x, startPos.y);
        mStartPos = startPos;
        mEndPos = endPos;
        mBoundWidth = boundWidth;
        mBoundHeight = boundHeight;
        mWindowScale = winScale;
        mVelX = velX;
        mVelY = velY;
        postFrameCallback(mFrameCallback);
    }

    private void startAnimations() {
        mRunningSpringAnimations = 2;
        mSpringAnimationX = startSpringAnimation(mValueHolderX, mEndPos.x, mVelX);
        mSpringAnimationY = startSpringAnimation(mValueHolderY, mEndPos.y, mVelY);
    }

    private void updateValueHolder(float valueX, float valueY) {
        if (mValueHolderX == null || mValueHolderY == null) {
            mValueHolderX = new FloatValueHolder(valueX);
            mValueHolderY = new FloatValueHolder(valueY);
            return;
        }
        mValueHolderX.setValue(valueX);
        mValueHolderY.setValue(valueY);
    }

    private SpringAnimation startSpringAnimation(FloatValueHolder valueHolder, float endValue,
            float velocity) {
        final boolean hyperOs = PopUpSettingsConfig.getInstance().isHyperOsMode();
        final SpringForce springForce = new SpringForce();
        springForce.setStiffness(hyperOs ? 380f : STPRING_STIFFNESS);
        springForce.setDampingRatio(hyperOs ? .85f : DAMPING_RATIO);
        springForce.setFinalPosition(endValue);

        final SpringAnimation springAnimation = new SpringAnimation(valueHolder, velocity);
        springAnimation.setStartVelocity(velocity).setSpring(springForce)
                .addUpdateListener((anim, val, vel) -> {
                    final int x;
                    final int y;
                    final int boundWidth;
                    final int boundHeight;
                    final float windowScale;
                    synchronized (mLock) {
                        synchronized (mCancelLock) {
                            if (mIsCancelling) {
                                return;
                            }
                            x = (int) mValueHolderX.getValue();
                            y = (int) mValueHolderY.getValue();
                            boundWidth = mBoundWidth;
                            boundHeight = mBoundHeight;
                            windowScale = mWindowScale;
                            setTaskPosition(x, y, windowScale, boundWidth, boundHeight);
                        }
                        scheduleApplyTransaction();
                    }
                    if (!hyperOs) {
                        updateOverlayPosition(x, y, boundWidth, boundHeight, windowScale);
                    }
                })
                .addEndListener((anim, canceled, val, vel) -> {
                    final boolean finished;
                    synchronized (mLock) {
                        if (mRunningSpringAnimations > 0) {
                            mRunningSpringAnimations--;
                        }
                        finished = mRunningSpringAnimations == 0;
                        if (!finished) {
                            return;
                        }
                        final int x = mEndPos.x;
                        final int y = mEndPos.y;
                        if (DEBUG_POP_UP) {
                            Slog.d(TAG, "SpringAnimation: onAnimationEnd mIsAnimating=" + mIsAnimating);
                        }
                        synchronized (mCancelLock) {
                            if (!mIsCancelling && !canceled) {
                                setTaskPosition(x, y, mWindowScale, mBoundWidth, mBoundHeight);
                            }
                        }
                        if (!canceled && mFrameTransaction != null) {
                            if (hyperOs) {
                                // Reuse the queued frame callback instead of applying once here
                                // and a second time from that callback. It takes the WM lock only
                                // after this animation lock has been released.
                                scheduleApplyTransaction();
                            } else {
                                mFrameTransaction.apply();
                            }
                        }
                        final OnAnimationEndCallback cb = mCallback;
                        mIsAnimating = false;
                        if (!mIsCancelling && !canceled && cb != null) {
                            mService.mAnimationHandler.post(cb::onAnimationEnded);
                        }
                    }
                    if (!hyperOs && !mIsCancelling && !canceled) {
                        updateOverlayPosition(mEndPos.x, mEndPos.y,
                                mBoundWidth, mBoundHeight, mWindowScale);
                    }
                });
        springAnimation.start();
        return springAnimation;
    }

    boolean isCrossOverAnimating() {
        synchronized (mLock) {
            return isAnimating() || (mCrossOverAnimator != null && mCrossOverAnimator.isRunning());
        }
    }

    boolean getCurrentAnimPosition(int[] outXY, float[] outScale) {
        if (PopUpSettingsConfig.getInstance().isHyperOsMode()) {
            final AnimationPosition position = mHyperOsAnimationPosition;
            if (position == null) return false;
            outXY[0] = position.x;
            outXY[1] = position.y;
            outScale[0] = position.scale;
            return true;
        }
        if (!mHasAnimPosition) return false;
        outXY[0] = mCurrentAnimX;
        outXY[1] = mCurrentAnimY;
        outScale[0] = mCurrentAnimScale;
        return true;
    }

    void clearAnimPosition() {
        mHyperOsAnimationPosition = null;
        mHasAnimPosition = false;
    }

    float getDecorExpansion() {
        final AnimationPosition position = mHyperOsAnimationPosition;
        return position != null ? position.decorExpansion : Float.NaN;
    }

    boolean isAnimating() {
        synchronized (mLock) {
            if (mIsAnimating || mIsCancelling) {
                return true;
            }
            if (mSpringAnimationX != null && mSpringAnimationX.isRunning()) {
                return true;
            }
            if (mSpringAnimationY != null && mSpringAnimationY.isRunning()) {
                return true;
            }
            if (mWindowExitAnimator != null && mWindowExitAnimator.isRunning()) {
                return true;
            }
            if (mToggleResizeAnimator != null && mToggleResizeAnimator.isRunning()) {
                return true;
            }
            if (mCrossOverAnimator != null && mCrossOverAnimator.isRunning()) {
                return true;
            }
            if (mDockAnimator != null && mDockAnimator.isRunning()) {
                return true;
            }
            return false;
        }
    }

    void playDockAnimation(int startX, int startY, int endX, int endY,
            int startCropW, int startCropH, int endCropW, int endCropH,
            float startAlpha, float endAlpha, float scale, Task task, TaskWindowSurfaceInfo info) {
        final boolean hyperOs = PopUpSettingsConfig.getInstance().isHyperOsMode();
        mIsAnimating = true;
        mDockAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        mDockAnimator.addUpdateListener(animation -> {
            synchronized (mLock) {
                final float progress = (float) animation.getAnimatedValue();
                final float easeProgress = hyperOs ? progress
                        : 1.0f - (1.0f - progress) * (1.0f - progress);
                final int x = (int) (startX + (endX - startX) * easeProgress);
                final int y = (int) (startY + (endY - startY) * easeProgress);
                final int cropW = (int) (startCropW + (endCropW - startCropW) * easeProgress);
                final int cropH = (int) (startCropH + (endCropH - startCropH) * easeProgress);
                final float alpha = startAlpha + (endAlpha - startAlpha) * easeProgress;
                synchronized (mCancelLock) {
                    if (!mIsCancelling && task != null && mFrameTransaction != null) {
                        final SurfaceControl leash = task.mSurfaceControl;
                        if (leash != null && leash.isValid()) {
                            mFrameTransaction.setPosition(leash, x, y)
                                    .setWindowCrop(leash, cropW, cropH)
                                    .setScale(leash, scale, scale)
                                    .setAlpha(leash, alpha)
                                    .setCornerRadius(leash, 0)
                                    .show(leash);
                            updateHyperOsPosition(x, y, scale);
                        }
                    }
                }
                scheduleApplyTransaction();
            }
        });
        mDockAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                synchronized (mCancelLock) {
                    if (!mIsCancelling && task != null && mFrameTransaction != null) {
                        final SurfaceControl leash = task.mSurfaceControl;
                        if (leash != null && leash.isValid()) {
                            mFrameTransaction.setPosition(leash, endX, endY)
                                    .setWindowCrop(leash, endCropW, endCropH)
                                    .setScale(leash, scale, scale)
                                    .setAlpha(leash, endAlpha)
                                    .setCornerRadius(leash, 0)
                                    .show(leash);
                            updateHyperOsPosition(endX, endY, scale);
                        }
                    }
                }
                scheduleApplyTransaction();
                synchronized (mLock) {
                    mIsAnimating = false;
                }
                notifyHyperOsAnimationFinished();
                // Let cancellation clear its flags before checking policy on the WM thread.
                // Never take the WM lock while holding the animation locks.
                mSurfaceAnimationHandler.post(() ->
                        mService.mH.post(info::updateDockedForegroundState));
            }
        });
        mDockAnimator.setDuration(hyperOs ? 220L : 250L);
        mDockAnimator.setInterpolator(hyperOs ? HYPER_OS_INTERPOLATOR
                : new AccelerateDecelerateInterpolator());
        mDockAnimator.start();
    }

    void playRestoreAnimation(int startX, int startY, int endX, int endY,
            int startCropW, int startCropH, int endCropW, int endCropH,
            float startAlpha, float endAlpha, float scale, float cornerRadius,
            Task task, TaskWindowSurfaceInfo info) {
        final boolean hyperOs = PopUpSettingsConfig.getInstance().isHyperOsMode();
        mIsAnimating = true;
        mDockAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        mDockAnimator.addUpdateListener(animation -> {
            synchronized (mLock) {
                final float progress = (float) animation.getAnimatedValue();
                final float easeProgress = hyperOs ? progress
                        : progress * progress * (3.0f - 2.0f * progress);
                final int x = (int) (startX + (endX - startX) * easeProgress);
                final int y = (int) (startY + (endY - startY) * easeProgress);
                final int cropW = (int) (startCropW + (endCropW - startCropW) * easeProgress);
                final int cropH = (int) (startCropH + (endCropH - startCropH) * easeProgress);
                final float alpha = startAlpha + (endAlpha - startAlpha) * easeProgress;
                synchronized (mCancelLock) {
                    if (!mIsCancelling && task != null && mFrameTransaction != null) {
                        final SurfaceControl leash = task.mSurfaceControl;
                        if (leash != null && leash.isValid()) {
                            mFrameTransaction.setPosition(leash, x, y)
                                    .setWindowCrop(leash, cropW, cropH)
                                    .setScale(leash, scale, scale)
                                    .setAlpha(leash, alpha)
                                    .setCornerRadius(leash, cornerRadius * easeProgress)
                                    .show(leash);
                            updateHyperOsPosition(x, y, scale);
                        }
                    }
                }
                scheduleApplyTransaction();
            }
        });
        mDockAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                synchronized (mCancelLock) {
                    if (!mIsCancelling && task != null && mFrameTransaction != null) {
                        final SurfaceControl leash = task.mSurfaceControl;
                        if (leash != null && leash.isValid()) {
                            mFrameTransaction.setPosition(leash, endX, endY)
                                    .setWindowCrop(leash, endCropW, endCropH)
                                    .setScale(leash, scale, scale)
                                    .setAlpha(leash, endAlpha)
                                    .setCornerRadius(leash, cornerRadius)
                                    .show(leash);
                            updateHyperOsPosition(endX, endY, scale);
                        }
                    }
                }
                scheduleApplyTransaction();
                synchronized (mLock) {
                    mIsAnimating = false;
                }
                notifyHyperOsAnimationFinished();
            }
        });
        mDockAnimator.setDuration(hyperOs ? 360L : 300L);
        mDockAnimator.setInterpolator(hyperOs ? HYPER_OS_INTERPOLATOR
                : new OvershootInterpolator(0.8f));
        mDockAnimator.start();
    }

    boolean cancelAnimation() {
        if (isAnimating()) {
            synchronized (mLock) {
                mIsAnimating = false;
                synchronized (mCancelLock) {
                    mIsCancelling = true;
                }
                postFrameCallback(mFrameCancelCallback);
            }
            return true;
        }
        return false;
    }

    private void doWindowTransition(int x, int y, float scale, int boundWidth, int boundHeight) {
        final SurfaceControl leash = mTask.mSurfaceControl;
        if (leash != null && leash.isValid()) {
            final float cornerRadius = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo().getCornerRadius();
            final boolean hyperOs = PopUpSettingsConfig.getInstance().isHyperOsMode();
            final SurfaceControl.Transaction transaction = hyperOs
                    ? mService.mTransactionFactory.get() : mTask.getSyncTransaction();
            transaction.setPosition(leash, x, y)
                    .setWindowCrop(leash, boundWidth, boundHeight)
                    .setScale(leash, scale, scale)
                    .setCornerRadius(leash, cornerRadius)
                    .show(leash);
            if (hyperOs) {
                // Publish before snapshotting the decoration for this transaction.
                updateHyperOsPosition(x, y, scale);
                HyperOsWindowDecor.getInstance().applyTransaction(mTask, transaction,
                        new Rect(x, y, x + Math.round(boundWidth * scale),
                                y + Math.round(boundHeight * scale)), scale);
            } else {
                transaction.apply();
            }
        }
    }

}
