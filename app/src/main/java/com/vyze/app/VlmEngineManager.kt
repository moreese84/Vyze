package com.vyze.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vyze.app.memory.MemoryRepository
import com.vyze.app.memory.SimilarInteraction
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device VLM engine wrapper using Google's LiteRT-LM framework.
 *
 * ## Model
 * Gemma 4 E2B (Edge 2 Billion) — multimodal vision-language model.
 * File: gemma-4-E2B-it.litertlm (2.59 GB) — generic variant with vision encoder
 *
 * ## Hardware Acceleration
 * GPU-only execution (OpenCL/Vulkan). CPU inference is not supported for this model size.
 *
 * ## Prompt Format
 * Uses Gemma 4's turn format:
 * `<|turn|>system [System Prompt]<|end_of_turn|><|turn|>user [User/Image Context]<|end_of_turn|><|turn|>model`
 * Image patch tokens are bound natively by the LiteRT-LM engine when
 * passing the Bitmap — no literal [IMAGE_TOKEN] placeholder needed.
 *
 * ## Memory Management
 * Incoming Bitmap frames are downscaled proportionally when exceeding target dimension.
 * Gemma 4 handles dynamic aspect ratios natively — no rigid center-cropping applied.
 * All scaled bitmaps are explicitly recycled after inference to prevent memory leaks.
 *
 * ## GPU Warm-up
 * A dummy 1×1 image is run through the engine immediately after initialization
 * to pre-compile OpenCL/Vulkan GPU kernels and minimize first-inference latency.
 *
 * ## API
 * Use [analyzeImage] to send a camera frame + text prompt and get a response.
 * Uses the callback-based MessageCallback to avoid SendChannel crashes.
 */
