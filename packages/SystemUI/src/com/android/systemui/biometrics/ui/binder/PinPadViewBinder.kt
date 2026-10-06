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

package com.android.systemui.biometrics.ui.binder

import android.view.KeyEvent
import android.widget.ImeAwareEditText
import com.android.systemui.res.R
import com.android.systemui.biometrics.ui.CredentialPasswordView
import com.android.systemui.biometrics.ui.IPinPad
import com.android.systemui.biometrics.ui.PinPadClickListener

/** Binder for IPinPad */
object PinPadViewBinder {
    /** Implements a PinPadClickListener inside a pin pad */
    @JvmStatic
    fun bind(view: IPinPad, credentialPasswordView: CredentialPasswordView) {
        val passwordField: ImeAwareEditText =
            credentialPasswordView.requireViewById(R.id.lockPassword)
        view.setPinPadClickListener(
            object : PinPadClickListener {

                override fun onDigitKeyClick(digit: String?) {
                    if (!passwordField.isEnabled || digit == null || digit.length != 1
                        || digit[0] !in '0'..'9') return
                    passwordField.append(digit)
                    passwordField.setSelection(passwordField.length())
                }

                override fun onBackspaceClick() {
                    if (!passwordField.isEnabled) return
                    val text = passwordField.text
                    if (text.isEmpty()) return
                    val end = if (passwordField.selectionEnd < 0) text.length
                        else passwordField.selectionEnd.coerceIn(0, text.length)
                    val start = if (passwordField.selectionStart < 0) end
                        else passwordField.selectionStart.coerceIn(0, end)
                    if (start < end) text.delete(start, end)
                    else if (end > 0) text.delete(end - 1, end)
                }

                override fun onEnterKeyClick() {
                    if (!passwordField.isEnabled) return
                    passwordField.dispatchKeyEvent(
                        KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)
                    )
                    passwordField.dispatchKeyEvent(
                        KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)
                    )
                }
            }
        )
    }
}
