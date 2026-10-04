/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.wm.shell.popupview;

import static android.view.WindowManager.TRANSIT_CHANGE;

import android.content.Context;
import android.graphics.Rect;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.ClipRectAnimation;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.window.TransitionInfo;

import com.android.wm.shell.popupview.PopUpViewTransitionAnimationAdapter;
import com.android.wm.shell.shared.TransitionUtil;

import java.util.ArrayList;
import java.util.List;

class PopUpViewTransitionAnimationSpec {

    private static final String TAG = "PopUpViewTransitionAnimationSpec";

    static final long ANIMATION_DURATION_MODE_CHANGING = 420L;
    static final long ANIMATION_DURATION_TO_BACK = 150L;

    static final long CHANGE_ANIMATION_DURATION = 520L;
    static final long CHANGE_ANIMATION_FADE_DURATION = 80L;
    static final long CHANGE_ANIMATION_FADE_OFFSET = 30L;

    static final long LAUNCH_FROM_RECENTS_ANIMATION_DURATION = 500L;

    private final LinearInterpolator mLinearInterpolator = new LinearInterpolator();

    static final Interpolator INTERPOLATOR_TO_BACK = new PathInterpolator(0.17f, 0.0f, 0.83f, 1.0f);
    static final Interpolator INTERPOLATOR_MODE_CHANGING = new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f);
    static final Interpolator INTERPOLATOR_MODE_CHANGING_FAST = new PathInterpolator(0.42f, 0.0f, 0.58f, 1.0f);
    static final Interpolator INTERPOLATOR_SCALE_BOUNCE = new PathInterpolator(0.2f, 1.6f, 0.35f, 1.05f);
    static final Interpolator INTERPOLATOR_SCALE_SMOOTH = new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f);
    static final Interpolator INTERPOLATOR_PIN_TO_PIN = new PathInterpolator(0.2f, 1.3f, 0.35f, 1.0f);

    private final Interpolator mFastOutExtraSlowInInterpolator;

    private float mTransitionAnimationScaleSetting = 1f;

    PopUpViewTransitionAnimationSpec(Context context) {
        mFastOutExtraSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                android.R.interpolator.fast_out_extra_slow_in);
    }

    void setAnimScaleSetting(float scale) {
        mTransitionAnimationScaleSetting = scale;
    }

    Animation createFadeOutAnimation(TransitionInfo.Change change) {
        final long duration = (long) (ANIMATION_DURATION_TO_BACK * mTransitionAnimationScaleSetting);
        final AnimationSet animationSet = new AnimationSet(false);
        final AlphaAnimation alphaAnimation = new AlphaAnimation(1.0f, 0.0f);
        alphaAnimation.setDuration(duration);
        animationSet.addAnimation(alphaAnimation);

        final float startScale = change.mPopUpView.mStartScale;
        final float endScale = startScale * 0.95f;
        final ScaleAnimation scaleAnimation = new ScaleAnimation(startScale, endScale, startScale, endScale);
        scaleAnimation.setInterpolator(INTERPOLATOR_SCALE_SMOOTH);
        scaleAnimation.setDuration(duration);
        animationSet.addAnimation(scaleAnimation);

        final TranslateAnimation translateAnimation = new TranslateAnimation(change.mPopUpView.mStartPos.x,
                change.mPopUpView.mStartPos.x, change.mPopUpView.mStartPos.y, change.mPopUpView.mStartPos.y);
        translateAnimation.setInterpolator(INTERPOLATOR_TO_BACK);
        translateAnimation.setDuration(duration);
        animationSet.addAnimation(translateAnimation);

        animationSet.initialize(0, 0, change.mPopUpView.mAppBounds.width(), change.mPopUpView.mAppBounds.height());
        return animationSet;
    }

    List<PopUpViewTransitionAnimationAdapter> createChangeAnimationAdapters(TransitionInfo info, SurfaceControl.Transaction t) {
        final ArrayList<PopUpViewTransitionAnimationAdapter> adapters = new ArrayList<>();
        final Rect rect = new Rect();
        for (TransitionInfo.Change change : info.getChanges()) {
            if ((change.getFlags() & android.window.TransitionInfo.FLAG_POP_UP_VIEW) != 0
                    && change.getMode() == TRANSIT_CHANGE &&
                    !change.getStartAbsBounds().equals(change.getEndAbsBounds())) {
                rect.union(change.getStartAbsBounds());
                rect.union(change.getEndAbsBounds());
                final Animation[] animations = createChangeBoundsChangeAnimations(change, rect);
                final SurfaceControl leash = getOrCreateScreenshot(change, change, t);
                final TransitionInfo.Root root = TransitionUtil.getRootFor(change, info);
                if (leash != null) {
                    adapters.add(new PopUpViewTransitionAnimationAdapter.SnapshotAdapter(animations[0], change, leash));
                } else {
                    Slog.e(TAG, "Failed to take screenshot for change=" + change);
                }
                adapters.add(new PopUpViewTransitionAnimationAdapter.BoundsChangeAdapter(animations[1], change, root));
            }
        }
        return adapters;
    }

    Animation createLaunchFromRecentsAnimation(TransitionInfo.Change change) {
        final long duration = (long) (LAUNCH_FROM_RECENTS_ANIMATION_DURATION * mTransitionAnimationScaleSetting);
        final AnimationSet animationSet = new AnimationSet(false);
        final AlphaAnimation alphaAnimation = new AlphaAnimation(1.0f, 1.0f);
        alphaAnimation.setDuration(duration);
        animationSet.addAnimation(alphaAnimation);

        final float startScale = change.mPopUpView.mStartScale;
        final float endScale = change.mPopUpView.mEndScale;
        final ScaleAnimation scaleAnimation = new ScaleAnimation(startScale, endScale, startScale, endScale);
        scaleAnimation.setInterpolator(INTERPOLATOR_MODE_CHANGING);
        scaleAnimation.setDuration(duration);
        animationSet.addAnimation(scaleAnimation);

        final TranslateAnimation translateAnimation = new TranslateAnimation(change.mPopUpView.mStartPos.x,
                change.mPopUpView.mEndPos.x, change.mPopUpView.mStartPos.y, change.mPopUpView.mEndPos.y);
        translateAnimation.setInterpolator(INTERPOLATOR_MODE_CHANGING);
        translateAnimation.setDuration(duration);
        animationSet.addAnimation(translateAnimation);

        animationSet.initialize(0, 0, change.mPopUpView.mAppBounds.width(), change.mPopUpView.mAppBounds.height());
        return animationSet;
    }

    Animation createAnimation(TransitionInfo.Change change) {
        final long duration = (long) (ANIMATION_DURATION_MODE_CHANGING * mTransitionAnimationScaleSetting);
        final AnimationSet animationSet = new AnimationSet(false);

        final float startScale = change.mPopUpView.mStartScale;
        final float endScale = change.mPopUpView.mEndScale;

        final ScaleAnimation scaleAnimation = new ScaleAnimation(startScale, endScale, startScale, endScale);

        // Scale and position must use the same clock and curve: otherwise the
        // apparent centre keeps moving after translation has already finished.
        scaleAnimation.setInterpolator(INTERPOLATOR_MODE_CHANGING);
        scaleAnimation.setDuration(duration);
        animationSet.addAnimation(scaleAnimation);

        final TranslateAnimation translateAnimation = new TranslateAnimation(change.mPopUpView.mStartPos.x,
                change.mPopUpView.mEndPos.x, change.mPopUpView.mStartPos.y, change.mPopUpView.mEndPos.y);
        translateAnimation.setInterpolator(INTERPOLATOR_MODE_CHANGING);
        translateAnimation.setDuration(duration);
        animationSet.addAnimation(translateAnimation);

        animationSet.initialize(0, 0, change.mPopUpView.mAppBounds.width(), change.mPopUpView.mAppBounds.height());
        return animationSet;
    }

    private Animation[] createChangeBoundsChangeAnimations(TransitionInfo.Change change, Rect rect) {
        final Rect endBounds = change.getEndAbsBounds();
        final Rect startBounds = change.mPopUpView.mStartDragBounds;
        final float width = (float) Math.max(1, startBounds.width())
                / Math.max(1, endBounds.width());
        final float height = (float) Math.max(1, startBounds.height())
                / Math.max(1, endBounds.height());
        final long duration = (long) (CHANGE_ANIMATION_DURATION * mTransitionAnimationScaleSetting);

        final AnimationSet snapshot = new AnimationSet(false);
        final AlphaAnimation fade = new AlphaAnimation(1f, 0f);
        fade.setDuration((long) (CHANGE_ANIMATION_FADE_DURATION * mTransitionAnimationScaleSetting));
        fade.setStartOffset((long) (CHANGE_ANIMATION_FADE_OFFSET * mTransitionAnimationScaleSetting));
        snapshot.addAnimation(fade);
        final ScaleAnimation snapshotScale = new ScaleAnimation(
                1f / width, 1f / width, 1f / height, 1f / height);
        snapshotScale.setDuration(duration);
        snapshot.addAnimation(snapshotScale);
        snapshot.initialize(startBounds.width(), startBounds.height(),
                endBounds.width(), endBounds.height());

        final AnimationSet content = new AnimationSet(true);
        content.setInterpolator(INTERPOLATOR_MODE_CHANGING);
        final ScaleAnimation scale = new ScaleAnimation(width, 1f, height, 1f);
        scale.setDuration(duration);
        content.addAnimation(scale);
        final TranslateAnimation translate = new TranslateAnimation(
                startBounds.left - endBounds.left, 0f, startBounds.top - endBounds.top, 0f);
        translate.setDuration(duration);
        content.addAnimation(translate);
        final Rect startCrop = new Rect(startBounds);
        final Rect endCrop = new Rect(endBounds);
        startCrop.offsetTo(0, 0);
        endCrop.offsetTo(0, 0);
        final ClipRectAnimation crop = new ClipRectAnimation(startCrop, endCrop);
        crop.setDuration(duration);
        content.addAnimation(crop);
        content.initialize(startBounds.width(), startBounds.height(), rect.width(), rect.height());
        return new Animation[]{snapshot, content};
    }

    private SurfaceControl getOrCreateScreenshot(TransitionInfo.Change change,
            TransitionInfo.Change change2, SurfaceControl.Transaction t) {
        final SurfaceControl snapshot = change.getSnapshot();
        if (snapshot != null) {
            t.reparent(snapshot, change2.getLeash());
        }
        return snapshot;
    }
}
