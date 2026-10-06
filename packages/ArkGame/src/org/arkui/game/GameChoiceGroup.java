/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;

import static org.arkui.game.GameStyle.*;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import java.util.function.IntConsumer;

/** Expressive single-choice buttons, with adaptive layout and spring-driven shapes. */
final class GameChoiceGroup extends LinearLayout {
    private final Choice[] choices;
    private final IntConsumer listener;
    private int selected;
    private boolean vertical;

    GameChoiceGroup(Context context, String[] labels, int selected, IntConsumer listener) {
        super(context);
        this.selected = Math.max(-1, Math.min(labels.length - 1, selected));
        this.listener = listener;
        setBaselineAligned(false);
        choices = new Choice[labels.length];
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            choices[i] = new Choice(context, labels[i]);
            choices[i].setOnClickListener(v -> {
                if (this.selected == index) return;
                select(index);
                listener.accept(index);
            });
            addView(choices[i]);
        }
        arrange(false);
        select(this.selected);
    }

    void select(int index) {
        selected = index;
        for (int i = 0; i < choices.length; i++) choices[i].select(i == index);
    }

    private void arrange(boolean stacked) {
        vertical = stacked;
        setOrientation(stacked ? VERTICAL : HORIZONTAL);
        for (int i = 0; i < choices.length; i++) {
            var params = new LayoutParams(stacked ? -1 : 0, -2, stacked ? 0 : 1);
            if (i > 0) {
                if (stacked) params.topMargin = dp(getContext(), 4);
                else params.setMarginStart(dp(getContext(), 4));
            }
            choices[i].setLayoutParams(params);
        }
    }

    @Override protected void onMeasure(int width, int height) {
        int available = MeasureSpec.getSize(width) - getPaddingLeft() - getPaddingRight();
        int perChoice = (available - dp(getContext(), 4) * (choices.length - 1)) / choices.length;
        boolean stack = false;
        for (Choice choice : choices) {
            int needed = (int) Math.ceil(choice.getPaint().measureText(choice.getText().toString()))
                    + dp(getContext(), 40);
            if (needed > perChoice) stack = true;
        }
        if (stack != vertical) arrange(stack);
        super.onMeasure(width, height);
    }

    private final class Choice extends TextView {
        private final GradientDrawable fill, mask;
        private final int inactive, active, inactiveInk, activeInk;
        private SpringAnimation spring;
        private float progress;

        Choice(Context c, String label) {
            super(c);
            setTextAppearance(com.android.settingslib.widget.theme.R.style.TextAppearance_SettingsLib_LabelLarge);
            setText(label);
            setGravity(Gravity.CENTER);
            setMinHeight(dp(c, 56));
            setPadding(dp(c, 10), dp(c, 12), dp(c, 10), dp(c, 12));
            setFocusable(true);
            setMaxLines(2);
            setCompoundDrawablePadding(dp(c, 4));
            if (label.equals(c.getString(R.string.repeat_forever_short))) setContentDescription(c.getString(R.string.repeat_forever));
            inactive = c.getColor(com.android.internal.R.color.materialColorSurfaceContainerHighest);
            active = accent(c);
            inactiveInk = ink(c);
            activeInk = c.getColor(com.android.internal.R.color.materialColorOnPrimaryContainer);
            fill = shape(inactive, dp(c, 28));
            mask = shape(Color.WHITE, dp(c, 28));
            setBackground(new RippleDrawable(ColorStateList.valueOf(
                    (inactiveInk & 0xffffff) | 0x24000000), fill, mask));
            setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(RadioButton.class.getName());
                    info.setCheckable(true);
                    info.setChecked(host.isSelected());
                }
            });
        }

        void select(boolean checked) {
            setSelected(checked);
            Drawable check = checked ? new Check(activeInk) : null;
            setCompoundDrawablesRelative(check, null, null, null);
            if (spring != null) spring.cancel();
            float target = checked ? 1 : 0;
            if (!isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
                drawSelection(target);
                return;
            }
            spring = new SpringAnimation(new FloatValueHolder(progress));
            spring.setMinimumVisibleChange(.001f);
            spring.setSpring(new SpringForce(target).setStiffness(900).setDampingRatio(.85f));
            spring.addUpdateListener((a, value, velocity) -> drawSelection(value));
            spring.start();
        }

        private void drawSelection(float value) {
            progress = Math.max(0, Math.min(1, value));
            float radius = dp(getContext(), 28 - 12 * progress);
            fill.setCornerRadius(radius);
            mask.setCornerRadius(radius);
            fill.setColor(blend(inactive, active, progress));
            setTextColor(blend(inactiveInk, activeInk, progress));
            var params = (LinearLayout.LayoutParams) getLayoutParams();
            if (params != null && !vertical) {
                params.weight = 1 + .15f * progress;
                setLayoutParams(params);
            }
        }

        @Override protected void onDetachedFromWindow() {
            if (spring != null) spring.cancel();
            super.onDetachedFromWindow();
        }
    }

    private static int blend(int from, int to, float value) {
        return Color.rgb(Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * value),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * value),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * value));
    }

    private final class Check extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Check(int color) {
            paint.setColor(color);
            paint.setStrokeWidth(dp(getContext(), 2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            setBounds(0, 0, dp(getContext(), 14), dp(getContext(), 14));
        }
        @Override public void draw(Canvas canvas) {
            float size = getBounds().width();
            canvas.drawLine(size * .2f, size * .5f, size * .43f, size * .73f, paint);
            canvas.drawLine(size * .43f, size * .73f, size * .82f, size * .27f, paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
