/*
 * Copyright (C) 2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 * Modified for ArkUI: scope logging to small windows and keep it opt-in.
 */
package com.android.internal.arkui;

import android.util.Log;

/** @hide */
public final class DebugConstants {
    private DebugConstants() {}

    public static final boolean DEBUG_POP_UP =
            Log.isLoggable("ArkuiSmallWindow", Log.DEBUG);
    public static final boolean DEBUG_WMS_TOP_APP = DEBUG_POP_UP;
}
