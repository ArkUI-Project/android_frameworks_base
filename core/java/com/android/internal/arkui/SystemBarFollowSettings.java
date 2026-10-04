/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.arkui;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

/**
 * Shared per-user policy for system bar motion.
 * @hide
 */
public final class SystemBarFollowSettings {
    private SystemBarFollowSettings() {}

    public static boolean isGestureNavigation(Context context) {
        return context.getResources().getInteger(
                com.android.internal.R.integer.config_navBarInteractionMode) == 2;
    }

    public static boolean navigationEnabled(Context context) {
        return navigationEnabled(context, UserHandle.USER_CURRENT);
    }

    public static boolean navigationEnabled(Context context, int user) {
        return isGestureNavigation(context) && Settings.System.getIntForUser(
                context.getContentResolver(), Settings.System.ARKUI_NAVIGATION_BAR_FOLLOW,
                1, user) != 0;
    }

    public static boolean statusEnabled(Context context, int user) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.ARKUI_STATUS_BAR_FOLLOW, 1, user) != 0;
    }

    public static int fadeDuration(Context context, int user) {
        return Math.max(200, Math.min(1200, Settings.System.getIntForUser(
                context.getContentResolver(), Settings.System.ARKUI_NAVIGATION_BAR_FADE,
                480, user)));
    }
}
