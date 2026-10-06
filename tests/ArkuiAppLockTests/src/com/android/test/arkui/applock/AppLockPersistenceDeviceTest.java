/* SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0 */
package com.android.test.arkui.applock;

import static org.junit.Assert.*;

import android.app.AppLockManager;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Run prepare, reboot the locally built emulator, unlock its keyguard, then run verify. */
@RunWith(AndroidJUnit4.class)
public final class AppLockPersistenceDeviceTest {
    private static final String TARGET = "com.android.test.arkui.applock.target";
    private static final Pattern CREDENTIAL_INPUT = Pattern.compile(
            "com\\.android\\.(systemui:id/lockPassword|settings:id/password_entry)");
    private static final int USER = UserHandle.myUserId();

    @Test
    public void prepareUnlockedPolicyBeforeReboot() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppLockManager manager = new AppLockManager(context);
        IBinder token = manager.completeAuthentication(
                manager.beginManagementAuthentication(USER, new Binder()));
        Bundle original = manager.getSettings(USER, token);
        Parcel parcel = Parcel.obtain();
        try (FileOutputStream stream = new FileOutputStream(backup(context))) {
            parcel.writeBundle(original);
            stream.write(parcel.marshall());
            stream.getFD().sync();
        } finally { parcel.recycle(); }
        try {
            Bundle policy = new Bundle(original);
            policy.putBoolean(AppLockManager.KEY_ENABLED, true);
            policy.putBoolean(AppLockManager.KEY_HIDE_NOTIFICATIONS, true);
            // Neither screen-off nor a short timeout should explain the lock after reboot.
            policy.putBoolean(AppLockManager.KEY_LOCK_ON_SCREEN_OFF, false);
            policy.putInt(AppLockManager.KEY_LOCK_MODE, AppLockManager.MODE_TIMEOUT);
            policy.putInt(AppLockManager.KEY_TIMEOUT_MINUTES, 1440);
            policy.putStringArrayList(AppLockManager.KEY_PACKAGES, new ArrayList<>(List.of(TARGET)));
            manager.setSettings(USER, token, policy);
            authenticateTarget(context);
            assertFalse("seed a real, temporary unlock grant", manager.isPackageLocked(TARGET, USER));
        } finally { manager.endManagementSession(token); }
    }

    @Test
    public void verifyRebootPreservesPolicyButDropsUnlockGrant() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppLockManager manager = new AppLockManager(context);
        IBinder token = manager.completeAuthentication(
                manager.beginManagementAuthentication(USER, new Binder()));
        Parcel parcel = Parcel.obtain();
        Bundle original;
        try (FileInputStream stream = new FileInputStream(backup(context))) {
            byte[] bytes = stream.readAllBytes();
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);
            original = parcel.readBundle(getClass().getClassLoader());
        } finally { parcel.recycle(); }
        try {
            Bundle policy = manager.getSettings(USER, token);
            assertTrue(policy.getBoolean(AppLockManager.KEY_ENABLED));
            assertEquals(List.of(TARGET), policy.getStringArrayList(AppLockManager.KEY_PACKAGES));
            assertEquals(AppLockManager.MODE_TIMEOUT, policy.getInt(AppLockManager.KEY_LOCK_MODE));
            assertEquals(1440, policy.getInt(AppLockManager.KEY_TIMEOUT_MINUTES));
            assertFalse(policy.getBoolean(AppLockManager.KEY_LOCK_ON_SCREEN_OFF));
            assertTrue(policy.getBoolean(AppLockManager.KEY_HIDE_NOTIFICATIONS));
            assertTrue("reboot must invalidate the previous successful authentication",
                    manager.isPackageLocked(TARGET, USER));
            authenticateTarget(context);
            assertFalse(manager.isPackageLocked(TARGET, USER));
        } finally {
            manager.setSettings(USER, token, original);
            manager.endManagementSession(token);
            assertTrue(backup(context).delete());
        }
    }

    private static File backup(Context context) {
        return new File(context.getFilesDir(), "app-lock-policy-before-reboot.bin");
    }

    private static void authenticateTarget(Context context) throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp();
        device.pressHome();
        context.startActivity(new Intent().setClassName(TARGET, TARGET + ".TargetActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        UiObject2 entry = device.wait(Until.findObject(
                By.res(CREDENTIAL_INPUT).enabled(true)), 15000);
        assertNotNull("real credential verification is required", entry);
        entry.setText(InstrumentationRegistry.getArguments().getString("pin"));
        device.pressEnter();
        assertTrue(device.wait(Until.hasObject(By.text("Private app content: " + TARGET)), 15000));
    }
}
