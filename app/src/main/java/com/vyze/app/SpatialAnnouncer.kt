package com.vyze.app

import android.graphics.RectF
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure spatial mapping and speech debouncing for accessibility announcements.
 *
 * Extracted from [ObjectDetectorHelper] to separate presentation/accessibility
 * logic from ML inference. This class has NO dependency on:
 * - Android Context
 * - TTS engine
 * - ML pipeline or camera frames
 *
 * ## Responsibilities
 * 1. Map bounding box coordinates to spatial zones (left / center / right)
 * 2. Compute proximity from box area ratio (close / medium / far)
 * 3. Debounce speech announcements per zone-key
 * 4. Build natural-language announcement strings from [SpatialInfo]
 *
 * ## Usage
 * ```kotlin
 * val announcer = SpatialAnnouncer()
 *
 * // From detection results
 * val announcements = announcer.getAnnounceableDetections(
 *     detections = resultBundle.detections,
 *     frameWidth = resultBundle.inputImageWidth,
 *     frameHeight = resultBundle.inputImageHeight
 * )
 * if (announcements.isNotEmpty()) ttsManager.speakImmediate(announcements.first())
 * ```
 */
class SpatialAnnouncer {

    // ── Speech Debounce State ─────────────────────────────────────
    // Key: "label|direction|proximity", Value: timestamp of last spoken
    private val lastSpokenTimestamp = ConcurrentHashMap<String, Long>()

    // ── Spatial Mapping ───────────────────────────────────────────

    data class SpatialInfo(
        val categoryName: String,
        val score: Float,
        val boundingBox: RectF,
        val direction: String,
        val proximity: String,
        val zoneKey: String
    )

    /**
     * Compute spatial zone and proximity for a single bounding box.
     *
     * @param boundingBox  Detection box in frame coordinates (post-unpad).
     * @param frameWidth   Width of the original camera frame.
     * @param frameHeight  Height of the original camera frame.
     * @param categoryName Human-readable label (e.g. "chair").
     * @param score        Confidence score from the detector.
     */
    fun computeSpatialInfo(
        boundingBox: RectF,
        frameWidth: Int,
        frameHeight: Int,
        categoryName: String,
        score: Float
    ): SpatialInfo {
        val centerX = (boundingBox.centerX().toFloat() / frameWidth).coerceIn(0f, 1f)
        val direction = when {
            centerX < LEFT_THRESHOLD  -> DIR_LEFT
            centerX <= RIGHT_THRESHOLD -> DIR_AHEAD
            else                       -> DIR_RIGHT
        }

        val boxArea = boundingBox.width().toLong() * boundingBox.height().toLong()
        val frameArea = frameWidth.toLong() * frameHeight.toLong()
        val areaFraction = if (frameArea > 0) boxArea.toFloat() / frameArea.toFloat() else 0f
        val proximity = when {
            areaFraction > CLOSE_THRESHOLD  -> PROX_CLOSE
            areaFraction < FAR_THRESHOLD    -> PROX_FAR
            else                            -> PROX_MEDIUM
        }

        val zoneKey = "$categoryName|$direction|$proximity"
        return SpatialInfo(categoryName, score, boundingBox, direction, proximity, zoneKey)
    }

    // ── Speech Debouncing ─────────────────────────────────────────

    /**
     * Check if this spatial zone is eligible to be spoken.
     *
     * Returns true if:
     * - The zone was never spoken, OR
     * - The zone was spoken more than [DEBOUNCE_MS] ago, OR
     * - The same label is currently being spoken in a different zone
     *   (e.g. "chair on your left" is active, so "chair on your right" should also speak)
     */
    fun isEligibleToSpeak(spatial: SpatialInfo): Boolean {
        val now = SystemClock.uptimeMillis()
        val prev = lastSpokenTimestamp[spatial.zoneKey]

        if (prev == null || now - prev > DEBOUNCE_MS) {
            lastSpokenTimestamp[spatial.zoneKey] = now
            return true
        }

        // Allow cross-zone announcements for the same label
        val labelPrefix = spatial.categoryName + "|"
        val hasDifferentZone = lastSpokenTimestamp.any { (key, ts) ->
            key.startsWith(labelPrefix) && key != spatial.zoneKey && (now - ts <= DEBOUNCE_MS)
        }
        if (hasDifferentZone) {
            lastSpokenTimestamp[spatial.zoneKey] = now
            return true
        }

        return false
    }

    /**
     * Clear all debounce timestamps. Call on settings change, mode switch,
     * or when the user explicitly requests a fresh announcement.
     */
    fun clearDebounceState() {
        lastSpokenTimestamp.clear()
    }

    // ── Announcement Generation ───────────────────────────────────

    /**
     * Build a natural-language announcement string from a [SpatialInfo].
     *
     * Returns null if:
     * - The proximity is empty (medium = no announcement)
     * - The zone is debounced (recently spoken)
     */
    fun buildAnnouncement(spatial: SpatialInfo): String? {
        if (spatial.proximity.isEmpty()) return null
        if (!isEligibleToSpeak(spatial)) return null
        val proximityText = if (spatial.proximity.isNotEmpty()) ", ${spatial.proximity}" else ""
        return "${spatial.categoryName} ${spatial.direction}$proximityText"
    }

    /**
     * Generate announceable strings for all detections in a result bundle.
     *
     * Each detection is mapped to spatial info, debounced, and converted
     * to a natural-language string. Results are sorted by confidence
     * (highest first) so the most important object is announced first.
     *
     * @param detections  Raw detections from the detector (bounding boxes in frame coords).
     * @param frameWidth  Width of the original camera frame.
     * @param frameHeight Height of the original camera frame.
     * @return List of announcement strings, most confident first.
     */
    fun getAnnounceableDetections(
        detections: List<ObjectDetectorHelper.VyzeDetection>,
        frameWidth: Int,
        frameHeight: Int
    ): List<String> {
        return detections.mapNotNull { detection ->
            val category = detection.categories.firstOrNull() ?: return@mapNotNull null
            val spatial = computeSpatialInfo(
                detection.boundingBox, frameWidth, frameHeight,
                category.label, category.score
            )
            buildAnnouncement(spatial)
        }.sortedByDescending { ann ->
            detections.firstOrNull { d ->
                val cat = d.categories.firstOrNull()
                cat != null && buildAnnouncement(
                    computeSpatialInfo(
                        d.boundingBox, frameWidth, frameHeight,
                        cat.label, cat.score
                    )
                ) == ann
            }?.categories?.firstOrNull()?.score ?: 0f
        }
    }

    // ── Companion Constants ───────────────────────────────────────

    companion object {
        private const val TAG = "SpatialAnnouncer"

        /** Fraction of frame width defining the left zone boundary. */
        const val LEFT_THRESHOLD = 0.35f

        /** Fraction of frame width defining the right zone boundary. */
        const val RIGHT_THRESHOLD = 0.65f

        /** Box area > this fraction of frame area = "close". */
        const val CLOSE_THRESHOLD = 0.15f

        /** Box area < this fraction of frame area = "far". */
        const val FAR_THRESHOLD = 0.05f

        const val DIR_LEFT   = "on your left"
        const val DIR_AHEAD  = "directly ahead"
        const val DIR_RIGHT  = "on your right"

        const val PROX_CLOSE  = "close"
        const val PROX_FAR    = "far"
        const val PROX_MEDIUM = ""

        /** Minimum milliseconds between announcements for the same zone. */
        const val DEBOUNCE_MS = 3500L
    }
}
