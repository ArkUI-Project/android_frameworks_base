/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Display
import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
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
    private var backdrop: ProgressiveBlur? = null
    private var focus = false
    private var passthrough = false
    private val store = ConfigStore(service)
    val configured = store.enabled
    private val back = OnBackInvokedCallback {
        if (voice.state.active || voice.state.error.isNotBlank()) voice.cancel()
        else if (expanded) expanded = false else hide()
    }
    private var visible by mutableStateOf(false)
    var expanded by mutableStateOf(false)
    var supplement by mutableStateOf(false)
    var draft by mutableStateOf("")
    var mode by mutableStateOf(TaskState.current.mode)
    var background by mutableStateOf(store.defaultBackground)
    val voice = SpeechInput(service, { text ->
        draft = text
        if (text.isNotBlank()) submit(text)
    }, { service.voiceFinished() })
    val shown: Boolean get() = root != null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
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

    fun show() {
        main.removeCallbacks(remove)
        if (root == null) {
            val frame = object : FrameLayout(context) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    val content = getChildAt(1)
                    content.measure(widthSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightSpec), MeasureSpec.AT_MOST))
                    setMeasuredDimension(resolveSize(content.measuredWidth, widthSpec), resolveSize(content.measuredHeight, heightSpec))
                    getChildAt(0).measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
                }
                override fun onAttachedToWindow() {
                    super.onAttachedToWindow()
                    post { findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back) }
                }
                override fun onDetachedFromWindow() {
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
            frame.addView(blur, FrameLayout.LayoutParams(-1, -1))
            val compose = ComposeView(context).apply {
                setContent {
                    AssistantTheme {
                        AnimatedVisibility(visible,
                            enter = slideInVertically(tween(160, easing = LinearOutSlowInEasing)) { it / 5 } + fadeIn(tween(100)),
                            exit = slideOutVertically(tween(120)) { it / 5 } + fadeOut(tween(100))) {
                            AssistantDrawer(this@AssistantOverlay)
                        }
                    }
                }
            }
            frame.addView(compose, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            root = frame; composition = compose
            manager.addView(frame, params)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        visible = true
    }

    private val remove = Runnable {
        composition?.disposeComposition(); composition = null
        root?.let { manager.removeViewImmediate(it) }; root = null
        backdrop = null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        service.overlayHidden()
    }

    fun hide() {
        voice.cancel(); visible = false; setFocus(false)
        if (root != null) main.postDelayed(remove, 150) else service.overlayHidden()
    }

    fun setBackdrop(color: Int, voice: Boolean) { backdrop?.configure(color, voice) }

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
        params.flags = params.flags and (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).inv()
        if (!focus || passthrough) params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (passthrough) params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        root?.let { manager.updateViewLayout(it, params) }
    }

    fun edit() {
        if (TaskState.current.running && !TaskState.current.paused) service.command(AssistantService.PAUSE)
        mode = TaskState.current.mode
        background = TaskState.current.background
        supplement = true; expanded = true; setFocus(true)
    }

    fun submit(text: String = draft) {
        if (text.isBlank()) return
        voice.cancel(); setFocus(false); supplement = false
        draft = ""
        service.command(AssistantService.START, text.trim(), mode, background)
    }

    fun startVoice() {
        if (TaskState.current.running && !TaskState.current.paused) service.command(AssistantService.PAUSE)
        service.requestVoicePermission { granted -> if (granted) { setFocus(false); voice.start() } }
    }

    fun settings() {
        hide()
        service.startActivity(Intent(service, AssistantSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun command(action: String) = service.command(action)

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        voice.destroy(); visible = false
        composition?.disposeComposition(); composition = null
        root?.let { manager.removeViewImmediate(it) }; root = null
        backdrop = null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

/** Cross-app backdrop blur must sample SurfaceFlinger, not the assistant's Compose layer. */
private class ProgressiveBlur(context: Context) : View(context) {
    private val tint = Paint()
    private val manager = context.getSystemService(WindowManager::class.java)
    private var enabled = false
    private var voice = false
    private var color = 0xfff8faf5.toInt()
    private val location = IntArray(2)
    private var observer: ViewTreeObserver? = null
    private val listener = java.util.function.Consumer<Boolean> { enabled = it; invalidate() }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val root = viewRootImpl
        val surface = root?.surfaceControl
        if (surface != null && surface.isValid) {
            getLocationInSurface(location)
            val depth = minOf(height.toFloat(), resources.displayMetrics.density * if (voice) 300 else 280)
            val top = location[1] + height - depth
            // Use surface-local bounds and commit with the resized window's buffer.
            SurfaceControl.Transaction().use { transaction ->
                if (enabled && depth > 0f && width > 0) {
                    transaction.setProgressiveBlurRegion(surface, location[0].toFloat(), top,
                        (location[0] + width).toFloat(), (location[1] + height).toFloat(),
                        (resources.displayMetrics.density * 56).toInt().coerceIn(1, 256))
                } else transaction.setBlurRegions(surface, emptyArray())
                root.applyTransactionOnDraw(transaction)
            }
        }
        true
    }

    fun configure(color: Int, voice: Boolean) {
        if (this.color == color && this.voice == voice) return
        this.color = color; this.voice = voice; invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observer = viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
        manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
        enabled = manager.isCrossWindowBlurEnabled
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val depth = minOf(height.toFloat(), resources.displayMetrics.density * if (voice) 300 else 280)
        val top = height - depth
        val rgb = color and 0x00ffffff
        tint.shader = if (voice) LinearGradient(0f, top, 0f, height.toFloat(),
            intArrayOf(rgb, rgb or 0x73000000, rgb or 0xe6000000.toInt(), rgb or 0xfa000000.toInt()),
            floatArrayOf(0f, 0.25f, 0.6f, 1f), Shader.TileMode.CLAMP)
        else LinearGradient(0f, top, 0f, height.toFloat(),
            intArrayOf(rgb, rgb or 0x18000000, rgb or 0x80000000.toInt()),
            floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), tint)
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
