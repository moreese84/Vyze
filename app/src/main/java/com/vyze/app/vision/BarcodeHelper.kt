package com.vyze.app.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * On-device barcode / QR detection using ML Kit Barcode Scanning.
 *
 * ## Supported Formats
 * All 1D formats (EAN-13/8, UPC-A/E, Code 39/93/128, ITF, Codabar — the ones
 * printed on retail products and banknotes) and 2D formats (QR, Data Matrix,
 * PDF417, Aztec).
 *
 * ## Latency
 * ~50-120ms on a mid-range phone — comparable to OCR, far faster than VLM
 * inference. Runs in the OCR pre-pass alongside text recognition.
 *
 * ## Usage
 * Call [scan] from a coroutine. Returns null when no barcode is present;
 * never throws — failures are logged and reported as null.
 */
class BarcodeHelper {

    private val scanner = BarcodeScanning.getClient()

    /**
     * Detect barcodes in a camera bitmap.
     *
     * @param bitmap Camera frame — will NOT be recycled (caller manages lifecycle)
     * @return (rawValue, formatName) of the strongest barcode, or null if
     *         none detected / detection failed
     */
    suspend fun scan(bitmap: Bitmap): Pair<String, String>? = withContext(Dispatchers.Default) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val barcodes = scanner.process(inputImage).await()

            // Prefer the first barcode that carries a raw value; ML Kit orders
            // by confidence/size, so the first hit is the strongest candidate.
            val best = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                ?: return@withContext null
            val rawValue = best.rawValue ?: return@withContext null
            return@withContext rawValue to formatName(best.format)
        } catch (e: Throwable) {
            Log.w(TAG, "Barcode scan failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Human-readable format name for scan-history metadata. */
    private fun formatName(@Barcode.BarcodeFormat format: Int): String = when (format) {
        Barcode.FORMAT_EAN_13 -> "EAN_13"
        Barcode.FORMAT_EAN_8 -> "EAN_8"
        Barcode.FORMAT_UPC_A -> "UPC_A"
        Barcode.FORMAT_UPC_E -> "UPC_E"
        Barcode.FORMAT_CODE_39 -> "CODE_39"
        Barcode.FORMAT_CODE_93 -> "CODE_93"
        Barcode.FORMAT_CODE_128 -> "CODE_128"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_CODABAR -> "CODABAR"
        Barcode.FORMAT_QR_CODE -> "QR_CODE"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        else -> "UNKNOWN"
    }

    fun close() {
        try { scanner.close() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "BarcodeHelper"
    }
}
