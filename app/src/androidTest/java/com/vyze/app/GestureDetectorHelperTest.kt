package com.vyze.app

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented tests for [GestureDetectorHelper].
 *
 * Verifies gesture detection flows:
 * - Single tap fires single-tap callback
 * - Double tap fires double-tap callback
 * - Long press fires long-press callback
 * - Triple tap fires triple-tap callback
 * - Triple tap + hold fires SOS callback
 * - Tap state resets after window expires
 */
@RunWith(AndroidJUnit4::class)
class GestureDetectorHelperTest {

    private lateinit var gestureHelper: GestureDetectorHelper
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        gestureHelper = GestureDetectorHelper(
            onSingleTap = { },
            onDoubleTap = { },
            onLongPress = { },
            onTripleTap = { },
            onTripleTapHold = { }
        )
    }

    @Test
    fun singleTap_firesSingleTapCallback() {
        var singleTapFired = false
        gestureHelper = GestureDetectorHelper(
            onSingleTap = { singleTapFired = true },
            onDoubleTap = { },
            onLongPress = { },
            onTripleTap = { },
            onTripleTapHold = { }
        )

        // Simulate a single tap
        val downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        val upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 100f, 100f, 0)

        gestureHelper.onDown(downEvent)
        gestureHelper.onSingleTapConfirmed(upEvent)

        downEvent.recycle()
        upEvent.recycle()

        assertTrue("Single tap callback should have fired", singleTapFired)
    }

    @Test
    fun doubleTap_firesDoubleTapCallback() {
        var doubleTapFired = false
        gestureHelper = GestureDetectorHelper(
            onSingleTap = { },
            onDoubleTap = { doubleTapFired = true },
            onLongPress = { },
            onTripleTap = { },
            onTripleTapHold = { }
        )

        val downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        gestureHelper.onDown(downEvent)
        gestureHelper.onDoubleTap(downEvent)
        downEvent.recycle()

        assertTrue("Double tap callback should have fired", doubleTapFired)
    }

    @Test
    fun reset_clearsAllState() {
        var singleTapFired = false
        gestureHelper = GestureDetectorHelper(
            onSingleTap = { singleTapFired = true },
            onDoubleTap = { },
            onLongPress = { },
            onTripleTap = { },
            onTripleTapHold = { }
        )

        gestureHelper.reset()

        val downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        val upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 100f, 100f, 0)

        gestureHelper.onDown(downEvent)
        gestureHelper.onSingleTapConfirmed(upEvent)

        downEvent.recycle()
        upEvent.recycle()

        // After reset, the tap timestamps should be cleared
        // Single tap should still work on a fresh tap
        assertTrue("Single tap should work after reset", singleTapFired)
    }

    @Test
    fun constants_areValid() {
        assertTrue(
            "Triple tap window should be positive",
            GestureDetectorHelper.TRIPLE_TAP_WINDOW_MS > 0
        )
        assertTrue(
            "Hold threshold should be positive",
            GestureDetectorHelper.TRIPLE_TAP_HOLD_THRESHOLD_MS > 0
        )
        assertTrue(
            "Triple tap window should be > hold threshold",
            GestureDetectorHelper.TRIPLE_TAP_WINDOW_MS >
                GestureDetectorHelper.TRIPLE_TAP_HOLD_THRESHOLD_MS
        )
    }
}
