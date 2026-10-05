/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.game.protocol;
import android.content.ComponentName;
public final class GameProtocol {
    public static final String PACKAGE = "org.arkui.game";
    public static final String PERMISSION = PACKAGE + ".permission.CONTROL_GAME";
    public static final ComponentName SERVICE = new ComponentName(PACKAGE, PACKAGE + ".GameService");
    public static final String SIDEBAR_STATE = PACKAGE + ".SIDEBAR_STATE", OPEN = PACKAGE + ".OPEN";
    public static final String OPEN_APPS = PACKAGE + ".OPEN_APPS";
    public static final int ATTACH = 1, ENVIRONMENT = 2, STATE = 3, NOTIFICATION = 4,
            SCREENSHOT = 5, RECORD = 6, BRIGHTNESS = 7, COMBO = 8, STOP_COMBO = 9,
            IDLE = 10, RETURN = 11, MEMORY = 12, RESULT = 13;
    private GameProtocol() { }
}
