/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.shade

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.IdRes
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Trace
import android.os.Trace.TRACE_TAG_APP
import android.provider.AlarmClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.DisplayCutout
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.TextView
import android.window.SurfaceSyncGroup
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.app.animation.Interpolators
import com.android.compose.theme.PlatformTheme
import com.android.keyguard.AlphaOptimizedLinearLayout
import com.android.systemui.Dumpable
import com.android.systemui.Flags.notificationShadeBlur
import com.android.systemui.animation.ShadeInterpolation
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterView.MODE_ESTIMATE
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.dump.DumpManager
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.ChipVisibilityListener
import com.android.systemui.qs.HeaderPrivacyIconsController
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeHeaderController.Companion.HEADER_TRANSITION_ID
import com.android.systemui.shade.ShadeHeaderController.Companion.LARGE_SCREEN_HEADER_CONSTRAINT
import com.android.systemui.shade.ShadeHeaderController.Companion.LARGE_SCREEN_HEADER_TRANSITION_ID
import com.android.systemui.shade.ShadeHeaderController.Companion.QQS_HEADER_CONSTRAINT
import com.android.systemui.shade.ShadeHeaderController.Companion.QS_HEADER_CONSTRAINT
import com.android.systemui.shade.ShadeViewProviderModule.Companion.SHADE_HEADER
import com.android.systemui.shade.carrier.ShadeCarrierGroup
import com.android.systemui.shade.carrier.ShadeCarrierGroupController
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import com.android.systemui.statusbar.core.NewStatusBarIcons
import com.android.systemui.statusbar.core.RudimentaryBattery
import com.android.systemui.statusbar.data.repository.StatusBarContentInsetsProviderStore
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.phone.StatusOverlayHoverListenerFactory
import com.android.systemui.statusbar.phone.domain.interactor.IsAreaDark
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import com.android.systemui.statusbar.pipeline.battery.ui.composable.BatteryWithChargeStatus
import com.android.systemui.statusbar.pipeline.battery.ui.composable.BatteryWithPercent
import com.android.systemui.statusbar.pipeline.battery.ui.composable.ShowPercentMode
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryNextToPercentViewModel
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.view.SystemStatusIconsLayoutHelper
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.statusbar.policy.VariableDateView
import com.android.systemui.statusbar.policy.VariableDateViewController
import com.android.systemui.util.ViewController
import dagger.Lazy
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Controller for QS header.
 *
 * [header] is a [MotionLayout] that has two transitions:
 * * [HEADER_TRANSITION_ID]: [QQS_HEADER_CONSTRAINT] <-> [QS_HEADER_CONSTRAINT] for portrait
 *   handheld device configuration.
 * * [LARGE_SCREEN_HEADER_TRANSITION_ID]: [LARGE_SCREEN_HEADER_CONSTRAINT] for all other
 *   configurations
 */
