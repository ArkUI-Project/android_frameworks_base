/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package android.app;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

/** Independent, per-user privacy credentials managed by trusted Settings UI. @hide */
public final class PrivacyPasswordManager {
    public static final String ACTION_SETTINGS = "org.arkui.settings.PRIVACY_PASSWORD";
    public static final String ACTION_CONFIRM = "org.arkui.settings.CONFIRM_PRIVACY_PASSWORD";
    public static final String EXTRA_CALLBACK = "org.arkui.privacy.CALLBACK";
    public static final String KEY_CONFIGURED = "configured";
    public static final String KEY_TYPE = "type";
    public static final String KEY_MATCHED = "matched";
    public static final String KEY_RETRY_MILLIS = "retry_millis";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_GENERATION = "generation";
    public static final int TYPE_PIN = 1;
    public static final int TYPE_PASSWORD = 2;

    private final IActivityTaskManager mService = ActivityTaskManager.getService();

    public PrivacyPasswordManager(Context context) { }

    public Bundle getState(int userId) {
        try { return mService.getPrivacyPasswordState(userId); }
        catch (RemoteException e) { throw e.rethrowFromSystemServer(); }
    }

    public IBinder beginAuthentication(int userId, IBinder owner) {
        try { return mService.beginPrivacyPasswordAuthentication(userId, owner); }
        catch (RemoteException e) { throw e.rethrowFromSystemServer(); }
    }

    public Bundle verify(IBinder challenge, byte[] credential) {
        try { return mService.verifyPrivacyPassword(challenge, credential); }
        catch (RemoteException e) { throw e.rethrowFromSystemServer(); }
    }

    public IBinder setPassword(int userId, IBinder owner, IBinder authorization,
            int type, byte[] credential) {
        try { return mService.setPrivacyPassword(userId, owner, authorization, type, credential); }
        catch (RemoteException e) { throw e.rethrowFromSystemServer(); }
    }

    public void clearPassword(int userId, IBinder authorization) {
        try { mService.clearPrivacyPassword(userId, authorization); }
        catch (RemoteException e) { throw e.rethrowFromSystemServer(); }
    }

    public void endAuthentication(IBinder token) {
        try { mService.endPrivacyPasswordAuthentication(token); }
        catch (RemoteException e) { throw e.rethrowFromSystemServer(); }
    }
}
