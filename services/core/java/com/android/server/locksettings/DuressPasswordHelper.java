/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Based on GrapheneOS DuressPasswordHelper (Apache-2.0). ArkUI integration:
 * https://github.com/GrapheneOS/platform_frameworks_base/blob/17/services/core/java/com/android/server/locksettings/DuressPasswordHelper.java
 */
package com.android.server.locksettings;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;

import java.util.function.BooleanSupplier;

/** Runs only after normal authentication has failed, including rate-limited attempts. */
final class DuressPasswordHelper {
    private static final String TAG = "DuressPasswordHelper";
    private final LockSettingsStorage mStorage;
    private final SyntheticPasswordManager mSpManager;
    private final Runnable mWipe;
    private final BooleanSupplier mIsDataEncrypted;
    private Handler mHandler;

    DuressPasswordHelper(Context context, LockSettingsStorage storage,
            SyntheticPasswordManager spManager, BooleanSupplier isDataEncrypted) {
        this(storage, spManager, null, () -> DuressWipe.run(context), isDataEncrypted);
    }

    // Test seam: dispatch credential checks deterministically and never erase a test device.
    DuressPasswordHelper(LockSettingsStorage storage, SyntheticPasswordManager spManager,
            Handler handler, Runnable wipe, BooleanSupplier isDataEncrypted) {
        mStorage = storage;
        mSpManager = spManager;
        mHandler = handler;
        mWipe = wipe;
        mIsDataEncrypted = isDataEncrypted;
    }

    boolean isEnabled() {
        return mIsDataEncrypted.getAsBoolean() && readCredentials() != null;
    }

    private DuressCredentials readCredentials() {
        try {
            String encoded = mStorage.getString(DuressCredentials.STORAGE_KEY, null,
                    UserHandle.USER_SYSTEM);
            return encoded == null ? null : DuressCredentials.deserialize(encoded);
        } catch (RuntimeException e) {
            // Do not report protection as enabled when its record cannot be used.
            Slog.e(TAG, "Unable to read duress credentials");
            return null;
        }
    }

    void set(LockscreenCredential pin, LockscreenCredential password) {
        if (pin.isNone() && password.isNone()) {
            clear();
            return;
        }
        if (!mIsDataEncrypted.getAsBoolean()) {
            throw new SecurityException("Duress protection requires file-based encryption of /data");
        }
        DuressCredentials credentials = DuressCredentials.create(mSpManager, pin, password);
        mStorage.setString(DuressCredentials.STORAGE_KEY, credentials.serialize(),
                UserHandle.USER_SYSTEM);
    }

    void clear() {
        mStorage.removeKey(DuressCredentials.STORAGE_KEY, UserHandle.USER_SYSTEM);
    }

    void onVerifyCredentialResult(VerifyCredentialResponse response,
            LockscreenCredential credential) {
        // A real unlock always takes precedence, even if credentials happen to be identical.
        if (response.isMatched() || credential == null
                || (!credential.isPin() && !credential.isPassword()) || !isEnabled()) {
            return;
        }
        LockscreenCredential copy = credential.duplicate();
        if (!getHandler().post(() -> {
            boolean matched = false;
            try (copy) {
                DuressCredentials credentials = readCredentials();
                matched = credentials != null && credentials.matches(mSpManager, copy);
            } catch (RuntimeException e) {
                // A corrupt record must not crash authentication or expose hashes in logs.
                Slog.e(TAG, "Unable to check duress credentials");
            }
            if (matched && mIsDataEncrypted.getAsBoolean()) mWipe.run();
        })) {
            copy.zeroize();
        }
    }

    private synchronized Handler getHandler() {
        if (mHandler == null) {
            HandlerThread thread = new HandlerThread("DuressCredentialCheck");
            thread.start();
            mHandler = new Handler(thread.getLooper());
        }
        return mHandler;
    }
}
