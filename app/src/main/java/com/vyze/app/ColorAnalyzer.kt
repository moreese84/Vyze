package com.vyze.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Analyzes the dominant color of a [Bitmap] by sampling the central 20%
 * region of interest (ROI) and classifying the average HSV value into one
 * of 12 distinct human-readable color terms.
 *
 * ## Localization
 * All color names and brightness modifiers are loaded from string resources
 * (values/strings.xml, values-ms/strings.xml, values-zh/strings.xml),
 * enabling automatic support for English, Bahasa Melayu, and Mandarin.
 *
 * ## Sampling Strategy
 * The center 20% of the image is used to avoid edge artifacts.
 *
 * ## Color Classification
 * Pixels are converted from RGB to HSV. The dominant color is classified by:
 *  1. Saturation check → Black / Grey / White
 *  2. Hue mapping → Red, Orange, Yellow, Green, Cyan, Blue, Purple, Pink, Brown
 *  3. Brightness modifier → "Dark" or "Bright" prefix
 */
class ColorAnalyzer {

    /**
     * Analyzes the center 20% ROI of the given [Bitmap] and returns a
     * human-readable color name localized via string resources.
     *
     * @param context  Used to load localized color name strings.
     * @param bitmap   The input image.
     * @return A localized color name like "Dark Blue", "Bright Yellow", "Grey", etc.
     */
    fun analyzeCenterColor(context: Context, bitmap: Bitmap): String {
        if (bitmap.width < 4 || bitmap.height < 4) return context.getString(R.string.color_unknown)

        // ── Step 1: Extract center 20% ROI ─────────────────────────────
        val roiWidth = (bitmap.width * ROI_FRACTION).toInt().coerceAtLeast(1)
        val roiHeight = (bitmap.height * ROI_FRACTION).toInt().coerceAtLeast(1)
        val startX = ((bitmap.width - roiWidth) / 2).coerceAtLeast(0)
        val startY = ((bitmap.height - roiHeight) / 2).coerceAtLeast(0)

        val roiBitmap = Bitmap.createBitmap(bitmap, startX, startY, roiWidth, roiHeight)

        // ── Step 2: Sample pixels and accumulate RGB ───────────────────
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var pixelCount = 0

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

        if (pixelCount == 0) return context.getString(R.string.color_unknown)

        val avgR = (totalR / pixelCount).toInt()
        val avgG = (totalG / pixelCount).toInt()
        val avgB = (totalB / pixelCount).toInt()

        // ── Step 3: Convert RGB → HSV ──────────────────────────────────
        val hsv = FloatArray(3)
        Color.RGBToHSV(avgR, avgG, avgB, hsv)
        val hue = hsv[0]        // 0–360
        val saturation = hsv[1] // 0–1
        val value = hsv[2]      // 0–1

        // ── Step 4: Classify ───────────────────────────────────────────
        return classifyColor(context, hue, saturation, value)
    }

    /**
     * Classifies an HSV triplet into a localized human-readable color name.
     */
    private fun classifyColor(context: Context, hue: Float, saturation: Float, value: Float): String {
        // ── Achromatic path (low saturation) ───────────────────────────
        if (saturation < SATURATION_THRESHOLD) {
            val achromaticKey = when {
                value < BLACK_MAX_VALUE -> R.string.color_black
                value > WHITE_MIN_VALUE -> R.string.color_white
                else -> R.string.color_grey
            }
            return context.getString(achromaticKey)
        }

        // ── Chromatic path (map hue to color name) ─────────────────────
        val baseColorKey = when (hue) {
            in 0.0..15.0, in 345.0..360.0 -> R.string.color_red
            in 15.0..40.0 -> R.string.color_orange
            in 40.0..70.0 -> R.string.color_yellow
            in 70.0..160.0 -> R.string.color_green
            in 160.0..195.0 -> R.string.color_cyan
            in 195.0..260.0 -> R.string.color_blue
            in 260.0..300.0 -> R.string.color_purple
            in 300.0..335.0 -> R.string.color_pink
            in 335.0..345.0 -> R.string.color_brown
            else -> R.string.color_red
        }

        val baseColor = context.getString(baseColorKey)

        // ── Brightness modifier ────────────────────────────────────────
        return when {
            value < DARK_MAX_VALUE -> context.getString(R.string.color_dark_prefix, baseColor)
            value > BRIGHT_MIN_VALUE -> context.getString(R.string.color_bright_prefix, baseColor)
            else -> baseColor
        }
    }

