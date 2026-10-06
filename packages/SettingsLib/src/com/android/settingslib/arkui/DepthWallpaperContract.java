/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.settingslib.arkui;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;

/** Shared, per-user contract for the Settings processor and the native keyguard renderer. */
public final class DepthWallpaperContract {
    public static final String PERMISSION = "com.android.settings.permission.DEPTH_WALLPAPER";
    public static final Uri URI = Uri.parse("content://com.android.settings.arkui.depthwallpaper");
    public static final Uri MASK_URI = URI.buildUpon().appendPath("mask").build();
    public static final String REVISION = "arkui_depth_wallpaper_revision";
    public record Option(String key, int defaultValue, int min, int max) {
        public int read(ContentResolver resolver, int user) {
            return Math.max(min, Math.min(max, Settings.Secure.getIntForUser(
                    resolver, key, defaultValue, user)));
        }
        public boolean write(ContentResolver resolver, int user, int value) {
            return Settings.Secure.putIntForUser(resolver, key,
                    Math.max(min, Math.min(max, value)), user);
        }
    }
    public static final Option ENABLED = option("enabled", 0, 0, 1);
    public static final Option AUTO_REFRESH = option("auto_refresh", 1, 0, 1);
    public static final Option SUBJECT = option("subject", 0, 0, 1);
    public static final Option CONFIDENCE = option("confidence", 55, 30, 90);
    public static final Option OPACITY = option("opacity", 100, 20, 100);
    public static final Option FEATHER = option("feather", 2, 0, 12);
    public static final Option SCALE = option("scale", 100, 90, 120);
    public static final Option OFFSET_X = option("offset_x", 0, -10, 10);
    public static final Option OFFSET_Y = option("offset_y", 0, -10, 10);
    public static final Option COVERAGE = option("coverage", 55, 15, 90);
    public static final Option LARGE_ONLY = option("large_only", 0, 0, 1);
    public static final Option ANIMATE = option("animate", 1, 0, 1);
    public static final Option DURATION = option("duration", 450, 150, 900);
    public static final Option AOD = option("aod", 0, 0, 1);
    public static final Option AOD_OPACITY = option("aod_opacity", 15, 5, 40);
    public static final Option BATTERY_SAVER = option("battery_saver", 1, 0, 1);
    public static final Option[] OPTIONS = {ENABLED, AUTO_REFRESH, SUBJECT, CONFIDENCE, OPACITY,
            FEATHER, SCALE, OFFSET_X, OFFSET_Y, COVERAGE, LARGE_ONLY, ANIMATE, DURATION,
            AOD, AOD_OPACITY, BATTERY_SAVER};

    private DepthWallpaperContract() { }
    private static Option option(String name, int value, int min, int max) {
        return new Option("arkui_depth_wallpaper_" + name, value, min, max);
    }

    /** Run once on a worker, never during draw. Transparent edges remain transparent. */
    public static Bitmap feather(Bitmap bitmap, int radius) {
        if (radius <= 0) return bitmap;
        radius = Math.min(radius, 32);
        final int w = bitmap.getWidth(), h = bitmap.getHeight();
        final int[] pixels = new int[w * h];
        final int[] horizontal = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        final int diameter = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int sum = 0;
            for (int x = 0; x <= radius && x < w; x++) sum += Color.alpha(pixels[y * w + x]);
            for (int x = 0; x < w; x++) {
                horizontal[y * w + x] = sum / diameter;
                if (x - radius >= 0) sum -= Color.alpha(pixels[y * w + x - radius]);
                if (x + radius + 1 < w) sum += Color.alpha(pixels[y * w + x + radius + 1]);
            }
        }
        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int y = 0; y <= radius && y < h; y++) sum += horizontal[y * w + x];
            for (int y = 0; y < h; y++) {
                pixels[y * w + x] = ((sum / diameter) << 24) | 0x00ffffff;
                if (y - radius >= 0) sum -= horizontal[(y - radius) * w + x];
                if (y + radius + 1 < h) sum += horizontal[(y + radius + 1) * w + x];
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }
}
