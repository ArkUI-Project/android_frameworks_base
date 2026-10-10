/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.assistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal object AssistantMotion {
    val spatial = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val expand = 260
    const val enter = 180
    const val exit = 140
    const val change = 120

    fun content(): ContentTransform =
        (fadeIn(tween(enter, delayMillis = 40)) +
            slideInVertically(tween(expand, easing = spatial)) { it / 12 }) togetherWith
            (fadeOut(tween(change)) + slideOutVertically(tween(exit)) { -it / 20 })
}

/** Reveal from the fixed composer edge, without a second parent size animation. */
@Composable
internal fun PanelReveal(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible,
        enter = expandVertically(tween(AssistantMotion.expand, easing = AssistantMotion.spatial),
            expandFrom = Alignment.Bottom, clip = false) + fadeIn(tween(AssistantMotion.enter, 40)),
        exit = shrinkVertically(tween(AssistantMotion.expand, easing = AssistantMotion.spatial),
            shrinkTowards = Alignment.Bottom, clip = false) + fadeOut(tween(AssistantMotion.change))) {
        content()
    }
}

@Composable
internal fun AnimatedGlyph(icon: ImageVector, description: String?,
    modifier: Modifier = Modifier.size(24.dp), tint: Color = LocalContentColor.current) {
    AnimatedContent(icon, modifier = modifier, contentKey = { it.name },
        contentAlignment = Alignment.Center,
        transitionSpec = {
            ((fadeIn(tween(AssistantMotion.change)) + scaleIn(tween(AssistantMotion.enter), initialScale = 0.8f))
                togetherWith (fadeOut(tween(90)) + scaleOut(tween(AssistantMotion.change), targetScale = 0.8f)))
                .using(SizeTransform(clip = false))
        }, label = "action icon") {
        Icon(it, description, modifier = modifier, tint = tint)
    }
}
