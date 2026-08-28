package com.vyze.app

import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure spatial mapping, scene grouping, and speech debouncing for accessibility announcements.
 *
 * ## Responsibilities
 * 1. Map bounding box coordinates to spatial zones (left / center / right)
 * 2. Compute proximity from box area ratio (close / medium / far)
 * 3. Group multiple instances of the same class (e.g. "3 chairs" instead of "chair, chair, chair")
 * 4. Debounce speech announcements per class with configurable cooldown
 * 5. Build natural-language announcement strings from [SpatialInfo]
 */
class SpatialAnnouncer {

    private val lastSpokenTimestamp = ConcurrentHashMap<String, Long>()

    data class SpatialInfo(
        val categoryName: String,
        val score: Float,
        val boundingBox: RectF,
        val direction: String,
        val proximity: String,
        val zoneKey: String
    )

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

    fun isEligibleToSpeak(label: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val prev = lastSpokenTimestamp[label]
        if (prev == null || now - prev > DEBOUNCE_MS) {
            lastSpokenTimestamp[label] = now
            return true
        }
        Log.d(TAG, "isEligibleToSpeak: DEBOUNCE active for '$label' " +
            "(${now - prev}ms < ${DEBOUNCE_MS}ms)")
        return false
    }

    fun clearDebounceState() {
        lastSpokenTimestamp.clear()
    }

    // ── Scene Grouping ────────────────────────────────────────────

    /**
     * Group detections by class label and build a scene description.
     *
     * ALWAYS returns a non-null string when detections is non-empty.
     * Falls back to raw label list if grouping fails.
     */
    fun buildSceneDescription(
        detections: List<ObjectDetectorHelper.VyzeDetection>,
        frameWidth: Int,
        frameHeight: Int
    ): String? {
        if (detections.isEmpty()) {
            Log.d(TAG, "buildSceneDescription: empty detections → null")
            return null
        }

        Log.d(TAG, "buildSceneDescription: ${detections.size} detections, " +
            "frame=${frameWidth}x${frameHeight}")

        // Group detections by class label
        val classGroups = mutableMapOf<String, MutableList<SpatialInfo>>()

        for (detection in detections) {
            val category = detection.categories.firstOrNull() ?: continue
            val spatial = computeSpatialInfo(
                detection.boundingBox, frameWidth, frameHeight,
                category.label, category.score
            )
            classGroups.getOrPut(category.label) { mutableListOf() }.add(spatial)
        }

        if (classGroups.isEmpty()) {
            // Fallback: extract raw labels from detections
            val rawLabels = detections.mapNotNull { it.categories.firstOrNull()?.label }
            if (rawLabels.isNotEmpty()) {
                val fallback = rawLabels.joinToString(", ")
                Log.d(TAG, "buildSceneDescription: classGroups empty, fallback=$fallback")
                return fallback
            }
            return null
        }

        // Build grouped announcements — always include at least one part
        val parts = mutableListOf<String>()

        for ((label, group) in classGroups) {
            val count = group.size
            val pluralLabel = pluralize(label, count)

            // Always include this class in the description (no debounce gate here
            // — debounce only prevents REPEATS, not first-time announcements)
            when {
                count == 1 -> {
                    val spatial = group.first()
                    parts.add(formatSingle(spatial))
                }
                count == 2 -> {
                    val positions = group.map { formatDirection(it) }.distinct()
                    if (positions.size == 1) {
                        parts.add("$count $pluralLabel ${positions.first()}")
                    } else {
                        parts.add("$count $pluralLabel")
                    }
                }
                else -> {
                    val byDirection = group.groupBy { it.direction }
                    if (byDirection.size == 1) {
                        parts.add("$count $pluralLabel ${byDirection.keys.first()}")
                    } else {
                        parts.add("$count $pluralLabel")
                    }
                }
            }
        }

        val result = if (parts.isNotEmpty()) parts.joinToString(". ") else {
            // Last resort: raw comma-separated labels
            detections.mapNotNull { it.categories.firstOrNull()?.label }
                .joinToString(", ")
                .ifEmpty { null }
        }

        Log.d(TAG, "buildSceneDescription: result=$result")
        return result
    }

