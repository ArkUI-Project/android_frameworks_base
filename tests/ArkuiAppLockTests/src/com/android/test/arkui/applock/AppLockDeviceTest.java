/* SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0 */
package com.android.test.arkui.applock;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.AppLockManager;
import android.app.KeyguardManager;
import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.TaskSnapshot;
import android.window.WindowContainerTransaction;
import android.window.WindowOrganizer;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import androidx.test.uiautomator.StaleObjectException;

import com.android.internal.widget.LockPatternUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * System integration tests. Install both helper APKs and provide a known test-only screen PIN
 * with the instrumentation argument "pin". Configuring policy uses this test's trusted system
 * UID; opening a locked application always goes through the real device-credential UI.
 */
@RunWith(AndroidJUnit4.class)
public class AppLockDeviceTest {
    private static final String FIRST = "com.android.test.arkui.applock.target";
    private static final String SECOND = "com.android.test.arkui.applock.second";
    private static final String SYSTEM_UI_FIXTURE = "com.android.test.arkui.applock.systemui";
    private static final String ACTIVITY = "com.android.test.arkui.applock.target.TargetActivity";
    private static final String SECOND_ACTIVITY = "com.android.test.arkui.applock.target.SecondActivity";
    private static final String CONFIRM =
            "com.android.settings.arkui.applock.AppLockConfirmActivity";
    private static final String CONFIRM_COMPONENT =
            new ComponentName("com.android.settings", CONFIRM).flattenToShortString();
    // Android 16 renders credentials inside SystemUI; older fallback screens use Settings.
    private static final Pattern CREDENTIAL_INPUT = Pattern.compile(
            "com\\.android\\.(systemui:id/lockPassword|settings:id/password_entry)");
    private static final Pattern PIN_BUTTON = Pattern.compile(
            "com\\.android\\.systemui:id/credential_pin_(key_[0-9]|backspace|confirm)");
    private static final int USER = UserHandle.myUserId();
    private Context mContext;
    private UiDevice mDevice;
    private AppLockManager mManager;
    private IBinder mOwner;
    private IBinder mToken;
    private Bundle mOriginal;
    private String mPin;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mManager = new AppLockManager(mContext);
        mOwner = new Binder();
        mPin = InstrumentationRegistry.getArguments().getString("pin");
        assertNotNull("A temporary test device PIN is required", mPin);
        mDevice.wakeUp();
        mDevice.pressHome();
        authenticateKeyguardIfPresent();
        mToken = managementToken();
        mOriginal = mManager.getSettings(USER, mToken);
        Bundle disabled = new Bundle(mOriginal);
        disabled.putBoolean(AppLockManager.KEY_ENABLED, false);
        mManager.setSettings(USER, mToken, disabled);
        // Revoke listener access before stopping/removing tasks; otherwise NLS auto-rebind can
        // start a replacement process that task removal kills while its provider is attaching.
        shell("cmd notification disallow_listener " + FIRST + "/"
                + FIRST + ".TestNotificationListener");
        shell("am force-stop " + FIRST);
        shell("am force-stop " + SECOND);
        removeFixtureTasks();
        command(FIRST, "cancel", null);
        command(SECOND, "cancel", null);
        shell("cmd notification allow_listener " + FIRST + "/"
                + FIRST + ".TestNotificationListener");
        await("notification listener connection", () ->
                command(FIRST, "listener", null).getBoolean("connected"), 15000);
        command(FIRST, "clear-events", null);
    }

    @After
    public void tearDown() throws Exception {
        if (mManager == null) return;
        try {
            mDevice.wakeUp();
            authenticateKeyguardIfPresent();
            mDevice.pressHome();
            // Screen-off tests deliberately revoke the previous management capability.
            mToken = managementToken();
            if (mOriginal != null) mManager.setSettings(USER, mToken, mOriginal);
            mManager.endManagementSession(mToken);
            command(FIRST, "cancel", null);
            command(SECOND, "cancel", null);
        } finally {
            shell("cmd notification disallow_listener " + FIRST + "/"
                    + FIRST + ".TestNotificationListener");
            shell("am force-stop " + FIRST);
            shell("am force-stop " + SECOND);
            removeFixtureTasks();
        }
    }

    @Test
    public void ordinaryUidCannotManageOrForgeAuthentication() {
        Bundle denied = command(FIRST, "security", null);
        assertTrue("fixture must hold the platform signature permission", denied.getBoolean("has_permission"));
        assertTrue("fixture must still be an ordinary application UID", denied.getBoolean("ordinary_uid"));
        for (String operation : Arrays.asList(
                "begin", "complete", "read", "write", "lock", "query", "notification_query", "broadcast")) {
            assertTrue(operation + " must reject an ordinary application UID", denied.getBoolean(operation));
        }
    }

    @Test
    public void realSystemUiUidCanQueryAppLocksButCannotManageOrAuthenticate() throws Exception {
        configure(AppLockManager.MODE_SCREEN_OFF, true, true);
        assertSystemUiQueryState(true, true);

        // Probe with actual valid capabilities, so rejection proves the caller boundary rather
        // than merely demonstrating that a fabricated token cannot pass validation.
        IBinder challenge = mManager.beginManagementAuthentication(USER, mOwner);
        Bundle request = new Bundle();
        request.putBinder("challenge", challenge);
        request.putBinder("token", mToken);
        request.putBundle("settings", mManager.getSettings(USER, mToken));
        Bundle denied = null;
        try {
            denied = mContext.getContentResolver().call(Uri.parse("content://" + SYSTEM_UI_FIXTURE),
                    "management-security", FIRST, request);
            assertNotNull(denied);
            assertSystemUiIdentity(denied);
            for (String operation : Arrays.asList(
                    "begin", "info", "complete", "cancel", "read", "write", "lock", "end")) {
                assertTrue(operation + " must remain restricted to system UID 1000",
                        denied.getBoolean(operation));
            }
            assertTrue("the rejected SystemUI caller must leave the real challenge intact",
                    mManager.getAuthenticationInfo(challenge).getBoolean(AppLockManager.KEY_MANAGEMENT));
            assertTrue("the rejected SystemUI caller must leave the real token usable",
                    mManager.getSettings(USER, mToken).getBoolean(AppLockManager.KEY_ENABLED));
        } finally {
            mManager.cancelAuthentication(challenge);
            if (denied != null) {
                IBinder created = denied.getBinder("created_challenge");
                if (created != null) mManager.cancelAuthentication(created);
                IBinder redeemed = denied.getBinder("redeemed_token");
                if (redeemed != null) mManager.endManagementSession(redeemed);
            }
        }

        Bundle ordinary = command(FIRST, "security", null);
        assertNotEquals("the ordinary fixture must not share the SystemUI UID",
                mContext.getPackageManager().getApplicationInfo("com.android.systemui", 0).uid,
                mContext.getPackageManager().getApplicationInfo(FIRST, 0).uid);
        assertTrue(ordinary.getBoolean("has_permission"));
        assertTrue(ordinary.getBoolean("ordinary_uid"));
        assertTrue("an ordinary platform-signed UID must not query locked state",
                ordinary.getBoolean("query"));
        assertTrue("an ordinary platform-signed UID must not query notification state",
                ordinary.getBoolean("notification_query"));

        openAndAuthenticate(FIRST);
        assertSystemUiQueryState(false, false);
        mManager.lockAll(USER, mToken);
        await("protected package relocks after explicit management action", () ->
                mManager.isPackageLocked(FIRST, USER), 10000);
        assertSystemUiQueryState(true, true);
    }

    private void assertSystemUiQueryState(boolean locked, boolean hideNotifications) throws Exception {
        Bundle state = mContext.getContentResolver().call(Uri.parse("content://" + SYSTEM_UI_FIXTURE),
                "query", FIRST, null);
        assertNotNull("the SystemUI shared-UID fixture must be installed", state);
        assertSystemUiIdentity(state);
        assertEquals("SystemUI must query the actual app-lock state", locked,
                state.getBoolean("locked"));
        assertEquals("SystemUI must query the actual notification protection state",
                hideNotifications, state.getBoolean("hide_notifications"));
    }

    private void assertSystemUiIdentity(Bundle result) throws Exception {
        int systemUiUid = mContext.getPackageManager().getApplicationInfo("com.android.systemui", 0).uid;
        int fixtureUid = mContext.getPackageManager().getApplicationInfo(SYSTEM_UI_FIXTURE, 0).uid;
        assertNotEquals("the test must exercise the real non-system SystemUI UID",
                android.os.Process.SYSTEM_UID, UserHandle.getAppId(systemUiUid));
        assertEquals("the fixture must share the installed SystemUI UID", systemUiUid, fixtureUid);
        assertEquals(fixtureUid, result.getInt("uid"));
        assertEquals("the framework call must use the provider process identity",
                fixtureUid, result.getInt("binder_uid"));
        assertEquals(systemUiUid, result.getInt("systemui_uid"));
        assertTrue("the SystemUI caller must hold the signature permission",
                result.getBoolean("has_permission"));
    }

    @Test
    public void managementChallengesAreOneUseAndCancelable() {
        IBinder challenge = mManager.beginManagementAuthentication(USER, mOwner);
        Bundle info = mManager.getAuthenticationInfo(challenge);
        assertTrue(info.getBoolean(AppLockManager.KEY_MANAGEMENT));
        assertEquals(USER, info.getInt(AppLockManager.KEY_USER_ID));
        IBinder token = mManager.completeAuthentication(challenge);
        assertNotNull(token);
        expectSecurity(() -> mManager.completeAuthentication(challenge));
        mManager.endManagementSession(token);
        expectSecurity(() -> mManager.getSettings(USER, token));
        IBinder canceled = mManager.beginManagementAuthentication(USER, mOwner);
        mManager.cancelAuthentication(canceled);
        expectSecurity(() -> mManager.completeAuthentication(canceled));
        expectSecurity(() -> mManager.setSettings(USER, new Binder(), new Bundle()));
    }

    @Test
    public void invalidPolicyDoesNotPartiallyOverwriteSettings() {
        configure(AppLockManager.MODE_TIMEOUT, false, true);
        Bundle before = mManager.getSettings(USER, mToken);
        for (int minutes : new int[]{0, -1, 1441}) {
            Bundle invalid = new Bundle(before);
            invalid.putInt(AppLockManager.KEY_TIMEOUT_MINUTES, minutes);
            expectIllegal(() -> mManager.setSettings(USER, mToken, invalid));
            assertEquals(before.getInt(AppLockManager.KEY_TIMEOUT_MINUTES),
                    mManager.getSettings(USER, mToken).getInt(AppLockManager.KEY_TIMEOUT_MINUTES));
        }
        Bundle invalidMode = new Bundle(before);
        invalidMode.putInt(AppLockManager.KEY_LOCK_MODE, 99);
        expectIllegal(() -> mManager.setSettings(USER, mToken, invalidMode));
        Bundle critical = new Bundle(before);
        critical.putStringArrayList(AppLockManager.KEY_PACKAGES,
                new ArrayList<>(Arrays.asList("com.android.settings")));
        expectIllegal(() -> mManager.setSettings(USER, mToken, critical));
    }

    @Test
    public void returnedSettingsCannotMutatePolicy() {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        Bundle copy = mManager.getSettings(USER, mToken);
        copy.putBoolean(AppLockManager.KEY_ENABLED, false);
        copy.getStringArrayList(AppLockManager.KEY_PACKAGES).clear();
        assertTrue(mManager.getSettings(USER, mToken).getBoolean(AppLockManager.KEY_ENABLED));
        assertTrue(mManager.isPackageLocked(FIRST, USER));
    }

    @Test
    public void coldLaunchRequiresCredentialAndGrantIsPerPackage() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        launch(FIRST);
        awaitAuthentication();
        assertPrivateContentAbsent(FIRST);
        assertTrue(mManager.isPackageLocked(FIRST, USER));
        authenticateApp();
        awaitVisible(FIRST);
        assertFalse(mManager.isPackageLocked(FIRST, USER));
        assertTrue(mManager.isPackageLocked(SECOND, USER));
        assertFalse(mManager.shouldHideNotifications(FIRST, USER));
        mDevice.pressHome();
        await("immediate background lock", () -> mManager.isPackageLocked(FIRST, USER), 10000);
    }

    @Test
    public void nativeCredentialPinPadSupportsDigitsDeletionAndConfirmation() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        launch(FIRST);
        awaitAuthentication();
        assertTrue("SystemUI must provide the native credential PIN pad",
                mDevice.wait(Until.hasObject(By.res("com.android.systemui", "pin_pad")), 15000));
        assertNativePinControlsInsideSystemSafeArea();
        // A deliberately incorrect prefix must be removed before entering the real PIN.
        int wrongDigit = (mPin.charAt(0) - '0' + 1) % 10;
        clickCredentialPinButton("credential_pin_key_" + wrongDigit);
        clickCredentialPinButton("credential_pin_backspace");
        for (char digit : mPin.toCharArray()) {
            clickCredentialPinButton("credential_pin_key_" + digit);
        }
        assertTrue("digit entry alone must not redeem the authentication challenge",
                mManager.isPackageLocked(FIRST, USER));
        assertPrivateContentAbsent(FIRST);
        clickCredentialPinButton("credential_pin_confirm");
        awaitVisible(FIRST);
        assertFalse(mManager.isPackageLocked(FIRST, USER));
    }

    @Test
    public void repeatedLaunchWhileAuthenticatingKeepsOneUsableCredentialPrompt() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        launch(FIRST);
        awaitAuthentication();
        launch(FIRST);
        SystemClock.sleep(750);
        assertTrue("a repeated launch must not cancel the current authentication", isAuthenticationVisible());
        assertPrivateContentAbsent(FIRST);
        authenticateApp();
        awaitVisible(FIRST);
        assertFalse(mManager.isPackageLocked(FIRST, USER));
    }

    @Test
    public void choosingAnotherDocumentWhileAuthenticatingRestoresLatestRequestedTask()
            throws Exception {
        int documentA = -1;
        int documentB = -1;
        try {
            // Setup leaves app lock disabled so both original, independent tasks can exist.
            launchDocument("A");
            awaitDocumentVisible("A");
            documentA = documentTaskId("A");
            mDevice.pressHome();
            launchDocument("B");
            awaitDocumentVisible("B");
            documentB = documentTaskId("B");
            assertNotEquals("NEW_DOCUMENT/MULTIPLE_TASK must create independent tasks",
                    documentA, documentB);
            mDevice.pressHome();
            configure(AppLockManager.MODE_IMMEDIATE, true, true);
            assertTrue(mManager.isPackageLocked(FIRST, USER));

            ActivityTaskManager.getService().startActivityFromRecents(documentA, null);
            awaitAuthentication();
            assertPrivateContentAbsent(FIRST);
            assertFalse(mDevice.hasObject(By.text("Private document: A")));
            assertFalse(mDevice.hasObject(By.text("Private document: B")));

            // Select B while A's credential prompt is still pending, without authenticating A.
            ActivityTaskManager.getService().startActivityFromRecents(documentB, null);
            SystemClock.sleep(750);
            assertTrue("the updated request must retain a usable credential prompt",
                    isAuthenticationVisible());
            assertTrue(mManager.isPackageLocked(FIRST, USER));
            assertPrivateContentAbsent(FIRST);
            assertFalse(mDevice.hasObject(By.text("Private document: A")));
            assertFalse(mDevice.hasObject(By.text("Private document: B")));

            authenticateApp();
            awaitVisible(FIRST);
            awaitDocumentVisible("B");
            assertFalse("authentication must restore the user's latest document, not A",
                    mDevice.hasObject(By.text("Private document: A")));
            assertFalse(mManager.isPackageLocked(FIRST, USER));
            assertEquals("document A must retain its original task identity",
                    documentA, documentTaskId("A"));
            assertEquals("document B must retain its original task identity",
                    documentB, documentTaskId("B"));
        } finally {
            if (documentB >= 0) ActivityTaskManager.getService().removeTask(documentB);
            if (documentA >= 0) ActivityTaskManager.getService().removeTask(documentA);
        }
    }

    @Test
    public void wrongCredentialDoesNotUnlockOrExposePrivateContent() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        launch(FIRST);
        awaitAuthentication();
        UiObject2 entry = mDevice.wait(Until.findObject(
                By.res(CREDENTIAL_INPUT).enabled(true)), 15000);
        assertNotNull(entry);
        entry.setText("000000".equals(mPin) ? "111111" : "000000");
        mDevice.pressEnter();
        SystemClock.sleep(1500);
        assertTrue(mManager.isPackageLocked(FIRST, USER));
        assertPrivateContentAbsent(FIRST);
        authenticateApp();
        awaitVisible(FIRST);
    }

    @Test
    public void realPinLockoutSurvivesRotationAndExpiresBeforeUnlock() throws Exception {
        assertTrue("this regression requires the temporary six-digit device PIN",
                mPin.matches("[0-9]{6}"));
        LockPatternUtils lockPatternUtils = new LockPatternUtils(mContext);
        int credentialOwner = mContext.getSystemService(UserManager.class)
                .getCredentialOwnerProfile(USER);
        String autoRotate = shell("settings get system accelerometer_rotation").trim();
        String userRotation = shell("settings get system user_rotation").trim();
        String screenTimeout = shell("settings get system screen_off_timeout").trim();
        String adaptiveAuthLock = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.DISABLE_ADAPTIVE_AUTH_LIMIT_LOCK, USER);
        final boolean serviceManagesLockout = android.security.Flags.manageLockoutEndTimeInService();
        final Pattern countdown = credentialCountdownPattern();
        boolean attemptedWrongPin = false;
        boolean correctPinSubmitted = false;
        boolean correctPinAuthenticated = false;
        try {
            boolean supportsAdaptiveAuthToggle =
                    (android.security.Flags.failedAuthLockToggle()
                            && mContext.getResources().getBoolean(
                                    com.android.internal.R.bool.config_enableFailedAuthLockToggle))
                    || (android.security.Flags.disableAdaptiveAuthCounterLock()
                            && Build.IS_DEBUGGABLE);
            assertTrue("this ROM must support isolating its separate failed-auth device-lock policy",
                    supportsAdaptiveAuthToggle);
            // Five failures can separately lock the entire device and dismiss BiometricPrompt.
            // Isolate that user-configurable policy only for this regression; actual credential
            // verification and its hardware/software rate limiter remain enabled throughout.
            assertTrue(Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.DISABLE_ADAPTIVE_AUTH_LIMIT_LOCK, "1", USER));
            Log.i("ArkuiAppLockTests", "Real PIN lockout flags: softwareRatelimiter="
                    + android.security.Flags.softwareRatelimiter()
                    + ", manageLockoutEndTimeInService="
                    + android.security.Flags.manageLockoutEndTimeInService());
            // Keep the credential page visible while the real, one-minute timeout runs.
            shell("settings put system screen_off_timeout 300000");
            mDevice.setOrientationNatural();
            configure(AppLockManager.MODE_IMMEDIATE, true, true);
            // Reset real GateKeeper/software-limiter history instead of relying on test order.
            openAndAuthenticate(FIRST);
            mDevice.pressHome();
            await("lock before exercising wrong credentials", () ->
                    mManager.isPackageLocked(FIRST, USER), 10000);
            launch(FIRST);
            awaitAuthentication();

            for (int attempt = 0; attempt < 10
                    && !isNativePinThrottled(countdown); attempt++) {
                UiObject2 entry = mDevice.wait(Until.findObject(
                        By.res("com.android.systemui", "lockPassword").enabled(true)), 15000);
                assertNotNull("PIN input must be ready for the next real verification", entry);
                // The software rate limiter recognizes duplicate wrong guesses. Use a new
                // six-digit guess each time so every attempt reaches real credential checking.
                String wrongPin = String.format(Locale.ROOT, "%06d",
                        (Integer.parseInt(mPin) + attempt + 1) % 1000000);
                int failedAttemptsBefore = lockPatternUtils.getCurrentFailedPasswordAttempts(
                        credentialOwner);
                entry.setText(wrongPin);
                attemptedWrongPin = true;
                clickCredentialPinButton("credential_pin_confirm");
                await("wrong PIN attempt " + (attempt + 1) + " completes after failure count "
                        + failedAttemptsBefore + " or establishes real credential throttling", () -> {
                    // A hardware timeout does not report an ordinary failed-password attempt.
                    // Legacy deadlines live only in SystemUI's injected LockPatternUtils, so
                    // prove that branch through its real countdown and every disabled key.
                    if (isNativePinThrottled(countdown)) return true;
                    if (serviceManagesLockout
                            && realPinLockoutDeadline(lockPatternUtils, credentialOwner)
                                    > SystemClock.elapsedRealtime()) return true;
                    // Identical generic errors share a StateFlow value, so a previous error's
                    // three-second timer can clear the message during a later failed check.
                    // The system failure counter proves this attempt actually finished checking.
                    if (lockPatternUtils.getCurrentFailedPasswordAttempts(credentialOwner)
                            <= failedAttemptsBefore) return false;
                    UiObject2 input = mDevice.findObject(
                            By.res("com.android.systemui", "lockPassword"));
                    return input != null && input.isEnabled()
                            && (input.getText() == null || input.getText().isEmpty());
                }, 15000);
                assertTrue(mManager.isPackageLocked(FIRST, USER));
                assertPrivateContentAbsent(FIRST);
            }
            await("real credential throttling publishes seconds and disables input and all keys",
                    () -> isNativePinThrottled(countdown), 5000);
            long firstObservedAt = SystemClock.elapsedRealtime();
            int firstSeconds = credentialCountdownSeconds(countdown);
            assertTrue("the first real lockout must fit the 90-second test bound",
                    firstSeconds > 0 && firstSeconds <= 90);
            if (serviceManagesLockout) {
                long deadline = realPinLockoutDeadline(lockPatternUtils, credentialOwner);
                assertTrue("service-managed verification must establish a real lockout deadline",
                        deadline > SystemClock.elapsedRealtime());
                assertTrue("the first service-managed lockout must fit the 90-second test bound",
                        deadline - SystemClock.elapsedRealtime() <= 90000);
            }
            assertNativePinInputDisabled();
            Log.i("ArkuiAppLockTests", "Real native PIN lockout seconds=" + firstSeconds
                    + ", serviceManaged=" + serviceManagesLockout);
            SystemClock.sleep(2100);
            await("real remaining seconds decrease after at least two elapsed seconds", () ->
                    isNativePinThrottled(countdown)
                            && credentialCountdownSeconds(countdown) > 0
                            && credentialCountdownSeconds(countdown) < firstSeconds, 5000);
            int beforeRotationSeconds = credentialCountdownSeconds(countdown);
            assertTrue("the sampled lockout must still show a positive remaining duration",
                    beforeRotationSeconds > 0 && beforeRotationSeconds < firstSeconds);
            long beforeRotationAt = SystemClock.elapsedRealtime();
            long sampledSeconds = (beforeRotationAt - firstObservedAt) / 1000;
            assertTrue("the countdown must be sampled across at least two real seconds",
                    beforeRotationAt - firstObservedAt >= 2000);
            assertTrue("countdown progress must follow actual elapsed time",
                    Math.abs(firstSeconds - beforeRotationSeconds - sampledSeconds) <= 3);
            assertNativePinInputDisabled();

            mDevice.setOrientationLeft();
            await("display rotates to landscape", () ->
                    mDevice.getDisplayWidth() > mDevice.getDisplayHeight(), 10000);
            // Locate the rebuilt hierarchy again; none of the old UiObject2 nodes are reused.
            assertTrue(mDevice.wait(Until.hasObject(
                    By.res("com.android.systemui", "pin_pad")), 10000));
            assertNativePinControlsInsideSystemSafeArea();
            assertNativePinInputDisabled();
            await("rotation preserves real seconds and disabled input and all keys", () ->
                    isNativePinThrottled(countdown), 5000);
            int afterRotationSeconds = credentialCountdownSeconds(countdown);
            assertTrue("the rebuilt PIN page must still show a positive remaining duration",
                    afterRotationSeconds > 0);
            long rotationSeconds = (SystemClock.elapsedRealtime() - beforeRotationAt) / 1000;
            assertTrue("rotation must not restart the remaining-seconds countdown",
                    afterRotationSeconds <= beforeRotationSeconds + 1);
            assertTrue("rotation must preserve countdown progress across actual elapsed time",
                    Math.abs(beforeRotationSeconds - afterRotationSeconds - rotationSeconds) <= 3);
            Log.i("ArkuiAppLockTests", "Rotated real native PIN lockout seconds="
                    + afterRotationSeconds + ", elapsedSeconds=" + rotationSeconds);
            if (serviceManagesLockout) {
                assertTrue("rotation must not discard the real service lockout deadline",
                        realPinLockoutDeadline(lockPatternUtils, credentialOwner)
                                > SystemClock.elapsedRealtime());
            }
            assertTrue(mManager.isPackageLocked(FIRST, USER));
            assertPrivateContentAbsent(FIRST);

            await("real lockout expires and enables the editor and all twelve native keys", () ->
                    hasNativePinInputState(true) && credentialCountdownSeconds(countdown) <= 0
                            && (!serviceManagesLockout
                                    || realPinLockoutDeadline(lockPatternUtils, credentialOwner)
                                            <= SystemClock.elapsedRealtime()), 90000);
            UiObject2 readyInput = mDevice.findObject(
                    By.res("com.android.systemui", "lockPassword").enabled(true));
            assertNotNull("the real PIN editor must recover before correct verification", readyInput);
            readyInput.setText(mPin);
            correctPinSubmitted = true;
            clickCredentialPinButton("credential_pin_confirm");
            awaitVisible(FIRST);
            assertFalse(mManager.isPackageLocked(FIRST, USER));
            correctPinAuthenticated = true;
        } finally {
            try {
                // A failed assertion must not leave teardown attempting a still-throttled PIN.
                if (attemptedWrongPin && !correctPinAuthenticated) {
                    final boolean submittedCorrectPin = correctPinSubmitted;
                    if (!mDevice.hasObject(By.res("com.android.systemui", "lockPassword"))
                            && !(submittedCorrectPin && !mManager.isPackageLocked(FIRST, USER))) {
                        mDevice.wakeUp();
                        mDevice.pressHome();
                        launch(FIRST);
                        awaitAuthentication();
                    }
                    await("actual native PIN input recovers before cleanup", () ->
                            (submittedCorrectPin && !mManager.isPackageLocked(FIRST, USER))
                                    || (hasNativePinInputState(true)
                                            && credentialCountdownSeconds(countdown) <= 0
                                            && (!serviceManagesLockout
                                                    || realPinLockoutDeadline(lockPatternUtils, credentialOwner)
                                                            <= SystemClock.elapsedRealtime())), 90000);
                }
            } finally {
                try {
                    try {
                        mDevice.freezeRotation();
                        mDevice.setOrientationNatural();
                    } finally {
                        mDevice.unfreezeRotation();
                    }
                } finally {
                    try {
                        restoreSystemSetting("user_rotation", userRotation);
                    } finally {
                        try {
                            restoreSystemSetting("accelerometer_rotation", autoRotate);
                        } finally {
                            try {
                                restoreSystemSetting("screen_off_timeout", screenTimeout);
                            } finally {
                                assertTrue("restore the original failed-auth device-lock setting exactly",
                                        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                                                Settings.Secure.DISABLE_ADAPTIVE_AUTH_LIMIT_LOCK,
                                                adaptiveAuthLock, USER));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void openingDifferentProtectedAppDoesNotLetOldPromptCancelNewAuthentication() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        launch(FIRST);
        awaitAuthentication();
        launch(SECOND);
        SystemClock.sleep(750);
        awaitAuthentication();
        authenticateApp();
        awaitVisible(SECOND);
        assertTrue(mManager.isPackageLocked(FIRST, USER));
        assertFalse(mManager.isPackageLocked(SECOND, USER));
    }

    @Test
    public void deniedBackgroundLaunchCannotReplaceUsersPendingAuthentication() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        launch(FIRST);
        awaitAuthentication();
        command(SECOND, "background-launch", FIRST);
        SystemClock.sleep(750);
        assertTrue("a denied background launch must not disturb the user's prompt", isAuthenticationVisible());
        authenticateApp();
        awaitVisible(FIRST);
        assertNotEquals("background launch must not replace the user's original intent",
                "blocked-background", command(FIRST, "result", null).getString("launch_marker"));
    }

    @Test
    public void samePackageActivityTransitionDoesNotRelock() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        openAndAuthenticate(FIRST);
        int task = taskId(FIRST);
        await("the authenticated caller is the original focused task's top activity", () ->
                isFixtureActivityTop(task, FIRST, ACTIVITY), 10000);
        mDevice.findObject(By.text("Open second activity")).click();
        await("the real same-package second activity becomes top in the original task", () ->
                isFixtureActivityTop(task, FIRST, SECOND_ACTIVITY), 10000);
        awaitVisible(FIRST);
        assertFalse(mManager.isPackageLocked(FIRST, USER));
        assertFalse(isAuthenticationVisible());
        assertTrue("a real Back event must be injected", mDevice.pressBack());
        await("Back returns to the original caller in the same focused task", () ->
                isFixtureActivityTop(task, FIRST, ACTIVITY), 10000);
        awaitVisible(FIRST);
        assertFalse(mManager.isPackageLocked(FIRST, USER));
        assertFalse(isAuthenticationVisible());
    }

    @Test
    public void authenticationPreservesActivityResultAndOriginalUriGrant() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        Bundle policy = mManager.getSettings(USER, mToken);
        policy.putStringArrayList(AppLockManager.KEY_PACKAGES,
                new ArrayList<>(Arrays.asList(SECOND)));
        mManager.setSettings(USER, mToken, policy);
        launch(FIRST);
        awaitVisible(FIRST);
        UiObject2 openResult = mDevice.wait(Until.findObject(
                By.text("Open protected app for result").enabled(true)), 10000);
        assertNotNull("the caller must show its real startActivityForResult button", openResult);
        openResult.click();
        awaitAuthentication();
        assertEquals("authentication must not deliver an early cancellation to the caller",
                Integer.MIN_VALUE, command(FIRST, "result", null).getInt("code"));
        authenticateApp();
        awaitVisible(SECOND);
        assertEquals("original-private-uri-grant", command(SECOND, "result", null).getString("grant"));
        UiObject2 returnResult = mDevice.wait(Until.findObject(
                By.text("Return protected result").enabled(true)), 10000);
        assertNotNull("the authenticated callee must show its real result button", returnResult);
        returnResult.click();
        awaitVisible(FIRST);
        assertEquals(android.app.Activity.RESULT_OK, command(FIRST, "result", null).getInt("code"));
        assertEquals("protected-result", command(FIRST, "result", null).getString("answer"));
    }

    @Test
    public void settingsUsesRealAuthenticationAndRejectsInvalidDelay() throws Exception {
        configure(AppLockManager.MODE_TIMEOUT, false, true);
        openSettings();
        authenticateApp();
        assertTrue(mDevice.wait(Until.hasObject(By.text(settingsString("arkui_app_lock_apps"))), 10000));
        for (int attempt = 0; attempt < 4
                && !mDevice.hasObject(By.text(settingsString("arkui_app_lock_timeout"))); attempt++) {
            mDevice.swipe(mDevice.getDisplayWidth() / 2, mDevice.getDisplayHeight() * 3 / 4,
                    mDevice.getDisplayWidth() / 2, mDevice.getDisplayHeight() / 3, 20);
        }
        UiObject2 delayPreference = mDevice.findObject(By.text(settingsString("arkui_app_lock_timeout")));
        assertNotNull("background delay preference must be reachable", delayPreference);
        delayPreference.click();
        UiObject2 input = mDevice.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000);
        assertNotNull(input);
        input.setText("0");
        mDevice.findObject(By.res("android", "button1")).click();
        assertTrue("invalid input must keep the dialog open", mDevice.hasObject(By.clazz("android.widget.EditText")));
        assertEquals(1, mManager.getSettings(USER, mToken).getInt(AppLockManager.KEY_TIMEOUT_MINUTES));
        input.setText("5");
        mDevice.findObject(By.res("android", "button1")).click();
        await("valid delay is persisted by Settings", () ->
                mManager.getSettings(USER, mToken).getInt(AppLockManager.KEY_TIMEOUT_MINUTES) == 5, 5000);
        mDevice.pressHome();
        openSettings();
        assertTrue("returning to management must require a new real credential",
                mDevice.wait(Until.hasObject(By.res(CREDENTIAL_INPUT)), 10000));
        authenticateApp();
        assertTrue(mDevice.wait(Until.hasObject(By.text(settingsString("arkui_app_lock_apps"))), 10000));
    }

    @Test
    public void settingsAppSelectionSearchSavesChangesAndCancelPreservesPolicy() throws Exception {
        configure(AppLockManager.MODE_TIMEOUT, false, true);
        openSettings();
        authenticateApp();

        UiObject2 search = openAppsDialog();
        search.setText(SECOND);
        mDevice.pressEnter();
        UiObject2 row = findAppSelectionRow(SECOND);
        assertFalse("search must exclude a different package", mDevice.hasObject(By.text(FIRST)));
        UiObject2 checkbox = row.findObject(By.clazz("android.widget.CheckBox"));
        assertNotNull(checkbox);
        assertTrue("the initially protected application must be selected", checkbox.isChecked());
        row.click();
        assertFalse(checkbox.isChecked());
        mDevice.findObject(By.res("android", "button1")).click();
        await("Settings saves the changed protected application list", () ->
                mManager.getSettings(USER, mToken).getStringArrayList(AppLockManager.KEY_PACKAGES)
                        .equals(new ArrayList<>(Arrays.asList(FIRST))), 5000);
        assertTrue(mManager.isPackageLocked(FIRST, USER));
        assertFalse(mManager.isPackageLocked(SECOND, USER));

        Bundle beforeCancel = mManager.getSettings(USER, mToken);
        search = openAppsDialog();
        search.setText(SECOND);
        mDevice.pressEnter();
        row = findAppSelectionRow(SECOND);
        checkbox = row.findObject(By.clazz("android.widget.CheckBox"));
        assertNotNull(checkbox);
        assertFalse("saved deselection must be reflected in the picker", checkbox.isChecked());
        row.click();
        assertTrue(checkbox.isChecked());
        mDevice.findObject(By.res("android", "button2")).click();
        assertTrue(mDevice.wait(Until.gone(By.res("android", "search_src_text")), 5000));
        Bundle afterCancel = mManager.getSettings(USER, mToken);
        assertEquals(beforeCancel.keySet(), afterCancel.keySet());
        for (String key : beforeCancel.keySet()) {
            assertEquals("cancel must not change policy field " + key,
                    beforeCancel.get(key), afterCancel.get(key));
        }
        assertFalse(mManager.isPackageLocked(SECOND, USER));
    }

    @Test
    public void cancelingAuthenticationNeverExposesPrivateActivity() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        launch(FIRST);
        awaitAuthentication();
        for (int attempt = 0; attempt < 3 && isAuthenticationVisible(); attempt++) {
            mDevice.pressBack();
            SystemClock.sleep(500);
        }
        SystemClock.sleep(500);
        assertTrue(mManager.isPackageLocked(FIRST, USER));
        assertPrivateContentAbsent(FIRST);
        assertFalse("cancel should return home without an authentication loop", isAuthenticationVisible());
    }

    @Test
    public void returningThroughRecentsRequiresNewAuthentication() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        openAndAuthenticate(FIRST);
        int task = taskId(FIRST);
        mDevice.pressHome();
        await("background lock", () -> mManager.isPackageLocked(FIRST, USER), 10000);
        ActivityTaskManager.getService().startActivityFromRecents(task, null);
        awaitAuthentication();
        assertPrivateContentAbsent(FIRST);
        authenticateApp();
        awaitVisible(FIRST);
    }

    @Test
    public void interruptedAuthenticationCanBeReopenedFromRecents() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        openAndAuthenticate(FIRST);
        int task = taskId(FIRST);
        mDevice.pressHome();
        await("background lock", () -> mManager.isPackageLocked(FIRST, USER), 10000);
        ActivityTaskManager.getService().startActivityFromRecents(task, null);
        awaitAuthentication();
        shell("am force-stop com.android.settings");
        mDevice.pressHome();
        ActivityTaskManager.getService().startActivityFromRecents(task, null);
        awaitAuthentication();
        assertPrivateContentAbsent(FIRST);
        authenticateApp();
        awaitVisible(FIRST);
    }

    @Test
    public void protectedTaskSnapshotDoesNotContainRealContent() throws Exception {
        configure(AppLockManager.MODE_SCREEN_OFF, true, true);
        openAndAuthenticate(FIRST);
        int task = taskId(FIRST);
        mDevice.pressHome();
        SystemClock.sleep(1000);
        TaskSnapshot snapshot = ActivityTaskManager.getService().getTaskSnapshot(task, false);
        assertTrue("protected task snapshots must be absent or generated from the app theme",
                snapshot == null || !snapshot.isRealSnapshot());
    }

    @Test
    public void authenticationPreservesHyperOsSmallWindowLaunch() throws Exception {
        String enabled = shell("settings get system arkui_small_window_enabled").trim();
        String mode = shell("settings get system arkui_small_window_mode").trim();
        try {
            shell("settings put system arkui_small_window_enabled 1");
            shell("settings put system arkui_small_window_mode 1");
            SystemClock.sleep(500);
            configure(AppLockManager.MODE_IMMEDIATE, true, true);
            openAndAuthenticate(FIRST);
            int taskId = taskId(FIRST);
            mDevice.pressHome();
            await("background relock before small window", () ->
                    mManager.isPackageLocked(FIRST, USER), 10000);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_PINNED_WINDOW_EXT);
            ActivityTaskManager.getService().startActivityFromRecents(taskId, options.toBundle());
            awaitAuthentication();
            assertPrivateContentAbsent(FIRST);
            authenticateApp();
            // Mode 101 is a noninteractive preview: WindowState denies app input, and its
            // scaled content is not a reliable accessibility surface. Verify the real task.
            await("authenticated original preview task is visible in mode 101", () ->
                    isFixtureTaskVisible(taskId, FIRST,
                            WindowConfiguration.WINDOWING_MODE_PINNED_WINDOW_EXT), 10000);
            Bundle authenticatedPolicy = mManager.getSettings(USER, mToken);
            assertTrue("preview authentication must leave protection enabled",
                    authenticatedPolicy.getBoolean(AppLockManager.KEY_ENABLED));
            assertTrue("the preview package must remain protected", authenticatedPolicy
                    .getStringArrayList(AppLockManager.KEY_PACKAGES).contains(FIRST));
            assertFalse(mManager.isPackageLocked(FIRST, USER));
            assertFalse("the authenticated preview must have a real unlocked grant",
                    mManager.shouldHideNotifications(FIRST, USER));
            await("preview authentication is dismissed", () -> !isAuthenticationVisible(), 10000);
        } finally {
            shell("am force-stop " + FIRST);
            restoreSystemSetting("arkui_small_window_enabled", enabled);
            restoreSystemSetting("arkui_small_window_mode", mode);
        }
    }

    @Test
    public void expandedMiniWindowHidesPrivateContentWhenLocked() throws Exception {
        String enabled = shell("settings get system arkui_small_window_enabled").trim();
        String mode = shell("settings get system arkui_small_window_mode").trim();
        try {
            shell("settings put system arkui_small_window_enabled 1");
            shell("settings put system arkui_small_window_mode 1");
            SystemClock.sleep(500);
            configure(AppLockManager.MODE_SCREEN_OFF, true, true);
            openAndAuthenticate(FIRST);
            int id = taskId(FIRST);
            mDevice.pressHome();
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_PINNED_WINDOW_EXT);
            ActivityTaskManager.getService().startActivityFromRecents(id, options.toBundle());
            ActivityManager.RunningTaskInfo target = null;
            for (ActivityManager.RunningTaskInfo task : ActivityTaskManager.getService()
                    .getTasks(100, false, false, -1)) {
                if (task.taskId == id) target = task;
            }
            assertNotNull(target);
            new WindowOrganizer().applyTransaction(new WindowContainerTransaction()
                    .setWindowingMode(target.token, WindowConfiguration.WINDOWING_MODE_MINI_WINDOW_EXT));
            awaitVisible(FIRST);
            target = null;
            for (ActivityManager.RunningTaskInfo task : ActivityTaskManager.getService()
                    .getTasks(100, false, false, -1)) {
                if (task.taskId == id) target = task;
            }
            assertNotNull(target);
            assertEquals("test must exercise the interactive expanded mini window",
                    WindowConfiguration.WINDOWING_MODE_MINI_WINDOW_EXT,
                    target.configuration.windowConfiguration.getWindowingMode());
            mManager.lockAll(USER, mToken);
            await("expanded mini app is locked", () -> mManager.isPackageLocked(FIRST, USER), 10000);
            await("expanded mini private content is hidden", () ->
                    !mDevice.hasObject(By.text("Private app content: " + FIRST)), 10000);
            options.setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_MINI_WINDOW_EXT);
            ActivityTaskManager.getService().startActivityFromRecents(id, options.toBundle());
            awaitAuthentication();
            assertPrivateContentAbsent(FIRST);
            authenticateApp();
            awaitVisible(FIRST);
            await("the authenticated expanded mini must retain its original task and mode", () ->
                    isFixtureTaskVisible(id, FIRST,
                            WindowConfiguration.WINDOWING_MODE_MINI_WINDOW_EXT), 10000);
        } finally {
            shell("am force-stop " + FIRST);
            restoreSystemSetting("arkui_small_window_enabled", enabled);
            restoreSystemSetting("arkui_small_window_mode", mode);
        }
    }

    @Test
    public void backgroundTimeoutAllowsQuickReturnThenLocksAfterOneMinute() throws Exception {
        configure(AppLockManager.MODE_TIMEOUT, false, true);
        openAndAuthenticate(FIRST);
        mDevice.pressHome();
        SystemClock.sleep(2000);
        assertFalse(mManager.isPackageLocked(FIRST, USER));
        launch(FIRST);
        awaitVisible(FIRST);
        assertFalse(isAuthenticationVisible());
        // The original deadline must be canceled while the app is genuinely visible.
        SystemClock.sleep(61000);
        assertFalse("foreground use must not be interrupted by the old background deadline",
                mManager.isPackageLocked(FIRST, USER));
        mDevice.pressHome();
        long started = SystemClock.elapsedRealtime();
        await("one minute background timeout", () -> mManager.isPackageLocked(FIRST, USER), 75000);
        assertTrue("timer must not lock before one minute",
                SystemClock.elapsedRealtime() - started >= 58000);
        launch(FIRST);
        awaitAuthentication();
        assertPrivateContentAbsent(FIRST);
    }

    @Test
    public void onlyScreenOffDoesNotLockOnHomeButRevokesOnSleep() throws Exception {
        configure(AppLockManager.MODE_SCREEN_OFF, true, true);
        openAndAuthenticate(FIRST);
        mDevice.pressHome();
        SystemClock.sleep(1000);
        assertFalse(mManager.isPackageLocked(FIRST, USER));
        IBinder previousToken = mToken;
        mDevice.sleep();
        await("screen-off lock", () -> mManager.isPackageLocked(FIRST, USER), 10000);
        expectSecurity(() -> mManager.getSettings(USER, previousToken));
        mDevice.wakeUp();
        authenticateKeyguardIfPresent();
        mToken = managementToken();
        launch(FIRST);
        awaitAuthentication();
        assertPrivateContentAbsent(FIRST);
    }

    @Test
    public void lockedNotificationsStayInAppButNotInListenerOrRanking() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        command(SECOND, "post", "locked-secret-one");
        await("app retains its notification", () -> contains(
                command(SECOND, "own", null), "active", "locked-secret-one"), 10000);
        SystemClock.sleep(750);
        Bundle observed = command(FIRST, "listener", null);
        assertFalse(contains(observed, "active", SECOND));
        assertFalse(contains(observed, "ranking", SECOND));
        assertFalse(contains(observed, "events", "locked-secret-one"));
        openAndAuthenticate(SECOND);
        await("unlock restores notification", () -> contains(
                command(FIRST, "listener", null), "active", "locked-secret-one"), 10000);
    }

    @Test
    public void lockingExistingNotificationScrubsRemovalAndRestoresLatest() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        openAndAuthenticate(SECOND);
        command(SECOND, "post", "old-private-title");
        await("unlocked notification is visible", () -> contains(
                command(FIRST, "listener", null), "active", "old-private-title"), 10000);
        command(FIRST, "clear-events", null);
        mDevice.pressHome();
        await("lock removes listener notification", () -> !contains(
                command(FIRST, "listener", null), "active", SECOND), 10000);
        assertFalse("synthetic removal must not include the old secret title",
                contains(command(FIRST, "listener", null), "events", "old-private-title"));
        command(SECOND, "post", "latest-private-title");
        SystemClock.sleep(500);
        assertFalse(contains(command(FIRST, "listener", null), "events", "latest-private-title"));
        openAndAuthenticate(SECOND);
        await("only latest notification is restored", () -> contains(
                command(FIRST, "listener", null), "active", "latest-private-title"), 10000);
        assertFalse(contains(command(FIRST, "listener", null), "active", "old-private-title"));
    }

    @Test
    public void notificationCanceledWhileLockedDoesNotReturnAfterUnlock() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        command(SECOND, "post", "canceled-private-title");
        await("notification exists in app", () -> contains(
                command(SECOND, "own", null), "active", "canceled-private-title"), 10000);
        command(SECOND, "cancel", null);
        openAndAuthenticate(SECOND);
        SystemClock.sleep(750);
        assertFalse(contains(command(FIRST, "listener", null), "active", SECOND));
    }

    @Test
    public void notificationImageUriIsRevokedOnLockAndRegrantedOnUnlock() throws Exception {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        openAndAuthenticate(SECOND);
        command(SECOND, "post-uri", "private-notification-image");
        String uri = "content://" + SECOND + ".private/icon";
        await("listener receives image URI grant", () ->
                command(FIRST, "read-uri", uri).getBoolean("readable"), 10000);
        mDevice.pressHome();
        await("locking revokes notification image URI", () ->
                !command(FIRST, "read-uri", uri).getBoolean("readable"), 10000);
        openAndAuthenticate(SECOND);
        await("unlock restores the original image URI grant", () ->
                command(FIRST, "read-uri", uri).getBoolean("readable"), 10000);
    }

    @Test
    public void turningOffNotificationHidingRestoresRecordsWhileAppStaysLocked() {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        command(SECOND, "post", "visible-by-choice");
        await("notification retained", () -> contains(
                command(SECOND, "own", null), "active", "visible-by-choice"), 10000);
        Bundle policy = mManager.getSettings(USER, mToken);
        policy.putBoolean(AppLockManager.KEY_HIDE_NOTIFICATIONS, false);
        mManager.setSettings(USER, mToken, policy);
        assertTrue(mManager.isPackageLocked(SECOND, USER));
        assertFalse(mManager.shouldHideNotifications(SECOND, USER));
        await("hiding disabled", () -> contains(
                command(FIRST, "listener", null), "active", "visible-by-choice"), 10000);
    }

    @Test
    public void disablingAppLockReleasesAllApplications() {
        configure(AppLockManager.MODE_IMMEDIATE, true, true);
        Bundle policy = mManager.getSettings(USER, mToken);
        policy.putBoolean(AppLockManager.KEY_ENABLED, false);
        mManager.setSettings(USER, mToken, policy);
        assertFalse(mManager.isPackageLocked(FIRST, USER));
        assertFalse(mManager.isPackageLocked(SECOND, USER));
        assertFalse(mManager.shouldHideNotifications(SECOND, USER));
    }

    @Test
    public void policyAndManagementTokensAreIsolatedBetweenUsers() throws Exception {
        String creation = shell("pm create-user ArkuiAppLockIsolationTest").trim();
        assertTrue("the emulator must support an isolated test user: " + creation,
                creation.matches("(?s).*created user id \\d+.*"));
        int other = Integer.parseInt(creation.replaceAll("(?s).*created user id (\\d+).*", "$1"));
        IBinder otherToken = null;
        try {
            shell("am start-user -w " + other);
            shell("pm install-existing --user " + other + " " + SECOND);
            assertTrue(shell("cmd lock_settings set-pin --user " + other + " " + mPin)
                    .contains("set to"));
            expectSecurity(() -> mManager.getSettings(other, mToken));
            expectSecurity(() -> mManager.setSettings(other, mToken, new Bundle()));
            shell("am switch-user -w " + other);
            authenticateKeyguardIfPresent();
            otherToken = mManager.completeAuthentication(
                    mManager.beginManagementAuthentication(other, mOwner));
            Bundle policy = mManager.getSettings(other, otherToken);
            policy.putBoolean(AppLockManager.KEY_ENABLED, true);
            policy.putStringArrayList(AppLockManager.KEY_PACKAGES,
                    new ArrayList<>(Arrays.asList(SECOND)));
            mManager.setSettings(other, otherToken, policy);
            assertTrue(mManager.isPackageLocked(SECOND, other));
            assertFalse(mManager.isPackageLocked(SECOND, USER));
            assertTrue(mManager.shouldHideNotifications(SECOND, other));
            assertFalse(mManager.shouldHideNotifications(SECOND, USER));
            final IBinder wrongUser = otherToken;
            expectSecurity(() -> mManager.getSettings(USER, wrongUser));
            expectSecurity(() -> mManager.setSettings(other, mToken, new Bundle()));
        } finally {
            if (otherToken != null) mManager.endManagementSession(otherToken);
            shell("am switch-user -w " + USER);
            authenticateKeyguardIfPresent();
            shell("cmd lock_settings clear --old " + mPin + " --user " + other);
            shell("am stop-user -w -f " + other);
            shell("pm remove-user " + other);
        }
    }

    private IBinder managementToken() {
        return mManager.completeAuthentication(mManager.beginManagementAuthentication(USER, mOwner));
    }

    private void configure(int mode, boolean screenOff, boolean hide) {
        Bundle policy = new Bundle();
        policy.putBoolean(AppLockManager.KEY_ENABLED, true);
        policy.putStringArrayList(AppLockManager.KEY_PACKAGES,
                new ArrayList<>(Arrays.asList(FIRST, SECOND)));
        policy.putInt(AppLockManager.KEY_LOCK_MODE, mode);
        policy.putInt(AppLockManager.KEY_TIMEOUT_MINUTES, 1);
        policy.putBoolean(AppLockManager.KEY_LOCK_ON_SCREEN_OFF, screenOff);
        policy.putBoolean(AppLockManager.KEY_HIDE_NOTIFICATIONS, hide);
        mManager.setSettings(USER, mToken, policy);
    }

    private void launch(String packageName) {
        mContext.startActivity(new Intent().setComponent(new ComponentName(packageName, ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void launchDocument(String marker) {
        mContext.startActivity(new Intent().setComponent(new ComponentName(FIRST, ACTIVITY))
                .setData(documentUri(marker)).putExtra("document_marker", marker)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
    }

    private static Uri documentUri(String marker) {
        return Uri.parse("arkui-app-lock-test://document/" + marker);
    }

    private int documentTaskId(String marker) throws Exception {
        for (ActivityManager.RunningTaskInfo task : ActivityTaskManager.getService()
                .getTasks(100, false, false, -1)) {
            if (task.baseActivity != null && FIRST.equals(task.baseActivity.getPackageName())
                    && documentUri(marker).equals(task.baseIntent.getData())) return task.taskId;
        }
        throw new AssertionError("No original task for document " + marker);
    }

    private void awaitDocumentVisible(String marker) {
        assertTrue("the requested private document " + marker + " must be displayed",
                mDevice.wait(Until.hasObject(By.text("Private document: " + marker)), 15000));
    }

    private void openSettings() {
        mContext.startActivity(new Intent().setComponent(new ComponentName("com.android.settings",
                "com.android.settings.Settings$AppLockSettingsActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private String settingsString(String name) throws Exception {
        Context settings = mContext.createPackageContext("com.android.settings", 0);
        int id = settings.getResources().getIdentifier(name, "string", "com.android.settings");
        assertTrue("Settings resource " + name, id != 0);
        return settings.getString(id);
    }

    private UiObject2 openAppsDialog() throws Exception {
        String title = settingsString("arkui_app_lock_apps");
        assertTrue(mDevice.wait(Until.hasObject(By.text(title)), 10000));
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do {
            UiObject2 search = mDevice.findObject(By.res("android", "search_src_text"));
            if (search != null) return search;
            // The first click may only show the loading toast while package enumeration runs.
            UiObject2 preference = mDevice.findObject(By.text(title));
            if (preference != null) preference.click();
            search = mDevice.wait(Until.findObject(By.res("android", "search_src_text")), 500);
            if (search != null) return search;
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("protected application picker must finish loading and open");
    }

    private UiObject2 findAppSelectionRow(String packageName) {
        // The search editor contains the same package text. Match the actual row subtitle.
        UiObject2 subtitle = mDevice.wait(Until.findObject(
                By.text(packageName).clazz("android.widget.TextView")), 5000);
        assertNotNull("search result for " + packageName, subtitle);
        // Accessibility may omit the noninteractive label column, so follow the actual parents.
        for (UiObject2 row = subtitle; row != null; row = row.getParent()) {
            if (row.isClickable() && row.findObject(By.clazz("android.widget.CheckBox")) != null) {
                return row;
            }
        }
        throw new AssertionError("search result must belong to a selectable application row");
    }

    private void openAndAuthenticate(String packageName) throws Exception {
        launch(packageName);
        awaitAuthentication();
        authenticateApp();
        awaitVisible(packageName);
    }

    private void awaitAuthentication() {
        await("trusted app lock authentication activity", this::isAuthenticationVisible, 15000);
    }

    private boolean isAuthenticationVisible() {
        try {
            if (mDevice.hasObject(By.res(CREDENTIAL_INPUT))) return true;
            for (String line : shell("dumpsys activity activities").split("\n")) {
                if ((line.contains("mResumedActivity") || line.contains("topResumedActivity"))
                        && (line.contains(CONFIRM) || line.contains(CONFIRM_COMPONENT))) return true;
            }
            return false;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void awaitVisible(String packageName) {
        assertTrue("private activity should be displayed after successful authentication",
                mDevice.wait(Until.hasObject(By.text("Private app content: " + packageName)), 15000));
    }

    private void assertPrivateContentAbsent(String packageName) {
        assertFalse("private content must be hidden before authenticating",
                mDevice.hasObject(By.text("Private app content: " + packageName)));
    }

    private void authenticateApp() throws Exception {
        UiObject2 entry = mDevice.wait(Until.findObject(
                By.res(CREDENTIAL_INPUT).enabled(true)), 15000);
        if (entry == null) {
            UiObject2 credential = mDevice.findObject(By.textContains("PIN"));
            if (credential != null && credential.isClickable()) credential.click();
            entry = mDevice.wait(Until.findObject(
                    By.res(CREDENTIAL_INPUT).enabled(true)), 5000);
        }
        assertNotNull("real device credential entry must be shown", entry);
        entry.setText(mPin);
        mDevice.pressEnter();
        UiObject2 next = mDevice.findObject(By.res("com.android.settings", "next_button"));
        if (next != null && next.isEnabled()) next.click();
        SystemClock.sleep(500);
    }

    private void clickCredentialPinButton(String resourceName) {
        UiObject2 button = mDevice.wait(Until.findObject(
                By.res("com.android.systemui", resourceName).enabled(true)), 5000);
        assertNotNull("native PIN button " + resourceName, button);
        button.click();
    }

    private void assertNativePinControlsInsideSystemSafeArea() {
        android.view.WindowMetrics metrics = mContext.getSystemService(WindowManager.class)
                .getMaximumWindowMetrics();
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        Rect safeArea = new Rect(metrics.getBounds());
        safeArea.inset(insets.left, insets.top, insets.right, insets.bottom);
        UiObject2 back = mDevice.wait(Until.findObject(
                By.res("com.android.systemui", "back_button")), 5000);
        assertNotNull("native credential return button must be visible", back);
        Rect backBounds = back.getVisibleBounds();
        Log.i("ArkuiAppLockTests", "Native PIN return bounds=" + backBounds
                + ", system safe area=" + safeArea);
        assertFalse("the return button must have a visible touch target", backBounds.isEmpty());
        assertTrue("the return button must avoid status/navigation bars and display cutouts: "
                + backBounds + " inside " + safeArea, safeArea.contains(backBounds));
        UiObject2 pad = mDevice.findObject(By.res("com.android.systemui", "pin_pad"));
        assertNotNull(pad);
        ArrayList<UiObject2> keys = new ArrayList<>(pad.findObjects(By.res(PIN_BUTTON)));
        assertEquals("all native PIN targets must remain visible after applying insets", 12,
                keys.size());
        int minimumTarget = Math.round(48 * mContext.getResources().getDisplayMetrics().density);
        for (UiObject2 key : keys) {
            Rect bounds = key.getVisibleBounds();
            assertTrue("native PIN targets must preserve their full minimum touch area: "
                    + key.getResourceName() + " " + bounds,
                    bounds.width() >= minimumTarget && bounds.height() >= minimumTarget);
            assertTrue("native PIN targets must avoid system unsafe areas: "
                    + bounds + " inside " + safeArea, safeArea.contains(bounds));
        }
    }

    private static long realPinLockoutDeadline(LockPatternUtils utils, int credentialOwner) {
        // Call only when the service owns the deadline. Legacy LockPatternUtils deadlines live
        // in SystemUI's injected instance and cannot be read from this test's new instance.
        return Math.max(utils.getLockoutAttemptDeadline(credentialOwner),
                utils.getLockoutEndTime(credentialOwner).toMillis());
    }

    private Pattern credentialCountdownPattern() throws Exception {
        Context systemUi = mContext.createPackageContext("com.android.systemui", 0);
        int id = systemUi.getResources().getIdentifier(
                "biometric_dialog_credential_too_many_attempts", "string", "com.android.systemui");
        assertTrue("SystemUI countdown string must exist", id != 0);
        String[] parts = systemUi.getString(id).split("%(?:1\\$)?d", -1);
        assertEquals("the countdown message must contain the remaining-seconds placeholder",
                2, parts.length);
        return Pattern.compile(Pattern.quote(parts[0]) + "(\\p{Nd}+)" + Pattern.quote(parts[1]));
    }

    private int credentialCountdownSeconds(Pattern countdown) {
        try {
            UiObject2 error = mDevice.findObject(By.res("com.android.systemui", "error"));
            String text = error == null ? null : error.getText();
            if (text == null) return -1;
            Matcher match = countdown.matcher(text);
            if (!match.matches()) return -1;
            return Integer.parseInt(match.group(1));
        } catch (NumberFormatException | StaleObjectException e) {
            // Rotation replaces the actual hierarchy. The next poll must obtain fresh nodes.
            return -1;
        }
    }

    private boolean hasNativePinInputState(boolean enabled) {
        try {
            UiObject2 input = mDevice.findObject(By.res("com.android.systemui", "lockPassword"));
            UiObject2 pad = mDevice.findObject(By.res("com.android.systemui", "pin_pad"));
            if (input == null || input.isEnabled() != enabled || pad == null) return false;
            ArrayList<UiObject2> keys = new ArrayList<>(pad.findObjects(By.res(PIN_BUTTON)));
            if (keys.size() != 12) return false;
            for (UiObject2 key : keys) {
                if (key.isEnabled() != enabled) return false;
            }
            return true;
        } catch (StaleObjectException e) {
            return false;
        }
    }

    private boolean isNativePinThrottled(Pattern countdown) {
        return credentialCountdownSeconds(countdown) > 0 && hasNativePinInputState(false);
    }

    private void assertNativePinInputDisabled() {
        UiObject2 input = mDevice.wait(Until.findObject(
                By.res("com.android.systemui", "lockPassword")), 5000);
        assertNotNull(input);
        assertFalse("PIN entry must remain disabled throughout lockout", input.isEnabled());
        UiObject2 pad = mDevice.wait(Until.findObject(
                By.res("com.android.systemui", "pin_pad")), 5000);
        assertNotNull(pad);
        ArrayList<UiObject2> buttons = new ArrayList<>(pad.findObjects(By.res(PIN_BUTTON)));
        assertEquals("all ten digits, delete, and confirm must be present", 12, buttons.size());
        for (UiObject2 button : buttons) {
            assertFalse("PIN pad button " + button.getResourceName() + " must remain disabled",
                    button.isEnabled());
        }
    }

    private void authenticateKeyguardIfPresent() throws Exception {
        if (mContext.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            mDevice.swipe(mDevice.getDisplayWidth() / 2, mDevice.getDisplayHeight() * 3 / 4,
                    mDevice.getDisplayWidth() / 2, mDevice.getDisplayHeight() / 4, 20);
        }
        UiObject2 key = mDevice.wait(Until.findObject(By.res("com.android.systemui", "key1")), 3000);
        if (key != null) {
            for (char digit : mPin.toCharArray()) {
                UiObject2 button = mDevice.findObject(By.res("com.android.systemui", "key" + digit));
                assertNotNull("keyguard PIN key " + digit, button);
                button.click();
            }
            UiObject2 enter = mDevice.findObject(By.res("com.android.systemui", "key_enter"));
            if (enter != null) enter.click(); else mDevice.pressEnter();
            SystemClock.sleep(500);
        }
    }

    private int taskId(String packageName) throws Exception {
        for (ActivityManager.RunningTaskInfo task : ActivityTaskManager.getService()
                .getTasks(100, false, false, -1)) {
            if (task.baseActivity != null && task.baseActivity.getPackageName().equals(packageName)) {
                return task.taskId;
            }
        }
        throw new AssertionError("No task for " + packageName);
    }

    private boolean isFixtureTaskVisible(int taskId, String packageName, int windowingMode) {
        try {
            for (ActivityManager.RunningTaskInfo task : ActivityTaskManager.getService()
                    .getTasks(100, false, false, -1)) {
                if (task.taskId == taskId && task.userId == USER && task.baseActivity != null
                        && packageName.equals(task.baseActivity.getPackageName())) {
                    return task.isVisible && task.configuration.windowConfiguration
                            .getWindowingMode() == windowingMode;
                }
            }
            return false;
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private boolean isFixtureActivityTop(int taskId, String packageName, String activityClass) {
        ComponentName expected = new ComponentName(packageName, activityClass);
        try {
            for (ActivityManager.RunningTaskInfo task : ActivityTaskManager.getService()
                    .getTasks(100, false, false, -1)) {
                if (task.taskId == taskId && task.userId == USER && task.baseActivity != null
                        && packageName.equals(task.baseActivity.getPackageName())) {
                    return task.isVisible && task.isFocused && expected.equals(task.topActivity);
                }
            }
            return false;
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private void removeFixtureTasks() throws Exception {
        // Force-stop keeps recent task configuration, including popup mode. Remove only the
        // fixture-owned tasks for this user so the next test starts with a fresh launch.
        Set<Integer> taskIds = new HashSet<>();
        for (ActivityManager.RunningTaskInfo task : ActivityTaskManager.getService()
                .getTasks(100, false, false, -1)) {
            if (isFixtureTask(task)) taskIds.add(task.taskId);
        }
        for (ActivityManager.RecentTaskInfo task : ActivityTaskManager.getService()
                .getRecentTasks(100, 0, USER).getList()) {
            if (isFixtureTask(task)) taskIds.add(task.taskId);
        }
        for (int taskId : taskIds) ActivityTaskManager.getService().removeTask(taskId);
    }

    private static boolean isFixtureTask(TaskInfo task) {
        if (task.userId != USER) return false;
        String packageName = null;
        if (task.baseIntent != null) {
            ComponentName component = task.baseIntent.getComponent();
            packageName = component == null ? task.baseIntent.getPackage()
                    : component.getPackageName();
        }
        if (packageName == null && task.baseActivity != null) {
            packageName = task.baseActivity.getPackageName();
        }
        return FIRST.equals(packageName) || SECOND.equals(packageName);
    }

    private Bundle command(String packageName, String operation, String argument) {
        return mContext.getContentResolver().call(Uri.parse("content://" + packageName),
                operation, argument, null);
    }

    private String shell(String command) throws Exception {
        return mDevice.executeShellCommand(command);
    }

    private void restoreSystemSetting(String key, String value) throws Exception {
        shell("settings " + ("null".equals(value) ? "delete system " + key
                : "put system " + key + " " + value));
    }

    private static boolean contains(Bundle value, String key, String needle) {
        ArrayList<String> list = value.getStringArrayList(key);
        if (list == null) return false;
        for (String item : list) if (item.contains(needle)) return true;
        return false;
    }

    private static void await(String description, BooleanSupplier condition, long timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(100);
        }
        assertTrue(description, condition.getAsBoolean());
    }

    private static void expectSecurity(Runnable operation) {
        try { operation.run(); fail("Expected SecurityException"); }
        catch (SecurityException expected) { }
    }

    private static void expectIllegal(Runnable operation) {
        try { operation.run(); fail("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
}