@SysUISingleton
class ShadeHeaderController
@Inject
constructor(
    @Named(SHADE_HEADER) private val header: MotionLayout,
    private val statusBarIconController: StatusBarIconController,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val privacyIconsController: HeaderPrivacyIconsController,
    private val statusBarContentInsetsProviderStore: StatusBarContentInsetsProviderStore,
    @ShadeDisplayAware private val configurationController: ConfigurationController,
    @ShadeDisplayAware private val context: Context,
    private val shadeDisplaysRepositoryLazy: Lazy<ShadeDisplaysRepository>,
    private val variableDateViewControllerFactory: VariableDateViewController.Factory,
    @Named(SHADE_HEADER) private val batteryMeterViewController: BatteryMeterViewController,
    private val unifiedBatteryViewModelFactory: BatteryViewModel.BasedOnUserSetting.Factory,
    private val tandemBatteryViewModelFactory: BatteryNextToPercentViewModel.Factory,
    private val dumpManager: DumpManager,
    private val shadeCarrierGroupControllerBuilder: ShadeCarrierGroupController.Builder,
    private val combinedShadeHeadersConstraintManager: CombinedShadeHeadersConstraintManager,
    private val demoModeController: DemoModeController,
    private val qsBatteryModeController: QsBatteryModeController,
    private val nextAlarmController: NextAlarmController,
    private val activityStarter: ActivityStarter,
    private val statusOverlayHoverListenerFactory: StatusOverlayHoverListenerFactory,
) : ViewController<View>(header), Dumpable {

    private val statusBarContentInsetsProvider
        get() =
            statusBarContentInsetsProviderStore.forDisplay(
                if (ShadeWindowGoesAround.isEnabled) {
                    // ShadeDisplaysRepository is the source of truth for display id when
                    // ShadeWindowGoesAround.isEnabled
                    shadeDisplaysRepositoryLazy.get().pendingDisplayId.value
                } else {
                    context.displayId
                }
            )

    companion object {
        /** IDs for transitions and constraints for the [MotionLayout]. */
        @VisibleForTesting internal val HEADER_TRANSITION_ID = R.id.header_transition

        @VisibleForTesting
        internal val LARGE_SCREEN_HEADER_TRANSITION_ID = R.id.large_screen_header_transition
        @VisibleForTesting internal val QQS_HEADER_CONSTRAINT = R.id.qqs_header_constraint
        @VisibleForTesting internal val QS_HEADER_CONSTRAINT = R.id.qs_header_constraint

        @VisibleForTesting
        internal val LARGE_SCREEN_HEADER_CONSTRAINT = R.id.large_screen_header_constraint
        private val NOTIFICATION_HEADER_CONSTRAINT = R.id.ark_dual_notification_header_constraint

        @VisibleForTesting internal val DEFAULT_CLOCK_INTENT = Intent(AlarmClock.ACTION_SHOW_ALARMS)

        private fun Int.stateToString() =
            when (this) {
                QQS_HEADER_CONSTRAINT -> "QQS Header"
                QS_HEADER_CONSTRAINT -> "QS Header"
                LARGE_SCREEN_HEADER_CONSTRAINT -> "Large Screen Header"
                NOTIFICATION_HEADER_CONSTRAINT -> "Notification Center Header"
                else -> "Unknown state $this"
            }
    }

    var shadeCollapseAction: Runnable? = null

    private lateinit var iconManager: TintedIconManager
    private lateinit var carrierIconSlots: List<String>
    private lateinit var mShadeCarrierGroupController: ShadeCarrierGroupController

    private val batteryIcon: BatteryMeterView = header.requireViewById(R.id.batteryRemainingIcon)
    private val clock: Clock = header.requireViewById(R.id.clock)
    private val date: TextView = header.requireViewById(R.id.date)
    private val iconContainer: StatusIconContainer = header.requireViewById(R.id.statusIcons)
    private val mShadeCarrierGroup: ShadeCarrierGroup = header.requireViewById(R.id.carrier_group)
    private val systemIconsHoverContainer: View =
        header.requireViewById(R.id.hover_system_icons_container)
    private val originalHeaderHeight = header.layoutParams.height
    private val originalClockEllipsize = clock.ellipsize
    private val headerSystemIcons: View = header.requireViewById(R.id.shade_header_system_icons)
    private val privacyContainer: View = header.requireViewById(R.id.privacy_container)
    private val originalHeaderClipChildren = header.clipChildren
    private val originalHeaderClipToPadding = header.clipToPadding
    private val originalHeaderAccessibility = header.importantForAccessibility
    private val originalClockAccessibility = clock.importantForAccessibility
    private val originalDateAccessibility = date.importantForAccessibility
    private val originalCarrierAccessibility = mShadeCarrierGroup.importantForAccessibility
    private val originalIconsAccessibility = headerSystemIcons.importantForAccessibility
    private val originalPrivacyAccessibility = privacyContainer.importantForAccessibility
    private var nativeClockPivotX = clock.pivotX
    private var nativeClockPivotY = clock.pivotY
    private var nativeClockClickable = clock.isClickable

    private var roundedCorners = 0
    private var cutout: DisplayCutout? = null
    private var lastInsets: WindowInsets? = null
    private var nextAlarmIntent: PendingIntent? = null

    private val showBatteryEstimate = MutableStateFlow(false)

    private var qsDisabled = false
    private var visible = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            updateListeners()
        }

    private var customizing = false
        set(value) {
            if (field != value) {
                field = value
                updateVisibility()
            }
        }
    private var customizerAnimating = false

    /**
     * Whether the QQS/QS part of the shade is visible. This is particularly important in
     * Lockscreen, as the shade is visible but QS is not.
     */
    var qsVisible = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            onShadeExpandedChanged()
        }

    /**
     * Whether we are in a configuration with large screen width. In this case, the header is a
     * single line.
     */
    var largeScreenActive = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            onHeaderStateChanged()
        }

    private var standalonePanel = false
    private var separatedShadeEnabled = false
    private var showingControlCenter = false
    private var separatedExpandedHeight = 0f
    private var separatedTransitionProgress = 0f
    private var separatedPropertiesApplied = false
    private var statusBarView: View? = null
    private var statusClockId = View.NO_ID
    private var lastStatusClockView: TextView? = null
    private var lastStatusClockAnchor: ClockAnchor? = null
    private var lastHeaderClockAnchor: ClockAnchor? = null
    private var clockTypographyKey: ClockTypographyKey? = null
    private val statusBarLayers = mutableListOf<StatusBarLayer>()
    private val statusBarMasks = mutableListOf<StatusBarMask>()
    private var statusBarSourceReveal = 1f
    private var lastStatusHandoffSynchronized = false
    private var statusHandoffCount = 0
    private val statusBarAnchorViews = mutableListOf<View>()
    private val separatedLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (statusBarView != null) lastStatusClockAnchor = statusClockAnchor()
        applySeparatedShadeTransition()
    }
    private val clockLayoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        nativeClockPivotX = if (v.isLayoutRtl) v.width.toFloat() else 0f
        nativeClockPivotY = v.height.toFloat() / 2f
        v.pivotX = nativeClockPivotX
        v.pivotY = nativeClockPivotY
        applySeparatedShadeTransition()
    }
    private var privacyChipVisible = false
    private var publishedStandaloneHeight: Int? = null
    var onHeaderHeightChanged: (() -> Unit)? = null

    val standaloneLayoutHeight: Int?
        get() = if (standalonePanel) standaloneHeight() else null

    /** Only the separated notification page uses its own header layout. */
    fun setStandalonePanel(enabled: Boolean, showingControlCenter: Boolean) {
        val notificationPanel = enabled && !showingControlCenter
        if (separatedShadeEnabled == enabled && this.showingControlCenter == showingControlCenter &&
            standalonePanel == notificationPanel) return
        restoreSeparatedProperties()
        separatedShadeEnabled = enabled
        this.showingControlCenter = showingControlCenter
        standalonePanel = notificationPanel
        updateStandaloneTypography()
        updateCarrierGroupPadding()
        updateTransition()
        if (::mShadeCarrierGroupController.isInitialized) updateListeners()
        header.requestApplyInsets()
        applySeparatedShadeTransition()
    }

    /** The actual collapsed status bar supplies anchors for any configured clock position. */
    fun setStatusBarView(view: View) {
        if (statusBarView === view) {
            applySeparatedShadeTransition()
            return
        }
        statusBarView?.let { clearStatusBarView(it) }
        statusBarView = view
        for (id in intArrayOf(R.id.status_bar_start_side_container, R.id.centered_area,
            R.id.status_bar_end_side_container)) {
            view.findViewById<View>(id)?.let {
                statusBarLayers.add(StatusBarLayer(it, it.alpha, it.importantForAccessibility))
            }
        }
        statusBarAnchorViews.add(view)
        for (id in intArrayOf(R.id.clock, R.id.clock_center, R.id.clock_right, R.id.system_icons)) {
            view.findViewById<View>(id)?.let {
                statusBarAnchorViews.add(it)
                statusBarMasks.add(StatusBarMask(it, it.clipBounds?.let(::Rect)))
            }
        }
        statusBarAnchorViews.forEach { it.addOnLayoutChangeListener(separatedLayoutListener) }
        // Cache the configured clock while the shade is closed, before its native owner hides it.
        lastStatusClockAnchor = statusClockAnchor()
        applySeparatedShadeTransition()
    }

    fun clearStatusBarView(view: View) {
        if (statusBarView !== view) return
        statusBarLayers.forEach { it.restore() }
        statusBarMasks.forEach { it.restore() }
        statusBarAnchorViews.forEach { it.removeOnLayoutChangeListener(separatedLayoutListener) }
        statusBarLayers.clear()
        statusBarMasks.clear()
        statusBarAnchorViews.clear()
        statusBarView = null
        statusClockId = View.NO_ID
        lastStatusClockView = null
        lastStatusClockAnchor = null
        clearClockTypography()
        applySeparatedShadeTransition()
    }

    /** A reversible transition driven directly by the panel's physical drag height. */
    fun setSeparatedShadeExpansion(expandedHeight: Float) {
        separatedExpandedHeight = if (expandedHeight.isFinite()) expandedHeight.coerceAtLeast(0f)
            else 0f
        applySeparatedShadeTransition()
    }

    private data class StatusBarLayer(
        val view: View,
        val originalAlpha: Float,
        val originalAccessibility: Int,
    ) {
        fun setReveal(alpha: Float) {
            view.alpha = originalAlpha * alpha
            view.importantForAccessibility = if (alpha <= 0f)
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else originalAccessibility
        }

        fun restore() {
            view.alpha = originalAlpha
            view.importantForAccessibility = originalAccessibility
        }
    }

    private data class StatusBarMask(
        val view: View,
        val originalClip: Rect?,
        val originalAccessibility: Int = view.importantForAccessibility,
    ) {
        private var masked = false

        fun hideDuplicate() {
            if (masked) return
            // Keep the native child laid out and ready, while drawing its sharp header counterpart.
            view.clipBounds = Rect(0, 0, 0, 0)
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            masked = true
        }

        fun restore() {
            if (!masked) return
            view.clipBounds = originalClip
            view.importantForAccessibility = originalAccessibility
            masked = false
        }
    }

    private data class ClockAnchor(val x: Float, val baseline: Float, val textSize: Float)

    private data class ClockTypographyKey(
        val source: Typeface,
        val target: Typeface,
        val sourceSpacing: Float,
        val targetSpacing: Float,
    )

    private fun clearClockTypography() {
        clock.clearDrawTypography()
        clock.clearDrawTypographyEndpoints()
        clockTypographyKey = null
    }

    private fun updateClockTypography() {
        val source = lastStatusClockView
        if (source == null) {
            clearClockTypography()
            return
        }
        val key = ClockTypographyKey(
            source.typeface ?: Typeface.DEFAULT,
            clock.typeface ?: Typeface.DEFAULT,
            source.letterSpacing,
            clock.letterSpacing,
        )
        if (clockTypographyKey != key) {
            clockTypographyKey = key
            clock.setDrawTypographyEndpoints(
                key.source, key.sourceSpacing, key.target, key.targetSpacing
            )
        }
        // Keep one font instance throughout the drag, including fully expanded notifications.
        // Only view scale/position changes; the target Typeface remains the measured layout role.
        clock.setDrawTypography(key.source, key.sourceSpacing)
    }

    /** Ignore child transforms, including the transform assigned on the previous drag frame. */
    private fun layoutPositionOnScreen(view: View): PointF {
        var current = view
        var x = 0f
        var y = 0f
        while (current.parent is View) {
            val parent = current.parent as View
            x += current.left - parent.scrollX
            y += current.top - parent.scrollY
            current = parent
        }
        val location = IntArray(2)
        current.getLocationOnScreen(location)
        return PointF(location[0] + x, location[1] + y)
    }

    private fun textAnchor(view: TextView): ClockAnchor {
        val position = layoutPositionOnScreen(view)
        val textLeft = view.totalPaddingLeft +
            (view.layout?.takeIf { it.lineCount > 0 }?.getLineLeft(0) ?: 0f)
        val baseline = if (view.baseline >= 0) view.baseline.toFloat() else
            view.totalPaddingTop - view.paint.fontMetrics.ascent
        return ClockAnchor(position.x + textLeft, position.y + baseline, view.textSize)
    }

    private fun statusClockAnchor(): ClockAnchor {
        val source = listOf(R.id.clock, R.id.clock_center, R.id.clock_right)
            .mapNotNull { statusBarView?.findViewById<TextView>(it) }
            .firstOrNull { it.visibility != View.GONE }
            ?: lastStatusClockView?.takeIf {
                statusBarView?.findViewById<TextView>(it.id) === it
            }
        statusClockId = source?.id ?: View.NO_ID
        if (source != null && source !== lastStatusClockView) {
            lastStatusClockView = source
            lastStatusClockAnchor = null
        }
        if (source != null && source.width > 0 && source.baseline >= 0) {
            return textAnchor(source).also { lastStatusClockAnchor = it }
        }
        if (source != null && source === lastStatusClockView) {
            lastStatusClockAnchor?.let { return it }
        }
        val insets = statusBarContentInsetsProvider?.getStatusBarContentInsetsForCurrentRotation()
        val textSize = source?.textSize?.takeIf { it > 0f }
            ?: resources.getDimension(R.dimen.status_bar_clock_size)
        val barHeight = statusBarView?.height?.takeIf { it > 0 }?.toFloat()
            ?: resources.getDimension(R.dimen.qs_header_height)
        val barPosition = statusBarView?.let { layoutPositionOnScreen(it) } ?: PointF()
        val barWidth = statusBarView?.width?.takeIf { it > 0 }
            ?: header.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val ratio = textSize / clock.textSize.coerceAtLeast(1f)
        val font = source?.paint?.fontMetrics ?: clock.paint.fontMetrics
        val fontRatio = if (source != null) 1f else ratio
        val baseline = barPosition.y +
            (barHeight - (font.descent + font.ascent) * fontRatio) / 2f
        val textWidth = if (source != null) source.paint.measureText(source.text.toString())
            else clock.paint.measureText(clock.text.toString()) * ratio
        val startPadding = resources.getDimension(R.dimen.status_bar_padding_start)
        val endPadding = resources.getDimension(R.dimen.status_bar_padding_end)
        val clockPadding = if (statusClockId == R.id.clock_right)
            resources.getDimension(R.dimen.status_bar_clock_end_padding)
        else resources.getDimension(R.dimen.status_bar_left_clock_starting_padding)
        val x = when {
            statusClockId == R.id.clock_center -> (barWidth - textWidth) / 2f
            statusClockId == R.id.clock_right || statusBarView?.isLayoutRtl == true ->
                barWidth - (insets?.right ?: 0) - endPadding - clockPadding - textWidth
            else -> (insets?.left ?: 0) + startPadding + clockPadding
        }
        return ClockAnchor(barPosition.x + x, baseline, textSize)
            .also { lastStatusClockAnchor = it }
    }

    private fun progressBetween(progress: Float, start: Float, end: Float): Float {
        val fraction = ((progress - start) / (end - start)).coerceIn(0f, 1f)
        return fraction * fraction * (3f - 2f * fraction)
    }

    private fun applySeparatedShadeTransition() {
        val active = header.isAttachedToWindow && !qsDisabled && separatedShadeEnabled &&
            separatedExpandedHeight > 0f
        val shadeSurface = header.rootSurfaceControl
        val statusSurface = statusBarView?.rootSurfaceControl
        val sync = if (active != separatedPropertiesApplied && shadeSurface != null &&
            statusSurface != null && shadeSurface !== statusSurface) {
            SurfaceSyncGroup("ArkSeparatedStatusHandoff")
        } else null
        try {
            if (sync != null) {
                // The two windows must present their ownership change in the same frame.
                val shadeAdded = sync.add(shadeSurface, null)
                val statusAdded = sync.add(statusSurface, null)
                lastStatusHandoffSynchronized = shadeAdded && statusAdded
                statusHandoffCount++
            }
            applySeparatedShadeProperties()
        } finally {
            // Release any registered root even when the other registration failed.
            sync?.markSyncReady()
        }
    }

    private fun applySeparatedShadeProperties() {
        if (!header.isAttachedToWindow || qsDisabled || !separatedShadeEnabled ||
            separatedExpandedHeight <= 0f) {
            separatedTransitionProgress = 0f
            restoreSeparatedProperties()
            if (separatedShadeEnabled) {
                header.alpha = 0f
                header.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            } else {
                header.importantForAccessibility = originalHeaderAccessibility
            }
            return
        }
        if (!separatedPropertiesApplied) nativeClockClickable = clock.isClickable
        separatedPropertiesApplied = true
        val progress = if (shadeExpandedFraction.isFinite() && shadeExpandedFraction >= 0f)
            shadeExpandedFraction.coerceIn(0f, 1f)
        else (separatedExpandedHeight / header.rootView.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        separatedTransitionProgress = progress
        if (!customizing && !customizerAnimating) header.alpha = 1f
        header.clipChildren = false
        header.clipToPadding = false
        header.importantForAccessibility = originalHeaderAccessibility
        header.translationX = 0f
        header.translationY = 0f
        header.scaleX = 1f
        header.scaleY = 1f

        val sourceClock = statusClockAnchor()
        lastStatusClockAnchor = sourceClock
        val statusClockFade = progressBetween(progress, 0f, 0.24f)
        // Clock/system duplicates stay clipped beneath the blur. Remaining status content,
        // including notification icons, fades reversibly instead of disappearing on DOWN.
        statusBarMasks.forEach { it.hideDuplicate() }
        statusBarSourceReveal = 1f - statusClockFade
        statusBarLayers.forEach { it.setReveal(statusBarSourceReveal) }

        val targetClock = textAnchor(clock)
        lastHeaderClockAnchor = targetClock
        val startScale = (sourceClock.textSize / targetClock.textSize.coerceAtLeast(1f))
            .coerceIn(0.05f, 1f)
        clock.pivotX = clock.totalPaddingLeft +
            (clock.layout?.takeIf { it.lineCount > 0 }?.getLineLeft(0) ?: 0f)
        clock.pivotY = targetClock.baseline - layoutPositionOnScreen(clock).y
        val clockMovement = if (showingControlCenter) 0f else progress
        updateClockTypography()
        clock.scaleX = startScale + (1f - startScale) * clockMovement
        clock.scaleY = clock.scaleX
        clock.translationX = (sourceClock.x - targetClock.x) * (1f - clockMovement)
        clock.translationY = (sourceClock.baseline - targetClock.baseline) * (1f - clockMovement)
        clock.alpha = if (showingControlCenter) 1f - statusClockFade else 1f
        clock.isClickable = nativeClockClickable && !showingControlCenter
        clock.importantForAccessibility = if (showingControlCenter)
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else originalClockAccessibility

        date.alpha = progressBetween(progress, 0.48f, 0.88f)
        mShadeCarrierGroup.alpha = progressBetween(progress, 0.62f, 1f)
        date.importantForAccessibility = if (date.alpha <= 0f)
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else originalDateAccessibility
        mShadeCarrierGroup.importantForAccessibility = if (mShadeCarrierGroup.alpha <= 0f)
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else originalCarrierAccessibility
        date.translationX = 0f
        date.translationY = (sourceClock.baseline - textAnchor(date).baseline) * (1f - progress)
        mShadeCarrierGroup.translationX = 0f
        val iconsPosition = layoutPositionOnScreen(headerSystemIcons)
        val sourceIcons = statusBarView?.findViewById<View>(R.id.system_icons)
        val sourcePosition = sourceIcons?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { layoutPositionOnScreen(it) }
        val statusInsets = statusBarContentInsetsProvider
            ?.getStatusBarContentInsetsForCurrentRotation()
        val sourceRight = if (sourcePosition != null && sourceIcons != null)
            sourcePosition.x + sourceIcons.width - sourceIcons.paddingRight -
                (sourceIcons.findViewById<View>(R.id.right_clock_layout)?.width ?: 0)
        else (statusBarView?.width?.takeIf { it > 0 } ?: header.width).toFloat() -
            (statusInsets?.right ?: header.paddingRight)
        val sourceCenterY = if (sourcePosition != null && sourceIcons != null)
            sourcePosition.y + sourceIcons.height / 2f
        else resources.getDimension(R.dimen.qs_header_height) / 2f
        val carrierPosition = layoutPositionOnScreen(mShadeCarrierGroup)
        val targetCarrierCenterY = carrierPosition.y + mShadeCarrierGroup.height / 2f
        mShadeCarrierGroup.translationY =
            (sourceCenterY - targetCarrierCenterY) * (1f - progress)
        val targetRight = iconsPosition.x + headerSystemIcons.width -
            headerSystemIcons.paddingRight - systemIconsHoverContainer.paddingRight
        val targetCenterY = iconsPosition.y + headerSystemIcons.height / 2f
        val iconMovement = progress
        headerSystemIcons.translationX = (sourceRight - targetRight) * (1f - iconMovement)
        headerSystemIcons.translationY = (sourceCenterY - targetCenterY) * (1f - iconMovement)
        val privacyPosition = layoutPositionOnScreen(privacyContainer)
        privacyContainer.translationX =
            (sourceRight - privacyPosition.x - privacyContainer.width) * (1f - iconMovement)
        privacyContainer.translationY =
            (sourceCenterY - privacyPosition.y - privacyContainer.height / 2f) * (1f - iconMovement)
        val iconsAlpha = if (showingControlCenter) 1f
            else 1f - progressBetween(progress, 0.10f, 0.40f)
        headerSystemIcons.alpha = if (!privacyChipVisible) iconsAlpha else 0f
        privacyContainer.alpha = if (privacyChipVisible) iconsAlpha else 0f
        for (view in arrayOf(headerSystemIcons, privacyContainer)) {
            view.importantForAccessibility = if (view.alpha <= 0f)
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else if (view === headerSystemIcons) originalIconsAccessibility
            else originalPrivacyAccessibility
        }
    }

    private fun restoreSeparatedProperties() {
        clearClockTypography()
        if (!separatedPropertiesApplied) return
        statusBarLayers.forEach { it.restore() }
        statusBarMasks.forEach { it.restore() }
        statusBarSourceReveal = 1f
        separatedPropertiesApplied = false
        header.alpha = ShadeInterpolation.getContentAlpha(shadeExpandedFraction.coerceIn(0f, 1f))
        header.translationX = 0f
        header.translationY = 0f
        header.scaleX = 1f
        header.scaleY = 1f
        header.clipChildren = originalHeaderClipChildren
        header.clipToPadding = originalHeaderClipToPadding
        header.importantForAccessibility = originalHeaderAccessibility
        clock.pivotX = nativeClockPivotX
        clock.pivotY = nativeClockPivotY
        clock.scaleX = if (standalonePanel || largeScreenActive) 1f else
            1f + (resources.getFloat(R.dimen.qqs_expand_clock_scale) - 1f) *
                qsExpandedFraction.coerceIn(0f, 1f)
        clock.scaleY = clock.scaleX
        clock.alpha = 1f
        clock.isClickable = nativeClockClickable
        clock.importantForAccessibility = originalClockAccessibility
        for (view in arrayOf(clock, date, mShadeCarrierGroup, headerSystemIcons, privacyContainer)) {
            view.translationX = 0f
            view.translationY = 0f
        }
        date.alpha = 1f
        mShadeCarrierGroup.alpha = 1f
        date.importantForAccessibility = originalDateAccessibility
        mShadeCarrierGroup.importantForAccessibility = originalCarrierAccessibility
        headerSystemIcons.alpha = 1f
        privacyContainer.alpha = 1f
        headerSystemIcons.importantForAccessibility = originalIconsAccessibility
        privacyContainer.importantForAccessibility = originalPrivacyAccessibility
        if (separatedShadeEnabled) {
            forceSeparatedHeaderLayout()
        } else {
            header.progress = headerLayoutExpansion
        }
    }

    private fun standaloneHeight(): Int {
        val minimum = resources.getDimensionPixelSize(
            R.dimen.ark_dual_notification_header_height
        )
        val textRows = resources.getDimension(R.dimen.ark_dual_notification_clock_height) +
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 32f, resources.displayMetrics)
        val spacing = 16 * resources.displayMetrics.density
        return maxOf(minimum, (textRows + spacing).toInt()) + header.paddingTop
    }

    private fun updateStandaloneTypography() {
        clearClockTypography()
        clock.setTextAppearance(R.style.TextAppearance_QS_Status_Clock)
        date.setTextAppearance(
            if (standalonePanel) R.style.TextAppearance_Ark_DualHeader_Date
            else R.style.TextAppearance_QS_Status
        )
        clock.setTextSizeResourceOverride(if (standalonePanel)
            R.dimen.ark_dual_notification_clock_size else 0)
        clock.ellipsize = if (standalonePanel) TextUtils.TruncateAt.END else originalClockEllipsize
        mShadeCarrierGroup.updateTextAppearanceAndTint(
            if (standalonePanel) R.style.TextAppearance_Ark_DualHeader_Carrier
            else R.style.TextAppearance_QS_Status, getFgColor(), getBgColor()
        )
        mShadeCarrierGroup.findViewById<TextView>(R.id.no_carrier_text)?.minimumHeight =
            if (standalonePanel) 0 else (48 * resources.displayMetrics.density).toInt()
        updateQQSPaddings()
        updateStandaloneLargeFontConstraints()
        val top = if (standalonePanel) standaloneTopInset()
            else statusBarContentInsetsProvider?.getStatusBarContentInsetsForCurrentRotation()?.top ?: 0
        header.setPadding(header.paddingLeft, top, header.paddingRight, header.paddingBottom)
        updateHeaderHeight()
    }

    private fun standaloneTopInset(insets: WindowInsets? = lastInsets): Int = maxOf(
        insets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top ?: 0,
        header.rootWindowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top ?: 0,
        resources.getDimensionPixelSize(R.dimen.qs_header_height),
    )

    private fun updateHeaderHeight() {
        val height = if (standalonePanel) standaloneHeight()
            else if (separatedShadeEnabled && !largeScreenActive) nativePhoneHeaderHeight()
            else originalHeaderHeight
        if (header.layoutParams.height != height) {
            header.layoutParams = header.layoutParams.apply { this.height = height }
        }
        val standaloneHeight = standaloneLayoutHeight
        if (publishedStandaloneHeight != standaloneHeight) {
            publishedStandaloneHeight = standaloneHeight
            onHeaderHeightChanged?.invoke()
        }
    }

    /** Untransformed bottom of the complete phone header, including its first layout. */
    fun getExpandedHeaderBottom(): Int {
        if (standalonePanel) return header.top + standaloneHeight()
        return header.top + maxOf(header.height, nativePhoneHeaderHeight())
    }

    private fun nativePhoneHeaderHeight(): Int = maxOf(
        resources.getDimensionPixelSize(R.dimen.qs_header_height),
        2 * resources.getDimensionPixelSize(R.dimen.large_screen_shade_header_min_height) +
            resources.getDimensionPixelSize(R.dimen.new_qs_header_non_clickable_element_height),
    )

    /** Expansion fraction of the QQS/QS shade. This is not the expansion between QQS <-> QS. */
    var shadeExpandedFraction = -1f
        set(value) {
            if ((qsVisible || separatedShadeEnabled) && field != value) {
                if (!separatedShadeEnabled) header.alpha = ShadeInterpolation.getContentAlpha(value)
                field = value
                updateIgnoredSlots()
            }
            applySeparatedShadeTransition()
        }

    /** Expansion fraction of the QQS <-> QS animation. */
    var qsExpandedFraction = -1f
        set(value) {
            if (visible && field != value) {
                field = value
                iconContainer.setQsExpansionTransitioning(
                    !separatedShadeEnabled && value > 0f && value < 1.0f
                )
                updatePosition()
                updateIgnoredSlots()
            }
        }

    /** Current scroll of QS. */
    var qsScrollY = 0
        set(value) {
            if (field != value) {
                field = value
                updateScrollY()
            }
        }

    private val insetListener =
        View.OnApplyWindowInsetsListener { view, insets ->
            val windowInsets = WindowInsets(insets)
            if (windowInsets != lastInsets) {
                updateConstraintsForInsets(view as MotionLayout, insets)
                lastInsets = windowInsets
                view.onApplyWindowInsets(insets)
            } else {
                insets
            }
        }

    private var singleCarrier = false

    private val demoModeReceiver =
        object : DemoMode {
            override fun demoCommands() = listOf(DemoMode.COMMAND_CLOCK)

            override fun dispatchDemoCommand(command: String, args: Bundle) =
                clock.dispatchDemoCommand(command, args)

            override fun onDemoModeStarted() = clock.onDemoModeStarted()

            override fun onDemoModeFinished() = clock.onDemoModeFinished()
        }

    private val chipVisibilityListener: ChipVisibilityListener =
        object : ChipVisibilityListener {
            override fun onChipVisibilityRefreshed(visible: Boolean) {
                // If the privacy chip is visible, we hide the status icons and battery remaining
                // icon, only in QQS.
                val update =
                    combinedShadeHeadersConstraintManager.privacyChipVisibilityConstraints(visible)
                header.updateAllConstraints(update)
                privacyChipVisible = visible
                updateStandalonePrivacyConstraints()
                applySeparatedShadeTransition()
            }
        }

    private val configurationControllerListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onConfigChanged(newConfig: Configuration?) {
                val left =
                    header.resources.getDimensionPixelSize(
                        R.dimen.large_screen_shade_header_left_padding
                    )
                header.setPadding(
                    left,
                    header.paddingTop,
                    header.paddingRight,
                    header.paddingBottom,
                )
                systemIconsHoverContainer.setPaddingRelative(
                    resources.getDimensionPixelSize(
                        R.dimen.hover_system_icons_container_padding_start
                    ),
                    resources.getDimensionPixelSize(
                        R.dimen.hover_system_icons_container_padding_top
                    ),
                    resources.getDimensionPixelSize(
                        R.dimen.hover_system_icons_container_padding_end
                    ),
                    resources.getDimensionPixelSize(
                        R.dimen.hover_system_icons_container_padding_bottom
                    ),
                )
                applySeparatedShadeTransition()
            }

            override fun onDensityOrFontScaleChanged() {
                clock.setTextAppearance(R.style.TextAppearance_QS_Status)
                date.setTextAppearance(R.style.TextAppearance_QS_Status)
                mShadeCarrierGroup.updateTextAppearanceAndTint(
                    R.style.TextAppearance_QS_Status,
                    getFgColor(),
                    getBgColor(),
                )
                loadConstraints()
                header.minHeight =
                    resources.getDimensionPixelSize(R.dimen.large_screen_shade_header_min_height)
                lastInsets?.let { updateConstraintsForInsets(header, it) }
                updateResources()
                updateCarrierGroupPadding()
                if (!ShadeWindowGoesAround.isEnabled) {
                    // the clock handles the config change itself.
                    clock.onDensityOrFontScaleChanged()
                }
                updateStandaloneTypography()
                updateTransition()
            }

            override fun onThemeChanged() {
                updateColors()
            }

            override fun onUiModeChanged() {
                updateColors()
            }
        }

    private val nextAlarmCallback =
        NextAlarmController.NextAlarmChangeCallback { nextAlarm ->
            nextAlarmIntent = nextAlarm?.showIntent
        }

    override fun onInit() {
        variableDateViewControllerFactory.create(date as VariableDateView).init()

        val fgColor = getFgColor()
        val bgColor = getBgColor()

        iconManager = tintedIconManagerFactory.create(iconContainer, StatusBarLocation.QS)
        iconManager.setTint(fgColor, bgColor)

        if (!NewStatusBarIcons.isEnabled) {
            batteryMeterViewController.init()

            // battery settings same as in QS icons
            batteryMeterViewController.ignoreTunerUpdates()

            batteryIcon.isVisible = true
            batteryIcon.updateColors(
                fgColor, /* foreground */
                bgColor, /* background */
                fgColor, /* single tone (current default) */
            )
        } else {
            // Configure the correct margins for the system icon container
            val statusIcons = mView.requireViewById<AlphaOptimizedLinearLayout>(R.id.statusIcons)
            SystemStatusIconsLayoutHelper.configurePaddingForNewStatusBarIcons(statusIcons)

            // Configure the compose battery view
            val batteryComposeView = createBatteryComposeView()
            mView.requireViewById<ViewGroup>(R.id.hover_system_icons_container).apply {
                addView(batteryComposeView, -1)
            }

            batteryComposeView.setOnClickListener { launchBatteryActivity() }
        }

        carrierIconSlots =
            listOf(
                header.context.getString(com.android.internal.R.string.status_bar_mobile),
                header.context.getString(com.android.internal.R.string.status_bar_stacked_mobile),
            )
        mShadeCarrierGroupController =
            shadeCarrierGroupControllerBuilder.setShadeCarrierGroup(mShadeCarrierGroup).build()

        privacyIconsController.onParentVisible()
        updateStandaloneTypography()
    }

    private fun getBgColor() =
        if (notificationShadeBlur()) {
            header.context.getColor(R.color.shade_header_text_color_bg)
        } else {
            android.graphics.Color.BLACK
        }

    private fun getFgColor() =
        if (notificationShadeBlur()) {
            header.context.getColor(R.color.shade_header_text_color)
        } else {
            android.graphics.Color.WHITE
        }

    private fun createBatteryComposeView(): ComposeView {
        return if (RudimentaryBattery.isEnabled) {
            ComposeView(mView.context).apply {
                setContent {
                    PlatformTheme {
                        id = R.id.battery_meter_composable_view
                        val showBatteryEstimate by showBatteryEstimate.collectAsStateWithLifecycle()
                        val dark = isSystemInDarkTheme()
                        BatteryWithChargeStatus(
                            modifier = Modifier.wrapContentSize(),
                            viewModelFactory = tandemBatteryViewModelFactory,
                            isDarkProvider = { IsAreaDark { dark } },
                            showPercentMode =
                                if (showBatteryEstimate) ShowPercentMode.PreferEstimate
                                else ShowPercentMode.Always,
                        )
                    }
                }
            }
        } else {
            ComposeView(mView.context).apply {
                setContent {
                    PlatformTheme {
                        id = R.id.battery_meter_composable_view
                        val showBatteryEstimate by showBatteryEstimate.collectAsStateWithLifecycle()
                        val dark = isSystemInDarkTheme()
                        val viewModel =
                            rememberViewModel(traceName = "UnifiedBattery") {
                                unifiedBatteryViewModelFactory.create()
                            }
                        BatteryWithPercent(
                            modifier = Modifier.wrapContentSize(),
                            viewModel = viewModel,
                            isDarkProvider = { IsAreaDark { dark } },
                            showPercent = viewModel.isBatteryPercentSettingEnabled,
                            showEstimate = showBatteryEstimate,
                        )
                    }
                }
            }
        }
    }

    override fun onViewAttached() {
        privacyIconsController.chipVisibilityListener = chipVisibilityListener
        updateVisibility()
        updateTransition()
        updateCarrierGroupPadding()

        header.setOnApplyWindowInsetsListener(insetListener)
        header.requestApplyInsets()

        clock.addOnLayoutChangeListener(clockLayoutListener)
        for (view in arrayOf(header, date, mShadeCarrierGroup, headerSystemIcons, privacyContainer)) {
            view.addOnLayoutChangeListener(separatedLayoutListener)
        }
        clock.setOnClickListener { launchClockActivity() }
        nativeClockClickable = clock.isClickable

        dumpManager.registerDumpable(this)
        configurationController.addCallback(configurationControllerListener)
        demoModeController.addCallback(demoModeReceiver)
        statusBarIconController.addIconGroup(iconManager)
        updateListeners()
        nextAlarmController.addCallback(nextAlarmCallback)
        systemIconsHoverContainer.setOnHoverListener(
            statusOverlayHoverListenerFactory.createListener(systemIconsHoverContainer)
        )
        applySeparatedShadeTransition()
    }

    override fun onViewDetached() {
        restoreSeparatedProperties()
        mShadeCarrierGroupController.setListening(false)
        mShadeCarrierGroupController.setOnSingleCarrierChangedListener(null)
        clock.removeOnLayoutChangeListener(clockLayoutListener)
        for (view in arrayOf(header, date, mShadeCarrierGroup, headerSystemIcons, privacyContainer)) {
            view.removeOnLayoutChangeListener(separatedLayoutListener)
        }
        clock.setOnClickListener(null)
        privacyIconsController.chipVisibilityListener = null
        dumpManager.unregisterDumpable(this::class.java.simpleName)
        configurationController.removeCallback(configurationControllerListener)
        demoModeController.removeCallback(demoModeReceiver)
        statusBarIconController.removeIconGroup(iconManager)
        nextAlarmController.removeCallback(nextAlarmCallback)
        systemIconsHoverContainer.setOnHoverListener(null)
    }

    fun disable(state1: Int, state2: Int, animate: Boolean) {
        val disabled = state2 and StatusBarManager.DISABLE2_QUICK_SETTINGS != 0
        if (disabled == qsDisabled) return
        qsDisabled = disabled
        updateVisibility()
        applySeparatedShadeTransition()
    }

    fun startCustomizingAnimation(show: Boolean, duration: Long) {
        header
            .animate()
            .setDuration(duration)
            .alpha(if (show) 0f else 1f)
            .setInterpolator(if (show) Interpolators.ALPHA_OUT else Interpolators.ALPHA_IN)
            .setListener(CustomizerAnimationListener(show))
            .start()
    }

    private fun launchBatteryActivity() {
        activityStarter.postStartActivityDismissingKeyguard(
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY), 0
        )
    }

    @VisibleForTesting
    internal fun launchClockActivity() {
        if (nextAlarmIntent != null) {
            activityStarter.postStartActivityDismissingKeyguard(nextAlarmIntent)
        } else {
            activityStarter.postStartActivityDismissingKeyguard(DEFAULT_CLOCK_INTENT, 0 /*delay */)
        }
    }

    private fun loadConstraints() {
        // Use resources.getXml instead of passing the resource id due to bug b/205018300
        header
            .getConstraintSet(QQS_HEADER_CONSTRAINT)
            .load(context, resources.getXml(R.xml.qqs_header))
        header
            .getConstraintSet(QS_HEADER_CONSTRAINT)
            .load(context, resources.getXml(R.xml.qs_header))
        header
            .getConstraintSet(LARGE_SCREEN_HEADER_CONSTRAINT)
            .load(context, resources.getXml(R.xml.large_screen_shade_header))
        header.getConstraintSet(NOTIFICATION_HEADER_CONSTRAINT)
            .load(context, resources.getXml(R.xml.ark_dual_notification_header))
    }

    private fun updateColors() {
        clock.setTextAppearance(R.style.TextAppearance_QS_Status)
        date.setTextAppearance(R.style.TextAppearance_QS_Status)
        mShadeCarrierGroup.updateTextAppearanceAndTint(
            R.style.TextAppearance_QS_Status,
            getFgColor(),
            getBgColor(),
        )
        iconManager.setTint(getFgColor(), getBgColor())
        updateStandaloneTypography()
    }

    private fun updateCarrierGroupPadding() {
        clock.doOnLayout {
            if (standalonePanel) {
                mShadeCarrierGroup.setPaddingRelative(0, 0, 0, 0)
                return@doOnLayout
            }
            val maxClockWidth =
                (clock.width * resources.getFloat(R.dimen.qqs_expand_clock_scale)).toInt()
            mShadeCarrierGroup.setPaddingRelative(maxClockWidth, 0, 0, 0)
        }
    }

    private fun updateConstraintsForInsets(view: MotionLayout, insets: WindowInsets) {
        val insetsProvider = statusBarContentInsetsProvider ?: return
        val cutout = insets.displayCutout.also { this.cutout = it }

        val sbInsets: Insets = insetsProvider.getStatusBarContentInsetsForCurrentRotation()
        val cutoutLeft = sbInsets.left
        val cutoutRight = sbInsets.right
        val hasCornerCutout: Boolean = insetsProvider.currentRotationHasCornerCutout()
        updateQQSPaddings()
        // Set these guides as the left/right limits for content that lives in the top row, using
        // cutoutLeft and cutoutRight
        var changes =
            combinedShadeHeadersConstraintManager.edgesGuidelinesConstraints(
                if (view.isLayoutRtl) cutoutRight else cutoutLeft,
                header.paddingStart,
                if (view.isLayoutRtl) cutoutLeft else cutoutRight,
                header.paddingEnd,
            )

        if (cutout != null) {
            val topCutout = cutout.boundingRectTop
            if (topCutout.isEmpty || hasCornerCutout) {
                changes += combinedShadeHeadersConstraintManager.emptyCutoutConstraints()
            } else {
                changes +=
                    combinedShadeHeadersConstraintManager.centerCutoutConstraints(
                        view.isLayoutRtl,
                        (view.width - view.paddingLeft - view.paddingRight - topCutout.width()) / 2,
                    )
            }
        } else {
            changes += combinedShadeHeadersConstraintManager.emptyCutoutConstraints()
        }

        val top = if (standalonePanel) {
            maxOf(sbInsets.top, standaloneTopInset(insets))
        } else sbInsets.top
        view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
        view.updateAllConstraints(changes)
        view.updateConstraints(NOTIFICATION_HEADER_CONSTRAINT) {
            setGuidelineBegin(R.id.begin_guide,
                maxOf((if (view.isLayoutRtl) cutoutRight else cutoutLeft) - view.paddingStart, 0))
            setGuidelineEnd(R.id.end_guide,
                maxOf((if (view.isLayoutRtl) cutoutLeft else cutoutRight) - view.paddingEnd, 0))
        }
        updateHeaderHeight()
        updateStandalonePrivacyConstraints()
        updateBatteryMode()
        applySeparatedShadeTransition()
    }

    private fun updateStandalonePrivacyConstraints() {
        header.updateConstraints(NOTIFICATION_HEADER_CONSTRAINT) {
            setAlpha(R.id.shade_header_system_icons, if (privacyChipVisible) 0f else 1f)
        }
    }

    private fun updateStandaloneLargeFontConstraints() {
        val largeFont = resources.configuration.fontScale > 1.3f
        header.updateConstraints(NOTIFICATION_HEADER_CONSTRAINT) {
            clear(R.id.carrier_group, ConstraintSet.TOP)
            connect(R.id.carrier_group, ConstraintSet.TOP,
                if (largeFont) R.id.clock else ConstraintSet.PARENT_ID,
                if (largeFont) ConstraintSet.BOTTOM else ConstraintSet.TOP)
            clear(R.id.carrier_group, ConstraintSet.START)
            connect(R.id.carrier_group, ConstraintSet.START,
                if (largeFont) R.id.date else R.id.clock, ConstraintSet.END,
                (12 * resources.displayMetrics.density).toInt())
            clear(R.id.date, ConstraintSet.END)
            connect(R.id.date, ConstraintSet.END,
                if (largeFont) R.id.carrier_group else R.id.end_guide,
                if (largeFont) ConstraintSet.START else ConstraintSet.END)
            clear(R.id.shade_header_system_icons, ConstraintSet.TOP)
            clear(R.id.privacy_container, ConstraintSet.TOP)
            for (id in intArrayOf(R.id.shade_header_system_icons, R.id.privacy_container)) {
                connect(id, ConstraintSet.TOP,
                    if (largeFont) ConstraintSet.PARENT_ID else R.id.carrier_group,
                    if (largeFont) ConstraintSet.TOP else ConstraintSet.BOTTOM,
                    if (largeFont) 0 else (4 * resources.displayMetrics.density).toInt())
            }
        }
    }

    private fun updateBatteryMode() {
        qsBatteryModeController.getBatteryMode(cutout, headerLayoutExpansion)?.let {
            if (NewStatusBarIcons.isEnabled) {
                showBatteryEstimate.value = it == MODE_ESTIMATE
            } else {
                batteryIcon.setPercentShowMode(it)
            }
        }
    }

    private fun updateScrollY() {
        if (!largeScreenActive) {
            header.scrollY = if (separatedShadeEnabled) 0 else qsScrollY
        }
    }

    private fun onShadeExpandedChanged() {
        if (qsVisible) {
            privacyIconsController.startListening()
        } else {
            privacyIconsController.stopListening()
        }
        updateVisibility()
        updatePosition()
    }

    private fun onHeaderStateChanged() {
        updateTransition()
    }

    /**
     * If not using [combinedHeaders] this should only be visible on large screen. Else, it should
     * be visible any time the QQS/QS shade is open.
     */
    private fun updateVisibility() {
        val visibility =
            if (qsDisabled) {
                View.GONE
            } else if (qsVisible && !customizing) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
        if (header.visibility != visibility) {
            header.visibility = visibility
            visible = visibility == View.VISIBLE
        }
    }

    private fun updateTransition() {
        if (standalonePanel) {
            header.setTransition(R.id.ark_dual_notification_header_transition)
            systemIconsHoverContainer.setOnClickListener(null)
            systemIconsHoverContainer.isClickable = false
        } else if (largeScreenActive) {
            logInstantEvent("Large screen constraints set")
            header.setTransition(LARGE_SCREEN_HEADER_TRANSITION_ID)
            systemIconsHoverContainer.isClickable = true
            systemIconsHoverContainer.setOnClickListener { shadeCollapseAction?.run() }
        } else {
            logInstantEvent("Small screen constraints set")
            header.setTransition(HEADER_TRANSITION_ID)
            systemIconsHoverContainer.setOnClickListener(null)
            systemIconsHoverContainer.isClickable = false
        }

        lastInsets?.let { updateConstraintsForInsets(header, it) }

        if (separatedShadeEnabled) {
            forceSeparatedHeaderLayout()
        } else {
            header.jumpToState(header.startState)
        }
        updatePosition()
        updateScrollY()
    }

    private fun forceSeparatedHeaderLayout() {
        val state = when {
            standalonePanel -> NOTIFICATION_HEADER_CONSTRAINT
            largeScreenActive -> LARGE_SCREEN_HEADER_CONSTRAINT
            else -> QS_HEADER_CONSTRAINT
        }
        if (header.currentState != state) header.jumpToState(state)
    }

    private fun updatePosition() {
        if (separatedShadeEnabled) {
            forceSeparatedHeaderLayout()
            updateBatteryMode()
        } else if (!largeScreenActive && visible) {
            logInstantEvent("updatePosition: $qsExpandedFraction")
            header.progress = headerLayoutExpansion
            updateBatteryMode()
        }
        applySeparatedShadeTransition()
    }

    private val headerLayoutExpansion: Float
        get() = if (separatedShadeEnabled) 1f else qsExpandedFraction

    private fun logInstantEvent(message: String) {
        Trace.instantForTrack(TRACE_TAG_APP, "LargeScreenHeaderController", message)
    }

    private fun updateListeners() {
        val listening = visible || (separatedShadeEnabled && header.isAttachedToWindow)
        mShadeCarrierGroupController.setListening(listening)
        if (listening) {
            singleCarrier = mShadeCarrierGroupController.isSingleCarrier
            updateIgnoredSlots()
            mShadeCarrierGroupController.setOnSingleCarrierChangedListener {
                singleCarrier = it
                updateIgnoredSlots()
            }
        } else {
            mShadeCarrierGroupController.setOnSingleCarrierChangedListener(null)
        }
    }

    private fun updateIgnoredSlots() {
        // The notification layout can be selected before onInit().
        if (!::carrierIconSlots.isInitialized) return
        // switching from QQS to QS state halfway through the transition
        if (singleCarrier || (!largeScreenActive && headerLayoutExpansion < 0.5)) {
            iconContainer.removeIgnoredSlots(carrierIconSlots)
        } else {
            iconContainer.addIgnoredSlots(carrierIconSlots)
        }
    }

    private fun updateResources() {
        roundedCorners = resources.getDimensionPixelSize(R.dimen.rounded_corner_content_padding)
        val padding = resources.getDimensionPixelSize(R.dimen.qs_panel_padding)
        header.setPadding(padding, header.paddingTop, padding, header.paddingBottom)
        updateQQSPaddings()
        qsBatteryModeController.updateResources()
    }

    private fun updateQQSPaddings() {
        if (standalonePanel) {
            clock.setPaddingRelative(0, clock.paddingTop, 0, clock.paddingBottom)
            return
        }
        val clockPaddingStart =
            resources.getDimensionPixelSize(R.dimen.status_bar_left_clock_starting_padding)
        val clockPaddingEnd =
            resources.getDimensionPixelSize(R.dimen.status_bar_left_clock_end_padding)
        clock.setPaddingRelative(
            clockPaddingStart,
            clock.paddingTop,
            clockPaddingEnd,
            clock.paddingBottom,
        )
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("visible: $visible")
        pw.println("shadeExpanded: $qsVisible")
        pw.println("shadeExpandedFraction: $shadeExpandedFraction")
        pw.println("active: $largeScreenActive")
        pw.println("qsExpandedFraction: $qsExpandedFraction")
        pw.println("qsScrollY: $qsScrollY")
        pw.println("currentState: ${header.currentState.stateToString()}")
        pw.println("standaloneNotificationPanel: $standalonePanel")
        pw.println("headerHeight: ${header.height}, expandedHeaderBottom: ${getExpandedHeaderBottom()}")
        pw.println("separatedShadeEnabled: $separatedShadeEnabled")
        pw.println("showingControlCenter: $showingControlCenter")
        pw.println("separatedExpandedHeight: $separatedExpandedHeight")
        pw.println("separatedTransitionProgress: $separatedTransitionProgress")
        pw.println("headerAlpha: ${header.alpha}")
        pw.println("statusClockId: $statusClockId")
        pw.println("statusBarSourceAttached: ${statusBarView?.isAttachedToWindow == true}")
        pw.println("statusBarSource: visibility=${statusBarView?.visibility}, " +
            "alpha=${statusBarView?.alpha}, clip=${statusBarView?.clipBounds}")
        pw.println("statusBarSourceLayerCount: ${statusBarLayers.size}")
        pw.println("statusBarSourceReveal: $statusBarSourceReveal")
        pw.println("statusBarSourceMaskCount: ${statusBarMasks.size}")
        statusBarMasks.forEach {
            pw.println("statusMask[${resources.getResourceEntryName(it.view.id)}]: " +
                "clip=${it.view.clipBounds}, original=${it.originalClip}")
        }
        pw.println("lastStatusHandoffSynchronized: $lastStatusHandoffSynchronized")
        pw.println("statusHandoffCount: $statusHandoffCount")
        pw.println("statusClockAnchor: $lastStatusClockAnchor")
        pw.println("headerClockAnchor: $lastHeaderClockAnchor")
        pw.println("sourceClock: visibility=${lastStatusClockView?.visibility}, " +
            "alpha=${lastStatusClockView?.alpha}")
        statusBarView?.findViewById<View>(R.id.status_bar_end_side_content)?.let {
            pw.println("sourceSystemInfo: visibility=${it.visibility}, alpha=${it.alpha}")
        }
        pw.println("separatedClock: alpha=${clock.alpha}, scale=${clock.scaleX}, " +
            "translationX=${clock.translationX}, translationY=${clock.translationY}")
        pw.println("clockTypographyMatchesSource: ${clock.drawTypeface != null &&
            clock.drawTypeface === lastStatusClockView?.typeface}")
        pw.println("clockTypographyDrawWeight: ${clock.drawTypeface?.weight}")
        pw.println("clockTypographyDrawSpacing: ${clock.drawLetterSpacing}")
        pw.println("clockTypographyClipRightExtension: ${clock.drawTypographyClipRightExtension}")
        clockTypographyKey?.let {
            pw.println("clockTypographyFamilies: source=${it.source.systemFontFamilyName}, " +
                "target=${it.target.systemFontFamilyName}")
            pw.println("clockTypographyWeights: source=${it.source.weight}, target=${it.target.weight}")
            pw.println("clockTypographySpacing: source=${it.sourceSpacing}, target=${it.targetSpacing}")
        }
        pw.println("separatedDateAlpha: ${date.alpha}")
        pw.println("separatedCarrierAlpha: ${mShadeCarrierGroup.alpha}")
        pw.println("separatedDateTranslationY: ${date.translationY}")
        pw.println("separatedCarrierTranslationY: ${mShadeCarrierGroup.translationY}")
        pw.println("separatedIcons: alpha=${headerSystemIcons.alpha}, " +
            "translationX=${headerSystemIcons.translationX}, " +
            "translationY=${headerSystemIcons.translationY}")
        for (layer in statusBarLayers) {
            pw.println("statusParentAlpha[${resources.getResourceEntryName(layer.view.id)}]: " +
                layer.view.alpha)
        }
    }

    private fun MotionLayout.updateConstraints(@IdRes state: Int, update: ConstraintChange) {
        val constraints = getConstraintSet(state)
        constraints.update()
        updateState(state, constraints)
    }

    /**
     * Updates the [ConstraintSet] for the case of combined headers.
     *
     * Only non-`null` changes are applied to reduce the number of rebuilding in the [MotionLayout].
     */
    private fun MotionLayout.updateAllConstraints(updates: ConstraintsChanges) {
        if (updates.qqsConstraintsChanges != null) {
            updateConstraints(QQS_HEADER_CONSTRAINT, updates.qqsConstraintsChanges)
        }
        if (updates.qsConstraintsChanges != null) {
            updateConstraints(QS_HEADER_CONSTRAINT, updates.qsConstraintsChanges)
        }
        if (updates.largeScreenConstraintsChanges != null) {
            updateConstraints(LARGE_SCREEN_HEADER_CONSTRAINT, updates.largeScreenConstraintsChanges)
        }
    }

    @VisibleForTesting internal fun simulateViewDetached() = this.onViewDetached()

    inner class CustomizerAnimationListener(private val enteringCustomizing: Boolean) :
        AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            super.onAnimationEnd(animation)
            customizerAnimating = false
            header.animate().setListener(null)
            if (enteringCustomizing) {
                customizing = true
            }
            applySeparatedShadeTransition()
        }

        override fun onAnimationStart(animation: Animator) {
            super.onAnimationStart(animation)
            customizerAnimating = true
            if (!enteringCustomizing) {
                customizing = false
            }
        }
    }
}
