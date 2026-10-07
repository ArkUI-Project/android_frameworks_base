/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.audio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.SparseIntArray;

import org.json.JSONObject;
import java.util.function.Consumer;

/** Package-bound, per-user media attenuation. No player can write another app's gain. */
final class AppVolumeStore {
    private static final String KEY = "arkui_app_volume_levels";
    private final Context mContext;
    private final Handler mHandler;
    private final Consumer<SparseIntArray> mApply;
    private final Runnable mReload = this::reload;

    AppVolumeStore(Context context, Handler handler, Consumer<SparseIntArray> apply) {
        mContext = context;
        mHandler = handler;
        mApply = apply;
        context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(KEY),
                false, new ContentObserver(handler) {
                    @Override public void onChange(boolean selfChange) { schedule(); }
                }, UserHandle.USER_ALL);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) { schedule(); }
        };
        final IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addDataScheme("package");
        context.registerReceiverAsUser(receiver, UserHandle.ALL, packages, null, handler,
                Context.RECEIVER_NOT_EXPORTED);
        final IntentFilter users = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        users.addAction(Intent.ACTION_USER_REMOVED);
        context.registerReceiverAsUser(receiver, UserHandle.ALL, users, null, handler,
                Context.RECEIVER_NOT_EXPORTED);
        schedule();
    }

    private void schedule() {
        mHandler.removeCallbacks(mReload);
        mHandler.postDelayed(mReload, 25);
    }

    private void reload() {
        final SparseIntArray levels = new SparseIntArray();
        for (UserHandle user : mContext.getSystemService(UserManager.class).getUserHandles(true)) {
            final String value = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(), KEY, user.getIdentifier());
            if (value == null || value.length() > 65536) continue;
            try {
                final JSONObject entries = new JSONObject(value);
                if (entries.length() > 2048) continue;
                final var names = entries.keys();
                while (names.hasNext()) {
                    final String name = names.next();
                    final int percent = entries.optInt(name, 100);
                    if (percent < 0 || percent > 100) continue;
                    try {
                        final int uid = mContext.getPackageManager().getPackageUidAsUser(
                                name, user.getIdentifier());
                        if (UserHandle.isApp(uid)) {
                            // Packages sharing an Android UID also share their audio identity.
                            levels.put(uid, Math.min(percent, levels.get(uid, 100)));
                        }
                    } catch (PackageManager.NameNotFoundException ignored) {
                        // An uninstalled package must never affect a subsequently reused UID.
                    }
                }
            } catch (org.json.JSONException ignored) { }
        }
        mApply.accept(levels);
    }
}
