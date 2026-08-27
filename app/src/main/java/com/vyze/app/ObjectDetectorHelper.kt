/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.vyze.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 object detection using the TensorFlow Lite Interpreter.
 *
 * Replaces MediaPipe's high-level ObjectDetector with a raw TFLite
 * pipeline so we can load any model — including YOLOv8-nano trained on
 * Open Images V7 (601 classes) or Objects365 (365 classes).
 *
 * ## Input Pipeline
 *
 * 1. Bitmap is pre-rotated to portrait and letterboxed to 640×640 by
 *    [com.vyze.app.delegates.MlPipelineManager].
 * 2. We normalize pixels to [0, 1] and write to a `[1, 640, 640, 3]`
 *    float32 input tensor.
 *
 * ## Output Pipeline
 *
 * YOLOv8 outputs `[1, 4+numClasses, 8400]`:
 * - Channels 0–3: `[cx, cy, w, h]` in 640×640 pixel coordinates.
 * - Channels 4+: class confidence scores.
 *
 * We transpose to `[8400, 4+C]`, filter by confidence threshold, apply
 * Non-Maximum Suppression, and return [VyzeDetection] results whose
 * bounding boxes are in the **letterboxed** coordinate space (same as
 * the input bitmap).
 */
class ObjectDetectorHelper(
    var threshold: Float = THRESHOLD_DEFAULT,
    var maxResults: Int = MAX_RESULTS_DEFAULT,
    val context: Context,
    var objectDetectorListener: DetectorListener? = null
) : Closeable {

    // Legacy stubs — kept for MainViewModel / settings spinner compatibility.
    // TFLite YOLOv8 uses a single model; delegate/model selection is no-op.
    var currentDelegate: Int = DELEGATE_CPU
    var currentModel: Int = MODEL_EFFICIENTDETV0

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    // Model input dimensions (read from model metadata or default to 640)
    private var inputSize = INPUT_SIZE_DEFAULT

    // Whether the model expects NCHW [1,3,640,640] or NHWC [1,640,640,3]
    private var inputIsNCHW = false

    // Actual model input shape logged at load time
    private var inputShape = "unknown"

    @Volatile
    var lastResultBundle: ResultBundle? = null
        private set

    init {
        setupObjectDetector()
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }

    // Keep the same name as before so callers don't break
    fun clearObjectDetector() = close()

    // ── Detector Setup ────────────────────────────────────────────────────────

    fun setupObjectDetector() {
        try {
            // ── Load model via FileChannel → MappedByteBuffer ─────────────
            // Using context.assets.openFd() + FileInputStream ensures the
            // .tflite binary is memory-mapped directly from the APK without
            // decompression or copy.  This is the most reliable approach for
            // TFLite model loading on Android.
            Log.i(TAG, "Attempting to load model: $MODEL_FILE")

            val assetFileDescriptor = context.assets.openFd(MODEL_FILE)
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val modelBuffer: MappedByteBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                assetFileDescriptor.startOffset,
                assetFileDescriptor.declaredLength
            )
            fileChannel.close()
            fileInputStream.close()
            assetFileDescriptor.close()

            Log.i(TAG, "Model buffer loaded: ${modelBuffer.capacity()} bytes")

            // ── Create Interpreter ───────────────────────────────────────
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)

            Log.i(TAG, "Interpreter created successfully")

            // ── Load labels ──────────────────────────────────────────────
            labels = context.assets.open(LABELS_FILE).bufferedReader().readLines()

            Log.i(TAG, "Loaded ${labels.size} labels from $LABELS_FILE")

            // ── Auto-detect input tensor layout ────────────────────────
            val inputTensorInfo: Tensor = interpreter!!.getInputTensor(0)
            val shape = inputTensorInfo.shape()  // e.g. [1, 640, 640, 3] or [1, 3, 640, 640]
            inputShape = shape.contentToString()

            inputIsNCHW = if (shape.size == 4) {
                // [N, C, H, W] → shape[1] < shape[2] means channels-first
                shape[1] <= 4 && shape[2] > shape[1]
            } else {
                false
            }

            if (shape.size == 4) {
                inputSize = if (inputIsNCHW) shape[2] else shape[1]
            }

            Log.i(TAG, "Input tensor shape: $inputShape, layout=${if (inputIsNCHW) "NCHW" else "NHWC"}, inputSize=$inputSize")

            // ── Log output tensor shape ────────────────────────────────
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outShape = outputTensor.shape()
            Log.i(TAG, "Output tensor shape: ${outShape.contentToString()}")
            Log.i(TAG, "Model ready: $MODEL_FILE, ${labels.size} labels")

        } catch (e: java.io.FileNotFoundException) {
            Log.e(TAG, "MODEL FILE NOT FOUND: $MODEL_FILE — place it in app/src/main/assets/", e)
            objectDetectorListener?.onError(
                "Model file not found: $MODEL_FILE. Place the .tflite file in assets/ folder."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TFLite: ${e.message}", e)
            e.printStackTrace()
            objectDetectorListener?.onError(
                "Object detector failed to initialize: ${e.message}"
            )
        }
    }

    fun isClosed(): Boolean = interpreter == null

    // ── Live-Stream Detection ─────────────────────────────────────────────────

    /**
     * Run YOLOv8 detection on a pre-processed bitmap.
     *
     * The caller MUST ensure the bitmap is:
     * 1. Rotated to upright portrait.
     * 2. Letterboxed to a square (640×640).
     *
     * @param bitmap            Portrait-oriented, letterboxed bitmap (ARGB_8888).
     * @param frameTime         Timestamp from [SystemClock.uptimeMillis].
     * @param originalWidth     Width of the frame before letterboxing.
     * @param originalHeight    Height of the frame before letterboxing.
     */
    fun detectLivestreamBitmap(
        bitmap: Bitmap,
        frameTime: Long,
        originalWidth: Int,
        originalHeight: Int
    ): ResultBundle? {
        val interp = interpreter ?: run {
            Log.e(TAG, "detectLivestreamBitmap: interpreter is NULL")
            return null
        }

        return try {
            val startTime = SystemClock.uptimeMillis()

            // 1. Preprocess: letterbox resize to model input size + normalize
            val inputTensor = preprocessBitmap(bitmap)

            // 2. Run inference
            val outputShape = interp.getOutputTensor(0).shape() // [1, 4+C, 8400]
            val numChannels = outputShape[1]
            val numAnchors = outputShape[2]
            val outputBuffer = Array(1) { Array(numChannels) { FloatArray(numAnchors) } }
            interp.run(inputTensor, outputBuffer)

            // 3. Parse output: transpose, filter, NMS
            val detections = parseOutput(outputBuffer[0], bitmap.width, bitmap.height)

            val inferenceTime = SystemClock.uptimeMillis() - startTime

            Log.d(TAG, "detect: ${detections.size} det, ${inferenceTime}ms")

            // Compute letterbox padding
            val padX = (bitmap.width - originalWidth) / 2
            val padY = (bitmap.height - originalHeight) / 2

            ResultBundle(
                detections = detections,
                inferenceTime = inferenceTime,
                inputImageHeight = originalHeight,
                inputImageWidth = originalWidth,
                letterboxPadX = padX,
                letterboxPadY = padY
            ).also { lastResultBundle = it }
        } catch (e: Exception) {
            Log.e(TAG, "detectLivestreamBitmap: ${e.message}", e)
            null
        }
    }

    // ── Video Detection (stub — not yet implemented for TFLite) ─────────────

    fun detectVideoFile(videoUri: android.net.Uri, inferenceIntervalMs: Long): ResultBundle? {
        Log.w(TAG, "detectVideoFile not yet implemented for TFLite YOLOv8")
        return null
    }

    // ── Gallery Detection ─────────────────────────────────────────────────────

    fun detectImage(image: Bitmap): ResultBundle? {
        val interp = interpreter ?: return null

        return try {
            val startTime = SystemClock.uptimeMillis()

            // For gallery images, we letterbox to the model's input size
            val letterboxed = letterboxToModelSize(image)
            val inputTensor = preprocessBitmap(letterboxed)

            val outputShape = interp.getOutputTensor(0).shape()
            val numChannels = outputShape[1]
            val numAnchors = outputShape[2]
            val outputBuffer = Array(1) { Array(numChannels) { FloatArray(numAnchors) } }
            interp.run(inputTensor, outputBuffer)

            val detections = parseOutput(outputBuffer[0], letterboxed.width, letterboxed.height)
            val inferenceTime = SystemClock.uptimeMillis() - startTime

            if (!letterboxed.isRecycled) letterboxed.recycle()

            ResultBundle(
                detections = detections,
                inferenceTime = inferenceTime,
                inputImageHeight = image.height,
                inputImageWidth = image.width,
                letterboxPadX = (letterboxed.width - image.width) / 2,
                letterboxPadY = (letterboxed.height - image.height) / 2
            )
        } catch (e: Exception) {
            Log.e(TAG, "detectImage: ${e.message}", e)
            null
        }
    }

    // ── Preprocessing ─────────────────────────────────────────────────────────

    /**
     * Convert a Bitmap into the TFLite input tensor, normalized to [0, 1].
     *
     * Auto-detects the model's expected layout:
     * - NCHW `[1, 3, H, W]`: writes R-plane, G-plane, B-plane sequentially.
     * - NHWC `[1, H, W, 3]`: writes RGB interleaved per pixel.
     *
     * The bitmap is expected to already be the correct size (640×640 after
     * letterboxing by MlPipelineManager). If not, it is resized.
     */
    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val resized = if (bitmap.width != inputSize || bitmap.height != inputSize) {
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        } else {
            bitmap
        }

        // Total floats: 1 × 3 × 640 × 640 = 1,228,800
        val totalFloats = 1 * 3 * inputSize * inputSize
        val buffer = ByteBuffer.allocateDirect(totalFloats * 4)
        buffer.order(ByteOrder.nativeOrder())
        buffer.rewind()

        // Extract all pixel ARGB ints
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        if (inputIsNCHW) {
            // ── NCHW layout: [1, 3, H, W] ───────────────────────────
            // Channel-first: write all R values, then all G, then all B.
            // This is what your YOLOv8 Open Images model expects.
            val planeSize = inputSize * inputSize

            // R plane
            for (pixel in pixels) {
                buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            }
            // G plane
            for (pixel in pixels) {
                buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            }
            // B plane
            for (pixel in pixels) {
                buffer.putFloat((pixel and 0xFF) / 255.0f)
            }
        } else {
            // ── NHWC layout: [1, H, W, 3] ───────────────────────────
            // Channel-last: RGB interleaved per pixel.
            for (pixel in pixels) {
                buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
                buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
                buffer.putFloat((pixel and 0xFF) / 255.0f)          // B
            }
        }

        if (resized !== bitmap && !resized.isRecycled) resized.recycle()

        buffer.rewind()
        return buffer
    }

    /**
     * Letterbox a bitmap to the model's square input size, preserving
     * aspect ratio with gray padding.
     */
    private fun letterboxToModelSize(source: Bitmap): Bitmap {
        val srcW = source.width
        val srcH = source.height
        if (srcW == inputSize && srcH == inputSize) return source

        val scale = min(inputSize.toFloat() / srcW, inputSize.toFloat() / srcH)
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()

        val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        canvas.drawColor(Color.rgb(114, 114, 114)) // YOLOv8 default gray padding

        val left = (inputSize - scaledW) / 2
        val top = (inputSize - scaledH) / 2
        val destRect = android.graphics.Rect(left, top, left + scaledW, top + scaledH)
        canvas.drawBitmap(source, null, destRect, null)

        return letterboxed
    }

    // ── Box type (shared by parseOutput and NMS) ──────────────────────────────

    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    // ── Output Parsing ────────────────────────────────────────────────────────

    /**
     * Parse YOLOv8 raw output tensor `[4+C, 8400]` into filtered detections.
     *
     * Steps:
     * 1. For each of the 8400 anchors, extract box `(cx, cy, w, h)` from
     *    channels 0–3 and find the top class confidence from channels 4+.
     * 2. Filter by [threshold].
     * 3. Convert center-format `(cx, cy, w, h)` → corner-format
     *    `(left, top, right, bottom)` in model-input pixel coordinates.
     * 4. Apply greedy Non-Maximum Suppression.
     * 5. Scale coordinates from model input space (640×640) back to the
     *    actual letterboxed bitmap dimensions.
     *
     * @param rawOutput  Shape `[4+C, 8400]` — first dimension is channels,
     *                   second is anchor count.
     * @param bitmapW    Width of the letterboxed bitmap the model received.
     * @param bitmapH    Height of the letterboxed bitmap the model received.
     */
    private fun parseOutput(
        rawOutput: Array<FloatArray>,  // shape [4+C, 8400]
        bitmapW: Int,
        bitmapH: Int
    ): List<VyzeDetection> {
        val numChannels = rawOutput.size     // 4 + numClasses  (e.g. 605)
        val numAnchors = rawOutput[0].size   // 8400
        val numClasses = numChannels - 4     // e.g. 601

        // ── Auto-detect normalized [0,1] vs pixel-space [0..640] ──────
        // Sample the first 100 anchors' cx values.  If the maximum is
        // < 2.0 the model outputs normalized coordinates and we must
        // scale by inputSize before any further processing.
        var maxCx = 0f
        for (i in 0 until min(100, numAnchors)) {
            val v = rawOutput[0][i]
            if (v > maxCx) maxCx = v
        }
        val outputIsNormalized = maxCx < 2.0f
        val boxScale = if (outputIsNormalized) inputSize.toFloat() else 1f

        if (outputIsNormalized) {
            Log.d(TAG, "Model output is NORMALIZED [0,1] — auto-scaling by $inputSize")
        }

        // ── Step 1: Find best class per anchor, filter by threshold ─────
        data class RawDetection(
            val cx: Float, val cy: Float, val w: Float, val h: Float,
            val classIdx: Int, val score: Float
        )

        val candidates = mutableListOf<RawDetection>()

        for (i in 0 until numAnchors) {
            val cx = rawOutput[0][i] * boxScale
            val cy = rawOutput[1][i] * boxScale
            val w  = rawOutput[2][i] * boxScale
            val h  = rawOutput[3][i] * boxScale

            // Skip degenerate boxes
            if (w <= 0f || h <= 0f) continue

            // Find best class score among channels 4..4+C
            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val score = rawOutput[4 + c][i]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }

            if (bestScore >= threshold && bestClass >= 0) {
                candidates.add(RawDetection(cx, cy, w, h, bestClass, bestScore))
            }
        }

        if (candidates.isEmpty()) return emptyList()

        Log.d(TAG, "parseOutput: ${candidates.size} candidates after threshold=${threshold}, " +
            "normalized=$outputIsNormalized, maxCx=$maxCx")

        // ── Step 2: Center-format → corner-format ──────────────────────
        val boxes = candidates.map {
            Box(it.cx - it.w / 2, it.cy - it.h / 2, it.cx + it.w / 2, it.cy + it.h / 2)
        }

        // ── Step 3: Non-Maximum Suppression ────────────────────────────
        val nmsIndices = nonMaxSuppression(boxes, candidates.map { it.score }, NMS_IOU_THRESHOLD)

        // ── Step 4: Scale to bitmap coords and build result ────────────
        // The box coords are now in model input pixel space (0..inputSize).
        // The letterboxed bitmap may differ (e.g. 1280×1280 from MlPipelineManager).
        // Scale factor = bitmapSize / modelInputSize.
        val scaleX = bitmapW.toFloat() / inputSize
        val scaleY = bitmapH.toFloat() / inputSize

        return nmsIndices.take(maxResults).mapNotNull { idx ->
            val det = candidates[idx]
            val box = boxes[idx]
            val label = if (det.classIdx < labels.size) labels[det.classIdx] else "class_${det.classIdx}"

            VyzeDetection(
                boundingBox = RectF(
                    box.left * scaleX,
                    box.top * scaleY,
                    box.right * scaleX,
                    box.bottom * scaleY
                ),
                categories = listOf(VyzeCategory(label = label, score = det.score))
            )
        }
    }

    /**
     * Greedy Non-Maximum Suppression.
     *
     * @param boxes     List of bounding boxes in corner format.
     * @param scores    Confidence scores (parallel to boxes).
     * @param iouThreshold  IoU threshold for suppression.
     * @return Indices of surviving boxes, sorted by score descending.
     */
    private fun nonMaxSuppression(
        boxes: List<Box>,
        scores: List<Float>,
        iouThreshold: Float
    ): List<Int> {
        // Sort by score descending
        val sortedIndices = scores.indices.sortedByDescending { scores[it] }
        val suppressed = BooleanArray(sortedIndices.size)
        val result = mutableListOf<Int>()

        for (i in sortedIndices.indices) {
            if (suppressed[i]) continue
            val idx = sortedIndices[i]
            result.add(idx)

            for (j in i + 1 until sortedIndices.size) {
                if (suppressed[j]) continue
                val jdx = sortedIndices[j]
                val iou = computeIoU(boxes[idx], boxes[jdx])
                if (iou > iouThreshold) {
                    suppressed[j] = true
                }
            }
        }

        return result
    }

    /** Compute Intersection-over-Union between two boxes. */
    private fun computeIoU(a: Box, b: Box): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = aArea + bArea - interArea

        return if (unionArea > 0) interArea / unionArea else 0f
    }

    // ── Result Types ──────────────────────────────────────────────────────────

    /**
     * A single detection — our replacement for MediaPipe's [ObjectDetectorResult].
     */
    data class VyzeDetection(
        val boundingBox: RectF,
        val categories: List<VyzeCategory>
    )

    data class VyzeCategory(
        val label: String,
        val score: Float
    )

    /**
     * Wraps inference results, timing, and coordinate-mapping information.
     */
    data class ResultBundle(
        val detections: List<VyzeDetection>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
        val inputImageRotation: Int = 0,
        val letterboxPadX: Int = 0,
        val letterboxPadY: Int = 0
    ) {
        /**
         * Convert a bounding box from letterboxed coordinates to the
         * original (pre-letterbox) frame coordinates.
         */
        fun unpadBox(box: RectF): RectF {
            return RectF(
                box.left - letterboxPadX,
                box.top - letterboxPadY,
                box.right - letterboxPadX,
                box.bottom - letterboxPadY
            )
        }
    }

    companion object {
        const val MODEL_FILE = "yolov8n.tflite"
        const val LABELS_FILE = "labels.txt"
        const val INPUT_SIZE_DEFAULT = 640
        const val MAX_RESULTS_DEFAULT = 10
        const val THRESHOLD_DEFAULT = 0.25F
        const val NMS_IOU_THRESHOLD = 0.45f
        const val OTHER_ERROR = 0
        const val GPU_ERROR = 1
        // Legacy constants kept for MainViewModel / settings compatibility
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val MODEL_EFFICIENTDETV0 = 0
        const val MODEL_EFFICIENTDETV2 = 1
        const val TAG = "ObjectDetectorHelper"
    }

    interface DetectorListener {
        fun onError(error: String, errorCode: Int = OTHER_ERROR)
        fun onResults(resultBundle: ResultBundle)
    }
}
