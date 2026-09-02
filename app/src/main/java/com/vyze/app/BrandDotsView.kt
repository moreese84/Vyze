package com.vyze.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated background with semi-transparent red and yellow dots
 * drifting slowly across a white canvas.
 *
 * Used on the loading screen to give visual feedback that the app
 * is alive and working — while the brand's signature dot colours
 * reinforce identity.
 *
 * Dots:
 * - 12–16 circles per screen
 * - Randomised size (18–48dp), speed, and drift direction
 * - Very low alpha (0.06–0.14) so they feel ambient, not distracting
 * - Gentle sinusoidal drift (wave-like horizontal movement)
 */
class BrandDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Dot(
        var x: Float,
        var y: Float,
        var radius: Float,
        var alpha: Float,
        var speedY: Float,       // vertical drift speed (px/frame)
        var driftAmplitude: Float, // horizontal sine amplitude
        var driftSpeed: Float,     // horizontal sine frequency
        var phase: Float,          // sine phase offset
        var color: Int            // 0 = red, 1 = yellow
    )

    private val dots = mutableListOf<Dot>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var isRunning = false

    // Brand colours (matching logo)
    private val redColor = 0xFFCE2028.toInt()
    private val yellowColor = 0xFFFBD10A.toInt()

    private val DOT_COUNT_MIN = 12
    private val DOT_COUNT_MAX = 16
    private val DOT_SIZE_MIN_DP = 18
    private val DOT_SIZE_MAX_DP = 48
    private val DOT_ALPHA_MIN = 0.06f
    private val DOT_ALPHA_MAX = 0.14f
    private val DRIFT_SPEED_BASE = 0.4f  // pixels per frame (~24fps)
    private val FRAME_DELAY_MS = 42L     // ~24fps — smooth but battery-friendly

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (!isRunning) return
            updatePositions()
            invalidate()
            handler.postDelayed(this, FRAME_DELAY_MS)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (dots.isEmpty() && w > 0 && h > 0) {
            initDots(w, h)
        }
    }

    private fun initDots(w: Int, h: Int) {
        val density = resources.displayMetrics.density
        val count = Random.nextInt(DOT_COUNT_MIN, DOT_COUNT_MAX + 1)

        dots.clear()
        for (i in 0 until count) {
            val sizeDp = Random.nextFloat() * (DOT_SIZE_MAX_DP - DOT_SIZE_MIN_DP) + DOT_SIZE_MIN_DP
            val radiusPx = sizeDp * density / 2f
            val alpha = Random.nextFloat() * (DOT_ALPHA_MAX - DOT_ALPHA_MIN) + DOT_ALPHA_MIN

            dots.add(
                Dot(
                    x = Random.nextFloat() * w,
                    y = Random.nextFloat() * h,
                    radius = radiusPx,
                    alpha = alpha,
                    speedY = DRIFT_SPEED_BASE * density * (0.5f + Random.nextFloat()),
                    driftAmplitude = (20f + Random.nextFloat() * 40f) * density,
                    driftSpeed = 0.003f + Random.nextFloat() * 0.005f,
                    phase = Random.nextFloat() * (2f * Math.PI).toFloat(),
                    color = if (i % 2 == 0) 0 else 1  // alternate red/yellow
                )
            )
        }
    }

    private fun updatePositions() {
        val h = height.toFloat()
        val w = width.toFloat()
        if (h <= 0f || w <= 0f) return

        for (dot in dots) {
            // Slow upward drift
            dot.y -= dot.speedY

            // Reset to bottom when off-screen top (with some padding)
            if (dot.y < -dot.radius * 2) {
                dot.y = h + dot.radius * 2
                dot.x = Random.nextFloat() * w
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // White background
        canvas.drawColor(0xFFFFFFFF.toInt())

        val time = System.currentTimeMillis()

        for (dot in dots) {
            // Gentle sinusoidal horizontal drift
            val driftX = dot.driftAmplitude * sin(time * dot.driftSpeed + dot.phase)
            val drawX = dot.x + driftX
            val drawY = dot.y

            paint.color = if (dot.color == 0) redColor else yellowColor
            paint.alpha = (dot.alpha * 255).toInt().coerceIn(0, 255)

            canvas.drawCircle(drawX, drawY, dot.radius, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isRunning = true
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        isRunning = false
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            if (!isRunning) {
                isRunning = true
                handler.post(ticker)
            }
        } else {
            isRunning = false
            handler.removeCallbacks(ticker)
        }
    }
}
