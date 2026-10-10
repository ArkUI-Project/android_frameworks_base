/*
 * Modified by the ArkUI Project in 2026 to default to separate shade panels.
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.statusbar.policy

import android.content.res.Resources
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.res.R
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject

/**
 * Source of truth for split shade state: should or should not use split shade based on orientation,
 * screen width, and flags.
 */
@SysUISingleton
class SplitShadeStateControllerImpl @Inject constructor(
    private val featureFlags: FeatureFlags,
    private val secureSettings: SecureSettings,
) :
    SplitShadeStateController {

    @Deprecated(
        message = "This is deprecated, please use ShadeInteractor#isSplitShade instead",
        replaceWith =
            ReplaceWith(
                "shadeInteractor.isSplitShade",
                "com.android.systemui.shade.domain.interactor.ShadeInteractor",
            ),
    )
    override fun shouldUseSplitNotificationShade(resources: Resources): Boolean {
        // On phones the chosen panel style also applies in landscape. Keep the existing
        // side-by-side layout on tablets and the inner display of a foldable.
        if (resources.configuration.smallestScreenWidthDp < 600 &&
            secureSettings.getIntForUser(Settings.Secure.DUAL_SHADE, 1,
                UserHandle.USER_CURRENT) != 0) {
            return false
        }
        return (resources.getBoolean(R.bool.config_use_split_notification_shade) ||
            (featureFlags.isEnabled(Flags.LOCKSCREEN_ENABLE_LANDSCAPE) &&
                resources.getBoolean(R.bool.force_config_use_split_notification_shade)))
    }
}
