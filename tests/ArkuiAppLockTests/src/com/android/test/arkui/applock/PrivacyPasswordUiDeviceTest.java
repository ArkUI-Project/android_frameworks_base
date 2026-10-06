/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.test.arkui.applock;

import static org.junit.Assert.*;

import android.app.AppLockManager;
import android.app.PrivacyPasswordManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/** Real Settings and Launcher workflows; only a newly created test credential is removed. */
@RunWith(AndroidJUnit4.class)
public final class PrivacyPasswordUiDeviceTest {
    private static final int USER = UserHandle.myUserId();
    private static final String SETTINGS = "com.android.settings";
    private static final String LAUNCHER = "com.android.launcher3";
    private static final String TARGET = "com.android.test.arkui.applock.target";
    private static final String PIN = "274839";
    private final IBinder mOwner = new Binder();
    private Context mContext;
    private Context mSettingsContext;
    private UiDevice mDevice;
    private PrivacyPasswordManager mPrivacy;
    private AppLockManager mApps;
    private Bundle mOriginal;
    private String mScreenPin;
    private boolean mOwnState;

    @Before public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mSettingsContext = mContext.createPackageContext(SETTINGS, 0);
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mScreenPin = InstrumentationRegistry.getArguments().getString("pin");
        mPrivacy = new PrivacyPasswordManager(mContext);
        mApps = new AppLockManager(mContext);
        assertFalse("Do not replace a user's privacy password", configured());
        mOwnState = true;
        mDevice.wakeUp();
        mDevice.pressHome();
    }

    @After public void tearDown() throws Exception {
        if (!mOwnState) return;
        mDevice.pressHome();
        if (mOriginal != null) {
            IBinder challenge = mApps.beginManagementAuthentication(USER, mOwner);
            if (mApps.getAuthenticationInfo(challenge).getInt(AppLockManager.KEY_AUTH_METHOD)
                    == AppLockManager.AUTH_PRIVACY_PASSWORD) {
                assertTrue(mApps.verifyPrivacyPassword(challenge, bytes(PIN))
                        .getBoolean(PrivacyPasswordManager.KEY_MATCHED));
            }
            IBinder session = mApps.completeAuthentication(challenge);
            mApps.setSettings(USER, session, mOriginal);
            mApps.endManagementSession(session);
        }
        if (configured()) {
            IBinder challenge = mPrivacy.beginAuthentication(USER, mOwner);
            Bundle verified = mPrivacy.verify(challenge, bytes(PIN));
            assertTrue("Only the test credential may be removed",
                    verified.getBoolean(PrivacyPasswordManager.KEY_MATCHED));
            mPrivacy.clearPassword(USER, verified.getBinder(PrivacyPasswordManager.KEY_TOKEN));
        }
        assertFalse(configured());
    }

    @Test public void expressiveSetupRequiresValidInputAndMatchingConfirmation() throws Exception {
        openPrivacySettings();
        title("arkui_privacy_password_create");
        field();
        click("privacy_custom_type");
        enter("short");
        click("privacy_primary");
        title("arkui_privacy_password_custom_error");
        assertFalse(configured());
        click("privacy_pin_type");
        enter(PIN);
        click("privacy_primary");
        title("arkui_privacy_password_confirm");
        enter("123456");
        click("privacy_primary");
        title("arkui_privacy_password_mismatch");
        assertFalse(configured());
        enter(PIN);
        click("privacy_primary");
        title("arkui_privacy_password_confirm");
        enter(PIN);
        click("privacy_primary");
        title("arkui_privacy_password_enabled");
        assertTrue(configured());
        mDevice.pressHome();
        openPrivacySettings();
        title("arkui_privacy_password_enter");
        enter("123456");
        click("privacy_primary");
        title("arkui_privacy_password_wrong");
        enter(PIN);
        click("privacy_primary");
        title("arkui_privacy_password_enabled");
    }

    @Test public void privacyConfirmationReusesTheAppLockPromptAndCancelsOnHome() throws Exception {
        createPassword();
        openPrivacyConfirmation();
        title("arkui_privacy_password_enter");
        field();
        assertFalse(mDevice.hasObject(By.res(SETTINGS, "privacy_pin_type")));
        enter("123456");
        click("privacy_primary");
        title("arkui_privacy_password_wrong");
        mDevice.pressHome();
        assertTrue(mDevice.wait(Until.gone(By.res(SETTINGS, "privacy_password_entry")), 5000));
        openPrivacyConfirmation();
        title("arkui_privacy_password_enter");
        enter(PIN);
        click("privacy_primary");
        assertTrue(mDevice.wait(Until.gone(By.res(SETTINGS, "privacy_password_entry")), 5000));
        assertTrue(configured());
    }

    @Test public void hiddenAppsRequirePrivacyPasswordAndReauthenticateAfterHome() throws Exception {
        awaitHiddenState("0");
        createPassword();
        assertNotNull("Provide a distinct test screen PIN", mScreenPin);
        assertNotEquals(PIN, mScreenPin);
        mDevice.executeShellCommand("am start -W -a com.android.launcher3.action.TRUST_APPS"
                + " -n com.android.launcher3/.settings.SettingsActivity");
        title("arkui_privacy_password_enter");
        assertFalse(mDevice.hasObject(By.res(LAUNCHER, "hidden_apps_list")));
        enter(mScreenPin);
        click("privacy_primary");
        title("arkui_privacy_password_wrong");
        assertFalse(mDevice.hasObject(By.res(LAUNCHER, "hidden_apps_list")));
        enter(PIN);
        click("privacy_primary");
        UiObject2 welcome = mDevice.wait(Until.findObject(By.res("android", "button1")), 2000);
        if (welcome != null) welcome.click();
        assertTrue("Authenticated Settings callback must reveal the hidden-app list",
                mDevice.wait(Until.hasObject(By.res(LAUNCHER, "hidden_apps_list")), 10000));
        hideTestTarget();
        mDevice.pressHome();
        awaitHiddenState("1");
        mContext.startActivity(new Intent().setComponent(new ComponentName(LAUNCHER,
                LAUNCHER + ".lineage.trust.TrustAppsActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        title("arkui_privacy_password_enter");
        assertFalse(mDevice.hasObject(By.res(LAUNCHER, "hidden_apps_list")));
        enter(PIN);
        click("privacy_primary");
        assertTrue(mDevice.wait(Until.hasObject(By.res(LAUNCHER, "hidden_apps_list")), 10000));
        hideTestTarget();
        mDevice.pressHome();
        awaitHiddenState("0");
        mContext.startActivity(new Intent().setComponent(new ComponentName(LAUNCHER,
                LAUNCHER + ".lineage.trust.TrustAppsActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        title("arkui_privacy_password_enter");
        click("privacy_cancel");
        assertTrue(mDevice.wait(Until.gone(By.res(SETTINGS, "privacy_password_entry")), 5000));
        assertFalse(mDevice.hasObject(By.res(LAUNCHER, "hidden_apps_list")));
    }

    @Test public void protectedLaunchUsesPrivacyPasswordRatherThanScreenCredential() throws Exception {
        createPassword();
        assertNotNull("Provide a distinct test screen PIN", mScreenPin);
        assertNotEquals(PIN, mScreenPin);
        IBinder challenge = mApps.beginManagementAuthentication(USER, mOwner);
        if (mApps.getAuthenticationInfo(challenge).getInt(AppLockManager.KEY_AUTH_METHOD)
                == AppLockManager.AUTH_PRIVACY_PASSWORD) {
            assertTrue(mApps.verifyPrivacyPassword(challenge, bytes(PIN))
                    .getBoolean(PrivacyPasswordManager.KEY_MATCHED));
        }
        IBinder session = mApps.completeAuthentication(challenge);
        mOriginal = mApps.getSettings(USER, session);
        Bundle policy = new Bundle(mOriginal);
        policy.putInt(AppLockManager.KEY_AUTH_METHOD, AppLockManager.AUTH_PRIVACY_PASSWORD);
        policy.putInt(AppLockManager.KEY_LOCK_MODE, AppLockManager.MODE_IMMEDIATE);
        policy.putBoolean(AppLockManager.KEY_ENABLED, true);
        policy.putStringArrayList(AppLockManager.KEY_PACKAGES,
                new ArrayList<>(Arrays.asList(TARGET)));
        mApps.setSettings(USER, session, policy);
        mDevice.executeShellCommand("am force-stop " + TARGET);
        mDevice.executeShellCommand("am start -W -n " + TARGET + "/"
                + TARGET + ".TargetActivity");
        field();
        assertTrue(mApps.isPackageLocked(TARGET, USER));
        assertFalse(mDevice.hasObject(By.text("Private app content: " + TARGET)));
        enter(mScreenPin);
        click("app_lock_verify");
        title("arkui_privacy_password_wrong");
        assertTrue(mApps.isPackageLocked(TARGET, USER));
        assertFalse(mDevice.hasObject(By.text("Private app content: " + TARGET)));
        enter(PIN);
        click("app_lock_verify");
        assertTrue(mDevice.wait(Until.hasObject(By.text("Private app content: " + TARGET)), 10000));
        assertFalse(mApps.isPackageLocked(TARGET, USER));
        mDevice.pressHome();
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (!mApps.isPackageLocked(TARGET, USER) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100);
        }
        assertTrue("Leaving the app must restore its privacy-password gate",
                mApps.isPackageLocked(TARGET, USER));
    }

    private boolean configured() {
        return mPrivacy.getState(USER).getBoolean(PrivacyPasswordManager.KEY_CONFIGURED);
    }

    private void hideTestTarget() {
        UiObject2 label = mDevice.wait(Until.findObject(By.text("App lock test target")), 5000);
        assertNotNull("The disposable target must be present in hidden-app management", label);
        UiObject2 parent = label.getParent();
        UiObject2 toggle = parent.findObject(By.res(LAUNCHER, "item_hidden_app_switch"));
        assertNotNull("The hide action must belong to the disposable target's row", toggle);
        toggle.click();
    }

    private void awaitHiddenState(String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        String actual;
        do {
            actual = queryHiddenState();
            if (expected.equals(actual)) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertEquals("The authenticated click must save even when Home interrupts its animation",
                expected, actual);
    }

    private String queryHiddenState() throws Exception {
        // UiAutomation uses Runtime.exec, so quoted SQL is not parsed as a shell argument.
        ParcelFileDescriptor[] pipes = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommandRwe("sqlite3 /data/user/0/" + LAUNCHER
                        + "/databases/trust_apps_db");
        try (var output = new ParcelFileDescriptor.AutoCloseInputStream(pipes[0]);
             var input = new ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]);
             var error = new ParcelFileDescriptor.AutoCloseInputStream(pipes[2])) {
            input.write(bytes("SELECT COALESCE(MAX(hidden),0) FROM trust_apps WHERE pkgname='"
                    + TARGET + "';\n"));
            input.close();
            String result = new String(output.readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals("The test database query must succeed", "",
                    new String(error.readAllBytes(), StandardCharsets.UTF_8).trim());
            return result;
        }
    }

    private void createPassword() {
        mPrivacy.setPassword(USER, mOwner, null, PrivacyPasswordManager.TYPE_PIN, bytes(PIN));
    }

    private void openPrivacySettings() throws Exception {
        mDevice.executeShellCommand("am start -W -a " + PrivacyPasswordManager.ACTION_SETTINGS
                + " -n " + SETTINGS + "/" + SETTINGS + ".arkui.privacy.PrivacyPasswordActivity");
    }

    private void openPrivacyConfirmation() throws Exception {
        mDevice.executeShellCommand("am start -W -a " + PrivacyPasswordManager.ACTION_CONFIRM
                + " -n " + SETTINGS + "/" + SETTINGS + ".arkui.privacy.PrivacyPasswordActivity");
    }

    private UiObject2 field() {
        UiObject2 input = mDevice.wait(Until.findObject(By.res(SETTINGS, "privacy_password_entry")), 10000);
        assertNotNull("Material password entry must inflate and be visible", input);
        assertNotNull("Privacy and app lock must share the same confirmation layout",
                mDevice.findObject(By.res(SETTINGS, "credential_prompt")));
        return input;
    }

    private void enter(String value) { field().setText(value); }

    private void click(String id) {
        UiObject2 button = mDevice.wait(Until.findObject(By.res(SETTINGS, id)), 5000);
        assertNotNull(id, button);
        button.click();
    }

    private void title(String name) {
        int id = mSettingsContext.getResources().getIdentifier(name, "string", SETTINGS);
        assertNotEquals("Missing localized string: " + name, 0, id);
        assertTrue("Missing UI state: " + name,
                mDevice.wait(Until.hasObject(By.text(mSettingsContext.getString(id))), 10000));
    }

    private static byte[] bytes(String password) { return password.getBytes(StandardCharsets.UTF_8); }
}
