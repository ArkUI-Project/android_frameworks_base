/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.wm.shell.popupview;

import static com.android.internal.arkui.DebugConstants.DEBUG_POP_UP;

import android.graphics.Point;
import android.graphics.Rect;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.window.TransitionInfo.Change;
import android.window.TransitionInfo.Root;

class PopUpViewTransitionAnimationAdapter {

    private static final String TAG = "PopUpViewTransitionAnimationAdapter";

    final Point mContentRelOffset = new Point();
    final Rect mRect = new Rect();
    final Transformation mTransformation = new Transformation();
    final float[] mMatrix = new float[9];
    final float[] mVecs = new float[4];

    final Animation mAnimation;
    final Change mChange;
    final SurfaceControl mLeash;

    private boolean mHasSnapshot;

    void setRoot(Root root) {
        // PopUpViewInfo positions are display-absolute; animation leashes are not.
        if (mChange.getParent() == null) {
            mContentRelOffset.set(-root.getOffset().x, -root.getOffset().y);
        } else {
            mContentRelOffset.set(
                    mChange.getEndRelOffset().x - mChange.getEndAbsBounds().left,
                    mChange.getEndRelOffset().y - mChange.getEndAbsBounds().top);
        }
    }

    PopUpViewTransitionAnimationAdapter(Animation animation, Change change) {
        this(animation, change, change.getLeash());
    }

    PopUpViewTransitionAnimationAdapter(Animation animation, Change change,
            SurfaceControl leash) {
        this(animation, change, leash, false);
    }

    PopUpViewTransitionAnimationAdapter(Animation animation, Change change,
            SurfaceControl leash, boolean hasSnapshot) {
        mAnimation = animation;
        mChange = change;
        mLeash = leash;
        mHasSnapshot = hasSnapshot;
    }

