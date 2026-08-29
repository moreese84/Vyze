package com.vyze.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vyze.app.data.InteractionDao
import com.vyze.app.data.MemoryDao
import com.vyze.app.memory.MemoryRepository
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
    private val memoryDao: MemoryDao,
    interactionDao: InteractionDao
) {

    private val TAG = "VyzeCoreController"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Track the current inference Job so it can be cancelled on barge-in. */
    @Volatile
    private var inferenceJob: kotlinx.coroutines.Job? = null

    private val memoryRepository = MemoryRepository(interactionDao)
    private val vlmEngine = VlmEngineManager(context, memoryRepository)
    private val promptBuilder = DynamicPromptBuilder(memoryDao)

    /** Gate to prevent overlapping inferences. */
    private val isInferring = AtomicBoolean(false)

    /** Whether the VLM engine has been initialized. */
    @Volatile
    private var engineReady = false

    // ── Debounce Cache ────────────────────────────────────────────
    // Prevents re-announcing the same object within a short window.
    // Solves the "Sandals → Stairs repeat" issue where stale results
    // are processed after TTS finishes.

    /** Last description spoken to the user (lowercase, trimmed). */
    @Volatile
    private var lastDescribedObject: String = ""

    /** Timestamp (ms) when [lastDescribedObject] was last spoken. */
    @Volatile
    private var lastDescribedTime: Long = 0L

    /** Minimum gap between identical descriptions. */
    private val DEBOUNCE_GAP_MS = 4000L  // 4 seconds

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
            // Announce download start on first progress callback
            // Use speakQueued (not speakImmediate) to preserve buffer contents
            if (copied < total / 20) {
                mainHandler.post {
                    try {
                        ttsManager.speakQueued(
                            "Please wait, model assets are downloading. " +
                            "This may take several minutes on first launch."
                        )
                    } catch (_: Throwable) {}
                }
            }
            // Announce at ~50% milestone
            if (total > 0 && copied in (total / 2 - 10_000_000)..(total / 2 + 10_000_000)) {
                mainHandler.post {
                    try {
                        ttsManager.speakQueued("Download is halfway complete.")
                    } catch (_: Throwable) {}
                }
            }
        }

        vlmEngine.onStepProgress = { percent, step ->
            mainHandler.post { onProgressUpdate?.invoke(percent, step) }
        }

        vlmEngine.onError = { error ->
            isInferring.set(false)
            mainHandler.post {
                // TTS error feedback is handled by CameraFragment to avoid duplication
                onStatusUpdate?.invoke("Error: $error")
                onError?.invoke(error)
            }
        }

        // Collect tokens into buffer ONLY — do NOT call TTS per-token.
        // Do NOT expose partial tokens to external callbacks.
        // Speaking is done once at the end via CameraFragment.speakThenCallback().
        vlmEngine.onTokenGenerated = { token ->
            try {
                // Accumulate silently — no external callback, no TTS leak
                pendingResponse.append(token)
            } catch (e: Throwable) {
                Log.e(TAG, "onTokenGenerated error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        vlmEngine.onComplete = { fullResponse ->
            try {
                CrashLogFile.log(TAG, "onComplete fired: ${fullResponse.length} chars")
                pendingResponse.clear()

                // NOTE: TTS is NOT spoken here. CameraFragment.onInferenceComplete
                // handles speaking via speakThenCallback() to ensure single-output
                // execution and prevent double-speaking.

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

        // Queue download announcement IMMEDIATELY — before any async work starts.
        // This guarantees the message is in the TTS buffer even if the model file
        // already exists (no copy → onModelCopyProgress never fires).
        ttsManager.speakQueued(
            "Please wait, model assets are downloading. " +
            "This may take several minutes on first launch."
        )

        // Launch init on background thread
        scope.launch {
            try {
                onStatusUpdate("Loading VLM model...")
                engineReady = vlmEngine.initialize()

                if (engineReady) {
                    Log.i(TAG, "VLM ready [${vlmEngine.getActiveBackend()}]")
                    onStatusUpdate("VLM ready [${vlmEngine.getActiveBackend()}]")
                    // TTS onboarding is handled by CameraFragment when it receives "VLM ready"
                } else {
                    Log.e(TAG, "VLM engine failed to initialize")
                    mainHandler.post {
                        try {
                            ttsManager.speakImmediate(
                                "Model download failed. Please check your internet connection."
                            )
                        } catch (_: Throwable) {}
                        onStatusUpdate?.invoke("Error: Model failed to load")
                    }
                }
            } catch (e: Throwable) {
                val errorMsg = "VLM init crashed: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, errorMsg, e)
                engineReady = false
                mainHandler.post {
                    try {
                        ttsManager.speakImmediate(
                            "Model load failed. ${e.message ?: "Unknown error."}"
                        )
                    } catch (_: Throwable) {}
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

        inferenceJob = scope.launch {
            try {
                CrashLogFile.log(TAG, "=== TRIGGER SNAPSHOT ===")
                CrashLogFile.log(TAG, "Bitmap: ${bitmap.width}x${bitmap.height}")

                // 1. Find visually similar past interactions (runs on IO thread)
                CrashLogFile.log(TAG, "Querying similar interactions...")
                val similarInteractions = memoryRepository.findSimilar(
                    bitmap = bitmap,
                    topK = 5,
                    minSim = 0.3f
                )
                CrashLogFile.log(TAG, "Found ${similarInteractions.size} similar interactions")

                // 2. Build prompt with similar interaction context
                CrashLogFile.log(TAG, "Building prompt...")
                val prompt = promptBuilder.buildPrompt(
                    snapshotDescription = query ?: "User triggered a camera snapshot.",
                    queryOverride = query,
                    similarInteractions = similarInteractions
                )
                CrashLogFile.log(TAG, "Prompt built: ${prompt.length} chars")

                // 3. Run VLM inference (VlmEngineManager handles bitmap preprocessing)
                pendingResponse.clear()
                CrashLogFile.log(TAG, "Calling vlmEngine.analyzeImage()...")

                val response = vlmEngine.analyzeImage(
                    bitmap = bitmap,
                    prompt = prompt,
                    memoryContext = null,
                    similarInteractions = similarInteractions
                )
                CrashLogFile.log(TAG, "analyzeImage() returned: ${response?.length ?: 0} chars")

                if (response == null) {
                    isInferring.set(false)
                    mainHandler.post {
                        onStatusUpdate?.invoke("Inference failed")
                    }
                } else {
                    // 4. Store interaction for future similarity search
                    CrashLogFile.log(TAG, "Storing interaction for adaptive intelligence...")
                    memoryRepository.storeInteraction(
                        bitmap = bitmap,
                        prompt = prompt,
                        output = response
                    )

                    // 5. Update debounce cache with the described object
                    val normalized = response.trim().lowercase()
                    if (normalized.isNotBlank()) {
                        lastDescribedObject = normalized
                        lastDescribedTime = System.currentTimeMillis()
                    }
                }

            } catch (e: Throwable) {
                // Catch Throwable (not Exception) to handle OOM, UnsatisfiedLinkError, etc.
                CrashLogFile.logError(TAG, "Snapshot trigger FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                isInferring.set(false)
                mainHandler.post {
                    // TTS handled by CameraFragment.onError to avoid duplication
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

    /**
     * Cancel any in-flight VLM inference job. Called on barge-in when the user
     * taps during ANALYZING state to immediately reset to IDLE.
     */
    fun cancelInference() {
        inferenceJob?.let { job ->
            if (job.isActive) {
                Log.d(TAG, "Cancelling in-flight inference job")
                job.cancel()
                isInferring.set(false)
            }
        }
        inferenceJob = null
    }

    /**
     * Cancel any in-flight inference AND clear the debounce cache.
     * Called on barge-in to fully reset the analysis pipeline.
     */
    fun cancelAndReset() {
        cancelInference()
        lastDescribedObject = ""
        lastDescribedTime = 0L
        pendingResponse.clear()
        Log.d(TAG, "cancelAndReset: pipeline fully reset")
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

    /**
     * Check if this response is a duplicate of the last described object.
     * Returns true if the normalized response matches [lastDescribedObject]
     * and was spoken within [DEBOUNCE_GAP_MS].
     */
    fun isDuplicateDescription(response: String): Boolean {
        val normalized = response.trim().lowercase()
        if (normalized.isBlank()) return false
        if (normalized == lastDescribedObject) {
            val elapsed = System.currentTimeMillis() - lastDescribedTime
            if (elapsed < DEBOUNCE_GAP_MS) {
                Log.d(TAG, "Debounce: '$normalized' already spoken ${elapsed}ms ago — skipping")
                return true
            }
        }
        return false
    }

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
