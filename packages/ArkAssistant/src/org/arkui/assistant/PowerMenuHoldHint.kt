/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.assistant

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.arkui.assistant.protocol.AssistantInvocation

@Composable
internal fun PowerMenuHoldHint(deadline: Long) {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(deadline) {
        if (deadline == 0L) return@LaunchedEffect
        val duration = AssistantInvocation.POWER_MENU_HOLD_DURATION_MILLIS
        // Follow actual held time, even when animations are disabled or their speed is changed.
        do {
            val remaining = (deadline - SystemClock.uptimeMillis()).coerceIn(0L, duration)
            progress = 1f - remaining.toFloat() / duration
            if (remaining == 0L) break
            withFrameNanos { }
        } while (true)
    }
    Box(Modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
        .padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(deadline != 0L,
            enter = fadeIn(tween(AssistantMotion.enter)) +
                slideInVertically(tween(AssistantMotion.enter)) { -it / 3 },
            exit = fadeOut(tween(AssistantMotion.exit)) +
                slideOutVertically(tween(AssistantMotion.exit)) { -it / 3 }) {
            Surface(Modifier.widthIn(max = 380.dp).fillMaxWidth(),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 6.dp) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.power_menu_hold_hint),
                            style = MaterialTheme.typography.labelLarge)
                    }
                    LinearProgressIndicator(progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
        }
    }
}
