/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The AviumUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.view.WindowManager.TRANSIT_CHANGE;
import static com.android.internal.arkui.DebugConstants.DEBUG_POP_UP;

import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Debug;
import android.util.Pair;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ClipRectAnimation;
import android.view.animation.PathInterpolator;
import android.view.animation.RadiusAnimation;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateXAnimation;
import android.view.animation.TranslateYAnimation;

import com.android.server.wm.Transition.ChangeInfo.PopUpViewInfo;

class WindowContainerExt implements SurfaceFreezerExt.Freezable, SurfaceAnimator.Animatable {

    private static final String TAG = "WindowContainerExt";

    private final Rect mPrevBounds = new Rect();

    final SurfaceFreezerExt mFreezerExt;
    private final TransitionController mTransitionController;
    private final WindowContainer mWc;

    private TaskWindowSurfaceInfo mTaskWindowSurfaceInfo;
    private Transition mTransition;
    private boolean mOwnsTransition;

    private int mWindowingMode;
    private boolean mFinishTopTask;

    WindowContainerExt(WindowContainer wc) {
        mWc = wc;
        mFreezerExt = new SurfaceFreezerExt(this, wc.mWmService);
        mTransitionController = wc.mTransitionController;
    }

    WindowContainerExt(WindowContainer wc, Object freezer) {
        this(wc);
    }

    @Override
    public SurfaceControl getSurfaceControl() {
        return mWc != null ? mWc.getSurfaceControl() : null;
    }

    @Override
    public SurfaceControl.Builder makeAnimationLeash() {
        return mWc != null ? mWc.makeSurface() : null;
    }

    @Override
    public SurfaceControl getParentSurfaceControl() {
        return mWc != null && mWc.getParent() != null ? mWc.getParent().getSurfaceControl() : null;
    }

