/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.arkui;

import android.content.res.Resources;
import android.util.ArraySet;

/** @hide */
public class PopUpViewManager {

    public static final boolean FEATURE_SUPPORTED = Resources.getSystem().getBoolean(
            com.android.internal.R.bool.config_supportsArkuiSmallWindows);

    /** TODO: Get rid of these dirty blacklist stuff. Maybe move them to local config file. */
    private static final ArraySet<String> SYSTEM_NOTIFICATION_BLACKLIST = new ArraySet<>();

    // Tap action constants for DimmerWindow
    public static final int TAP_ACTION_PIN_WINDOW = 0;
    public static final int TAP_ACTION_EXIT = 1;
    public static final int TAP_ACTION_NOTHING = 2;
    public static final int TAP_ACTION_CLOSE = 3;

    private PopUpViewManager() {}

    static {
        SYSTEM_NOTIFICATION_BLACKLIST.add("android");
        SYSTEM_NOTIFICATION_BLACKLIST.add("com.android.chrome");
        SYSTEM_NOTIFICATION_BLACKLIST.add("com.android.packageinstaller");
        SYSTEM_NOTIFICATION_BLACKLIST.add("com.google.android.gms");
        SYSTEM_NOTIFICATION_BLACKLIST.add("com.google.android.packageinstaller");
    }

    public static boolean inSystemNotificationBlacklist(String packageName) {
        return SYSTEM_NOTIFICATION_BLACKLIST.contains(packageName);
    }
}
