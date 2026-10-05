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
import android.view.Choreographer;

import java.util.Arrays;

/** A chain of row positions, sampled by both shade surfaces in the drawing phase. */
public final class ShadeTailMotion {
    public interface Listener {
        void onShadeMotion(Frame frame);
        default void setControlCenterEnabled(boolean enabled) {}
    }

    private static final int STATION_COUNT = 6;
    private static final float STIFFNESS = 1400f;
    private static final float DAMPING = 2f * 0.76f * (float) Math.sqrt(STIFFNESS);
    private static final float MAX_STEP = 1f / 120f;

    private final Listener mListener;
    private final Choreographer mChoreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback mFrameCallback = this::doFrame;
    private final float[] mPositions = new float[STATION_COUNT];
    private final float[] mVelocities = new float[STATION_COUNT];
    private boolean mEnabled;
    private float mExpansion = -1f;
    private boolean mClosing;
    private boolean mRetreating;
    private float mTarget;
    private boolean mFrameScheduled;
    private long mLastFrameTime;

    public ShadeTailMotion(Listener listener) {
        mListener = listener;
    }

    public void setExpansion(boolean enabled, float expansion) {
        float fraction = Float.isFinite(expansion) ? MathUtils.saturate(expansion) : 0f;
        if (mEnabled == enabled && mExpansion == fraction) {
            return;
        }
        final boolean becameEnabled = enabled && !mEnabled;
        if (mExpansion <= 0f) mClosing = false;
        else if (mExpansion == 1f && fraction < 1f) mClosing = true;
        boolean retreating = fraction < mExpansion;
        if (enabled && mEnabled && retreating != mRetreating) {
            // Both directions approach the final cards from above. Reflect every station about
            // the old target, preserving its rendered position and velocity on partial reversals.
            for (int i = 0; i < STATION_COUNT; i++) {
                mPositions[i] = 2f * mTarget - mPositions[i];
                mVelocities[i] = -mVelocities[i];
            }
        }
        mRetreating = retreating;
        mExpansion = fraction;
        mEnabled = enabled;
        // Start responding during a short pull, after the backdrop has begun obscuring the app.
        float contentExpansion = MathUtils.saturate((fraction - 0.025f) / 0.755f);
        float remaining = 1f - contentExpansion;
        // Latch this curve at the endpoint; a reversal of a partial pull keeps the same curve.
        mTarget = mClosing ? fraction * fraction * fraction
                : 1f - remaining * remaining * remaining;
        if (!enabled || fraction == 0f || !ValueAnimator.areAnimatorsEnabled()) {
            stopFrames();
            Arrays.fill(mPositions, mTarget);
            Arrays.fill(mVelocities, 0f);
            dispatch();
        } else {
            // The panel can become visible in this traversal, before our animation callback.
            // Publish its existing row positions first so a newly visible tree cannot flash full.
            if (becameEnabled) dispatch();
            scheduleFrame();
        }
    }

    public void reset() {
        setExpansion(false, 0f);
    }

    /** A configuration change keeps the current layout, but drops velocity from its old geometry. */
    public void snapToExpansion(boolean enabled, float expansion) {
        stopFrames();
        mEnabled = false;
        mExpansion = 0f;
        mClosing = false;
        mRetreating = false;
        setExpansion(enabled, expansion);
        stopFrames();
        Arrays.fill(mPositions, mTarget);
        Arrays.fill(mVelocities, 0f);
        dispatch();
    }

    private void scheduleFrame() {
        if (mFrameScheduled || !mEnabled) return;
        mFrameScheduled = true;
        mChoreographer.postFrameCallback(mFrameCallback);
    }

    private void stopFrames() {
        if (mFrameScheduled) mChoreographer.removeFrameCallback(mFrameCallback);
        mFrameScheduled = false;
        mLastFrameTime = 0;
    }

