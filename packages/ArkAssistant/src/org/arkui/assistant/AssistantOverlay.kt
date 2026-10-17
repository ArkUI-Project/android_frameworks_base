/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Display
import android.view.SurfaceControl
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.graphics.TransformOrigin
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** The activity is only an entry point. The service owns a non-modal, current-app overlay. */
internal class AssistantOverlay(private val service: AssistantService) : LifecycleOwner, SavedStateRegistryOwner {
    private val main = Handler(Looper.getMainLooper())
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = saved.savedStateRegistry
    private val context = ContextThemeWrapper(service.createWindowContext(
        service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null), R.style.AssistantTheme)
    private val manager = context.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var composition: ComposeView? = null
    private var powerMenuComposition: ComposeView? = null
    private var powerMenuDeadline by mutableLongStateOf(0L)
    private var backdrop: ProgressiveBlur? = null
    private var invocationGlow: AssistantInvocationGlow? = null
    private var focus = false
    private var passthrough = false
    private val store = ConfigStore(service)
    val configured = store.enabled
    private val back = OnBackInvokedCallback {
        if (voice.state.active || voice.state.error.isNotBlank()) voice.cancel()
        else if (progress) closeProgress()
        else if (expanded) expanded = false else hide()
    }
    private var visible by mutableStateOf(false)
    var expanded by mutableStateOf(false)
    var progress by mutableStateOf(false)
        private set
    var preview by mutableStateOf(false)
    var previewExpanded by mutableStateOf(false)
    var textInput by mutableStateOf(false)
    var takingOver by mutableStateOf(false)
    var dragging by mutableStateOf(false)
    var dragOffset by mutableStateOf(0f)
    var minimizing by mutableStateOf(false)
        private set
    var voiceHeld by mutableStateOf(false)
        private set
    private var finishHeldVoice = false
    var supplement by mutableStateOf(false)
    var draft by mutableStateOf("")
    var background by mutableStateOf(false)
    val voice = SpeechInput(service, { text ->
        draft = text
        if (text.isNotBlank()) submit(text)
    }, { voiceHeld = false; service.voiceFinished() })
    val shown: Boolean get() = root != null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.BOTTOM
        title = "ArkAI drawer"
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        setFitInsetsTypes(0)
        setTrustedOverlay()
    }

    init { saved.performRestore(null); registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }

    fun show(effect: AssistantInvocationEffect? = null) {
        main.removeCallbacks(remove)
        minimizing = false; dragging = false; dragOffset = 0f
        if (root == null) {
            val frame = object : FrameLayout(context) {
                private var outsideDown: MotionEvent? = null
                private val touchRegion = ViewTreeObserver.OnComputeInternalInsetsListener { insets ->
                    insets.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
                    // Reserve the system's swipe-up area even when the navigation bar is
                    // hidden. Going Home/Recents must remain available during AI execution.
                    val navigation = rootWindowInsets?.getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.navigationBars() or
                            android.view.WindowInsets.Type.mandatorySystemGestures())?.bottom ?: 0
                    insets.touchableRegion.set(0, 0, width, (height - navigation).coerceAtLeast(0))
                }
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && event.y < (composition?.top ?: height)) {
                        outsideDown?.recycle()
                        outsideDown = MotionEvent.obtain(event)
                    }
                    outsideDown?.let { down ->
                        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                            val slop = ViewConfiguration.get(context).scaledTouchSlop
                            val tapped = event.actionMasked == MotionEvent.ACTION_UP &&
                                kotlin.math.abs(event.x - down.x) < slop && kotlin.math.abs(event.y - down.y) < slop
                            down.recycle(); outsideDown = null
                            val task = TaskState.current
                            // Pausing or waiting for confirmation does not hand control to
                            // the user. Only a completed takeover unlocks outside dismissal.
                            if (tapped && (!task.running || task.userInControl)) minimize()
                        }
                        return true
                    }
                    if (voiceHeld && event.actionMasked == MotionEvent.ACTION_UP) {
                        voiceHeld = false; finishHeldVoice = true
                        if (voice.state.active) voice.finish()
                    } else if (voiceHeld && event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        // A permission dialog may interrupt the first hold; fall back to auto-send.
                        if (voice.state.active) voice.cancel()
                        voiceHeld = false
                    }
                    return super.dispatchTouchEvent(event)
                }
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    val content = composition
                    content?.measure(widthSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightSpec), MeasureSpec.AT_MOST))
                    // Keep the surface stable while Compose animates the bottom-aligned panel.
                    setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
                    val contentHeight = content?.measuredHeight ?: 0
                    backdrop?.let { blur ->
                        blur.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(if (contentHeight == 0) 0 else
                                minOf(measuredHeight, maxOf(contentHeight,
                                    (resources.displayMetrics.density * 280).toInt() + blur.bottomInset)), MeasureSpec.EXACTLY))
                    }
                    invocationGlow?.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
                    powerMenuComposition?.measure(widthSpec,
                        MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.AT_MOST))
                }
                override fun onAttachedToWindow() {
                    super.onAttachedToWindow()
                    viewTreeObserver.addOnComputeInternalInsetsListener(touchRegion)
                    post { findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back) }
                }
                override fun onDetachedFromWindow() {
                    outsideDown?.recycle(); outsideDown = null
                    viewTreeObserver.removeOnComputeInternalInsetsListener(touchRegion)
                    findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(back)
                    super.onDetachedFromWindow()
                }
            }.apply {
                clipChildren = false; clipToPadding = false
                setViewTreeLifecycleOwner(this@AssistantOverlay)
                setViewTreeSavedStateRegistryOwner(this@AssistantOverlay)
            }
            val blur = ProgressiveBlur(context)
            backdrop = blur
            frame.addView(blur, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            val glow = AssistantInvocationGlow(context)
            invocationGlow = glow
            frame.addView(glow, FrameLayout.LayoutParams(-1, -1))
            val compose = ComposeView(context).apply {
                setContent {
                    AssistantTheme {
                        var attached by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { attached = true }
                        val show = attached && visible
                        val strength = animateFloatAsState(if (show) 1f else 0f,
                            tween(if (visible) AssistantMotion.enter else AssistantMotion.exit), label = "backdrop visibility")
                        LaunchedEffect(strength) {
                            snapshotFlow { strength.value }.collect { blur.setStrength(it) }
                        }
                        AnimatedVisibility(show,
                            enter = slideInVertically(tween(AssistantMotion.enter, easing = AssistantMotion.spatial)) { it / 5 } +
                                scaleIn(tween(AssistantMotion.enter), initialScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                                fadeIn(tween(AssistantMotion.change)),
                            exit = slideOutVertically(tween(AssistantMotion.exit)) { it / 4 } +
                                scaleOut(tween(AssistantMotion.exit), targetScale = if (minimizing) 0.85f else 0.96f,
                                    transformOrigin = TransformOrigin(0.5f, 1f)) + fadeOut(tween(AssistantMotion.change))) {
                            AssistantDrawer(this@AssistantOverlay)
                        }
                    }
                }
            }
            frame.addView(compose, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            val powerMenu = ComposeView(context).apply {
                setContent { AssistantTheme { PowerMenuHoldHint(powerMenuDeadline) } }
            }
            powerMenuComposition = powerMenu
            frame.addView(powerMenu, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            // Vote only while drawing, including Compose's child views and the wake glow.
            // Let the display policy choose the high rate supported by the current mode.
            frame.propagateRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH, true)
            root = frame; composition = compose
            manager.addView(frame, params)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        visible = true
        effect?.let { invocationGlow?.play(it) }
    }

    private val remove = Runnable {
        composition?.disposeComposition(); composition = null
        powerMenuComposition?.disposeComposition(); powerMenuComposition = null
        root?.let { manager.removeViewImmediate(it) }; root = null
        backdrop = null
        invocationGlow = null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        service.overlayHidden()
    }

    fun hide() {
        service.cancelPowerKeyHold()
        invocationGlow?.stop()
        voice.cancel(); visible = false; setFocus(false)
        if (root != null) main.postDelayed(remove, (AssistantMotion.exit + 30).toLong()) else service.overlayHidden()
    }

    fun minimize() { minimizing = true; hide() }

    fun setPowerMenuHold(deadline: Long) { powerMenuDeadline = deadline }

    fun setBackdrop(color: Int, voice: Boolean, bottomInset: Int, voiceReveal: Float) {
        backdrop?.configure(color, voice, bottomInset)
        invocationGlow?.setPresentation(bottomInset, voiceReveal)
    }

    fun setFocus(value: Boolean) {
        focus = value
        updateFlags()
    }

    fun setPassthrough(value: Boolean, committed: () -> Unit = {}) {
        main.removeCallbacks(restoreInput)
        passthrough = value; updateFlags()
        val view = root
        if (view == null) committed()
        else view.postOnAnimation { view.postOnAnimation { committed() } }
        if (value) main.postDelayed(restoreInput, 1900)
    }

    private val restoreInput = Runnable { passthrough = false; updateFlags() }

    private fun updateFlags() {
        val previous = params.flags
        params.flags = params.flags and (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).inv()
        if (!focus || passthrough) params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (passthrough) params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (params.flags != previous) root?.let { manager.updateViewLayout(it, params) }
    }

    fun edit() {
        if (TaskState.current.running && !TaskState.current.paused) service.command(AssistantService.PAUSE)
        background = TaskState.current.background
        closeProgress()
        supplement = true; expanded = true; openKeyboard()
    }

    fun openProgress() {
        if (!TaskState.current.running) { closeProgress(); expanded = true; return }
        voice.cancel(); supplement = false; progress = true; expanded = true
        preview = TaskState.current.background
        setFocus(false)
    }

    fun closeProgress() { progress = false; previewExpanded = false }

    fun collapse() { closeProgress(); expanded = false }

    fun openKeyboard() { textInput = true; setFocus(true) }

    fun previewFrame() = service.previewFrame()

    fun displayState(state: Bundle) = service.displayState(state)

    fun submit(text: String = draft) {
        if (text.isBlank()) return
        voice.cancel(); setFocus(false); supplement = false
        textInput = false
        draft = ""
        service.command(AssistantService.START, text.trim(), TaskState.current.running && background)
    }

    fun startVoice() = startVoice(false)

    fun startHeldVoice() = startVoice(true)

    private fun startVoice(held: Boolean) {
        closeProgress()
        voiceHeld = held; finishHeldVoice = false
        if (TaskState.current.running && !TaskState.current.paused) service.command(AssistantService.PAUSE)
        service.requestVoicePermission { granted ->
            if (granted) {
                setFocus(false); voice.start(voiceHeld)
                if (finishHeldVoice) voice.finish()
            } else voiceHeld = false
        }
    }

    fun settings() {
        hide()
        service.startActivity(Intent(service, AssistantSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun command(action: String) = service.command(action)

    fun destroy() {
        service.cancelPowerKeyHold()
        main.removeCallbacksAndMessages(null)
        invocationGlow?.stop()
        voice.destroy(); visible = false
        composition?.disposeComposition(); composition = null
        powerMenuComposition?.disposeComposition(); powerMenuComposition = null
        root?.let { manager.removeViewImmediate(it) }; root = null
        backdrop = null
        invocationGlow = null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

/** Cross-app backdrop blur must sample SurfaceFlinger, not the assistant's Compose layer. */
private class ProgressiveBlur(context: Context) : View(context) {
    private val tint = Paint()
    private val manager = context.getSystemService(WindowManager::class.java)
    private var enabled = false
    private var voice = false
    private var strength = 0f
    var bottomInset = 0
        private set
    private var color = 0xfff8faf5.toInt()
    private val location = IntArray(2)
    private val appliedBounds = RectF()
    private var appliedLayerId = -1
    private var appliedRadius = -1
    private var gradientTop = Float.NaN
    private var gradientBottom = Float.NaN
    private var gradientColor = 0
    private var observer: ViewTreeObserver? = null
    private val listener = java.util.function.Consumer<Boolean> { enabled = it; invalidate() }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val root = viewRootImpl
        val surface = root?.surfaceControl
        if (surface != null && surface.isValid) {
            getLocationInSurface(location)
            val bottom = (height - bottomInset).coerceAtLeast(0).toFloat()
            val depth = minOf(bottom, resources.displayMetrics.density * if (voice) 300 else 280)
            val left = location[0].toFloat()
            val top = location[1] + bottom - depth
            val right = left + width
            val surfaceBottom = location[1] + bottom
            val radius = if (enabled && strength > 0f && depth > 0f && width > 0)
                (resources.displayMetrics.density * 56 * strength).toInt().coerceIn(1, 256) else 0
            val layerId = surface.layerId
            if (layerId == appliedLayerId && radius == appliedRadius &&
                (radius == 0 || (appliedBounds.left == left && appliedBounds.top == top &&
                    appliedBounds.right == right && appliedBounds.bottom == surfaceBottom))) {
                return@OnPreDrawListener true
            }
            // Keep the blur aligned with the panel in the same rendered frame.
            SurfaceControl.Transaction().use { transaction ->
                if (radius > 0) {
                    transaction.setProgressiveBlurRegion(surface, left, top, right, surfaceBottom, radius)
                } else transaction.setBlurRegions(surface, emptyArray())
                root.applyTransactionOnDraw(transaction)
            }
            appliedLayerId = layerId
            appliedRadius = radius
            appliedBounds.set(left, top, right, surfaceBottom)
        }
        true
    }

    fun configure(color: Int, voice: Boolean, bottomInset: Int) {
        if (this.color == color && this.voice == voice && this.bottomInset == bottomInset) return
        if (this.bottomInset != bottomInset) {
            this.bottomInset = bottomInset
            requestLayout()
        }
        this.color = color; this.voice = voice; invalidate()
    }

    fun setStrength(value: Float) {
        if (strength == value) return
        strength = value; invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        appliedLayerId = -1
        appliedRadius = -1
        observer = viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
        manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
        enabled = manager.isCrossWindowBlurEnabled
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The voice sheet owns its blue-to-cyan gradient in the same Compose layer as
        // its text. SurfaceFlinger still supplies the progressively blurred backdrop.
        if (voice) return
        val bottom = (height - bottomInset).coerceAtLeast(0).toFloat()
        val depth = minOf(bottom, resources.displayMetrics.density * if (voice) 300 else 280)
        if (depth <= 0f) return
        val top = bottom - depth
        val rgb = color and 0x00ffffff
        tint.alpha = (strength * 255).toInt()
        if (gradientTop != top || gradientBottom != bottom || gradientColor != rgb) {
            tint.shader = LinearGradient(0f, top, 0f, bottom,
                intArrayOf(rgb, rgb or 0x18000000, rgb or 0x80000000.toInt()),
                floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
            gradientTop = top; gradientBottom = bottom; gradientColor = rgb
        }
        canvas.drawRect(0f, top, width.toFloat(), bottom, tint)
    }

    override fun onDetachedFromWindow() {
        manager.removeCrossWindowBlurEnabledListener(listener)
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw); observer = null
        viewRootImpl?.surfaceControl?.takeIf { it.isValid }?.let { surface ->
            SurfaceControl.Transaction().use { it.setBlurRegions(surface, emptyArray()).apply() }
        }
        super.onDetachedFromWindow()
    }
}
