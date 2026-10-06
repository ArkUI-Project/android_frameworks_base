/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.test.arkui.applock;

import static org.junit.Assert.*;

import android.app.AppLockManager;
import android.app.PrivacyPasswordManager;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/** Native service tests. Refuse to replace an existing user credential; restore app policy. */
@RunWith(AndroidJUnit4.class)
public final class PrivacyPasswordDeviceTest {
    private static final int USER = UserHandle.myUserId();
    private static final String PIN = "274839";
    private static final String TARGET = "com.android.test.arkui.applock.target";
    private Context mContext;
    private PrivacyPasswordManager mPrivacy;
    private AppLockManager mApps;
    private final IBinder mOwner = new Binder();
    private IBinder mPrivacySession;
    private IBinder mAppSession;
    private Bundle mOriginal;
    private String mCurrentPassword = PIN;
    private boolean mCreated;
    private boolean mChangedPolicy;

    @Before public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mPrivacy = new PrivacyPasswordManager(mContext);
        mApps = new AppLockManager(mContext);
        assertFalse("Tests must not replace a user's existing privacy password",
                mPrivacy.getState(USER).getBoolean(PrivacyPasswordManager.KEY_CONFIGURED));
        mPrivacySession = set(null, PrivacyPasswordManager.TYPE_PIN, PIN);
        mCreated = true;
        mAppSession = appSession();
        mOriginal = mApps.getSettings(USER, mAppSession);
    }

    @After public void tearDown() {
        if (!mCreated) return;
        if (mChangedPolicy && mOriginal != null) {
            if (!mPrivacy.getState(USER).getBoolean(PrivacyPasswordManager.KEY_CONFIGURED)) {
                mCurrentPassword = PIN;
                mPrivacySession = set(null, PrivacyPasswordManager.TYPE_PIN, PIN);
            }
            try { mApps.getSettings(USER, mAppSession); }
            catch (RuntimeException error) { mAppSession = appSession(); }
            mApps.setSettings(USER, mAppSession, mOriginal);
        }
        if (mPrivacy.getState(USER).getBoolean(PrivacyPasswordManager.KEY_CONFIGURED)) {
            try { mPrivacy.clearPassword(USER, mPrivacySession); }
            catch (SecurityException error) {
                IBinder challenge = mPrivacy.beginAuthentication(USER, mOwner);
                Bundle verified = verify(challenge, mCurrentPassword);
                assertTrue(verified.getBoolean(PrivacyPasswordManager.KEY_MATCHED));
                mPrivacy.clearPassword(USER, verified.getBinder(PrivacyPasswordManager.KEY_TOKEN));
            }
        }
        if (mAppSession != null) mApps.endManagementSession(mAppSession);
        assertFalse(mPrivacy.getState(USER).getBoolean(PrivacyPasswordManager.KEY_CONFIGURED));
    }

    @Test public void correctPrivacyPinIsRequired() {
        IBinder challenge = mPrivacy.beginAuthentication(USER, mOwner);
        Bundle wrong = verify(challenge, "123456");
        assertFalse(wrong.getBoolean(PrivacyPasswordManager.KEY_MATCHED));
        assertNull(wrong.getBinder(PrivacyPasswordManager.KEY_TOKEN));
        Bundle correct = verify(challenge, PIN);
        assertTrue(correct.getBoolean(PrivacyPasswordManager.KEY_MATCHED));
        assertNotNull(correct.getBinder(PrivacyPasswordManager.KEY_TOKEN));
    }

    @Test public void mutationRequiresVerifiedCapability() {
        IBinder challenge = mPrivacy.beginAuthentication(USER, mOwner);
        denied(() -> set(null, PrivacyPasswordManager.TYPE_PIN, "395728"));
        denied(() -> set(challenge, PrivacyPasswordManager.TYPE_PIN, "395728"));
        denied(() -> mPrivacy.clearPassword(USER, challenge));
        denied(() -> mPrivacy.clearPassword(USER, new Binder()));
        assertTrue(verify(challenge, PIN).getBoolean(PrivacyPasswordManager.KEY_MATCHED));
    }

    @Test public void endedChallengeCannotBeUsed() {
        IBinder challenge = mPrivacy.beginAuthentication(USER, mOwner);
        mPrivacy.endAuthentication(challenge);
        denied(() -> verify(challenge, PIN));
    }

    @Test public void authenticationCannotBeReplayed() {
        IBinder challenge = mPrivacy.beginAuthentication(USER, mOwner);
        assertTrue(verify(challenge, PIN).getBoolean(PrivacyPasswordManager.KEY_MATCHED));
        denied(() -> verify(challenge, PIN));
    }

    @Test public void changingPasswordRevokesOldSessionsAndOldPassword() {
        IBinder old = mPrivacySession;
        mPrivacySession = set(old, PrivacyPasswordManager.TYPE_PIN, "395728");
        mCurrentPassword = "395728";
        denied(() -> mPrivacy.clearPassword(USER, old));
        IBinder challenge = mPrivacy.beginAuthentication(USER, mOwner);
        assertFalse(verify(challenge, PIN).getBoolean(PrivacyPasswordManager.KEY_MATCHED));
        assertTrue(verify(challenge, mCurrentPassword).getBoolean(PrivacyPasswordManager.KEY_MATCHED));
    }

    @Test public void customUnicodePasswordUsesIndependentType() {
        mCurrentPassword = "ArkUI🔒Privacy";
        mPrivacySession = set(mPrivacySession, PrivacyPasswordManager.TYPE_PASSWORD, mCurrentPassword);
        assertEquals(PrivacyPasswordManager.TYPE_PASSWORD,
                mPrivacy.getState(USER).getInt(PrivacyPasswordManager.KEY_TYPE));
        IBinder challenge = mPrivacy.beginAuthentication(USER, mOwner);
        assertFalse(verify(challenge, PIN).getBoolean(PrivacyPasswordManager.KEY_MATCHED));
        assertTrue(verify(challenge, mCurrentPassword).getBoolean(PrivacyPasswordManager.KEY_MATCHED));
    }

    @Test public void invalidPasswordFormatsCannotReplaceCredential() {
        invalid(() -> set(mPrivacySession, PrivacyPasswordManager.TYPE_PIN, "12345"));
        invalid(() -> set(mPrivacySession, PrivacyPasswordManager.TYPE_PIN, "abc123"));
        invalid(() -> set(mPrivacySession, PrivacyPasswordManager.TYPE_PASSWORD, "short"));
        invalid(() -> set(mPrivacySession, 99, PIN));
        assertTrue(verify(mPrivacy.beginAuthentication(USER, mOwner), PIN)
                .getBoolean(PrivacyPasswordManager.KEY_MATCHED));
    }

    @Test public void newChallengesDoNotResetSharedAttemptThrottle() {
        IBinder first = mPrivacy.beginAuthentication(USER, mOwner);
        Bundle failure = null;
        for (int i = 0; i < 5; i++) failure = verify(first, "123456");
        assertFalse(failure.getBoolean(PrivacyPasswordManager.KEY_MATCHED));
        assertTrue(failure.getLong(PrivacyPasswordManager.KEY_RETRY_MILLIS) > 20_000);
        IBinder second = mPrivacy.beginAuthentication(USER, new Binder());
        Bundle blocked = verify(second, PIN);
        assertFalse(blocked.getBoolean(PrivacyPasswordManager.KEY_MATCHED));
        assertTrue(blocked.getLong(PrivacyPasswordManager.KEY_RETRY_MILLIS) > 20_000);
        assertTrue(mPrivacy.getState(USER).getLong(PrivacyPasswordManager.KEY_RETRY_MILLIS) > 20_000);
    }

    @Test public void appLockCannotRedeemPrivacyChallengeAsScreenLock() {
        usePrivacyMethod(false);
        IBinder challenge = mApps.beginManagementAuthentication(USER, mOwner);
        assertEquals(AppLockManager.AUTH_PRIVACY_PASSWORD,
                mApps.getAuthenticationInfo(challenge).getInt(AppLockManager.KEY_AUTH_METHOD));
        denied(() -> mApps.completeAuthentication(challenge));
        Bundle wrong = verifyApp(challenge, "123456");
        assertFalse(wrong.getBoolean(PrivacyPasswordManager.KEY_MATCHED));
        denied(() -> mApps.completeAuthentication(challenge));
        assertTrue(verifyApp(challenge, PIN).getBoolean(PrivacyPasswordManager.KEY_MATCHED));
        mAppSession = mApps.completeAuthentication(challenge);
        assertNotNull(mAppSession);
        denied(() -> mApps.completeAuthentication(challenge));
    }

    @Test public void changingMethodRevokesPreviousManagementCapability() {
        IBinder old = mAppSession;
        Bundle policy = new Bundle(mOriginal);
        policy.putBoolean(AppLockManager.KEY_ENABLED, false);
        policy.putInt(AppLockManager.KEY_AUTH_METHOD, AppLockManager.AUTH_PRIVACY_PASSWORD);
        mChangedPolicy = true;
        mApps.setSettings(USER, old, policy);
        // On a device whose original mode is already privacy, change in the other direction.
        if (mOriginal.getInt(AppLockManager.KEY_AUTH_METHOD) == AppLockManager.AUTH_PRIVACY_PASSWORD) {
            policy.putInt(AppLockManager.KEY_AUTH_METHOD, AppLockManager.AUTH_SCREEN_LOCK);
            mApps.setSettings(USER, old, policy);
        }
        denied(() -> mApps.getSettings(USER, old));
        mAppSession = appSession();
    }

    @Test public void passwordChangeRevokesPendingAppLockAuthentication() {
        usePrivacyMethod(false);
        IBinder challenge = mApps.beginManagementAuthentication(USER, mOwner);
        assertTrue(verifyApp(challenge, PIN).getBoolean(PrivacyPasswordManager.KEY_MATCHED));
        mPrivacySession = set(mPrivacySession, PrivacyPasswordManager.TYPE_PIN, "395728");
        mCurrentPassword = "395728";
        denied(() -> mApps.completeAuthentication(challenge));
        mAppSession = appSession();
    }

    @Test public void turningOffPrivacyPasswordDisablesDependentAppLock() {
        usePrivacyMethod(true);
        assertTrue(mApps.isPackageLocked(TARGET, USER));
        mPrivacy.clearPassword(USER, mPrivacySession);
        mPrivacySession = null;
        assertFalse(mPrivacy.getState(USER).getBoolean(PrivacyPasswordManager.KEY_CONFIGURED));
        assertFalse(mApps.isPackageLocked(TARGET, USER));
    }

    @Test public void ordinaryPlatformSignedAppCannotAccessPrivacyCredentials() {
        Bundle denied = mContext.getContentResolver().call(Uri.parse("content://" + TARGET),
                "privacy-security", null, null);
        assertNotNull(denied);
        for (String key : new String[] {"state", "begin", "verify", "set", "clear", "end", "app_verify"}) {
            assertTrue(key + " must reject an ordinary application UID", denied.getBoolean(key));
        }
    }

    @Test public void otherUserCannotBeAuthenticatedOrProvisioned() {
        denied(() -> mPrivacy.getState(9999));
        denied(() -> mPrivacy.beginAuthentication(9999, mOwner));
        denied(() -> mPrivacy.setPassword(9999, mOwner, mPrivacySession,
                PrivacyPasswordManager.TYPE_PIN, PIN.getBytes(StandardCharsets.UTF_8)));
    }

    private void usePrivacyMethod(boolean enabled) {
        Bundle policy = new Bundle(mOriginal);
        policy.putInt(AppLockManager.KEY_AUTH_METHOD, AppLockManager.AUTH_PRIVACY_PASSWORD);
        policy.putBoolean(AppLockManager.KEY_ENABLED, enabled);
        if (enabled) policy.putStringArrayList(AppLockManager.KEY_PACKAGES,
                new ArrayList<>(Arrays.asList(TARGET)));
        mChangedPolicy = true;
        mApps.setSettings(USER, mAppSession, policy);
        mAppSession = appSession();
    }

    private IBinder appSession() {
        IBinder challenge = mApps.beginManagementAuthentication(USER, mOwner);
        if (mApps.getAuthenticationInfo(challenge).getInt(AppLockManager.KEY_AUTH_METHOD)
                == AppLockManager.AUTH_PRIVACY_PASSWORD) {
            assertTrue(verifyApp(challenge, mCurrentPassword).getBoolean(PrivacyPasswordManager.KEY_MATCHED));
        }
        return mApps.completeAuthentication(challenge);
    }

    private IBinder set(IBinder token, int type, String password) {
        byte[] bytes = password.getBytes(StandardCharsets.UTF_8);
        try { return mPrivacy.setPassword(USER, mOwner, token, type, bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    private Bundle verify(IBinder challenge, String password) {
        byte[] bytes = password.getBytes(StandardCharsets.UTF_8);
        try { return mPrivacy.verify(challenge, bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    private Bundle verifyApp(IBinder challenge, String password) {
        byte[] bytes = password.getBytes(StandardCharsets.UTF_8);
        try { return mApps.verifyPrivacyPassword(challenge, bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    private static void denied(Runnable operation) {
        try { operation.run(); fail("Expected SecurityException"); }
        catch (SecurityException expected) { }
    }

    private static void invalid(Runnable operation) {
        try { operation.run(); fail("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
}
