/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.settingslib.arkui;

import android.content.ContentResolver;
import android.provider.Settings;

/** Per-user controls shared by Settings and the SystemUI display overlay. */
public final class SmartPixelsSettings {
    public static final String ENABLED = "arkui_smart_pixels_enabled";
    public static final String PERCENT = "arkui_smart_pixels_percent";
    public static final String POWER_SAVE_ONLY = "arkui_smart_pixels_power_save_only";
    public static final int DEFAULT_PERCENT = 25;
    public static final String[] KEYS = {ENABLED, PERCENT, POWER_SAVE_ONLY};

    private SmartPixelsSettings() { }

    public static boolean enabled(ContentResolver resolver, int user) {
        return Settings.Secure.getIntForUser(resolver, ENABLED, 0, user) == 1;
    }

    public static boolean powerSaveOnly(ContentResolver resolver, int user) {
        return Settings.Secure.getIntForUser(resolver, POWER_SAVE_ONLY, 0, user) == 1;
    }

    public static int percent(ContentResolver resolver, int user) {
        return SmartPixelsPattern.clamp(Settings.Secure.getIntForUser(resolver, PERCENT,
                DEFAULT_PERCENT, user));
    }
}
