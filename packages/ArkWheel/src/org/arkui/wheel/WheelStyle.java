/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.TextView;

final class WheelStyle {
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
        return new RippleDrawable(ColorStateList.valueOf((onSurface(c) & 0xffffff) | 0x22000000),
                round(color, radius), round(Color.WHITE, radius));
    }
    static TextView text(Context c, CharSequence label, int sp) {
        TextView view = new TextView(c);
        view.setTextAppearance(com.android.settingslib.widget.theme.R.style.TextAppearance_SettingsLib_BodyMedium);
        view.setText(label); view.setTextSize(sp); view.setTextColor(onSurface(c)); return view;
    }
    static final class Icon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final Drawable artwork;
        private final boolean inset;
        private boolean highlighted;
        Icon(Context c, WheelCatalog.Item item, boolean themed) {
            super(c);
            Drawable source = item.icon;
            boolean monochrome = themed && source instanceof AdaptiveIconDrawable
                    && ((AdaptiveIconDrawable) source).getMonochrome() != null;
            if (monochrome) {
                Drawable foreground = ((AdaptiveIconDrawable) source).getMonochrome();
                if (foreground.getConstantState() != null) foreground = foreground.getConstantState().newDrawable(c.getResources());
                foreground = foreground.mutate(); foreground.setTint(onContainer(c));
                // Let AdaptiveIconDrawable account for the icon's extra inset, just as Launcher does.
                source = new AdaptiveIconDrawable(new ColorDrawable(container(c)), foreground);
            }
            artwork = source.getConstantState() == null ? source
                    : source.getConstantState().newDrawable(c.getResources()).mutate();
            inset = item.component == null;
            setContentDescription(item.label); setFocusable(true); setClickable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
        }
        void highlight(boolean value) {
            if (highlighted == value) return;
            highlighted = value;
            animate().scaleX(value ? 1.18f : 1).scaleY(value ? 1.18f : 1).setDuration(90).start();
            invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            paint.setColor(highlighted ? primary(getContext()) : container(getContext()));
            c.drawCircle(cx, cy, r, paint);
            float padding = highlighted ? dp(getContext(), 3) : 0;
            clip.rewind(); clip.addCircle(cx, cy, r - padding, Path.Direction.CW);
            c.save(); c.clipPath(clip);
            int border = Math.round(inset ? r * .45f : padding);
            artwork.setBounds(border, border, getWidth() - border, getHeight() - border);
            artwork.draw(c); c.restore();
        }
        @Override protected void onDetachedFromWindow() { animate().cancel(); super.onDetachedFromWindow(); }
    }
    static final class Symbol extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int kind;
        Symbol(Context c, int kind) { this.kind = kind; p.setColor(onContainer(c)); }
        @Override public void draw(Canvas c) {
            c.save(); c.translate(getBounds().left, getBounds().top);
            c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.2f);
            p.setStrokeJoin(Paint.Join.ROUND); p.setStrokeCap(Paint.Cap.ROUND);
            path.rewind();
            switch (kind) {
                case 1: path.moveTo(15, 4); path.lineTo(7, 12); path.lineTo(15, 20); c.drawPath(path, p); break;
                case 2:
                    path.moveTo(3, 11); path.lineTo(12, 3); path.lineTo(21, 11);
                    path.lineTo(21, 21); path.lineTo(15, 21); path.lineTo(15, 14);
                    path.lineTo(9, 14); path.lineTo(9, 21); path.lineTo(3, 21); path.close(); c.drawPath(path, p); break;
                case 3: c.drawRoundRect(5, 3, 19, 21, 3, 3, p); c.drawLine(1, 7, 1, 17, p); break;
                case 4:
                    path.moveTo(4, 17); path.quadTo(6, 14, 6, 9); path.cubicTo(6, 1, 18, 1, 18, 9);
                    path.quadTo(18, 14, 20, 17); path.close(); c.drawPath(path, p); c.drawArc(9, 18, 15, 22, 0, 180, false, p); break;
                case 5:
                    for (int y = 6; y <= 18; y += 6) { c.drawLine(3, y, 21, y, p); c.drawCircle(y == 12 ? 16 : 8, y, 2, p); } break;
                case 6:
                    c.drawRoundRect(4, 4, 20, 20, 3, 3, p); c.drawCircle(12, 12, 4, p);
                    c.drawLine(1, 7, 1, 1, p); c.drawLine(1, 1, 7, 1, p);
                    c.drawLine(17, 23, 23, 23, p); c.drawLine(23, 23, 23, 17, p); break;
                case 7:
                    c.drawRoundRect(5, 10, 19, 22, 3, 3, p); c.drawArc(8, 2, 16, 16, 180, 180, false, p); c.drawPoint(12, 16, p); break;
                case 8:
                    path.moveTo(6, 2); path.lineTo(18, 2); path.lineTo(18, 7); path.lineTo(15, 11);
                    path.lineTo(15, 22); path.lineTo(9, 22); path.lineTo(9, 11); path.lineTo(6, 7);
                    path.close(); c.drawPath(path, p); c.drawLine(6, 6, 18, 6, p); c.drawPoint(12, 14, p); break;
                default:
                    p.setStyle(Paint.Style.FILL);
                    for (int x = 4; x <= 20; x += 8) c.drawCircle(x, 12, 2, p);
            }
            c.restore();
        }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { p.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return 48; }
        @Override public int getIntrinsicHeight() { return 48; }
    }
}
