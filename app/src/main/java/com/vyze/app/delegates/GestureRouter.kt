package com.vyze.app.delegates

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.vyze.app.ColorAnalyzer
import com.vyze.app.GestureDetectorHelper
import com.vyze.app.HapticManager
import com.vyze.app.R
import com.vyze.app.TTSManager
import com.vyze.app.data.ScanRepository
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Handles all gesture-to-action routing for the camera screen.
 *
 * Gesture Vocabulary:
 * - Single tap → Object Detection readout (with touch coordinate for hit-test)
 * - Double tap → OCR text recognition
 * - Long press → Scene Summary
 * - Triple tap → Color Analysis (center ROI)
 * - Triple tap + hold → Emergency SOS → opens dialer
 */
class GestureRouter(
    private val context: Context,
    private val ttsManager: TTSManager,
    private val hapticManager: HapticManager,
    private val colorAnalyzer: ColorAnalyzer,
    private val scanRepository: ScanRepository,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {

    private val TAG = "GestureRouter"

    /** Callback for single-tap with (x, y) in view coordinates. */
    var onSingleTapAction: (x: Float, y: Float) -> Unit = { _, _ -> }

    /** Callback for double-tap (OCR readout). */
    var onDoubleTapAction: () -> Unit = {}

    /** Callback for long-press (Scene Summary). */
    var onLongPressAction: () -> Unit = {}

    private lateinit var gestureDetectorHelper: GestureDetectorHelper
    private lateinit var gestureDetector: GestureDetector
    private var isAttached = false

    /**
     * Attaches the gesture router to a container view.
     */
    fun attach(containerView: View) {
        if (isAttached) return
        isAttached = true

        gestureDetectorHelper = GestureDetectorHelper(
            onSingleTap = { e ->
                hapticManager.vibrateTap()
                onSingleTapAction(e.x, e.y)
            },
            onDoubleTap = {
                hapticManager.vibrateDoubleTap()
                onDoubleTapAction()
            },
            onLongPress = {
                hapticManager.vibrateLongPress()
                onLongPressAction()
            },
            onTripleTap = {
                hapticManager.vibrateDoubleTap()
                performColorAnalysis(containerView)
            },
            onTripleTapHold = {
                hapticManager.vibrateWarning()
                hapticManager.vibrateWarning()
                triggerEmergencySOS()
            }
        )

        gestureDetector = GestureDetector(context, gestureDetectorHelper)
        containerView.setOnTouchListener { _, event ->
            gestureDetectorHelper.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true // Consume the event so child overlays don't steal it
        }
    }

    /**
     * Detaches the gesture router, cleaning up listeners.
     */
    fun detach() {
        if (!isAttached) return
        isAttached = false
        gestureDetectorHelper.reset()
    }

    // ── Color Analysis ────────────────────────────────────────────────

    private fun performColorAnalysis(containerView: View) {
        ttsManager.speakImmediate(context.getString(R.string.color_analyzing))

        try {
            val bitmap = Bitmap.createBitmap(
                containerView.width.coerceAtLeast(1),
                containerView.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            containerView.draw(canvas)

            val colorName = colorAnalyzer.analyzeCenterColor(context, bitmap)
            bitmap.recycle()

            val announcement = context.getString(R.string.color_result, colorName)
            ttsManager.speak(announcement)

            // Persist scan
            MainScope().launch {
                scanRepository.saveColorScan(colorName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Color analysis failed", e)
            ttsManager.speakImmediate("Color analysis failed.")
        }
    }

    // ── Emergency SOS ─────────────────────────────────────────────────

    private fun triggerEmergencySOS() {
        ttsManager.speakImmediate(context.getString(R.string.sos_activated))

        mainHandler.postDelayed({
            try {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$SOS_EMERGENCY_NUMBER")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open dialer for SOS", e)
                ttsManager.speakImmediate("Could not open dialer.")
            }
        }, SOS_DIAL_DELAY_MS)
    }

    companion object {
        const val SOS_EMERGENCY_NUMBER = "999"
        const val SOS_DIAL_DELAY_MS = 1500L
    }
}
