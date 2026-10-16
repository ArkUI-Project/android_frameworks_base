/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.server.locksettings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.os.Handler;
import android.os.UserHandle;
import android.os.test.TestLooper;

import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/** All destructive calls use an injected mock. These tests never erase device data. */
public class DuressPasswordHelperTest extends BaseLockSettingsServiceTests {
    private final TestLooper mLooper = new TestLooper();
    private final AtomicBoolean mDataEncrypted = new AtomicBoolean(true);
    private Runnable mWipe;
    private DuressPasswordHelper mHelper;

    @Before
    public void setUpDuress() {
        mWipe = mock(Runnable.class);
        mHelper = new DuressPasswordHelper(mStorage, mSpManager,
                new Handler(mLooper.getLooper()), mWipe, mDataEncrypted::get);
        try (LockscreenCredential pin = newPin("2468");
                LockscreenCredential password = newPassword("duress-secret")) {
            mHelper.set(pin, password);
        }
    }

    @Test
    public void normalCredentialTakesPrecedence() {
        try (LockscreenCredential pin = newPin("2468")) {
            mHelper.onVerifyCredentialResult(VerifyCredentialResponse.OK, pin);
        }
        mLooper.dispatchAll();
        verify(mWipe, never()).run();
    }

    @Test
    public void incorrectCredentialDoesNotWipe() {
        try (LockscreenCredential pin = newPin("1234")) {
            mHelper.onVerifyCredentialResult(VerifyCredentialResponse.OTHER_ERROR, pin);
        }
        mLooper.dispatchAll();
        verify(mWipe, never()).run();
    }

    @Test
    public void duressPinSurvivesOriginalCredentialZeroization() {
        try (LockscreenCredential pin = newPin("2468")) {
            mHelper.onVerifyCredentialResult(VerifyCredentialResponse.OTHER_ERROR, pin);
        }
        mLooper.dispatchAll();
        verify(mWipe).run();
    }

    @Test
    public void duressPasswordWorksAfterHardwareTimeout() {
        try (LockscreenCredential password = newPassword("duress-secret")) {
            mHelper.onVerifyCredentialResult(VerifyCredentialResponse.fromTimeout(30000), password);
        }
        mLooper.dispatchAll();
        verify(mWipe).run();
    }

    @Test
    public void pinDoesNotMatchPasswordInput() {
        try (LockscreenCredential password = newPassword("2468")) {
            mHelper.onVerifyCredentialResult(VerifyCredentialResponse.OTHER_ERROR, password);
        }
        mLooper.dispatchAll();
        verify(mWipe, never()).run();
    }

    @Test
    public void disabledCredentialsDoNotWipe() {
        mHelper.clear();
        assertFalse(mHelper.isEnabled());
        try (LockscreenCredential pin = newPin("2468")) {
            mHelper.onVerifyCredentialResult(VerifyCredentialResponse.OTHER_ERROR, pin);
        }
        mLooper.dispatchAll();
        verify(mWipe, never()).run();
    }

    @Test
    public void corruptRecordDoesNotWipeOrCrashVerification() {
        mStorage.setString(DuressCredentials.STORAGE_KEY, "corrupt", UserHandle.USER_SYSTEM);
        assertFalse(mHelper.isEnabled());
        try (LockscreenCredential pin = newPin("2468")) {
            mHelper.onVerifyCredentialResult(VerifyCredentialResponse.OTHER_ERROR, pin);
        }
        mLooper.dispatchAll();
        verify(mWipe, never()).run();
    }

    @Test
    public void unencryptedDataDisablesExistingCredentials() {
        mDataEncrypted.set(false);
        assertFalse(mHelper.isEnabled());
        try (LockscreenCredential pin = newPin("2468")) {
            mHelper.onVerifyCredentialResult(VerifyCredentialResponse.OTHER_ERROR, pin);
        }
        mLooper.dispatchAll();
        verify(mWipe, never()).run();
    }

    @Test
    public void unencryptedDataRejectsConfiguration() {
        mDataEncrypted.set(false);
        try (LockscreenCredential pin = newPin("2468");
                LockscreenCredential password = newPassword("duress-secret")) {
            assertThrows(SecurityException.class, () -> mHelper.set(pin, password));
        }
    }

    @Test
    public void encryptionIsCheckedAgainBeforeWiping() {
        try (LockscreenCredential pin = newPin("2468")) {
            mHelper.onVerifyCredentialResult(VerifyCredentialResponse.OTHER_ERROR, pin);
        }
        mDataEncrypted.set(false);
        mLooper.dispatchAll();
        verify(mWipe, never()).run();
    }

    @Test
    public void recordRoundTripAndLengthValidation() {
        String encoded = mStorage.getString(DuressCredentials.STORAGE_KEY, null,
                UserHandle.USER_SYSTEM);
        DuressCredentials restored = DuressCredentials.deserialize(encoded);
        assertEquals(encoded, restored.serialize());
        try (LockscreenCredential pin = newPin("2468");
                LockscreenCredential password = newPassword("duress-secret")) {
            assertTrue(restored.matches(mSpManager, pin));
            assertTrue(restored.matches(mSpManager, password));
        }
        assertThrows(IllegalArgumentException.class,
                () -> DuressCredentials.deserialize(encoded.substring(2)));
        assertThrows(IllegalArgumentException.class,
                () -> DuressCredentials.deserialize("ff" + encoded.substring(2)));
        assertThrows(IllegalArgumentException.class,
                () -> DuressCredentials.deserialize(encoded.substring(0, 2) + "ff"
                        + encoded.substring(4)));
    }
}
