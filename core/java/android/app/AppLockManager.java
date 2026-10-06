/*
 * Copyright (C) 2026 The ArkUI Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.app;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * The trusted system application lock interface.
 * @hide
 */
public final class AppLockManager {
    public static final String PERMISSION = "android.permission.MANAGE_APP_LOCK";
    public static final String ACTION_APP_LOCK_STATE_CHANGED =
            "android.app.action.APP_LOCK_STATE_CHANGED";
    public static final String EXTRA_CHALLENGE = "android.app.extra.APP_LOCK_CHALLENGE";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_PACKAGES = "packages";
    public static final String KEY_HIDE_NOTIFICATIONS = "hide_notifications";
    public static final String KEY_LOCK_ON_SCREEN_OFF = "lock_on_screen_off";
    public static final String KEY_LOCK_MODE = "lock_mode";
    public static final String KEY_TIMEOUT_MINUTES = "timeout_minutes";
    public static final String KEY_PACKAGE_NAME = "package_name";
    public static final String KEY_USER_ID = "user_id";
    public static final String KEY_MANAGEMENT = "management";
    public static final String KEY_AUTH_METHOD = "auth_method";
    public static final int AUTH_SCREEN_LOCK = 0;
    public static final int AUTH_PRIVACY_PASSWORD = 1;
    public static final int MODE_IMMEDIATE = 0;
    public static final int MODE_TIMEOUT = 1;
    public static final int MODE_SCREEN_OFF = 2;

    private final IActivityTaskManager mService;

    public AppLockManager(Context context) {
        mService = ActivityTaskManager.getService();
    }

    public IBinder beginManagementAuthentication(int userId, IBinder owner) {
        try {
            return mService.beginAppLockManagementAuthentication(userId, owner);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public Bundle getAuthenticationInfo(IBinder challenge) {
        try {
            return mService.getAppLockAuthenticationInfo(challenge);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public IBinder completeAuthentication(IBinder challenge) {
        try {
            return mService.completeAppLockAuthentication(challenge);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public Bundle verifyPrivacyPassword(IBinder challenge, byte[] credential) {
        try {
            return mService.verifyAppLockPrivacyPassword(challenge, credential);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void cancelAuthentication(IBinder challenge) {
        try {
            mService.cancelAppLockAuthentication(challenge);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void endManagementSession(IBinder token) {
        try {
            mService.endAppLockManagementSession(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public Bundle getSettings(int userId, IBinder token) {
        try {
            return mService.getAppLockSettings(userId, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void setSettings(int userId, IBinder token, Bundle settings) {
        try {
            mService.setAppLockSettings(userId, token, settings);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void lockAll(int userId, IBinder token) {
        try {
            mService.lockAllAppLockPackages(userId, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public boolean isPackageLocked(String packageName, int userId) {
        try {
            return mService.isAppLockPackageLocked(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public boolean shouldHideNotifications(String packageName, int userId) {
        try {
            return mService.shouldHideAppLockNotifications(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
