/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.settingslib.arkui;

/** Physical-pixel ordered dithering, shared by the renderer and its small host tests. */
public final class SmartPixelsPattern {
    public static final int SIZE = 8;
    public static final int MIN_PERCENT = 10;
    public static final int MAX_PERCENT = 75;

    private SmartPixelsPattern() { }

    public static int clamp(int percent) {
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent));
    }

    public static int[] create(int percent, int phase) {
        int[] pixels = new int[SIZE * SIZE];
        int threshold = Math.round(pixels.length * clamp(percent) / 100f);
        // Visit every translation over 64 minutes, distributing the black pixels over time.
        int shiftX = Math.floorMod(phase, SIZE);
        int shiftY = Math.floorMod(phase / SIZE, SIZE);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int sampleX = (x + shiftX) % SIZE;
                int sampleY = (y + shiftY) % SIZE;
                int rank = 0;
                // Recursive 2x2 Bayer ordering; each rank from 0 to 63 appears exactly once.
                for (int bit = 0; bit < 3; bit++) {
                    int a = (sampleX >> bit) & 1;
                    int b = (sampleY >> bit) & 1;
                    rank = rank * 4 + ((a ^ b) * 2 + b);
                }
                pixels[y * SIZE + x] = rank < threshold ? 0xff000000 : 0;
            }
        }
        return pixels;
    }
}
