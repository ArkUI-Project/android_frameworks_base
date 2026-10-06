/* SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0 */
package com.android.test.arkui.applock.target;

import android.app.AppLockManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;

/** Test-only IPC fixture; it runs commands after dropping the system harness identity. */
public class TargetProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        long identity = Binder.clearCallingIdentity();
        try {
            NotificationManager notifications = getContext().getSystemService(
                    NotificationManager.class);
            Bundle result = new Bundle();
            switch (method) {
                case "post":
                case "post-uri":
                    notifications.createNotificationChannel(new NotificationChannel("test",
                            "App lock test", NotificationManager.IMPORTANCE_DEFAULT));
                    Notification.Builder builder = new Notification.Builder(getContext(), "test")
                            .setSmallIcon(android.R.drawable.ic_lock_lock)
                            .setContentTitle(argument)
                            .setContentText("Secret body " + argument);
                    if ("post-uri".equals(method)) builder.setLargeIcon(Icon.createWithContentUri(
                            "content://" + getContext().getPackageName() + ".private/icon"));
                    notifications.notify(7, builder.build());
                    return result;
                case "cancel":
                    notifications.cancelAll();
                    return result;
                case "own":
                    ArrayList<String> own = new ArrayList<>();
                    for (StatusBarNotification notification : notifications.getActiveNotifications()) {
                        own.add(TestNotificationListener.describe(notification));
                    }
                    result.putStringArrayList("active", own);
                    return result;
                case "listener":
                    return TestNotificationListener.read();
                case "clear-events":
                    synchronized (TestNotificationListener.events) {
                        TestNotificationListener.events.clear();
                    }
                    return result;
                case "result":
                    result.putInt("code", TargetActivity.lastResultCode);
                    result.putString("answer", TargetActivity.lastResult);
                    result.putString("grant", TargetActivity.receivedGrant);
                    result.putString("launch_marker", TargetActivity.receivedLaunchMarker);
                    return result;
                case "background-launch":
                    getContext().startActivity(new Intent().setComponent(new ComponentName(
                            argument, TargetActivity.class.getName()))
                            .putExtra("launch_marker", "blocked-background")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return result;
                case "read-uri":
                    try (var input = getContext().getContentResolver().openInputStream(Uri.parse(argument))) {
                        result.putBoolean("readable", input.readAllBytes().length > 0);
                    } catch (Exception error) {
                        result.putBoolean("readable", false);
                        result.putString("error", error.toString());
                    }
                    return result;
                case "security":
                    AppLockManager manager = new AppLockManager(getContext());
                    int user = UserHandle.myUserId();
                    result.putBoolean("has_permission", getContext().checkSelfPermission(
                            AppLockManager.PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED);
                    result.putBoolean("ordinary_uid", UserHandle.getAppId(android.os.Process.myUid())
                            != android.os.Process.SYSTEM_UID);
                    result.putBoolean("begin", denied(() ->
                            manager.beginManagementAuthentication(user, new Binder())));
                    result.putBoolean("complete", denied(() ->
                            manager.completeAuthentication(new Binder())));
                    result.putBoolean("read", denied(() -> manager.getSettings(user, null)));
                    result.putBoolean("write", denied(() ->
                            manager.setSettings(user, null, new Bundle())));
                    result.putBoolean("lock", denied(() -> manager.lockAll(user, null)));
                    result.putBoolean("query", denied(() ->
                            manager.isPackageLocked(getContext().getPackageName(), user)));
                    result.putBoolean("notification_query", denied(() ->
                            manager.shouldHideNotifications(getContext().getPackageName(), user)));
                    result.putBoolean("broadcast", denied(() -> getContext().sendBroadcast(
                            new Intent(AppLockManager.ACTION_APP_LOCK_STATE_CHANGED)
                                    .putExtra(Intent.EXTRA_USER_HANDLE, user),
                            AppLockManager.PERMISSION)));
                    return result;
                default:
                    throw new IllegalArgumentException(method);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
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
