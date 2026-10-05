/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.recognition;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Native visible content, with hidden and password strings that must never be returned. */
public final class VisibleContentFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("secure", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.WHITE);
        int padding = dp(20); column.setPadding(padding, padding, padding, padding);
        column.addView(label("Visible 原文😀 2026", 23));
        TextView ellipsis = label("Visible prefix HIDDEN_ELLIPSIS_SECRET", 18);
        ellipsis.setSingleLine(true); ellipsis.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(ellipsis, new LinearLayout.LayoutParams(dp(130), dp(48)));
        TextView password = label("PASSWORD_SECRET", 18);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        column.addView(password);
        TextView hidden = label("GONE_SECRET", 18); hidden.setVisibility(View.GONE); column.addView(hidden);
        TextView transparent = label("TRANSPARENT_SECRET", 18);
        transparent.setTextColor(Color.TRANSPARENT); column.addView(transparent);
        SpannableString styled = new SpannableString("Visible span SPAN_SECRET");
        styled.setSpan(new ForegroundColorSpan(Color.TRANSPARENT), 13, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView span = label("", 18); span.setText(styled); column.addView(span);
        ImageView transparentImage = new ImageView(this);
        Bitmap hiddenImage = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888);
        hiddenImage.eraseColor(Color.GREEN); transparentImage.setImageBitmap(hiddenImage);
        transparentImage.setImageAlpha(0);
        column.addView(transparentImage, new LinearLayout.LayoutParams(dp(80), dp(80)));
        Bitmap bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLUE);
        ImageView image = new ImageView(this); image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        column.addView(image, new LinearLayout.LayoutParams(-1, dp(200)));
        View spacer = new View(this); column.addView(spacer, new LinearLayout.LayoutParams(-1, dp(1600)));
        column.addView(label("OFFSCREEN_SECRET", 18));
        scroll.addView(column); setContentView(scroll);
        if (getIntent().getBooleanExtra("debug", false)) scroll.postDelayed(() -> logTree(scroll.getRootView(), 0), 700);
    }
    private TextView label(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.BLACK); view.setPadding(0, dp(6), 0, dp(6)); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    static void logTree(View view, int depth) {
        if (depth > 12) return;
        android.graphics.Rect bounds = new android.graphics.Rect(); view.getBoundsOnScreen(bounds, true);
        android.util.Log.i("VisibleContentFixture", depth + " " + view.getClass().getSimpleName()
                + " bounds=" + bounds + " shown=" + view.isShown() + " outline=" + view.getClipToOutline()
                + " foreground=" + view.getForeground()
                + " alpha=" + view.getAlpha() + " clip=" + view.getClipBounds());
        if (depth == 0 && view.getViewRootImpl() != null) {
            try {
                var field = view.getViewRootImpl().getClass().getDeclaredField("mWinFrame"); field.setAccessible(true);
                android.util.Log.i("VisibleContentFixture", "Window frame=" + field.get(view.getViewRootImpl()));
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        if (view instanceof android.view.ViewGroup) {
            var group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) logTree(group.getChildAt(index), depth + 1);
        }
    }
}
