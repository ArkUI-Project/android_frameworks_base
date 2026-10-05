/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
final class GameStyle {
    static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static int surface(Context c) { return c.getColor(com.android.internal.R.color.materialColorSurfaceContainerHigh); }
    static int ink(Context c) { return c.getColor(com.android.internal.R.color.materialColorOnSurface); }
    static int accent(Context c) { return c.getColor(com.android.internal.R.color.materialColorPrimaryContainer); }
    static GradientDrawable shape(int color, float radius) { var d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d; }
    static TextView text(Context c, CharSequence label, int sp) {
        var v = new TextView(c); v.setTextAppearance(com.android.settingslib.widget.theme.R.style.TextAppearance_SettingsLib_BodyMedium);
        v.setTextSize(sp); v.setTextColor(ink(c)); v.setText(label); return v;
    }
    static TextView button(Context c, CharSequence label, boolean selected, Runnable action) {
        var v = text(c, label, 14); v.setGravity(Gravity.CENTER); v.setMinHeight(dp(c,56));
        v.setPadding(dp(c,10),dp(c,10),dp(c,10),dp(c,10));
        int color = selected ? accent(c) : c.getColor(com.android.internal.R.color.materialColorSurfaceContainerHighest);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf((ink(c)&0xffffff)|0x24000000), shape(color, dp(c,selected?28:18)), shape(Color.WHITE,dp(c,28))));
        if (selected) v.setTextColor(c.getColor(com.android.internal.R.color.materialColorOnPrimaryContainer));
        v.setSelected(selected); v.setFocusable(true); v.setOnClickListener(w -> action.run());
        return v;
    }
    static LinearLayout column(Context c, int padding) {
        var v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); int p = dp(c,padding); v.setPadding(p,p,p,p); return v;
    }
    static void add(LinearLayout parent, View child) {
        var lp = new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin = dp(parent.getContext(),8); parent.addView(child,lp);
    }
}
