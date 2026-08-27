package com.vyze.app

import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Transforms raw YOLOv8 bounding boxes and ML Kit OCR text into structured
 * spatial JSON context for [AgentEngine].
 *
 * ## Coordinate Pipeline
 * Incoming bounding boxes are in **frame coordinates** (post-unpad, pre-scale).
 * The builder maps them to spatial zones (left / center / right) and proximity
 * bands (close / medium / far) so the agent receives human-readable spatial
 * context without needing raw pixel math.
 *
 * ## Output Format
 * The JSON fed to Gemini Nano looks like:
 * ```json
 * {
 *   "scene": {
 *     "timestamp_ms": 1719475200000,
 *     "objects": [
 *       { "label": "chair", "confidence": 0.87, "zone": "left", "proximity": "close", "box_area_pct": 12.3 },
 *       { "label": "table", "confidence": 0.72, "zone": "center", "proximity": "medium", "box_area_pct": 5.1 }
 *     ],
 *     "ocr_text": "EXIT sign above door",
 *     "total_objects": 2
 *   }
 * }
 * ```
 */
class AgentContextBuilder {

    // ── Spatial Thresholds ────────────────────────────────────────────
    // Frame is divided into 3 horizontal zones: left, center, right.
    // proximity is based on bounding box area as % of total frame area.

    companion object {
        /** Fraction of frame width defining the left zone boundary. */
        private const val LEFT_ZONE_MAX = 0.35f

        /** Fraction of frame width defining the right zone boundary. */
        private const val RIGHT_ZONE_MIN = 0.65f

        /** Box area > this fraction of frame area = "close". */
        private const val CLOSE_AREA_THRESHOLD = 0.10f

        /** Box area < this fraction of frame area = "far". */
        private const val FAR_AREA_THRESHOLD = 0.02f

        private const val TAG = "AgentContextBuilder"
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Build a structured spatial JSON string from the latest detection results.
     *
     * @param detections    YOLOv8 detection results (bounding boxes in frame coords).
     * @param frameWidth    Width of the original camera frame (pre-letterbox).
     * @param frameHeight   Height of the original camera frame (pre-letterbox).
     * @param ocrText       Latest OCR text from ML Kit (may be empty).
     * @return A JSON string describing the current scene.
     */
    fun buildContext(
        detections: List<ObjectDetectorHelper.VyzeDetection>,
        frameWidth: Int,
        frameHeight: Int,
        ocrText: String = ""
    ): String {
        val timestamp = System.currentTimeMillis()
        val frameArea = frameWidth.toLong() * frameHeight.toLong()

        val objectsArray = JSONArray()

        // Group detections by label + zone for deduplication
        val grouped = detections
            .mapNotNull { detection ->
                val category = detection.categories.firstOrNull() ?: return@mapNotNull null
                val box = detection.boundingBox
                val zone = computeZone(box, frameWidth)
                val proximity = computeProximity(box, frameArea)
                val areaPct = if (frameArea > 0) {
                    (box.width().toLong() * box.height().toLong()).toFloat() / frameArea.toFloat() * 100f
                } else 0f

                ContextObject(
                    label = category.label,
                    confidence = category.score,
                    zone = zone,
                    proximity = proximity,
                    boxAreaPct = areaPct
                )
            }
            .sortedByDescending { it.confidence }

        // Deduplicate: keep highest-confidence instance per label+zone
        val seen = mutableSetOf<String>()
        for (obj in grouped) {
            val key = "${obj.label}|${obj.zone}"
            if (seen.add(key)) {
                objectsArray.put(obj.toJson())
            }
        }

        val sceneJson = JSONObject().apply {
            put("timestamp_ms", timestamp)
            put("objects", objectsArray)
            put("ocr_text", ocrText)
            put("total_objects", objectsArray.length())
        }

        val root = JSONObject().apply {
            put("scene", sceneJson)
        }

        return root.toString(2) // pretty-print with 2-space indent
    }

    /**
     * Build context from a [ObjectDetectorHelper.ResultBundle] directly.
     */
    fun buildContextFromResultBundle(
        resultBundle: ObjectDetectorHelper.ResultBundle,
        ocrText: String = ""
    ): String {
        // Unpad boxes to frame coordinates before building context
        val frameDetections = resultBundle.detections.map { det ->
            ObjectDetectorHelper.VyzeDetection(
                boundingBox = resultBundle.unpadBox(det.boundingBox),
                categories = det.categories
            )
        }
        return buildContext(
            detections = frameDetections,
            frameWidth = resultBundle.inputImageWidth,
            frameHeight = resultBundle.inputImageHeight,
            ocrText = ocrText
        )
    }

    /**
     * Build a compact text summary (non-JSON) for quick rule-based fallback.
     *
     * Example output: `"chair 0.87 left close, table 0.72 center medium"`
     */
    fun buildCompactSummary(
        detections: List<ObjectDetectorHelper.VyzeDetection>,
        frameWidth: Int,
        frameHeight: Int,
        ocrText: String = ""
    ): String {
        val frameArea = frameWidth.toLong() * frameHeight.toLong()
        val parts = mutableListOf<String>()

        for (detection in detections) {
            val category = detection.categories.firstOrNull() ?: continue
            val zone = computeZone(detection.boundingBox, frameWidth)
            val proximity = computeProximity(detection.boundingBox, frameArea)
            val proxStr = if (proximity.isNotEmpty()) " $proximity" else ""
            parts.add("${category.label} ${(category.score * 100).toInt()}% $zone$proxStr")
        }

        val objectSummary = parts.joinToString("; ")
        return if (ocrText.isNotBlank()) {
            "Objects: $objectSummary. Text visible: $ocrText"
        } else {
            "Objects: $objectSummary"
        }
    }

    // ── Spatial Mapping ───────────────────────────────────────────────

    private fun computeZone(box: RectF, frameWidth: Int): String {
        if (frameWidth <= 0) return "center"
        val centerX = box.centerX() / frameWidth.toFloat()
        return when {
            centerX < LEFT_ZONE_MAX  -> "left"
            centerX > RIGHT_ZONE_MIN -> "right"
            else                     -> "center"
        }
    }

    private fun computeProximity(box: RectF, frameArea: Long): String {
        if (frameArea <= 0) return "medium"
        val boxArea = box.width().toLong() * box.height().toLong()
        val fraction = boxArea.toFloat() / frameArea.toFloat()
        return when {
            fraction > CLOSE_AREA_THRESHOLD -> "close"
            fraction < FAR_AREA_THRESHOLD   -> "far"
            else                            -> "medium"
        }
    }

    // ── Internal Types ────────────────────────────────────────────────

    private data class ContextObject(
        val label: String,
        val confidence: Float,
        val zone: String,
        val proximity: String,
        val boxAreaPct: Float
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("label", label)
                put("confidence", String.format(Locale.US, "%.2f", confidence).toFloat())
                put("zone", zone)
                put("proximity", proximity)
                put("box_area_pct", String.format(Locale.US, "%.1f", boxAreaPct).toFloat())
            }
        }
    }
}
