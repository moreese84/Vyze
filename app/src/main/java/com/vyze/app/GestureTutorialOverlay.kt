package com.vyze.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

/**
 * Interactive gesture tutorial overlay that guides low-vision users through
 * practice gestures with visual indicators, haptic pulses, and TTS instructions.
 *
 * ## Tutorial Steps
 * 1. Single Tap — "Tap once anywhere on the circle"
 * 2. Double Tap — "Tap twice quickly"
 * 3. Long Press — "Press and hold for 2 seconds"
 * 4. Triple Tap — "Tap three times quickly"
 * 5. Triple Tap + Hold — "Tap three times and hold on the last"
 *
 * ## Visual Feedback
 * - Large yellow circle as the target area
 * - Ripple animation on successful gesture
 * - Green flash on success, red flash on failure
 * - Current step text displayed in center
 */
class GestureTutorialOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val TAG = "GestureTutorialOverlay"

    // ── Callbacks ─────────────────────────────────────────────────────

    /** Called when a tutorial step is completed. */
    var onStepCompleted: ((stepIndex: Int) -> Unit)? = null

    /** Called when all tutorial steps are completed. */
    var onTutorialCompleted: (() -> Unit)? = null

    // ── Paint Objects ─────────────────────────────────────────────────

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700") // Yellow
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val circleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFD700") // Semi-transparent yellow
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val instructionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700")
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFD700")
        style = Paint.Style.FILL
    }

    private val successPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8000FF00") // Semi-transparent green
        style = Paint.Style.FILL
    }

    private val failPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FF0000") // Semi-transparent red
        style = Paint.Style.FILL
    }

    // ── Tutorial State ────────────────────────────────────────────────

    private data class TutorialStep(
        val name: String,
        val instruction: String,
        val gestureType: GestureType,
        val hapticPattern: LongArray
    )

    private enum class GestureType {
        SINGLE_TAP, DOUBLE_TAP, LONG_PRESS, TRIPLE_TAP, TRIPLE_TAP_HOLD
    }

    private val tutorialSteps = listOf(
        TutorialStep(
            name = "Single Tap",
            instruction = "Tap once anywhere on the circle",
            gestureType = GestureType.SINGLE_TAP,
            hapticPattern = longArrayOf(0, 30)
        ),
        TutorialStep(
            name = "Double Tap",
            instruction = "Tap twice quickly",
            gestureType = GestureType.DOUBLE_TAP,
            hapticPattern = longArrayOf(0, 40, 60, 40)
        ),
        TutorialStep(
            name = "Long Press",
            instruction = "Press and hold for 2 seconds",
            gestureType = GestureType.LONG_PRESS,
            hapticPattern = longArrayOf(0, 150)
        ),
        TutorialStep(
            name = "Triple Tap",
            instruction = "Tap three times quickly",
            gestureType = GestureType.TRIPLE_TAP,
            hapticPattern = longArrayOf(0, 40, 60, 40, 60, 40)
        ),
        TutorialStep(
            name = "Triple Tap + Hold",
            instruction = "Tap three times and hold on the last",
            gestureType = GestureType.TRIPLE_TAP_HOLD,
            hapticPattern = longArrayOf(0, 40, 60, 40, 60, 150)
        )
    )

    private var currentStepIndex = 0
    private var isTutorialActive = false

    // ── Animation State ───────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())
    private var rippleRadius = 0f
    private var rippleAnimator: ValueAnimator? = null

    /** Flash overlay state (0 = none, 1 = success, -1 = fail). */
    private var flashState = 0
    private var flashAlpha = 0

    // ── Touch Tracking ────────────────────────────────────────────────

    private val tapTimestamps = mutableListOf<Long>()
    private var touchDownTime = 0L
    private var isHolding = false

    /** Max interval between taps to count as multi-tap. */
    private val TAP_WINDOW_MS = 600L

    /** Hold threshold for long press / triple-tap-hold. */
    private val HOLD_THRESHOLD_MS = 800L

    // ── Drawing ───────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isTutorialActive) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 40f

        // Draw target circle
        canvas.drawCircle(cx, cy, radius, circleFillPaint)
        canvas.drawCircle(cx, cy, radius, circlePaint)

        // Draw ripple
        if (rippleRadius > 0) {
            canvas.drawCircle(cx, cy, rippleRadius, ripplePaint)
        }

        // Draw flash overlay
        if (flashAlpha > 0) {
            val flashPaint = if (flashState > 0) successPaint else failPaint
            flashPaint.alpha = flashAlpha
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
        }

        // Draw step number
        val step = tutorialSteps[currentStepIndex]
        canvas.drawText(
            "${currentStepIndex + 1}/${tutorialSteps.size}",
            cx, cy - 60f,
            textPaint
        )

        // Draw gesture name
        textPaint.textSize = 52f
        canvas.drawText(step.name, cx, cy, textPaint)
        textPaint.textSize = 48f

        // Draw instruction
        canvas.drawText(step.instruction, cx, cy + 80f, instructionPaint)

        // Draw progress dots
        val dotRadius = 12f
        val dotSpacing = 40f
        val totalWidth = tutorialSteps.size * dotSpacing
        val startX = cx - totalWidth / 2 + dotSpacing / 2

        for (i in tutorialSteps.indices) {
            val dotPaint = if (i <= currentStepIndex) {
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FFD700")
                    style = Paint.Style.FILL
                }
            } else {
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#66FFFFFF")
                    style = Paint.Style.FILL
                }
            }
            canvas.drawCircle(startX + i * dotSpacing, cy + 150f, dotRadius, dotPaint)
        }
    }

    // ── Touch Handling ────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isTutorialActive) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTime = SystemClock.uptimeMillis()
                isHolding = false

                // Start hold detection
                handler.postDelayed(holdRunnable, HOLD_THRESHOLD_MS)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
                val holdDuration = SystemClock.uptimeMillis() - touchDownTime

                if (isHolding) {
                    // Was holding — check for triple-tap-hold
                    handleGestureCompleted(GestureType.TRIPLE_TAP_HOLD)
                } else if (holdDuration >= HOLD_THRESHOLD_MS) {
                    // Long press completed
                    handleGestureCompleted(GestureType.LONG_PRESS)
                } else {
                    // Tap — track it
                    recordTap()
                    checkForTapGesture()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private val holdRunnable = Runnable {
        isHolding = true
    }

    private fun recordTap() {
        val now = SystemClock.uptimeMillis()
        tapTimestamps.add(now)

        // Evict old taps
        while (tapTimestamps.isNotEmpty() && (now - tapTimestamps[0]) > TAP_WINDOW_MS) {
            tapTimestamps.removeAt(0)
        }
    }

    private fun checkForTapGesture() {
        val now = SystemClock.uptimeMillis()
        tapTimestamps.removeAll { (now - it) > TAP_WINDOW_MS }
        val count = tapTimestamps.size

        when (count) {
            1 -> handleGestureCompleted(GestureType.SINGLE_TAP)
            2 -> handleGestureCompleted(GestureType.DOUBLE_TAP)
            3 -> handleGestureCompleted(GestureType.TRIPLE_TAP)
        }
    }

    // ── Gesture Completion ────────────────────────────────────────────

    private fun handleGestureCompleted(gestureType: GestureType) {
        val currentStep = tutorialSteps[currentStepIndex]

        if (gestureType == currentStep.gestureType) {
            // Success!
            showFlash(success = true)
            startRippleAnimation()

            handler.postDelayed({
                tapTimestamps.clear()
                currentStepIndex++

                if (currentStepIndex >= tutorialSteps.size) {
                    // All steps completed
                    isTutorialActive = false
                    onTutorialCompleted?.invoke()
                    invalidate()
                } else {
                    onStepCompleted?.invoke(currentStepIndex - 1)
                    invalidate()
                }
            }, 800)
        } else {
            // Wrong gesture
            showFlash(success = false)
            tapTimestamps.clear()
            invalidate()
        }
    }

    // ── Animations ────────────────────────────────────────────────────

    private fun startRippleAnimation() {
        rippleAnimator?.cancel()
        val maxRadius = min(width, height) / 2f
        rippleAnimator = ValueAnimator.ofFloat(0f, maxRadius).apply {
            duration = 600
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                rippleRadius = animator.animatedValue as Float
                ripplePaint.alpha = ((1f - animator.animatedFraction) * 128).toInt()
                invalidate()
            }
            start()
        }
    }

    private fun showFlash(success: Boolean) {
        flashState = if (success) 1 else -1
        flashAlpha = 180

        val fadeAnimator = ValueAnimator.ofInt(180, 0).apply {
            duration = 400
            addUpdateListener { animator ->
                flashAlpha = animator.animatedValue as Int
                invalidate()
            }
        }
        fadeAnimator.start()
    }

    // ── Public API ────────────────────────────────────────────────────

    /** Start the gesture tutorial from the beginning. */
    fun startTutorial() {
        currentStepIndex = 0
        isTutorialActive = true
        tapTimestamps.clear()
        invalidate()
    }

    /** Skip to a specific step. */
    fun goToStep(stepIndex: Int) {
        if (stepIndex in tutorialSteps.indices) {
            currentStepIndex = stepIndex
            tapTimestamps.clear()
            invalidate()
        }
    }

    /** Stop the tutorial. */
    fun stopTutorial() {
        isTutorialActive = false
        handler.removeCallbacksAndMessages(null)
        rippleAnimator?.cancel()
        invalidate()
    }

    /** Get current step name for TTS. */
    fun getCurrentStepInstruction(): String {
        return if (isTutorialActive && currentStepIndex < tutorialSteps.size) {
            tutorialSteps[currentStepIndex].instruction
        } else {
            "Tutorial not active"
        }
    }

    /** Get total number of steps. */
    fun getStepCount(): Int = tutorialSteps.size

    /** Check if tutorial is currently active. */
    fun isActive(): Boolean = isTutorialActive

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        rippleAnimator?.cancel()
    }
}
