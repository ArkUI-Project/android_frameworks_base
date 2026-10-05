/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.sidebar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.TextView;

final class SidebarStyle {
    static int dp(Context c, float value) {
        return Math.round(c.getResources().getDisplayMetrics().density * value);
    }
    static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
    static TextView text(Context c, CharSequence label, int sp, int color) {
        TextView t = new TextView(c);
        t.setTextAppearance(com.android.settingslib.widget.theme.R.style.TextAppearance_SettingsLib_BodyMedium);
        t.setText(label);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static int onSurface(Context c) {
        return c.getColor(com.android.internal.R.color.materialColorOnSurface);
    }
    static int surface(Context c) {
        return c.getColor(com.android.internal.R.color.materialColorSurfaceContainerHigh);
    }
    static int secondaryContainer(Context c) {
        return c.getColor(com.android.internal.R.color.materialColorSecondaryContainer);
    }
    static RippleDrawable buttonBackground(Context c, int color, float radius) {
        int ripple = (onSurface(c) & 0x00FFFFFF) | 0x1F000000;
        return new RippleDrawable(ColorStateList.valueOf(ripple), rounded(color, radius),
                rounded(Color.WHITE, radius));
    }

    /** Resolution-independent toolbar geometry; application artwork comes from PackageManager. */
    static final class Glyph extends View {
        static final int GRID = 0, BACK = 1, EDIT = 2, DOTS = 3, SEARCH = 4;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int kind;
        Glyph(Context c, int kind, String description) {
            super(c);
            this.kind = kind;
            setContentDescription(description);
            setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        void setKind(int value) { kind = value; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.translate((getWidth() - dp(getContext(), 24)) / 2f,
                    (getHeight() - dp(getContext(), 24)) / 2f);
            float unit = dp(getContext(), 24) / 24f;
            canvas.scale(unit, unit);
            paint.setColor(onSurface(getContext()));
            paint.setStyle(Paint.Style.FILL);
            if (kind == GRID) {
                for (int x = 3; x <= 13; x += 10) for (int y = 3; y <= 13; y += 10) {
                    canvas.drawRoundRect(x, y, x + 8, y + 8, 2, 2, paint);
                }
            } else if (kind == DOTS) {
                for (int y = 5; y <= 19; y += 7) canvas.drawCircle(12, y, 2, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                if (kind == BACK) {
                    Path p = new Path(); p.moveTo(15, 4); p.lineTo(7, 12); p.lineTo(15, 20);
                    canvas.drawPath(p, paint);
                } else if (kind == SEARCH) {
                    canvas.drawCircle(10, 10, 6, paint);
                    canvas.drawLine(15, 15, 21, 21, paint);
                } else {
                    canvas.drawRoundRect(4, 5, 19, 21, 3, 3, paint);
                    canvas.drawLine(11, 13, 21, 3, paint);
                }
            }
            canvas.restore();
        }
    }
}
