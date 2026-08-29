package com.vyze.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device VLM engine wrapper using Google's LiteRT-LM framework.
 *
 * ## Model
 * Gemma 3n E2B (Edge 2 Billion) — int4 quantized multimodal vision-language model.
 * File: gemma-3n-E2B-it-int4.litertlm (3.66 GB)
 *
 * ## Hardware Acceleration
 * GPU-only execution (OpenCL/Vulkan). CPU inference is not supported for this model size.
 *
 * ## Prompt Format
 * Uses Gemma's exact turn system prompt format:
 * `<start_of_turn>user\n{prompt}<end_of_turn>\n<start_of_turn>model\n`
 * Image patch tokens are bound natively by the LiteRT-LM engine when
 * passing the Bitmap — no literal [IMAGE_TOKEN] placeholder needed.
 *
 * ## Memory Management
 * Incoming Bitmap frames are downscaled to a max of 448×448 pixels before inference.
 * All scaled bitmaps are explicitly recycled after inference to prevent memory leaks.
 *
 * ## API
 * Use [analyzeImage] to send a camera frame + text prompt and get a response.
 * Uses the callback-based MessageCallback to avoid SendChannel crashes.
 */
class VlmEngineManager(private val context: Context) : Closeable {

    private var engine: Engine? = null

    @Volatile
    private var activeBackend: String = "NONE"

    @Volatile
    private var isInitialized = false

    // Callbacks for UI updates
    var onTokenGenerated: ((String) -> Unit)? = null
    var onComplete: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onModelCopyProgress: ((copied: Long, total: Long) -> Unit)? = null
    var onStepProgress: ((Int, String) -> Unit)? = null

