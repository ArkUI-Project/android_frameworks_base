/*
 * Modified by the ArkUI Project in 2026 for native small-window support.
 * Copyright (C) 2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.android;

import static android.provider.Settings.System.POP_UP_NOTIFICATION_BLACKLIST;
import static android.provider.Settings.System.POP_UP_NOTIFICATION_JUMP_LANDSCAPE;
import static android.provider.Settings.System.POP_UP_NOTIFICATION_JUMP_PORTRAIT;
import static android.provider.Settings.System.POP_UP_SINGLE_TAP_ACTION;
import static android.provider.Settings.System.POP_UP_DOUBLE_TAP_ACTION;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.R;

import com.android.internal.arkui.PopUpViewManager;

/** @hide */
public class PopUpSettingsHelper {

    private PopUpSettingsHelper() {}

    public static boolean isNotificationJumpEnabled(Context context, boolean landscape) {
        return isNotificationJumpEnabled(context, landscape, UserHandle.USER_CURRENT);
    }

    public static boolean isNotificationJumpEnabled(Context context, boolean landscape, int userId) {
        if (!com.android.internal.arkui.SmallWindowSettings.isEnabled(context, userId)) {
            return false;
        }
        final boolean defaultEnabled = landscape ?
                context.getResources().getBoolean(
                        R.bool.config_popUpView_defaultNotificationLandJump) :
                context.getResources().getBoolean(
                        R.bool.config_popUpView_defaultNotificationPortJump);
        return Settings.System.getIntForUser(context.getContentResolver(), landscape ?
                POP_UP_NOTIFICATION_JUMP_LANDSCAPE : POP_UP_NOTIFICATION_JUMP_PORTRAIT,
                defaultEnabled ? 1 : 0, userId) == 1;
    }

    public static String getNotificationJumpBlacklist(Context context) {
        return getNotificationJumpBlacklist(context, UserHandle.USER_CURRENT);
    }

    public static String getNotificationJumpBlacklist(Context context, int userId) {
        return Settings.System.getStringForUser(context.getContentResolver(),
                POP_UP_NOTIFICATION_BLACKLIST, userId);
    }

    public static int getSingleTapAction(Context context) {
        return getSingleTapAction(context, UserHandle.USER_CURRENT);
    }

    public static int getSingleTapAction(Context context, int userId) {
        final int defaultAction = context.getResources().getInteger(
                R.integer.config_popUpView_defaultSingleTapAction);
        return Settings.System.getIntForUser(context.getContentResolver(),
                POP_UP_SINGLE_TAP_ACTION, defaultAction, userId);
    }

    public static int getDoubleTapAction(Context context) {
        return getDoubleTapAction(context, UserHandle.USER_CURRENT);
    }

    public static int getDoubleTapAction(Context context, int userId) {
        final int defaultAction = context.getResources().getInteger(
                R.integer.config_popUpView_defaultDoubleTapAction);
        return Settings.System.getIntForUser(context.getContentResolver(),
                POP_UP_DOUBLE_TAP_ACTION, defaultAction, userId);
    }
}
