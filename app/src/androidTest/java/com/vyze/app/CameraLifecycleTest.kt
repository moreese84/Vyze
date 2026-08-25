package com.vyze.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vyze.app.delegates.CameraSetupDelegate
import com.vyze.app.delegates.MlPipelineManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

/**
 * Instrumented tests for camera binding lifecycle components.
 *
 * Verifies:
 * - CameraSetupDelegate initializes without crash
 * - MlPipelineManager initializes ML components
 * - Delegates properly clean up resources
 */
@RunWith(AndroidJUnit4::class)
class CameraLifecycleTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var bgExecutor: java.util.concurrent.ExecutorService

    @Before
    fun setup() {
        bgExecutor = Executors.newSingleThreadExecutor()
    }

    @Test
    fun cameraSetupDelegate_instantiatesSuccessfully() {
        val delegate = CameraSetupDelegate()
        assertNull("Preview should be null before setup", delegate.preview)
        assertNull("Camera should be null before setup", delegate.camera)
        assertNull("CameraProvider should be null before setup", delegate.cameraProvider)
    }

    @Test
    fun cameraSetupDelegate_releaseCamera_doesNotCrash() {
        val delegate = CameraSetupDelegate()
        // Should not crash even when nothing is initialized
        delegate.releaseCamera()
        delegate.destroy()
        assertNull("CameraProvider should be null after destroy", delegate.cameraProvider)
    }

    @Test
    fun mlPipelineManager_isOdInitialized_falseBeforeInit() {
        val pipeline = MlPipelineManager(context, bgExecutor)
        assertFalse("OD should not be initialized before initialize()", pipeline.isOdInitialized())
    }

    @Test
    fun mlPipelineManager_reset_clearsFlags() {
        val pipeline = MlPipelineManager(context, bgExecutor)
        pipeline.reset()
        assertFalse("OCR should not be requested after reset", pipeline.isOcrRequested())
        assertEquals("Luminance should be 0 after reset", 0.0, pipeline.latestMeanLuminance, 0.001)
    }

    @Test
    fun mlPipelineManager_frameSkipRatio_isValid() {
        assertTrue(
            "Frame skip ratio should be >= 2",
            MlPipelineManager.FRAME_SKIP_RATIO >= 2
        )
    }

    @Test
    fun scanRepository_constants_areCorrect() {
        assertEquals("OCR type", "OCR", com.vyze.app.data.ScanRepository.TYPE_OCR)
        assertEquals("BARCODE type", "BARCODE", com.vyze.app.data.ScanRepository.TYPE_BARCODE)
        assertEquals("CURRENCY type", "CURRENCY", com.vyze.app.data.ScanRepository.TYPE_CURRENCY)
        assertEquals("COLOR type", "COLOR", com.vyze.app.data.ScanRepository.TYPE_COLOR)
        assertEquals("FACE type", "FACE", com.vyze.app.data.ScanRepository.TYPE_FACE)
        assertEquals("SCENE type", "SCENE", com.vyze.app.data.ScanRepository.TYPE_SCENE)
    }
}
