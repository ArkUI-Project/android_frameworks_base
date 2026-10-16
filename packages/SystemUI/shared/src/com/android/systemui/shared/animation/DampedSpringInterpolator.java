/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.shared.animation;

import android.view.animation.Interpolator;

/** A finite spring which retains the release velocity and settles at the exact endpoint. */
public final class DampedSpringInterpolator implements Interpolator {
    private static final double FREQUENCY = 18.0;
    private static final double DAMPING = 0.78;
    private final double mDurationSeconds;
    private final double mDecay = FREQUENCY * DAMPING;
    private final double mFrequency = FREQUENCY * Math.sqrt(1.0 - DAMPING * DAMPING);
    private final double mSine;

    /** Velocity is in units/second; distance uses the same units, including its sign. */
    public DampedSpringInterpolator(float velocity, float distance, long durationMillis) {
        mDurationSeconds = Math.max(1L, durationMillis) / 1000.0;
        double normalizedVelocity = Math.abs(distance) > 0.0001f && Float.isFinite(velocity)
                ? velocity / distance : 0.0;
        normalizedVelocity = Math.max(-FREQUENCY * 4, Math.min(FREQUENCY * 4,
                normalizedVelocity));
        // Multiplying the residual by (1-t/T)^2 removes its position and velocity at T.
        // Compensate its initial derivative so a reversal does not discard momentum.
        mSine = (normalizedVelocity - 2.0 / mDurationSeconds - mDecay) / mFrequency;
    }

    @Override
    public float getInterpolation(float fraction) {
        if (fraction <= 0f) return 0f;
        if (fraction >= 1f) return 1f;
        double t = fraction * mDurationSeconds;
        double remaining = 1.0 - fraction;
        double phase = -Math.cos(mFrequency * t) + mSine * Math.sin(mFrequency * t);
        return (float) (1.0 + remaining * remaining * Math.exp(-mDecay * t) * phase);
    }

    /** The normalized velocity in units/second at the given, uneased time fraction. */
    public float getVelocity(float fraction) {
        if (fraction >= 1f) return 0f;
        double t = Math.max(0f, fraction) * mDurationSeconds;
        double remaining = 1.0 - Math.max(0f, fraction);
        double sin = Math.sin(mFrequency * t);
        double cos = Math.cos(mFrequency * t);
        double phase = -cos + mSine * sin;
        double derivative = mFrequency * (sin + mSine * cos);
        return (float) (Math.exp(-mDecay * t) * (remaining * remaining
                * (derivative - mDecay * phase) - 2.0 * remaining / mDurationSeconds * phase));
    }
}
