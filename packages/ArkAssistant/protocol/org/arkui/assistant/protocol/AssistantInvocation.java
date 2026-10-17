/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.assistant.protocol;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

/** Shared, per-user entry points for the privileged assistant shortcuts. */
public final class AssistantInvocation {
    public static final String POWER = "arkui_assistant_power_long_press";
    public static final String POWER_DOUBLE_TAP = "arkui_assistant_power_double_tap";
    public static final String DOUBLE_TAP = "arkui_assistant_nav_double_tap";
    public static final String LONG_PRESS = "arkui_assistant_nav_long_press";
    public static final String EXTRA_ACTION = "assistant_action";
    public static final String EXTRA_ENTRY = "arkui_assistant_entry";
    // The system owns the hold timer and replays its state when the overlay registers.
    public static final String EXTRA_POWER_KEY_SESSION = "arkui_assistant_power_key_session";
    public static final String EXTRA_POWER_KEY_CALLBACK = "arkui_assistant_power_key_callback";
    public static final String EXTRA_POWER_KEY_DEADLINE = "arkui_assistant_power_key_deadline";
    public static final long POWER_MENU_HOLD_DURATION_MILLIS = 5000;
    public static final int POWER_KEY_REGISTER = 1;
    public static final int POWER_KEY_CANCEL = 2;
    public static final int POWER_KEY_HELD = 3;
    public static final int POWER_KEY_RELEASED = 4;
    public static final int POWER_KEY_MENU = 5;
    public static final int NONE = 0;
    public static final int VOICE = 1;
    public static final int TEXT = 2;
    public static final int SCREEN = 3;

    private AssistantInvocation() {}

    public static int action(Context context, String entry, int userId) {
        final int fallback;
        if (POWER.equals(entry)) fallback = VOICE;
        else if (POWER_DOUBLE_TAP.equals(entry)) fallback = NONE;
        else if (DOUBLE_TAP.equals(entry)) fallback = TEXT;
        else if (LONG_PRESS.equals(entry)) fallback = SCREEN;
        else return NONE;
        int value = Settings.Secure.getIntForUser(context.getContentResolver(), entry,
                fallback, userId);
        return value >= NONE && value <= SCREEN ? value : NONE;
    }

    public static boolean isAvailable(Context context, int userId) {
        if (Settings.Secure.getIntForUser(context.getContentResolver(),
                AssistantProtocol.ENABLED, 0, userId) != 1) return false;
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationInfoAsUser(AssistantProtocol.PACKAGE, 0, userId).enabled
                    && pm.checkSignatures("android", AssistantProtocol.PACKAGE)
                    == PackageManager.SIGNATURE_MATCH;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean canInvoke(Context context, String entry, int userId) {
        return action(context, entry, userId) != NONE && isAvailable(context, userId)
                && !context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                && Settings.Secure.getIntForUser(context.getContentResolver(),
                        Settings.Secure.USER_SETUP_COMPLETE, 0, userId) == 1
                && !context.getSystemService(ActivityManager.class).isInLockTaskMode();
    }

    public static boolean invoke(Context context, String entry, int userId) {
        return invoke(context, entry, userId, null);
    }

    public static boolean invoke(Context context, String entry, int userId,
            ResultReceiver powerKeySession) {
        if (!canInvoke(context, entry, userId)) return false;
        int action = action(context, entry, userId);
        if (action == NONE) return false;
        try {
            context.startActivityAsUser(new Intent()
                    .setClassName(AssistantProtocol.PACKAGE,
                            AssistantProtocol.PACKAGE + ".AssistantShortcutActivity")
                    .putExtra(EXTRA_ACTION, action)
                    .putExtra(EXTRA_ENTRY, entry)
                    .putExtra(EXTRA_POWER_KEY_SESSION, powerKeySession)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    UserHandle.of(userId));
            return true;
        } catch (RuntimeException e) {
            Log.w("ArkAI", "Assistant shortcut unavailable", e);
            return false;
        }
    }
}
