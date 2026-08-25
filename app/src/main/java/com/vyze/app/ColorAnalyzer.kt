package com.vyze.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Analyzes the dominant color of a [Bitmap] by sampling the central 20%
 * region of interest (ROI) and classifying the average HSV value into one
 * of 12 distinct human-readable color terms.
 *
 * ## Sampling Strategy
 * The center 20% of the image is used to avoid edge artifacts (fingers,
 * borders, vignetting) and focus on the subject the user is pointing at.
 *
 * ## Color Classification
 * Pixels are converted from RGB to HSV (Hue 0–360°, Saturation 0–1,
 * Value/Brightness 0–1). The dominant color is classified by:
 *  1. **Saturation check** — very low saturation yields Black / Grey / White
 *     based on brightness.
 *  2. **Hue mapping** — the hue angle is mapped to one of 9 chromatic colors
 *     (Red, Orange, Yellow, Green, Cyan, Blue, Purple, Pink, Brown).
 *  3. **Brightness modifier** — "Dark" or "Bright" is prepended when value
 *     is below 0.35 or above 0.75 respectively.
 *
 * Returns a formatted string like "Dark Blue", "Bright Yellow", or "Grey".
 */
class ColorAnalyzer {

    /**
     * Analyzes the center 20% ROI of the given [Bitmap] and returns a
     * human-readable color name.
     *
     * @param bitmap The input image. A copy is created internally for safe
     *               pixel reading; the original is not modified.
     * @return A string like "Dark Blue", "Bright Yellow", "Red", "Grey", etc.
     *         Returns "Unknown" if the bitmap is too small or invalid.
     */
    fun analyzeCenterColor(bitmap: Bitmap): String {
        if (bitmap.width < 4 || bitmap.height < 4) return UNKNOWN_COLOR

        // ── Step 1: Extract center 20% ROI ─────────────────────────────────
        val roiWidth  = (bitmap.width  * ROI_FRACTION).toInt().coerceAtLeast(1)
        val roiHeight = (bitmap.height * ROI_FRACTION).toInt().coerceAtLeast(1)
        val startX = ((bitmap.width  - roiWidth)  / 2).coerceAtLeast(0)
        val startY = ((bitmap.height - roiHeight) / 2).coerceAtLeast(0)

        val roiBitmap = Bitmap.createBitmap(bitmap, startX, startY, roiWidth, roiHeight)

        // ── Step 2: Sample pixels and accumulate RGB ───────────────────────
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var pixelCount = 0

        // Downsample large ROIs for speed — sample every Nth pixel
        val step = max(1, min(roiWidth, roiHeight) / SAMPLE_GRID)

        for (y in 0 until roiHeight step step) {
            for (x in 0 until roiWidth step step) {
                val pixel = roiBitmap.getPixel(x, y)
                totalR += Color.red(pixel)
                totalG += Color.green(pixel)
                totalB += Color.blue(pixel)
                pixelCount++
            }
        }

        if (pixelCount == 0) return UNKNOWN_COLOR

        val avgR = (totalR / pixelCount).toInt()
        val avgG = (totalG / pixelCount).toInt()
        val avgB = (totalB / pixelCount).toInt()

        // ── Step 3: Convert RGB → HSV ──────────────────────────────────────
        val hsv = FloatArray(3)
        Color.RGBToHSV(avgR, avgG, avgB, hsv)
        val hue        = hsv[0]  // 0–360
        val saturation = hsv[1]  // 0–1
        val value      = hsv[2]  // 0–1

        // ── Step 4: Classify ───────────────────────────────────────────────
        return classifyColor(hue, saturation, value)
    }

    /**
     * Classifies an HSV triplet into a human-readable color name.
     *
     * @param hue        Hue angle in degrees (0–360).
     * @param saturation Saturation (0–1). Near-zero = grayscale.
     * @param value      Brightness/Value (0–1). Near-zero = black, near-one = bright.
     * @return A 12-class color name with optional brightness modifier.
     */
    private fun classifyColor(hue: Float, saturation: Float, value: Float): String {
        // ── Achromatic path (low saturation) ───────────────────────────────
        if (saturation < SATURATION_THRESHOLD) {
            return when {
                value < BLACK_MAX_VALUE   -> BLACK
                value > WHITE_MIN_VALUE   -> WHITE
                else                      -> GREY
            }
        }

        // ── Chromatic path (map hue to color name) ─────────────────────────
        val baseColor = when (hue) {
            in   0.0 ..  15.0, in 345.0 .. 360.0 -> RED
            in  15.0 ..  40.0                     -> ORANGE
            in  40.0 ..  70.0                     -> YELLOW
            in  70.0 .. 160.0                     -> GREEN
            in 160.0 .. 195.0                     -> CYAN
            in 195.0 .. 260.0                     -> BLUE
            in 260.0 .. 300.0                     -> PURPLE
            in 300.0 .. 335.0                     -> PINK
            in 335.0 .. 345.0                     -> BROWN
            else                                  -> RED
        }

        // ── Brightness modifier ────────────────────────────────────────────
        return when {
            value < DARK_MAX_VALUE  -> "Dark $baseColor"
            value > BRIGHT_MIN_VALUE -> "Bright $baseColor"
            else                    -> baseColor
        }
    }

    companion object {
        private const val TAG = "ColorAnalyzer"

        /** Fraction of the image to sample from the center (20%). */
        const val ROI_FRACTION = 0.20f

        /** Max grid dimension for pixel sampling (controls speed vs accuracy). */
        const val SAMPLE_GRID = 20

        // ── Achromatic Thresholds ───────────────────────────────────────────

        /** Below this saturation → grayscale classification. */
        const val SATURATION_THRESHOLD = 0.12f

        /** Below this value → Black. */
        const val BLACK_MAX_VALUE = 0.15f

        /** Above this value → White. */
        const val WHITE_MIN_VALUE = 0.85f

        // ── Brightness Modifiers ────────────────────────────────────────────

        /** Below this value → "Dark" prefix. */
        const val DARK_MAX_VALUE = 0.35f

        /** Above this value → "Bright" prefix. */
        const val BRIGHT_MIN_VALUE = 0.75f

        // ── Color Names ─────────────────────────────────────────────────────

        const val BLACK  = "Black"
        const val WHITE  = "White"
        const val GREY   = "Grey"
        const val RED    = "Red"
        const val ORANGE = "Orange"
        const val YELLOW = "Yellow"
        const val GREEN  = "Green"
        const val CYAN   = "Cyan"
        const val BLUE   = "Blue"
        const val PURPLE = "Purple"
        const val PINK   = "Pink"
        const val BROWN  = "Brown"

        const val UNKNOWN_COLOR = "Unknown"
    }
}