    // ── Storage Permission Check ──────────────────────────────────

    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
        }
    }

    fun buildStorageSettingsIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            null
        }
    }

    // ── Initialization ─────────────────────────────────────────────

    /**
     * Initialize the Gemma 3n E2B engine.
     * GPU-only — enforced for LiteRT-LM execution of int4 quantized model.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true

        CrashLogFile.log(TAG, "=== INIT START (Gemma 3n E2B) ===")

        try {
            Log.i(TAG, "Starting Gemma 3n E2B initialization...")

            // Step 0: Ensure native library is loaded
            CrashLogFile.log(TAG, "Step 0: Loading native library")
            ensureNativeLibLoaded()

            // Step 1: Resolve model file
            onStepProgress?.invoke(10, "Resolving model file...")
            CrashLogFile.log(TAG, "Step 1: Resolving model file")

            val modelFile = resolveModelFile()
            if (modelFile == null) {
                val errorMsg = buildModelNotFoundError()
                Log.e(TAG, errorMsg)
                CrashLogFile.logError(TAG, errorMsg)
                onError?.invoke(errorMsg)
                return@withContext false
            }

            Log.i(TAG, "Model resolved: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            CrashLogFile.log(TAG, "Model: ${modelFile.absolutePath} (${modelFile.length() / (1024 * 1024)}MB)")

            // Step 2: GPU-only backend — enforced for LiteRT-LM int4 model
            onStepProgress?.invoke(50, "Initializing GPU backend...")
            CrashLogFile.log(TAG, "Step 2: Initializing GPU backend (enforced)...")

            val gpuSuccess = tryInitializeWithBackend(modelFile, Backend.GPU(), "GPU")
            if (gpuSuccess) {
                onStepProgress?.invoke(95, "Finalizing...")
                CrashLogFile.log(TAG, "=== INIT SUCCESS [GPU] ===")
                Log.i(TAG, "Gemma 3n E2B loaded successfully [backend=GPU]")
                return@withContext true
            }

            // GPU failed — no fallback to CPU for this model
            val errorMsg = "VLM init failed: GPU backend required but initialization failed. " +
                "CPU fallback is disabled for Gemma 3n E2B int4 model."
            Log.e(TAG, errorMsg)
            CrashLogFile.logError(TAG, errorMsg)
            onError?.invoke(errorMsg)
            false

        } catch (e: Throwable) {
            val errorMsg = "VLM init failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, errorMsg, e)
            CrashLogFile.logError(TAG, errorMsg, e)
            onError?.invoke(errorMsg)
            isInitialized = false
            false
        }
    }

    /**
     * Try to initialize the engine with a specific backend.
     * Returns true on success, false on failure.
     */
    private fun tryInitializeWithBackend(
        modelFile: File,
        backend: Backend,
        backendName: String
    ): Boolean {
        CrashLogFile.log(TAG, "Trying $backendName backend...")
        onStepProgress?.invoke(60, "Initializing $backendName backend...")

        var eng: Engine? = null
        try {
            val engineConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                visionBackend = backend,
                cacheDir = context.cacheDir.path
            )

            CrashLogFile.log(TAG, "EngineConfig created [$backendName] — creating Engine...")
            eng = Engine(engineConfig)

            CrashLogFile.log(TAG, "Engine created [$backendName] — calling initialize()...")
            eng.initialize()

            engine = eng
            activeBackend = backendName
            isInitialized = true
            return true

        } catch (e: Throwable) {
            CrashLogFile.logError(TAG, "$backendName init failed: ${e.javaClass.simpleName}: ${e.message}", e)
            Log.e(TAG, "$backendName init failed: ${e.message}", e)
            // Clean up partial engine
            try { eng?.close() } catch (_: Throwable) {}
            engine = null
            return false
        }
    }

    private fun buildModelNotFoundError(): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val downloadsFile = downloadsDir?.let { File(it, MODEL_FILE) }
        val externalDir = context.getExternalFilesDir(null)
        val externalFile = externalDir?.let { File(it, MODEL_FILE) }

        val sb = StringBuilder()
        sb.appendLine("$MODEL_FILE not found.")
        sb.appendLine()
        sb.appendLine("Locations checked:")

        if (downloadsFile != null) {
            sb.appendLine("  1. ${downloadsFile.absolutePath}")
            sb.appendLine("     exists=${downloadsFile.exists()}, size=${downloadsFile.length()} bytes")
        } else {
            sb.appendLine("  1. /storage/emulated/0/Download/$MODEL_FILE  (Downloads dir unavailable)")
        }

        if (externalFile != null) {
            sb.appendLine("  2. ${externalFile.absolutePath}")
            sb.appendLine("     exists=${externalFile.exists()}, size=${externalFile.length()} bytes")
        } else {
            sb.appendLine("  2. <app-scoped external dir>/$MODEL_FILE  (dir unavailable)")
        }

        sb.appendLine()
        sb.appendLine("Push via ADB:")
        sb.appendLine("  adb push $MODEL_FILE /storage/emulated/0/Download/")

        return sb.toString().trimEnd()
    }

    // ── Image Analysis (Main API) ─────────────────────────────────

    /**
     * Analyze a camera frame with a text prompt.
     *
     * Uses Gemma's exact turn system prompt format:
     * `<start_of_turn>user\n{prompt}<end_of_turn>\n<start_of_turn>model\n`
     * Image patch tokens are bound natively by the engine via Content.ImageBytes.
     *
     * @param bitmap  Camera frame — will be downscaled to max 448×448 before inference
     * @param prompt  Text prompt describing what to analyze
     * @return The complete model response, or null on error
     */
    suspend fun analyzeImage(bitmap: Bitmap, prompt: String): String? = withContext(Dispatchers.Default) {
        val eng = engine
        if (eng == null || !isInitialized) {
            Log.e(TAG, "VLM not initialized — cannot run inference")
            onError?.invoke("AI model not ready")
            return@withContext null
        }

        var scaledBitmap: Bitmap? = null
        try {
            val startTime = System.currentTimeMillis()
            CrashLogFile.log(TAG, "=== ANALYZE IMAGE ===")
            CrashLogFile.log(TAG, "Input bitmap: ${bitmap.width}x${bitmap.height}")

            // 1. Preprocess bitmap — downscale to max 448×448 to prevent OOM
            scaledBitmap = preprocessBitmap(bitmap)
            CrashLogFile.log(TAG, "Preprocessed: ${scaledBitmap.width}x${scaledBitmap.height}")

            // 2. Encode image to JPEG bytes
            val imageBytes = bitmapToJpegBytes(scaledBitmap)
            CrashLogFile.log(TAG, "JPEG: ${imageBytes.size} bytes")

            // 3. Build Gemma turn-formatted prompt (no literal IMAGE_TOKEN —
            //    the engine binds image patch tokens natively from Content.ImageBytes)
            //
            //    <start_of_turn>user
            //    {prompt}
            //    <end_of_turn>
            //    <start_of_turn>model
            val formattedPrompt = buildGemmaTurnPrompt(prompt)
            CrashLogFile.log(TAG, "Formatted prompt: ${formattedPrompt.take(120)}...")

            // 4. Create conversation with empty system instruction
            //    (the formatted prompt is sent as user content below)
            val conversationConfig = ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = TOP_K,
                    topP = TOP_P,
                    temperature = TEMPERATURE
                )
            )

            eng.createConversation(conversationConfig).use { conversation ->
                // 5. Build multimodal contents — formatted prompt text + clamped bitmap.
                //    The native engine binds image patch tokens from Content.ImageBytes
                //    directly; no literal [IMAGE_TOKEN] placeholder is needed or allowed.
                val contents = Contents.of(
                    Content.Text(formattedPrompt),
                    Content.ImageBytes(imageBytes)
                )

                // 6. Send via callback-based API (avoids SendChannel crash)
                val responseBuilder = StringBuilder()
                val latch = CountDownLatch(1)
                var inferenceError: String? = null

                val callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        try {
                            val text = message.toString()
                            if (text.isNotEmpty()) {
                                responseBuilder.append(text)
                                onTokenGenerated?.invoke(text)
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "onMessage error: ${e.message}")
                        }
                    }

                    override fun onDone() {
                        Log.d(TAG, "sendMessageAsync onDone")
                        latch.countDown()
                    }

                    override fun onError(throwable: Throwable) {
                        Log.e(TAG, "sendMessageAsync onError: ${throwable.message}", throwable)
                        inferenceError = throwable.message
                        latch.countDown()
                    }
                }

                conversation.sendMessageAsync(contents, callback)

                // 7. Wait for completion (max 180s — Gemma 3n E2B int4 at 3.66GB)
                val completed = latch.await(INFERENCE_TIMEOUT_SEC, TimeUnit.SECONDS)
                if (!completed) {
                    Log.w(TAG, "Inference timed out after ${INFERENCE_TIMEOUT_SEC}s")
                    inferenceError = "Inference timed out"
                }

                val fullResponse = responseBuilder.toString().trim()
                val elapsed = System.currentTimeMillis() - startTime

                if (inferenceError != null && fullResponse.isEmpty()) {
                    Log.e(TAG, "Inference failed: $inferenceError")
                    CrashLogFile.logError(TAG, "Inference failed: $inferenceError")
                    onError?.invoke("Inference error: $inferenceError")
                    return@use null
                }

                Log.i(TAG, "Inference complete: ${fullResponse.length} chars in ${elapsed}ms [backend=$activeBackend]")
                CrashLogFile.log(TAG, "Response: ${fullResponse.take(200)}...")

                onComplete?.invoke(fullResponse)
                CrashLogFile.exportToDownloads(context)

                fullResponse
            } ?: return@withContext null

        } catch (e: Throwable) {
            Log.e(TAG, "Inference failed: ${e.javaClass.simpleName}: ${e.message}", e)
            CrashLogFile.logError(TAG, "Inference failed", e)
            onError?.invoke("Inference error: ${e.message}")
            null
        } finally {
            // Explicitly recycle the scaled bitmap to free memory after inference
            try {
                if (scaledBitmap != null && !scaledBitmap.isRecycled) {
                    scaledBitmap.recycle()
                    CrashLogFile.log(TAG, "Scaled bitmap recycled")
                }
            } catch (_: Throwable) {}
        }
    }

    // ── Gemma Prompt Formatting ────────────────────────────────────

    /**
     * Build Gemma's exact turn system prompt format:
     *
     * ```
     * <start_of_turn>user
     * {prompt}
     * <end_of_turn>
     * <start_of_turn>model
     * ```
     *
     * Image patch tokens are bound natively by the LiteRT-LM engine when
     * Content.ImageBytes is included in the same Contents — no literal
     * `[IMAGE_TOKEN]` string is inserted into the text payload.
     */
    private fun buildGemmaTurnPrompt(prompt: String): String {
        return "<start_of_turn>user\n" +
            "$prompt\n" +
            "<end_of_turn>\n" +
            "<start_of_turn>model\n"
    }

    // ── Bitmap Preprocessing ──────────────────────────────────────

    /**
     * Preprocess a camera bitmap for VLM input.
     * - Downscales to a maximum dimension of 448×448 pixels to prevent peak RAM spikes and OOM
     * - Returns a NEW bitmap (caller must recycle)
     */
    private fun preprocessBitmap(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height

        // Calculate scale factor — enforce 448×448 max
        val maxDim = maxOf(w, h)
        val scale = if (maxDim > MAX_INPUT_DIMENSION) {
            MAX_INPUT_DIMENSION.toFloat() / maxDim
        } else {
            1.0f
        }

        return if (scale < 1.0f) {
            val newW = (w * scale).toInt()
            val newH = (h * scale).toInt()
            Bitmap.createScaledBitmap(source, newW, newH, true)
        } else {
            // Already small enough — return a copy so we own the memory
            source.copy(source.config, false)
        }
    }

    /**
     * Convert a Bitmap to JPEG bytes for the LiteRT-LM API.
     */
    private fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }

    // ── Engine State ──────────────────────────────────────────────

    fun isReady(): Boolean = isInitialized && engine != null
    fun getActiveBackend(): String = activeBackend

    // ── Model Resolution ──────────────────────────────────────────

    /**
     * Resolve model file from the device filesystem.
     *
     * Lookup order:
     *  1. Public Download folder — `/storage/emulated/0/Download/gemma-3n-E2B-it-int4.litertlm`
     *  2. App-scoped external files — `context.getExternalFilesDir(null)`
     */
    private fun resolveModelFile(): File? {
        // Tier 1: Public Download folder (most accessible via ADB push)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir != null && downloadsDir.exists()) {
            val downloadsFile = File(downloadsDir, MODEL_FILE)
            Log.d(TAG, "Tier 1: Checking ${downloadsFile.absolutePath}")
            if (downloadsFile.exists() && downloadsFile.length() > MIN_MODEL_SIZE) {
                Log.i(TAG, "Tier 1: Model found at ${downloadsFile.absolutePath} (${downloadsFile.length()} bytes)")
                return downloadsFile
            }
            Log.d(TAG, "Tier 1: Not found (exists=${downloadsFile.exists()}, size=${downloadsFile.length()})")
        } else {
            Log.d(TAG, "Tier 1: Downloads directory unavailable or does not exist")
        }

        // Tier 2: App-scoped external files — no special permissions required
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val externalFile = File(externalDir, MODEL_FILE)
            Log.d(TAG, "Tier 2: Checking ${externalFile.absolutePath}")
            if (externalFile.exists() && externalFile.length() > MIN_MODEL_SIZE) {
                Log.i(TAG, "Tier 2: Model found at ${externalFile.absolutePath} (${externalFile.length()} bytes)")
                return externalFile
            }
            Log.d(TAG, "Tier 2: Not found (exists=${externalFile.exists()}, size=${externalFile.length()})")
        } else {
            Log.d(TAG, "Tier 2: getExternalFilesDir(null) returned null")
        }

        Log.w(TAG, "Model not found in any location")
        return null
    }

    // ── Cleanup ────────────────────────────────────────────────────

    override fun close() {
        try {
            engine?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing VLM engine: ${e.message}")
        }
        engine = null
        isInitialized = false
        activeBackend = "NONE"
    }

    // ── Companion ──────────────────────────────────────────────────

    companion object {
        private const val TAG = "VlmEngineManager"

        // Model configuration — Gemma 3n E2B int4 quantized (3.66 GB)
        const val MODEL_FILE = "gemma-3n-E2B-it-int4.litertlm"
        const val MIN_MODEL_SIZE = 500L * 1024 * 1024  // 500MB minimum sanity check

        // Image preprocessing — 448×448 max to prevent RAM spikes and OOM
        private const val MAX_INPUT_DIMENSION = 448
        const val JPEG_QUALITY = 90

        // Sampling parameters
        const val TEMPERATURE = 0.4
        const val TOP_K = 40
        const val TOP_P = 0.9

        // Inference — 3 min timeout for large 3.66GB int4 model
        private const val INFERENCE_TIMEOUT_SEC = 180L

        private var nativeLibLoaded = false

        /**
         * Explicitly load the litertlm native library before Engine creation.
         */
        @Synchronized
        fun ensureNativeLibLoaded() {
            if (nativeLibLoaded) return
            try {
                System.loadLibrary("litertlm_jni")
                nativeLibLoaded = true
                Log.i(TAG, "Native library (litertlm_jni) loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                try {
                    System.loadLibrary("litertlm")
                    nativeLibLoaded = true
                    Log.i(TAG, "Native library (litertlm) loaded successfully")
                } catch (e2: UnsatisfiedLinkError) {
                    Log.w(TAG, "Native library auto-load failed: ${e2.message}. " +
                        "Will rely on AAR bundled loading.")
                    nativeLibLoaded = true
                }
            }
        }
    }
}
