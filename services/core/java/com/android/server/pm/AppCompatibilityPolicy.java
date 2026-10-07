/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.server.pm;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.SystemProperties;
import android.provider.Settings;

import com.android.internal.arkui.CompatibilitySettings;
import com.android.server.pm.pkg.AndroidPackage;

/** Owner-controlled APK installation options, without changing signing-based permission checks. */
final class AppCompatibilityPolicy {
    // SettingsProvider starts after package scanning. Mirror this opt-in for boot-time rescans.
    private static final String SKIP_APK_VERIFICATION_PROPERTY =
            "persist.sys.arkui.skip_apk_verify";
    private static volatile boolean sAllowDifferentSigner;
    private static volatile boolean sSkipApkSignatureVerification =
            SystemProperties.getBoolean(SKIP_APK_VERIFICATION_PROPERTY, false);
    private static volatile boolean sAllowDowngrade;

    private AppCompatibilityPolicy() {}

    // SettingsProvider is ready at this point. Never query it under the package manager lock.
    static void start(ContentResolver resolver, Handler handler) {
        final ContentObserver observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) {
                sAllowDifferentSigner = Settings.Global.getInt(resolver,
                        CompatibilitySettings.DISABLE_UPDATE_SIGNATURE_CHECK, 0) == 1;
                sSkipApkSignatureVerification = Settings.Global.getInt(resolver,
                        CompatibilitySettings.DISABLE_APK_SIGNATURE_VERIFICATION, 0) == 1;
                if (SystemProperties.getBoolean(SKIP_APK_VERIFICATION_PROPERTY, false)
                        != sSkipApkSignatureVerification) {
                    SystemProperties.set(SKIP_APK_VERIFICATION_PROPERTY,
                            Boolean.toString(sSkipApkSignatureVerification));
                }
                sAllowDowngrade = Settings.Global.getInt(resolver,
                        CompatibilitySettings.ALLOW_APP_DOWNGRADE, 0) == 1;
            }
        };
        resolver.registerContentObserver(Settings.Global.getUriFor(
                CompatibilitySettings.DISABLE_UPDATE_SIGNATURE_CHECK), false, observer);
        resolver.registerContentObserver(Settings.Global.getUriFor(
                CompatibilitySettings.DISABLE_APK_SIGNATURE_VERIFICATION), false, observer);
        resolver.registerContentObserver(Settings.Global.getUriFor(
                CompatibilitySettings.ALLOW_APP_DOWNGRADE), false, observer);
        observer.onChange(false);
    }

    static boolean canReplaceSigningKey(PackageSetting existing,
            SharedUserSetting sharedUser, PackageSetting disabledSystemPackage) {
        return sAllowDifferentSigner && sharedUser == null && disabledSystemPackage == null
                && isOrdinaryApp(existing);
    }

    static boolean shouldSkipApkSignatureVerification() {
        return sSkipApkSignatureVerification;
    }

    static boolean canDowngrade(PackageSetting existing) {
        return sAllowDowngrade && isOrdinaryApp(existing);
    }

    private static boolean isOrdinaryApp(PackageSetting existing) {
        if (existing == null || existing.isSystem() || existing.isUpdatedSystemApp()
                || existing.isPrivileged() || existing.isApex() || existing.hasSharedUser()) {
            return false;
        }
        final AndroidPackage pkg = existing.getPkg();
        return pkg != null && !pkg.isSignedWithPlatformKey()
                && !pkg.isSdkLibrary() && !pkg.isStaticSharedLibrary();
    }
}
