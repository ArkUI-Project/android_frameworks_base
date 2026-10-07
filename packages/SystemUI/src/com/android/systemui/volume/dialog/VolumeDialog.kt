/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.volume.dialog

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.app.tracing.coroutines.coroutineScopeTraced
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.volume.AppVolumeController
import com.android.systemui.volume.panel.ui.composable.VolumePanelRoot
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelViewModel
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.dagger.factory.VolumeDialogComponentFactory
import com.android.systemui.volume.dialog.domain.interactor.DesktopAudioTileDetailsFeatureInteractor
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import javax.inject.Inject
import kotlinx.coroutines.awaitCancellation
import lineageos.providers.LineageSettings

class VolumeDialog
@Inject
constructor(
    @Application context: Context,
    private val componentFactory: VolumeDialogComponentFactory,
    private val visibilityInteractor: VolumeDialogVisibilityInteractor,
    private val panelFactory: VolumePanelViewModel.Factory,
    private val appVolumes: AppVolumeController,
    private val userTracker: UserTracker,
    private val keyguard: KeyguardStateController,
    desktopAudioTileDetailsFeatureInteractor: DesktopAudioTileDetailsFeatureInteractor,
) : ComponentDialog(context, R.style.Theme_SystemUI_Dialog_Volume) {
    // Use horizontal volume dialog if the audio tile details view is enabled
    private val isVolumeDialogVertical = !desktopAudioTileDetailsFeatureInteractor.isEnabled()

    private var volumePanelOnLeft: Boolean = false
    private val userCallback = object : UserTracker.Callback {
        override fun onUserChanged(id: Int, userContext: Context) {
            visibilityInteractor.dismissDialog(Events.DISMISS_REASON_VOLUME_CONTROLLER)
        }
    }
    private val keyguardCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardShowingChanged() {
            if (keyguard.isShowing && visibilityInteractor.isExpanded.value) {
                visibilityInteractor.dismissDialog(Events.DISMISS_REASON_SCREEN_OFF)
            }
        }
    }

    private val volumePanelOnLeftObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val onLeft =
                    LineageSettings.Secure.getIntForUser(
                        context.contentResolver,
                        LineageSettings.Secure.VOLUME_PANEL_ON_LEFT,
                        0,
                        UserHandle.USER_CURRENT
                    ) != 0

                if (volumePanelOnLeft != onLeft) {
                    volumePanelOnLeft = onLeft
                    applyLayoutAndGravity()
                }
            }
        }

    private fun applyLayoutAndGravity() {
        val win = window ?: return
        val side = if (volumePanelOnLeft) Gravity.LEFT else Gravity.RIGHT
        val dialogView = win.decorView

        dialogView.layoutDirection =
            if (volumePanelOnLeft)
                View.LAYOUT_DIRECTION_RTL
            else
                View.LAYOUT_DIRECTION_LTR

        if (isVolumeDialogVertical) {
            win.setLayout(
                (minOf(420, context.resources.configuration.screenWidthDp) *
                    context.resources.displayMetrics.density).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            win.setGravity(side)
        } else {
            win.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            win.setGravity(Gravity.TOP or side)
        }
    }

    init {
        with(window!!) {
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
            setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            setWindowAnimations(-1)

            attributes =
                attributes.apply {
                    title = "VolumeDialog" // Not the same as Window#setTitle
                }
        }
        setCancelable(false)
        setCanceledOnTouchOutside(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isVolumeDialogVertical) {
            setContentView(R.layout.arkui_volume_dialog)
            bindExpandedPanel()
        } else {
            setContentView(R.layout.volume_dialog_horizontal)
        }
        requireViewById<View>(R.id.volume_dialog).repeatWhenAttached {
            coroutineScopeTraced("[Volume]dialog") {
                val component = componentFactory.create(this)
                with(component.volumeDialogViewBinder()) {
                    bind(this@VolumeDialog, isVolumeDialogVertical)
                }

                awaitCancellation()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        userTracker.addCallback(userCallback, java.util.concurrent.Executor { context.mainExecutor.execute(it) })
        keyguard.addCallback(keyguardCallback)
        context.contentResolver.registerContentObserver(
            LineageSettings.Secure.getUriFor(LineageSettings.Secure.VOLUME_PANEL_ON_LEFT),
            false,
            volumePanelOnLeftObserver,
            UserHandle.USER_ALL
        )
        volumePanelOnLeft = LineageSettings.Secure.getIntForUser(
            context.contentResolver, LineageSettings.Secure.VOLUME_PANEL_ON_LEFT,
            0, UserHandle.USER_CURRENT
        ) != 0
        applyLayoutAndGravity()
    }

    override fun onStop() {
        super.onStop()
        visibilityInteractor.setExpanded(false, resetTimeout = false)
        userTracker.removeCallback(userCallback)
        keyguard.removeCallback(keyguardCallback)
        context.contentResolver.unregisterContentObserver(volumePanelOnLeftObserver)
    }

    /** A second state of the same volume window, anchored to the compact pill. */
    private fun bindExpandedPanel() {
        val compact = requireViewById<View>(R.id.arkui_volume_compact)
        val expanded = requireViewById<ComposeView>(R.id.arkui_volume_expanded)
        compact.alpha = 1f
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (visibilityInteractor.isExpanded.value) visibilityInteractor.setExpanded(false)
                else visibilityInteractor.dismissDialog(Events.DISMISS_REASON_DONE_CLICKED)
            }
        })
        expanded.setContent {
            PlatformTheme {
                val isExpanded by visibilityInteractor.isExpanded.collectAsStateWithLifecycle()
                val anchor = compact.findViewById<View>(R.id.volume_dialog_background)
                val initial: (IntSize) -> IntSize = { size ->
                    IntSize(minOf(anchor.width, size.width), minOf(anchor.height, size.height))
                }
                Box(Modifier.fillMaxWidth(), contentAlignment =
                    if (volumePanelOnLeft) Alignment.CenterStart else Alignment.CenterEnd) {
                    AnimatedVisibility(isExpanded,
                        enter = expandIn(animationSpec = tween(420, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)),
                            expandFrom = if (volumePanelOnLeft) Alignment.CenterStart else Alignment.CenterEnd,
                            initialSize = initial) + fadeIn(tween(180, 50)),
                        exit = shrinkOut(animationSpec = tween(320, easing = CubicBezierEasing(.4f, 0f, .2f, 1f)),
                            shrinkTowards = if (volumePanelOnLeft) Alignment.CenterStart else Alignment.CenterEnd,
                            targetSize = initial) + fadeOut(tween(180))) {
                        val scope = rememberCoroutineScope()
                        val panel = remember(scope) {
                            panelFactory.create(scope) {
                                visibilityInteractor.dismissDialog(Events.DISMISS_REASON_DONE_CLICKED)
                            }
                        }
                        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().heightIn(max =
                                (context.resources.configuration.screenHeightDp - 72).coerceIn(180, 680).dp)) {
                                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.arkui_volume_expanded_title), Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleLarge)
                                    TextButton(onClick = { visibilityInteractor.setExpanded(false) }) {
                                        Text(stringResource(R.string.arkui_volume_collapse))
                                    }
                                }
                                VolumePanelRoot(panel, Modifier.weight(1f, fill = false),
                                    appVolumes = appVolumes, embedded = true)
                            }
                        }
                    }
                }
            }
        }
        expanded.repeatWhenAttached {
            coroutineScopeTraced("VolumeExpansion") {
                visibilityInteractor.isExpanded.collect { value ->
                    compact.animate().cancel()
                    compact.visibility = View.VISIBLE
                    compact.importantForAccessibility = if (value) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    compact.animate().alpha(if (value) 0f else 1f).setDuration(180).withEndAction {
                        if (value && visibilityInteractor.isExpanded.value) compact.visibility = View.INVISIBLE
                    }.start()
                    if (value) window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    else window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                }
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (visibilityInteractor.isExpanded.value && event.actionMasked != MotionEvent.ACTION_OUTSIDE) {
            visibilityInteractor.resetDismissTimeout()
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * NOTE: This will be called with ACTION_OUTSIDE MotionEvents for touches that occur outside of
     * the touchable region of the volume dialog (as returned by [.onComputeInternalInsets]) even if
     * those touches occurred within the bounds of the volume dialog.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isShowing) {
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                if (visibilityInteractor.isExpanded.value) {
                    visibilityInteractor.setExpanded(false)
                    return true
                }
                visibilityInteractor.dismissDialog(Events.DISMISS_REASON_TOUCH_OUTSIDE)
                return true
            }
        }
        return false
    }
}
