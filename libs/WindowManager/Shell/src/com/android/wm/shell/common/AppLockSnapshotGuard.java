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
package com.android.wm.shell.common;

import android.app.AppLockManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.util.ArraySet;
import android.view.SurfaceControl;

/** Erases active Shell-owned screenshots when application-lock policy or state changes. */
public final class AppLockSnapshotGuard {
    private static final ArraySet<SurfaceControl> sSnapshots = new ArraySet<>();
    private static Context sContext;
    private static Handler sMainHandler;
    private static boolean sRegistered;
    private static int sCaptures;
    private static final BroadcastReceiver sReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!AppLockManager.ACTION_APP_LOCK_STATE_CHANGED.equals(intent.getAction())) return;
            synchronized (sSnapshots) {
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    for (int i = 0; i < sSnapshots.size(); i++) {
                        SurfaceControl snapshot = sSnapshots.valueAt(i);
                        if (snapshot.isValid()) {
                            // Alpha or reparent updates from an already-running animator must
                            // never be able to restore the screenshot's previous private pixels.
                            transaction.setBuffer(snapshot, (HardwareBuffer) null).hide(snapshot);
                        }
                    }
                    transaction.apply();
                }
            }
        }
    };

    private AppLockSnapshotGuard() {}

    /** Called by Shell initialization with its main-thread handler. */
    public static void initialize(Context context, Handler mainHandler) {
        synchronized (sSnapshots) {
            if (sContext != null) return;
            Context application = context.getApplicationContext();
            sContext = application != null ? application : context;
            sMainHandler = mainHandler;
        }
    }

    /** The snapshot owner must call {@link #forget} during its normal animation cleanup. */
    public static void track(SurfaceControl snapshot) {
        if (snapshot == null || !snapshot.isValid()) return;
        synchronized (sSnapshots) {
            if (sContext == null || !sSnapshots.add(snapshot)) return;
            register();
        }
    }

    /** Listen before synchronous GPU readback, so a policy change during capture is not missed. */
    public static void beginCapture() {
        synchronized (sSnapshots) {
            if (sContext == null) return;
            sCaptures++;
            register();
        }
    }

    public static void endCapture() {
        synchronized (sSnapshots) {
            if (sContext == null) return;
            if (sCaptures > 0) sCaptures--;
            unregisterIfIdle();
        }
    }

    public static void forget(SurfaceControl snapshot) {
        synchronized (sSnapshots) {
            sSnapshots.remove(snapshot);
            unregisterIfIdle();
        }
    }

    private static void register() {
        if (sRegistered) return;
        sContext.registerReceiverForAllUsers(sReceiver,
                new IntentFilter(AppLockManager.ACTION_APP_LOCK_STATE_CHANGED),
                AppLockManager.PERMISSION, sMainHandler, Context.RECEIVER_NOT_EXPORTED);
        sRegistered = true;
    }

    private static void unregisterIfIdle() {
        if (!sRegistered || sCaptures != 0 || !sSnapshots.isEmpty()) return;
        sContext.unregisterReceiver(sReceiver);
        sRegistered = false;
    }
}
