/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2026 The AviumUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.view.animation;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

/**
 * @hide
 */
public class RadiusAnimation extends Animation {

    private float mFromRadius;
    private float mToRadius;

    public RadiusAnimation(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.RadiusAnimation);

        mFromRadius = a.getFloat(
                com.android.internal.R.styleable.RadiusAnimation_fromRadius, 0.0f);
        mToRadius = a.getFloat(
                com.android.internal.R.styleable.RadiusAnimation_toRadius, 0.0f);

        a.recycle();
    }
    public RadiusAnimation(float fromRadius, float toRadius) {
        mFromRadius = fromRadius;
        mToRadius = toRadius;
    }

    @Override
    protected void applyTransformation(float interpolatedTime, Transformation t) {
        float radius = mFromRadius + ((mToRadius - mFromRadius) * interpolatedTime);
        t.setCornerRadius(radius);
    }

    @Override
    public boolean willChangeTransformationMatrix() {
        return false;
    }

    public float getFromRadius() {
        return mFromRadius;
    }

    public float getToRadius() {
        return mToRadius;
    }
}
