/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.arkui;

import android.app.ActivityTaskManager;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

/** Shared policy for ArkUI small-window entry points. @hide */
public final class SmallWindowSettings {
    public static final int MODE_FLYME = 0;
    public static final int MODE_HYPEROS = 1;

    private SmallWindowSettings() {}

    public static int getMode(Context context) {
        return getChoice(context, Settings.System.ARKUI_SMALL_WINDOW_MODE,
                MODE_FLYME, MODE_HYPEROS);
    }

    public static boolean isHyperOsMode(Context context) {
        return getMode(context) == MODE_HYPEROS;
    }

    public static boolean isSupported(Context context) {
        return PopUpViewManager.FEATURE_SUPPORTED
                && ActivityTaskManager.supportsMultiWindow(context);
    }

    public static boolean isEnabled(Context context, int userId) {
        return isSupported(context) && getBoolean(context,
                Settings.System.ARKUI_SMALL_WINDOW_ENABLED, true, userId);
    }

    public static boolean isEnabled(Context context) {
        return isEnabled(context, UserHandle.USER_CURRENT);
    }

    public static boolean isRecentsEnabled(Context context) {
        return isEnabled(context) && getBoolean(context,
                Settings.System.ARKUI_SMALL_WINDOW_RECENTS, true, UserHandle.USER_CURRENT);
    }

    public static boolean isSwipeUpEnabled(Context context) {
        return isEnabled(context) && getBoolean(context,
                Settings.System.ARKUI_SMALL_WINDOW_SWIPE_UP, false, UserHandle.USER_CURRENT);
    }

    public static boolean isNotificationDragEnabled(Context context) {
        return isEnabled(context) && getBoolean(context,
                Settings.System.ARKUI_SMALL_WINDOW_NOTIFICATION_DRAG, false,
                UserHandle.USER_CURRENT);
    }

    public static boolean isPackageAllowed(Context context, String packageName) {
        return isEnabled(context) && !TextUtils.isEmpty(packageName)
                && !containsPackage(Settings.System.getStringForUser(context.getContentResolver(),
                        Settings.System.ARKUI_SMALL_WINDOW_BLACKLIST, UserHandle.USER_CURRENT),
                        packageName);
    }

    public static boolean containsPackage(String packages, String packageName) {
        if (TextUtils.isEmpty(packages) || TextUtils.isEmpty(packageName)) {
            return false;
        }
        for (String item : packages.split(";")) {
            if (packageName.equals(item.trim())) {
                return true;
            }
        }
        return false;
    }

    public static boolean getBoolean(Context context, String key, boolean defaultValue, int userId) {
        return Settings.System.getIntForUser(context.getContentResolver(), key,
                defaultValue ? 1 : 0, userId) != 0;
    }

    public static int getChoice(Context context, String key, int defaultValue, int maximum) {
        final int value = Settings.System.getIntForUser(context.getContentResolver(), key,
                defaultValue, UserHandle.USER_CURRENT);
        return value >= 0 && value <= maximum ? value : defaultValue;
    }
}