    // ── Backward-compatible overload (no context) ─────────────────────

    /**
     * Legacy overload that returns English color names.
     * Prefer the Context-based overload for localized results.
     */
    fun analyzeCenterColor(bitmap: Bitmap): String {
        if (bitmap.width < 4 || bitmap.height < 4) return UNKNOWN_COLOR

        val roiWidth = (bitmap.width * ROI_FRACTION).toInt().coerceAtLeast(1)
        val roiHeight = (bitmap.height * ROI_FRACTION).toInt().coerceAtLeast(1)
        val startX = ((bitmap.width - roiWidth) / 2).coerceAtLeast(0)
        val startY = ((bitmap.height - roiHeight) / 2).coerceAtLeast(0)
        val roiBitmap = Bitmap.createBitmap(bitmap, startX, startY, roiWidth, roiHeight)

        var totalR = 0L; var totalG = 0L; var totalB = 0L; var pixelCount = 0
        val step = max(1, min(roiWidth, roiHeight) / SAMPLE_GRID)
        for (y in 0 until roiHeight step step) {
            for (x in 0 until roiWidth step step) {
                val pixel = roiBitmap.getPixel(x, y)
                totalR += Color.red(pixel); totalG += Color.green(pixel); totalB += Color.blue(pixel)
                pixelCount++
            }
        }
        if (pixelCount == 0) return UNKNOWN_COLOR
        val avgR = (totalR / pixelCount).toInt()
        val avgG = (totalG / pixelCount).toInt()
        val avgB = (totalB / pixelCount).toInt()

        val hsv = FloatArray(3)
        Color.RGBToHSV(avgR, avgG, avgB, hsv)
        val hue = hsv[0]; val saturation = hsv[1]; val value = hsv[2]

        return classifyColorEnglish(hue, saturation, value)
    }

    private fun classifyColorEnglish(hue: Float, saturation: Float, value: Float): String {
        if (saturation < SATURATION_THRESHOLD) {
            return when {
                value < BLACK_MAX_VALUE -> BLACK
                value > WHITE_MIN_VALUE -> WHITE
                else -> GREY
            }
        }
        val baseColor = when (hue) {
            in 0.0..15.0, in 345.0..360.0 -> RED
            in 15.0..40.0 -> ORANGE
            in 40.0..70.0 -> YELLOW
            in 70.0..160.0 -> GREEN
            in 160.0..195.0 -> CYAN
            in 195.0..260.0 -> BLUE
            in 260.0..300.0 -> PURPLE
            in 300.0..335.0 -> PINK
            in 335.0..345.0 -> BROWN
            else -> RED
        }
        return when {
            value < DARK_MAX_VALUE -> "Dark $baseColor"
            value > BRIGHT_MIN_VALUE -> "Bright $baseColor"
            else -> baseColor
        }
    }

    companion object {
        const val TAG = "ColorAnalyzer"
        const val ROI_FRACTION = 0.20f
        const val SAMPLE_GRID = 20
        const val SATURATION_THRESHOLD = 0.12f
        const val BLACK_MAX_VALUE = 0.15f
        const val WHITE_MIN_VALUE = 0.85f
        const val DARK_MAX_VALUE = 0.35f
        const val BRIGHT_MIN_VALUE = 0.75f

        // English fallback constants
        const val BLACK = "Black"; const val WHITE = "White"; const val GREY = "Grey"
        const val RED = "Red"; const val ORANGE = "Orange"; const val YELLOW = "Yellow"
        const val GREEN = "Green"; const val CYAN = "Cyan"; const val BLUE = "Blue"
        const val PURPLE = "Purple"; const val PINK = "Pink"; const val BROWN = "Brown"
        const val UNKNOWN_COLOR = "Unknown"
    }
}
