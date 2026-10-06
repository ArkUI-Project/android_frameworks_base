/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Keeps the panel's window and surface attached while its content changes. */
final class GamePageContainer extends FrameLayout {
    private View current, outgoing;
    private SpringAnimation transition;

    GamePageContainer(Context context) {
        super(context);
        setClipChildren(true);
        setClipToPadding(true);
    }

    void show(View next, CharSequence title, int direction) {
        View previous = current;
        if (transition != null) {
            SpringAnimation running = transition;
            transition = null;
            running.cancel();
            // A rapid tab change retains whichever page is currently most visible.
            if (outgoing != null && outgoing.getAlpha() > current.getAlpha()) {
                removeView(current);
                previous = outgoing;
            } else {
                removeView(outgoing);
            }
        }
        outgoing = previous;
        current = next;
        addView(next, new LayoutParams(-1, -1));
        setAccessibilityPaneTitle(title);
        if (previous == null || !ValueAnimator.areAnimatorsEnabled() || direction == 0) {
            removeView(previous);
            outgoing = null;
            return;
        }
        previous.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        float distance = GameStyle.dp(getContext(), 24) * direction
                * (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1);
        float previousAlpha = previous.getAlpha(), previousX = previous.getTranslationX();
        float previousScale = previous.getScaleX();
        next.setAlpha(0);
        next.setTranslationX(distance);
        next.setScaleX(.96f); next.setScaleY(.96f);
        View exiting = previous;
        transition = new SpringAnimation(new FloatValueHolder(0));
        transition.setMinimumVisibleChange(.001f);
        transition.setSpring(new SpringForce(1).setStiffness(600).setDampingRatio(1));
        transition.addUpdateListener((animation, value, velocity) -> {
            float progress = Math.max(0, Math.min(1, value));
            next.setAlpha(progress);
            next.setTranslationX(distance * (1 - progress));
            next.setScaleX(.96f + .04f * progress); next.setScaleY(next.getScaleX());
            exiting.setAlpha(previousAlpha * (1 - progress));
            exiting.setTranslationX(previousX - distance * progress * 0.5f);
            exiting.setScaleX(previousScale + .02f * progress); exiting.setScaleY(exiting.getScaleX());
        });
        transition.addEndListener((animation, canceled, value, velocity) -> {
            if (canceled || animation != transition) return;
            next.setAlpha(1);
            next.setTranslationX(0);
            next.setScaleX(1); next.setScaleY(1);
            removeView(exiting);
            outgoing = null;
            transition = null;
        });
        transition.start();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // Outgoing controls cannot execute an action in the new page's context.
        return transition != null || super.dispatchTouchEvent(event);
    }

    @Override protected void onDetachedFromWindow() {
        if (transition != null) {
            SpringAnimation running = transition;
            transition = null;
            running.cancel();
        }
        removeView(outgoing);
        outgoing = null;
        if (current != null) {
            current.setAlpha(1);
            current.setTranslationX(0);
            current.setScaleX(1); current.setScaleY(1);
        }
        super.onDetachedFromWindow();
    }
}
