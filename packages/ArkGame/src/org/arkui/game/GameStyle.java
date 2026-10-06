/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.android.settingslib.widget.theme.R;
final class GameStyle {
    static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static int surface(Context c) { return c.getColor(com.android.internal.R.color.materialColorSurfaceContainerHigh); }
    static int ink(Context c) { return c.getColor(com.android.internal.R.color.materialColorOnSurface); }
    static int accent(Context c) { return c.getColor(com.android.internal.R.color.materialColorPrimaryContainer); }
    static GradientDrawable shape(int color, float radius) { var d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d; }
    static TextView text(Context c, CharSequence label, int sp) {
        int appearance = sp >= 32 ? R.style.TextAppearance_SettingsLib_HeadlineLarge
                : sp >= 28 ? R.style.TextAppearance_SettingsLib_HeadlineMedium
                : sp >= 24 ? R.style.TextAppearance_SettingsLib_HeadlineSmall
                : sp >= 20 ? R.style.TextAppearance_SettingsLib_TitleLarge
                : sp >= 16 ? R.style.TextAppearance_SettingsLib_BodyLarge
                : sp >= 14 ? R.style.TextAppearance_SettingsLib_BodyMedium
                : R.style.TextAppearance_SettingsLib_BodySmall;
        return styledText(c, label, sp, appearance);
    }
    static TextView heading(Context c, CharSequence label, int sp) {
        int appearance = sp >= 32 ? R.style.TextAppearance_SettingsLib_HeadlineLarge_Emphasized
                : sp >= 28 ? R.style.TextAppearance_SettingsLib_HeadlineMedium_Emphasized
                : sp >= 24 ? R.style.TextAppearance_SettingsLib_HeadlineSmall_Emphasized
                : sp >= 20 ? R.style.TextAppearance_SettingsLib_TitleLarge_Emphasized
                : sp >= 16 ? R.style.TextAppearance_SettingsLib_TitleMedium_Emphasized
                : R.style.TextAppearance_SettingsLib_TitleSmall_Emphasized;
        var v = styledText(c, label, sp, appearance);
        v.setAccessibilityHeading(true);
        return v;
    }
    private static TextView styledText(Context c, CharSequence label, int sp, int appearance) {
        var v = new TextView(c);
        v.setTextAppearance(appearance);
        v.setTextSize(sp);
        int lineHeight = sp + (sp >= 16 ? 8 : sp >= 14 ? 6 : 4);
        v.setLineHeight(Math.max(v.getPaint().getFontMetricsInt(null), Math.round(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, lineHeight,
                        c.getResources().getDisplayMetrics()))));
        v.setFallbackLineSpacing(true);
        v.setTextColor(ink(c));
        v.setText(label);
        return v;
    }
    private static int buttonInk(Context c, boolean selected) {
        return selected ? c.getColor(com.android.internal.R.color.materialColorOnPrimaryContainer)
                : ink(c);
    }
    private static RippleDrawable buttonBackground(Context c, boolean selected, float radius) {
        int color = selected ? accent(c)
                : c.getColor(com.android.internal.R.color.materialColorSurfaceContainerHighest);
        return new GameButtonBackground(c,color,buttonInk(c,selected),radius);
    }
    private static void buttonSemantics(View v, CharSequence description, boolean selected,
            Runnable action) {
        v.setContentDescription(description);
        v.setSelected(selected);
        v.setFocusable(true);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        v.setOnClickListener(w -> action.run());
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.setSelected(host.isSelected());
            }
        });
    }
    static TextView button(Context c, CharSequence label, boolean selected, Runnable action) {
        var v = styledText(c, label, 16, R.style.TextAppearance_SettingsLib_LabelLarge_Emphasized);
        v.setGravity(Gravity.CENTER); v.setMinHeight(dp(c,56));
        v.setPadding(dp(c,10),dp(c,10),dp(c,10),dp(c,10));
        int color = selected ? c.getColor(com.android.internal.R.color.materialColorPrimary)
                : c.getColor(com.android.internal.R.color.materialColorSurfaceContainerHighest);
        int foreground = selected ? c.getColor(com.android.internal.R.color.materialColorOnPrimary) : ink(c);
        v.setBackground(new GameButtonBackground(c,color,foreground,28));
        v.setTextColor(foreground);
        buttonSemantics(v, label, false, action);
        return v;
    }
    static EditText input(Context c,CharSequence label) {
        var v = new EditText(c);
        v.setTextAppearance(R.style.TextAppearance_SettingsLib_BodyLarge);
        v.setSingleLine(true);
        v.setMinHeight(dp(c,56));
        v.setTextColor(ink(c));
        v.setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12));
        v.setBackground(shape(c.getColor(com.android.internal.R.color.materialColorSurfaceContainerHighest),dp(c,16)));
        v.setContentDescription(label);
        v.setSelectAllOnFocus(true);
        v.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        return v;
    }
    static LinearLayout featureCard(Context c, int label, int summary, boolean selected,
            Runnable action) {
        return featureCard(c, label, summary, selected, action, false);
    }
    static LinearLayout featureCard(Context c, int label, int summary, boolean selected,
            Runnable action, boolean compact) {
        var card = new LinearLayout(c);
        card.setOrientation(compact ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(dp(c, compact ? 56 : 112));
        int padding = dp(c, compact ? 8 : 12);
        card.setPadding(padding, padding, padding, padding);
        card.setBackground(buttonBackground(c, selected, selected ? 20 : 28));
        card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        var copy = column(c, 0);
        copy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        var title = heading(c, c.getString(label), compact ? 14 : 16);
        title.setTextColor(buttonInk(c, selected));
        title.setMaxLines(compact ? 1 : 2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        copy.addView(title, new LinearLayout.LayoutParams(-1, -2));
        if (summary != 0) {
            var detail = text(c, c.getString(summary), compact ? 11 : 12);
            detail.setTextColor(buttonInk(c, selected));
            detail.setMaxLines(compact ? 1 : 2);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            detail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            var detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.topMargin = dp(c, compact ? 2 : 4);
            copy.addView(detail, detailParams);
        }
        var copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        if (compact) {
            copyParams.setMarginEnd(dp(c,8));
            card.addView(copy,copyParams);
        }
        int iconSize = compact ? 24 : 28;
        var icon = new ImageView(c);
        icon.setImageDrawable(new ToolIcon(c, label, selected, iconSize));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        var iconParams = new LinearLayout.LayoutParams(dp(c,iconSize),dp(c,iconSize));
        if (!compact) { iconParams.gravity = Gravity.START; iconParams.bottomMargin = dp(c,8); }
        card.addView(icon,iconParams);
        if (!compact) card.addView(copy,new LinearLayout.LayoutParams(-1,-2));
        buttonSemantics(card, summary == 0 ? c.getString(label)
                : c.getString(label) + ", " + c.getString(summary), selected, action);
        return card;
    }
    static TextView shortcut(Context c, int label, boolean selected, Runnable action) {
        return shortcut(c, label, selected, action, false);
    }
    static void selectFeature(LinearLayout card,boolean selected) {
        var c = card.getContext();
        card.setSelected(selected);
        ((GameButtonBackground) card.getBackground()).select(selected ? accent(c)
                : c.getColor(com.android.internal.R.color.materialColorSurfaceContainerHighest),dp(c,selected ? 20 : 28));
        tintChildren(card,buttonInk(c,selected));
    }
    private static void tintChildren(ViewGroup group,int color) {
        for (int i = 0; i < group.getChildCount(); i++) {
            var child = group.getChildAt(i);
            if (child instanceof TextView text) text.setTextColor(color);
            else if (child instanceof ImageView icon) icon.getDrawable().setTint(color);
            else if (child instanceof ViewGroup nested) tintChildren(nested,color);
        }
    }
    static TextView shortcut(Context c, int label, boolean selected, Runnable action,
            boolean compact) {
        var v = styledText(c, c.getString(label), compact ? 11 : 12,
                R.style.TextAppearance_SettingsLib_LabelMedium);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(c, compact ? 56 : 72));
        v.setMinWidth(dp(c, compact ? 48 : 56));
        int horizontalPadding = dp(c, compact ? 4 : 8);
        int verticalPadding = dp(c, compact ? 6 : 12);
        v.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        v.setMaxLines(compact ? 1 : 2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setCompoundDrawables(null, new ToolIcon(c, label, selected, compact ? 24 : 28),
                null, null);
        v.setCompoundDrawablePadding(dp(c, compact ? 4 : 8));
        v.setTextColor(buttonInk(c, selected));
        if (label == org.arkui.game.R.string.combo || label == org.arkui.game.R.string.timer) {
            int color = c.getColor(label == org.arkui.game.R.string.combo
                    ? com.android.internal.R.color.materialColorSecondaryContainer
                    : com.android.internal.R.color.materialColorTertiaryContainer);
            int foreground = c.getColor(label == org.arkui.game.R.string.combo
                    ? com.android.internal.R.color.materialColorOnSecondaryContainer
                    : com.android.internal.R.color.materialColorOnTertiaryContainer);
            v.setTextColor(foreground);
            var icon = new ToolIcon(c,label,selected,compact ? 24 : 28); icon.setTint(foreground);
            v.setCompoundDrawables(null,icon,null,null);
            v.setBackground(new GameButtonBackground(c,color,foreground,selected ? 16 : 28));
        } else v.setBackground(buttonBackground(c, selected, 28));
        buttonSemantics(v, c.getString(label), selected, action);
        return v;
    }
    static ImageView iconButton(Context c, int label, boolean selected, Runnable action) {
        var v = new ImageView(c);
        v.setMinimumWidth(dp(c, 56));
        v.setMinimumHeight(dp(c, 56));
        v.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setImageDrawable(new ToolIcon(c, label, selected, 28));
        v.setBackground(buttonBackground(c, selected, 24));
        buttonSemantics(v, c.getString(label), selected, action);
        return v;
    }
    static LinearLayout column(Context c, int padding) {
        var v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); int p = dp(c,padding); v.setPadding(p,p,p,p); return v;
    }
    static void add(LinearLayout parent, View child) {
        var lp = new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin = dp(parent.getContext(),8); parent.addView(child,lp);
    }
}
