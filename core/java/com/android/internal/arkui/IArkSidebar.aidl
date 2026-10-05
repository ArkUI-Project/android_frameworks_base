/* Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.arkui;
import com.android.internal.arkui.IArkSidebarHost;

/** Private, signature-protected bridge to the independent sidebar application. @hide */
interface IArkSidebar {
    oneway void attachHost(IArkSidebarHost host);
    oneway void updateEnvironment(boolean available, String foreground, in String[] recent);
    oneway void openFromGesture(boolean left, float y);
    oneway void recognitionCaptureFinished();
}