    private void doFrame(long frameTimeNanos) {
        mFrameScheduled = false;
        if (!mEnabled) return;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            stopFrames();
            Arrays.fill(mPositions, mTarget);
            Arrays.fill(mVelocities, 0f);
            dispatch();
            return;
        }
        float elapsed = mLastFrameTime == 0 ? 1f / 60f
                : MathUtils.constrain((frameTimeNanos - mLastFrameTime) / 1_000_000_000f,
                        1f / 240f, 0.05f);
        mLastFrameTime = frameTimeNanos;
        while (elapsed > 0f) {
            float step = Math.min(elapsed, MAX_STEP);
            // Update backwards so every station follows its predecessor's previous substep.
            for (int i = STATION_COUNT - 1; i >= 0; i--) {
                float leader = i == 0 ? mTarget : mPositions[i - 1];
                float acceleration = STIFFNESS * (leader - mPositions[i])
                        - DAMPING * mVelocities[i];
                mVelocities[i] += acceleration * step;
                mPositions[i] += mVelocities[i] * step;
            }
            elapsed -= step;
        }
        boolean settled = true;
        for (int i = 0; i < STATION_COUNT; i++) {
            settled &= Math.abs(mPositions[i] - mTarget) < 0.0008f
                    && Math.abs(mVelocities[i]) < 0.008f;
        }
        if (settled) {
            Arrays.fill(mPositions, mTarget);
            Arrays.fill(mVelocities, 0f);
            mLastFrameTime = 0;
        }
        // Only one immutable frame is delivered, regardless of the number of moving stations.
        dispatch();
        if (mEnabled && !settled) scheduleFrame();
    }

    private void dispatch() {
        if (!mEnabled) {
            mListener.onShadeMotion(Frame.DISABLED);
            return;
        }
        float[] positions = new float[STATION_COUNT];
        for (int i = 0; i < STATION_COUNT; i++) {
            positions[i] = mRetreating ? 2f * mTarget - mPositions[i] : mPositions[i];
        }
        mListener.onShadeMotion(new Frame(mTarget, positions));
    }

    public static final class Transform {
        public static final Transform IDENTITY = new Transform(0f, 1f, 1f);
        public final float translationY;
        public final float scale;
        public final float alpha;

        private Transform(float translationY, float scale, float alpha) {
            this.translationY = translationY;
            this.scale = scale;
            this.alpha = alpha;
        }
    }

    /** Positions cannot change after dispatch, so all consumers draw the same animation frame. */
    public static final class Frame {
        public static final Frame DISABLED = new Frame();
        public static final Frame COLLAPSED = new Frame(0f, new float[STATION_COUNT]);
        public final boolean enabled;
        public final float target;
        public final boolean fullyExpanded;
        private final float[] mRowPositions;

        private Frame() {
            enabled = false;
            target = 1f;
            fullyExpanded = true;
            mRowPositions = new float[STATION_COUNT];
            Arrays.fill(mRowPositions, 1f);
        }

        private Frame(float target, float[] positions) {
            enabled = true;
            this.target = target;
            mRowPositions = positions;
            boolean expanded = target == 1f;
            for (float position : positions) expanded &= position == 1f;
            fullyExpanded = expanded;
        }

        public float progressAt(float depth) {
            float station = MathUtils.saturate(depth) * (STATION_COUNT - 1);
            int first = Math.min((int) station, STATION_COUNT - 2);
            return MathUtils.lerp(mRowPositions[first], mRowPositions[first + 1], station - first);
        }

        public Transform transform(float top, float height, float density) {
            if (!enabled || fullyExpanded) return Transform.IDENTITY;
            float center = Math.max(0f, top + height / 2f);
            float progress = progressAt(center / (800f * density));
            float scale = 0.85f + 0.15f * MathUtils.constrain(progress, 0f, 1.06f);
            float travel = 24f * density + center * 0.15f;
            float opacity = MathUtils.saturate((progress - 0.02f) / 0.42f);
            return new Transform(-(1f - progress) * travel, scale,
                    opacity * opacity * (3f - 2f * opacity));
        }

        /** Fit rows into their original order without changing measurement or hit-test bounds. */
        public Transform[] transforms(float[] tops, float[] heights, float density) {
            return transforms(tops, heights, null, null, density);
        }

        /** Horizontal lanes keep a tall media card from pushing adjacent tile rows down. */
        public Transform[] transforms(float[] tops, float[] heights, float[] lefts,
                float[] widths, float density) {
            int count = tops.length;
            Transform[] result = new Transform[count];
            if (!enabled || fullyExpanded) {
                Arrays.fill(result, Transform.IDENTITY);
                return result;
            }
            int[] order = new int[count];
            for (int i = 0; i < count; i++) {
                int at = i;
                while (at > 0 && tops[order[at - 1]] > tops[i]) {
                    order[at] = order[at - 1];
                    at--;
                }
                order[at] = i;
            }
            for (int first = 0; first < count;) {
                int end = first + 1;
                float top = tops[order[first]];
                float height = heights[order[first]];
                while (end < count && Math.abs(tops[order[end]] - top) <= 1f) {
                    height = Math.max(height, heights[order[end]]);
                    end++;
                }
                Transform desired = transform(top, height, density);
                float translation = desired.translationY;
                if (desired.alpha > 0f) {
                    for (int member = first; member < end; member++) {
                        int next = order[member];
                        float drawnTop = tops[next] + heights[next] * (1f - desired.scale) / 2f;
                        for (int preceding = 0; preceding < first; preceding++) {
                            int previous = order[preceding];
                            Transform fitted = result[previous];
                            float originalBottom = tops[previous] + heights[previous];
                            if (fitted.alpha <= 0f || originalBottom > tops[next] + 1f) continue;
                            if (lefts != null && (lefts[previous] + widths[previous] <= lefts[next]
                                    || lefts[next] + widths[next] <= lefts[previous])) continue;
                            float gap = Math.min(8f * density,
                                    Math.max(0f, tops[next] - originalBottom)
                                            * Math.min(fitted.scale, desired.scale));
                            float bottom = tops[previous]
                                    + heights[previous] * (1f + fitted.scale) / 2f
                                    + fitted.translationY;
                            translation = Math.max(translation, bottom + gap - drawnTop);
                        }
                    }
                }
                Transform fitted = new Transform(translation, desired.scale, desired.alpha);
                for (int i = first; i < end; i++) result[order[i]] = fitted;
                first = end;
            }
            return result;
        }
    }
}
