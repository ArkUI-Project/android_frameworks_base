/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.settingslib.arkui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SmartPixelsPatternTest {
    @Test public void percentages_coverExpectedPixelsWithOnlyBlackAndTransparent() {
        for (int percent = 10; percent <= 75; percent++) {
            int black = 0;
            for (int color : SmartPixelsPattern.create(percent, 0)) {
                assertTrue(color == 0 || color == 0xff000000);
                if (color == 0xff000000) black++;
            }
            assertEquals(Math.round(64 * percent / 100f), black);
        }
    }

    @Test public void shifts_distributeWearOverEveryPixel() {
        int[] blackFrames = new int[64];
        for (int phase = 0; phase < 64; phase++) {
            int[] pattern = SmartPixelsPattern.create(25, phase);
            for (int i = 0; i < pattern.length; i++) {
                if (pattern[i] == 0xff000000) blackFrames[i]++;
            }
        }
        for (int frames : blackFrames) assertEquals(16, frames);
    }

    @Test public void invalidSettings_keepScreenReadable() {
        assertEquals(10, SmartPixelsPattern.clamp(-100));
        assertEquals(75, SmartPixelsPattern.clamp(100));
        assertEquals(25, SmartPixelsPattern.clamp(25));
    }
}
