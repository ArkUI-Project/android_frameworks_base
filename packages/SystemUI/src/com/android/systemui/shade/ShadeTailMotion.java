/*
 * Copyright (C) 2026 The ArkUI Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shade;

import android.animation.ValueAnimator;
import android.util.MathUtils;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** A single, retargetable spring shared by both shade surfaces. */
public final class ShadeTailMotion {
    public interface Listener {
        void onShadeMotion(boolean enabled, float reveal, float tail);
        default void setControlCenterEnabled(boolean enabled) {}
    }

    private final Listener mListener;
    private final SpringAnimation mSpring;
    private boolean mEnabled;
    private float mExpansion = -1f;
    private boolean mClosing;
    private float mReveal;
    private float mTail;

    public ShadeTailMotion(Listener listener) {
        mListener = listener;
        mSpring = new SpringAnimation(new FloatValueHolder());
        mSpring.setSpring(new SpringForce(0f).setStiffness(320f).setDampingRatio(0.68f));
        mSpring.setMinimumVisibleChange(0.001f);
        mSpring.addUpdateListener((animation, value, velocity) -> {
            mTail = value;
            dispatch();
        });
    }

    public void setExpansion(boolean enabled, float expansion) {
        float fraction = MathUtils.saturate(expansion);
        if (mEnabled == enabled && mExpansion == fraction) {
            return;
        }
        if (mExpansion <= 0f) mClosing = false;
        else if (mExpansion == 1f && fraction < 1f) mClosing = true;
        mExpansion = fraction;
        mEnabled = enabled;
        // The header arrives first; the spring continues travelling after the finger stops.
        float remaining = 1f - fraction;
        // Latch direction at an endpoint so reversing a partial drag doesn't jump curves.
        // Content leaves ahead of the backdrop on closing, as it arrives ahead on opening.
        mReveal = mClosing ? fraction * fraction * fraction
                : 1f - remaining * remaining * remaining;
        if (!enabled || fraction == 0f || !ValueAnimator.areAnimatorsEnabled()) {
            mSpring.cancel();
            mTail = mReveal;
            mSpring.setStartValue(mTail);
            mSpring.setStartVelocity(0f);
        } else {
            mSpring.animateToFinalPosition(mReveal);
        }
        dispatch();
    }

    public void reset() {
        setExpansion(false, 0f);
    }

    private void dispatch() {
        mListener.onShadeMotion(mEnabled, mReveal, mTail);
    }

    /** Render-only displacement: lower rows travel farther and settle later. */
    public static float translation(float reveal, float tail, float centerY, float density) {
        return -(1f - reveal) * 48f * density
                + MathUtils.constrain(tail - reveal, -0.65f, 0.35f)
                * (32f * density + Math.max(0f, centerY) * 0.42f);
    }

    public static float scale(float reveal, float tail) {
        return 0.90f + 0.10f * reveal
                + 0.025f * MathUtils.constrain(tail - reveal, -0.65f, 0.35f);
    }

    public static float scaleY(float reveal, float tail) {
        // Match the compression of row spacing, so adjacent cards never pass through each other.
        return scale(reveal, tail)
                * (1f + 0.42f * MathUtils.constrain(tail - reveal, -0.65f, 0.35f));
    }
}