class VlmEngineManager(
    private val context: Context,
    private val memoryRepository: MemoryRepository? = null
) : Closeable {

    private var engine: Engine? = null

    @Volatile
    private var activeBackend: String = "NONE"

    @Volatile
    private var isInitialized = false

    /** Whether this device is classified as low-RAM by Android. */
    private val isLowRamDevice: Boolean by lazy {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.isLowRamDevice == true
        } catch (e: Throwable) { false }
    }

    /**
     * Available device-level memory in MB.
     * Uses ActivityManager.MemoryInfo which reports total available RAM
     * (including native memory where LiteRT-LM loads), not just Java heap.
     */
    private fun availableHeapMB(): Long {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                return memInfo.availMem / (1024 * 1024)
            }
        } catch (_: Throwable) {}
        // Fallback: Java heap only (less accurate)
        val runtime = Runtime.getRuntime()
        return (runtime.freeMemory() + (runtime.maxMemory() - runtime.totalMemory())) / (1024 * 1024)
    }

    // Callbacks for UI updates
    var onTokenGenerated: ((String, String) -> Unit)? = null  // (token, sessionId)
    var onComplete: ((String, String) -> Unit)? = null         // (response, sessionId)
    var onError: ((String, String) -> Unit)? = null            // (error, sessionId)
    var onModelCopyProgress: ((copied: Long, total: Long) -> Unit)? = null
    var onStepProgress: ((Int, String) -> Unit)? = null

    /**
     * Latch for the currently active inference. Promoted to instance field
     * so [interrupt] can signal it without destroying the engine.
     *
     * Set before [CountDownLatch.await] in [analyzeImage], cleared after
     * the await returns. Only one inference runs at a time (blocking
     * CountDownLatch), so a single reference is safe.
     */
    @Volatile
    private var activeLatch: CountDownLatch? = null

    /**
     * Set to true by [interrupt] to signal that the current inference was
     * cancelled externally. Checked after latch.await() to decide whether
     * to close the Conversation and fire callbacks.
     */
    @Volatile
    private var wasInterrupted = false

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
     * Initialize the Gemma 4 E2B engine.
     * NPU → GPU fallback chain for optimal hardware acceleration.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true

        CrashLogFile.log(TAG, "=== INIT START (Gemma 4 E2B) ===")

        // ── Pre-flight RAM Check ──────────────────────────────────
        // Gemma 4 E2B requires ~3 GB of RAM at peak (model weights
        // + KV-cache + image encoding). Reject early on constrained devices
        // with a clear error instead of crashing mid-inference with OOM.
        // Trigger GC first to reclaim idle memory before measuring.
        System.gc()
        Thread.sleep(100)
        val freeMB = availableHeapMB()
        val requiredMB = if (isLowRamDevice) MIN_RAM_LOW_RAM_DEVICE_MB else MIN_RAM_STANDARD_MB
        if (freeMB < requiredMB) {
            val errorMsg = "Insufficient RAM: ${freeMB}MB free, need ${requiredMB}MB. " +
                "Gemma 4 E2B requires a device with at least ${if (isLowRamDevice) "3" else "4"}GB RAM."
            Log.e(TAG, errorMsg)
            CrashLogFile.logError(TAG, errorMsg)
            onError?.invoke(errorMsg, "")
            return@withContext false
        }
        Log.i(TAG, "RAM check passed: ${freeMB}MB free (lowRam=$isLowRamDevice, required=${requiredMB}MB)")
        CrashLogFile.log(TAG, "RAM: ${freeMB}MB free, lowRam=$isLowRamDevice")

        try {
            Log.i(TAG, "Starting Gemma 4 E2B initialization...")

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
                onError?.invoke(errorMsg, "")
                return@withContext false
            }

            Log.i(TAG, "Model resolved: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            CrashLogFile.log(TAG, "Model: ${modelFile.absolutePath} (${modelFile.length() / (1024 * 1024)}MB)")

            // Step 2: GPU backend (primary)
            // NPU is skipped — the Gemma 4 E2B generic model does not ship with
            // TF_LITE_PREFILL_DECODE for NPU, so NPU init always fails and
            // wastes 5-10s on mid-tier Dimensity/Snapdragon devices.
            onStepProgress?.invoke(30, "Loading model weights into GPU...")
            CrashLogFile.log(TAG, "Step 2: Initializing GPU backend...")

            val gpuSuccess = tryInitializeWithBackend(modelFile, Backend.GPU(), "GPU")
            if (gpuSuccess) {
                // GPU warm-up — pre-compile OpenCL/Vulkan kernels
                onStepProgress?.invoke(75, "Warming up GPU kernels (this takes a moment)...")
                CrashLogFile.log(TAG, "Step 3: GPU warm-up (dummy inference)...")
                warmUp()

                onStepProgress?.invoke(95, "Finalizing...")
                CrashLogFile.log(TAG, "=== INIT SUCCESS [GPU] ===")
                Log.i(TAG, "Gemma 4 E2B loaded successfully [backend=GPU]")
                return@withContext true
            }

            // GPU failed — no CPU fallback for this model size
            val errorMsg = "VLM init failed: GPU backend unavailable. " +
                "CPU fallback is disabled for Gemma 4 E2B model."
            Log.e(TAG, errorMsg)
            CrashLogFile.logError(TAG, errorMsg)
            onError?.invoke(errorMsg, "")
            false

        } catch (e: Throwable) {
            val errorMsg = "VLM init failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, errorMsg, e)
            CrashLogFile.logError(TAG, errorMsg, e)
            onError?.invoke(errorMsg, "")
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
        onStepProgress?.invoke(35, "Creating $backendName engine instance...")

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

            onStepProgress?.invoke(45, "Loading ${modelFile.length() / (1024 * 1024)}MB model into ${backendName}...")
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
     * Uses Gemma 4's turn format:
     * `<|turn|>system [System Prompt]<|end_of_turn|><|turn|>user [User/Image Context]<|end_of_turn|><|turn|>model`
     * Image patch tokens are bound natively by the engine via Content.ImageBytes.
     *
     * @param bitmap          Camera frame — will be downscaled proportionally before inference
     * @param prompt          User query describing what to analyze
     * @param memoryContext   Optional context string injected below the baseline instruction
     * @param targetDimension Target bitmap dimension (scales proportionally, no center-crop)
     * @return The complete model response, or null on error
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        prompt: String,
        memoryContext: String? = null,
        similarInteractions: List<SimilarInteraction> = emptyList(),
        sessionId: String = "",
        targetDimension: Int = MAX_INPUT_DIMENSION,
        maxTokens: Int = MAX_TOKENS
    ): String? = withContext(Dispatchers.Default) {
        val eng = engine
        if (eng == null || !isInitialized) {
            Log.e(TAG, "VLM not initialized — cannot run inference")
            onError?.invoke("AI model not ready", sessionId)
            return@withContext null
        }

        var scaledBitmap: Bitmap? = null
        try {
            val startTime = System.currentTimeMillis()
            CrashLogFile.log(TAG, "=== ANALYZE IMAGE ===")
            CrashLogFile.log(TAG, "Input bitmap: ${bitmap.width}x${bitmap.height}")

            // 1. Preprocess bitmap — center-crop to square + scale to targetDimension
            scaledBitmap = preprocessBitmap(bitmap, targetDimension)
            CrashLogFile.log(TAG, "Preprocessed: ${scaledBitmap.width}x${scaledBitmap.height} (target=$targetDimension)")

            // 2. Encode image to JPEG bytes
            val imageBytes = bitmapToJpegBytes(scaledBitmap)
            CrashLogFile.log(TAG, "JPEG: ${imageBytes.size} bytes")

            // 3. Build the user payload + Gemma 4 turn format.
            //    System directive is injected as <|turn|>system block.
            //    User rules come from DynamicPromptBuilder inside the user payload.
            val userPayload = buildUserPayload(prompt, memoryContext)
            val formattedPrompt = buildGemmaTurnPrompt(userPayload, SYSTEM_DIRECTIVE)
            CrashLogFile.log(TAG, "Formatted prompt: ${formattedPrompt.take(120)}...")

            // 4. Create conversation with empty system instruction
            //    (the formatted prompt is sent as user content below)
            val conversationConfig = ConversationConfig(
                maxOutputToken = maxTokens,
                samplerConfig = SamplerConfig(
                    topK = TOP_K,
                    topP = TOP_P,
                    temperature = TEMPERATURE
                )
            )

            // 5. Create conversation — managed manually (NOT via use{}) so we can
            //    prevent Conversation.close() when interrupted. Closing while the
            //    native thread is still running causes SIGSEGV in liblitertlm_jni.so.
            val conversation = eng.createConversation(conversationConfig)
            wasInterrupted = false  // Reset flag before starting new inference

            try {
                // 6. Build multimodal contents — formatted prompt text + clamped bitmap.
                //    The native engine binds image patch tokens from Content.ImageBytes
                //    directly; no literal [IMAGE_TOKEN] placeholder is needed or allowed.
                val contents = Contents.of(
                    Content.Text(formattedPrompt),
                    Content.ImageBytes(imageBytes)
                )

                // 7. Send via callback-based API (avoids SendChannel crash)
                val responseBuilder = StringBuilder()
                val latch = CountDownLatch(1)
                activeLatch = latch  // Expose to interrupt() for cancellation
                var inferenceError: String? = null

                val callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        try {
                            val text = message.toString()
                            if (text.isNotEmpty()) {
                                responseBuilder.append(text)
                                onTokenGenerated?.invoke(text, sessionId)
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

                // 8. Wait for completion (max 180s — Gemma 3n E2B int4 at 3.66GB)
                //    On low-RAM devices, use a shorter timeout to fail fast instead of
                //    hanging during an OOM recovery that may never complete.
                val timeoutSec = if (isLowRamDevice) LOW_RAM_INFERENCE_TIMEOUT_SEC else INFERENCE_TIMEOUT_SEC
                val completed = latch.await(timeoutSec, TimeUnit.SECONDS)

                // ── Memory pressure check ──────────────────────────
                // If available heap drops below threshold during inference,
                // log a warning. The watchdog timer in VyzeCoreController
                // handles the force-reset if the engine hangs due to OOM.
                val remainingMB = availableHeapMB()
                if (remainingMB < LOW_RAM_THRESHOLD_MB) {
                    Log.w(TAG, "Memory pressure during inference: ${remainingMB}MB free — may OOM")
                    CrashLogFile.log(TAG, "LOW MEMORY WARNING: ${remainingMB}MB free during inference")
                }
                activeLatch = null  // Clear — interrupt() can no longer signal this inference

                // Check if interrupt() released the latch externally. If so, the native
                // thread may still be running — do NOT close the Conversation (causes
                // SIGSEGV) and do NOT fire callbacks (stale session).
                if (wasInterrupted) {
                    Log.i(TAG, "Inference interrupted externally — discarding stale result")
                    return@withContext null
                }

                if (!completed) {
                    Log.w(TAG, "Inference timed out after ${INFERENCE_TIMEOUT_SEC}s")
                    inferenceError = "Inference timed out"
                }

                val fullResponse = responseBuilder.toString().trim()
                val elapsed = System.currentTimeMillis() - startTime

                if (inferenceError != null && fullResponse.isEmpty()) {
                    Log.e(TAG, "Inference failed: $inferenceError")
                    CrashLogFile.logError(TAG, "Inference failed: $inferenceError")
                    onError?.invoke("Inference error: $inferenceError", sessionId)
                    return@withContext null
                }

                Log.i(TAG, "Inference complete: ${fullResponse.length} chars in ${elapsed}ms [backend=$activeBackend]")
                CrashLogFile.log(TAG, "Response: ${fullResponse.take(200)}...")

                onComplete?.invoke(fullResponse, sessionId)
                CrashLogFile.exportToDownloads(context)

                fullResponse
            } finally {
                // CRITICAL: Only close the Conversation if it was NOT interrupted.
                // When interrupted, the native thread is still running inside
                // sendMessageAsync → onDone. Closing the Conversation here frees
                // the JNI pointer while the native thread references it → SIGSEGV.
                // The Conversation will be garbage-collected when the native thread
                // finishes and releases its reference.
                if (!wasInterrupted) {
                    try {
                        conversation.close()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Conversation close error: ${e.message}")
                    }
                } else {
                    Log.d(TAG, "Skipping conversation.close() — native thread still running")
                }
            }

        } catch (e: Throwable) {
            Log.e(TAG, "Inference failed: ${e.javaClass.simpleName}: ${e.message}", e)
            CrashLogFile.logError(TAG, "Inference failed", e)
            onError?.invoke("Inference error: ${e.message}", sessionId)
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

    // ── Gemma 4 Prompt Formatting ─────────────────────────────────

    /**
     * Build the prompt using the SDK-compatible turn format.
     * The system directive is prepended to the user content.
     * The turn markers use <start_of_turn>/<end_of_turn> which the
     * LiteRT-LM SDK v0.16.1 parses correctly.
     *
     * Image patch tokens are bound natively by the LiteRT-LM engine when
     * Content.ImageBytes is included in the same Contents — no literal
     * `[IMAGE_TOKEN]` string is inserted into the text payload.
     */
    private fun buildGemmaTurnPrompt(
        userContent: String,
        systemPrompt: String = ""
    ): String {
        val fullContent = if (systemPrompt.isNotBlank()) {
            "$systemPrompt\n\n$userContent"
        } else {
            userContent
        }
        return "<start_of_turn>user\n" +
            "$fullContent\n" +
            "<end_of_turn>\n" +
            "<start_of_turn>model\n"
    }

    // ── Prompt Assembly ───────────────────────────────────────────

    /**
     * Build the user payload from the query + optional memory context.
     * The system prompt is injected separately via buildGemmaTurnPrompt().
     */
    private fun buildUserPayload(query: String, memoryContext: String?): String {
        val sb = StringBuilder()
        sb.append(query)

        if (!memoryContext.isNullOrBlank()) {
            sb.appendLine()
            sb.append("Context: $memoryContext")
        }

        return sb.toString().trimEnd()
    }

    // ── Adaptive Intelligence Context ─────────────────────────────
    // Similar interactions are injected via DynamicPromptBuilder only.
    // No duplicate injection path here to prevent prompt token bloat.

    // ── Bitmap Preprocessing ──────────────────────────────────────

    /**
     * Preprocess a camera bitmap for VLM input.
     *
     * Gemma 4 handles dynamic aspect ratios natively via its vision token
     * budget. We only downscale if the bitmap exceeds the target dimension,
     * preserving the original aspect ratio — no center-cropping.
     *
     * @param source         Raw camera bitmap
     * @param targetDimension Maximum dimension (width or height) allowed
     * @return A NEW bitmap (possibly smaller, same aspect ratio). Caller must recycle.
     */
    private fun preprocessBitmap(source: Bitmap, targetDimension: Int = MAX_INPUT_DIMENSION): Bitmap {
        val w = source.width
        val h = source.height
        val maxDim = maxOf(w, h)

        // Only downscale if the bitmap exceeds target — preserve original aspect ratio
        return if (maxDim > targetDimension) {
            val scale = targetDimension.toFloat() / maxDim
            val newW = (w * scale).toInt().coerceAtLeast(1)
            val newH = (h * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, newW, newH, true)
        } else {
            // Bitmap is already within target — return as-is (no copy needed)
            source
        }
    }

    /**
     * Convert a Bitmap to JPEG bytes for the LiteRT-LM API.
     * Pre-allocates 8KB buffer to avoid array copy re-allocations.
     */
    private fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream(8192)
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }

    // ── GPU Warm-up ─────────────────────────────────────────────

    /**
     * Run a dummy 1×1 image through the engine to pre-compile OpenCL/Vulkan GPU
     * kernels. This eliminates the first-inference cold-start penalty so the real
     * user query benefits from already-warmed GPU delegates.
     */
    private suspend fun warmUp() = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext
        try {
            // TEXT-ONLY warm-up — do NOT send images here.
            // Sending Content.ImageBytes before the vision encoder is fully
            // initialized causes a SIGSEGV (null pointer in liblitertlm_jni.so).
            // The text decoder and GPU kernels are warmed up by this text-only
            // message; the vision encoder warms up on the first real inference.
            val conversationConfig = ConversationConfig(
                maxOutputToken = MAX_TOKENS,
                samplerConfig = SamplerConfig(
                    topK = TOP_K,
                    topP = TOP_P,
                    temperature = TEMPERATURE
                )
            )

            eng.createConversation(conversationConfig).use { conversation ->
                val contents = Contents.of(
                    Content.Text("hello")
                )
                val latch = CountDownLatch(1)
                conversation.sendMessageAsync(contents, object : MessageCallback {
                    override fun onMessage(message: Message) { /* no-op */ }
                    override fun onDone() { latch.countDown() }
                    override fun onError(throwable: Throwable) {
                        Log.w(TAG, "warmUp onError: ${throwable.message}")
                        latch.countDown()
                    }
                })
                latch.await(WARMUP_TIMEOUT_SEC, TimeUnit.SECONDS)
            }

            Log.i(TAG, "GPU warm-up completed (text-only)")
        } catch (e: Throwable) {
            Log.w(TAG, "GPU warm-up failed (non-fatal): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── Engine State ──────────────────────────────────────────────

    fun isReady(): Boolean = isInitialized && engine != null
    fun getActiveBackend(): String = activeBackend

    /**
     * Check if the model file exists on disk (without initializing the engine).
     * Used to avoid announcing 'downloading' when the model is already present.
     */
    fun isModelOnDisk(): Boolean {
        return resolveModelFile() != null
    }

    // ── Native Interruption ──────────────────────────────────────

    /**
     * Immediately release the blocking [CountDownLatch.await] in [analyzeImage]
     * so the calling coroutine resumes without waiting for the full inference.
     *
     * **Engine stays alive:** Unlike [resetSession], this does NOT close the
     * underlying Engine. The next [analyzeImage] call executes immediately
     * without model reload latency.
     *
     * **Stale response safety:** The caller (VyzeCoreController) gates callbacks
     * via sessionId == activeSessionId — any tokens or onComplete from the
     * interrupted inference are discarded by the caller.
     */
    fun interrupt() {
        val latch = activeLatch
        if (latch != null) {
            Log.i(TAG, "interrupt: releasing active inference latch")
            wasInterrupted = true   // Signal analyzeImage to skip close + callbacks
            latch.countDown()       // Unblocks await() — coroutine resumes immediately
            activeLatch = null
        } else {
            Log.d(TAG, "interrupt: no active latch (no inference running)")
        }
    }

    // ── Session Reset ────────────────────────────────────────────

    /**
     * Lightweight session reset — clears the Engine's native KV-cache by
     * closing and reinitializing the engine. This purges any stale attention
     * embeddings that persist across inferences within the same Engine instance.
     *
     * Cost: Model reload (~2-4s on GPU). Only call on lifecycle transitions
     * (e.g., app returning from background) — NOT on every inference.
     *
     * Each [analyzeImage] call already creates a fresh Conversation via
     * [Engine.createConversation], so conversation-level history is already
     * isolated. This method addresses Engine-level KV-cache accumulation.
     */
    fun resetSession() {
        if (!isInitialized || engine == null) return
        Log.i(TAG, "resetSession: clearing Engine KV-cache via reinit")
        try {
            engine?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "resetSession close error: ${e.message}")
        }
        engine = null
        isInitialized = false
        // Reinitialize on background thread — uses the SAME NPU → GPU fallback
        // chain as initialize() so the optimal backend is always selected.
        val eng = scope.launch(Dispatchers.IO) {
            try {
                val modelFile = resolveModelFile()
                if (modelFile != null) {
                    // GPU only — NPU is skipped (see initialize() rationale)
                    val gpuOk = tryInitializeWithBackend(modelFile, Backend.GPU(), "GPU")
                    if (gpuOk) {
                        Log.i(TAG, "resetSession: reinitialized on GPU")
                        warmUp()
                        return@launch
                    }
                    Log.e(TAG, "resetSession: GPU init failed")
                    engine = null
                    isInitialized = false
                } else {
                    Log.e(TAG, "resetSession: model file not found")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "resetSession reinit failed: ${e.message}", e)
                engine = null
                isInitialized = false
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Model Resolution ──────────────────────────────────────────

    /**
     * Resolve model file from the device filesystem.
     *
     * Lookup order:
     *  1. Public Download folder — `/storage/emulated/0/Download/gemma-4-E2B-it.litertlm`
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

        // Model configuration — Gemma 4 E2B (2.59 GB) — generic multimodal with vision encoder
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        const val MIN_MODEL_SIZE = 500L * 1024 * 1024  // 500MB minimum sanity check

        // Image preprocessing — no center-crop, just proportional downscale
        // Gemma 4 handles dynamic aspect ratios natively
        private const val MAX_INPUT_DIMENSION = 512
        const val JPEG_QUALITY = 75

        // Greedy decoding — fast, concise output with minimal latency
        const val TEMPERATURE = 0.1
        const val TOP_K = 1
        const val TOP_P = 1.0
        const val MAX_TOKENS = 35

        // Timeouts
        private const val INFERENCE_TIMEOUT_SEC = 180L  // 3 min for real inference
        private const val LOW_RAM_INFERENCE_TIMEOUT_SEC = 60L  // 1 min on low-RAM devices — fail fast
        private const val WARMUP_TIMEOUT_SEC = 60L       // 60s for GPU warm-up (mid-tier may need longer)

        /**
         * Gemma 4 system directive — injected as <|turn|>system block.
         * Prevents internal English reasoning chains and enforces language mirroring.
         */
        private const val SYSTEM_DIRECTIVE =
            "You are a fast, concise visual assistant. Describe scene layouts and spatial objects " +
            "directly in the language requested by the user without cross-translating or outputting " +
            "internal reasoning chains. Respond only in the requested language."

        // ── Mid-Tier / Low-RAM Thresholds ───────────────────────
        /** Minimum free device RAM (MB) required to attempt model init on standard devices. */
        private const val MIN_RAM_STANDARD_MB = 1200L
        /** Minimum free device RAM (MB) required on devices flagged as low-RAM. */
        private const val MIN_RAM_LOW_RAM_DEVICE_MB = 800L
        /** Log a warning if free device RAM drops below this during inference. */
        private const val LOW_RAM_THRESHOLD_MB = 500L

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
