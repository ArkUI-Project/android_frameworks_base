/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.biometrics.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest
import com.android.keyguard.NumPadButton
import com.android.keyguard.NumPadKey
import com.android.keyguard.PasswordTextView
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class CredentialPinPadViewTest : SysuiTestCase() {
    @Test
    fun portraitNativeKeysHaveCenteredUnclippedDigitsAtBothFontScales() {
        verifyActualLayout(Configuration.ORIENTATION_PORTRAIT, "portrait")
    }

    @Test
    fun landscapeNativeKeysHaveCenteredUnclippedDigitsAtBothFontScales() {
        verifyActualLayout(Configuration.ORIENTATION_LANDSCAPE, "landscape")
    }

    @Test
    fun pinContentKeepsReturnHeaderAndInputViewportInsideSystemInsetsWithoutImeOrAccumulation() {
        for (orientation in listOf(
                Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE)) {
            for (fontScale in listOf(1f, 2f)) {
                val theme = createPinTheme(orientation, fontScale)
                val view = LayoutInflater.from(theme).inflate(R.layout.auth_credential_pin_view, null)
                        as CredentialPasswordView
                view.requireViewById<TextView>(R.id.title).text = "Verify identity"
                val status = Insets.of(0, dp(theme, 40), 0, 0)
                val navigation = Insets.of(dp(theme, 9), 0, dp(theme, 42), dp(theme, 28))
                val cutout = Insets.of(dp(theme, 30), dp(theme, 54), dp(theme, 18), dp(theme, 12))
                val safe = Insets.of(cutout.left, cutout.top, navigation.right, navigation.bottom)
                val insets = WindowInsets.Builder()
                        .setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(), status)
                        .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), navigation)
                        .setInsetsIgnoringVisibility(WindowInsets.Type.displayCutout(), cutout)
                        .setInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                                Insets.NONE)
                        .setVisible(WindowInsets.Type.systemBars(), false)
                        .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, dp(theme, 300)))
                        .setVisible(WindowInsets.Type.ime(), true)
                        .build()
                for (repeat in 0..1) {
                    assertTrue("the PIN root must consume its insets exactly once",
                            view.dispatchApplyWindowInsets(insets).isConsumed)
                    assertEquals(safe, Insets.of(view.paddingLeft, view.paddingTop,
                            view.paddingRight, view.paddingBottom))
                    layoutFullPrompt(view, orientation)
                    val safeBounds = Rect(safe.left, safe.top,
                            view.width - safe.right, view.height - safe.bottom)
                    assertDescendantInside(view, view.requireViewById(R.id.back_button), safeBounds)
                    assertDescendantInside(view, view.requireViewById(R.id.title), safeBounds)
                    var viewport = view.requireViewById<View>(R.id.pin_pad).parent as View
                    while (viewport !is ScrollView) viewport = viewport.parent as View
                    // Large fonts may require scrolling; the actual scrolling viewport, including
                    // both landscape panes, must still end before the navigation/cutout safe edge.
                    assertDescendantInside(view, viewport, safeBounds)
                }
                val withoutIme = WindowInsets.Builder(insets)
                        .setInsets(WindowInsets.Type.ime(), Insets.NONE)
                        .setVisible(WindowInsets.Type.ime(), false).build()
                view.dispatchApplyWindowInsets(withoutIme)
                assertEquals("native PIN content must not follow an IME animation", safe,
                        Insets.of(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom))
                view.dispatchApplyWindowInsets(WindowInsets.Builder().build())
                assertEquals("removing insets must remove their padding instead of retaining it", Insets.NONE,
                        Insets.of(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom))

                val password = LayoutInflater.from(theme)
                        .inflate(R.layout.auth_credential_password_view, null) as CredentialPasswordView
                assertTrue(password.findViewById<View>(R.id.pin_pad) == null)
                password.dispatchApplyWindowInsets(insets)
                assertEquals("password credentials must retain their existing IME avoidance",
                        dp(theme, 300), password.paddingBottom)
            }
        }
    }

    @Test
    fun fullCredentialParentKeepsPinSafeWhenInsetsAreConsumedOrContainNoSystemBars() {
        for (orientation in listOf(
                Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE)) {
            for (fontScale in listOf(1f, 2f)) {
                val manager = mock(WindowManager::class.java)
                val theme = createPinTheme(orientation, fontScale, manager)
                val safe = Insets.of(dp(theme, 18), dp(theme, 54), dp(theme, 42), dp(theme, 28))
                val displayInsets = WindowInsets.Builder()
                        .setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(),
                                Insets.of(0, dp(theme, 40), 0, 0))
                        .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(),
                                Insets.of(0, 0, safe.right, safe.bottom))
                        .setInsetsIgnoringVisibility(WindowInsets.Type.displayCutout(),
                                Insets.of(safe.left, safe.top, 0, 0))
                        .setVisible(WindowInsets.Type.systemBars(), false)
                        .build()
                whenever(manager.maximumWindowMetrics)
                        .thenReturn(windowMetrics(theme, orientation, displayInsets))

                // Use the same LinearLayout -> real one/two-pane ConstraintLayout -> credential
                // FrameLayout -> PIN page chain as AuthContainerView's fullscreen credential.
                val outer = LinearLayout(theme).apply { this.orientation = LinearLayout.VERTICAL }
                val layout = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    R.layout.biometric_prompt_two_pane_layout
                } else {
                    R.layout.biometric_prompt_one_pane_layout
                }
                val container = LayoutInflater.from(theme).inflate(layout, outer, false)
                        as ConstraintLayout
                outer.addView(container, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val credential = container.requireViewById<FrameLayout>(R.id.credential_view)
                val pin = LayoutInflater.from(theme).inflate(
                        R.layout.auth_credential_pin_view, credential, false) as CredentialPasswordView
                pin.requireViewById<TextView>(R.id.title).text = "Verify identity"
                credential.addView(pin)
                ConstraintSet().apply {
                    clone(container)
                    constrainMaxWidth(R.id.panel, 0)
                    setGuidelineBegin(R.id.leftGuideline, 0)
                    setGuidelineBegin(R.id.topGuideline, 0)
                    setGuidelineEnd(R.id.rightGuideline, 0)
                    setVisibility(R.id.credential_view, View.VISIBLE)
                    setVisibility(R.id.fallback_view, View.GONE)
                    setVisibility(R.id.auth_screen, View.GONE)
                    setVisibility(R.id.biometric_icon, View.GONE)
                    applyTo(container)
                }

                // No child callback runs when the parent consumes the event. Execute the real
                // visible lifecycle callback, which must obtain the full display metrics itself.
                outer.setOnApplyWindowInsetsListener { _, _ -> WindowInsets.CONSUMED }
                assertTrue(outer.dispatchApplyWindowInsets(displayInsets).isConsumed)
                assertEquals("this fixture must initially have no delivered child insets", 0,
                        pin.paddingTop)
                pin.onVisibilityAggregated(true)
                for (repeat in 0..1) {
                    outer.dispatchApplyWindowInsets(displayInsets)
                    pin.onVisibilityAggregated(true)
                    assertEquals(safe, Insets.of(pin.paddingLeft, pin.paddingTop,
                            pin.paddingRight, pin.paddingBottom))
                    layoutFullPrompt(outer, orientation)
                    val safeBounds = Rect(safe.left, safe.top,
                            outer.width - safe.right, outer.height - safe.bottom)
                    assertDescendantInside(outer, pin.requireViewById(R.id.back_button), safeBounds)
                    assertDescendantInside(outer, pin.requireViewById(R.id.title), safeBounds)
                    var viewport = pin.requireViewById<View>(R.id.pin_pad).parent as View
                    while (viewport !is ScrollView) viewport = viewport.parent as View
                    assertDescendantInside(outer, viewport, safeBounds)
                }

                // A dialog above the bars can instead receive a callback with no bar sources.
                outer.setOnApplyWindowInsetsListener(null)
                outer.dispatchApplyWindowInsets(WindowInsets.Builder().build())
                assertEquals("zero-source window insets must retain the display's safe area", safe,
                        Insets.of(pin.paddingLeft, pin.paddingTop, pin.paddingRight, pin.paddingBottom))

                // A subsequent display configuration may remove or relocate those sources. Do
                // not cache the old callback or repeatedly add its padding during visibility.
                whenever(manager.maximumWindowMetrics).thenReturn(
                        windowMetrics(theme, orientation, WindowInsets.Builder().build()))
                pin.onVisibilityAggregated(false)
                pin.onVisibilityAggregated(true)
                assertEquals("display changes must not leave stale safe-area padding", Insets.NONE,
                        Insets.of(pin.paddingLeft, pin.paddingTop, pin.paddingRight, pin.paddingBottom))
            }
        }
    }

    @Test
    fun disablingEveryNativeKeyBlocksClicksAndTouchAnimationsThenAllowsInputAgain() {
        for (orientation in listOf(
                Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE)) {
            val pad = inflateAndLayout(orientation, 1f)
            val clicks = ArrayList<String>()
            pad.setPinPadClickListener(object : PinPadClickListener {
                override fun onDigitKeyClick(digit: String?) { clicks.add("digit:$digit") }
                override fun onBackspaceClick() { clicks.add("delete") }
                override fun onEnterKeyClick() { clicks.add("confirm") }
            })
            pad.setInputEnabled(false)
            assertFalse(pad.isEnabled)
            for (id in ALL_KEYS) {
                val key = pad.requireViewById<View>(id)
                assertFalse("every input target must be disabled", key.isEnabled)
                assertTrue("disabled native keys must remain visibly subdued", key.alpha < 1f)
                val background = key.background as GradientDrawable
                val radiusBefore = background.cornerRadius
                val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
                        key.width / 2f, key.height / 2f, 0)
                try {
                    assertFalse("disabled keys must not start the native press animation",
                            key.onTouchEvent(down))
                } finally {
                    down.recycle()
                }
                assertEquals(radiusBefore, background.cornerRadius, 0f)
                // performClick bypasses View's touch gate, so the real listeners must also gate.
                key.performClick()
            }
            assertTrue("disabled digits and actions must never invoke the verifier", clicks.isEmpty())

            pad.setInputEnabled(true)
            assertTrue(pad.isEnabled)
            for (id in ALL_KEYS) {
                val key = pad.requireViewById<View>(id)
                assertTrue(key.isEnabled)
                assertEquals(1f, key.alpha, 0f)
                key.performClick()
            }
            assertEquals((0..9).map { "digit:$it" } + listOf("delete", "confirm"), clicks)
            pad.setPinPadClickListener(null)
            ALL_KEYS.forEach { pad.requireViewById<View>(it).performClick() }
            assertEquals("detaching the verifier must remove every callback", 12, clicks.size)
        }
    }

    private fun verifyActualLayout(orientation: Int, label: String) {
        for (fontScale in listOf(1f, 2f)) {
            val pad = inflateAndLayout(orientation, fontScale)
            val bitmap = drawNewComponent(pad)
            try {
                val positions = ArrayList<Rect>()
                val firstSide = pad.requireViewById<View>(DIGITS[0]).width
                for ((index, id) in ALL_KEYS.withIndex()) {
                    val key = pad.requireViewById<View>(id)
                    val description = "$label fontScale=$fontScale key=$index"
                    if (index < DIGITS.size) {
                        assertTrue("$description must reuse the lockscreen digit class", key is NumPadKey)
                        verifyDigitDrawing(key as NumPadKey, index.toString(), description)
                    } else {
                        assertTrue("$description must reuse the lockscreen action class", key is NumPadButton)
                        assertNotNull("$description must retain its native action icon",
                                (key as NumPadButton).drawable)
                    }
                    assertEquals("$description must have a circular touch target", key.width, key.height)
                    assertEquals("$description must match the other input targets", firstSide, key.width)
                    assertTrue("$description must retain a 48dp minimum touch target",
                            key.width >= dp(pad.context, 48))
                    assertTrue("$description must use the native animated background",
                            key.background is GradientDrawable)
                    val background = key.background as GradientDrawable
                    val outline = Outline()
                    background.getOutline(outline)
                    val outlineBounds = Rect()
                    assertTrue("$description must have a round native outline", outline.getRect(outlineBounds))
                    assertEquals(Rect(0, 0, key.width, key.height), outlineBounds)
                    assertEquals("$description must render a circle, including landscape actions",
                            key.width / 2f, outline.radius, 1f)

                    val position = Rect(0, 0, key.width, key.height)
                    pad.offsetDescendantRectToMyCoords(key, position)
                    assertTrue("$description must lie inside the measured pad",
                            Rect(0, 0, pad.width, pad.height).contains(position))
                    for (previous in positions) {
                        assertFalse("native touch targets must not overlap", Rect.intersects(previous, position))
                    }
                    positions.add(position)
                }
                assertEquals("all ten digits and both actions must be laid out", 12, positions.size)
                if (fontScale == 1f) writeComponentArtifact(bitmap, label)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun createPinTheme(
        orientation: Int,
        fontScale: Float,
        windowManager: WindowManager? = null,
    ): ContextThemeWrapper {
        val configuration = Configuration(mContext.resources.configuration).apply {
            this.orientation = orientation
            this.fontScale = fontScale
            screenWidthDp = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 891 else 411
            screenHeightDp = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 411 else 891
            smallestScreenWidthDp = 411
        }
        val powerManager = mock(PowerManager::class.java)
        val configuredContext = mContext.createConfigurationContext(configuration)
        val manager = windowManager ?: mock(WindowManager::class.java).also {
            whenever(it.maximumWindowMetrics).thenReturn(
                    windowMetrics(configuredContext, orientation, WindowInsets.Builder().build()))
        }
        val ownContext = object : ContextWrapper(configuredContext) {
            override fun getSystemService(name: String): Any? = when (name) {
                Context.POWER_SERVICE -> powerManager
                Context.WINDOW_SERVICE -> manager
                else -> super.getSystemService(name)
            }
        }
        return ContextThemeWrapper(
                ContextThemeWrapper(ownContext, R.style.Theme_SystemUI),
                R.style.LockPinPasswordContainerStyle)
    }

    private fun windowMetrics(context: Context, orientation: Int, insets: WindowInsets): WindowMetrics {
        val width = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 891 else 411
        val height = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 411 else 891
        return WindowMetrics(Rect(0, 0, dp(context, width), dp(context, height)), insets,
                context.resources.displayMetrics.density)
    }

    private fun inflateAndLayout(orientation: Int, fontScale: Float): CredentialPinPadView {
        val theme = createPinTheme(orientation, fontScale)
        val input = LayoutInflater.from(theme).inflate(R.layout.auth_credential_pin_input, null)
        assertTrue("the real input layout must retain native lockscreen password dots",
                input.requireViewById<View>(R.id.credential_pin_dots) is PasswordTextView)
        val pad = input.requireViewById<CredentialPinPadView>(R.id.pin_pad)
        // The real landscape prompt uses half the screen for input. Its ScrollView measures
        // content with unbounded height, allowing natural growth at large accessibility fonts.
        val inputWidth = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 445 else 411
        input.measure(View.MeasureSpec.makeMeasureSpec(dp(theme, inputWidth), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        input.layout(0, 0, input.measuredWidth, input.measuredHeight)
        dispatchRegisteredPreDraw(input)
        assertTrue(pad.isLaidOut)
        assertTrue(pad.width > 0 && pad.height > 0)
        return pad
    }

    private fun layoutFullPrompt(view: View, orientation: Int) {
        val width = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 891 else 411
        val height = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 411 else 891
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(view.context, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(view.context, height), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        dispatchRegisteredPreDraw(view)
    }

    private fun assertDescendantInside(root: ViewGroup, child: View, safeBounds: Rect) {
        val bounds = Rect(0, 0, child.width, child.height)
        root.offsetDescendantRectToMyCoords(child, bounds)
        assertFalse("the credential element must have a measured area", bounds.isEmpty)
        assertTrue("credential element ${child.id} must avoid unsafe areas: $bounds/$safeBounds",
                safeBounds.contains(bounds))
    }

    private fun dispatchRegisteredPreDraw(root: View) {
        // View.draw(Canvas) skips ViewRootImpl's pre-draw phase. Native single-line TextViews
        // use a VERY_WIDE Layout and register onPreDraw to bring their text into the viewport.
        // Unattached children each own a floating observer; attachment normally merges these
        // into the window observer. Dispatch their real listeners, once per observer, instead
        // of replacing native gravity or computing a synthetic scroll position in this test.
        val observers = Collections.newSetFromMap(IdentityHashMap<ViewTreeObserver, Boolean>())
        fun visit(view: View) {
            val observer = view.viewTreeObserver
            if (observers.add(observer)) {
                assertFalse("the native pre-draw phase must allow the component render",
                        observer.dispatchOnPreDraw())
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
    }

    private fun verifyDigitDrawing(key: NumPadKey, digit: String, description: String) {
        val text = key.requireViewById<TextView>(R.id.digit_text)
        assertEquals("$description must render its actual digit", digit, text.text.toString())
        assertEquals(digit, key.contentDescription.toString())
        assertEquals("$description must center the real child bounds",
                key.width / 2f, (text.left + text.right) / 2f, 1f)
        val layout = requireNotNull(text.layout) { "$description must have a measured text layout" }
        assertEquals(1, layout.lineCount)
        assertEquals("$description must not truncate its digit", 0, layout.getEllipsisCount(0))
        val drawingOriginX = text.left + text.compoundPaddingLeft - text.scrollX
        val drawingCenterX = drawingOriginX + (layout.getLineLeft(0) + layout.getLineRight(0)) / 2f
        // Child bounds alone miss the case where a wide TextView draws its text left-aligned.
        assertEquals("$description must center the text's actual advance",
                key.width / 2f, drawingCenterX, 1f)

        val ink = Rect()
        text.paint.getTextBounds(digit, 0, digit.length, ink)
        assertFalse("$description must contain visible glyph ink", ink.isEmpty)
        val originX = drawingOriginX + layout.getLineLeft(0)
        val baseline = text.top + text.totalPaddingTop - text.scrollY + layout.getLineBaseline(0)
        val drawnInk = RectF(originX + ink.left, baseline + ink.top.toFloat(),
                originX + ink.right, baseline + ink.bottom.toFloat())
        val visible = RectF(0f, 0f, key.width.toFloat(), key.height.toFloat())
        if (key.clipChildren) {
            visible.intersect(text.left.toFloat(), text.top.toFloat(),
                    text.right.toFloat(), text.bottom.toFloat())
        }
        assertTrue("$description must not crop the painted digit, including the child clip: $drawnInk/$visible",
                drawnInk.left >= visible.left - 1f && drawnInk.top >= visible.top - 1f &&
                drawnInk.right <= visible.right + 1f && drawnInk.bottom <= visible.bottom + 1f)
    }

    private fun drawNewComponent(pad: CredentialPinPadView): Bitmap {
        // This bitmap contains only our newly inflated test keypad. It never captures a window,
        // the real credential input, entered PINs, or FLAG_SECURE content.
        val bitmap = Bitmap.createBitmap(pad.width, pad.height, Bitmap.Config.ARGB_8888)
        val attributes = pad.context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        val background = attributes.getColor(0, Color.BLACK)
        attributes.recycle()
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        pad.draw(canvas)
        return bitmap
    }

    private fun writeComponentArtifact(bitmap: Bitmap, orientation: String) {
        val output = File(mContext.filesDir, "arkui-pin-pad-test-$orientation.png")
        FileOutputStream(output).use {
            assertTrue("the component render must be saved", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        assertTrue(output.length() > 0)
        Log.i("CredentialPinPadViewTest", "New test keypad component render: ${output.absolutePath}")
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    companion object {
        private val DIGITS = intArrayOf(
                R.id.credential_pin_key_0, R.id.credential_pin_key_1,
                R.id.credential_pin_key_2, R.id.credential_pin_key_3,
                R.id.credential_pin_key_4, R.id.credential_pin_key_5,
                R.id.credential_pin_key_6, R.id.credential_pin_key_7,
                R.id.credential_pin_key_8, R.id.credential_pin_key_9)
        private val ALL_KEYS = DIGITS + intArrayOf(R.id.credential_pin_backspace, R.id.credential_pin_confirm)
    }
}
