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

    private OnAnimationEndCallback mCallback;
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
    private float mWindowScale;
    private float mVelX;
    private float mVelY;

    private boolean mIsAnimating;
    private boolean mIsCancelling;
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
                synchronized (mCancelLock) {
                    mIsCancelling = false;
                }
            }
        };
        mService = wms;
        mBounds = new Rect();
        mLastAnimatingScale = -1.0f;
        mSurfaceAnimationHandler.runWithScissors(() -> {
            mChoreographer = Choreographer.getSfInstance();
        }, 0L);
    }

    void setTask(Task task) {
        mTask = task;
        if (mTask != null) {
            mFrameTransaction = mService.mTransactionFactory.get();
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
            }
            mCurrentAnimX = x;
            mCurrentAnimY = y;
            mCurrentAnimScale = scale;
            mHasAnimPosition = true;
        }
    }

    private void scheduleApplyTransaction() {
        if (!mApplyScheduled && mFrameTransaction != null) {
            mChoreographer.postCallback(Choreographer.CALLBACK_TRAVERSAL,
                    () -> {
                        mFrameTransaction.apply();
                        mApplyScheduled = false;
                    }, null);
            mApplyScheduled = true;
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
                        }
                        mLastAnimatingScale = -1.0f;
                    }
                }
                scheduleApplyTransaction();
                synchronized (mLock) {
                    mIsAnimating = false;
                }
            }
        });
        mCrossOverAnimator.setDuration(ANIMATION_CROSS_OVER_EXIT_DURATION);
        mCrossOverAnimator.setInterpolator(new PathInterpolator(0.17f, 0.0f, 0.83f, 1.0f));
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
        mChoreographer.postFrameCallback(mFrameExitCallback);
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
        final float startAlpha = mIsFromLeaveButton ? 0.5f : 1.0f;
        final float startScale = mStartScale;
        final float endScale = mIsFromLeaveButton ? 0.0f : mStartScale;
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
            }
        });
        mWindowExitAnimator.setDuration(ANIMATION_CROSS_OVER_EXIT_DURATION);
        mWindowExitAnimator.setInterpolator(new PathInterpolator(0.17f, 0.0f, 0.83f, 1.0f));
        mWindowExitAnimator.start();
    }

    void playToggleResizeWindowAnimation(Point startPos, Point endPos,
            float startWinScale, float endWinScale, OnAnimationEndCallback callback) {
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
        mCallback = callback;
        mIsAnimating = true;
        mChoreographer.postFrameCallback(mFrameToggleResizeCallback);
    }

    private void startToggleResizeAnimation() {
        final Point startPos = mStartPos;
        final Point endPos = mEndPos;
        final float startScale = mStartScale;
        final float endScale = mEndScale;
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
                PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                        mTask, mCurrentAnimX, mCurrentAnimY,
                        mBounds.width(), mBounds.height(), currentScale);
                synchronized (mCancelLock) {
                    if (!mIsCancelling && mTask != null && mFrameTransaction != null) {
                        final SurfaceControl leash = mTask.mSurfaceControl;
                        if (leash != null && leash.isValid()) {
                            final float cornerRadius = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo().getCornerRadius();
                            mFrameTransaction.setPosition(leash, currentPosX, currentPosY);
                            mFrameTransaction.setScale(leash, currentScale, currentScale);
                            mFrameTransaction.setCornerRadius(leash, cornerRadius);
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
            }
        });
        mToggleResizeAnimator.setDuration(ANIMATION_RESIZE);
        mToggleResizeAnimator.setInterpolator(new android.view.animation.PathInterpolator(
                0.2f, 0f, 0f, 1f));
        mToggleResizeAnimator.start();
    }

    void playResizeAnimation(Point startPos, Point endPos,
            int boundWidth, int boundHeight,
            float startWinScale, float endWinScale,
            Rect displayBound, boolean isLandscape, TaskWindowSurfaceInfo info) {
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
            }
        });
        windowScaleAnimator.setDuration(ANIMATION_SCALE_DURATION);
        windowScaleAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
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
        mChoreographer.postFrameCallback(mFrameCallback);
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
        final SpringForce springForce = new SpringForce();
        springForce.setStiffness(STPRING_STIFFNESS);
        springForce.setDampingRatio(DAMPING_RATIO);
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
                    PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                            mTask, x, y, boundWidth, boundHeight, windowScale);
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
                            mFrameTransaction.apply();
                        }
                        final OnAnimationEndCallback cb = mCallback;
                        mIsAnimating = false;
                        if (!mIsCancelling && !canceled && cb != null) {
                            mService.mAnimationHandler.post(cb::onAnimationEnded);
                        }
                    }
                    if (!mIsCancelling && !canceled) {
                        PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                                mTask, mEndPos.x, mEndPos.y, mBoundWidth, mBoundHeight, mWindowScale);
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
        if (!mHasAnimPosition) return false;
        outXY[0] = mCurrentAnimX;
        outXY[1] = mCurrentAnimY;
        outScale[0] = mCurrentAnimScale;
        return true;
    }

    void clearAnimPosition() {
        mHasAnimPosition = false;
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
        mIsAnimating = true;
        mDockAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        mDockAnimator.addUpdateListener(animation -> {
            synchronized (mLock) {
                final float progress = (float) animation.getAnimatedValue();
                final float easeProgress = 1.0f - (1.0f - progress) * (1.0f - progress);
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
                        }
                    }
                }
                scheduleApplyTransaction();
                synchronized (mLock) {
                    mIsAnimating = false;
                }
                // Let cancellation clear its flags before checking policy on the WM thread.
                // Never take the WM lock while holding the animation locks.
                mSurfaceAnimationHandler.post(() ->
                        mService.mH.post(info::updateDockedForegroundState));
            }
        });
        mDockAnimator.setDuration(250L);
        mDockAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        mDockAnimator.start();
    }

    void playRestoreAnimation(int startX, int startY, int endX, int endY,
            int startCropW, int startCropH, int endCropW, int endCropH,
            float startAlpha, float endAlpha, float scale, float cornerRadius,
            Task task, TaskWindowSurfaceInfo info) {
        mIsAnimating = true;
        mDockAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        mDockAnimator.addUpdateListener(animation -> {
            synchronized (mLock) {
                final float progress = (float) animation.getAnimatedValue();
                final float easeProgress = progress * progress * (3.0f - 2.0f * progress);
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
                        }
                    }
                }
                scheduleApplyTransaction();
                synchronized (mLock) {
                    mIsAnimating = false;
                }
            }
        });
        mDockAnimator.setDuration(300L);
        mDockAnimator.setInterpolator(new OvershootInterpolator(0.8f));
        mDockAnimator.start();
    }

    boolean cancelAnimation() {
        if (isAnimating()) {
            synchronized (mLock) {
                mIsAnimating = false;
                synchronized (mCancelLock) {
                    mIsCancelling = true;
                }
                mChoreographer.postFrameCallback(mFrameCancelCallback);
            }
            return true;
        }
        return false;
    }

    private void doWindowTransition(int x, int y, float scale, int boundWidth, int boundHeight) {
        final SurfaceControl leash = mTask.mSurfaceControl;
        if (leash != null && leash.isValid()) {
            final float cornerRadius = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo().getCornerRadius();
            mTask.getSyncTransaction().setPosition(leash, x, y)
                    .setWindowCrop(leash, boundWidth, boundHeight)
                    .setScale(leash, scale, scale)
                    .setCornerRadius(leash, cornerRadius)
                    .show(leash).apply();
        }
    }

}
