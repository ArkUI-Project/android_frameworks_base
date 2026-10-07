/* Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel

import com.android.systemui.common.shared.model.Icon
import com.android.systemui.haptics.slider.SliderHapticFeedbackFilter
import com.android.systemui.volume.AppVolumeEntry
import kotlin.math.roundToInt

data class AppVolumeSliderState(val app: AppVolumeEntry, override val value: Float) : SliderState {
    override val valueRange = 0f..100f
    override val step = 1f
    override val hapticFilter = SliderHapticFeedbackFilter()
    override val icon = Icon.Loaded(app.icon, null)
    override val isIconTinted = false
    override val isEnabled = true
    override val label = app.label
    override val a11yClickDescription: String? = null
    override val a11yStateDescription = "${value.roundToInt()}%"
    override val a11yContentDescription = app.label
    override val disabledMessage: String? = null
    override val isMutable = true
}
