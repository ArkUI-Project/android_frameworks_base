/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Expressive rounded-to-square press feedback, shared by the assistant's native controls. */
final class GameButtonBackground extends RippleDrawable {
    private final GradientDrawable fill, mask;
    private float radius, currentRadius;
    private int color, currentColor;
    private SpringAnimation spring;
    private boolean pressed;

    GameButtonBackground(Context context,int color,int ink,float radiusDp) {
        this(GameStyle.shape(color,GameStyle.dp(context,radiusDp)),
                GameStyle.shape(Color.WHITE,GameStyle.dp(context,radiusDp)),ink,
                GameStyle.dp(context,radiusDp));
    }
    private GameButtonBackground(GradientDrawable fill,GradientDrawable mask,int ink,float radius) {
        super(ColorStateList.valueOf((ink & 0xffffff) | 0x24000000),fill,mask);
        this.fill = fill;
        this.mask = mask;
        this.radius = radius;
        currentRadius = radius;
        color = currentColor = fill.getColor().getDefaultColor();
    }
    @Override protected boolean onStateChange(int[] states) {
        boolean changed = super.onStateChange(states);
        if (fill == null) return changed;
        boolean next = false;
        for (int state : states) if (state == android.R.attr.state_pressed) next = true;
        if (next == pressed) return changed;
        pressed = next;
        animateShape();
        return true;
    }
    void select(int nextColor,float nextRadius) {
        color = nextColor;
        radius = nextRadius;
        animateShape();
    }
    private void animateShape() {
        if (spring != null) spring.cancel();
        float target = pressed ? radius * .45f : radius;
        float fromRadius = currentRadius;
        int fromColor = currentColor, toColor = color;
        if (!ValueAnimator.areAnimatorsEnabled() || getCallback() == null) {
            drawShape(target,toColor);
        } else {
            spring = new SpringAnimation(new FloatValueHolder(0));
            spring.setMinimumVisibleChange(.001f);
            spring.setSpring(new SpringForce(1).setStiffness(900).setDampingRatio(.75f));
            spring.addUpdateListener((a,value,velocity) -> {
                float progress = Math.max(0,Math.min(1,value));
                drawShape(fromRadius + (target - fromRadius) * progress,
                        (int) android.animation.ArgbEvaluator.getInstance().evaluate(progress,fromColor,toColor));
            });
            spring.start();
        }
    }
    private void drawShape(float corner,int tint) {
        currentRadius = corner;
        currentColor = tint;
        fill.setCornerRadius(corner);
        mask.setCornerRadius(corner);
        fill.setColor(tint);
    }
    @Override public void jumpToCurrentState() {
        super.jumpToCurrentState();
        if (spring != null) spring.cancel();
        if (fill != null) drawShape(pressed ? radius * .45f : radius,color);
    }
    @Override public boolean setVisible(boolean visible,boolean restart) {
        if (!visible && spring != null) spring.cancel();
        return super.setVisible(visible,restart);
    }
}
