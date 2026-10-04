/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The AviumUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
import static android.view.WindowManager.LayoutParams.TYPE_PINNED_WINDOW_DISMISS_HINT;

import static com.android.internal.arkui.DebugConstants.DEBUG_POP_UP;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.animation.TimeInterpolator;
import android.app.ActivityThread;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.internal.R;

class PinnedWindowDismissView extends FrameLayout {

    private static final String TAG = "PinnedWindowDismissView";

    private static final long ANIMATION_START_DELAY_SHOW = 100L;
    private static final long ANIMATION_START_DELAY_HIDE = 0L;
    private static final long DEFAULT_FADE_DURATION = 300L;
    private static final long DEFAULT_FADE_DURATION_DISMISS = 150L;
    private static final float ALPHA_CUTOFF_THRESHOLD = 0.001f;

    private static final TimeInterpolator DEFAULT_INTERPOLATOR = new PathInterpolator(0.17f, 0.0f, 0.83f, 1.0f);

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Runnable mFadeAnimationEndRunnable;

    private final int mDismissThresholdY;

    private ImageView mButtonView;

    private boolean mAddedToWm;
    private boolean mIsVisible;

    private Handler mHandler;
    private ViewPropertyAnimator mCurrentAnimation;
    private WindowManager.LayoutParams mWmLayoutParams;

    public PinnedWindowDismissView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFadeAnimationEndRunnable = () -> {
            updateVisibility();
            if (!mIsVisible) {
                dismiss();
            }
        };
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mDismissThresholdY = getContext().getResources().getDimensionPixelSize(
                R.dimen.pinned_window_dismiss_gradient_bg_height);

        mAddedToWm = false;
        mIsVisible = false;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mButtonView = findViewById(R.id.pinned_window_dismiss_button);
        setAlpha(0.0f);
    }

    void showOnScreen(Looper looper) {
        if (mAddedToWm) {
            return;
        }
        mHandler = new Handler(looper);
        mWmLayoutParams = new WindowManager.LayoutParams(LayoutParams.MATCH_PARENT,
                mDismissThresholdY,
                TYPE_PINNED_WINDOW_DISMISS_HINT,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_NO_LIMITS, PixelFormat.RGBA_8888);
        mWmLayoutParams.setTrustedOverlay();
        mWmLayoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        mWmLayoutParams.setTitle(TAG);
        mWmLayoutParams.privateFlags |= SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        mWmLayoutParams.gravity = Gravity.AXIS_SPECIFIED | Gravity.TOP;
        mWmLayoutParams.setFitInsetsTypes(0);
        mWmLayoutParams.x = 0;
        mWmLayoutParams.y = 0;
        try {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "start showOnScreen");
            }
            mAddedToWm = true;
            mHandler.post(() -> mWindowManager.addView(this, mWmLayoutParams));
        } catch (Exception e) {
            Slog.e(TAG, "add to wm occur error", e);
        }
        animateToVisibility(true, ANIMATION_START_DELAY_SHOW, DEFAULT_FADE_DURATION);
    }

    void hideOnScreen(boolean isDismiss) {
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "start hideOnScreen isDismiss=" + isDismiss);
        }
        animateToVisibility(false, isDismiss ? ANIMATION_START_DELAY_HIDE : 0L,
                isDismiss ? DEFAULT_FADE_DURATION_DISMISS : DEFAULT_FADE_DURATION);
    }

    boolean isInRemoveThreshold(float y) {
        return y < mDismissThresholdY;
    }

    void setButtonSelected(boolean selected) {
        if (mButtonView != null) {
            mButtonView.setSelected(selected);
        }
    }

    void reset() {
        mIsVisible = false;
        if (mCurrentAnimation != null) {
            mCurrentAnimation.cancel();
            mCurrentAnimation = null;
        }
        setAlpha(0.0f);
        dismiss();
    }

    private void dismiss() {
        if (mAddedToWm) {
            try {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "start dismiss");
                }
                mAddedToWm = false;
                mHandler.post(() -> mWindowManager.removeView(this));
            } catch (Exception e) {
                Slog.e(TAG, "remove from wm occur error", e);
            }
        }
    }

    private void animateToVisibility(boolean isVisible, long startDelay, long duration) {
        if (mIsVisible != isVisible) {
            mIsVisible = isVisible;
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "animateToVisibility: isVisible=" + isVisible);
            }
            if (mCurrentAnimation != null) {
                mCurrentAnimation.cancel();
                mCurrentAnimation = null;
            }
            final float finalAlpha = mIsVisible ? 1.0f : 0.0f;
            if (Float.compare(getAlpha(), finalAlpha) != 0) {
                setVisibility(View.VISIBLE);
                mCurrentAnimation = animate().alpha(finalAlpha).setStartDelay(startDelay)
                        .setInterpolator(DEFAULT_INTERPOLATOR)
                        .setDuration(duration)
                        .withEndAction(mFadeAnimationEndRunnable);
            } else if (!mIsVisible) {
                dismiss();
            }
        }
    }

    private void updateVisibility() {
        if (getAlpha() < ALPHA_CUTOFF_THRESHOLD && getVisibility() != View.INVISIBLE) {
            setVisibility(View.INVISIBLE);
        } else if (getAlpha() > ALPHA_CUTOFF_THRESHOLD && getVisibility() != View.VISIBLE) {
            final int oldFocusability = getDescendantFocusability();
            setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            setVisibility(View.VISIBLE);
            setDescendantFocusability(oldFocusability);
        }
    }
}
