package com.android.systemui.biometrics.ui.binder

import android.graphics.Rect
import android.hardware.biometrics.Flags
import android.os.SystemClock
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImeAwareEditText
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.keyguard.PasswordTextView
import com.android.systemui.biometrics.domain.interactor.BiometricPromptView
import com.android.systemui.biometrics.ui.CredentialPasswordView
import com.android.systemui.biometrics.ui.CredentialView
import com.android.systemui.biometrics.ui.IPinPad
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import java.nio.CharBuffer
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

/** Sub-binder for the [CredentialPasswordView]. */
object CredentialPasswordViewBinder {

    /** Bind the view. */
    fun bind(
        view: CredentialPasswordView,
        host: CredentialView.Host,
        viewModel: CredentialViewModel,
        requestFocusForInput: Boolean,
    ) {
        val imeManager = view.context.getSystemService(InputMethodManager::class.java)!!

        val passwordField: ImeAwareEditText = view.requireViewById(R.id.lockPassword)
        val pinPadView = view.findViewById(R.id.pin_pad) as? IPinPad
        val pinDots = view.findViewById<PasswordTextView>(R.id.credential_pin_dots)

        if (pinPadView != null) {
            pinDots?.setShowPassword(false)
            pinDots?.setUsePinShapes(true)
            pinDots?.setIsPinHinting(false)
            pinDots?.isEnabled = false
            // Configure this before any focus request, including the first attached frame.
            passwordField.showSoftInputOnFocus = false
            passwordField.transformationMethod = PinDotTransformation
            passwordField.isEnabled = false
            pinPadView.setInputEnabled(false)
            passwordField.isCursorVisible = false
            passwordField.isLongClickable = false
            passwordField.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            passwordField.customSelectionActionModeCallback = NoPinSelection
            passwordField.customInsertionActionModeCallback = NoPinSelection
        }

        var attachment = 0
        var aborted = false
        var clearPinInput: (() -> Unit)? = null
        val onBackInvokedCallback = OnBackInvokedCallback {
            if (pinPadView == null) {
                host.onCredentialAborted()
                return@OnBackInvokedCallback
            }
            if (aborted) return@OnBackInvokedCallback
            aborted = true
            clearPinInput?.invoke()
            host.onCredentialAborted()
        }

        view.repeatWhenAttached {
            val generation = ++attachment
            var active = true
            var verifying = false
            var matched = false
            var throttled = false
            var credentialActive = true
            var verificationJob: Job? = null
            var pinTextWatcher: TextWatcher? = null
            fun isCurrent(): Boolean =
                active && attachment == generation && view.isAttachedToWindow
            fun enablePinInput(enabled: Boolean) {
                if (pinPadView != null && isCurrent()) {
                    val restoreFocus = enabled && !passwordField.isEnabled && requestFocusForInput
                    passwordField.isEnabled = enabled
                    pinPadView.setInputEnabled(enabled)
                    pinDots?.isEnabled = enabled
                    if (restoreFocus) {
                        // Disabling the editor for verification clears its focus. Restore it once
                        // when input becomes available, rather than stealing focus on timer polls.
                        passwordField.requestFocus()
                        passwordField.setSelection(passwordField.length())
                    }
                }
            }
            fun updatePinInput() {
                enablePinInput(!verifying && !matched && !throttled && !aborted && credentialActive)
            }
            fun clearPinListeners() {
                if (pinPadView == null || attachment != generation) return
                passwordField.text.clear()
                pinDots?.reset(false, false)
                pinTextWatcher?.let { passwordField.removeTextChangedListener(it) }
                pinTextWatcher = null
                pinDots?.isEnabled = false
                passwordField.isEnabled = false
                pinPadView.setInputEnabled(false)
                pinPadView.setPinPadClickListener(null)
                passwordField.setOnEditorActionListener(null)
                passwordField.setOnKeyListener(null)
                passwordField.setOnTouchListener(null)
                view.findViewById<View>(R.id.back_button)?.setOnClickListener(null)
            }
            enablePinInput(false)
            try {
                // the header info never changes - do it early
                val header = viewModel.header.first()
                passwordField.setTextOperationUser(UserHandle.of(header.user.userIdForPasswordEntry))
                viewModel.inputBoxContentDescription.firstOrNull()?.let { descriptionId ->
                    passwordField.contentDescription = view.context.getString(descriptionId)
                }
                viewModel.inputFlags.firstOrNull()?.let { flags -> passwordField.inputType = flags }
                if (pinPadView != null) {
                    // Unlike the standard password transformation, never reveal the newest digit.
                    passwordField.transformationMethod = PinDotTransformation
                    passwordField.text.clear()
                    if (pinDots != null) {
                        pinDots.reset(false, false)
                        pinTextWatcher = object : TextWatcher {
                            override fun beforeTextChanged(
                                s: CharSequence?, start: Int, count: Int, after: Int,
                            ) {}
                            override fun onTextChanged(
                                s: CharSequence?, start: Int, before: Int, count: Int,
                            ) {}
                            override fun afterTextChanged(text: Editable?) {
                                if (!isCurrent()) return
                                // The native dot renderer mirrors length only, never the secret.
                                val length = text?.length ?: 0
                                if (length == 0) {
                                    pinDots.reset(false, false)
                                } else {
                                    while (pinDots.text.length > length) pinDots.deleteLastChar()
                                    while (pinDots.text.length < length) pinDots.append('\u2022')
                                }
                            }
                        }
                        passwordField.addTextChangedListener(pinTextWatcher)
                    }
                    passwordField.setOnTouchListener { _, event ->
                        if (passwordField.isEnabled && event.action == MotionEvent.ACTION_UP) {
                            passwordField.requestFocus()
                            passwordField.setSelection(passwordField.length())
                        }
                        // Keep dots focused without selection handles, paste, or an IME.
                        true
                    }
                    PinPadViewBinder.bind(pinPadView, view)
                    view.findViewById<View>(R.id.back_button)?.setOnClickListener {
                        onBackInvokedCallback.onBackInvoked()
                    }
                    clearPinInput = {
                        if (attachment == generation) {
                            verificationJob?.cancel()
                            clearPinListeners()
                        }
                    }
                }
                suspend fun refreshPinLockout() {
                    if (pinPadView == null) return
                    val deadline = viewModel.getPinLockoutDeadline(header)
                    if (!isCurrent() || matched || aborted) return
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    val wasThrottled = throttled
                    throttled = remaining > 0
                    if (throttled) {
                        passwordField.text.clear()
                        viewModel.showPinLockoutError(remaining)
                    } else if (wasThrottled) {
                        viewModel.resetErrorMessage()
                    }
                    updatePinInput()
                }
                if (requestFocusForInput && pinPadView == null) {
                    passwordField.requestFocus()
                    passwordField.scheduleShowSoftInput()
                }
                passwordField.setOnEditorActionListener(
                    OnImeSubmitListener { text ->
                        if (pinPadView == null) {
                            lifecycleScope.launch { viewModel.checkCredential(text, header) }
                        } else if (isCurrent() && !verifying && !matched && !aborted
                            && !throttled && passwordField.isEnabled
                            && text.isNotEmpty()) {
                            // Set this synchronously before launching: repeated Enter events must not
                            // race the first check. A throttled check suspends until its timer ends.
                            verifying = true
                            enablePinInput(false)
                            // The background verifier must not observe an editable we clear below.
                            // Use a separate, erasable copy rather than retaining an immutable PIN.
                            val credential = CharArray(text.length) { text[it] }
                            passwordField.text.clear()
                            verificationJob = lifecycleScope.launch {
                                try {
                                    viewModel.checkCredential(CharBuffer.wrap(credential), header)
                                } finally {
                                    credential.fill('\u0000')
                                    verifying = false
                                    if (isCurrent() && !matched && !aborted) refreshPinLockout()
                                }
                            }
                            // A launch into an already cancelled lifecycle need not execute its body.
                            verificationJob?.invokeOnCompletion { credential.fill('\u0000') }
                        }
                    }
                )
                passwordField.setOnKeyListener(OnBackButtonListener(onBackInvokedCallback))
                if (pinPadView != null) {
                    // Restore the effective user's saved lockout before enabling any key.
                    refreshPinLockout()
                    lifecycleScope.launch {
                        while (isCurrent() && !matched && !aborted) {
                            delay(1_000L)
                            refreshPinLockout()
                        }
                    }
                    // A previous application's IME can still be visible when this prompt takes focus.
                    imeManager.hideSoftInputFromWindow(view.windowToken, 0)
                }
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    if (pinPadView != null) {
                        launch {
                            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                // Attach can precede IMM's served-view/window-focus handshake.
                                view.windowInsetsController?.hide(WindowInsets.Type.ime())
                                imeManager.hideSoftInputFromWindow(view.windowToken, 0)
                                awaitCancellation()
                            }
                        }
                    }
                    // dismiss on a valid credential check
                    if (Flags.bpFallbackOptions()) {
                        launch {
                            viewModel.currentView.collect { currentView ->
                                // Hide keyboard if we are no longer on credential screen
                                if (currentView != BiometricPromptView.CREDENTIAL) {
                                    if (pinPadView != null) passwordField.text.clear()
                                    imeManager.hideSoftInputFromWindow(
                                        view.windowToken,
                                        0, // flag
                                    )
                                }
                                credentialActive = currentView == BiometricPromptView.CREDENTIAL
                                updatePinInput()
                            }
                        }

                    }

                    launch {
                        val attestations = if (Flags.bpFallbackOptions()) {
                            combine(viewModel.validatedAttestation, viewModel.isCredentialAllowed, ::Pair)
                        } else {
                            viewModel.validatedAttestation.map { it to true }
                        }
                        attestations.collect { (attestation, isAllowed) ->
                            if (pinPadView != null && (!isCurrent() || matched || aborted)) {
                                return@collect
                            }
                            if (attestation != null) {
                                matched = true
                                clearPinListeners()
                                imeManager.hideSoftInputFromWindow(view.windowToken, 0)
                                if (Flags.bpFallbackOptions()) {
                                    host.onCredentialMatched(attestation, isAllowed)
                                } else {
                                    host.onCredentialMatched(attestation)
                                }
                                viewModel.resetAttestation()
                            } else {
                                passwordField.setText("")
                                updatePinInput()
                            }
                        }
                    }

                    val onBackInvokedDispatcher = view.findOnBackInvokedDispatcher()
                    if (onBackInvokedDispatcher != null) {
                        launch {
                                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                                    onBackInvokedCallback,
                                )
                                awaitCancellation()
                            }
                            .invokeOnCompletion {
                                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(
                                    onBackInvokedCallback
                                )
                            }
                    }
                }
            } finally {
                active = false
                // Cancellation of an old attachment can finish after a new one is installed.
                // It must neither erase the new PIN nor enable its keypad.
                if (attachment == generation && pinPadView != null) {
                    clearPinListeners()
                    clearPinInput = null
                    attachment++
                }
            }
        }
    }
}

