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
    private var privacyChipVisible = false
    private var publishedStandaloneHeight: Int? = null
    var onHeaderHeightChanged: (() -> Unit)? = null

    val standaloneLayoutHeight: Int?
        get() = if (standalonePanel) standaloneHeight() else null

    /** Only the separated notification page uses its own header layout. */
    fun setStandalonePanel(enabled: Boolean, showingControlCenter: Boolean) {
        val notificationPanel = enabled && !showingControlCenter
        if (standalonePanel == notificationPanel) return
        standalonePanel = notificationPanel
        updateStandaloneTypography()
        updateCarrierGroupPadding()
        updateTransition()
        header.requestApplyInsets()
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
        val estimatedHeight = maxOf(
            resources.getDimensionPixelSize(R.dimen.qs_header_height),
            2 * resources.getDimensionPixelSize(R.dimen.large_screen_shade_header_min_height) +
                resources.getDimensionPixelSize(R.dimen.new_qs_header_non_clickable_element_height),
        )
        return header.top + maxOf(header.height, estimatedHeight)
    }

    /** Expansion fraction of the QQS/QS shade. This is not the expansion between QQS <-> QS. */
    var shadeExpandedFraction = -1f
        set(value) {
            if (qsVisible && field != value) {
                header.alpha = ShadeInterpolation.getContentAlpha(value)
                field = value
                updateIgnoredSlots()
            }
        }

    /** Expansion fraction of the QQS <-> QS animation. */
    var qsExpandedFraction = -1f
        set(value) {
            if (visible && field != value) {
                field = value
                iconContainer.setQsExpansionTransitioning(
                    value > 0f && value < 1.0f
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

        clock.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val newPivot = if (v.isLayoutRtl) v.width.toFloat() else 0f
            v.pivotX = newPivot
            v.pivotY = v.height.toFloat() / 2
        }
        clock.setOnClickListener { launchClockActivity() }

        dumpManager.registerDumpable(this)
        configurationController.addCallback(configurationControllerListener)
        demoModeController.addCallback(demoModeReceiver)
        statusBarIconController.addIconGroup(iconManager)
        nextAlarmController.addCallback(nextAlarmCallback)
        systemIconsHoverContainer.setOnHoverListener(
            statusOverlayHoverListenerFactory.createListener(systemIconsHoverContainer)
        )
    }

    override fun onViewDetached() {
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
            header.scrollY = if (standalonePanel) 0 else qsScrollY
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

        header.jumpToState(header.startState)
        updatePosition()
        updateScrollY()
    }

    private fun updatePosition() {
        if (!largeScreenActive && visible) {
            logInstantEvent("updatePosition: $qsExpandedFraction")
            header.progress = headerLayoutExpansion
            updateBatteryMode()
        }
    }

    private val headerLayoutExpansion: Float
        get() = if (standalonePanel) 1f else qsExpandedFraction

    private fun logInstantEvent(message: String) {
        Trace.instantForTrack(TRACE_TAG_APP, "LargeScreenHeaderController", message)
    }

    private fun updateListeners() {
        mShadeCarrierGroupController.setListening(visible)
        if (visible) {
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
            header.animate().setListener(null)
            if (enteringCustomizing) {
                customizing = true
            }
        }

        override fun onAnimationStart(animation: Animator) {
            super.onAnimationStart(animation)
            if (!enteringCustomizing) {
                customizing = false
            }
        }
    }
}
