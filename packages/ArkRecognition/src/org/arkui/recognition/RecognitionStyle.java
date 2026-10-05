/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

/** Native controls use the same expressive theme and dynamic roles as system Settings. */
final class RecognitionStyle {
    static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static int surface(Context c) { return c.getColor(com.android.internal.R.color.materialColorSurfaceContainerHigh); }
    static int onSurface(Context c) { return c.getColor(com.android.internal.R.color.materialColorOnSurface); }
    static int primary(Context c) { return c.getColor(com.android.internal.R.color.materialColorPrimary); }
    static int container(Context c) { return c.getColor(com.android.internal.R.color.materialColorPrimaryContainer); }
    static int onContainer(Context c) { return c.getColor(com.android.internal.R.color.materialColorOnPrimaryContainer); }
    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d;
    }
    static RippleDrawable button(Context c, int color, float radius) {
        return new RippleDrawable(ColorStateList.valueOf((onSurface(c) & 0xffffff) | 0x24000000),
                round(color, radius), round(Color.WHITE, radius));
    }
    static TextView text(Context c, CharSequence label, int sp) {
        TextView view = new TextView(c);
        view.setTextAppearance(sp >= 20
                ? com.android.settingslib.widget.theme.R.style.TextAppearance_SettingsLib_TitleLarge_Emphasized
                : sp >= 16 ? com.android.settingslib.widget.theme.R.style.TextAppearance_SettingsLib_BodyLarge
                : com.android.settingslib.widget.theme.R.style.TextAppearance_SettingsLib_BodyMedium);
        view.setText(label); view.setTextSize(sp); view.setTextColor(onSurface(c));
        int lineHeight = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                sp + (sp >= 16 ? 8 : 6), c.getResources().getDisplayMetrics()));
        view.setLineHeight(Math.max(lineHeight, view.getPaint().getFontMetricsInt(null)));
        view.setFallbackLineSpacing(true); return view;
    }
    static TextView chip(Context c, int label, boolean selected, Runnable run) {
        TextView view = text(c, c.getString(label), 14);
        view.setTextAppearance(com.android.settingslib.widget.theme.R.style.TextAppearance_SettingsLib_LabelLarge);
        view.setGravity(Gravity.CENTER); view.setMinHeight(dp(c, 48)); view.setMinWidth(dp(c, 48));
        view.setPadding(dp(c, 18), dp(c, 10), dp(c, 18), dp(c, 10));
        view.setTextColor(selected ? onContainer(c) : onSurface(c));
        if (label == R.string.text_mode || label == R.string.image_mode) {
            Symbol symbol = new Symbol(c, label, selected ? onContainer(c) : onSurface(c));
            symbol.setBounds(0, 0, dp(c, 24), dp(c, 24));
            view.setCompoundDrawablesRelative(symbol, null, null, null); view.setCompoundDrawablePadding(dp(c, 8));
        }
        view.setBackground(button(c, selected ? container(c) : surface(c), dp(c, 28)));
        semantics(view, c.getString(label), selected, run); return view;
    }
    static ImageView icon(Context c, int label, boolean selected, Runnable run) {
        ImageView view = new ImageView(c); view.setMinimumWidth(dp(c, 48)); view.setMinimumHeight(dp(c, 48));
        view.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12)); view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImageDrawable(new Symbol(c, label, selected ? onContainer(c) : onSurface(c)));
        view.setBackground(button(c, selected ? container(c) : surface(c), dp(c, 24)));
        semantics(view, c.getString(label), selected, run); return view;
    }
    private static void semantics(View view, String label, boolean selected, Runnable run) {
        view.setContentDescription(label); view.setSelected(selected); view.setFocusable(true);
        view.setOnClickListener(v -> run.run());
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); info.setSelected(selected);
            }
        });
    }
    private static final class Symbol extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int label, size;
        Symbol(Context c, int label, int color) {
            this.label = label; size = dp(c, 24); p.setColor(color);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.8f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void draw(Canvas c) {
            c.save(); c.translate(getBounds().left, getBounds().top); c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            if (label == R.string.text_mode) {
                c.drawLine(4, 5, 20, 5, p); c.drawLine(12, 5, 12, 19, p); c.drawLine(8, 19, 16, 19, p);
            } else if (label == R.string.close) { c.drawLine(6, 6, 18, 18, p); c.drawLine(18, 6, 6, 18, p); }
            else if (label == R.string.more) { for (int y = 5; y <= 19; y += 7) { p.setStyle(Paint.Style.FILL); c.drawCircle(12, y, 1.5f, p); p.setStyle(Paint.Style.STROKE); } }
            else if (label == R.string.details) { c.drawCircle(12, 12, 9, p); c.drawLine(12, 11, 12, 17, p); c.drawPoint(12, 7, p); }
            else if (label == R.string.save_image || label == R.string.save_video || label == R.string.save_frame) {
                c.drawLine(12, 3, 12, 15, p); c.drawLine(7, 10, 12, 15, p); c.drawLine(17, 10, 12, 15, p);
                Path path = new Path(); path.moveTo(4, 15); path.lineTo(4, 20); path.lineTo(20, 20); path.lineTo(20, 15); c.drawPath(path, p);
            } else if (label == R.string.select_video) {
                c.drawRoundRect(3, 5, 21, 19, 3, 3, p); Path path = new Path(); path.moveTo(10, 9); path.lineTo(15, 12); path.lineTo(10, 15); path.close(); c.drawPath(path, p);
            } else { c.drawRoundRect(3, 4, 21, 20, 3, 3, p); c.drawCircle(8, 9, 1.5f, p); Path path = new Path(); path.moveTo(4, 18); path.lineTo(11, 11); path.lineTo(15, 15); path.lineTo(18, 12); path.lineTo(21, 15); c.drawPath(path, p); }
            c.restore();
        }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { p.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
