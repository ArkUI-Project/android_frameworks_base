/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package android.inputmethodservice;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;

/** The expressive dictation dock occupies the IME caption inset, not another overlay window. */
final class ImeVoiceInputView extends FrameLayout {
    private final FrameLayout mCapsule;
    private final Waveform mWaveform;
    private final TextView mStatus;
    private final ImageView mFinish;
    private final ImageButton mDismiss;
    private final ImageButton mSwitcher;
    private final View mHandle;
    private final Runnable mOnVoice;
    private final Runnable mOnCancel;
    private final Runnable mOnHide;
    private ValueAnimator mExpansion;
    private float mExpanded;
    private boolean mActive;
    private boolean mShowSwitcher;
    private int mForeground;
    private int mAccent;
    private int mSurface;
    private int mContainer;
    private int mActiveContainer;

    ImeVoiceInputView(Context context, Runnable onVoice, Runnable onCancel, Runnable onHide,
            Runnable onSwitch) {
        super(context);
        mOnVoice = onVoice;
        mOnCancel = onCancel;
        mOnHide = onHide;
        setClipChildren(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        mCapsule = new FrameLayout(context);
        mCapsule.setClickable(true);
        mCapsule.setFocusable(true);
        mCapsule.setOnClickListener(v -> mOnVoice.run());
        mCapsule.setContentDescription(context.getString(R.string.ime_voice_start));
        addView(mCapsule, new LayoutParams(dp(64), dp(48), Gravity.BOTTOM | Gravity.START));

        mWaveform = new Waveform(context);
        mCapsule.addView(mWaveform, new LayoutParams(dp(48), dp(48), Gravity.START));
        mStatus = new TextView(context);
        mStatus.setTextSize(14);
        mStatus.setSingleLine(true);
        mStatus.setEllipsize(TextUtils.TruncateAt.END);
        mStatus.setGravity(Gravity.CENTER_VERTICAL);
        mStatus.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams statusParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(48));
        statusParams.setMarginStart(dp(48));
        statusParams.setMarginEnd(dp(40));
        mCapsule.addView(mStatus, statusParams);
        mFinish = new ImageView(context);
        mFinish.setImageResource(R.drawable.ic_check_24dp);
        mFinish.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mFinish.setPadding(dp(8), dp(12), dp(8), dp(12));
        mFinish.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        mCapsule.addView(mFinish, new LayoutParams(dp(40), dp(48), Gravity.END));

        mSwitcher = button(R.drawable.ic_ime_switcher, R.string.inputMethod, onSwitch);
        addView(mSwitcher, new LayoutParams(dp(48), dp(48), Gravity.BOTTOM | Gravity.END));
        mDismiss = button(R.drawable.ic_ime_nav_back, R.string.ime_voice_hide, () -> {
            if (mActive) mOnCancel.run(); else mOnHide.run();
        });
        addView(mDismiss, new LayoutParams(dp(48), dp(48), Gravity.BOTTOM | Gravity.END));
        mHandle = new View(context);
        mHandle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams handleParams = new LayoutParams(dp(44), dp(4), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        handleParams.bottomMargin = dp(7);
        addView(mHandle, handleParams);
        updateColors();
        applyExpansion();
    }

    private ImageButton button(int icon, int label, Runnable action) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(icon);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setContentDescription(getContext().getString(label));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    void setSwitcherVisible(boolean visible) {
        mShowSwitcher = visible;
        applyExpansion();
    }

    void setVoiceEnabled(boolean enabled) {
        mCapsule.setEnabled(enabled);
        mCapsule.setAlpha(enabled ? 1f : 0.38f);
    }

    void showState(boolean active, boolean processing, String text, float level) {
        boolean changed = mActive != active;
        mActive = active;
        mStatus.setText(text);
        mWaveform.setLevel(active && !processing ? level : 0f);
        mCapsule.setContentDescription(active ? text + ", " + getContext().getString(
                processing ? R.string.ime_voice_processing : R.string.ime_voice_finish)
                : getContext().getString(R.string.ime_voice_start));
        mCapsule.setClickable(!processing);
        mFinish.setVisibility(processing ? INVISIBLE : VISIBLE);
        mDismiss.setImageResource(active ? R.drawable.ic_close : R.drawable.ic_ime_nav_back);
        mDismiss.setRotation(active ? 0f : isLayoutRtl() ? 90f : -90f);
        mDismiss.setContentDescription(getContext().getString(
                active ? R.string.cancel : R.string.ime_voice_hide));
        if (changed) {
            updateCapsuleBackground();
            if (mExpansion != null) mExpansion.cancel();
            mExpansion = ValueAnimator.ofFloat(mExpanded, active ? 1f : 0f);
            mExpansion.setDuration(active ? 220 : 160);
            mExpansion.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
            mExpansion.addUpdateListener(a -> {
                mExpanded = (float) a.getAnimatedValue();
                applyExpansion();
            });
            mExpansion.start();
        }
    }

    private void applyExpansion() {
        if (mCapsule == null) return;
        int available = Math.max(dp(64), getWidth() - getPaddingLeft() - getPaddingRight() - dp(72));
        LayoutParams capsule = (LayoutParams) mCapsule.getLayoutParams();
        capsule.width = dp(64) + Math.round((available - dp(64)) * mExpanded);
        capsule.setMarginStart(dp(16));
        mCapsule.setLayoutParams(capsule);
        mCapsule.setTranslationY(-dp(14) * mExpanded);
        mDismiss.setTranslationY(-dp(14) * mExpanded);
        mStatus.setAlpha(mExpanded);
        mFinish.setAlpha(mExpanded);
        mStatus.setVisibility(mExpanded > 0f ? VISIBLE : INVISIBLE);
        mWaveform.setTranslationX((isLayoutRtl() ? -1 : 1) * dp(8) * (1f - mExpanded));
        mSwitcher.setVisibility(mShowSwitcher && !mActive ? VISIBLE : GONE);
        LayoutParams switcher = (LayoutParams) mSwitcher.getLayoutParams();
        switcher.setMarginEnd(dp(48));
        mSwitcher.setLayoutParams(switcher);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyExpansion();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        android.graphics.Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
        setPadding(cutout.left, 0, cutout.right, 0);
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        updateColors();
    }

    private void updateColors() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        mForeground = getContext().getColor(dark ? R.color.system_on_surface_dark : R.color.system_on_surface_light);
        mAccent = getContext().getColor(dark ? R.color.system_primary_dark : R.color.system_primary_light);
        mSurface = getContext().getColor(dark ? R.color.system_surface_container_dark : R.color.system_surface_container_light);
        mContainer = getContext().getColor(dark ? R.color.system_surface_container_highest_dark : R.color.system_surface_container_lowest_light);
        mActiveContainer = getContext().getColor(dark ? R.color.system_secondary_container_dark : R.color.system_secondary_container_light);
        setBackgroundColor(mSurface);
        mStatus.setTextColor(mForeground);
        mFinish.setImageTintList(ColorStateList.valueOf(mForeground));
        for (ImageButton button : new ImageButton[] {mDismiss, mSwitcher}) {
            button.setImageTintList(ColorStateList.valueOf(mForeground));
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), null,
                    shape(0xffffffff, 24)));
        }
        mDismiss.setRotation(mActive ? 0f : isLayoutRtl() ? 90f : -90f);
        mHandle.setBackground(shape(mForeground, 2));
        updateCapsuleBackground();
        mWaveform.invalidate();
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private void updateCapsuleBackground() {
        mCapsule.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22777777),
                new InsetDrawable(shape(mActive ? mActiveContainer : mContainer, 20), 0, dp(8), 0, dp(8)),
                new InsetDrawable(shape(0xffffffff, 20), 0, dp(8), 0, dp(8))));
        // The inset shapes shrink only the visual surface, not the 48dp content/touch area.
        mCapsule.setPadding(0, 0, 0, 0);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mExpansion != null) mExpansion.cancel();
        mOnCancel.run();
        super.onDetachedFromWindow();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class Waveform extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mLevel;
        private ValueAnimator mAmplitude;

        Waveform(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mPaint.setStrokeWidth(dp(2));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setLevel(float value) {
            if (mAmplitude != null) mAmplitude.cancel();
            mAmplitude = ValueAnimator.ofFloat(mLevel, Math.max(0f, Math.min(1f, value)));
            mAmplitude.setDuration(90);
            mAmplitude.addUpdateListener(a -> { mLevel = (float) a.getAnimatedValue(); invalidate(); });
            mAmplitude.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float[] heights = {12f, 4f, 18f, 7f, 14f};
            for (int i = 0; i < heights.length; i++) {
                float height = dp(heights[i] + mLevel * (i % 2 == 0 ? 5 : 13));
                float x = getWidth() / 2f + dp((i - 2) * 4);
                mPaint.setColor(i == 4 ? mAccent : mForeground);
                canvas.drawLine(x, (getHeight() - height) / 2f, x, (getHeight() + height) / 2f, mPaint);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            if (mAmplitude != null) mAmplitude.cancel();
            super.onDetachedFromWindow();
        }
    }
}
