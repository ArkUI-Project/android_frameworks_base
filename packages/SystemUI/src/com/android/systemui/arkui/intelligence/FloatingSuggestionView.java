/*
 * Copyright (C) 2025-2026 The AviumUI Project
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Appearance and gestures adapted from AviumUseful's FloatingIconManager.
 * ArkUI adds native SystemUI lifecycle and accessibility dismissal support.
 */
package com.android.systemui.arkui.intelligence;

import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.res.R;

import java.io.IOException;

/** Avium-style 48 dp suggestion, with a 32 dp icon and a 50 dp touch window. */
final class FloatingSuggestionView extends FrameLayout {
    private static final long ANIMATION_DURATION = 150;
    private final Runnable mDismiss;
    private final ImageView mIcon;
    private float mDownX;
    private float mDownY;
    private boolean mMoved;
    private boolean mCanceled;
    private boolean mDismissing;

    FloatingSuggestionView(Context context, int iconResource, Runnable dismiss) {
        super(context);
        mDismiss = dismiss;
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        final FrameLayout circle = new FrameLayout(context);
        circle.setBackgroundResource(R.drawable.arkui_suggestion_background);
        circle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(circle, new LayoutParams(dp(48), dp(48), Gravity.CENTER));
        mIcon = new ImageView(context);
        setIcon(Icon.createWithResource(context, iconResource), context.getUserId());
        mIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        circle.addView(mIcon, new LayoutParams(dp(32), dp(32), Gravity.CENTER));
    }

    void setIcon(Icon icon, int userId) {
        try {
            // The upstream assets include large source images. Decode only the pixels
            // needed by the floating icon instead of retaining a full-size bitmap.
            if (icon.getType() == Icon.TYPE_RESOURCE
                    && getContext().getPackageName().equals(icon.getResPackage())) {
                mIcon.setImageDrawable(ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(getResources(), icon.getResId()),
                        (decoder, info, source) -> decoder.setTargetSize(dp(32), dp(32))));
            } else {
                final Drawable drawable = icon.loadDrawableAsUser(getContext(), userId);
                if (drawable != null) mIcon.setImageDrawable(drawable);
            }
        } catch (IOException e) {
            Log.w("ArkIntelligence", "Unable to decode suggestion icon", e);
            mIcon.setImageResource(android.R.drawable.ic_dialog_info);
        }
    }

    void animateIn() {
        setScaleX(0f);
        setScaleY(0f);
        setAlpha(0f);
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(ANIMATION_DURATION)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void dismiss() {
        if (mDismissing) return;
        mDismissing = true;
        animate().cancel();
        animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(ANIMATION_DURATION)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(mDismiss).start();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (mDismissing) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getRawX();
                mDownY = event.getRawY();
                mMoved = false;
                mCanceled = false;
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                mCanceled = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                mMoved |= Math.abs(event.getRawX() - mDownX) >= dp(10)
                        || Math.abs(event.getRawY() - mDownY) >= dp(10);
                return true;
            case MotionEvent.ACTION_UP:
                if (mCanceled) return true;
                final float dx = event.getRawX() - mDownX;
                final float dy = event.getRawY() - mDownY;
                if (!mMoved && Math.abs(dx) < dp(10) && Math.abs(dy) < dp(10)) {
                    performClick();
                } else if (Math.abs(dx) > dp(50) || dy < -dp(50)) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                    dismiss();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.Button.class.getName());
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_DISMISS,
                getContext().getString(R.string.arkui_suggest_dismiss)));
    }

    @Override public boolean performClick() {
        return !mDismissing && super.performClick();
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_DISMISS) {
            dismiss();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
