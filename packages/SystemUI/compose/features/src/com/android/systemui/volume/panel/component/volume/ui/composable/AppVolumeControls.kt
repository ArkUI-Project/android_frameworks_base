/* Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.volume.panel.component.volume.ui.composable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.PlatformSliderDefaults
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon as DrawableIcon
import com.android.systemui.res.R
import com.android.systemui.volume.AppVolumeController
import com.android.systemui.volume.AppVolumeEntry
import com.android.systemui.volume.AppVolumeState
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AppVolumeSliderState
import kotlin.math.roundToInt

@Composable
fun AppVolumeControls(controller: AppVolumeController) {
    val flow = remember(controller) { controller.observe() }
    val state by flow.collectAsStateWithLifecycle(initialValue = AppVolumeState(-1, emptyList()))
    var choosing by remember(state.userId) { mutableStateOf(false) }
    var installed by remember(state.userId) { mutableStateOf<List<AppVolumeEntry>>(emptyList()) }
    LaunchedEffect(choosing, state.userId) {
        if (choosing && state.userId >= 0) installed = controller.installedApps(state.userId)
    }
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.arkui_app_volume_title), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { controller.reset(state.userId) }, enabled = state.apps.any { it.isSaved }) {
                Text(stringResource(R.string.arkui_app_volume_reset))
            }
        }
        Text(stringResource(R.string.arkui_app_volume_summary),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (app in state.apps) {
            androidx.compose.runtime.key(state.userId, app.uid) {
                var level by remember { mutableFloatStateOf(app.percent.toFloat()) }
                var dragging by remember { mutableStateOf(false) }
                val toggleMute = {
                    level = if (level == 0f) 100f else 0f
                    controller.setLevel(app, state.userId, level.roundToInt())
                }
                LaunchedEffect(app.percent) { if (!dragging) level = app.percent.toFloat() }
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DrawableIcon(icon = Icon.Loaded(app.icon, null), tint = Color.Unspecified, modifier = Modifier.size(28.dp))
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(app.label, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(when {
                                    app.isPlaying -> R.string.arkui_app_volume_playing
                                    app.hasSoundEffects -> R.string.arkui_app_volume_effects
                                    else -> R.string.arkui_app_volume_saved
                                }), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${level.roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = toggleMute) { Text(stringResource(if (level == 0f) R.string.arkui_app_volume_unmute
                                else R.string.arkui_app_volume_mute)) }
                        }
                        VolumeSlider(state = AppVolumeSliderState(app, level),
                            onValueChange = { dragging = true; level = it; controller.setLevel(app, state.userId, it.roundToInt()) },
                            onValueChangeFinished = { dragging = false; controller.setLevel(app, state.userId, level.roundToInt()) },
                            onIconTapped = toggleMute, sliderColors = PlatformSliderDefaults.defaultPlatformSliderColors(),
                            hapticsViewModelFactory = null, showLabel = false)
                        if (app.isSaved && !app.isPlaying) {
                            TextButton(onClick = { controller.remove(app, state.userId) }) {
                                Text(stringResource(R.string.arkui_app_volume_remove))
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { choosing = !choosing }, enabled = state.userId >= 0,
            modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (choosing) R.string.arkui_app_volume_close_picker else R.string.arkui_app_volume_add))
        }
        AnimatedVisibility(choosing) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (app in installed.filter { candidate -> state.apps.none { it.uid == candidate.uid } }) {
                    Surface(onClick = { controller.setLevel(app, state.userId, 100); choosing = false },
                        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DrawableIcon(icon = Icon.Loaded(app.icon, null), tint = Color.Unspecified, modifier = Modifier.size(28.dp))
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

