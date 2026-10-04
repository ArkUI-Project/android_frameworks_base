/*
 * Copyright (C) 2026 The ArkUI Project
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

package com.android.systemui.shade.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import com.android.systemui.shade.ShadeTailMotion

class ShadeTailState {
    var enabled by mutableStateOf(false)
    var reveal by mutableFloatStateOf(1f)
    var tail by mutableFloatStateOf(1f)
}

val LocalShadeTailMotion = staticCompositionLocalOf<ShadeTailState?> { null }

/** Reads animation state only in the drawing phase, without measuring or recomposing each frame. */
@Composable
fun Modifier.shadeTail(strength: Float = 1f): Modifier {
    val motion = LocalShadeTailMotion.current ?: return this
    if (!motion.enabled) return this
    val position = remember { floatArrayOf(0f) }
    return onPlaced { position[0] = it.positionInRoot().y }
        .graphicsLayer {
            if (motion.enabled) {
                translationY =
                    ShadeTailMotion.translation(
                        motion.reveal, motion.tail, position[0] + size.height / 2f, density
                    ) * strength
                scaleX = 1f + (ShadeTailMotion.scale(motion.reveal, motion.tail) - 1f) * strength
                scaleY = 1f + (ShadeTailMotion.scaleY(motion.reveal, motion.tail) - 1f) * strength
            } else {
                translationY = 0f
                scaleX = 1f
                scaleY = 1f
            }
        }
}
