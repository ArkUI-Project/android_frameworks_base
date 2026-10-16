/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Based on GrapheneOS DuressWipe (Apache-2.0):
 * https://github.com/GrapheneOS/platform_frameworks_base/blob/17/services/core/java/com/android/server/locksettings/DuressWipe.java
 * Adapted to Android 16's eSIM reset API and existing secret deletion path.
 */
package com.android.server.locksettings;

import android.content.Context;
import android.os.RecoverySystem;
import android.os.storage.StorageManager;
import android.util.Slog;

import com.android.server.power.PowerManagerService;
import com.android.server.recoverysystem.RecoverySystemService;

import java.util.concurrent.atomic.AtomicBoolean;

/** Called only when a failed device-credential attempt matches a configured duress value. */
final class DuressWipe {
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    static void run(Context context) {
        // Key deletion cannot protect plaintext /data. Fail closed even for a stale configuration.
        if (!StorageManager.isFileEncrypted()) return;
        if (!STARTED.compareAndSet(false, true)) return;

        Thread euiccWipe = new Thread(() -> {
            try {
                if (!RecoverySystem.wipeEuiccData(context, context.getPackageName())) {
                    Slog.e("DuressWipe", "eSIM erase did not complete");
                }
            } catch (RuntimeException e) {
                Slog.e("DuressWipe", "eSIM erase failed");
            }
        }, "DuressEuiccWipe");
        euiccWipe.start();

        // Destroy KeyMint and Secretkeeper secrets before waiting on telephony or shutting down.
        // This is the same cryptographic erase used by Android's forced factory reset.
        try {
            RecoverySystemService.deleteSecrets();
            euiccWipe.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Clear live keys in RAM. Do not send a cancellable master-clear broadcast or
            // wait for recovery to destroy the on-disk encryption keys.
            PowerManagerService.lowLevelShutdown(null);
        }
    }
}
