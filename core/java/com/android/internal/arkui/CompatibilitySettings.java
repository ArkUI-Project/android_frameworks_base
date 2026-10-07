/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.arkui;

/** Protected settings shared by the native compatibility controls and system services. */
public final class CompatibilitySettings {
    private CompatibilitySettings() {}

    // Secure, per user. Only an application's FLAG_SECURE is overridden.
    public static final String IGNORE_SECURE_FLAG = "arkui_ignore_secure_flag";
    public static final String BLOCK_SCREENSHOT_DETECTION = "arkui_block_screenshot_detection";
    // Global: an APK installation and its signing identity are shared by all users.
    public static final String DISABLE_UPDATE_SIGNATURE_CHECK = "arkui_disable_update_signature_check";
    public static final String ALLOW_APP_DOWNGRADE = "arkui_allow_app_downgrade";
}