/** PIN dots must never expose a character, even when the device's show-password setting is on. */
private object PinDotTransformation : PasswordTransformationMethod() {
    override fun getTransformation(source: CharSequence?, view: View?): CharSequence? =
        source?.let { value ->
            object : CharSequence {
                override val length: Int get() = value.length
                override fun get(index: Int): Char {
                    if (index !in 0 until length) throw IndexOutOfBoundsException()
                    return '\u2022'
                }
                override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
                    if (startIndex < 0 || endIndex < startIndex || endIndex > length) {
                        throw IndexOutOfBoundsException()
                    }
                    return "\u2022".repeat(endIndex - startIndex)
                }
                override fun toString(): String = "\u2022".repeat(length)
            }
        }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun onFocusChanged(
        view: View?, sourceText: CharSequence?, focused: Boolean,
        direction: Int, previouslyFocusedRect: Rect?,
    ) {}
}

private object NoPinSelection : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
    override fun onDestroyActionMode(mode: ActionMode?) {}
}

private class OnBackButtonListener(private val onBackInvokedCallback: OnBackInvokedCallback) :
    View.OnKeyListener {
    override fun onKey(v: View, keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) {
            return false
        }
        if (event.action == KeyEvent.ACTION_UP) {
            onBackInvokedCallback.onBackInvoked()
        }
        return true
    }
}

private class OnImeSubmitListener(private val onSubmit: (text: CharSequence) -> Unit) :
    TextView.OnEditorActionListener {
    override fun onEditorAction(v: TextView, actionId: Int, event: KeyEvent?): Boolean {
        val isSoftImeEvent =
            event == null &&
                (actionId == EditorInfo.IME_NULL ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_NEXT)
        val isKeyboardEnterKey =
            event != null &&
                KeyEvent.isConfirmKey(event.keyCode) &&
                event.action == KeyEvent.ACTION_DOWN
        if (isSoftImeEvent || isKeyboardEnterKey) {
            onSubmit(v.text)
            return true
        }
        return false
    }
}
