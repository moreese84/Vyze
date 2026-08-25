package com.vyze.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects human faces in camera frames and produces spatial announcements
 * for TTS readout.
 *
 * ## Spatial Mapping
 * Each detected face is mapped to:
 *  - **Orientation:** "facing you" (eulerY close to 0°) or "side profile"
 *  - **Proximity:** "close" (face bbox area > 15% of frame), "far" (< 5%), "" medium
 *  - **Position:** "on your left", "directly ahead", "on your right" (by center X)
 *
 * ## Speech Debouncing
 * A 4,000 ms cooldown per face state (count + position combination) prevents
 * TTS spamming when the same faces remain in view.
 *
 * ## Memory Safety
 * The intermediate [Bitmap] is created from the camera frame and recycled
 * after ML Kit consumes it.
 */
class FaceDetectorHelper {

    private val detector: FaceDetector

    /**
     * Timestamp of when each face state was last announced.
     * Keyed by "count|position" string.
     */
    private val lastSpokenTimestamp = ConcurrentHashMap<String, Long>()

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(MIN_FACE_SIZE)
            .build()

        detector = FaceDetection.getClient(options)
    }

    /**
     * Processes a [Bitmap] for face detection.
     *
     * @param bitmap          The camera frame as a Bitmap (ARGB_8888).
     * @param rotationDegrees Rotation applied to the image.
     * @param onSuccess       Callback with list of face announcements.
     * @param onError         Callback invoked when detection fails.
     */
    fun processBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int,
        onSuccess: (List<String>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                val announcements = buildAnnouncements(faces, bitmap.width, bitmap.height)
                onSuccess(announcements)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed", e)
                onError(e)
            }
    }

    /**
     * Builds TTS announcements for detected faces with spatial context.
     *
     * For a single face: "Person facing you, close, on your right"
     * For multiple faces: "2 people detected directly ahead"
     */
    private fun buildAnnouncements(
        faces: List<Face>,
        frameWidth: Int,
        frameHeight: Int
    ): List<String> {
        if (faces.isEmpty()) return emptyList()

        val results = mutableListOf<String>()

        if (faces.size == 1) {
            // Single face — detailed spatial announcement
            val face = faces[0]
            val spatial = computeFaceSpatial(face, frameWidth, frameHeight)

            val stateKey = "1|${spatial.position}"
            if (!isWithinCooldown(stateKey)) {
                recordSpoken(stateKey)
                results.add(spatial.toAnnouncement())
            }
        } else {
            // Multiple faces — count-based announcement
            val avgCenterX = faces.map { it.boundingBox.centerX() }.average().toFloat()
            val position = classifyPosition(avgCenterX, frameWidth)

            val stateKey = "${faces.size}|$position"
            if (!isWithinCooldown(stateKey)) {
                recordSpoken(stateKey)
                results.add("${faces.size} people detected $position")
            }
        }

        return results
    }

    /**
     * Computes spatial information for a single face.
     */
    private fun computeFaceSpatial(
        face: Face,
        frameWidth: Int,
        frameHeight: Int
    ): FaceSpatialInfo {
        val box = face.boundingBox
        val centerX = box.centerX().toFloat() / frameWidth
        val position = classifyPosition(box.centerX().toFloat(), frameWidth)

        // Proximity based on face bbox area relative to frame area
        val faceArea = box.width().toLong() * box.height().toLong()
        val frameArea = frameWidth.toLong() * frameHeight.toLong()
        val areaFraction = if (frameArea > 0) faceArea.toFloat() / frameArea.toFloat() else 0f

        val proximity = when {
            areaFraction > CLOSE_THRESHOLD  -> "close"
            areaFraction < FAR_THRESHOLD    -> "far"
            else                            -> ""
        }

        // Orientation based on eulerY (horizontal rotation)
        // eulerY close to 0° = facing camera, ±90° = side profile
        val eulerY = face.headEulerAngleY
        val orientation = when {
            kotlin.math.abs(eulerY) < ORIENTATION_THRESHOLD -> "facing you"
            else -> "side profile"
        }

        return FaceSpatialInfo(
            position = position,
            proximity = proximity,
            orientation = orientation
        )
    }

    /**
     * Classifies the horizontal center position of a face.
     */
    private fun classifyPosition(centerX: Float, frameWidth: Int): String {
        val normalised = centerX / frameWidth
        return when {
            normalised < LEFT_THRESHOLD  -> "on your left"
            normalised <= RIGHT_THRESHOLD -> "directly ahead"
            else                         -> "on your right"
        }
    }

    /**
     * Checks whether a face state is within the cooldown window.
     */
    private fun isWithinCooldown(stateKey: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val lastTime = lastSpokenTimestamp[stateKey] ?: return false
        return (now - lastTime) < COOLDOWN_MS
    }

    /**
     * Records that a face state was just spoken.
     */
    private fun recordSpoken(stateKey: String) {
        lastSpokenTimestamp[stateKey] = SystemClock.uptimeMillis()
    }

    /**
     * Clears all cooldown timestamps.
     */
    fun clearCooldowns() {
        lastSpokenTimestamp.clear()
    }

    /**
     * Releases the ML Kit face detector resources.
     */
    fun close() {
        detector.close()
    }

    // ── Data Classes ────────────────────────────────────────────────────────

    /**
     * Spatial information for a single detected face.
     */
    private data class FaceSpatialInfo(
        val position: String,
        val proximity: String,
        val orientation: String
    ) {
        /**
         * Formats the spatial info into a TTS announcement.
         * Example: "Person facing you, close, on your right"
         */
        fun toAnnouncement(): String {
            val parts = mutableListOf("Person")
            parts.add(orientation)
            if (proximity.isNotEmpty()) parts.add(proximity)
            parts.add(position)
            return parts.joinToString(", ")
        }
    }

    companion object {
        private const val TAG = "FaceDetectorHelper"

        /** Cooldown per face state in milliseconds. */
        const val COOLDOWN_MS = 4000L

        /** Centre-X normalised threshold: below this → left */
        const val LEFT_THRESHOLD = 0.35f
        /** Centre-X normalised threshold: above this → right */
        const val RIGHT_THRESHOLD = 0.65f

        /** Face bbox area fraction above this → "close" */
        const val CLOSE_THRESHOLD = 0.15f
        /** Face bbox area fraction below this → "far" */
        const val FAR_THRESHOLD = 0.05f

        /** Euler-Y threshold in degrees for "facing you" vs "side profile" */
        const val ORIENTATION_THRESHOLD = 30f

        /** Minimum face size as fraction of frame (smaller faces ignored) */
        const val MIN_FACE_SIZE = 0.1f
    }
}
