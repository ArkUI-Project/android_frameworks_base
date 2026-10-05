/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.ImageView;

/** Pixel-only fixture ensures recognition cannot accidentally substitute accessibility text. */
public final class RecognitionFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("secure", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ImageView image = new ImageView(this); image.setImageBitmap(fixture(false));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setBackgroundColor(Color.WHITE); setContentView(image);
    }
    static Bitmap fixture(boolean dark) {
        Bitmap image = Bitmap.createBitmap(720, 1200, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(image); c.drawColor(dark ? 0xff121212 : Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(dark ? Color.WHITE : Color.BLACK); p.setTextSize(56);
        c.drawText("你好，世界", 70, 160, p); c.drawText("ArkUI offline OCR", 70, 270, p);
        p.setTextSize(44); c.drawText("Copy this text 12345", 70, 380, p);
        p.setShader(new LinearGradient(60, 540, 660, 1060, 0xff0e7490, 0xffa7f3d0, Shader.TileMode.CLAMP));
        c.drawRect(60, 540, 660, 1060, p); p.setShader(null);
        p.setColor(0xffffdf87); c.drawCircle(490, 675, 68, p);
        p.setColor(0xff166534);
        android.graphics.Path hill = new android.graphics.Path(); hill.moveTo(60, 1060); hill.lineTo(300, 700); hill.lineTo(660, 1060); hill.close(); c.drawPath(hill, p);
        p.setColor(0xff065f46); hill.reset(); hill.moveTo(280, 1060); hill.lineTo(540, 770); hill.lineTo(660, 1060); hill.close(); c.drawPath(hill, p);
        return image;
    }

    static Bitmap smallMixedFixture(boolean dark) {
        Bitmap image = Bitmap.createBitmap(720, 520, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image); canvas.drawColor(dark ? 0xff121212 : Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(dark ? 0xfff4f4f4 : 0xff242424); paint.setTextSize(18);
        canvas.drawText("ArkUI offline OCR", 38, 90, paint);
        paint.setTextSize(22);
        canvas.drawText("你好 ArkUI 12345", 38, 180, paint);
        paint.setTextSize(16);
        canvas.drawText("Copy this text 67890", 38, 280, paint);
        paint.setColor(dark ? 0xffdbeafe : 0xff172554); paint.setTextSize(20);
        canvas.drawText("support@example.com", 38, 380, paint);
        return image;
    }

    static Bitmap lexiconFixture() {
        Bitmap image = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK);
        paint.setTextSize(22);
        canvas.drawText("IllyNote GitHub WiFi", 48, 100, paint);
        paint.setTextSize(26);
        canvas.drawText("The quick brown fox 24680", 48, 210, paint);
        paint.setTypeface(android.graphics.Typeface.MONOSPACE); paint.setTextSize(28);
        canvas.drawText("QZXV 47291", 48, 320, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT); paint.setTextSize(22);
        canvas.drawText("Copy this text", 48, 430, paint);
        return image;
    }
}
