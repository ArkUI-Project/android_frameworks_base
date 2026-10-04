/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.arkui;

import android.content.Context;
import android.graphics.Rect;
import android.provider.Settings;
import android.view.WindowManager;

/** Shared display-space destinations for gesture previews. @hide */
public final class SmallWindowBounds {
    /** App-content geometry; adding both chrome bands gives the reference's 1.59 outer ratio. */
    public static final float HYPER_OS_ASPECT_RATIO = 1.42f;
    public static final float HYPER_OS_HEADER_HEIGHT_RATIO = .105f;
    public static final float HYPER_OS_FOOTER_HEIGHT_RATIO = .065f;
    public static final float HYPER_OS_PREVIEW_BORDER_RATIO = .065f;
    public static final float HYPER_OS_CORNER_RADIUS_RATIO = .075f;
    public static final float HYPER_OS_TOP_RATIO = .11f;

    private SmallWindowBounds() { }

    public static float getHyperOsMiniWindowScale(boolean landscapeApp,
            boolean portraitDisplay, float sizeMultiplier) {
        final float base = landscapeApp == !portraitDisplay ? .70f : .90f;
        return Math.min(.95f, base * sizeMultiplier);
    }

    public static Rect getDestination(Context context, boolean floatingPreview,
            boolean landscapeApp) {
        final Rect display = context.getSystemService(WindowManager.class)
                .getMaximumWindowMetrics().getBounds();
        final boolean hyperOs = SmallWindowSettings.isHyperOsMode(context);
        final int shortSide = Math.min(display.width(), display.height());
        final int longSide = hyperOs ? (int) (shortSide * HYPER_OS_ASPECT_RATIO)
                : (int) (shortSide * 16f / 9f);
        final int width = landscapeApp ? longSide : shortSide;
        final int height = landscapeApp ? shortSide : longSide;
        final boolean portraitDisplay = display.height() >= display.width();
        final float scale;
        if (floatingPreview) {
            final boolean small = SmallWindowSettings.getChoice(context,
                    Settings.System.ARKUI_SMALL_WINDOW_PINNED_SIZE, 0, 1) == 0;
            scale = small ? .28f : landscapeApp ? .517f : .43f;
        } else {
            final int size = SmallWindowSettings.getChoice(context,
                    Settings.System.ARKUI_SMALL_WINDOW_SIZE, 1, 2);
            final float multiplier = size == 0 ? .9f : size == 2 ? 1.08f : 1f;
            final float base = landscapeApp ? (portraitDisplay ? .9f : .68f)
                    : (portraitDisplay ? .75f : .9f);
            final float orientationFactor = landscapeApp == !portraitDisplay
                    ? 1f : (float) shortSide / longSide;
            scale = (hyperOs ? getHyperOsMiniWindowScale(landscapeApp, portraitDisplay,
                    multiplier) : Math.min(.95f, base * multiplier)) * orientationFactor;
        }
        final int visibleWidth = Math.round(width * scale);
        final int visibleHeight = Math.round(height * scale);
        final int left = floatingPreview ? display.right - 44 - visibleWidth
                : display.centerX() - visibleWidth / 2;
        final int top = hyperOs ? display.top
                + Math.round(display.height() * HYPER_OS_TOP_RATIO)
                + Math.round(visibleWidth * (floatingPreview ? HYPER_OS_PREVIEW_BORDER_RATIO
                        : HYPER_OS_HEADER_HEIGHT_RATIO))
                : floatingPreview ? display.top + 88
                : display.centerY() - visibleHeight / 2;
        return new Rect(left, top, left + visibleWidth, top + visibleHeight);
    }
}
