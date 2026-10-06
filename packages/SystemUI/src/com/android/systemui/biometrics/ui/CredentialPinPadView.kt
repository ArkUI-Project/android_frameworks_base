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
package com.android.systemui.biometrics.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.android.keyguard.NumPadButton
import com.android.keyguard.NumPadKey
import com.android.systemui.res.R
import kotlin.math.roundToInt

/** The lockscreen's real PIN keys, bound to the credential prompt's verifier. */
class CredentialPinPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), IPinPad {
    private val keys = ArrayList<View>()
    private val rows = ArrayList<LinearLayout>()
    private var listener: PinPadClickListener? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutDirection = LAYOUT_DIRECTION_LTR
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val keyguardContext = ContextThemeWrapper(context, R.style.Theme_SystemUI)
        val actionContainer = FrameLayout(keyguardContext)
        LayoutInflater.from(keyguardContext).inflate(
            R.layout.auth_credential_pin_action_keys, actionContainer, true)
        val deleteKey = actionContainer.requireViewById<CredentialPinActionKey>(
            R.id.credential_pin_backspace)
        val enterKey = actionContainer.requireViewById<CredentialPinActionKey>(
            R.id.credential_pin_confirm)
        actionContainer.removeAllViews()
        deleteKey.reloadColors()
        enterKey.reloadColors()
        deleteKey.setOnClickListener {
            if (deleteKey.isEnabled) {
                deleteKey.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                listener?.onBackspaceClick()
            }
        }
        enterKey.setOnClickListener {
            if (enterKey.isEnabled) {
                enterKey.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                listener?.onEnterKeyClick()
            }
        }
        val keyIds = intArrayOf(
            R.id.credential_pin_key_0, R.id.credential_pin_key_1,
            R.id.credential_pin_key_2, R.id.credential_pin_key_3,
            R.id.credential_pin_key_4, R.id.credential_pin_key_5,
            R.id.credential_pin_key_6, R.id.credential_pin_key_7,
            R.id.credential_pin_key_8, R.id.credential_pin_key_9,
        )
        for (rowIndex in 0..3) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            rows.add(row)
            for (column in 0..2) {
                val key = when {
                    rowIndex == 3 && column == 0 -> deleteKey
                    rowIndex == 3 && column == 2 -> enterKey
                    else -> {
                        val digit = if (rowIndex == 3) 0 else rowIndex * 3 + column + 1
                        CredentialPinDigitKey(keyguardContext).apply {
                            id = keyIds[digit]
                            setDigit(digit)
                            reloadColors()
                            setOnClickListener {
                                if (isEnabled) {
                                    userActivity()
                                    listener?.onDigitKeyClick(digit.toString())
                                }
                            }
                        }
                    }
                }
                keys.add(key)
                row.addView(key, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            }
            addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
    }

    override fun setPinPadClickListener(pinPadClickListener: PinPadClickListener?) {
        listener = pinPadClickListener
    }

    override fun setInputEnabled(enabled: Boolean) {
        isEnabled = enabled
        keys.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.38f
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val horizontalGap = resources.getDimensionPixelSize(
            R.dimen.credential_pin_key_horizontal_spacing)
        val verticalGap = resources.getDimensionPixelSize(R.dimen.credential_pin_key_spacing)
        val preferredSide = resources.getDimensionPixelSize(R.dimen.credential_pin_key_min_height)
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            preferredSide * 3 + horizontalGap * 2
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val side = minOf(preferredSide, (available - horizontalGap * 2) / 3)
            .coerceAtLeast((48 * resources.displayMetrics.density).roundToInt())
        keys.forEachIndexed { index, key ->
            (key.layoutParams as LayoutParams).apply {
                width = side
                height = side
                marginStart = if (index % 3 == 0) 0 else horizontalGap
            }
        }
        rows.forEachIndexed { index, row ->
            (row.layoutParams as LayoutParams).topMargin = if (index == 0) 0 else verticalGap
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(side * 3 + horizontalGap * 2, MeasureSpec.EXACTLY),
            heightMeasureSpec,
        )
    }
}

/** Keep the lockscreen's layout/animation, with an explicit circle instead of its legacy ratio. */
private class CredentialPinDigitKey(context: Context) : NumPadKey(context) {
    init {
        // The native digit's negative bottom padding adjusts its baseline. A wrap-content
        // height also clips that baseline's glyphs; filling the key preserves the same
        // center-vertical baseline while giving the TextView its full drawing bounds.
        findViewById<View>(R.id.digit_text).layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredWidth)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        isEnabled && super.onTouchEvent(event)
}

/** Inflated with the exact NumPadKey.Delete/Enter styles used by the lockscreen. */
class CredentialPinActionKey(context: Context, attrs: AttributeSet) : NumPadButton(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredWidth)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        isEnabled && super.onTouchEvent(event)
}
