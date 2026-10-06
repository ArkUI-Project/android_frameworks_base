/* SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0 */
package com.android.test.arkui.applock.systemui;

import android.app.AppLockManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Test-only shared-UID fixture. Framework calls originate from this process's own Looper. */
public class SystemUiQueryProvider extends ContentProvider {
    private Handler mOwnIdentityHandler;

    @Override
    public boolean onCreate() {
        mOwnIdentityHandler = new Handler(Looper.getMainLooper());
        return true;
    }

    @Override
    public Bundle call(String method, String packageName, Bundle extras) {
        // A nested call on the incoming Binder thread could inherit the system harness's
        // identity. Run on our main Looper instead; never impersonate UID 1000 or clear it.
        FutureTask<Bundle> operation = new FutureTask<>(() ->
                runWithOwnIdentity(method, packageName, extras));
        if (mOwnIdentityHandler.getLooper().isCurrentThread()) {
            operation.run();
        } else {
            mOwnIdentityHandler.post(operation);
        }
        try {
            return operation.get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new IllegalStateException("SystemUI UID fixture operation failed", error);
        }
    }

    private Bundle runWithOwnIdentity(String method, String packageName, Bundle extras)
            throws PackageManager.NameNotFoundException {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new IllegalStateException("Framework queries must use the fixture's own UID");
        }
        AppLockManager manager = new AppLockManager(getContext());
        int user = UserHandle.myUserId();
        Bundle result = new Bundle();
        result.putInt("uid", Process.myUid());
        result.putInt("binder_uid", Binder.getCallingUid());
        result.putInt("systemui_uid", getContext().getPackageManager()
                .getApplicationInfo("com.android.systemui", 0).uid);
        result.putBoolean("has_permission", getContext().checkSelfPermission(
                AppLockManager.PERMISSION) == PackageManager.PERMISSION_GRANTED);
        switch (method) {
            case "query":
                result.putBoolean("locked", manager.isPackageLocked(packageName, user));
                result.putBoolean("hide_notifications",
                        manager.shouldHideNotifications(packageName, user));
                return result;
            case "management-security":
                IBinder challenge = extras.getBinder("challenge");
                IBinder token = extras.getBinder("token");
                Bundle settings = extras.getBundle("settings");
                IBinder[] createdChallenge = new IBinder[1];
                IBinder[] redeemedToken = new IBinder[1];
                result.putBoolean("begin", denied(() -> createdChallenge[0] =
                        manager.beginManagementAuthentication(user, new Binder())));
                result.putBoolean("info", denied(() ->
                        manager.getAuthenticationInfo(challenge)));
                result.putBoolean("complete", denied(() -> redeemedToken[0] =
                        manager.completeAuthentication(challenge)));
                result.putBoolean("cancel", denied(() -> manager.cancelAuthentication(challenge)));
                result.putBoolean("read", denied(() -> manager.getSettings(user, token)));
                result.putBoolean("write", denied(() -> manager.setSettings(user, token, settings)));
                result.putBoolean("lock", denied(() -> manager.lockAll(user, token)));
                result.putBoolean("end", denied(() -> manager.endManagementSession(token)));
                // Let the harness clean up even if a regression accidentally admits management.
                result.putBinder("created_challenge", createdChallenge[0]);
                result.putBinder("redeemed_token", redeemedToken[0]);
                return result;
            default:
                throw new IllegalArgumentException(method);
        }
    }

    private static boolean denied(Runnable action) {
        try {
            action.run();
            return false;
        } catch (SecurityException expected) {
            return true;
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
        throw new UnsupportedOperationException();
    }
}
