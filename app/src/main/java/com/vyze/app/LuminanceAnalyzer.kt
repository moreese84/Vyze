package com.vyze.app

import android.media.Image
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class LuminanceAnalyzer(
    private val onLuminanceResult: (isDark: Boolean, meanLuminance: Double) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val DARK_THRESHOLD = 50.0
    }

    /**
     * Standalone analyzer that closes the imageProxy when done.
     * Used when running luminance analysis independently.
     */
    override fun analyze(imageProxy: ImageProxy) {
        try {
            analyzeLuminance(imageProxy)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Extracts luminance data from the Y plane WITHOUT closing the imageProxy.
     * This allows the composite analyzer to share the frame with other analyzers
     * (e.g., ObjectDetectorHelper) that handle their own imageProxy lifecycle.
     */
    fun analyzeLuminance(imageProxy: ImageProxy) {
        val image: Image = imageProxy.image ?: return

        // Get the Y plane (luminance) from the YUV_420_888 format
        val yBuffer = image.planes[0].buffer
        val ySize = yBuffer.remaining()
        val yData = ByteArray(ySize)
        yBuffer.get(yData)

        // Calculate mean brightness
        var sum = 0L
        for (byte in yData) {
            // Convert signed byte to unsigned (0-255)
            sum += (byte.toInt() and 0xFF)
        }
        val meanLuminance = sum.toDouble() / ySize

        val isDark = meanLuminance < DARK_THRESHOLD
        onLuminanceResult(isDark, meanLuminance)
    }
}
