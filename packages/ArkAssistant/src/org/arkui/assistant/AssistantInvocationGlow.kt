/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.assistant

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.animation.LinearInterpolator

internal enum class AssistantInvocationEffect { AURORA, SCREEN, POWER }

/** Keep invocation and listening surfaces in the same blue/cyan color family. */
internal object AssistantGlowPalette {
    val Blue = 0xff207bff.toInt()
    val Cyan = 0xff19bfff.toInt()
    val BrightCyan = 0xff19c5ff.toInt()
    val Violet = 0xff677fff.toInt()
}

/** A finite, decorative wake animation in the drawer's existing window. */
internal class AssistantInvocationGlow(context: Context) : View(context) {
    private val shader = RuntimeShader(SHADER)
    private val paint = Paint().apply { this.shader = this@AssistantInvocationGlow.shader }
    private var animator: ValueAnimator? = null
    private var pending: AssistantInvocationEffect? = null
    private var activeEffect = AssistantInvocationEffect.AURORA
    private var drawing = false
    private var bottomInset = 0
    private var voiceReveal = 0f
    private var cornerRadius = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        val density = resources.displayMetrics.density
        // The shader supplies the soft falloff directly, without a full-screen blur layer.
        shader.setFloatUniform("density", density)
        shader.setFloatUniform("corners", 0f, 0f, 0f, 0f)
        shader.setFloatUniform("bottomInset", 0f)
        shader.setFloatUniform("voiceReveal", 0f)
        shader.setColorUniform("blue", AssistantGlowPalette.Blue)
        shader.setColorUniform("cyan", AssistantGlowPalette.Cyan)
        shader.setColorUniform("violet", AssistantGlowPalette.Violet)
    }

    fun play(effect: AssistantInvocationEffect) {
        stop()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        pending = effect
        startWhenLaidOut()
    }

    fun stop() {
        pending = null
        val previous = animator
        animator = null
        previous?.cancel()
        drawing = false
        invalidate()
    }

    fun setPresentation(bottomInset: Int, voiceReveal: Float) {
        if (this.bottomInset == bottomInset && this.voiceReveal == voiceReveal) return
        this.bottomInset = bottomInset
        this.voiceReveal = voiceReveal
        shader.setFloatUniform("bottomInset", bottomInset.toFloat())
        shader.setFloatUniform("voiceReveal", voiceReveal)
        if (drawing) invalidate()
    }

    private fun startWhenLaidOut() {
        val effect = pending ?: return
        if (!isAttachedToWindow || width == 0 || height == 0) return
        pending = null
        activeEffect = effect
        drawing = true
        shader.setFloatUniform("effect", when (effect) {
            AssistantInvocationEffect.AURORA -> 0f
            AssistantInvocationEffect.SCREEN -> 1f
            AssistantInvocationEffect.POWER -> 2f
        })
        val durationMillis = when (effect) {
            AssistantInvocationEffect.AURORA -> 2400L
            AssistantInvocationEffect.SCREEN -> 3000L
            AssistantInvocationEffect.POWER -> 2800L
        }
        shader.setFloatUniform("duration", durationMillis.toFloat() / 1000f)
        shader.setFloatUniform("progress", 0f)
        val animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMillis
            interpolator = LinearInterpolator()
            addUpdateListener {
                shader.setFloatUniform("progress", it.animatedValue as Float)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    animator = null
                    drawing = false
                    invalidate()
                }
            })
        }
        animator = animation
        animation.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
        startWhenLaidOut()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader.setFloatUniform("resolution", w.toFloat(), h.toFloat())
        startWhenLaidOut()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val topLeft = (insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0).toFloat()
        val topRight = (insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0).toFloat()
        val bottomRight = (insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0).toFloat()
        val bottomLeft = (insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0).toFloat()
        cornerRadius = maxOf(topLeft, topRight, bottomRight, bottomLeft)
        shader.setFloatUniform("corners", topLeft, topRight, bottomRight, bottomLeft)
        if (drawing) invalidate()
        return super.onApplyWindowInsets(insets)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!drawing || !canvas.isHardwareAccelerated) return
        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density
        if (activeEffect == AssistantInvocationEffect.AURORA) {
            val bottom = h - bottomInset
            val depth = minOf(h * 0.34f, 280f * density)
            // Beyond this range the Gaussian lobes are below visible alpha precision.
            val top = (bottom - depth * 2f).coerceAtLeast(0f)
            val end = (bottom + depth).coerceAtMost(h)
            if (end > top) canvas.drawRect(0f, top, w, end, paint)
        } else {
            // Draw four non-overlapping edge bands instead of shading transparent pixels
            // in the center. Include rounded corners and the complete visible halo.
            val band = cornerRadius + 72f * density
            val x = minOf(band, w / 2f)
            val y = minOf(band, h / 2f)
            canvas.drawRect(0f, 0f, w, y, paint)
            canvas.drawRect(0f, h - y, w, h, paint)
            if (h > y * 2f) {
                canvas.drawRect(0f, y, x, h - y, paint)
                canvas.drawRect(w - x, y, w, h - y, paint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    companion object {
        // Premultiplied output lets the current app remain visible through both effects.
        private const val SHADER = """
            uniform float2 resolution;
            uniform float density;
            uniform float4 corners;
            uniform float bottomInset;
            uniform float effect;
            uniform float progress;
            uniform float duration;
            uniform float voiceReveal;
            layout(color) uniform half4 blue;
            layout(color) uniform half4 cyan;
            layout(color) uniform half4 violet;

            float bell(float2 p, float2 center, float2 spread) {
                float2 d = (p - center) / spread;
                return exp(-dot(d, d) * 2.0);
            }

            float edgeDistance(float2 p) {
                float2 halfSize = resolution * 0.5;
                float radius = p.x < halfSize.x
                    ? (p.y < halfSize.y ? corners.x : corners.w)
                    : (p.y < halfSize.y ? corners.y : corners.z);
                radius = min(radius, min(halfSize.x, halfSize.y));
                float2 q = abs(p - halfSize) - halfSize + radius + density;
                return abs(length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius);
            }

            half4 main(float2 coord) {
                float2 uv = coord / resolution;
                // Keep the reveal responsive, hold the full glow, then fade for 1.1 seconds.
                float time = progress * duration;
                float enter = smoothstep(0.0, 0.20, time);
                float leave = 1.0 - smoothstep(duration - 1.10, duration, time);
                float envelope = enter * leave;
                if (effect < 0.5) {
                    // Retain a subtle afterglow when the listening surface appears.
                    float open = 1.0 - pow(1.0 - clamp(time / 0.65, 0.0, 1.0), 4.0);
                    float depth = min(resolution.y * 0.34, 280.0 * density);
                    float2 p = float2(uv.x, (resolution.y - bottomInset - coord.y) / depth);
                    float drift = sin(progress * 5.0) * 0.06;
                    float blueLight = bell(p, float2(0.27 + drift, 0.03 + open * 0.22),
                        float2(0.12 + open * 0.44, 0.12 + open * 0.60));
                    float violetLight = bell(p, float2(0.73 - drift, 0.03 + open * 0.12),
                        float2(0.10 + open * 0.44, 0.08 + open * 0.52));
                    float cyanLight = bell(p, float2(0.53 + drift, 0.08),
                        float2(0.10 + open * 0.30, 0.08 + open * 0.30));
                    float light = blueLight + violetLight * 0.35 + cyanLight * 0.65;
                    float3 color = (blue.rgb * blueLight + violet.rgb * violetLight * 0.35
                        + cyan.rgb * cyanLight * 0.65) / max(light, 0.001);
                    float alpha = min(light * 0.28, 0.38) * envelope
                        * (1.0 - voiceReveal * 0.72);
                    return half4(color * alpha, alpha);
                }

                // Navigation starts at the bottom; power starts at the right-hand button edge.
                float distance = edgeDistance(coord);
                float front = 1.12 - smoothstep(0.0, 0.50, time) * 1.40;
                float sweep = smoothstep(front - 0.12, front + 0.12, uv.y);
                if (effect > 1.5) {
                    float fromButton = abs(uv.y - 0.38) + (1.0 - uv.x) * 0.48;
                    float reach = smoothstep(0.0, 0.65, time) * 1.30;
                    sweep = 1.0 - smoothstep(reach - 0.10, reach + 0.06, fromButton);
                }
                // Fold the former 3dp blur into the Gaussian width and peak intensity.
                float coreDistance = distance / (5.4 * density);
                float haloDistance = distance / (22.2 * density);
                float core = exp(-0.5 * coreDistance * coreDistance) * 0.30;
                float halo = exp(-0.5 * haloDistance * haloDistance) * 0.178;
                float hue = 0.5 + 0.5 * sin(uv.x * 4.5 + uv.y * 5.0 - progress * 7.0);
                float3 color = mix(blue.rgb, cyan.rgb, hue * 0.65);
                color = mix(color, violet.rgb, (1.0 - uv.y) * 0.20);
                float alpha = (halo + core) * sweep * envelope;
                alpha *= 1.0 - voiceReveal * smoothstep(0.65, 1.0, uv.y) * 0.65;
                return half4(color * alpha, alpha);
            }
        """
    }
}
