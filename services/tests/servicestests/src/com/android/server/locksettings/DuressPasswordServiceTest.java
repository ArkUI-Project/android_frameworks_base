/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.server.locksettings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;

import org.junit.Before;
import org.junit.Test;

/** Configuration tests only; never submit a duress credential to the real verifier. */
public class DuressPasswordServiceTest extends BaseLockSettingsServiceTests {
    @Before
    public void setUpDuress() {
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        mService.initializeSyntheticPassword(MANAGED_PROFILE_USER_ID);
        try (LockscreenCredential owner = newPassword("owner-secret");
                LockscreenCredential none = nonePassword()) {
            assertTrue(mService.setLockCredential(owner, none, PRIMARY_USER_ID));
        }
    }

    private void configure() {
        try (LockscreenCredential owner = newPassword("owner-secret");
                LockscreenCredential pin = newPin("2468");
                LockscreenCredential password = newPassword("duress-secret")) {
            mService.setDuressCredentials(owner, pin, password);
        }
    }

    @Test
    public void unencryptedDataRejectsConfiguration() {
        mInjector.mIsDataEncrypted = false;
        assertThrows(SecurityException.class, this::configure);
        assertFalse(mService.hasDuressCredentials());
    }

    @Test
    public void unencryptedDataDisablesExistingConfigurationAndRestoresAutoConfirm() {
        configure();
        mService.setBoolean(LockPatternUtils.AUTO_PIN_CONFIRM, true, PRIMARY_USER_ID);
        mInjector.mIsDataEncrypted = false;
        assertFalse(mService.hasDuressCredentials());
        assertTrue(mService.getBoolean(LockPatternUtils.AUTO_PIN_CONFIRM, false, PRIMARY_USER_ID));
    }

    @Test
    public void requiresCurrentOwnerCredential() {
        try (LockscreenCredential wrong = newPassword("wrong-owner");
                LockscreenCredential pin = newPin("2468");
                LockscreenCredential password = newPassword("duress-secret")) {
            assertThrows(SecurityException.class,
                    () -> mService.setDuressCredentials(wrong, pin, password));
        }
        assertFalse(mService.hasDuressCredentials());
    }

    @Test
    public void rejectsOwnerCredentialAsDuressPassword() {
        try (LockscreenCredential owner = newPassword("owner-secret");
                LockscreenCredential pin = newPin("2468")) {
            assertThrows(IllegalArgumentException.class,
                    () -> mService.setDuressCredentials(owner, pin, owner));
        }
        assertFalse(mService.hasDuressCredentials());
    }

    @Test
    public void rejectsIncompletePairWithoutLosingExistingConfiguration() {
        configure();
        try (LockscreenCredential owner = newPassword("owner-secret");
                LockscreenCredential pin = newPin("2468");
                LockscreenCredential none = nonePassword()) {
            assertThrows(IllegalArgumentException.class,
                    () -> mService.setDuressCredentials(owner, pin, none));
        }
        assertTrue(mService.hasDuressCredentials());
    }

    @Test
    public void ownerCanDisableBothCredentials() {
        configure();
        try (LockscreenCredential owner = newPassword("owner-secret");
                LockscreenCredential none = nonePassword()) {
            mService.setDuressCredentials(owner, none, none);
        }
        assertFalse(mService.hasDuressCredentials());
    }

    @Test
    public void removingOwnerLockDisablesDuress() {
        configure();
        try (LockscreenCredential owner = newPassword("owner-secret");
                LockscreenCredential none = nonePassword()) {
            assertTrue(mService.setLockCredential(none, owner, PRIMARY_USER_ID));
        }
        assertFalse(mService.hasDuressCredentials());
    }

    @Test
    public void switchingOwnerToPatternDisablesDuress() {
        configure();
        try (LockscreenCredential owner = newPassword("owner-secret");
                LockscreenCredential pattern = newPattern("1235789")) {
            assertTrue(mService.setLockCredential(pattern, owner, PRIMARY_USER_ID));
        }
        assertFalse(mService.hasDuressCredentials());
    }

    @Test
    public void autoConfirmPreferenceIsSuspendedAndRestored() {
        mService.setBoolean(LockPatternUtils.AUTO_PIN_CONFIRM, true, PRIMARY_USER_ID);
        mService.setBoolean(LockPatternUtils.AUTO_PIN_CONFIRM, true, MANAGED_PROFILE_USER_ID);
        configure();
        assertFalse(mService.getBoolean(LockPatternUtils.AUTO_PIN_CONFIRM, false, PRIMARY_USER_ID));
        assertFalse(mService.getBoolean(LockPatternUtils.AUTO_PIN_CONFIRM, false,
                MANAGED_PROFILE_USER_ID));
        try (LockscreenCredential owner = newPassword("owner-secret");
                LockscreenCredential none = nonePassword()) {
            mService.setDuressCredentials(owner, none, none);
        }
        assertTrue(mService.getBoolean(LockPatternUtils.AUTO_PIN_CONFIRM, false, PRIMARY_USER_ID));
        assertTrue(mService.getBoolean(LockPatternUtils.AUTO_PIN_CONFIRM, false,
                MANAGED_PROFILE_USER_ID));
    }
}