    fun getAnnounceableDetections(
        detections: List<ObjectDetectorHelper.VyzeDetection>,
        frameWidth: Int,
        frameHeight: Int
    ): List<String> {
        if (detections.isEmpty()) return emptyList()

        // First try scene grouping
        val sceneDesc = buildSceneDescription(detections, frameWidth, frameHeight)
        if (sceneDesc != null) {
            return listOf(sceneDesc)
        }

        // Fallback: individual detections — always return something for non-empty
        val results = detections.mapNotNull { detection ->
            val category = detection.categories.firstOrNull() ?: return@mapNotNull null
            val spatial = computeSpatialInfo(
                detection.boundingBox, frameWidth, frameHeight,
                category.label, category.score
            )
            buildAnnouncement(spatial)
        }

        // If debounce blocked everything, return at least the top detection label
        if (results.isEmpty() && detections.isNotEmpty()) {
            val topLabel = detections.maxByOrNull {
                it.categories.firstOrNull()?.score ?: 0f
            }?.categories?.firstOrNull()?.label
            if (topLabel != null) {
                Log.d(TAG, "getAnnounceableDetections: debounce blocked all, fallback=$topLabel")
                return listOf(topLabel)
            }
        }

        return results
    }

    // ── Announcement Generation ───────────────────────────────────

    fun buildAnnouncement(spatial: SpatialInfo): String? {
        if (!isEligibleToSpeak(spatial.categoryName)) return null
        val proximityText = if (spatial.proximity.isNotEmpty()) ", ${spatial.proximity}" else ""
        return "${spatial.categoryName} ${spatial.direction}$proximityText"
    }

    private fun formatSingle(spatial: SpatialInfo): String {
        val proximityText = if (spatial.proximity.isNotEmpty()) ", ${spatial.proximity}" else ""
        return "${spatial.categoryName} ${spatial.direction}$proximityText"
    }

    private fun formatDirection(spatial: SpatialInfo): String {
        return spatial.direction
    }

    private fun pluralize(label: String, count: Int): String {
        if (count == 1) return label

        val irregulars = mapOf(
            "person" to "people",
            "mouse" to "mice",
            "child" to "children",
            "tooth" to "teeth",
            "foot" to "feet",
            "goose" to "geese",
            "man" to "men",
            "woman" to "women"
        )

        val lowerLabel = label.lowercase()
        if (lowerLabel in irregulars) {
            return irregulars[lowerLabel]!!
        }

        return when {
            lowerLabel.endsWith("s") -> "${label}es"
            lowerLabel.endsWith("y") && !lowerLabel.endsWith("ay") &&
                !lowerLabel.endsWith("ey") && !lowerLabel.endsWith("oy") &&
                !lowerLabel.endsWith("uy") -> "${label.dropLast(1)}ies"
            else -> "${label}s"
        }
    }

    // ── Companion Constants ───────────────────────────────────────

    companion object {
        private const val TAG = "SpatialAnnouncer"

        const val LEFT_THRESHOLD = 0.35f
        const val RIGHT_THRESHOLD = 0.65f
        const val CLOSE_THRESHOLD = 0.15f
        const val FAR_THRESHOLD = 0.05f

        const val DIR_LEFT   = "on your left"
        const val DIR_AHEAD  = "directly ahead"
        const val DIR_RIGHT  = "on your right"

        const val PROX_CLOSE  = "close"
        const val PROX_FAR    = "far"
        const val PROX_MEDIUM = ""

        /** Per-class debounce cooldown — reduced to 1.5 seconds. */
        const val DEBOUNCE_MS = 1500L
    }
}
