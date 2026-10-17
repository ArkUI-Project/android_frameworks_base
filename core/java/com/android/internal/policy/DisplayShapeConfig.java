/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.policy;

import android.content.res.Resources;

import com.android.internal.R;

/**
 * Display-specific calibration applied by a reversible runtime resource overlay.
 * @hide
 */
public final class DisplayShapeConfig {
    private DisplayShapeConfig() { }

    private static boolean matches(Resources res, String uniqueId) {
        return uniqueId != null && !uniqueId.isEmpty()
                && uniqueId.equals(res.getString(R.string.config_arkuiDisplayShapeUniqueId));
    }

    public static int roundedCornerRadius(Resources res, String uniqueId) {
        return matches(res, uniqueId)
                ? res.getDimensionPixelSize(R.dimen.config_arkuiRoundedCornerRadius) : -1;
    }

    public static boolean overridesCutout(Resources res, String uniqueId) {
        return matches(res, uniqueId) && res.getBoolean(R.bool.config_arkuiDisplayCutoutOverride);
    }

    public static String cutoutPath(Resources res) {
        return res.getString(R.string.config_arkuiDisplayCutoutPath);
    }
}
