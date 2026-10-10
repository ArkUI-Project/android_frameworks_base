/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.assistant.protocol;

public final class AssistantProtocol {
    private AssistantProtocol() {}
    public static final String PACKAGE = "org.arkui.assistant";
    public static final String PERMISSION = PACKAGE + ".permission.CONTROL_ASSISTANT";
    public static final String ENABLED = "arkui_assistant_enabled";
    public static final String BLOCKED_APPS = "arkui_assistant_blocked_apps";
    public static final String HOST = "com.android.systemui.arkui.AssistantHostService";
    public static final int BEGIN = 1;
    public static final int CAPTURE = 2;
    public static final int ACTION = 3;
    public static final int END = 4;
    public static final int INPUT_STATE = 5;
    public static final int TAKE_OVER = 6;
    public static final int PREVIEW = 7;
    public static final int DISPLAY_STATE = 8;
    public static final int EXECUTION_STATE = 9;
    public static final String SESSION = "session";
    public static final String CONTENT = "content";
    public static final String ERROR = "error";
    public static final String ERROR_CODE = "error_code";
    public static final String APP_BLOCKED = "app_blocked";
    public static final String POLICY_UNAVAILABLE = "policy_unavailable";
}
