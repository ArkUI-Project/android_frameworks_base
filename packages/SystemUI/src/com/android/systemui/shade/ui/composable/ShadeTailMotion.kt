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

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import com.android.systemui.shade.ShadeTailMotion

class ShadeTailState {
    var enabled by mutableStateOf(false)
        private set
    var frame by mutableStateOf(ShadeTailMotion.Frame.DISABLED)
        private set

    private data class Geometry(
        val top: Float,
        val height: Float,
        val left: Float,
        val width: Float,
    )

    private val surfaces = linkedMapOf<Any, Geometry>()
    private var geometryRevision by mutableIntStateOf(0)
    private var indexedRevision = -1
    private val indices = mutableMapOf<Any, Int>()
    private var tops = floatArrayOf()
    private var heights = floatArrayOf()
    private var lefts = floatArrayOf()
    private var widths = floatArrayOf()
    private var cachedFrame: ShadeTailMotion.Frame? = null
    private var cachedRevision = -1
    private var cachedDensity = 0f
    private var cachedTransforms = emptyArray<ShadeTailMotion.Transform>()

    fun update(next: ShadeTailMotion.Frame) {
        // Layers are already installed when this changes, so the zero frame affects this draw.
        enabled = next.enabled
        frame = if (next.enabled) next else ShadeTailMotion.Frame.DISABLED
    }

    fun reset() {
        update(ShadeTailMotion.Frame.DISABLED)
        clearGeometry()
    }

    internal fun place(surface: Any, top: Float, height: Float, left: Float, width: Float) {
        val geometry = Geometry(top, height, left, width)
        if (surfaces[surface] != geometry) {
            surfaces[surface] = geometry
            geometryRevision++
        }
    }

    internal fun unregister(surface: Any) {
        if (surfaces.remove(surface) != null) geometryRevision++
    }

    internal fun transform(
        surface: Any,
        fallbackHeight: Float,
        density: Float,
    ): ShadeTailMotion.Transform {
        val currentFrame = frame
        val revision = geometryRevision
        if (indexedRevision != revision) {
            indices.clear()
            tops = FloatArray(surfaces.size)
            heights = FloatArray(surfaces.size)
            lefts = FloatArray(surfaces.size)
            widths = FloatArray(surfaces.size)
            surfaces.entries.forEachIndexed { index, (key, geometry) ->
                indices[key] = index
                tops[index] = geometry.top
                heights[index] = geometry.height
                lefts[index] = geometry.left
                widths[index] = geometry.width
            }
            indexedRevision = revision
        }
        if (cachedFrame !== currentFrame || cachedRevision != revision || cachedDensity != density) {
            // Constrain rows in each column together while keeping tall media beside the tiles.
            // Use a single result for brightness, media, tiles and both footer surfaces.
            cachedTransforms = currentFrame.transforms(tops, heights, lefts, widths, density)
            cachedFrame = currentFrame
            cachedRevision = revision
            cachedDensity = density
        }
        return indices[surface]?.let { cachedTransforms[it] }
            ?: currentFrame.transform(0f, fallbackHeight, density)
    }

    private fun clearGeometry() {
        if (surfaces.isNotEmpty()) {
            surfaces.clear()
            geometryRevision++
        }
        indices.clear()
        tops = floatArrayOf()
        heights = floatArrayOf()
        lefts = floatArrayOf()
        widths = floatArrayOf()
        indexedRevision = -1
        cachedFrame = null
        cachedRevision = -1
        cachedTransforms = emptyArray()
    }
}

val LocalShadeTailMotion = staticCompositionLocalOf<ShadeTailState?> { null }

/** Reads animation state only in the drawing phase, without measuring or recomposing each frame. */
@Composable
fun Modifier.shadeTail(): Modifier {
    val motion = LocalShadeTailMotion.current ?: return this
    val surface = remember(motion) { Any() }
    DisposableEffect(motion, surface) {
        onDispose { motion.unregister(surface) }
    }
    return onPlaced {
            val position = it.positionInRoot()
            motion.place(
                surface,
                position.y,
                it.size.height.toFloat(),
                position.x,
                it.size.width.toFloat(),
            )
        }
        .pointerInput(motion) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (
                    motion.enabled &&
                        motion.transform(surface, size.height.toFloat(), density).alpha <= 0f
                ) {
                    down.consume()
                }
            }
        }
        .graphicsLayer {
            if (motion.enabled) {
                val transform = motion.transform(surface, size.height, density)
                alpha = transform.alpha
                translationY = transform.translationY
                scaleX = transform.scale
                scaleY = transform.scale
            } else {
                alpha = 1f
                translationY = 0f
                scaleX = 1f
                scaleY = 1f
            }
        }
}
