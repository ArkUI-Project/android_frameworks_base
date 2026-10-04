/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.arkui;

import android.content.ComponentName;
import android.os.Bundle;

/** Private protocol, not a public framework API. @hide */
public final class SidebarContract {
    public static final String PACKAGE = "org.arkui.sidebar";
    public static final String PERMISSION = PACKAGE + ".permission.CONTROL_SIDEBAR";
    public static final ComponentName SERVICE = new ComponentName(PACKAGE,
            PACKAGE + ".SidebarService");
    public static final ComponentName SETTINGS = new ComponentName(PACKAGE,
            PACKAGE + ".SidebarSettingsActivity");
    public static final String READY = "ready";
    public static final String ENABLED = "enabled", MODE = "mode", SIDE = "side",
            POSITION = "position", HANDLE_HEIGHT = "handle_height",
            HANDLE_OPACITY = "handle_opacity", HOLD_DELAY = "hold_delay",
            HOLD_DISTANCE = "hold_distance", RECOMMEND = "recommend",
            HAPTICS = "haptics", ICON_SIZE = "icon_size",
            ANIMATION = "animation", LANDSCAPE = "landscape", LAUNCH_MODE = "launch_mode",
            PINNED = "pinned", EXCLUDED = "excluded";
    public static final int HANDLE = 0, BACK_HOLD = 1, BOTH = 2;
    private SidebarContract() {}
    public static int integer(Bundle config, String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, config.getInt(key, fallback)));
    }
}
