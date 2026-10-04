/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The AviumUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.internal.arkui.DebugConstants.DEBUG_POP_UP;

import android.app.WindowConfiguration;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ClipRectAnimation;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.view.animation.RadiusAnimation;
import android.view.animation.ScaleAnimation;
import android.view.animation.Transformation;
import android.view.animation.TranslateXAnimation;
import android.view.animation.TranslateYAnimation;

import java.io.PrintWriter;
import java.util.function.Supplier;

class WindowChangeAnimationSpecExt implements LocalAnimationAdapter.AnimationSpec {

    private static final String TAG = "WindowChangeAnimationSpecExt";

    static final int ANIMATION_DURATION_MODE_CHANGING = 336;

    private static final Interpolator INTERPOLATOR_MODE_CHANGING =
            new PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f);

    private static final Interpolator INTERPOLATOR_MINI_TO_FULL =
            new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f);

    private static final Interpolator INTERPOLATOR_THUMBNAIL_ALPHA =
            new PathInterpolator(0.42f, 0.0f, 0.58f, 1.0f);

    private final TaskWindowSurfaceInfo mStartInfo;
    private final TaskWindowSurfaceInfo mEndInfo;

    private final boolean mIsAppAnimation;
    private final boolean mIsThumbnail;
    private final boolean mIsPivotCenter;
    private final float mScalePart;

    private Animation mAnimation;
    private float mStartCornerRadius;
    private float mEndCornerRadius;
    private float mStartAlpha;
    private float mEndAlpha;
    private Rect mStartBounds;
    private Rect mEndBounds;
    private Rect mStartClipBounds;
    private Rect mEndClipBounds;
    private Rect mStartAppBound;
    private Rect mEndAppBound;

    private final ThreadLocal<TmpValues> mThreadLocalTmps =
            ThreadLocal.withInitial(() -> new TmpValues());
    private final Rect mTmpRect = new Rect();

    interface IAnimationCallBack {
        void onAnimationInit(Animation animation, boolean isThumbnail);
    }

    WindowChangeAnimationSpecExt(TaskWindowSurfaceInfo startInfo, TaskWindowSurfaceInfo endInfo,
            DisplayInfo displayInfo, float durationScale, boolean isAppAnimation, boolean isThumbnail) {
        this(startInfo, endInfo, 1.0f, displayInfo, durationScale, isAppAnimation, isThumbnail, false);
    }

    WindowChangeAnimationSpecExt(TaskWindowSurfaceInfo startInfo, TaskWindowSurfaceInfo endInfo,
            float scalePart, DisplayInfo displayInfo, float durationScale,
            boolean isAppAnimation, boolean isThumbnail, boolean isPivotCenter) {
        this(startInfo, endInfo, scalePart, displayInfo, durationScale, isAppAnimation, isThumbnail,
                isPivotCenter, true, null);
    }

    WindowChangeAnimationSpecExt(TaskWindowSurfaceInfo startInfo, TaskWindowSurfaceInfo endInfo,
            float scalePart, DisplayInfo displayInfo, float durationScale,
            boolean isAppAnimation, boolean isThumbnail, boolean isPivotCenter,
            boolean shareInterpolator, IAnimationCallBack callBack) {
        mStartInfo = startInfo;
        mEndInfo = endInfo;
        mScalePart = scalePart;
        mIsAppAnimation = isAppAnimation;
        mIsThumbnail = isThumbnail;
        mIsPivotCenter = isPivotCenter;
        initBounds();
        createAnimationInner((int) (ANIMATION_DURATION_MODE_CHANGING * durationScale),
                displayInfo, shareInterpolator, callBack);
    }

    private void initBounds() {
        mStartBounds = getBoundsFromInfo(mStartInfo);
        mEndBounds = getBoundsFromInfo(mEndInfo);
        mStartClipBounds = getClipBoundsFromInfo(mStartInfo);
        mEndClipBounds = getClipBoundsFromInfo(mEndInfo);
        mStartAppBound = getAppBoundFromInfo(mStartInfo);
        mEndAppBound = getAppBoundFromInfo(mEndInfo);
        mStartCornerRadius = getCornerRadiusFromInfo(mStartInfo);
        mEndCornerRadius = getCornerRadiusFromInfo(mEndInfo);
        mStartAlpha = getAlphaFromInfo(mStartInfo);
        mEndAlpha = getAlphaFromInfo(mEndInfo);
    }

    @Override
    public boolean getShowWallpaper() {
        return false;
    }

    @Override
    public long getDuration() {
        return mAnimation.getDuration();
    }

    private Rect getBoundsFromInfo(TaskWindowSurfaceInfo info) {
        if (info == null) {
            return new Rect();
        }
        return info.getTaskWindowSurfaceBounds();
    }

    private Rect getClipBoundsFromInfo(TaskWindowSurfaceInfo info) {
        if (info == null) {
            return new Rect();
        }
        final Task task = info.mTask;
        if (task != null) {
            final Rect bounds = new Rect();
            task.getBounds(bounds);
            return bounds;
        }
        return new Rect();
    }

    private Rect getAppBoundFromInfo(TaskWindowSurfaceInfo info) {
        if (info == null || info.mTask == null || info.mTask.mDisplayContent == null) {
            return new Rect();
        }
        final Rect displayBound = new Rect();
        info.mTask.mDisplayContent.getBounds(displayBound);
        return displayBound;
    }

    private float getPositionAndScaleFormInfo(TaskWindowSurfaceInfo info, Point out) {
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "getPositionAndScaleFormInfo, info=" + info);
        }
        if (info == null || info.mTask == null) {
            out.set(0, 0);
            return 1.0f;
        }
        final Task task = info.mTask;
        final int windowingMode = info.mFreezedWindowingMode != WINDOWING_MODE_UNDEFINED
                ? info.mFreezedWindowingMode
                : task.getConfiguration().windowConfiguration.getWindowingMode();

        if (WindowConfiguration.isPopUpWindowMode(windowingMode)) {
            final Rect displayBound = new Rect();
            if (task.mDisplayContent != null) {
                task.mDisplayContent.getBounds(displayBound);
            }
            final Rect bound = task.getBounds();
            info.setWindowSurfaceScaleFactor(WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                    bound, displayBound, info.getWindowCenterPosition(), info.getWindowSurfaceScale(),
                    false, out));
            return info.getWindowSurfaceRealScale();
        }
        out.set(0, 0);
        return 1.0f;
    }

    private float getCornerRadiusFromInfo(TaskWindowSurfaceInfo info) {
        if (info == null) {
            return 0.0f;
        }
        final int windowingMode = info.mFreezedWindowingMode != 0 ? info.mFreezedWindowingMode
                : info.mTask.getConfiguration().windowConfiguration.getWindowingMode();
        if (WindowConfiguration.isPopUpWindowMode(windowingMode)) {
            return info.getCornerRadius();
        }
        return 0.0f;
    }

    private float getAlphaFromInfo(TaskWindowSurfaceInfo info) {
        if (info == null) {
            return 1.0f;
        }
        return 1.0f;
    }

    private void createAnimationInner(long duration, DisplayInfo displayInfo,
            boolean shareInterpolator, IAnimationCallBack callBack) {
        final boolean growing = ((mEndBounds.width() - mStartBounds.width())
                + mEndBounds.height()) - mStartBounds.height() >= 0;
        final long scalePeriod = (long) (duration * mScalePart);

        final float startScaleX = mStartBounds.width() > 0 && mStartAppBound.width() > 0
                ? (float) mStartBounds.width() / mStartAppBound.width() : 1.0f;
        final float startScaleY = mStartBounds.height() > 0 && mStartAppBound.height() > 0
                ? (float) mStartBounds.height() / mStartAppBound.height() : 1.0f;
        final float endScaleX = mEndBounds.width() > 0 && mEndAppBound.width() > 0
                ? (float) mEndBounds.width() / mEndAppBound.width() : 1.0f;
        final float endScaleY = mEndBounds.height() > 0 && mEndAppBound.height() > 0
                ? (float) mEndBounds.height() / mEndAppBound.height() : 1.0f;

        if (mIsThumbnail) {
            createThumbnailAnimation(duration, scalePeriod, growing, startScaleX, startScaleY,
                    endScaleX, endScaleY, callBack);
            mAnimation.initialize(mStartBounds.width(), mStartBounds.height(),
                    mEndBounds.width(), mEndBounds.height());
            return;
        }

        createMainAnimation(duration, scalePeriod, growing, startScaleX, startScaleY,
                endScaleX, endScaleY, shareInterpolator, callBack, displayInfo);
    }

    private void createThumbnailAnimation(long duration, long scalePeriod, boolean growing,
            float startScaleX, float startScaleY, float endScaleX, float endScaleY,
            IAnimationCallBack callBack) {
        final AnimationSet animSet = new AnimationSet(true);

        final Animation alphaAnim = new AlphaAnimation(1.0f, 0.0f);
        alphaAnim.setDuration(scalePeriod);
        if (!growing) {
            alphaAnim.setStartOffset(duration - scalePeriod);
        }
        callBackInit(callBack, alphaAnim);
        animSet.addAnimation(alphaAnim);

        final Animation clipRectAnim = new ClipRectAnimation(mStartClipBounds, mEndClipBounds);
        clipRectAnim.setDuration(duration);
        callBackInit(callBack, clipRectAnim);
        animSet.addAnimation(clipRectAnim);

        mAnimation = animSet;
        callBackInit(callBack, animSet);
    }

    private void createMainAnimation(long duration, long scalePeriod, boolean growing,
            float startScaleX, float startScaleY, float endScaleX, float endScaleY,
            boolean shareInterpolator, IAnimationCallBack callBack, DisplayInfo displayInfo) {
        final AnimationSet animSet = new AnimationSet(shareInterpolator);
        callBackInit(callBack, animSet);

        final Animation scaleAnim = new ScaleAnimation(startScaleX, endScaleX, startScaleY, endScaleY);
        scaleAnim.setDuration(scalePeriod);
        if (!growing) {
            scaleAnim.setStartOffset(duration - scalePeriod);
        }
        callBackInit(callBack, scaleAnim);
        animSet.addAnimation(scaleAnim);

        final float fromX = mIsPivotCenter ? mStartBounds.exactCenterX() : mStartBounds.left;
        final float toX = mIsPivotCenter ? mEndBounds.exactCenterX() : mEndBounds.left;
        final Animation translateXAnim = new TranslateXAnimation(fromX, toX);
        translateXAnim.setDuration(duration);
        callBackInit(callBack, translateXAnim);
        animSet.addAnimation(translateXAnim);

        float fromY = mStartBounds.top;
        float toY = mEndBounds.top;
        if (mIsPivotCenter) {
            fromY = ((mStartBounds.bottom * 2) - Math.round(mStartClipBounds.height() * startScaleY)) * 0.5f;
            toY = ((mEndBounds.bottom * 2) - Math.round(mEndClipBounds.height() * endScaleY)) * 0.5f;
        }
        final Animation translateYAnim = new TranslateYAnimation(fromY, toY);
        translateYAnim.setDuration(duration);
        callBackInit(callBack, translateYAnim);
        animSet.addAnimation(translateYAnim);

        final Animation radiusAnim = new RadiusAnimation(mStartCornerRadius, mEndCornerRadius);
        radiusAnim.setDuration(duration);
        callBackInit(callBack, radiusAnim);
        animSet.addAnimation(radiusAnim);

        final Animation clipRectAnim = new ClipRectAnimation(mStartClipBounds, mEndClipBounds);
        clipRectAnim.setDuration(duration);
        callBackInit(callBack, clipRectAnim);
        animSet.addAnimation(clipRectAnim);

        final Animation alphaAnim = new AlphaAnimation(mStartAlpha, mEndAlpha);
        alphaAnim.setDuration(duration);
        callBackInit(callBack, alphaAnim);
        animSet.addAnimation(alphaAnim);

        mAnimation = animSet;
        callBackInit(callBack, animSet);
        animSet.initialize(mStartBounds.width(), mStartBounds.height(),
                displayInfo.appWidth, displayInfo.appHeight);
    }

    private void callBackInit(IAnimationCallBack callBack, Animation animation) {
        if (callBack != null) {
            callBack.onAnimationInit(animation, mIsThumbnail);
        }
    }

    @Override
    public void apply(SurfaceControl.Transaction t, SurfaceControl leash, long currentPlayTime) {
        final TmpValues tmp = mThreadLocalTmps.get();

        if (mIsThumbnail) {
            mAnimation.getTransformation(currentPlayTime, tmp.mTransformation);
            t.setMatrix(leash, tmp.mTransformation.getMatrix(), tmp.mFloats);
            t.setAlpha(leash, tmp.mTransformation.getAlpha());
            t.setWindowCrop(leash, tmp.mTransformation.getClipRect());
            return;
        }

        mAnimation.getTransformation(currentPlayTime, tmp.mTransformation);
        final Matrix matrix = tmp.mTransformation.getMatrix();

        t.setMatrix(leash, matrix, tmp.mFloats);
        t.setCornerRadius(leash, tmp.mTransformation.getCornerRadius());
        t.setAlpha(leash, tmp.mTransformation.getAlpha());

        final Rect clipRect = tmp.mTransformation.getClipRect();
        t.setWindowCrop(leash, clipRect);

        if (mIsPivotCenter && mEndInfo != null && mEndInfo.mTask != null) {
            final float posX = tmp.mFloats[2] - ((clipRect.width() * tmp.mFloats[0]) * 0.5f);
            final float posY = (tmp.mFloats[5] - ((clipRect.height() * tmp.mFloats[4]) * 0.5f))
                    - ((clipRect.top - mStartAppBound.top) * tmp.mFloats[4]);
            t.setPosition(leash, posX, posY);
        }
    }

    @Override
    public long calculateStatusBarTransitionStartTime() {
        final long uptime = SystemClock.uptimeMillis();
        return Math.max(uptime, (long) (mAnimation.getDuration() * 0.99f + uptime - 120));
    }

    @Override
    public boolean needsEarlyWakeup() {
        return mIsAppAnimation;
    }

    @Override
    public void dump(PrintWriter pw, String prefix) {
    }

    @Override
    public void dumpDebugInner(ProtoOutputStream proto) {
    }

    private static class TmpValues {
        final float[] mFloats;
        final Transformation mTransformation;
        final float[] mVecs;

        private TmpValues() {
            mTransformation = new Transformation();
            mFloats = new float[9];
            mVecs = new float[4];
        }
    }
}
