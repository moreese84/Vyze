package com.vyze.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import com.vyze.app.data.InteractionDao
import com.vyze.app.data.MemoryDao
import com.vyze.app.memory.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central controller for the Vyze VLM accessibility pipeline.
 *
 * ## Session Isolation
 * Every capture trigger generates a unique [activeSessionId]. All token
 * callbacks and onComplete handlers check this ID before acting — stale
 * tokens/completions from a cancelled or superseded inference are silently
 * dropped. This prevents the previous capture's result from being spoken
 * after a new capture has started.
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

    @Volatile
    private var inferenceJob: kotlinx.coroutines.Job? = null

    private val memoryRepository = MemoryRepository(interactionDao)
    private val vlmEngine = VlmEngineManager(context, memoryRepository)
    private val promptBuilder = DynamicPromptBuilder(memoryDao)
    private val ocrHelper = OcrHelper()

    private val isInferring = AtomicBoolean(false)

    @Volatile
    private var engineReady = false

    // ── Session Isolation ─────────────────────────────────────────
    // Each capture trigger generates a new UUID. Token and onComplete
    // callbacks check this ID — stale callbacks from a previous
    // inference are silently dropped.

    @Volatile
    private var activeSessionId: String = ""

    // ── Debounce Cache ────────────────────────────────────────────

    @Volatile
    private var lastDescribedObject: String = ""

    @Volatile
    private var lastDescribedTime: Long = 0L

    private val DEBOUNCE_GAP_MS = 4000L

    // ── Language Mirroring ────────────────────────────────────────
    // Detected from SpeechRecognizer. Passed to DynamicPromptBuilder
    // for language mirror directive and to TTSManager for voice switching.
    @Volatile
    private var activeUserLocale: Locale = Locale.US

    // ── Sentence Buffer (Token Streaming) ─────────────────────────

    private val sentenceBuffer = StringBuilder()
    private val bufferLock = Any()

    @Volatile
    private var firstChunkSent = false

    private val SENTENCE_TERMINATORS = charArrayOf('.', '!', '?', '\n')

    /** Early flush at commas/colons for faster TTFT — no min char threshold. */
    private val EARLY_FLUSH_TERMINATORS = charArrayOf(',', ':')

    private val minFlushChars = 10

    /** Monotonically increasing counter for unique utterance IDs per session. */
    private val chunkCounter = java.util.concurrent.atomic.AtomicInteger(0)

    var onStatusUpdate: ((String) -> Unit)? = null
    var onProgressUpdate: ((Int, String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onInferenceComplete: ((String) -> Unit)? = null

    // ── Initialization ─────────────────────────────────────────────

    /** Tracks which download milestones have been announced to avoid repeats. */
    private val announcedMilestones = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun initialize() {
        // Reset milestone tracker for this download session
        announcedMilestones.clear()

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

            // ── Audio Progress Milestones ─────────────────────────
            // Speak at 0% (start), 25%, 50%, 75%, and 100% (complete)
            // so blind users hear regular progress updates.
            if (total > 0) {
            val progressPercent = (copied * 100 / total).toInt()

            when {
                // Start: first callback, announce download is beginning
                copied < total / 50 && announcedMilestones.add(0) -> {
                    mainHandler.post {
                        try {
                            ttsManager.speakQueued(
                                "Model not found. Downloading now. " +
                                "This is about ${mbTotal / 1024} gigabytes."
                            )
                        } catch (_: Throwable) {}
                    }
                }
                // 25%
                progressPercent >= 25 && announcedMilestones.add(25) -> {
                    mainHandler.post {
                        try {
                            ttsManager.speakQueued("25 percent downloaded.")
                        } catch (_: Throwable) {}
                    }
                }
                // 50%
                progressPercent >= 50 && announcedMilestones.add(50) -> {
                    mainHandler.post {
                        try {
                            ttsManager.speakQueued("Halfway downloaded.")
                        } catch (_: Throwable) {}
                    }
                }
                // 75%
                progressPercent >= 75 && announcedMilestones.add(75) -> {
                    mainHandler.post {
                        try {
                            ttsManager.speakQueued("75 percent downloaded. Almost done.")
                        } catch (_: Throwable) {}
                    }
                }
                // 100%
                progressPercent >= 99 && announcedMilestones.add(100) -> {
                    mainHandler.post {
                        try {
                            ttsManager.speakQueued("Download complete. Loading model now.")
                        } catch (_: Throwable) {}
                    }
                }
            }
            } // end if (total > 0)
        }

        vlmEngine.onStepProgress = { percent, step ->
            mainHandler.post { onProgressUpdate?.invoke(percent, step) }
        }

        // ── Session-Gated Token Callback ──────────────────────────
        // Drops tokens from any session that doesn't match activeSessionId.
        vlmEngine.onError = { error, sessionId ->
            if (sessionId != activeSessionId && sessionId.isNotEmpty()) {
                Log.d(TAG, "onError: STALE session $sessionId (active=$activeSessionId) — DISCARDING")
                isInferring.set(false)
            } else {
                Log.e(TAG, "onError: session=$sessionId error=$error")
                flushRemainingSentenceBuffer()
                isInferring.set(false)
                mainHandler.post {
                    onStatusUpdate?.invoke("Error: $error")
                    onError?.invoke(error)
                }
            }
        }

        vlmEngine.onTokenGenerated = { token, sessionId ->
            if (sessionId != activeSessionId && sessionId.isNotEmpty()) {
                // Stale token — SILENTLY DROP
            } else {
                try {
                    synchronized(bufferLock) {
                        sentenceBuffer.append(token)
                    }
                    flushSentenceBufferIfReady()
                } catch (e: Throwable) {
                    Log.e(TAG, "onTokenGenerated error: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        vlmEngine.onComplete = { fullResponse, sessionId ->
            if (sessionId != activeSessionId && sessionId.isNotEmpty()) {
                Log.d(TAG, "onComplete: STALE session $sessionId (active=$activeSessionId) — DISCARDING")
                isInferring.set(false)
            } else {
                CrashLogFile.log(TAG, "onComplete fired: session=$sessionId, ${fullResponse.length} chars")
                try {
                    flushRemainingSentenceBuffer()
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
                            CrashLogFile.log(TAG, "onComplete UI callbacks done (session=$sessionId)")
                        } catch (e: Throwable) {
                            CrashLogFile.logError(TAG, "onComplete callback error: ${e.javaClass.simpleName}: ${e.message}", e)
                        }
                    }
                } catch (e: Throwable) {
                    CrashLogFile.logError(TAG, "onComplete error: ${e.javaClass.simpleName}: ${e.message}", e)
                    isInferring.set(false)
                }
            }
        }

        // Announce loading status so blind users know the app is working.
        val modelExists = vlmEngine.isModelOnDisk()
        if (!modelExists) {
            ttsManager.speakQueued(
                "Please wait, model assets are downloading. " +
                "This may take several minutes on first launch."
            )
        } else {
            ttsManager.speakQueued(
                "Model found. Loading now."
            )
        }

        scope.launch {
            try {
                onStatusUpdate("Loading VLM model...")
                engineReady = vlmEngine.initialize()

                if (engineReady) {
                    Log.i(TAG, "VLM ready [${vlmEngine.getActiveBackend()}]")
                    onStatusUpdate("VLM ready [${vlmEngine.getActiveBackend()}]")
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

    // ── Text Sanitization ──────────────────────────────────────────

    private fun sanitizeForTts(text: String): String {
        var cleaned = text
            .replace("\"", "")
            .replace("\n", " ")
            .replace("\r", "")
            .replace("**", "")
            .replace("__", "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Enforce trailing punctuation — Android TTS clips phonemes on
        // unpunctuated final words. Append '.' if missing.
        if (cleaned.isNotEmpty()) {
            val lastChar = cleaned.last()
            if (lastChar != '.' && lastChar != '!' && lastChar != '?') {
                cleaned = "$cleaned."
            }
        }

        return cleaned
    }

    // ── Sentence Buffer Flush Logic ────────────────────────────────

    private fun flushSentenceBufferIfReady() {
        var chunk: String = ""
        synchronized(bufferLock) {
            val text = sentenceBuffer.toString()
            if (text.isEmpty()) return

            // ── Ultra-fast path: first chunk of response ─────────
            // Flush as soon as 1+ word (≥3 chars) — minimizes
            // Time-To-First-Token for immediate audio feedback.
            if (!firstChunkSent) {
                val wordCount = text.trim().split(Regex("\\s+")).size
                if (wordCount >= FIRST_CHUNK_MIN_WORDS || text.trim().length >= FIRST_CHUNK_MIN_CHARS) {
                    chunk = sanitizeForTts(text.trim())
                    sentenceBuffer.setLength(0)
                    if (chunk.isNotEmpty()) {
                        mainHandler.post {
                            try {
                                val chunkId = "${activeSessionId}_chunk_${chunkCounter.incrementAndGet()}"
                                ttsManager.speak(chunk, TextToSpeech.QUEUE_FLUSH, utteranceId = chunkId)
                                firstChunkSent = true
                                CrashLogFile.log(TAG, "First-chunk flush (id=$chunkId): ${chunk.take(60)}...")
                            } catch (e: Throwable) {
                                Log.w(TAG, "TTS first-chunk error: ${e.message}")
                            }
                        }
                        return
                    }
                }
            }

            // ── Fast path: early punctuation flush (commas, colons) ──
            var earlyTerminator = -1
            for (i in text.length - 1 downTo 0) {
                if (text[i] in EARLY_FLUSH_TERMINATORS) {
                    earlyTerminator = i
                    break
                }
            }
            if (earlyTerminator >= 0 && earlyTerminator + 1 >= MIN_EARLY_FLUSH_CHARS) {
                val raw = text.substring(0, earlyTerminator + 1)
                chunk = sanitizeForTts(raw)
                sentenceBuffer.delete(0, earlyTerminator + 1)
                while (sentenceBuffer.isNotEmpty() && sentenceBuffer[0] == ' ') {
                    sentenceBuffer.deleteCharAt(0)
                }
                if (chunk.isNotEmpty()) {
                    mainHandler.post {
                        try {
                            val chunkId = "${activeSessionId}_chunk_${chunkCounter.incrementAndGet()}"
                            ttsManager.speak(chunk, TextToSpeech.QUEUE_ADD, utteranceId = chunkId)
                            CrashLogFile.log(TAG, "Early flush (id=$chunkId): ${chunk.take(60)}...")
                        } catch (e: Throwable) {
                            Log.w(TAG, "TTS flush error: ${e.message}")
                        }
                    }
                    return
                }
            }

            // ── Slow path: full sentence terminator check ──────────
            var lastTerminator = -1
            for (i in text.length - 1 downTo 0) {
                if (text[i] in SENTENCE_TERMINATORS) {
                    lastTerminator = i
                    break
                }
            }

            if (lastTerminator < 0) return
            if (lastTerminator + 1 < minFlushChars) return

            val raw = text.substring(0, lastTerminator + 1)
            chunk = sanitizeForTts(raw)

            sentenceBuffer.delete(0, lastTerminator + 1)
            while (sentenceBuffer.isNotEmpty() && sentenceBuffer[0] == ' ') {
                sentenceBuffer.deleteCharAt(0)
            }
        }

        if (chunk.isNotEmpty()) {
            mainHandler.post {
                try {
                    val chunkId = "${activeSessionId}_chunk_${chunkCounter.incrementAndGet()}"
                    if (!firstChunkSent) {
                        ttsManager.speak(chunk, TextToSpeech.QUEUE_FLUSH, utteranceId = chunkId)
                        firstChunkSent = true
                    } else {
                        ttsManager.speak(chunk, TextToSpeech.QUEUE_ADD, utteranceId = chunkId)
                    }
                    CrashLogFile.log(TAG, "Sentence flush (id=$chunkId): ${chunk.take(60)}...")
                } catch (e: Throwable) {
                    Log.w(TAG, "TTS flush error: ${e.message}")
                }
            }
        }
    }

    /**
     * Force-flush ALL remaining text in the sentence buffer.
     * Called from onComplete when inference finishes.
     *
     * This method:
     * - Bypasses minFlushChars (no minimum threshold)
     * - Bypasses SENTENCE_TERMINATORS check (flushes even without trailing period)
     * - Sanitizes text for TTS prosody
     * - Posts to mainHandler for sequential QUEUE_ADD
     *
     * Idempotent: safe to call multiple times (clears buffer on first call).
     */
    private fun flushRemainingSentenceBuffer() {
        val remaining: String
        synchronized(bufferLock) {
            if (sentenceBuffer.isEmpty()) return
            remaining = sanitizeForTts(sentenceBuffer.toString().trim())
            sentenceBuffer.setLength(0)  // force-clear, not just clear()
        }

        if (remaining.isNotEmpty()) {
            mainHandler.post {
                try {
                    val finalChunkId = "${activeSessionId}_final_${chunkCounter.incrementAndGet()}"
                    ttsManager.speak(remaining, TextToSpeech.QUEUE_ADD, utteranceId = finalChunkId)
                    CrashLogFile.log(TAG, "Final flush (id=$finalChunkId): ${remaining.take(80)}...")

                    // Queue a silent tail utterance to keep hasPendingSpeech() true
                    // while the hardware AudioTrack drains the final audible text.
                    // onDone fires when encoding finishes, NOT when speaker finishes.
                    ttsManager.playSilentUtterance(350, TextToSpeech.QUEUE_ADD)
                } catch (e: Throwable) {
                    Log.w(TAG, "TTS final flush error: ${e.message}")
                }
            }
        }
    }

    private fun resetSentenceBuffer() {
        synchronized(bufferLock) {
            sentenceBuffer.clear()
        }
        firstChunkSent = false
    }

    // ── Full Pipeline Reset ────────────────────────────────────────

    /**
     * Full pipeline reset before a new capture. This ensures:
     * 1. Active TTS is stopped (no stale audio from previous capture)
     * 2. Sentence buffer is cleared (no stale tokens)
     * 3. Session ID is incremented (stale callbacks are dropped)
     * 4. Streaming state is reset
     *
     * Must be called BEFORE [triggerSnapshot] to guarantee isolation.
     */
    fun resetForNewCapture() {
        // 1. Stop any active TTS — cancel lingering audio
        ttsManager.stop()

        // 2. Cancel any in-flight inference from previous capture
        cancelInference()

        // 3. Generate new session ID — stale callbacks will be dropped
        val newSessionId = UUID.randomUUID().toString()
        activeSessionId = newSessionId
        chunkCounter.set(0)
        Log.d(TAG, "resetForNewCapture: new session=$newSessionId")

        // 4. Clear all buffers and state
        resetSentenceBuffer()
        lastDescribedObject = ""
        lastDescribedTime = 0L
    }

    // ── Snapshot Trigger ───────────────────────────────────────────

    fun triggerSnapshot(bitmap: Bitmap, query: String? = null, continuousMode: Boolean = false) {
        if (!engineReady) {
            Log.w(TAG, "triggerSnapshot called but engine not ready")
            return
        }

        // Defensive bitmap validation — prevents crashes from recycled/damaged frames
        if (bitmap.isRecycled) {
            Log.e(TAG, "triggerSnapshot: bitmap is recycled — aborting")
            mainHandler.post {
                onStatusUpdate?.invoke("Error: captured frame was recycled")
                onError?.invoke("Captured frame was recycled before inference")
            }
            return
        }

        try {
            bitmap.getPixel(0, 0) // pixel access test — catches hardware corruption
        } catch (e: Throwable) {
            Log.e(TAG, "triggerSnapshot: bitmap is corrupted — aborting")
            mainHandler.post {
                onStatusUpdate?.invoke("Error: captured frame is corrupted")
                onError?.invoke("Captured frame is corrupted: ${e.message}")
            }
            return
        }

        if (!isInferring.compareAndSet(false, true)) {
            Log.d(TAG, "Inference already in progress — ignoring")
            return
        }

        // Ensure session is fresh — resetForNewCapture() should have
        // been called, but guard against missed calls
        val sessionId = activeSessionId
        if (sessionId.isEmpty()) {
            activeSessionId = UUID.randomUUID().toString()
        }
        val currentSessionId = activeSessionId

        resetSentenceBuffer()

        // ── Dynamic Resolution Scaling ──────────────────────────
        // Text-extraction queries ("read", "label", etc.) benefit from higher
        // resolution (384x384) to capture fine-grained text details.
        // Standard scene queries use 256x256 for faster inference.
        val targetDimension = if (isTextExtractionQuery(query)) {
            TEXT_EXTRACTION_DIMENSION
        } else {
            SCENE_QUERY_DIMENSION
        }
        CrashLogFile.log(TAG, "Target dimension: $targetDimension (query=\"${query?.take(40)}\")")

        onStatusUpdate("Analyzing snapshot...")        // ── Watchdog Timer ───────────────────────────────────────
        // If neither onComplete nor onError fires within WATCHDOG_TIMEOUT_MS,
        // force-reset the pipeline and notify the user. Prevents indefinite
        // ANALYZING state when the VLM hangs on GPU execution.
        val watchdogRunnable = Runnable {
            if (isInferring.get() && activeSessionId == currentSessionId) {
                Log.e(TAG, "Watchdog: inference hung for ${WATCHDOG_TIMEOUT_MS}ms — force resetting")
                CrashLogFile.log(TAG, "WATCHDOG FIRED — forcing pipeline reset")
                isInferring.set(false)
                cancelInference()
                resetSentenceBuffer()
                mainHandler.post {
                    onStatusUpdate?.invoke("Inference timed out")
                    onError?.invoke("Inference timed out. Please try again.")
                }
            }
        }
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)

        inferenceJob = scope.launch {
            var inferenceBitmap: Bitmap = bitmap
            try {
                // ── CANCELLATION CHECK: bail out immediately if job was cancelled ──
                if (!isActive) {
                    Log.d(TAG, "Job cancelled before inference start — aborting")
                    return@launch
                }

                CrashLogFile.log(TAG, "=== TRIGGER SNAPSHOT (session=$currentSessionId) ===")
                CrashLogFile.log(TAG, "Bitmap: ${bitmap.width}x${bitmap.height}")

                CrashLogFile.log(TAG, "Querying similar interactions (async)...")

                val similarInteractionsDeferred = async(Dispatchers.IO) {
                    try {
                        memoryRepository.findSimilar(
                            bitmap = bitmap,
                            topK = 5,
                            minSim = 0.3f
                        )
                    } catch (e: Throwable) {
                        CrashLogFile.logError(TAG, "Similar search failed: ${e.message}", e)
                        emptyList()
                    }
                }

                // Downsample bitmap for continuous mode to reduce memory + latency.
                // Center-crop to 1:1 first to preserve spatial alignment, then scale.
                inferenceBitmap = if (continuousMode &&
                    (bitmap.width > CONTINUOUS_MAX_DIM || bitmap.height > CONTINUOUS_MAX_DIM)
                ) {
                    CrashLogFile.log(TAG, "Downsampling bitmap for continuous mode: ${bitmap.width}x${bitmap.height} -> ${CONTINUOUS_MAX_DIM}x${CONTINUOUS_MAX_DIM}")
                    try {
                        // Center-crop to square
                        val size = minOf(bitmap.width, bitmap.height)
                        val cx = (bitmap.width - size) / 2
                        val cy = (bitmap.height - size) / 2
                        val cropped = android.graphics.Bitmap.createBitmap(bitmap, cx, cy, size, size)
                        // Scale to target
                        val scaled = android.graphics.Bitmap.createScaledBitmap(
                            cropped, CONTINUOUS_MAX_DIM, CONTINUOUS_MAX_DIM, true
                        )
                        if (scaled !== cropped) cropped.recycle()
                        scaled
                    } catch (e: Throwable) {
                        CrashLogFile.logError(TAG, "Downsample failed: ${e.message}", e)
                        bitmap
                    }
                } else bitmap

                // ── OCR PRE-PASS (text queries only) ──────────────
                // ML Kit OCR is 10-30x faster than full VLM inference.
                // For text queries, run OCR first, then feed clean text
                // to Gemma for interpretation — skips character-level reading.
                var ocrText: String? = null
                var ocrConfidence = 0f
                val isTextQuery = isTextExtractionQuery(query)

                if (isTextQuery) {
                    CrashLogFile.log(TAG, "Text query detected — running ML Kit OCR...")
                    val ocrResult = ocrHelper.extractTextWithConfidence(inferenceBitmap)
                    ocrText = ocrResult.first
                    ocrConfidence = ocrResult.second
                    CrashLogFile.log(TAG, "OCR result: ${ocrText?.take(100) ?: "(none)"} confidence=$ocrConfidence")
                }

                // ── OCR FAST-PATH: skip Gemma if confidence is high ──
                // If ML Kit returned clean, high-confidence text, there's no
                // need to invoke the 3.66GB Gemma model. TTS reads OCR text
                // directly — saves battery and reduces latency from ~5s to ~150ms.
                if (isTextQuery && !ocrText.isNullOrBlank() && ocrConfidence >= OCR_FAST_PATH_CONFIDENCE) {
                    CrashLogFile.log(TAG, "OCR FAST-PATH: confidence=$ocrConfidence >= $OCR_FAST_PATH_CONFIDENCE — skipping Gemma")
                    isInferring.set(false)
                    mainHandler.removeCallbacks(watchdogRunnable)
                    val ocrResponse = ocrText
                    if (currentSessionId == activeSessionId) {
                        mainHandler.post {
                            onInferenceComplete?.invoke(ocrResponse)
                            onStatusUpdate?.invoke("Ready [OCR fast-path]")
                        }
                    }
                    return@launch
                }

                // ── CANCELLATION CHECK: bail out before prompt build ──
                if (!isActive) {
                    Log.d(TAG, "Job cancelled before prompt build — aborting")
                    return@launch
                }

                CrashLogFile.log(TAG, "Building prompt...")
                val basePrompt = promptBuilder.buildPrompt(
                    snapshotDescription = query ?: "User triggered a camera snapshot.",
                    queryOverride = query,
                    continuousMode = continuousMode,
                    userLocale = activeUserLocale,
                    ocrText = ocrText
                )
                CrashLogFile.log(TAG, "Base prompt built: ${basePrompt.length} chars")

                // ── CANCELLATION CHECK: bail out before VLM call ──
                if (!isActive) {
                    Log.d(TAG, "Job cancelled before VLM call — aborting")
                    return@launch
                }

                CrashLogFile.log(TAG, "Calling vlmEngine.analyzeImage()...")
                // Text queries need more tokens (medicine labels, documents)
                // Scene queries are concise (25 words max)
                val inferenceMaxTokens = if (isTextQuery) TEXT_QUERY_MAX_TOKENS else SCENE_QUERY_MAX_TOKENS
                val response = vlmEngine.analyzeImage(
                    bitmap = inferenceBitmap,
                    prompt = basePrompt,
                    memoryContext = null,
                    similarInteractions = emptyList(),
                    sessionId = currentSessionId,
                    targetDimension = targetDimension,
                    maxTokens = inferenceMaxTokens
                )

                // ── CANCELLATION CHECK: bail out after VLM call if cancelled ──
                if (!isActive) {
                    Log.d(TAG, "Job cancelled after VLM call — discarding response")
                    return@launch
                }

                CrashLogFile.log(TAG, "analyzeImage() returned: ${response?.length ?: 0} chars")

                val similarInteractions = try {
                    similarInteractionsDeferred.await()
                } catch (e: Throwable) {
                    emptyList()
                }
                CrashLogFile.log(TAG, "Similar interactions resolved: ${similarInteractions.size} found")

                if (response == null) {
                    // ALWAYS reset isInferring — prevents stuck ANALYZING state
                    isInferring.set(false)
                    flushRemainingSentenceBuffer()
                    if (currentSessionId == activeSessionId) {
                        mainHandler.post {
                            onStatusUpdate?.invoke("Inference returned empty response")
                            onError?.invoke("No response from model")
                        }
                    }
                } else {
                    CrashLogFile.log(TAG, "Storing interaction for adaptive intelligence...")
                    memoryRepository.storeInteraction(
                        bitmap = bitmap,
                        prompt = basePrompt,
                        output = response
                    )

                    if (currentSessionId == activeSessionId) {
                        val normalized = response.trim().lowercase()
                        if (normalized.isNotBlank()) {
                            lastDescribedObject = normalized
                            lastDescribedTime = System.currentTimeMillis()
                        }
                    }
                }

            } catch (e: Throwable) {
                CrashLogFile.logError(TAG, "Snapshot trigger FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                // ALWAYS reset isInferring + flush buffer — prevents stuck ANALYZING
                isInferring.set(false)
                flushRemainingSentenceBuffer()
                if (currentSessionId == activeSessionId) {
                    mainHandler.post {
                        onStatusUpdate?.invoke("Error: ${e.message}")
                        onError?.invoke("Inference crashed: ${e.message}")
                    }
                }
            } finally {
                // DEFENSIVE: guarantee isInferring is never left true
                isInferring.set(false)
                // Cancel watchdog — inference completed (success, error, or cancel)
                mainHandler.removeCallbacks(watchdogRunnable)

                // Recycle downsampled bitmap if it's a different instance
                if (inferenceBitmap !== bitmap) {
                    try {
                        if (!inferenceBitmap.isRecycled) {
                            inferenceBitmap.recycle()
                            CrashLogFile.log(TAG, "Downsampled bitmap recycled")
                        }
                    } catch (_: Throwable) {}
                }

                CrashLogFile.log(TAG, "finally block — recycling original bitmap")
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

    fun cancelInference() {
        inferenceJob?.let { job ->
            if (job.isActive) {
                Log.d(TAG, "Cancelling in-flight inference job")
                job.cancel()
            }
        }
        inferenceJob = null
        isInferring.set(false)

        // Release the active inference latch so the blocking await() resumes
        // immediately. The engine stays alive — no model reload on next query.
        // Stale callbacks from the interrupted inference are dropped via
        // sessionId gating in onTokenGenerated / onComplete / onError.
        try {
            vlmEngine.interrupt()
        } catch (e: Throwable) {
            Log.w(TAG, "interrupt() error: ${e.message}")
        }
    }

    fun cancelAndReset() {
        cancelInference()
        lastDescribedObject = ""
        lastDescribedTime = 0L
        resetSentenceBuffer()
        Log.d(TAG, "cancelAndReset: pipeline fully reset")
    }

    // ── Language Mirroring ────────────────────────────────────────

    /**
     * Lock TTS voice + prompt language to the user's detected spoken language.
     * Called from CameraFragment when SpeechRecognizer returns results.
     *
     * @param detectedLocale Language detected by SpeechRecognizer, or null
     *                       (falls back to Locale.US if null or unsupported)
     */
    fun setUserLocale(detectedLocale: Locale?) {
        val locale = detectedLocale?.takeIf {
            it.language.isNotBlank() && it != Locale("und")
        } ?: Locale.US

        activeUserLocale = locale
        Log.i(TAG, "setUserLocale: $locale (language=${locale.language})")

        // Switch TTS voice to match detected language
        mainHandler.post {
            ttsManager.switchToLocale(locale)
        }
    }

    // ── State ──────────────────────────────────────────────────────

    fun setPreference(key: String, value: String) {
        scope.launch { promptBuilder.setPreference(key, value) }
    }

    fun getPromptBuilder(): DynamicPromptBuilder = promptBuilder
    fun getStorageSettingsIntent(): android.content.Intent? = vlmEngine.buildStorageSettingsIntent()
    fun isEngineReady(): Boolean = engineReady
    fun isCurrentlyInferring(): Boolean = isInferring.get()
    fun getEngineBackend(): String = vlmEngine.getActiveBackend()
    fun getActiveSessionId(): String = activeSessionId

    fun isStreamingActive(): Boolean = firstChunkSent

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

    fun resetSessionState() {
        Log.d(TAG, "resetSessionState: clearing debounce + pending + engine session")
        lastDescribedObject = ""
        lastDescribedTime = 0L
        resetSentenceBuffer()
        vlmEngine.resetSession()
    }

    fun destroy() {
        vlmEngine.close()
        ocrHelper.close()
        scope.cancel()
        Log.d(TAG, "VyzeCoreController destroyed")
    }

    // ── Dynamic Resolution Scaling ──────────────────────────────

    /**
     * Detect text-extraction queries that benefit from higher resolution.
     * Returns true if the query contains keywords indicating the user wants
     * to read text, labels, signs, or documents.
     *
     * When true, the bitmap is scaled to 384x384 (vs 256x256 for scene queries)
     * to capture finer text details for the VLM's OCR capabilities.
     */
    private fun isTextExtractionQuery(query: String?): Boolean {
        if (query.isNullOrBlank()) return false
        val lower = query.lowercase()
        return TEXT_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    companion object {
        private const val TAG = "VyzeCoreController"

        /**
         * Safety watchdog timeout (ms). If neither onComplete nor onError
         * fires within this window, the pipeline is force-reset to prevent
         * indefinite ANALYZING state.
         */
        private const val WATCHDOG_TIMEOUT_MS = 15_000L

        /** Max dimension for continuous mode bitmap downsampling. */
        private const val CONTINUOUS_MAX_DIM = 256
        private const val MIN_EARLY_FLUSH_CHARS = 12
        private const val FIRST_CHUNK_MIN_WORDS = 1
        private const val FIRST_CHUNK_MIN_CHARS = 3

        // ── Dynamic Resolution Constants ──────────────────────────

        /** Higher resolution for text extraction (384x384 captures fine text details). */
        private const val TEXT_EXTRACTION_DIMENSION = 384

        /** Standard resolution for scene queries (256x256 for fast inference). */
        private const val SCENE_QUERY_DIMENSION = 256

        // ── Dynamic Token Limits ────────────────────────────────
        /** Scene queries: concise descriptions (25 words, ~35 tokens). */
        private const val SCENE_QUERY_MAX_TOKENS = 48
        /** Text queries: full label/document reading (96 tokens = ~70 words). */
        private const val TEXT_QUERY_MAX_TOKENS = 96

        // ── OCR Fast-Path ──────────────────────────────────────
        /** ML Kit confidence threshold to skip Gemma and read OCR text directly. */
        private const val OCR_FAST_PATH_CONFIDENCE = 0.85f

        /** Keywords that trigger higher-resolution text extraction + OCR pre-pass. */
        private val TEXT_KEYWORDS = listOf(
            // English
            "read", "label", "text", "sign", "document",
            "ingredient", "word", "writing", "print",
            "prescription", "medicine", "dosage", "instructions",
            "menu", "book", "paper", "note", "letter",
            "number", "phone", "address", "name",
            "price", "tag", "caption", "title", "heading",
            // Malay / Bahasa Melayu
            "baca", "harga", "ramuan", "resipi", "ubat",
            "dos", "arahan", "alamat", "telefon", "nota",
            "menu", "surat", "tulisan", "nombor", "nama"
        )
    }
}