    void prepareForFirstFrame(SurfaceControl.Transaction t) {
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "prepareForFirstFrame");
        }
        if (!mHasSnapshot && mChange.getSnapshot() != null) {
            t.reparent(mChange.getSnapshot(), null);
        }
        t.show(mLeash);
        mAnimation.getTransformation(0L, mTransformation);
        onAnimationUpdateInner(t, 0L);
    }

    void onAnimationUpdate(SurfaceControl.Transaction t, long currentPlayTime) {
        final long min = Math.min(currentPlayTime, getDurationHint());
        mTransformation.clear();
        mAnimation.getTransformation(min, mTransformation);
        onAnimationUpdateInner(t, min);
    }

    void onAnimationUpdateInner(SurfaceControl.Transaction t, long currentPlayTime) {
        mTransformation.getMatrix().postTranslate(mContentRelOffset.x, mContentRelOffset.y);
        t.setMatrix(mLeash, mTransformation.getMatrix(), mMatrix);
        t.setAlpha(mLeash, mTransformation.getAlpha());
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "onAnimationUpdateInner, currentPlayTime=" + currentPlayTime +
                    ", mChange.mPopUpView=" + mChange.mPopUpView +
                    ", mMatrix=" + mTransformation.getMatrix() +
                    ", getAlpha()=" + mTransformation.getAlpha());
        }
        if (mAnimation.getDuration() > 0) {
            final float interpolation = mAnimation.getInterpolator().getInterpolation(
                    Math.max(0f, Math.min(1f, (float) currentPlayTime / getDurationHint())));
            t.setCornerRadius(mLeash, mChange.mPopUpView.mStartCornerRadius +
                    (mChange.mPopUpView.mEndCornerRadius - mChange.mPopUpView.mStartCornerRadius) * interpolation);
            t.setWindowCrop(mLeash, mChange.mPopUpView.mWindowCrop);
        }
    }

    void onAnimationEnd(SurfaceControl.Transaction t) {
        // WM's finish transaction reparents to the resting parent. Do not write
        // animation-root coordinates back into that different coordinate space.
        if (mChange.getMode() == android.view.WindowManager.TRANSIT_CHANGE) {
            if (mChange.getTaskInfo() == null || !android.app.WindowConfiguration.isPopUpWindowMode(
                    mChange.getTaskInfo().getWindowingMode())) {
                // Fullscreen inherits WM bounds. Never overwrite its finish state with
                // a crop or scale left over from a previous small-window transition.
                t.setScale(mLeash, 1f, 1f)
                        .setPosition(mLeash, mChange.getEndRelOffset().x,
                                mChange.getEndRelOffset().y)
                        .setWindowCrop(mLeash, null)
                        .setCornerRadius(mLeash, 0f)
                        .setAlpha(mLeash, 1f);
                return;
            }
            t.setScale(mLeash, mChange.mPopUpView.mEndScale, mChange.mPopUpView.mEndScale);
            t.setPosition(mLeash,
                    mChange.mPopUpView.mEndPos.x + mChange.getEndRelOffset().x
                            - mChange.getEndAbsBounds().left,
                    mChange.mPopUpView.mEndPos.y + mChange.getEndRelOffset().y
                            - mChange.getEndAbsBounds().top);
            t.setAlpha(mLeash, 1f);
            t.setWindowCrop(mLeash, mChange.mPopUpView.mWindowCrop);
            t.setCornerRadius(mLeash, mChange.mPopUpView.mEndCornerRadius);
        }
    }

    long getDurationHint() {
        return mAnimation.computeDurationHint();
    }

    static class SnapshotAdapter extends PopUpViewTransitionAnimationAdapter {

        SnapshotAdapter(Animation animation, Change change, SurfaceControl leash) {
            super(animation, change, leash, true);
        }

        @Override
        void onAnimationUpdateInner(SurfaceControl.Transaction t, long currentPlayTime) {
            mTransformation.getMatrix().postTranslate(0.0f, 0.0f);
            t.setMatrix(mLeash, mTransformation.getMatrix(), mMatrix);
            t.setAlpha(mLeash, mTransformation.getAlpha());
        }

        @Override
        void onAnimationEnd(SurfaceControl.Transaction t) {
            if (mLeash.isValid()) {
                t.remove(mLeash);
            }
        }
    }

    static class BoundsChangeAdapter extends PopUpViewTransitionAnimationAdapter {

        BoundsChangeAdapter(Animation animation, Change change, Root root) {
            super(animation, change, change.getLeash());
            change.getStartAbsBounds();
            final Rect endAbsBounds = change.getEndAbsBounds();
            if (change.getParent() != null) {
                mContentRelOffset.set(change.getEndRelOffset());
                return;
            }
            final Point offset = root.getOffset();
            mContentRelOffset.set(endAbsBounds.left - offset.x, endAbsBounds.top - offset.y);
        }

        @Override
        void onAnimationUpdateInner(SurfaceControl.Transaction t, long currentPlayTime) {
            mTransformation.getMatrix().postTranslate(mContentRelOffset.x, mContentRelOffset.y);
            t.setMatrix(mLeash, mTransformation.getMatrix(), mMatrix);
            t.setAlpha(mLeash, mTransformation.getAlpha());
            mVecs[0] = 1.0f;
            mVecs[1] = 0.0f;
            mVecs[2] = 0.0f;
            mVecs[3] = 1.0f;
            mTransformation.getMatrix().mapVectors(mVecs);
            mVecs[0] = 1.0f / mVecs[0];
            mVecs[3] = 1.0f / mVecs[3];

            final Rect clipRect = mTransformation.getClipRect();
            mRect.left = (int) (clipRect.left * mVecs[0] + 0.5f);
            mRect.right = (int) (clipRect.right * mVecs[0] + 0.5f);
            mRect.top = (int) (clipRect.top * mVecs[3] + 0.5f);
            mRect.bottom = (int) (clipRect.bottom * mVecs[3] + 0.5f);
            t.setCrop(mLeash, mRect);
        }
    }
}