    @Override
    public void onAnimationLeashCreated(Transaction t, SurfaceControl leash) {
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "Animation leash created for " + mWc + ", leash=" + leash);
        }
    }

    @Override
    public void onAnimationLeashLost(Transaction t) {
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "Animation leash lost for " + mWc);
        }
    }

    @Override
    public void onAnimationLeashDestroyed(Transaction t) {
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "Animation leash destroyed for " + mWc);
        }
    }

    @Override
    public int getSurfaceWidth() {
        return mWc != null ? mWc.getBounds().width() : 0;
    }

    @Override
    public int getSurfaceHeight() {
        return mWc != null ? mWc.getBounds().height() : 0;
    }

    @Override
    public SurfaceControl getAnimationLeashParent() {
        return getParentSurfaceControl();
    }

    @Override
    public Transaction getSyncTransaction() {
        return mWc != null ? mWc.getSyncTransaction() : null;
    }

    @Override
    public Transaction getPendingTransaction() {
        return mWc != null ? mWc.getPendingTransaction() : null;
    }

    @Override
    public void commitPendingTransaction() {
        if (mWc != null) {
            mWc.commitPendingTransaction();
        }
    }

    void onWindowingModeChanged(int preWindowMode) {
        if (mTaskWindowSurfaceInfo != null) {
            mTaskWindowSurfaceInfo.onWindowingModeChanged(preWindowMode);
        }
    }

    void onConfigurationChanged() {
        if (mTaskWindowSurfaceInfo != null) {
            mTaskWindowSurfaceInfo.onConfigurationChanged();
        }
    }

    Pair<AnimationAdapter, AnimationAdapter> getAnimationAdapter(DisplayInfo displayInfo, float durationScale) {
        if (mWc.getWindowConfiguration().isPopUpWindowMode() ||
                PopUpWindowController.getInstance().isTryExitWindowingMode() ||
                mFreezerExt.hasFreezeTaskWindowSurfaceInfo()) {
            final TaskWindowSurfaceInfo freezedInfo = mFreezerExt.getFreezeTaskWindowSurfaceInfo();
            if (freezedInfo == null) {
                return null;
            }

            final int startWinMode = freezedInfo.mFreezedWindowingMode;
            final int endWinMode = mWc.getWindowingMode();
            final boolean isMiniToFull = WindowConfiguration.isMiniExtWindowMode(startWinMode)
                    && !WindowConfiguration.isPopUpWindowMode(endWinMode);
            final boolean isFullToPin = !WindowConfiguration.isPopUpWindowMode(startWinMode)
                    && WindowConfiguration.isPinnedExtWindowMode(endWinMode);
            final boolean isPinToFull = WindowConfiguration.isPinnedExtWindowMode(startWinMode)
                    && !WindowConfiguration.isPopUpWindowMode(endWinMode);

            final WindowChangeAnimationSpecExt.IAnimationCallBack animationCallBack =
                    new WindowChangeAnimationSpecExt.IAnimationCallBack() {
                @Override
                public void onAnimationInit(Animation animation, boolean isThumbnail) {
                    onAnimationInitInternal(animation, isThumbnail, isMiniToFull, isFullToPin,
                            isPinToFull, durationScale);
                }
            };

            final AnimationAdapter adapter = new LocalAnimationAdapter(
                    new WindowChangeAnimationSpecExt(freezedInfo, mTaskWindowSurfaceInfo,
                            1.0f, displayInfo, durationScale, true, false, false, true, animationCallBack),
                    mWc.getSurfaceAnimationRunner());
            AnimationAdapter thumbnailAdapter = null;
            if (mFreezerExt.getSnapshot() != null) {
                final WindowChangeAnimationSpecExt.IAnimationCallBack thumbnailCallBack =
                        new WindowChangeAnimationSpecExt.IAnimationCallBack() {
                    @Override
                    public void onAnimationInit(Animation animation, boolean isThumbnail) {
                        onThumbnailAnimationInitInternal(animation, isThumbnail, durationScale);
                    }
                };
                thumbnailAdapter = new LocalAnimationAdapter(
                        new WindowChangeAnimationSpecExt(freezedInfo, mTaskWindowSurfaceInfo,
                                1.0f, displayInfo, durationScale, true, true, false, true, thumbnailCallBack),
                        mWc.getSurfaceAnimationRunner());
            }
            return new Pair<>(adapter, thumbnailAdapter);
        }
        return null;
    }

    private void onAnimationInitInternal(Animation animation, boolean isThumbnail,
            boolean isMiniToFull, boolean isFullToPin, boolean isPinToFull, float durationScale) {
        if (isThumbnail) {
            return;
        }
        if (isMiniToFull || isFullToPin) {
            if (animation instanceof AnimationSet) {
                animation.setInterpolator(new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f));
                animation.setDuration((long) (420.0f * durationScale));
            }
            return;
        }
        if (isPinToFull) {
            if (animation instanceof AnimationSet) {
                animation.setInterpolator(new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f));
            }
            if (animation instanceof TranslateXAnimation
                    || animation instanceof TranslateYAnimation
                    || animation instanceof RadiusAnimation) {
                animation.setDuration((long) (250.0f * durationScale));
            } else if (animation instanceof ScaleAnimation
                    || animation instanceof ClipRectAnimation) {
                animation.setDuration((long) (400.0f * durationScale));
            }
        }
    }

    private void onThumbnailAnimationInitInternal(Animation animation, boolean isThumbnail,
            float durationScale) {
        if (isThumbnail) {
            if (animation instanceof AlphaAnimation) {
                animation.setInterpolator(new PathInterpolator(0.42f, 0.0f, 0.58f, 1.0f));
                animation.setDuration((long) (20.0f * durationScale));
                animation.setStartOffset((long) (400.0f * durationScale));
            } else {
                animation.setInterpolator(new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f));
                animation.setDuration((long) (420.0f * durationScale));
            }
        }
    }

    PopUpViewInfo getPopUpViewInfo() {
        return mTaskWindowSurfaceInfo != null ? mTaskWindowSurfaceInfo.getPopUpViewInfo() : null;
    }

    void getPrevBounds(Rect rect) {
        if (rect != null) {
            rect.set(mPrevBounds);
        }
    }

    TaskWindowSurfaceInfo getTaskWindowSurfaceInfo() {
        return mTaskWindowSurfaceInfo;
    }

    SurfaceFreezerExt getFreezerExt() {
        return mFreezerExt;
    }

    boolean getFreezerSkipAnim() {
        return mFreezerExt.mNoWindowModeAnim;
    }

    void setFinishTopTask(boolean finishTopTask) {
        mFinishTopTask = finishTopTask;
    }

    void setFreezerSkipAnim(boolean skip) {
        mFreezerExt.mNoWindowModeAnim = skip;
    }

    boolean setPreFreezedWindowingMode(int windowingMode) {
        mFreezerExt.setPreFreezedWindowingMode(windowingMode);
        return true;
    }

    boolean setOrientation(WindowContainer parent) {
        if (mWc == null || parent == null ||
                (!mWc.getWindowConfiguration().isPopUpWindowMode() &&
                !parent.getWindowConfiguration().isPopUpWindowMode())) {
            return false;
        }
        final int requestedOrientation = mWc.getRequestedConfigurationOrientation();
        final int systemOrientation = mWc.mWmService.mRoot.getConfiguration().orientation;
        final Configuration overrideConfig = parent.getRequestedOverrideConfiguration();
        final Rect tmpBounds = parent.getConfiguration().windowConfiguration.getBounds();
        final int height = Math.max(tmpBounds.height(), tmpBounds.width());
        final int width = Math.min(tmpBounds.height(), tmpBounds.width());
        final Rect newBounds;
        if (requestedOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            newBounds = new Rect(0, 0, height, width);
        } else {
            newBounds = new Rect(0, 0, width, height);
        }
        newBounds.offsetTo(tmpBounds.left, tmpBounds.top);
        overrideConfig.windowConfiguration.setBounds(newBounds);
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "setOrientation: requestedOrientation=" + requestedOrientation +
                    ", systemOrientation=" + systemOrientation + " tmpBounds=" + tmpBounds +
                    " newBounds" + newBounds + " mWc=" + mWc + " parent=" + parent +
                    " callers=" + Debug.getCallers(2));
        }
        if (!tmpBounds.equals(newBounds)) {
            parent.onRequestedOverrideConfigurationChanged(overrideConfig);
        }
        if (mWc.getWindowConfiguration().isPinnedExtWindowMode()) {
            TopActivityRecorder.getInstance().updateTopPinnedWindowActivity(mWc.asActivityRecord());
        }
        return true;
    }

    void initTask(Task task) {
        mTaskWindowSurfaceInfo = new TaskWindowSurfaceInfo(task);
    }

    boolean initializeChangeTransition(Rect startBounds) {
        if (mWc.getWindowConfiguration().isPopUpWindowMode() ||
                PopUpWindowController.getInstance().isTryExitWindowingMode()) {
            mFreezerExt.freeze(mWc.getSyncTransaction(), startBounds, mTaskWindowSurfaceInfo);
            return true;
        }
        return false;
    }

    void prepareTransition() {
        if (!mTransitionController.isShellTransitionsEnabled() || mTransition != null) {
            return;
        }
        mPrevBounds.set(mWc.getBounds());
        mWindowingMode = mWc.getWindowingMode();
        mOwnsTransition = !mTransitionController.isCollecting();
        mTransition = mOwnsTransition
                ? mTransitionController.createTransition(TRANSIT_CHANGE)
                : mTransitionController.getCollectingTransition();
        if (mTaskWindowSurfaceInfo != null) {
            // Joining another transition still needs geometry captured before the resize.
            mFreezerExt.setPreFreezedWindowingMode(mWindowingMode);
            mFreezerExt.transitionFreeze(mPrevBounds, mTaskWindowSurfaceInfo);
        }
        if ((mWc.asWallpaperToken() != null &&
                !mTransition.isInTransition(mWc.mDisplayContent)) ||
                mWc.getSyncGroup() == null ||
                mWc.getSyncGroup() == mWc.mWmService.mSyncEngine.getSyncSet(mTransition.getSyncId())) {
            mTransitionController.collect(mWc);
            if (mOwnsTransition) mTransition.setReady(mWc, true);
        }
    }

    void scheduleTransition() {
        if (mTransition == null) {
            return;
        }
        if (mWc.getWindowConfiguration().isPopUpWindowMode() ||
                PopUpWindowController.getInstance().isTryExitWindowingMode() ||
                mFreezerExt.hasFreezeTaskWindowSurfaceInfo()) {
            final TaskWindowSurfaceInfo info = mFreezerExt.takeFreezeTaskWindowSurfaceInfo();
            if (info != null) {
                mTaskWindowSurfaceInfo.scheduleTransition(info, mWc.getDisplayContent().getDisplayInfo());
            }
        }
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "scheduleTransition(), requestStartTransition");
        }
        if (mOwnsTransition) {
            mTransitionController.requestStartTransition(mTransition, mWc.asTask(), null, null);
        }
        mTransition = null;
        mOwnsTransition = false;
    }

    void transitionFreeze(Task task) {
        if (mTransition == null) {
            return;
        }
        if (!PopUpWindowController.getInstance().shouldStartChangeTransition(
                mWindowingMode, task.getWindowingMode())) {
            return;
        }
        if (!PopUpWindowController.getInstance().shouldInitializeChangeTransition(task, mWindowingMode)) {
            return;
        }
        mFreezerExt.transitionFreeze(task.mTmpPrevBounds, mTaskWindowSurfaceInfo);
    }

    boolean isFinishTopTask() {
        return mFinishTopTask;
    }

    public boolean shouldDeferAnimationFinish(Runnable endDeferFinishCallback) {
        return false;
    }
}
