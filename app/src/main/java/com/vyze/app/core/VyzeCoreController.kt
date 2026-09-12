package com.vyze.app.core
import com.vyze.app.VyzeApplication
import com.vyze.app.util.CrashLogFile
import com.vyze.app.speech.TTSManager
import com.vyze.app.device.AudioCapture
import com.vyze.app.vision.EmbeddingEngine
import com.vyze.app.vision.OcrHelper
import com.vyze.app.vision.BarcodeHelper

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import com.vyze.app.data.InteractionDao
import com.vyze.app.data.MemoryDao
import com.vyze.app.memory.MemoryRepository
import com.vyze.app.memory.PreferenceLearner
import com.vyze.app.memory.SimilarInteraction
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
    private val barcodeHelper = BarcodeHelper()
    private val scanRepository by lazy { com.vyze.app.data.ScanRepository(context.applicationContext) }
    private val preferenceLearner = PreferenceLearner(memoryDao)

    private val isInferring = AtomicBoolean(false)

    @Volatile
    private var engineReady = false

    // ── Session Isolation ─────────────────────────────────────────
    // Each capture trigger generates a new UUID. Token and onComplete
    // callbacks check this ID — stale callbacks from a previous
    // inference are silently dropped.

    @Volatile
    private var activeSessionId: String = ""

    /** True while the current snapshot is a currency query (banknote/coin). */
    @Volatile
    private var currencyModeActive = false

    /** True while the current snapshot is a bank card identification query. */
    @Volatile
    private var bankCardModeActive = false

    /**
     * Timestamp of the last inference activity (token received). The
     * watchdog re-arms itself while tokens are still flowing, so long
     * read-back generations are never force-killed mid-sentence — but a
     * genuinely hung inference (no tokens for the grace period) still resets.
     */
    @Volatile
    private var lastInferenceActivityMs: Long = 0L

    // ── Debounce Cache ────────────────────────────────────────────

    @Volatile
    private var lastDescribedObject: String = ""

    @Volatile
    private var lastDescribedTime: Long = 0L

    // ── Dialogue Memory (conversational follow-ups) ───────────────
    // Bounded ring buffer of recent voice exchanges. Injected into voice
    // query prompts so pronouns resolve across turns ("what about the one
    // BEHIND it?"). Dropped when the follow-up window closes or after
    // DIALOGUE_TTL_MS of silence. Deliberately SMALL: 2 turns × ~100 chars
    // ≈ 120-150 prompt tokens — negligible prefill even on low-RAM phones.
    private val dialogueTurns = ArrayDeque<Pair<String, String>>(MAX_DIALOGUE_TURNS)

    /** Timestamp of the last recorded exchange (for TTL expiry). */
    @Volatile
    private var lastDialogueAt = 0L

    /** True while the hands-free conversation window is open. */
    @Volatile
    private var conversationWindowOpen = false

    // ── Continuous-Mode Scene Gating (battery) ────────────────────
    // Baseline embedding of the scene as of the last SPOKEN description.
    // Auto-captures that are visually unchanged vs this baseline skip the
    // multi-second Gemma run entirely — same audible outcome, big battery
    // and latency saving. Uses EmbeddingEngine (~1ms per frame).
    @Volatile
    private var lastContinuousEmbedding: FloatArray? = null

    @Volatile
    private var lastContinuousDescriptionAt = 0L

    // ── Speech Intelligence (instant answers + conversation verbs) ──
    // Last frame retained for "tell me more" — expands the PREVIOUS answer
    // on the SAME frame instead of re-capturing: zero capture latency, and
    // the detail describes what the user actually just heard about.
    // Owned bitmap (separate instance from the pipeline's recycled ones).
    private var lastSnapshotBitmap: Bitmap? = null

    /** Fired when a continuous auto-capture is skipped (scene unchanged). */
    var onContinuousSkip: (() -> Unit)? = null

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

    // Language FIX: added full-width CJK sentence punctuation (。！？；) —
    // Gemma ends Chinese answers with these, not ASCII dots. Without them the
    // streaming flush never found a boundary and a Chinese answer was only
    // spoken after the whole generation finished (or stalled entirely on long
    // answers), which users perceived as "no response" for Chinese queries.
    private val SENTENCE_TERMINATORS = charArrayOf('.', '!', '?', '\n', '。', '！', '？', '；')

    private var flushCountersSent = 0

    /** True while the CURRENT flush is a mid-sentence fast-start fragment. */
    @Volatile
    private var fastStartFragment = false

    /** Chars accumulated before a post-first coalesced flush may fire. */
    private val COALESCED_FLUSH_CHARS = 110

    private val minFlushChars = 10

    /** Monotonically increasing counter for unique utterance IDs per session. */
    private val chunkCounter = java.util.concurrent.atomic.AtomicInteger(0)

    // ── Confidence Check ─────────────────────────────────────────
    /** Buffer for first N tokens to check for hedging language. */
    private val tokenConfidenceBuffer = StringBuilder()
    /** Once first N tokens pass confidence check, stop checking. */
    private var confidenceCheckPassed = false

    var onStatusUpdate: ((String) -> Unit)? = null
    var onProgressUpdate: ((Int, String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onInferenceComplete: ((String) -> Unit)? = null

    // ── Initialization ─────────────────────────────────────────────

    /** Tracks which download milestones have been announced to avoid repeats. */
    private val announcedMilestones = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun initialize() {
        // Seed the prompt output language from the user's DECLARED Vyze voice
        // (persisted). Without this, single-tap/reading answers stay in English
        // until the user happens to speak once — taps have no speech to detect
        // a language from, so the chosen voice language is the only signal that
        // a Malay user reads Malay labels.
        activeUserLocale = TTSManager.storedLanguageLocale(context)

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
                                "First time setup. Downloading AI engine. " +
                                "This takes a few minutes on first use."
                            )
                        } catch (_: Throwable) {}
                    }
                }
                // 25%
                progressPercent >= 25 && announcedMilestones.add(25) -> {
                    mainHandler.post {
                        try {
                            ttsManager.speakQueued("Quarterway there.")
                        } catch (_: Throwable) {}
                    }
                }
                // 50%
                progressPercent >= 50 && announcedMilestones.add(50) -> {
                    mainHandler.post {
                        try {
                            ttsManager.speakQueued("Halfway done.")
                        } catch (_: Throwable) {}
                    }
                }
                // 75%
                progressPercent >= 75 && announcedMilestones.add(75) -> {
                    mainHandler.post {
                        try {
                            ttsManager.speakQueued("Almost there.")
                        } catch (_: Throwable) {}
                    }
                }
                // 100%
                progressPercent >= 99 && announcedMilestones.add(100) -> {
                    mainHandler.post {
                        try {
                            ttsManager.speakQueued("Download complete. Preparing AI assistant.")
                        } catch (_: Throwable) {}
                    }
                }
            }
            } // end if (total > 0)
        }

        vlmEngine.onStepProgress = { percent, step ->
            mainHandler.post {
                onProgressUpdate?.invoke(percent, step)

                // Speak key milestones so blind users hear progress
                // during the 10-20s model loading phase.
                when {
                    percent >= 75 && announcedMilestones.add(75) -> {
                        try { ttsManager.speakQueued("Almost ready.") } catch (_: Throwable) {}
                    }
                    percent >= 30 && percent < 75 && announcedMilestones.add(30) -> {
                        try { ttsManager.speakQueued("Loading model weights.") } catch (_: Throwable) {}
                    }
                }
            }
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
                    // ── WATCHDOG PROGRESS: tokens are flowing — reset the
                    //    stall clock so long generations are never killed.
                    lastInferenceActivityMs = System.currentTimeMillis()

                    // ── CONFIDENCE CHECK: abort if model is hedging ──────
                    // Track the first few tokens. If the model starts with
                    // hedging language ("I think", "maybe", "it looks like"),
                    // it's uncertain. Abort early and return a safe fallback
                    // instead of letting it guess and potentially hallucinate.
                    if (!confidenceCheckPassed) {
                        tokenConfidenceBuffer.append(token)
                        val accumulated = tokenConfidenceBuffer.toString().trim()
                        if (accumulated.length >= CONFIDENCE_CHECK_CHARS) {
                            val lowerAccumulated = accumulated.lowercase()
                            val isHedging = HEDGING_PHRASES.any { phrase ->
                                lowerAccumulated.contains(phrase)
                            }
                            if (isHedging) {
                                Log.w(TAG, "Confidence abort: model hedging on '$accumulated'")
                                CrashLogFile.log(TAG, "CONFIDENCE ABORT: hedging detected")
                                confidenceCheckPassed = true  // prevent re-entry
                                isInferring.set(false)
                                vlmEngine.interrupt()
                                mainHandler.post {
                                    onInferenceComplete?.invoke("Not clearly visible.")
                                    onStatusUpdate?.invoke("Ready [low confidence]")
                                }
                            }
                            confidenceCheckPassed = true  // first N tokens OK — no more checking
                        }
                    }

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

                        // ── CURRENCY SCAN HISTORY ─────────────────────
                        // Persist a confident money read (banknote or coin)
                        // into scan history via the existing CURRENCY type.
                        if (currencyModeActive && fullResponse.isNotBlank()) {
                            try {
                                com.vyze.app.data.ScanRepository(context.applicationContext)
                                    .saveCurrencyScan(fullResponse.take(120))
                                CrashLogFile.log(TAG, "Currency scan saved: ${fullResponse.take(60)}")
                            } catch (e: Throwable) {
                                CrashLogFile.logError(TAG, "Currency scan save failed: ${e.message}", e)
                            }
                        }
                        currencyModeActive = false

                        // ── BANK CARD SCAN HISTORY ───────────────────
                        // Persist a confident bank card identification
                        // (bank name, card type) into scan history.
                        if (bankCardModeActive && fullResponse.isNotBlank()) {
                            try {
                                com.vyze.app.data.ScanRepository(context.applicationContext)
                                    .saveBankCardScan(fullResponse.take(120))
                                CrashLogFile.log(TAG, "Bank card scan saved: ${fullResponse.take(60)}")
                            } catch (e: Throwable) {
                                CrashLogFile.logError(TAG, "Bank card scan save failed: ${e.message}", e)
                            }
                        }
                        bankCardModeActive = false
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
                "First time setup. Downloading AI engine. " +
                "This takes a few minutes on first use."
            )
        } else {
            ttsManager.speakQueued(
                "Preparing your AI assistant."
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
                            "AI engine setup failed. Please check your connection and try again."
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
                            "AI engine failed to start. ${e.message ?: "Please restart the app."}"
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
        // unpunctuated final words. Append '.' if missing. A trailing ',' is
        // kept as-is: it is already punctuation (anti-clipping satisfied), and
        // it marks a fast-start mid-sentence fragment whose continuation must
        // flow — stamping a period there would force full-stop intonation and
        // a dead-air seam into the middle of a sentence.
        if (cleaned.isNotEmpty()) {
            val lastChar = cleaned.last()
            if (lastChar != '.' && lastChar != '!' && lastChar != '?' && lastChar != ',') {
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

            // ── Sentence-boundary flushing only ─────────────────────
            // Flush ONLY at real sentence ends (., !, ?, newline). Commas and
            // colons stay INSIDE the sentence. Previously the buffer also
            // flushed at commas (and even spoke the first 2-3 words instantly),
            // which chopped one sentence into many tiny utterances — each one
            // spoken with a full-stop intonation and a dead-air gap, so users
            // heard "A red can. …(seconds of silence)… with a white label."
            // Whole sentences are the natural spoken unit: buffer until one
            // completes, then speak it in one flowing utterance (the TTS voice
            // renders internal commas as short natural pauses).
            var cut = -1
            for (i in text.length - 1 downTo 0) {
                if (text[i] in SENTENCE_TERMINATORS) {
                    cut = i
                    break
                }
            }

            // ── Hard ceiling ───────────────────────────────────────
            // If the model emits a long run without sentence punctuation
            // (rare), flush anyway so speech never stalls for seconds.
            // Language FIX: ceiling halved for CJK — 200 Latin chars ≈ 40
            // Chinese characters; CJK text is far denser, so the same stall
            // protection must trip much earlier (200 CJK chars is a whole
            // paragraph, roughly a minute of silence before the first word).
            val readAheadCeiling = if (text.any { it.code in 0x2E80..0x9FFF || it.code in 0x3000..0x303F || it.code in 0xFF00..0xFFEF }) {
                MAX_FLUSH_READ_AHEAD_CHARS / 5
            } else {
                MAX_FLUSH_READ_AHEAD_CHARS
            }
            if (cut < 0 && text.length >= readAheadCeiling) {
                cut = text.length - 1
            }

            // ── FIRST-UTTERANCE FAST START ────────────────────────
            // ONLY for the very first flush: if the model is still mid-sentence
            // but has already emitted a long-enough lead-in (dense descriptions
            // often run 60+ chars before the first period), speak the
            // word-aligned prefix immediately instead of making the user wait
            // for the entire first sentence. This moves first speech forward by
            // ~1s at the cost of ONE brief mid-sentence pause at the very
            // start. Every later flush stays strictly sentence-boundary, so the
            // "A red can…" choppiness cannot come back.
            if (cut < 0 && !firstChunkSent && text.length >= FAST_START_CHARS) {
                val spaceIdx = text.lastIndexOf(' ', FAST_START_CHARS - 1)
                if (spaceIdx >= FAST_START_MIN_CHARS) {
                    cut = spaceIdx
                    // Continuity FIX: do NOT let sanitizeForTts stamp a period
                    // onto this mid-sentence fragment. A period gives the
                    // continuation full-stop intonation + a dead-air seam
                    // ("Brown wooden door. …closed, center…"). A comma keeps
                    // the voice in listing intonation, so the rest of the
                    // sentence flows as one natural continuation.
                    fastStartFragment = true
                }
            }
            if (cut < 0) {
                return
            }
            // Keep buffering tiny fragments ("Yes.") so a one-word sentence
            // doesn't become its own clipped utterance — it joins the next one.
            if (cut + 1 < minFlushChars) return

            // ── COALESCED FLUSH (post-first utterances) ───────────
            // After the first flush, accumulate ~2 short sentences (or one
            // long one) before speaking again. Android TTS leaves a 100-300ms
            // hardware seam between separate utterances — one utterance per
            // sentence made longer answers sound like stop-start reading.
            // Coalescing turns most answers into 1-2 flowing utterances at
            // the cost of ~0.5-1s extra wait on LATER sentences only (the
            // first flush still speaks at the earliest possible moment).
            if (firstChunkSent && flushCountersSent >= 1 && !fastStartFragment) {
                val lastIdx = lastSentenceTerminatorIndex(text)
                val coalescedLen = (lastIdx?.plus(1)) ?: text.length
                if (coalescedLen < COALESCED_FLUSH_CHARS &&
                    text.length < readAheadCeiling
                ) {
                    return // keep buffering — more sentences will join this utterance
                }
                // Coalescing target reached: cut at the LAST terminator so
                // maximum content rides in this one utterance. (Past the hard
                // ceiling the cut is already forced — speak now, boundary or not.)
                if (lastIdx != null && text.length < readAheadCeiling) {
                    cut = lastIdx
                }
            }

            val cutEnd = cut + 1
            chunk = sanitizeForTts(
                text.substring(0, cutEnd) + if (fastStartFragment) "," else ""
            )
            fastStartFragment = false
            flushCountersSent++

            sentenceBuffer.delete(0, cut + 1)
            while (sentenceBuffer.isNotEmpty() && sentenceBuffer[0] == ' ') {
                sentenceBuffer.deleteCharAt(0)
            }
        }

        if (chunk.isNotEmpty()) {
            mainHandler.post {
                try {
                    val chunkId = "${activeSessionId}_chunk_${chunkCounter.incrementAndGet()}"
                    // First utterance flushes any leftover status speech
                    // ("Analyzing scene...") so the answer starts clean.
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
            if (sentenceBuffer.isNotEmpty()) {
                remaining = sanitizeForTts(sentenceBuffer.toString().trim())
                sentenceBuffer.setLength(0)  // force-clear, not just clear()
            } else {
                remaining = ""
            }
        }

        // Post unconditionally: even with nothing left to speak, queue a
        // silent tail so hasPendingSpeech() stays true while the hardware
        // AudioTrack drains the last audible utterance — prevents the final
        // words from being clipped when the caller polls for completion.
        mainHandler.post {
            try {
                if (remaining.isNotEmpty()) {
                    val finalChunkId = "${activeSessionId}_final_${chunkCounter.incrementAndGet()}"
                    ttsManager.speak(remaining, TextToSpeech.QUEUE_ADD, utteranceId = finalChunkId)
                    CrashLogFile.log(TAG, "Final flush (id=$finalChunkId): ${remaining.take(80)}...")
                }
                ttsManager.playSilentUtterance(450, TextToSpeech.QUEUE_ADD)
            } catch (e: Throwable) {
                Log.w(TAG, "TTS final flush error: ${e.message}")
            }
        }
    }

    private fun resetSentenceBuffer() {
        synchronized(bufferLock) {
            sentenceBuffer.clear()
        }
        firstChunkSent = false
        flushCountersSent = 0
        fastStartFragment = false
        tokenConfidenceBuffer.clear()
        confidenceCheckPassed = false
    }

    /** Index of the LAST sentence terminator in [text], or null if none. */
    private fun lastSentenceTerminatorIndex(text: String): Int? {
        for (i in text.length - 1 downTo 0) {
            if (text[i] in SENTENCE_TERMINATORS) return i
        }
        return null
    }

    // ── Tap Grid Tagging (structured spatial prompting) ───────────

    /** Matches the "(x, y)" pair inside a tap query string. */
    private val TAP_COORDS_REGEX = Regex("\\((\\d+),\\s*(\\d+)\\)")

    /**
     * Map raw tap coordinates to a 3x3 grid sector of the camera frame.
     * Small VLMs ground coarse positional tags far better than raw pixel
     * numbers — this turns "(120, 400)" into "Center-Left".
     */
    private fun gridSectorFor(x: Int, y: Int, frameW: Int, frameH: Int): String {
        val col = when {
            x < frameW / 3 -> "Left"
            x < frameW * 2 / 3 -> "Center"
            else -> "Right"
        }
        val row = when {
            y < frameH / 3 -> "Top"
            y < frameH * 2 / 3 -> "Middle"
            else -> "Bottom"
        }
        return "$row-$col"
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
    init {
        // Answer-completion signal for the preference learner: fires ONLY for
        // utterances that played to the end — never for flushed/stopped speech
        // (notifyFlushed reports those as onError). Each fully-heard answer is
        // evidence the current verbosity is acceptable and erodes one step of
        // interrupt evidence.
        ttsManager.onUtteranceCompleted = { _ ->
            scope.launch { preferenceLearner.recordAnswerCompleted() }
        }
    }

    fun resetForNewCapture() {
        // 0. PREFERENCE SIGNAL: a user-initiated capture while speech is still
        //    playing = impatience evidence ("that answer was longer than I
        //    needed"). MUST be read before ttsManager.stop() below, which
        //    clears the pending-utterance state. Continuous-mode auto-captures
        //    never pass through this method, so leaning back and listening can
        //    never be misread as impatience.
        if (ttsManager.hasPendingSpeech()) {
            scope.launch { preferenceLearner.recordInterruptWhileSpeaking() }
        }

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
        currencyModeActive = false
        bankCardModeActive = false
        // A deliberate user action invalidates the continuous-mode scene
        // baseline — the next auto-capture should describe fresh.
        lastContinuousEmbedding = null
    }

    // ── Retained-Frame Helpers (conversation verbs) ───────────────

    /**
     * Owned downscaled copy of the frame retained for "tell me more".
     * Returns null when the source frame is unusable — retention is
     * best-effort and must never disturb the main pipeline.
     */
    private fun downscaleForRetention(source: Bitmap): Bitmap? {
        return try {
            if (source.isRecycled) return null
            val w = source.width
            val h = source.height
            if (w <= 0 || h <= 0) return null
            val scale = RETAINED_FRAME_DIM.toFloat() / maxOf(w, h)
            if (scale >= 1f) {
                source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
            } else {
                Bitmap.createScaledBitmap(
                    source,
                    (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1),
                    true
                )
            }
        } catch (e: Throwable) {
            CrashLogFile.logError(TAG, "downscaleForRetention failed: ${e.message}", e)
            null
        }
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
        // Text-extraction queries ("read", "label", etc.) and tap queries
        // (which often land on objects with labels) benefit from higher
        // resolution to capture fine-grained text details (512px when OCR
        // actually finds text — see the post-OCR dimension decision below).
        // Standard scene queries use 256x256 for faster inference.
        val isTapQuery = query?.contains(TAP_POSITION_MARKER) == true
        // Dialogue memory only for GENUINE spoken follow-ups — taps and
        // precise-read modes must not inherit conversational context.
        val isVoiceFollowUpCandidate = !isTapQuery && !query.isNullOrBlank()
        val currencyQuery = isCurrencyQuery(query)
        val bankCardQuery = isBankCardQuery(query)
        // Deterministic at trigger time — the volatile mode flags are reset in
        // onComplete before the record step runs.
        val isPreciseRead = currencyQuery || bankCardQuery
        if (isVoiceFollowUpCandidate) {
            // Lightweight TTL prune on every voice trigger.
            if (System.currentTimeMillis() - lastDialogueAt > DIALOGUE_TTL_MS) {
                dialogueTurns.clear()
            }
        }
        currencyModeActive = currencyQuery
        bankCardModeActive = bankCardQuery

        // ── TAP GRID TAG (structured spatial prompting) ───────────
        // Raw pixel coords mean nothing to a small VLM — it ignores them.
        // Translate the tap into a 3x3 sector tag so the model knows WHERE
        // the user is asking about without doing pixel math.
        val tapSector = if (isTapQuery) {
            query?.let { q ->
                TAP_COORDS_REGEX.find(q)?.destructured?.let { (xs, ys) ->
                    gridSectorFor(xs.toInt(), ys.toInt(), bitmap.width, bitmap.height)
                }
            }
        } else null
        val taggedQuery = if (tapSector != null && query != null) {
            "$query [Target location: $tapSector of the camera frame]"
        } else query
        // Pointing questions ("what is this", "apa ini", "这是什么") point at a
        // real object, usually packaged goods with labels — give them the
        // high-resolution + OCR pre-pass so the brand and text are read from
        // ground truth instead of a 256px guess.
        val isTextQuery = isTextExtractionQuery(query) || isTapQuery || currencyQuery ||
            bankCardQuery || isPointingQuery(query)

        // ── CONTINUOUS-MODE SCENE GATING (battery) ────────────────
        // A tap/voice query must ALWAYS run. Continuous auto-captures run
        // Gemma only when the scene CHANGED since the last spoken
        // description (~1ms embedding check vs multi-second inference —
        // this is the battery/latency saver).
        if (continuousMode) {
            val baseline = lastContinuousEmbedding
            if (baseline != null) {
                val candidate = EmbeddingEngine.generateEmbedding(bitmap)
                val similarity = EmbeddingEngine.cosineSimilarity(candidate, baseline)
                if (similarity >= CONTINUOUS_SKIP_SIMILARITY) {
                    Log.d(TAG, "Scene gating: unchanged scene (sim=$similarity) — skipping inference")
                    isInferring.set(false)
                    mainHandler.post {
                        onContinuousSkip?.invoke()
                        onStatusUpdate?.invoke("Ready [scene unchanged]")
                    }
                    return
                }
            }
        }

        onStatusUpdate("Analyzing snapshot...")

        // ── Watchdog Timer (progress-aware) ────────────────────
        // Safety net against a hung GPU inference. The runnable re-arms
        // itself whenever tokens are still flowing (lastInferenceActivityMs
        // updated on every received token), so a long text read-back is
        // never force-killed mid-sentence — only a stall with NO output for
        // WATCHDOG_TIMEOUT_MS triggers the force reset.
        lastInferenceActivityMs = System.currentTimeMillis()
        val watchdogRunnable = object : Runnable {
            override fun run() {
                if (isInferring.get() && activeSessionId == currentSessionId) {
                    val idleMs = System.currentTimeMillis() - lastInferenceActivityMs
                    if (idleMs < WATCHDOG_TIMEOUT_MS) {
                        // Still making progress — re-arm and keep watching.
                        mainHandler.postDelayed(this, WATCHDOG_TIMEOUT_MS)
                    } else {
                        Log.e(TAG, "Watchdog: no output for ${WATCHDOG_TIMEOUT_MS}ms — force resetting")
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

                // ── OCR PRE-PASS (text + tap queries) ────────────
                // ML Kit OCR is 10-30x faster than full VLM inference.
                // For text and tap queries, run OCR first, then feed clean
                // text to Gemma for interpretation — the model can read
                // labels and boxes verbatim instead of guessing from a
                // 256px downscale. A tap often lands on an object with
                // text (medicine boxes, signs), so taps always OCR.
                var ocrText: String? = null
                var ocrConfidence = 0f
                var barcodeResult: Pair<String, String>? = null

                if (isTextQuery) {
                    CrashLogFile.log(TAG, "Text query detected — running ML Kit OCR...")
                    val ocrResult = ocrHelper.extractTextWithConfidence(inferenceBitmap)
                    ocrText = ocrResult.first
                    ocrConfidence = ocrResult.second
                    CrashLogFile.log(TAG, "OCR result: ${ocrText?.take(100) ?: "(none)"} confidence=$ocrConfidence")

                    // ── BARCODE PRE-PASS (runs alongside OCR) ─────────
                    // ~50-120ms. Runs on every text/tap query so retail
                    // products and banknotes are identified by their barcode
                    // even when printed labels are unreadable to OCR.
                    try {
                        barcodeResult = barcodeHelper.scan(inferenceBitmap)
                        if (barcodeResult != null) {
                            CrashLogFile.log(TAG, "Barcode detected: ${barcodeResult?.second} — ${barcodeResult?.first?.take(60)}")
                        }
                    } catch (e: Throwable) {
                        CrashLogFile.logError(TAG, "Barcode pre-pass failed: ${e.message}", e)
                    }

                    // ── SCAN HISTORY PERSISTENCE ─────────────────────
                    // Fire-and-forget (sibling on the controller scope, so an
                    // inference cancel can't lose the write): the scan DID
                    // happen once OCR/barcode recognized content. Covers the
                    // OCR fast-path, VLM-path and OCR-fallback completions
                    // without duplicating per completion path.
                    val ocrToPersist = ocrText
                    val barcodeToPersist = barcodeResult
                    if (!ocrToPersist.isNullOrBlank() || barcodeToPersist != null) {
                        scope.launch {
                            try {
                                ocrToPersist?.let {
                                    scanRepository.saveOcrScan(it)
                                    CrashLogFile.log(TAG, "OCR scan saved: ${it.take(60)}")
                                }
                                barcodeToPersist?.let { (rawValue, format) ->
                                    scanRepository.saveBarcodeScan(rawValue, format)
                                    CrashLogFile.log(TAG, "Barcode scan saved: $format — ${rawValue.take(60)}")
                                }
                            } catch (e: Throwable) {
                                CrashLogFile.logError(TAG, "Scan history save failed: ${e.message}", e)
                            }
                        }
                    }

                    // ── MEDICINE LOOKUP: cross-reference OCR against local DB ──
                    // If OCR text matches a known medicine, inject structured drug
                    // info into the prompt so Gemma can provide accurate medical
                    // information without guessing from visual patterns.
                    if (!ocrText.isNullOrBlank() && isMedicineQuery(query)) {
                        try {
                            val medicineInfo = lookupMedicine(ocrText)
                            if (medicineInfo != null) {
                                CrashLogFile.log(TAG, "Medicine match: ${medicineInfo.name}")
                                ocrText = "$ocrText\n[MEDICINE INFO: ${medicineInfo.name}, ${medicineInfo.genericName}, ${medicineInfo.dosage}. ${medicineInfo.frequency}. WARNING: ${medicineInfo.warnings}]"
                            }
                        } catch (e: Throwable) {
                            CrashLogFile.logError(TAG, "Medicine lookup failed: ${e.message}", e)
                        }
                    }
                }

                // ── VLM DIMENSION — decided AFTER OCR ────────────
                // Pay the full 512px prefill only when OCR actually found text
                // for the model to echo; a tap/pointing query with no readable
                // text needs only 384px, and pure scene queries stay at 256px.
                // Keeps small-text accuracy where it matters while shaving
                // prefill latency on textless captures.
                val targetDimension = when {
                    isTextQuery && !ocrText.isNullOrBlank() -> TEXT_EXTRACTION_DIMENSION
                    // Textless visual object (tap / "what is this" with nothing
                    // readable): treat it like a scene query — 256px prefill is
                    // the fastest the GPU can do, and there is no text to gain
                    // from a bigger image.
                    isTapQuery || isPointingQuery(query) -> SCENE_QUERY_DIMENSION
                    isTextQuery -> TEXT_EXTRACTION_NO_OCR_DIMENSION
                    else -> SCENE_QUERY_DIMENSION
                }
                CrashLogFile.log(TAG, "VLM dimension: $targetDimension (ocrChars=${ocrText?.length ?: 0})")

                // ── OCR FAST-PATH: skip Gemma if confidence is high ──
                // Only for EXPLICIT spoken reading queries ("read this label",
                // "baca teks ini") — the whole ask IS the text, so reading it
                // directly is correct. Taps are excluded on purpose: a tap on a
                // product must keep Gemma in the loop, because the fast path
                // would otherwise commit Latin-only OCR at high confidence and
                // never give the model a chance to read small/mixed script the
                // OCR missed (e.g. tiny Chinese glyphs on a small bottle).
                if (!isTapQuery && isTextExtractionQuery(query) &&
                    !ocrText.isNullOrBlank() && ocrConfidence >= OCR_FAST_PATH_CONFIDENCE
                ) {
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

                // ── MEMORY CONTEXT (resolved in parallel with OCR above) ──
                // If the current frame strongly matches a RECENT past scan, hand
                // the prior description to the model as context. The model still
                // analyzes the FRESH frame — memory never replaces the analysis,
                // it only lets the answer confirm continuity ("same box you
                // scanned earlier") instead of describing from zero.
                val similarInteractions = try {
                    similarInteractionsDeferred.await()
                } catch (e: Throwable) {
                    emptyList()
                }
                CrashLogFile.log(TAG, "Similar interactions resolved: ${similarInteractions.size} found")

        val memoryContext = if (continuousMode || currencyModeActive || bankCardModeActive || !ocrText.isNullOrBlank()) {
            null // scene memory adds nothing where OCR is already the ground truth
        } else {
                    buildMemoryContext(similarInteractions)
                }
                if (memoryContext != null) {
                    CrashLogFile.log(TAG, "Memory context injected: ${memoryContext.take(80)}...")
                }

                val brevityLevel = preferenceLearner.getBrevityLevel()
                // Conversational context ONLY for genuine voice follow-ups —
                // never for taps, continuous mode, currency or bank-card reads.
                val dialogueContext = if (isVoiceFollowUpCandidate &&
                    !continuousMode && !currencyModeActive && !bankCardModeActive
                ) {
                    dialogueContextForPrompt()
                } else null
                CrashLogFile.log(TAG, "Building prompt... (brevity=${brevityLevel.label}, dialogue=${dialogueContext != null})")
                val basePrompt = promptBuilder.buildPrompt(
                    snapshotDescription = taggedQuery ?: "User triggered a camera snapshot.",
                    queryOverride = taggedQuery,
                    continuousMode = continuousMode,
                    userLocale = activeUserLocale,
                    ocrText = ocrText,
                    currencyMode = currencyModeActive,
                    bankCardMode = bankCardModeActive,
                    memoryContext = memoryContext,
                    brevityLevel = brevityLevel,
                    dialogueContext = dialogueContext
                )
                CrashLogFile.log(TAG, "Base prompt built: ${basePrompt.length} chars")

                // ── CANCELLATION CHECK: bail out before VLM call ──
                if (!isActive) {
                    Log.d(TAG, "Job cancelled before VLM call — aborting")
                    return@launch
                }

                CrashLogFile.log(TAG, "Calling vlmEngine.analyzeImage()...")
                // Scene queries are concise (25 words max). Text queries get an
                // ADAPTIVE budget sized to the OCR text actually found — the
                // model mostly echoes it back, so a dense back-panel gets a
                // proportional budget instead of hitting a fixed cap.
                val inferenceMaxTokens = if (isTextQuery) {
                    textQueryTokenBudget(ocrText)
                } else {
                    // Learned brevity tightens the output budget so short
                    // answers also finish GENERATING sooner, not just read
                    // shorter — first-audio latency improves with it.
                    when (brevityLevel) {
                        PreferenceLearner.BrevityLevel.NORMAL -> SCENE_QUERY_MAX_TOKENS
                        PreferenceLearner.BrevityLevel.BRIEF -> (SCENE_QUERY_MAX_TOKENS * 4) / 5
                        PreferenceLearner.BrevityLevel.TERSE -> (SCENE_QUERY_MAX_TOKENS * 3) / 5
                    }
                }
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

                // ── OCR FALLBACK ─────────────────────────────────
                // A READING query (tap on a label/box/panel, pointing question,
                // explicit read ask) must never end in silence when the model
                // itself produced nothing — Gemma is weak in Malay, so a Malay
                // back-panel read can come back blank/empty even though ML Kit
                // already extracted the ground truth. Deliver the OCR text
                // verbatim instead.
                if (response.isNullOrBlank() && isTextQuery && !ocrText.isNullOrBlank()) {
                    isInferring.set(false)
                    flushRemainingSentenceBuffer()
                    CrashLogFile.log(TAG, "VLM response blank but OCR text exists — using OCR fallback")
                    if (currentSessionId == activeSessionId) {
                        mainHandler.post {
                            onInferenceComplete?.invoke(ocrText)
                            onStatusUpdate?.invoke("Ready [OCR fallback]")
                        }
                    }
                } else if (response == null) {
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
                        // Record the exchange for conversational follow-ups.
                        if (isVoiceFollowUpCandidate && !isPreciseRead) {
                            recordDialogueTurn(query ?: "", response)
                        }

                        // Continuous mode: refresh the scene baseline so the
                        // NEXT auto-capture can skip if the scene is unchanged.
                        if (continuousMode) {
                            try {
                                lastContinuousEmbedding = EmbeddingEngine.generateEmbedding(bitmap)
                                lastContinuousDescriptionAt = System.currentTimeMillis()
                            } catch (e: Throwable) {
                                CrashLogFile.logError(TAG, "Scene baseline update failed: ${e.message}", e)
                            }
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

                // ── LAST-FRAME RETENTION (conversation verbs) ────────
                // Keep an OWNED copy of the frame so "tell me more" can
                // expand on THIS scene without a fresh capture. The original
                // and downsampled bitmaps are recycled below — the retained
                // frame must be a separate instance.
                try {
                    val keep = if (inferenceBitmap !== bitmap && !inferenceBitmap.isRecycled) {
                        inferenceBitmap
                    } else {
                        downscaleForRetention(bitmap)
                    }
                    if (keep != null) {
                        val old = lastSnapshotBitmap
                        lastSnapshotBitmap = keep
                        if (old != null && old !== keep) {
                            try { old.recycle() } catch (_: Throwable) {}
                        }
                    }
                } catch (e: Throwable) {
                    CrashLogFile.logError(TAG, "Frame retention failed: ${e.message}", e)
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

    // ── Speech Intelligence: instant answers + conversation verbs ──
    // The same tiering philosophy as OCR fast-path and barcode pre-pass,
    // extended to SPEECH: queries the phone already knows the answer to are
    // served locally in milliseconds instead of a multi-second Gemma run.

    /**
     * Build the localized instant answer for a system-status query (time,
     * date, battery level), or null when the query is not an instant answer.
     * Detection and answering share this single source of truth — they can
     * never disagree.
     *
     * Deliberately CONSERVATIVE keyword lists: a false instant answer is
     * worse than a slow Gemma answer, so forms that can be part of a bigger
     * knowledge question ("what time does the store open", "how do I save
     * battery") fall through to the normal pipelines.
     */
    private fun instantAnswerFor(query: String): String? {
        val lower = query.lowercase().trim()
        val words = lower.split(Regex("\\s+")).size

        val timeQ =
            lower.contains("what time is it") || lower.contains("what's the time") ||
                lower.contains("whats the time") ||
                (lower.contains("what time") && (lower.contains("now") || words <= 3)) ||
                lower.contains("pukul berapa sekarang") || lower.contains("pukul berapa ini") ||
                lower.contains("sekarang pukul") || lower.contains("jam berapa sekarang") ||
                (lower.contains("jam berapa") && words <= 3) ||
                (lower.contains("pukul berapa") && words <= 3) ||
                lower.contains("现在几点") || lower.contains("几点了") || lower == "几点"

        val dateQ =
            (lower.contains("what day is") &&
                (lower.contains("today") || lower.contains("hari ini") || lower.contains("今天"))) ||
                lower.contains("what date") || lower.contains("today's date") ||
                lower.contains("todays date") || lower.contains("tarikh hari ini") ||
                lower.contains("ini hari apa") || lower.contains("hari apa hari ini") ||
                lower.contains("今天几号") || lower.contains("今天是几号") ||
                lower.contains("今天是星期几") || lower.contains("今天星期几") ||
                lower.contains("今天几月")

        // Status asks only — knowledge questions about batteries stay on the
        // text-only pipeline.
        val batteryQ =
            lower.contains("how much battery") || lower.contains("battery level") ||
                lower.contains("battery percentage") || lower.contains("battery status") ||
                lower.contains("battery left") || lower.contains("is my battery") ||
                lower == "battery" || lower == "bateri" ||
                lower.contains("bateri berapa") || lower.contains("berapa bateri") ||
                lower.contains("bateri peratus") || lower.contains("bateri ada berapa") ||
                lower.contains("电量多少") || lower.contains("电池还有多少") ||
                lower.contains("电量还有多少")

        if (!timeQ && !dateQ && !batteryQ) return null

        val isMalay = activeUserLocale.language == "ms"
        val isChinese = activeUserLocale.language == "zh"

        return when {
            timeQ -> {
                val now = java.util.Calendar.getInstance()
                val time = java.text.DateFormat.getTimeInstance(
                    java.text.DateFormat.SHORT, activeUserLocale
                ).format(now.time)
                when {
                    isMalay -> "Pukul $time."
                    isChinese -> "现在是$time。"
                    else -> "It's $time."
                }
            }
            dateQ -> {
                val date = java.text.DateFormat.getDateInstance(
                    java.text.DateFormat.LONG, activeUserLocale
                ).format(java.util.Calendar.getInstance().time)
                when {
                    isMalay -> "Hari ini $date."
                    isChinese -> "今天是$date。"
                    else -> "Today is $date."
                }
            }
            else -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                if (level < 0) {
                    when {
                        isMalay -> "Tahap bateri tidak dapat dibaca."
                        isChinese -> "无法读取电量。"
                        else -> "Battery level is unavailable right now."
                    }
                } else {
                    val status = try {
                        context.registerReceiver(
                            null,
                            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                        )?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                    } catch (_: Throwable) { -1 }
                    val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == android.os.BatteryManager.BATTERY_STATUS_FULL
                    when {
                        isMalay && charging -> "Bateri pada $level peratus, sedang mengecas."
                        isMalay -> "Bateri pada $level peratus."
                        isChinese && charging -> "电量为$level%，正在充电。"
                        isChinese -> "电量为$level%。"
                        charging -> "Battery is at $level percent, charging."
                        else -> "Battery is at $level percent."
                    }
                }
            }
        }
    }

    /** True when [query] is answerable locally without any model inference. */
    fun detectInstantAnswer(query: String?): Boolean {
        if (query.isNullOrBlank()) return false
        return instantAnswerFor(query) != null
    }

    /** Speak the instant answer immediately — no capture, no inference. */
    fun triggerInstantAnswer(query: String) {
        val answer = instantAnswerFor(query) ?: return
        CrashLogFile.log(TAG, "Instant answer — inference skipped: \"${answer.take(60)}\"")
        ttsManager.speakQueued(answer)
        onStatusUpdate?.invoke("Ready [instant]")
    }

    /**
     * Verbs that ask Vyze to EXPAND or REPEAT the previous answer. Bare
     * "lagi" / "more" are deliberately EXCLUDED — they are too ambiguous in
     * everyday speech ("apa lagi") and must never hijack the normal pipeline.
     */
    private val EXPANSION_VERBS = listOf(
        // English
        "more detail", "more details", "tell me more", "expand", "elaborate",
        "go on", "say again", "repeat that", "repeat it", "what did you say",
        "in detail", "more about", "describe more", "what else",
        // Malay
        "lebih detail", "lebih terperinci", "terangkan lagi", "cerita lagi",
        "lagi detail", "bagitahu lagi", "cakap lagi", "ulang", "sebut lagi",
        "lebih banyak", "lagi banyak", "apa lagi",
        // Chinese
        "详细一点", "再详细", "详细说明", "多说一点", "再说一遍", "再说一次",
        "再讲一遍", "重复", "多一点", "还有什么"
    )

    /**
     * True when the query is a conversation verb ("tell me more" / "lagi
     * terperinci" / "详细一点") about the answer just given. Requires a recent
     * spoken answer (lastDescribedTime) and the retained frame — without both,
     * the query falls through to the normal pipeline.
     */
    fun detectConversationVerb(query: String?): Boolean {
        if (query.isNullOrBlank()) return false
        if (lastDescribedObject.isBlank() ||
            System.currentTimeMillis() - lastDescribedTime > EXPANSION_MAX_AGE_MS
        ) return false
        val frame = lastSnapshotBitmap ?: return false
        if (frame.isRecycled) return false
        val lower = query.lowercase().trim()
        return EXPANSION_VERBS.any { lower.contains(it) }
    }

    /**
     * Expand the PREVIOUS answer on the RETAINED frame — no re-capture, so
     * zero camera latency and the detail describes exactly what the user just
     * heard about. Learned brevity is overridden on purpose: the user asked
     * for MORE. Completion (speak + follow-up window) fires via the shared
     * onComplete callback, exactly like an image response.
     */
    fun triggerExpandedAnswer() {
        val frame = lastSnapshotBitmap
        if (frame == null || frame.isRecycled) return
        if (!engineReady) {
            Log.w(TAG, "triggerExpandedAnswer called but engine not ready")
            return
        }
        if (!isInferring.compareAndSet(false, true)) {
            Log.d(TAG, "Expanded answer: inference already in progress — ignoring")
            return
        }
        activeSessionId = UUID.randomUUID().toString()
        val currentSessionId = activeSessionId
        resetSentenceBuffer()

        lastInferenceActivityMs = System.currentTimeMillis()
        val watchdogRunnable = object : Runnable {
            override fun run() {
                if (isInferring.get() && activeSessionId == currentSessionId) {
                    val idleMs = System.currentTimeMillis() - lastInferenceActivityMs
                    if (idleMs < WATCHDOG_TIMEOUT_MS) {
                        mainHandler.postDelayed(this, WATCHDOG_TIMEOUT_MS)
                    } else {
                        Log.e(TAG, "Watchdog: no expanded output for ${WATCHDOG_TIMEOUT_MS}ms — force resetting")
                        isInferring.set(false)
                        cancelInference()
                        resetSentenceBuffer()
                        mainHandler.post {
                            onStatusUpdate?.invoke("Inference timed out")
                            onError?.invoke("Inference timed out. Please try again.")
                        }
                    }
                }
            }
        }
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)

        inferenceJob = scope.launch {
            try {
                if (!isActive) {
                    Log.d(TAG, "Expansion job cancelled before start — aborting")
                    return@launch
                }
                CrashLogFile.log(TAG, "=== EXPANDED ANSWER (session=$currentSessionId) ===")
                val prior = lastDescribedObject
                val expansionDirective =
                    "The user asks for MORE detail about the previous answer: \"$prior\". " +
                    "Describe the scene in MORE depth: objects you did not mention before, " +
                    "colors, textures, positions, distances, and any visible text. " +
                    "Do NOT repeat the previous answer. Do NOT invent objects that are not visible."
                val basePrompt = promptBuilder.buildPrompt(
                    snapshotDescription = expansionDirective,
                    queryOverride = expansionDirective,
                    continuousMode = false,
                    userLocale = activeUserLocale,
                    ocrText = null,
                    currencyMode = false,
                    bankCardMode = false,
                    memoryContext = null,
                    brevityLevel = PreferenceLearner.BrevityLevel.NORMAL,
                    dialogueContext = dialogueContextForPrompt()
                )
                if (!isActive) return@launch
                CrashLogFile.log(TAG, "Expansion prompt built: ${basePrompt.length} chars")
                val response = vlmEngine.analyzeImage(
                    bitmap = frame,
                    prompt = basePrompt,
                    memoryContext = null,
                    similarInteractions = emptyList(),
                    sessionId = currentSessionId,
                    targetDimension = 512,
                    maxTokens = SCENE_QUERY_MAX_TOKENS
                )
                if (!isActive) {
                    Log.d(TAG, "Expansion job cancelled after VLM call — discarding")
                    return@launch
                }
                if (response.isNullOrBlank()) {
                    flushRemainingSentenceBuffer()
                    if (currentSessionId == activeSessionId) {
                        mainHandler.post {
                            onStatusUpdate?.invoke("Inference returned empty response")
                            onError?.invoke("No response from model")
                        }
                    }
                } else {
                    // The prior-answer anchor is deliberately LEFT pointing at
                    // the ORIGINAL description: updating it here would make
                    // the fragment's isDuplicateDescription compare the
                    // expansion against ITSELF (a self-duplicate race that
                    // can swallow the speech). Repeated "tell me more" still
                    // works — the anchor stays fresh from the original answer,
                    // and dialogue memory carries the prior expansions.
                    recordDialogueTurn("more detail", response)
                }
            } catch (e: Throwable) {
                CrashLogFile.logError(TAG, "Expanded answer FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                isInferring.set(false)
                flushRemainingSentenceBuffer()
                if (currentSessionId == activeSessionId) {
                    mainHandler.post {
                        onStatusUpdate?.invoke("Error: ${e.message}")
                        onError?.invoke("Inference crashed: ${e.message}")
                    }
                }
            } finally {
                // DEFENSIVE: never leave isInferring true
                isInferring.set(false)
                mainHandler.removeCallbacks(watchdogRunnable)
                CrashLogFile.log(TAG, "=== EXPANDED ANSWER DONE ===")
            }
        }
    }

    // ── Text-Only Q&A (no camera needed) ──────────────────────────

    /**
     * Detect general-knowledge questions that need NO camera frame
     * ("what is paracetamol used for?", "how do I tie a knot?"). These are
     * answered by the model's text decoder alone — faster and cheaper than
     * image inference, and they don't require pointing the phone.
     *
     * Conservative by design: if the query mentions anything visual
     * (this, here, in front, see, look), it falls through to the normal
     * camera pipeline — a missed text-only route is safe, a wrongly
     * routed visual query is not.
     */
    fun isTextOnlyQuery(query: String?): Boolean {
        if (query.isNullOrBlank()) return false
        val lower = query.lowercase().trim()

        // Malay knowledge form — "apa itu <noun>?" (what is <noun>?) is a
        // question ABOUT the noun, not a pointer at a scene object. The bare
        // forms ("apa itu?", "itu apa?") point at something and must stay on
        // the camera path. Scene extras (holding / in front / see) keep it on
        // the camera path too ("apa itu yang saya pegang" = what am I holding).
        val wordCount = lower.split(Regex("\\s+")).size
        val malayKnowledgeWithSubject =
            (lower.contains("apa itu") || lower.contains("apa ini")) &&
                wordCount >= 3 &&
                !lower.contains("pegang") &&
                !lower.contains("tangan") &&
                !lower.contains("hadapan") &&
                !lower.contains("depan") &&
                !lower.contains("nampak") &&
                !lower.contains("lihat")
        if (malayKnowledgeWithSubject) return true

        // Must contain a knowledge-question marker...
        val hasKnowledgeMarker = TEXT_ONLY_QUERY_KEYWORDS.any { lower.contains(it) }
        if (!hasKnowledgeMarker) return false
        // ...and must NOT reference the visual scene.
        val referencesScene = TEXT_ONLY_EXCLUDE_KEYWORDS.any { lower.contains(it) }
        return !referencesScene
    }

    /**
     * Run a text-only inference — no bitmap, no OCR, no memory fingerprint.
     * Uses the model's text decoder directly for general-knowledge answers.
     *
     * The response streams through the same onTokenGenerated/onComplete
     * callbacks and is spoken by the caller (CameraFragment) exactly like a
     * scene answer.
     */
    fun triggerTextQuery(query: String) {
        if (!engineReady) {
            Log.w(TAG, "triggerTextQuery called but engine not ready")
            return
        }
        if (!isInferring.compareAndSet(false, true)) {
            Log.d(TAG, "Text inference already in progress — ignoring")
            return
        }

        // Fresh session — stale callbacks from a previous inference are dropped
        activeSessionId = UUID.randomUUID().toString()
        val currentSessionId = activeSessionId
        resetSentenceBuffer()

        // Progress-aware watchdog — same protection as image inference
        lastInferenceActivityMs = System.currentTimeMillis()
        val watchdogRunnable = object : Runnable {
            override fun run() {
                if (isInferring.get() && activeSessionId == currentSessionId) {
                    val idleMs = System.currentTimeMillis() - lastInferenceActivityMs
                    if (idleMs < WATCHDOG_TIMEOUT_MS) {
                        mainHandler.postDelayed(this, WATCHDOG_TIMEOUT_MS)
                    } else {
                        Log.e(TAG, "Watchdog: no text output for ${WATCHDOG_TIMEOUT_MS}ms — force resetting")
                        isInferring.set(false)
                        cancelInference()
                        resetSentenceBuffer()
                        mainHandler.post {
                            onStatusUpdate?.invoke("Inference timed out")
                            onError?.invoke("Inference timed out. Please try again.")
                        }
                    }
                }
            }
        }
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)

        inferenceJob = scope.launch {
            try {
                // ── CANCELLATION CHECK ──────────────────────────
                if (!isActive) {
                    Log.d(TAG, "Text job cancelled before start — aborting")
                    return@launch
                }

                CrashLogFile.log(TAG, "Building text-only prompt...")
                val basePrompt = promptBuilder.buildPrompt(
                    snapshotDescription = query,
                    queryOverride = query,
                    continuousMode = false,
                    userLocale = activeUserLocale,
                    ocrText = null,
                    currencyMode = false,
                    bankCardMode = false,
                    memoryContext = null,
                    brevityLevel = preferenceLearner.getBrevityLevel()
                )
                CrashLogFile.log(TAG, "Text prompt built: ${basePrompt.length} chars")

                // ── CANCELLATION CHECK ──────────────────────────
                if (!isActive) {
                    Log.d(TAG, "Text job cancelled before VLM call — aborting")
                    return@launch
                }

                CrashLogFile.log(TAG, "Calling vlmEngine.analyzeText()...")
                val response = vlmEngine.analyzeText(
                    prompt = basePrompt,
                    sessionId = currentSessionId,
                    maxTokens = TEXT_ONLY_MAX_TOKENS
                )

                if (!isActive) {
                    Log.d(TAG, "Text job cancelled after VLM call — discarding")
                    return@launch
                }

                CrashLogFile.log(TAG, "analyzeText() returned: ${response?.length ?: 0} chars")

                if (response == null) {
                    isInferring.set(false)
                    flushRemainingSentenceBuffer()
                    if (currentSessionId == activeSessionId) {
                        mainHandler.post {
                            onStatusUpdate?.invoke("Inference returned empty response")
                            onError?.invoke("No response from model")
                        }
                    }
                } else {
                    CrashLogFile.log(TAG, "Storing text interaction...")
                    try {
                        promptBuilder.storeInteraction(query, response)
                    } catch (e: Throwable) {
                        CrashLogFile.logError(TAG, "Text interaction store failed: ${e.message}", e)
                    }
                    recordDialogueTurn(query, response)
                    // Completion (speak + IDLE) fires via the shared onComplete
                    // callback with session gating — same as image responses.
                }
            } catch (e: Throwable) {
                CrashLogFile.logError(TAG, "Text query FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                isInferring.set(false)
                flushRemainingSentenceBuffer()
                if (currentSessionId == activeSessionId) {
                    mainHandler.post {
                        onStatusUpdate?.invoke("Error: ${e.message}")
                        onError?.invoke("Inference crashed: ${e.message}")
                    }
                }
            } finally {
                // DEFENSIVE: never leave isInferring true
                isInferring.set(false)
                mainHandler.removeCallbacks(watchdogRunnable)
                CrashLogFile.log(TAG, "=== TRIGGER TEXT QUERY DONE ===")
            }
        }
    }

    /**
     * Transcribe speech with the model's NATIVE audio encoder — fully
     * offline, no Google services. This is the noisy-room rescue path:
     * when Android's SpeechRecognizer fails or hears ambient chatter,
     * capture the user's speech with [AudioCapture] and feed it here.
     *
     * @param audioBytes Raw 16 kHz mono float32 PCM (from [AudioCapture])
     * @return The transcription, or null if nothing was understood
     */
    suspend fun transcribeAudio(audioBytes: ByteArray): String? {
        if (!engineReady) {
            Log.w(TAG, "transcribeAudio called but engine not ready")
            return null
        }
        // Gemma's ASR instruction — transcribe in the user's language. The
        // language name is derived from the active locale so Malay/Chinese
        // speech is transcribed natively, not through an English detour.
        val langName = activeUserLocale.getDisplayLanguage(java.util.Locale.US)
            .ifBlank { activeUserLocale.language }
        val asrPrompt = "Transcribe the following speech segment in $langName into $langName text. " +
            "Follow these specific instructions for formatting the answer: " +
            "Only output the transcription, with no newlines. " +
            "When transcribing numbers, write the digits, i.e. write 1.7 and not one point seven, " +
            "and write 3 instead of three."
        return vlmEngine.transcribeAudio(
            audioBytes = audioBytes,
            prompt = asrPrompt,
            sessionId = ASR_SESSION_TAG,
            maxTokens = ASR_MAX_TOKENS
        )
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

        // Switch TTS voice to match detected language.
        // mirrorDetectedLocale normalizes ISO 639-3 STT codes (zlm→ms-MY,
        // cmn→zh) and enforces the unknown→English fallback — the raw
        // switchToLocale pass-through previously let unsupported tags
        // diverge the engine language state.
        mainHandler.post {
            ttsManager.mirrorDetectedLocale(detectedLocale)
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
        barcodeHelper.close()
        try { lastSnapshotBitmap?.recycle() } catch (_: Throwable) {}
        lastSnapshotBitmap = null
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

    /**
     * Detect generic pointing questions ("what is this", "apa ini", "这是什么").
     * The user is asking about the object in front of the camera. These get
     * the text path (384px + OCR pre-pass) because pointed-at objects usually
     * carry labels/packaging — OCR ground truth stops the model from guessing
     * a brand from a downscaled frame. Malay/Chinese equivalents included.
     */
    private fun isPointingQuery(query: String?): Boolean {
        if (query.isNullOrBlank()) return false
        val lower = query.lowercase()
        return POINTING_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    // ── Currency Reading (banknotes + coins) ────────────────────────

    /**
     * Detect currency queries ("what money is this", "read this note",
     * "berapa nilai duit ini"). These route through the high-resolution
     * text path and add no-guessing money rules to the prompt.
     */
    private fun isCurrencyQuery(query: String?): Boolean {
        if (query.isNullOrBlank()) return false
        val lower = query.lowercase()
        return CURRENCY_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    /**
     * Detect if the query is asking to identify a bank card.
     * Returns true for queries containing bank-card-related keywords
     * in English, Malay, or Chinese.
     */
    private fun isBankCardQuery(query: String?): Boolean {
        if (query.isNullOrBlank()) return false
        val lower = query.lowercase()
        return BANK_CARD_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    // ── Medicine Knowledge Base ──────────────────────────────────

    /**
     * Detect if the query is asking about medicine.
     * Returns true for queries containing medicine-related keywords
     * in English or Malay.
     */
    private fun isMedicineQuery(query: String?): Boolean {
        if (query.isNullOrBlank()) return false
        val lower = query.lowercase()
        return MEDICINE_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    /**
     * Look up a medicine from the local knowledge base by matching
     * OCR text against the database. Tries exact match first, then
     * fuzzy substring search.
     *
     * @return [MedicineEntity] if matched, null otherwise
     */
    private suspend fun lookupMedicine(ocrText: String): com.vyze.app.data.MedicineEntity? {
        val app = context as? android.app.Application ?: return null
        val medicineDao = (app as? VyzeApplication)?.medicineDao ?: return null

        // Normalize OCR text for matching
        val normalized = ocrText.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()

        // 1. Try exact search key match
        val exactMatch = medicineDao.findBySearchKey(normalized)
        if (exactMatch != null) return exactMatch

        // 2. Try fuzzy substring match — extract individual words and search
        val words = normalized.split(Regex("\\s+")).filter { it.length >= 3 }
        for (word in words) {
            val matches = medicineDao.searchByName(word)
            if (matches.isNotEmpty()) {
                // Return the first match (most relevant)
                return matches.first()
            }
        }

        return null
    }

    /**
     * Size the output-token budget to the text actually found by OCR.
     * The model mostly echoes the OCR block (~1 token per 4 chars) plus a
     * short intro, so a dense panel gets a proportionally large budget —
     * effectively unlimited for the text on the object — while short reads
     * and currency answers keep a small budget and finish fast.
     */
    private fun textQueryTokenBudget(ocrText: String?): Int {
        if (ocrText.isNullOrBlank()) return TEXT_QUERY_MAX_TOKENS_BASE
        val needed = TEXT_READING_OVERHEAD_TOKENS + (ocrText.length / OCR_CHARS_PER_OUTPUT_TOKEN)
        return needed.coerceIn(TEXT_QUERY_MAX_TOKENS_BASE, TEXT_QUERY_MAX_TOKENS_CEILING)
    }

    /**
     * Build a short "prior scene memory" snippet for prompt injection when the
     * current frame strongly resembles a RECENT past scan.
     *
     * Pure context — the model still analyzes the fresh frame; the memory only
     * adds continuity. Prefers the most RECENT eligible match, because a scene
     * description from seconds ago is far more trustworthy than one from hours
     * ago (the world may have changed).
     *
     * @return clipped prior description, or null when nothing is eligible.
     */
    private fun buildMemoryContext(similar: List<SimilarInteraction>): String? {
        val now = System.currentTimeMillis()
        val eligible = similar.filter {
            it.similarityScore >= MEMORY_INJECT_MIN_SIMILARITY &&
                (now - it.record.timestamp) <= MEMORY_INJECT_MAX_AGE_MS
        }
        val best = eligible.minByOrNull { now - it.record.timestamp } ?: return null
        val prior = best.record.output.trim()
        if (prior.length < 8) return null
        return if (prior.length > MEMORY_CONTEXT_MAX_CHARS) {
            prior.take(MEMORY_CONTEXT_MAX_CHARS).trimEnd() + "…"
        } else {
            prior
        }
    }

    // ── Dialogue Memory API ───────────────────────────────────────

    /** Called when the hands-free follow-up window OPENS. */
    fun onConversationWindowOpened() {
        conversationWindowOpen = true
    }

    /** Called when the follow-up window CLOSES — dialogue expires. */
    fun onConversationWindowClosed() {
        conversationWindowOpen = false
        if (dialogueTurns.isNotEmpty()) {
            dialogueTurns.clear()
            Log.d(TAG, "Dialogue memory expired (window closed)")
        }
    }

    /**
     * Record a completed voice exchange. Keeps at most [MAX_DIALOGUE_TURNS]
     * turns, each answer trimmed — bounded RAM, bounded prompt cost.
     */
    private fun recordDialogueTurn(userQuery: String, response: String) {
        val q = userQuery.trim().take(MAX_DIALOGUE_TURN_CHARS)
        val a = response.trim().take(MAX_DIALOGUE_TURN_CHARS)
        if (q.isBlank() || a.isBlank()) return
        synchronized(dialogueTurns) {
            while (dialogueTurns.size >= MAX_DIALOGUE_TURNS) dialogueTurns.removeFirst()
            dialogueTurns.addLast(q to a)
        }
        lastDialogueAt = System.currentTimeMillis()
    }

    /**
     * Snapshot of recent dialogue for prompt injection, or null when there is
     * nothing usable (no window open, expired TTL, or empty buffer).
     */
    private fun dialogueContextForPrompt(): String? {
        if (!conversationWindowOpen) return null
        if (dialogueTurns.isEmpty()) return null
        // TTL expiry: silence longer than DIALOGUE_TTL_MS invalidates context —
        // the conversation topic is almost certainly stale by then.
        if (System.currentTimeMillis() - lastDialogueAt > DIALOGUE_TTL_MS) {
            dialogueTurns.clear()
            return null
        }
        return synchronized(dialogueTurns) {
            dialogueTurns.joinToString("\n") { (q, a) -> "User: $q\nVyze: $a" }
        }
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
        /**
         * If the model emits this many characters without sentence-ending
         * punctuation, flush anyway so speech never stalls mid-generation.
         */
        private const val MAX_FLUSH_READ_AHEAD_CHARS = 200

        /**
         * Fast-start: speak the first word-aligned fragment once the lead-in
         * reaches this many characters (before the first sentence ends), so
         * first speech begins sooner. Must be long enough that the fragment
         * reads naturally as an opening clause. Lowered from 44 to 30 so
         * first audio arrives ~300-500ms sooner — the brief mid-sentence
         * pause is a worthwhile tradeoff for blind users who hear silence.
         */
        private const val FAST_START_CHARS = 30
        /** Minimum length of the fast-start fragment (avoids "Yes."-style clips). */
        private const val FAST_START_MIN_CHARS = 18

        // ── Dynamic Resolution Constants ──────────────────────────

        /**
         * Higher resolution for text extraction (384px captures fine text details,
         * balanced against prefill latency; 512px previously but OCR provides
         * ground truth so the model only needs to interpret — not read — the
         * image, making the smaller dimension safe and measurably faster).
         * Only used when OCR actually found text to echo.
         */
        private const val TEXT_EXTRACTION_DIMENSION = 384

        /**
         * Text queries where OCR found nothing readable — the model is doing
         * visual-only object ID (like a scene query), so 256px is adequate.
         * The old 384px was a hangover from when 512px was the main text
         * dimension; now that the post-OCR path uses 384, the no-OCR path
         * may as well use the scene budget (256px) for fastest prefill.
         */
        private const val TEXT_EXTRACTION_NO_OCR_DIMENSION = 256

        /** Standard resolution for scene queries (256x256 for fast inference). */
        private const val SCENE_QUERY_DIMENSION = 256

        // ── Dynamic Token Limits ────────────────────────────────
        /** Scene queries: concise descriptions (raised — avoids mid-sentence cutoffs). */
        private const val SCENE_QUERY_MAX_TOKENS = 128

        /** Max dialogue turns kept for conversational follow-ups. */
        private const val MAX_DIALOGUE_TURNS = 2

        /** Per-turn char cap — keeps the injected block ~120-150 tokens. */
        private const val MAX_DIALOGUE_TURN_CHARS = 100

        /** Dialogue expires after this much silence even mid-window. */
        private const val DIALOGUE_TTL_MS = 60_000L

        /**
         * Continuous auto-captures with cosine similarity at or above this vs
         * the last SPOKEN scene skip Gemma entirely (scene-unchanged skip).
         * Conservative — a slightly-different scene still re-describes.
         */
        private const val CONTINUOUS_SKIP_SIMILARITY = 0.9f

        /**
         * Floor for text queries with no OCR text found (scene tap with no
         * readable text, blurry label, etc.). Generous but bounded.
         */
        private const val TEXT_QUERY_MAX_TOKENS_BASE = 192

        /**
         * Hard ceiling for text reads. The model context + engine limits
         * (180s inference timeout) bound any real generation anyway, so this
         * (~800 words of output) is as close to "unlimited" as the stack allows.
         */
        private const val TEXT_QUERY_MAX_TOKENS_CEILING = 1024

        /** Rough output tokens needed to echo OCR text verbatim (~1 per 4 chars). */
        private const val OCR_CHARS_PER_OUTPUT_TOKEN = 4

        /** Extra output budget for the model's intro/outro around the read text. */
        private const val TEXT_READING_OVERHEAD_TOKENS = 64

        // ── Memory Context Injection ────────────────────────────
        /** Similarity bar for treating a past scan as "the same scene". */
        private const val MEMORY_INJECT_MIN_SIMILARITY = 0.6f
        /** Only inject memories from scans within this window (24h). */
        private const val MEMORY_INJECT_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        /** Cap injected snippet length to keep prompts lean. */
        private const val MEMORY_CONTEXT_MAX_CHARS = 240

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
            "packaging", "package", "packet", "wrapper", "bottle", "jar",
            // Spoken reading asks
            "what does it say", "what does this say", "does it say",
            "does this say", "what's written", "what is written",
            "what is printed", "printed on", "written on", "on the label",
            "on the packaging", "can you read", "read out", "read aloud",
            // Malay / Bahasa Melayu
            "baca", "harga", "ramuan", "resipi", "ubat",
            "dos", "arahan", "alamat", "telefon", "nota",
            "menu", "surat", "tulisan", "nombor", "nama",
            "tertulis", "ditulis", "bertulis", "ada tulis",
            "bungkusan", "pembungkusan", "pekej", "botol", "tin",
            // Chinese
            "写的是什么", "写着什么", "上面写着", "上面写", "包装", "瓶", "罐"
        )

        /**
         * Deictic pointing questions — the user is asking about the object in
         * front of the camera ("what is this", "apa ini", "这是什么"). Only
         * phrases with an explicit pointer (this/that/ini/itu/这/那/holding)
         * qualify — a knowledge question like "what is paracetamol" must stay
         * on the text-only path.
         */
        private val POINTING_KEYWORDS = listOf(
            // English
            "what is this", "what's this", "what is that", "what's that",
            "what is this thing", "what's this thing", "what is this object",
            "what is this item", "what is this packet", "what is this box",
            "what is this bottle", "what is this can", "what is in my hand",
            "what is in my hands", "what am i holding", "what am i looking at",
            "this thing", "this object", "this item", "this packet", "this box",
            // Malay / Bahasa Melayu
            "ini apa", "apa ini", "ni apa", "itu apa", "apa itu",
            "benda apa ini", "apa benda ini", "ini benda apa", "benda apa",
            "barang apa ini", "apa barang ini", "apa yang saya pegang",
            "apa yang saya ada", "saya pegang apa", "apa yang di tangan",
            // Chinese
            "这是什么", "这个是什么", "那是什么", "那个是什么", "前面是什么"
        )

        /** Marker in tap queries — "User tapped at position (x, y)". */
        private const val TAP_POSITION_MARKER = "tapped at position"

        /** Age limit (ms) for "tell me more" on the retained frame. */
        private const val EXPANSION_MAX_AGE_MS = 90_000L

        /** Max dimension of the retained frame kept for "tell me more". */
        private const val RETAINED_FRAME_DIM = 512

        /** Keywords that trigger currency reading (banknotes + coins). */
        private val CURRENCY_KEYWORDS = listOf(
            // English
            "money", "banknote", "bank note", "banknotes", "cash",
            "currency", "ringgit", "coin", "coins",
            // Malay / Bahasa Melayu
            "wang", "duit", "wang kertas", "wang syiling", "duit syiling",
            "syiling", "koin",
            // Chinese
            "钱", "钞票", "纸币", "硬币", "钱币", "多少钱"
        )

        /** Keywords that trigger bank card identification. */
        private val BANK_CARD_KEYWORDS = listOf(
            // English
            "bank card", "debit card", "credit card", "atm card",
            "card", "what card", "which bank",
            // Malay / Bahasa Melayu
            "kad bank", "kad debit", "kad kredit", "kad atm",
            "kad", "kad apa", "bank apa",
            // Chinese
            "银行卡", "借记卡", "信用卡", "什么卡", "哪家银行"
        )

        /** Keywords that trigger medicine database lookup. */
        private val MEDICINE_KEYWORDS = listOf(
            "medicine", "medication", "drug", "pill", "tablet",
            "capsule", "dosage", "prescription", "pharmacy",
            "ubat", "dos", "ubat apa", "jenis ubat"
        )

        // ── Confidence Check Constants ───────────────────────────
        /** Number of characters to accumulate before checking for hedging. */
        private const val CONFIDENCE_CHECK_CHARS = 30

        /** Hedging phrases that indicate low model confidence. */
        private val HEDGING_PHRASES = listOf(
            "i think", "maybe", "it looks like", "it appears",
            "possibly", "might be", "could be", "not sure",
            "hard to tell", "unclear", "difficult to determine",
            "not certain", "seems like", "i guess"
        )

        // ── Text-Only Q&A ───────────────────────────────────────
        /** Output cap for general-knowledge answers (concise for TTS). */
        private const val TEXT_ONLY_MAX_TOKENS = 192

        /**
         * Knowledge-question markers that route to TEXT-ONLY inference.
         * Multi-language: English + Malay + Chinese.
         */
        private val TEXT_ONLY_QUERY_KEYWORDS = listOf(
            // English
            "what is", "what are", "who is", "who are", "why is",
            "why do", "how do", "how to", "how does", "when is",
            "when do", "where is", "meaning of", "definition of",
            "tell me about", "explain", "what does", "what's the difference",
            // Malay / Bahasa Melayu
            "apa itu", "apa maksud", "siapa", "kenapa", "bagaimana",
            "bila", "di mana", "maksud", "ceritakan", "terangkan",
            // Chinese
            "是什么", "什么意思", "为什么", "怎么", "如何", "谁", "在哪里"
        )

        /**
         * Scene-referencing words that FORCE the camera pipeline instead of
         * text-only. If the user says "this", "here", "in front" etc., they
         * are pointing at something — text-only would be wrong.
         */
        private val TEXT_ONLY_EXCLUDE_KEYWORDS = listOf(
            // English
            "this", "that", "these", "those", "here", "there",
            "in front", "in front of me", "around me", "in the room",
            "what is this", "what's this", "this thing", "this object",
            "near me", "see", "look", "point", "show me",
            // Malay / Bahasa Melayu
            "ini", "itu", "di hadapan", "sekitar", "sini", "sana",
            "benda ini", "objek ini", "lihat", "nampak",
            // Chinese
            "这个", "那个", "这里", "那里", "前面", "这个东西"
        )

        /**
         * Session tag for model-native ASR transcriptions. A DISTINCT,
         * non-empty id that can never collide with a real query session UUID:
         * the session-gated token/complete/error handlers drop any callback
         * whose id differs from activeSessionId, so a rescue transcription
         * must NOT be passed as "" (empty ids slip through the gate and would
         * stream / speak the raw transcription as if it were the VLM answer).
         */
        private const val ASR_SESSION_TAG = "vyze-model-asr-rescue"

        /** Output cap for model-native speech transcriptions (short). */
        private const val ASR_MAX_TOKENS = 96
    }
}
