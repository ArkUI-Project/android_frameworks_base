/*
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
 *
 */

package com.android.systemui.keyguard.ui.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.systemui.arkui.depth.DepthWallpaperRenderer

/** Provides a container for all keyguard ui content. */
class KeyguardRootView(
    context: Context,
    attrs: AttributeSet?,
) :
    ConstraintLayout(
        context,
        attrs,
    ) {
    private val depthWallpaper = DepthWallpaperRenderer(this)
    private val depthClockViews = mutableMapOf<View, Boolean>()

    fun setDepthClockViews(large: List<View>, small: List<View>) {
        depthWallpaper.clocksChanged()
        depthClockViews.clear()
        large.forEach { depthClockViews[it] = true }
        small.forEach { depthClockViews[it] = false }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        depthWallpaper.attach()
    }

    override fun onDetachedFromWindow() {
        depthWallpaper.detach()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        depthWallpaper.geometryChanged()
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val save = depthClockViews[child]?.let { depthWallpaper.beginClock(canvas, child, it) } ?: -1
        val drawn = super.drawChild(canvas, child, drawingTime)
        depthWallpaper.endClock(canvas, save)
        return drawn
    }
}
