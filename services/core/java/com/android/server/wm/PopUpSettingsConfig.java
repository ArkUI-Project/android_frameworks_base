/*
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 * Modified for ArkUI: per-user policy, validated choices and live setting updates.
 */
package com.android.server.wm;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.arkui.PopUpViewManager;
import com.android.internal.arkui.SmallWindowSettings;
import com.android.internal.util.android.PopUpSettingsHelper;

class PopUpSettingsConfig {
    private static final PopUpSettingsConfig INSTANCE = new PopUpSettingsConfig();
    private Context mContext;
    private Handler mHandler;

    // Read by window-management and gesture threads; publish immutable snapshots.
    private volatile boolean mEnabled;
    private volatile int mMode = SmallWindowSettings.MODE_FLYME;
    private volatile boolean mEdgeDockEnabled = true;
    private volatile boolean mEdgeDockForeground = true;
    private volatile boolean mHapticsEnabled = true;
    private volatile int mSize = 1;
    private volatile boolean mPinnedSmall = true;
    private volatile int mSingleTapAction = PopUpViewManager.TAP_ACTION_PIN_WINDOW;
    private volatile int mDoubleTapAction = PopUpViewManager.TAP_ACTION_EXIT;
    private volatile String mNotificationExceptions = "";
    private volatile String mAppExceptions = "";

    static PopUpSettingsConfig getInstance() {
        return INSTANCE;
    }

    void init(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        final ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                updateAll();
            }
        };
        for (String key : new String[] {
                Settings.System.ARKUI_SMALL_WINDOW_ENABLED,
                Settings.System.ARKUI_SMALL_WINDOW_MODE,
                Settings.System.ARKUI_SMALL_WINDOW_BLACKLIST,
                Settings.System.ARKUI_SMALL_WINDOW_EDGE_DOCK,
                Settings.System.ARKUI_SMALL_WINDOW_EDGE_DOCK_FOREGROUND,
                Settings.System.ARKUI_SMALL_WINDOW_HAPTICS,
                Settings.System.ARKUI_SMALL_WINDOW_SIZE,
                Settings.System.ARKUI_SMALL_WINDOW_PINNED_SIZE,
                Settings.System.POP_UP_NOTIFICATION_BLACKLIST,
                Settings.System.POP_UP_SINGLE_TAP_ACTION,
                Settings.System.POP_UP_DOUBLE_TAP_ACTION,
        }) {
            context.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL);
        }
        readSettings();
    }

    boolean isEnabled() {
        return mEnabled;
    }

    boolean isHyperOsMode() {
        return mMode == SmallWindowSettings.MODE_HYPEROS;
    }

    boolean isPackageAllowed(String packageName) {
        return mEnabled && !SmallWindowSettings.containsPackage(mAppExceptions, packageName);
    }

    boolean inNotificationBlacklist(String packageName) {
        return !isPackageAllowed(packageName)
                || PopUpViewManager.inSystemNotificationBlacklist(packageName)
                || SmallWindowSettings.containsPackage(mNotificationExceptions, packageName);
    }

    boolean isEdgeDockEnabled() {
        return mEdgeDockEnabled;
    }

    boolean isEdgeDockForeground() {
        return mEdgeDockForeground;
    }

    boolean isHapticsEnabled() {
        return mHapticsEnabled;
    }

    float getSizeMultiplier() {
        return mSize == 0 ? 0.9f : mSize == 2 ? 1.08f : 1.0f;
    }

    boolean isPinnedSmallByDefault() {
        return mPinnedSmall;
    }

    int getSingleTapAction() {
        return mSingleTapAction;
    }

    int getDoubleTapAction() {
        return mDoubleTapAction;
    }

    void updateAll() {
        if (mHandler != null) {
            mHandler.post(() -> {
                readSettings();
                PopUpWindowController.getInstance().onSettingsChanged();
            });
        }
    }

    private void readSettings() {
        mEnabled = SmallWindowSettings.isEnabled(mContext);
        mMode = SmallWindowSettings.getMode(mContext);
        mEdgeDockEnabled = SmallWindowSettings.getBoolean(mContext,
                Settings.System.ARKUI_SMALL_WINDOW_EDGE_DOCK, true, UserHandle.USER_CURRENT);
        mEdgeDockForeground = SmallWindowSettings.getBoolean(mContext,
                Settings.System.ARKUI_SMALL_WINDOW_EDGE_DOCK_FOREGROUND,
                true, UserHandle.USER_CURRENT);
        mHapticsEnabled = SmallWindowSettings.getBoolean(mContext,
                Settings.System.ARKUI_SMALL_WINDOW_HAPTICS, true, UserHandle.USER_CURRENT);
        mSize = SmallWindowSettings.getChoice(mContext,
                Settings.System.ARKUI_SMALL_WINDOW_SIZE, 1, 2);
        mPinnedSmall = SmallWindowSettings.getChoice(mContext,
                Settings.System.ARKUI_SMALL_WINDOW_PINNED_SIZE, 0, 1) == 0;
        mSingleTapAction = SmallWindowSettings.getChoice(mContext,
                Settings.System.POP_UP_SINGLE_TAP_ACTION,
                PopUpViewManager.TAP_ACTION_PIN_WINDOW, PopUpViewManager.TAP_ACTION_CLOSE);
        mDoubleTapAction = SmallWindowSettings.getChoice(mContext,
                Settings.System.POP_UP_DOUBLE_TAP_ACTION, PopUpViewManager.TAP_ACTION_EXIT,
                PopUpViewManager.TAP_ACTION_CLOSE);
        mNotificationExceptions = PopUpSettingsHelper.getNotificationJumpBlacklist(mContext);
        mAppExceptions = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.ARKUI_SMALL_WINDOW_BLACKLIST, UserHandle.USER_CURRENT);
    }
}
