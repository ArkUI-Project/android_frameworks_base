/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.arkui;
import android.os.Bundle;
import android.content.ComponentName;

/** Narrow, current-user-only operations validated by SystemUI. @hide */
oneway interface IArkSidebarHost {
    void configurationChanged(in Bundle configuration);
    void launchApplication(in ComponentName component, boolean smallWindow);
}
