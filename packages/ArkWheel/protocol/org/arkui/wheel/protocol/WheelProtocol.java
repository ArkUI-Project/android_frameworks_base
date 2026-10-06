/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.wheel.protocol;

import android.content.ComponentName;

/** Private Messenger protocol shared only by the wheel APK and its SystemUI host. */
public final class WheelProtocol {
    public static final String PACKAGE = "org.arkui.wheel";
    public static final ComponentName SERVICE = new ComponentName(PACKAGE, PACKAGE + ".WheelService");
    public static final ComponentName SETTINGS = new ComponentName(PACKAGE, PACKAGE + ".WheelSettingsActivity");
    public static final int ATTACH = 1, ENVIRONMENT = 2, CONFIGURATION = 3,
            LAUNCH = 4, ACTION = 5, MANAGE = 6,
            RECOGNITION_VISIBILITY = 9, TRIGGER_REGIONS = 10, GESTURE_CANCEL = 11;
    public static final String ENABLED = "enabled", ALLOWED = "allowed", FOREGROUND = "foreground",
            RECENT = "recent", COMPONENT = "component", SMALL = "small";
    public static final String DISPLAY_ID = "display_id", TRIGGER_RECTS = "trigger_rects",
            DISPLAY_ROTATION = "display_rotation";
    public static final String GESTURE_DOWN_TIME = "gesture_down_time",
            GESTURE_DEVICE_ID = "gesture_device_id", GESTURE_CANCEL_TIME = "gesture_cancel_time";
    public static final int BACK = 1, HOME = 2, RECENTS = 3, NOTIFICATIONS = 4,
            QUICK_SETTINGS = 5, SCREENSHOT = 6, LOCK = 7, FLASHLIGHT = 8, RECOGNIZE = 9;
    private WheelProtocol() { }
}
