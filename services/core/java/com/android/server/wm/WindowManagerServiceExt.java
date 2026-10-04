/*
 * Copyright (C) 2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 * Modified for ArkUI: initialize small windows without display-resolution overrides.
 */
package com.android.server.wm;

class WindowManagerServiceExt {
    private static final WindowManagerServiceExt INSTANCE = new WindowManagerServiceExt();

    static WindowManagerServiceExt getInstance() {
        return INSTANCE;
    }

    void init(WindowManagerService wms) {
        TopActivityRecorder.getInstance().initWms(wms);
        PopUpWindowController.getInstance().init(wms.mContext, wms);
    }

    void systemReady() {
        PopUpWindowController.getInstance().systemReady();
    }
}
