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

package com.android.server.am;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.FloatProperty;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

/** Native Material 3 Expressive presentation shared by crash and ANR dialogs. */
final class AppErrorDialogUi {
    static final int PRIMARY = 0;
    static final int TONAL = 1;
    static final int TEXT = 2;

    private final BaseErrorDialog mDialog;
    private final Context mContext;
    private final ScrollView mScroll;
    private final LinearLayout mContent;
    private LinearLayout mActionGroup;
    private final ArrayList<Button> mActions = new ArrayList<>();
    private final int mSurface;
    private final int mOnSurface;
    private final int mOnSurfaceVariant;
    private final int mPrimaryContainer;
    private final int mOnPrimaryContainer;
    private final int mSecondaryContainer;
    private final int mOnSecondaryContainer;
    private final int mDetailsSurface;
    private boolean mActionsEnabled;
    private final Runnable mEnableActions = () -> setActionsEnabled(true);

    AppErrorDialogUi(BaseErrorDialog dialog) {
        mDialog = dialog;
        mContext = dialog.getContext();
        final boolean dark = (mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        mSurface = color(dark ? android.R.color.system_surface_container_high_dark
                : android.R.color.system_surface_container_high_light);
        mOnSurface = color(dark ? android.R.color.system_on_surface_dark
                : android.R.color.system_on_surface_light);
        mOnSurfaceVariant = color(dark ? android.R.color.system_on_surface_variant_dark
                : android.R.color.system_on_surface_variant_light);
        mPrimaryContainer = color(dark ? android.R.color.system_primary_container_dark
                : android.R.color.system_primary_container_light);
        mOnPrimaryContainer = color(dark ? android.R.color.system_on_primary_container_dark
                : android.R.color.system_on_primary_container_light);
        mSecondaryContainer = color(dark ? android.R.color.system_secondary_container_dark
                : android.R.color.system_secondary_container_light);
        mOnSecondaryContainer = color(dark ? android.R.color.system_on_secondary_container_dark
                : android.R.color.system_on_secondary_container_light);
        mDetailsSurface = color(dark ? android.R.color.system_surface_container_highest_dark
                : android.R.color.system_surface_container_highest_light);

        mContent = new LinearLayout(mContext);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setPadding(dp(24), dp(16), dp(24), dp(24));
        mScroll = new ScrollView(mContext) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                final int maximum = (int) (getResources().getDisplayMetrics().heightPixels * .7f);
                final int available = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                        ? maximum : Math.min(maximum, MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(available,
                        MeasureSpec.AT_MOST));
            }
        };
        mScroll.setFillViewport(false);
        mScroll.setClipToPadding(false);
        mScroll.addView(mContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Retain the original error dialog's title panel and alert layout.
        final FrameLayout frame = dialog.findViewById(android.R.id.custom);
        frame.addView(mScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.findViewById(com.android.internal.R.id.customPanel).setVisibility(View.VISIBLE);
    }

    void start() {
        final Window window = mDialog.getWindow();
        final int width = Math.min(dp(400),
                mContext.getResources().getDisplayMetrics().widthPixels - dp(48));
        window.setBackgroundDrawable(shape(mSurface, 28));
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.CENTER);
        window.setDimAmount(.32f);
        window.setElevation(dp(6));
        window.getDecorView().setClipToOutline(true);
        mDialog.findViewById(com.android.internal.R.id.topPanel).setBackground(null);
        mDialog.findViewById(com.android.internal.R.id.customPanel).setBackground(null);

        // Keep the error dialog's one-second protection against accidental input.
        setActionsEnabled(false);
        mScroll.postDelayed(mEnableActions, 1000);
        final View decor = window.getDecorView();
        decor.setAlpha(0f);
        decor.setScaleX(.94f);
        decor.setScaleY(.94f);
        decor.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f)).start();
    }

    void stop() {
        mScroll.removeCallbacks(mEnableActions);
        mContent.animate().cancel();
        mDialog.getWindow().getDecorView().animate().cancel();
    }

    void showHeader(CharSequence title, CharSequence description) {
        mDialog.setTitle(title);
        final TextView heading = mDialog.findViewById(com.android.internal.R.id.alertTitle);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        heading.setTextColor(mOnSurface);
        heading.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        heading.setSingleLine(false);
        heading.setMaxLines(4);
        heading.setEllipsize(null);
        heading.setAccessibilityHeading(true);
        mContent.removeAllViews();
        mActions.clear();
        mActionGroup = null;
        mScroll.scrollTo(0, 0);
        if (description != null) add(text(description, 14, mOnSurfaceVariant), 0);
        mActionGroup = new LinearLayout(mContext);
        mActionGroup.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams actions = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actions.topMargin = description == null ? 0 : dp(16);
        mContent.addView(mActionGroup, actions);
        animatePage();
    }

    Button addAction(int id, int label, int icon, int kind, View.OnClickListener listener) {
        final Button button = new Button(mContext, null, 0);
        button.setId(id);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(64));
        button.setMinimumHeight(dp(64));
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setTextColor(mOnSurface);
        if (icon != 0) {
            final boolean primary = kind == PRIMARY
                    && id != com.android.internal.R.id.aerr_close;
            final int fill = primary ? mPrimaryContainer : mSecondaryContainer;
            final int ink = primary ? mOnPrimaryContainer : mOnSecondaryContainer;
            final LayerDrawable badge = new LayerDrawable(new Drawable[] {
                    shape(fill, 20), icon(icon, ink)});
            badge.setLayerInset(1, dp(8), dp(8), dp(8), dp(8));
            badge.setBounds(0, 0, dp(40), dp(40));
            button.setCompoundDrawablesRelative(badge, null, null, null);
            button.setCompoundDrawablePadding(dp(16));
        }
        button.setOnClickListener(listener);
        button.setEnabled(mActionsEnabled);
        button.setAlpha(mActionsEnabled ? 1f : .38f);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = mActions.isEmpty() ? 0 : dp(2);
        mActionGroup.addView(button, params);
        mActions.add(button);
        updateRowShapes();
        return button;
    }

    private void updateRowShapes() {
        for (int i = 0; i < mActions.size(); i++) {
            final Button button = mActions.get(i);
            final boolean first = i == 0;
            final boolean last = i == mActions.size() - 1;
            final float[] resting = corners(first ? 24 : 4, last ? 24 : 4);
            final float[] pressed = corners(first ? 24 : 12, last ? 24 : 12);
            final GradientDrawable background = shape(mDetailsSurface, 0);
            final GradientDrawable mask = shape(0xffffffff, 0);
            background.setCornerRadii(resting);
            mask.setCornerRadii(resting);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(
                    (mOnSurface & 0x00ffffff) | 0x1f000000), background, mask));
            // StateListAnimator retargets every ObjectAnimator to the button. Keep the
            // drawable interpolation on a button property rather than a drawable target.
            final float[] radii = resting.clone();
            final FloatProperty<View> cornerMorph = new FloatProperty<>("cornerMorph") {
                private float mFraction;

                @Override
                public Float get(View view) {
                    return mFraction;
                }

                @Override
                public void setValue(View view, float fraction) {
                    mFraction = fraction;
                    for (int corner = 0; corner < radii.length; corner++) {
                        radii[corner] = resting[corner]
                                + (pressed[corner] - resting[corner]) * fraction;
                    }
                    background.setCornerRadii(radii);
                    mask.setCornerRadii(radii);
                }
            };
            final StateListAnimator motion = new StateListAnimator();
            motion.addState(new int[] {android.R.attr.state_pressed, android.R.attr.state_enabled},
                    press(button, cornerMorph, .985f, 1f));
            motion.addState(new int[0], press(button, cornerMorph, 1f, 0f));
            button.setStateListAnimator(motion);
        }
    }

    private float[] corners(int top, int bottom) {
        return new float[] {dp(top), dp(top), dp(top), dp(top),
                dp(bottom), dp(bottom), dp(bottom), dp(bottom)};
    }

    void addStackTrace(CharSequence stackTrace, Context userContext) {
        // Text selection and its clipboard actions must belong to the crashing app's user.
        final TextView stack = new TextView(new ContextThemeWrapper(
                userContext, mContext.getTheme()));
        stack.setText(stackTrace);
        stack.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        stack.setTextColor(mOnSurface);
        stack.setLineSpacing(dp(3), 1f);
        stack.setTypeface(Typeface.MONOSPACE);
        stack.setTextDirection(View.TEXT_DIRECTION_LTR);
        stack.setTextIsSelectable(true);
        stack.setHorizontallyScrolling(true);
        stack.setPadding(dp(16), dp(16), dp(16), dp(16));
        final HorizontalScrollView horizontal = new HorizontalScrollView(mContext);
        horizontal.addView(stack, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final ScrollView vertical = new ScrollView(mContext);
        vertical.setNestedScrollingEnabled(true);
        vertical.setBackground(shape(mDetailsSurface, 16));
        vertical.setClipToOutline(true);
        vertical.addView(horizontal, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(dp(360),
                        (int) (mContext.getResources().getDisplayMetrics().heightPixels * .4f)));
        params.topMargin = dp(20);
        mContent.addView(vertical, mContent.indexOfChild(mActionGroup), params);
        ((LinearLayout.LayoutParams) mActionGroup.getLayoutParams()).topMargin = dp(16);
    }

    private void animatePage() {
        mContent.animate().cancel();
        mContent.setAlpha(0f);
        mContent.setTranslationY(dp(8));
        mContent.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f)).start();
    }

    private void setActionsEnabled(boolean enabled) {
        mActionsEnabled = enabled;
        for (Button action : mActions) {
            action.setEnabled(enabled);
            action.setAlpha(enabled ? 1f : .38f);
        }
    }

    private AnimatorSet press(View view, FloatProperty<View> cornerMorph,
            float value, float fraction) {
        final AnimatorSet animation = new AnimatorSet();
        animation.playTogether(ObjectAnimator.ofFloat(view, View.SCALE_X, value),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, value),
                ObjectAnimator.ofFloat(view, cornerMorph, fraction));
        animation.setDuration(120);
        animation.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
        return animation;
    }

    private TextView text(CharSequence value, int size, int color) {
        final TextView text = new TextView(mContext);
        text.setText(value);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        text.setTextColor(color);
        text.setLineSpacing(dp(3), 1f);
        return text;
    }

    private void add(View view, int topMargin) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        mContent.addView(view, mActionGroup == null ? -1 : mContent.indexOfChild(mActionGroup),
                params);
    }

    private Drawable icon(int resource, int tint) {
        final Drawable icon = mContext.getDrawable(resource).mutate();
        icon.setTint(tint);
        icon.setBounds(0, 0, dp(24), dp(24));
        return icon;
    }

    private GradientDrawable shape(int fill, int radius) {
        final GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int color(int resource) {
        return mContext.getColor(resource);
    }

    private int dp(int value) {
        return Math.round(value * mContext.getResources().getDisplayMetrics().density);
    }
}
