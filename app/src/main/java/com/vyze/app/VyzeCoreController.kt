package com.vyze.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vyze.app.data.MemoryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central controller for the Vyze VLM accessibility pipeline.
 *
 * ## Memory Management
 * Camera snapshots are downscaled to max 1024px before inference to prevent OOM.
 * Bitmaps are recycled immediately after use.
 *
 * ## TTS Strategy
 * Speaks the COMPLETE response after inference finishes (not per-sentence streaming).
 * This avoids threading issues with rapid-fire TTS calls during token streaming.
 */
class VyzeCoreController(
    private val context: Context,
    private val ttsManager: TTSManager,
    private val memoryDao: MemoryDao
) {

    private val TAG = "VyzeCoreController"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val vlmEngine = VlmEngineManager(context)
    private val promptBuilder = DynamicPromptBuilder(memoryDao)

    /** Gate to prevent overlapping inferences. */
    private val isInferring = AtomicBoolean(false)

    /** Whether the VLM engine has been initialized. */
    @Volatile
    private var engineReady = false

    /** Callback for diagnostic/status updates to the UI. */
    var onStatusUpdate: ((String) -> Unit)? = null

    /** Callback for progress updates: (percent 0-100, step description). */
    var onProgressUpdate: ((Int, String) -> Unit)? = null

    /** Callback for fatal errors during initialization. */
    var onError: ((String) -> Unit)? = null

    /** Callback for inference completion with full response. */
    var onInferenceComplete: ((String) -> Unit)? = null

    // ── Initialization ─────────────────────────────────────────────

    fun initialize() {
        vlmEngine.onModelCopyProgress = { copied, total ->
            val mbCopied = copied / (1024 * 1024)
            val mbTotal = total / (1024 * 1024)
            val percent = if (total > 0) (20 + (copied * 40 / total)).toInt().coerceIn(20, 60) else 20
            val msg = "Copying model... ${mbCopied}MB / ${mbTotal}MB"
            Log.d(TAG, msg)
            mainHandler.post {
                onProgressUpdate?.invoke(percent, msg)
                onStatusUpdate?.invoke(msg)
            }
        }

        vlmEngine.onStepProgress = { percent, step ->
            mainHandler.post { onProgressUpdate?.invoke(percent, step) }
        }

        vlmEngine.onError = { error ->
            isInferring.set(false)
            mainHandler.post {
                try { ttsManager.speakImmediate("Sorry, $error") } catch (_: Exception) {}
                onStatusUpdate?.invoke("Error: $error")
                onError?.invoke(error)
            }
        }

        // Collect tokens into buffer — do NOT call TTS per-token.
        // Speaking is done once at the end with the complete response.
        vlmEngine.onTokenGenerated = { token ->
            try {
                pendingResponse.append(token)
                // Update UI overlay with live token count (non-intrusive)
                val count = pendingResponse.length
                if (count % 50 == 0) {
                    mainHandler.post {
                        onStatusUpdate?.invoke("Generating... ($count chars)")
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "onTokenGenerated error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        vlmEngine.onComplete = { fullResponse ->
            try {
                CrashLogFile.log(TAG, "onComplete fired: ${fullResponse.length} chars")
                pendingResponse.clear()

                // Speak the COMPLETE response in one go
                if (fullResponse.isNotBlank()) {
                    CrashLogFile.log(TAG, "Posting TTS speak (${fullResponse.length} chars)")
                    mainHandler.post {
                        try {
                            CrashLogFile.log(TAG, "Calling TTS speakImmediate")
                            ttsManager.speakImmediate(fullResponse)
                            CrashLogFile.log(TAG, "TTS speakImmediate returned")
                        } catch (e: Throwable) {
                            CrashLogFile.logError(TAG, "TTS speak error: ${e.javaClass.simpleName}: ${e.message}", e)
                        }
                    }
                }

                // Store observation in memory (best-effort)
                scope.launch {
                    try {
                        promptBuilder.storeEnvironmentObservation(fullResponse)
                        CrashLogFile.log(TAG, "Memory store OK")
                    } catch (e: Throwable) {
                        CrashLogFile.logError(TAG, "Memory store failed: ${e.javaClass.simpleName}: ${e.message}", e)
                    }
                }

                isInferring.set(false)
                CrashLogFile.log(TAG, "isInferring set to false")
                mainHandler.post {
                    try {
                        onInferenceComplete?.invoke(fullResponse)
                        onStatusUpdate?.invoke("Ready")
                        CrashLogFile.log(TAG, "onComplete UI callbacks done")
                    } catch (e: Throwable) {
                        CrashLogFile.logError(TAG, "onComplete callback error: ${e.javaClass.simpleName}: ${e.message}", e)
                    }
                }
            } catch (e: Throwable) {
                CrashLogFile.logError(TAG, "onComplete error: ${e.javaClass.simpleName}: ${e.message}", e)
                isInferring.set(false)
            }
        }

        // Launch init on background thread
        scope.launch {
            try {
                onStatusUpdate("Loading VLM model...")
                engineReady = vlmEngine.initialize()

                if (engineReady) {
                    Log.i(TAG, "VLM ready [${vlmEngine.getActiveBackend()}]")
                    onStatusUpdate("VLM ready [${vlmEngine.getActiveBackend()}]")
                } else {
                    Log.e(TAG, "VLM engine failed to initialize")
                }
            } catch (e: Throwable) {
                val errorMsg = "VLM init crashed: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, errorMsg, e)
                engineReady = false
                mainHandler.post {
                    try { ttsManager.speakImmediate("AI model failed to load.") } catch (_: Exception) {}
                    onStatusUpdate?.invoke("Error: $errorMsg")
                }
            }
        }
    }

    private fun onStatusUpdate(msg: String) {
        mainHandler.post { onStatusUpdate?.invoke(msg) }
    }

    // ── Snapshot Trigger ───────────────────────────────────────────

    fun triggerSnapshot(bitmap: Bitmap, query: String? = null) {
        if (!engineReady) {
            Log.w(TAG, "triggerSnapshot called but engine not ready")
            return
        }

        if (!isInferring.compareAndSet(false, true)) {
            Log.d(TAG, "Inference already in progress — ignoring")
            return
        }

        onStatusUpdate("Analyzing snapshot...")

        scope.launch {
            try {
                CrashLogFile.log(TAG, "=== TRIGGER SNAPSHOT ===")
                CrashLogFile.log(TAG, "Bitmap: ${bitmap.width}x${bitmap.height}")

                // 1. Build prompt
                CrashLogFile.log(TAG, "Building prompt...")
                val prompt = promptBuilder.buildPrompt(
                    snapshotDescription = query ?: "User triggered a camera snapshot.",
                    queryOverride = query
                )
                CrashLogFile.log(TAG, "Prompt built: ${prompt.length} chars")

                // 2. Run VLM inference (VlmEngineManager handles bitmap preprocessing)
                pendingResponse.clear()
                CrashLogFile.log(TAG, "Calling vlmEngine.analyzeImage()...")

                val response = vlmEngine.analyzeImage(bitmap, prompt)
                CrashLogFile.log(TAG, "analyzeImage() returned: ${response?.length ?: 0} chars")

                if (response == null) {
                    isInferring.set(false)
                    mainHandler.post {
                        try { ttsManager.speakImmediate("Sorry, I couldn't analyze the image.") } catch (_: Throwable) {}
                        onStatusUpdate?.invoke("Inference failed")
                    }
                }

            } catch (e: Throwable) {
                // Catch Throwable (not Exception) to handle OOM, UnsatisfiedLinkError, etc.
                CrashLogFile.logError(TAG, "Snapshot trigger FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                isInferring.set(false)
                mainHandler.post {
                    try { ttsManager.speakImmediate("Something went wrong. Please try again.") } catch (_: Throwable) {}
                    onStatusUpdate?.invoke("Error: ${e.message}")
                }
            } finally {
                CrashLogFile.log(TAG, "finally block — recycling bitmap")
                // Recycle the original bitmap (VlmEngineManager handles its own scaled copy)
                try {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                        CrashLogFile.log(TAG, "Bitmap recycled")
                    }
                } catch (e: Throwable) {
                    CrashLogFile.logError(TAG, "Bitmap recycle failed: ${e.message}")
                }
                CrashLogFile.log(TAG, "Calling System.gc()")
                System.gc()
                CrashLogFile.log(TAG, "=== TRIGGER SNAPSHOT DONE ===")
            }
        }
    }

    fun triggerWithQuery(bitmap: Bitmap, query: String) {
        triggerSnapshot(bitmap, query)
    }

    // ── State ──────────────────────────────────────────────────────

    private val pendingResponse = StringBuilder()

    fun setPreference(key: String, value: String) {
        scope.launch { promptBuilder.setPreference(key, value) }
    }

    fun getPromptBuilder(): DynamicPromptBuilder = promptBuilder
    fun getStorageSettingsIntent(): android.content.Intent? = vlmEngine.buildStorageSettingsIntent()
    fun isEngineReady(): Boolean = engineReady
    fun isCurrentlyInferring(): Boolean = isInferring.get()
    fun getEngineBackend(): String = vlmEngine.getActiveBackend()

    // ── Lifecycle ──────────────────────────────────────────────────

    fun destroy() {
        vlmEngine.close()
        scope.cancel()
        Log.d(TAG, "VyzeCoreController destroyed")
    }

    companion object {
        private const val TAG = "VyzeCoreController"
        // Bitmap preprocessing is now handled by VlmEngineManager.analyzeImage()
    }
}
