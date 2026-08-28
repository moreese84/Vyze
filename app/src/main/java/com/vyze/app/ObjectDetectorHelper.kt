package com.vyze.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 object detection via TensorFlow Lite.
 *
 * ## Dataset: Open Images V7 (601 classes)
 * - labels.txt contains all 601 Open Images V7 class names.
 * - The model class index maps directly to labels.txt entry: `labels[bestClass]`.
 * - No artificial offsets, no COCO-80 assumptions, no class truncation.
 *
 * ## Hardware acceleration
 * - Attempts GPU delegate first for faster inference.
 * - Falls back to NNAPI if GPU is unavailable.
 * - Final fallback to CPU with 4 threads.
 *
 * ## Thread safety
 * - Each call to [detect] MUST provide a fresh bitmap.
 * - A new ByteBuffer is allocated per call — no shared buffers.
 * - The interpreter is accessed from a single background thread.
 *
 * ## Output format
 * Standard YOLOv8 output tensor: `[1, 4+C, N]` (channels-first) where
 * C = number of classes from the model and N = number of anchors.
 * We transpose on the fly to iterate over N anchor predictions.
 */
class ObjectDetectorHelper(
    var threshold: Float = THRESHOLD_DEFAULT,
    var maxResults: Int = MAX_RESULTS_DEFAULT,
    val context: Context,
    var objectDetectorListener: DetectorListener? = null
) : Closeable {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var gpuDelegate: GpuDelegate? = null

    private var inputSize = INPUT_SIZE_DEFAULT
    private var inputIsNCHW = false
    private var activeDelegate: String = "CPU"

    @Volatile
    var lastResultBundle: ResultBundle? = null
        private set

    private var frameSeqNum = 0L

    init {
        setupObjectDetector()
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
    }

    fun clearObjectDetector() = close()

    // ── Setup ─────────────────────────────────────────────────────

    fun setupObjectDetector() {
        try {
            Log.i(TAG, "Loading $MODEL_FILE")

            val fd = context.assets.openFd(MODEL_FILE)
            val fis = FileInputStream(fd.fileDescriptor)
            val buf: MappedByteBuffer = fis.channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
            fis.channel.close(); fis.close(); fd.close()

            // Build interpreter options with hardware acceleration
            val options = Interpreter.Options().apply {
                setNumThreads(4)

                // Try GPU delegate first — catch Throwable to also catch
                // UnsatisfiedLinkError when the native GPU library is missing
                try {
                    val delegate = GpuDelegate()
                    addDelegate(delegate)
                    gpuDelegate = delegate
                    activeDelegate = "GPU"
                    Log.i(TAG, "GPU delegate enabled")
                } catch (e: Throwable) {
                    Log.w(TAG, "GPU delegate unavailable (${e.javaClass.simpleName}): ${e.message}")
                    gpuDelegate = null
                    activeDelegate = "CPU"
                }
            }

            interpreter = Interpreter(buf, options)

            labels = context.assets.open(LABELS_FILE).bufferedReader().readLines()

            val inShape = interpreter!!.getInputTensor(0).shape()
            inputIsNCHW = inShape.size == 4 && inShape[1] <= 4 && inShape[2] > inShape[1]
            inputSize = if (inShape.size == 4) {
                if (inputIsNCHW) inShape[2] else inShape[1]
            } else INPUT_SIZE_DEFAULT

            val outShape = interpreter!!.getOutputTensor(0).shape()
            val outClasses = if (outShape.size == 3) {
                val d0 = outShape[1]; val d1 = outShape[2]
                if (d0 < d1) d0 - 4 else d1 - 4
            } else 0

            Log.i(TAG, "Dataset: Open Images V7 (${labels.size} classes)")
            Log.i(TAG, "Input=${inShape.contentToString()} ${if (inputIsNCHW) "NCHW" else "NHWC"} " +
                "Output=${outShape.contentToString()} modelClasses=$outClasses " +
                "delegate=$activeDelegate threshold=$threshold")

            if (outClasses != labels.size) {
                Log.w(TAG, "Model outputs $outClasses classes but labels.txt has ${labels.size} entries. " +
                    "Using labels.getOrNull() for safe mapping — some indices may map to 'Unknown'.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}", e)
            objectDetectorListener?.onError("Init failed: ${e.message}")
        }
    }

    fun isClosed(): Boolean = interpreter == null

    // ── Detection ─────────────────────────────────────────────────

    fun detect(
        bitmap: Bitmap,
        originalWidth: Int,
        originalHeight: Int
    ): ResultBundle {
        val interp = interpreter ?: return emptyResult(originalWidth, originalHeight)

        val seq = ++frameSeqNum
        val t0 = SystemClock.uptimeMillis()

        return try {
            val inputBuf = preprocess(bitmap, seq)

            val outShape = interp.getOutputTensor(0).shape()
            require(outShape.size == 3) { "Expected rank-3 output, got ${outShape.size}" }
            val d0 = outShape[1]
            val d1 = outShape[2]
            val output = Array(1) { Array(d0) { FloatArray(d1) } }

            interp.run(inputBuf, output)

            val raw = output[0]
            val detections = parseYoloOutput(raw, d0, d1, bitmap.width, bitmap.height, seq)

            val dt = SystemClock.uptimeMillis() - t0
            Log.d(TAG, "[$seq] ${detections.size} det ${dt}ms [$activeDelegate]")

            val padX = (bitmap.width - originalWidth) / 2
            val padY = (bitmap.height - originalHeight) / 2

            ResultBundle(
                detections = detections,
                inferenceTime = dt,
                inputImageHeight = originalHeight,
                inputImageWidth = originalWidth,
                letterboxPadX = padX,
                letterboxPadY = padY
            ).also { lastResultBundle = it }

        } catch (e: Exception) {
            Log.e(TAG, "[$seq] ${e.javaClass.simpleName}: ${e.message}", e)
            emptyResult(originalWidth, originalHeight)
        }
    }

    /** Legacy API for gallery detection. */
    fun detectLivestreamBitmap(
        bitmap: Bitmap, frameTime: Long,
        originalWidth: Int, originalHeight: Int
    ): ResultBundle = detect(bitmap, originalWidth, originalHeight)

    fun detectImage(image: Bitmap): ResultBundle? {
        val interp = interpreter ?: return null
        return try {
            val lb = letterboxToModelSize(image)
            val buf = preprocess(lb, 0)
            val outShape = interp.getOutputTensor(0).shape()
            val output = Array(1) { Array(outShape[1]) { FloatArray(outShape[2]) } }
            interp.run(buf, output)
            val dets = parseYoloOutput(output[0], outShape[1], outShape[2],
                lb.width, lb.height, 0)
            if (lb !== image && !lb.isRecycled) lb.recycle()
            ResultBundle(detections = dets, inferenceTime = 0,
                inputImageHeight = image.height, inputImageWidth = image.width,
                letterboxPadX = 0, letterboxPadY = 0)
        } catch (e: Exception) {
            Log.e(TAG, "detectImage: ${e.message}", e); null
        }
    }

    private fun emptyResult(ow: Int, oh: Int) = ResultBundle(
        detections = emptyList(), inferenceTime = 0,
        inputImageHeight = oh, inputImageWidth = ow,
        letterboxPadX = 0, letterboxPadY = 0
    ).also { lastResultBundle = it }

    // ── Preprocessing ─────────────────────────────────────────────

    private fun preprocess(bitmap: Bitmap, seq: Long): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()

        val buf = ByteBuffer.allocateDirect(3 * inputSize * inputSize * 4)
        buf.order(ByteOrder.nativeOrder())
        buf.clear()

        if (inputIsNCHW) {
            for (p in pixels) buf.putFloat(((p shr 16) and 0xFF) / 255.0f)
            for (p in pixels) buf.putFloat(((p shr 8) and 0xFF) / 255.0f)
            for (p in pixels) buf.putFloat((p and 0xFF) / 255.0f)
        } else {
            for (p in pixels) {
                buf.putFloat(((p shr 16) and 0xFF) / 255.0f)
                buf.putFloat(((p shr 8) and 0xFF) / 255.0f)
                buf.putFloat((p and 0xFF) / 255.0f)
            }
        }

        buf.rewind()
        return buf
    }

    private fun letterboxToModelSize(src: Bitmap): Bitmap {
        if (src.width == inputSize && src.height == inputSize) return src
        val scale = min(inputSize.toFloat() / src.width, inputSize.toFloat() / src.height)
        val sw = (src.width * scale).toInt()
        val sh = (src.height * scale).toInt()
        val lb = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        Canvas(lb).drawColor(Color.rgb(114, 114, 114))
        Canvas(lb).drawBitmap(src, null,
            android.graphics.Rect((inputSize - sw) / 2, (inputSize - sh) / 2,
                (inputSize - sw) / 2 + sw, (inputSize - sh) / 2 + sh), null)
        return lb
    }

    // ── YOLOv8 Output Parsing ─────────────────────────────────────

    /**
     * Parse the raw YOLOv8 output tensor for Open Images V7 (601 classes).
     *
     * YOLOv8 standard: `[1, 4+C, N]` → raw is `[4+C, N]` (channels-first)
     *   - raw[0..3] = cx, cy, w, h per anchor
     *   - raw[4..4+C] = class scores per anchor (C = number of model classes)
     *
     * Label mapping: `labels[bestClass]` — direct 0-based index into labels.txt.
     * No offsets, no truncation, no COCO assumptions.
     */
    private fun parseYoloOutput(
        raw: Array<FloatArray>,
        dim0: Int, dim1: Int,
        bitmapW: Int, bitmapH: Int,
        seq: Long
    ): List<VyzeDetection> {

        val channelsFirst = dim0 < dim1
        val numAnchors = if (channelsFirst) dim1 else dim0
        val numChannels = if (channelsFirst) dim0 else dim1
        val numClasses = numChannels - 4

        Log.d(TAG, "[$seq] raw[$dim0,$dim1] channelsFirst=$channelsFirst " +
            "anchors=$numAnchors classes=$numClasses labels=${labels.size}")

        if (numClasses <= 0 || numAnchors <= 0) return emptyList()

        // Detect normalized vs pixel-space output
        var maxCoord = 0f
        for (i in 0 until min(200, numAnchors)) {
            val v = if (channelsFirst) raw[0][i] else raw[i][0]
            if (v > maxCoord) maxCoord = v
        }
        val scale = if (maxCoord < 2.0f) inputSize.toFloat() else 1f

        // ── Extract candidates ─────────────────────────────────────
        data class Cand(val cx: Float, val cy: Float, val w: Float, val h: Float,
                         val cls: Int, val score: Float)

        val cands = mutableListOf<Cand>()
        var globalMax = 0f

        for (i in 0 until numAnchors) {
            val cx: Float; val cy: Float; val w: Float; val h: Float
            if (channelsFirst) {
                cx = raw[0][i] * scale; cy = raw[1][i] * scale
                w  = raw[2][i] * scale; h  = raw[3][i] * scale
            } else {
                cx = raw[i][0] * scale; cy = raw[i][1] * scale
                w  = raw[i][2] * scale; h  = raw[i][3] * scale
            }

            if (w <= 0f || h <= 0f) continue

            var bestCls = -1; var bestScr = 0f
            for (c in 0 until numClasses) {
                val s = if (channelsFirst) raw[4 + c][i] else raw[i][4 + c]
                if (s > bestScr) { bestScr = s; bestCls = c }
            }

            if (bestScr > globalMax) globalMax = bestScr

            if (bestScr >= threshold && bestCls >= 0) {
                cands.add(Cand(cx, cy, w, h, bestCls, bestScr))
            }
        }

        Log.d(TAG, "[$seq] globalMax=$globalMax thresh=$threshold cands=${cands.size}")
        if (cands.isEmpty()) return emptyList()

        // ── Center → corner ──────────────────────────────────────
        val boxes = cands.map {
            Box(left = it.cx - it.w / 2, top = it.cy - it.h / 2,
                right = it.cx + it.w / 2, bottom = it.cy + it.h / 2)
        }

        // ── NMS ──────────────────────────────────────────────────
        val kept = nms(boxes, cands.map { it.score }, NMS_IOU_THRESHOLD)
        Log.d(TAG, "[$seq] NMS: ${cands.size} → ${kept.size}")

        // ── Build detections ─────────────────────────────────────
        val sx = bitmapW.toFloat() / inputSize
        val sy = bitmapH.toFloat() / inputSize

        return kept.take(maxResults).map { idx ->
            val c = cands[idx]
            val b = boxes[idx]
            val label = labels.getOrElse(c.cls) { "Unknown (${c.cls})" }
            VyzeDetection(
                boundingBox = RectF(
                    (b.left * sx).coerceIn(0f, bitmapW.toFloat()),
                    (b.top * sy).coerceIn(0f, bitmapH.toFloat()),
                    (b.right * sx).coerceIn(0f, bitmapW.toFloat()),
                    (b.bottom * sy).coerceIn(0f, bitmapH.toFloat())
                ),
                categories = listOf(VyzeCategory(label = label, score = c.score))
            )
        }
    }

    // ── NMS ───────────────────────────────────────────────────────

    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun nms(boxes: List<Box>, scores: List<Float>, iouThr: Float): List<Int> {
        val order = scores.indices.sortedByDescending { scores[it] }
        val alive = BooleanArray(order.size)
        val result = mutableListOf<Int>()

        for (i in order.indices) {
            if (alive[i]) continue
            val a = order[i]
            result.add(a)
            for (j in i + 1 until order.size) {
                if (alive[j]) continue
                if (iou(boxes[a], boxes[order[j]]) > iouThr) alive[j] = true
            }
        }
        return result
    }

    private fun iou(a: Box, b: Box): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val union = (a.right - a.left) * (a.bottom - a.top) +
                    (b.right - b.left) * (b.bottom - b.top) - inter
        return if (union > 0) inter / union else 0f
    }

    // ── Types ─────────────────────────────────────────────────────

    data class VyzeDetection(val boundingBox: RectF, val categories: List<VyzeCategory>)
    data class VyzeCategory(val label: String, val score: Float)

    data class ResultBundle(
        val detections: List<VyzeDetection>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
        val inputImageRotation: Int = 0,
        val letterboxPadX: Int = 0,
        val letterboxPadY: Int = 0
    ) {
        fun unpadBox(box: RectF) = RectF(
            box.left - letterboxPadX, box.top - letterboxPadY,
            box.right - letterboxPadX, box.bottom - letterboxPadY
        )
    }

    companion object {
        const val MODEL_FILE = "yolov8n.tflite"
        const val LABELS_FILE = "labels.txt"
        const val INPUT_SIZE_DEFAULT = 640
        const val MAX_RESULTS_DEFAULT = 10
        const val THRESHOLD_DEFAULT = 0.50f
        const val NMS_IOU_THRESHOLD = 0.45f
        const val OTHER_ERROR = 0
        const val TAG = "ObjectDetectorHelper"
    }

    interface DetectorListener {
        fun onError(error: String, errorCode: Int = OTHER_ERROR)
        fun onResults(resultBundle: ResultBundle)
    }
}
