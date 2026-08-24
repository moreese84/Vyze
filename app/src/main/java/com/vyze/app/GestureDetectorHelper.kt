package com.vyze.app

import android.view.GestureDetector
import android.view.MotionEvent

/**
 * Helper class that wraps GestureDetector.SimpleOnGestureListener
 * for full-screen accessibility gestures.
 *
 * - Single Tap: Triggers Object Detection readout
 * - Double Tap: Triggers ML Kit Text Recognition (OCR) readout
 * - Long Press: Triggers real-time Luminance/Light level readout
 */
class GestureDetectorHelper(
    private val onSingleTap: () -> Unit = {},
    private val onDoubleTap: () -> Unit = {},
    private val onLongPress: () -> Unit = {}
) : GestureDetector.SimpleOnGestureListener() {

    companion object {
        private const val TAG = "GestureDetectorHelper"
        private const val DOUBLE_TAP_TIMEOUT = 300L
    }

    private var lastTapTime = 0L

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        onSingleTap()
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        onDoubleTap()
        return true
    }

    override fun onLongPress(e: MotionEvent) {
        onLongPress()
    }

    override fun onDown(e: MotionEvent): Boolean {
        return true
    }
}
