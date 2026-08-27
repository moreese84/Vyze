package com.vyze.app

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Aggregates spatial object detections into a natural-language scene summary.
 *
 * Instead of announcing objects individually (e.g. "chair on your left,
 * chair on your right, table directly ahead"), this aggregator merges
 * duplicates and constructs a single coherent sentence:
 *
 * ```
 * "You are facing 1 table, 2 chairs, and 1 person ahead. 1 chair is on your left."
 * ```
 *
 * ## Duplicate Merging
 * Objects with the same label and direction are counted (e.g. "2 chairs
 * directly ahead") rather than repeated.
 *
 * ## Speech Debouncing
 * A 6,000 ms cooldown prevents repetitive room summaries when the user
 * holds the camera steady.
 *
 * ## Input Format
 * Accepts a list of [SpatialAnnouncer.SpatialInfo] objects or
 * pre-formatted strings in the format `"label|direction"`.
 */
class SceneAggregator {

    /**
     * Timestamp of when the last scene summary was spoken.
     */
    @Volatile
    private var lastSpokenTime: Long = 0L

    /**
     * Aggregates a list of spatial detections into a natural sentence.
     *
     * @param detections List of [SpatialAnnouncer.SpatialInfo] from the detector.
     * @return A natural-language scene summary, or null if nothing valid / cooldown active.
     */
    fun aggregate(detections: List<SpatialAnnouncer.SpatialInfo>): String? {
        if (detections.isEmpty()) return null

        // Check cooldown
        val now = SystemClock.uptimeMillis()
        if (now - lastSpokenTime < COOLDOWN_MS) return null

        // Filter out medium-proximity detections (empty proximity string)
        val significant = detections.filter { it.proximity.isNotEmpty() }
        if (significant.isEmpty()) return null

        // Group by direction, then by label within each direction
        val byDirection = significant.groupBy { it.direction }

        val parts = mutableListOf<String>()

        // Priority order: ahead first, then left, then right
        val directionOrder = listOf(
            SpatialAnnouncer.DIR_AHEAD,
            SpatialAnnouncer.DIR_LEFT,
            SpatialAnnouncer.DIR_RIGHT
        )

        for (direction in directionOrder) {
            val dirDetections = byDirection[direction] ?: continue

            // Count objects: Map<label, count>
            val counted = dirDetections
                .groupBy { it.categoryName.lowercase() }
                .mapValues { it.value.size }

            val objectParts = counted.map { (label, count) ->
                if (count == 1) "1 $label" else "$count ${label}s"
            }

            if (objectParts.isNotEmpty()) {
                val objectSummary = formatList(objectParts)
                parts.add("$objectSummary $direction")
            }
        }

        if (parts.isEmpty()) return null

        // Record cooldown
        lastSpokenTime = now

        // Build the final sentence
        return buildSceneSentence(parts)
    }

    /**
     * Aggregates pre-formatted detection strings (e.g. from TTS announcements).
     * Expected format: "label direction" or "label, proximity, direction".
     *
     * @param detectionStrings List of announcement strings.
     * @return A natural-language scene summary, or null.
     */
    fun aggregateStrings(detectionStrings: List<String>): String? {
        if (detectionStrings.isEmpty()) return null

        // Parse each string to extract label and direction
        val spatialInfos = detectionStrings.mapNotNull { parseDetectionString(it) }
        return aggregate(spatialInfos)
    }

    /**
     * Parses a detection string like "chair on your left" or "person facing you, close, ahead"
     * into a [SpatialAnnouncer.SpatialInfo].
     */
    private fun parseDetectionString(input: String): SpatialAnnouncer.SpatialInfo? {
        val lower = input.lowercase()

        // Find the direction in the string
        val direction = when {
            lower.contains("on your left")  -> SpatialAnnouncer.DIR_LEFT
            lower.contains("on your right") -> SpatialAnnouncer.DIR_RIGHT
            lower.contains("directly ahead") || lower.contains("ahead") -> SpatialAnnouncer.DIR_AHEAD
            else -> return null
        }

        // Extract the label: everything before the direction
        val dirIndex = lower.indexOf(direction)
        if (dirIndex <= 0) return null

        val beforeDirection = input.substring(0, dirIndex).trim()
            .removeSuffix(",").trim()

        // Remove common prefixes like "1 ", "2 ", "Person ", etc.
        val label = beforeDirection
            .replace(Regex("^\\d+\\s+"), "")
            .replace(Regex("^person\\s+", RegexOption.IGNORE_CASE), "")
            .trim()

        if (label.isEmpty()) return null

        // Determine proximity from the string
        val proximity = when {
            lower.contains("close") -> SpatialAnnouncer.PROX_CLOSE
            lower.contains("far")   -> SpatialAnnouncer.PROX_FAR
            else                    -> SpatialAnnouncer.PROX_MEDIUM
        }

        return SpatialAnnouncer.SpatialInfo(
            categoryName = label,
            score = 1.0f,
            boundingBox = android.graphics.RectF(),
            direction = direction,
            proximity = proximity,
            zoneKey = "$label|$direction|$proximity"
        )
    }

    /**
     * Builds a natural scene sentence from aggregated parts.
     *
     * Example: "You are facing 1 table and 2 chairs ahead. 1 person is on your right."
     */
    private fun buildSceneSentence(directionParts: List<String>): String {
        if (directionParts.isEmpty()) return ""

        if (directionParts.size == 1) {
            return "You are facing ${directionParts[0]}."
        }

        // Multiple directions: first part uses "You are facing", rest use ". X is ..."
        val first = directionParts[0]
        val rest = directionParts.drop(1).joinToString(". ") { part ->
            val countAndObjects = part.substringBefore(" ")
            val direction = part.substringAfter(" ")
            // Reconstruct: "1 chair is on your left"
            val objectPart = part.substringBeforeLast(" ")
            "$objectPart is $direction"
        }

        return "You are facing $first. $rest"
    }

    /**
     * Formats a list of strings into a natural English list.
     * ["1 table", "2 chairs", "1 person"] → "1 table, 2 chairs, and 1 person"
     */
    private fun formatList(items: List<String>): String {
        return when (items.size) {
            0    -> ""
            1    -> items[0]
            2    -> "${items[0]} and ${items[1]}"
            else -> items.dropLast(1).joinToString(", ") + ", and ${items.last()}"
        }
    }

    /**
     * Clears the cooldown timer. Call on settings change or mode switch.
     */
    fun resetCooldown() {
        lastSpokenTime = 0L
    }

    companion object {
        private const val TAG = "SceneAggregator"

        /** Cooldown in milliseconds to avoid repetitive scene summaries. */
        const val COOLDOWN_MS = 6000L
    }
}
