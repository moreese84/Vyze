package com.vyze.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ConcurrentHashMap

/**
 * Analyzes camera frames for barcodes (UPC, EAN) and QR codes using
 * bundled ML Kit Barcode Scanning.
 *
 * ## Supported Formats
 *  - **UPC-A** (12-digit US/Canada retail barcodes)
 *  - **EAN-13** (13-digit international retail barcodes)
 *  - **EAN-8** (8-digit compact barcodes)
 *  - **QR Code** (URLs, text, contact info, Wi-Fi configs)
 *
 * ## Speech Output
 *  - Barcodes: "Barcode detected: [number]"
 *  - QR Codes: "QR Code detected: [URL/text]"
 *  - Other:    "Code detected: [value]"
 *
 * ## Speech Debouncing
 * A 3,500 ms cooldown per barcode content prevents TTS spamming when
 * the camera holds steady on the same code.
 *
 * **Memory safety:** The intermediate [Bitmap] is created from the
 * [android.media.Image] and recycled after ML Kit consumes it.
 */
class BarcodeAnalyzer {

    private val scanner = BarcodeScanning.getClient()

    /**
     * Timestamp of when each barcode content was last announced.
     * Keyed by the raw barcode value string.
     */
    private val lastSpokenTimestamp = ConcurrentHashMap<String, Long>()

    /**
     * Processes a [Bitmap] for barcode detection.
     *
     * @param bitmap          The camera frame as a Bitmap (ARGB_8888).
     * @param rotationDegrees Rotation applied to the image (from ImageProxy.imageInfo).
     * @param onSuccess       Callback with formatted barcode announcements.
     * @param onError         Callback invoked when scanning fails.
     */
    fun processBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int,
        onSuccess: (List<String>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val announcements = barcodes.mapNotNull { barcode ->
                    formatBarcodeAnnouncement(barcode)
                }.filter { announcement ->
                    // Apply cooldown — only include barcodes not within cooldown window
                    val content = announcement.second
                    isEligibleToSpeak(content)
                }.map { it.first }

                onSuccess(announcements)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Barcode scanning failed", e)
                onError(e)
            }
    }

    /**
     * Formats a [Barcode] into a human-readable TTS announcement and
     * its raw content string for debouncing.
     *
     * @return Pair of (announcement, rawContent) or null if the barcode
     *         has no readable value.
     */
    private fun formatBarcodeAnnouncement(barcode: Barcode): Pair<String, String>? {
        val rawValue = barcode.rawValue ?: return null

        return when (barcode.valueType) {
            // ── UPC / EAN Barcodes ──────────────────────────────────────────
            Barcode.TYPE_PRODUCT -> {
                val typeName = when (barcode.format) {
                    Barcode.FORMAT_UPC_A  -> "UPC"
                    Barcode.FORMAT_EAN_13 -> "EAN-13"
                    Barcode.FORMAT_EAN_8  -> "EAN-8"
                    Barcode.FORMAT_UPC_E  -> "UPC-E"
                    else                  -> "Barcode"
                }
                val announcement = "$typeName code detected: $rawValue"
                Pair(announcement, rawValue)
            }

            // ── QR Codes ────────────────────────────────────────────────────
            Barcode.TYPE_URL -> {
                val title = barcode.url?.title ?: ""
                val display = if (title.isNotEmpty()) {
                    "QR Code detected: $title"
                } else {
                    "QR Code detected: ${barcode.url?.url ?: rawValue}"
                }
                Pair(display, rawValue)
            }

            Barcode.TYPE_TEXT -> {
                Pair("QR Code detected: $rawValue", rawValue)
            }

            Barcode.TYPE_WIFI -> {
                val ssid = barcode.wifi?.ssid ?: ""
                Pair("QR Code detected: Wi-Fi network $ssid", rawValue)
            }

            Barcode.TYPE_CONTACT_INFO -> {
                val name = barcode.contactInfo?.name?.formattedName ?: ""
                Pair("QR Code detected: contact $name", rawValue)
            }

            Barcode.TYPE_EMAIL -> {
                val email = barcode.email?.address ?: ""
                Pair("QR Code detected: email $email", rawValue)
            }

            Barcode.TYPE_PHONE -> {
                val phone = barcode.phone?.number ?: rawValue
                Pair("QR Code detected: phone number $phone", rawValue)
            }

            // ── Fallback ────────────────────────────────────────────────────
            else -> {
                Pair("Code detected: $rawValue", rawValue)
            }
        }
    }

    /**
     * Checks whether a barcode content string is eligible to be spoken
     * (outside the cooldown window).
     */
    private fun isEligibleToSpeak(content: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val lastTime = lastSpokenTimestamp[content] ?: return true

        if (now - lastTime > COOLDOWN_MS) {
            lastSpokenTimestamp[content] = now
            return true
        }
        return false
    }

    /**
     * Records that a barcode content was just spoken.
     */
    private fun recordSpoken(content: String) {
        lastSpokenTimestamp[content] = SystemClock.uptimeMillis()
    }

    /**
     * Clears all cooldown timestamps.
     */
    fun clearCooldowns() {
        lastSpokenTimestamp.clear()
    }

    /**
     * Releases the ML Kit barcode scanner resources.
     */
    fun close() {
        scanner.close()
    }

    companion object {
        private const val TAG = "BarcodeAnalyzer"

        /** Cooldown per barcode content in milliseconds. */
        const val COOLDOWN_MS = 3500L
    }
}
