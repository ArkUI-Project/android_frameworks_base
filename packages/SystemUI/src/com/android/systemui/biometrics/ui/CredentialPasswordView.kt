package com.android.systemui.biometrics.ui

import android.content.Context
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Insets
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.biometrics.AuthPanelController
import com.android.systemui.biometrics.plugins.AuthContextPlugins
import com.android.systemui.biometrics.ui.binder.CredentialViewBinder
import com.android.systemui.biometrics.ui.binder.Spaghetti
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel
import com.android.systemui.res.R

/** PIN or password credential view for BiometricPrompt. */
class CredentialPasswordView(context: Context, attrs: AttributeSet?) :
    LinearLayout(context, attrs), CredentialView, View.OnApplyWindowInsetsListener {

    private var bottomInset: Int = 0

    private val accessibilityManager by lazy {
        context.getSystemService(AccessibilityManager::class.java)
    }

    /** Initializes the view. */
    override fun init(
        viewModel: CredentialViewModel,
        host: CredentialView.Host,
        panelViewController: AuthPanelController,
        animatePanel: Boolean,
        legacyCallback: Spaghetti.Callback,
        plugins: AuthContextPlugins?,
    ) {
        CredentialViewBinder.bind(
            this,
            host,
            viewModel,
            panelViewController,
            animatePanel,
            legacyCallback,
            plugins,
        )
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        setOnApplyWindowInsetsListener(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (findViewById<View>(R.id.pin_pad) != null) {
            applyPinSafeInsets(rootWindowInsets)
            requestApplyInsets()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && findViewById<View>(R.id.pin_pad) != null) {
            applyPinSafeInsets(rootWindowInsets)
        }
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsets): WindowInsets {
        if (findViewById<View>(R.id.pin_pad) != null) {
            applyPinSafeInsets(insets)
            return WindowInsets.CONSUMED
        }
        val statusBarInsets: Insets = insets.getInsets(WindowInsets.Type.statusBars())
        val displayCutoutInsets: Insets = insets.getInsets(WindowInsets.Type.displayCutout())
        val keyboardInsets: Insets = insets.getInsets(WindowInsets.Type.ime())
        val navigationInsets: Insets = insets.getInsets(WindowInsets.Type.navigationBars())
        val imeBottomInset = keyboardInsets.bottom
        if (bottomInset != imeBottomInset) {
            val titleView: TextView? = findViewById(R.id.title)
            if (titleView != null) {
                if (
                    bottomInset > 0 && resources.configuration.orientation == ORIENTATION_LANDSCAPE
                ) {
                    titleView.isSingleLine = true
                    titleView.ellipsize = TextUtils.TruncateAt.MARQUEE
                    titleView.marqueeRepeatLimit = -1
                    // select to enable marquee unless a screen reader is enabled
                    titleView.isSelected = accessibilityManager?.shouldMarquee() ?: false
                } else {
                    titleView.isSingleLine = false
                    titleView.ellipsize = null
                    // select to enable marquee unless a screen reader is enabled
                    titleView.isSelected = false
                }
            }
        }

        setPadding(
            displayCutoutInsets.left,
            statusBarInsets.top.coerceAtLeast(displayCutoutInsets.top),
            displayCutoutInsets.right,
            if (keyboardInsets.bottom == 0) navigationInsets.bottom else keyboardInsets.bottom,
        )
        return WindowInsets.CONSUMED
    }

    private fun applyPinSafeInsets(insets: WindowInsets?) {
        val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        // TYPE_KEYGUARD_DIALOG is above the status bar. Its dispatched InsetsState may only
        // contain sources above that window, while maximumWindowMetrics includes the display's
        // raw sources. Read the view's display so hidden bars, cutouts and side navigation are
        // still respected when an ancestor consumes insets or no callback reaches this page.
        val viewDisplay = display
        val displayContext = if (viewDisplay != null && viewDisplay.displayId != context.displayId) {
            context.createDisplayContext(viewDisplay)
        } else {
            context
        }
        val displayInsets = displayContext.getSystemService(WindowManager::class.java)
            ?.maximumWindowMetrics?.windowInsets?.getInsetsIgnoringVisibility(types) ?: Insets.NONE
        val safeInsets = Insets.max(
            displayInsets,
            Insets.max(
                insets?.getInsetsIgnoringVisibility(types) ?: Insets.NONE,
                rootWindowInsets?.getInsetsIgnoringVisibility(types) ?: Insets.NONE,
            ),
        )
        // Replace the root padding once, rather than adding it to a previous callback or to
        // descendant padding. Native PIN entry has no IME, so its animation is excluded.
        if (paddingLeft != safeInsets.left || paddingTop != safeInsets.top ||
            paddingRight != safeInsets.right || paddingBottom != safeInsets.bottom) {
            setPadding(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom)
        }
    }
}

private fun AccessibilityManager.shouldMarquee(): Boolean = !isEnabled || !isTouchExplorationEnabled
