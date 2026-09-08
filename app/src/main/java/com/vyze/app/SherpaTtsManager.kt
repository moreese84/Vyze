package com.vyze.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Sherpa-ONNX TTS manager supporting multiple models:
 * - Kokoro: English + Chinese (neural quality, ~9/10)
 * - Meta MMS: Malay + multilingual (functional quality, ~7/10)
 *
 * Auto-selects the best model for the active language.
 * Falls back gracefully when models are not yet downloaded.
 *
 * ## Queueing
 * [speak] appends to an internal queue and a single drain job generates and
 * plays utterances strictly in order — the equivalent of Android TTS
 * QUEUE_ADD. Passing `flush = true` (QUEUE_FLUSH) cancels the current
 * utterance, clears the queue, and starts the new one immediately.
 *
 * ## Completion
 * Each utterance's `onDone` callback fires on the main thread after its audio
 * has actually finished playing (or immediately on failure), so callers get
 * accurate end-of-speech signals instead of polling.
 */
class SherpaTtsManager(private val context: Context) {

    private val TAG = "SherpaTtsManager"

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val drainLock = Any()
    private var drainJob: Job? = null

    /** Bumped on every stop(); lets in-flight playback detect cancellation. */
    private val playbackGen = AtomicLong(0)

    // ── Model Instances ─────────────────────────────────────────
    private var kokoroTts: OfflineTts? = null
    private var mmsTts: OfflineTts? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    private var initializeStarted = false

    // Multiple TTSManager instances share this manager, so ready listeners are
    // a list, not a single slot — one instance must not erase another's.
    private val readyListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * Register a callback for when model loading finishes (success or partial).
     * If models are already loaded, the callback fires immediately.
     */
    fun addOnReady(listener: () -> Unit) {
        readyListeners.add(listener)
        if (isReady()) listener()
    }

    // ── Playback State ──────────────────────────────────────────
    private val isPlaying = AtomicBoolean(false)
    @Volatile
    private var currentVolume: Float = 1.0f

    // ── Speak Queue ─────────────────────────────────────────────
    private class QueueItem(
        val text: String,
        val locale: Locale,
        val speed: Float,
        val volume: Float,
        val onStart: (() -> Unit)?,
        val onDone: (() -> Unit)?
    )

    private val speakQueue = ConcurrentLinkedQueue<QueueItem>()

    // ── Model Paths ─────────────────────────────────────────────
    private val modelBaseDir: File
        get() = File(context.getExternalFilesDir(null), "sherpa-models")

    private val kokoroModelDir: File
        get() = File(modelBaseDir, "kokoro")

    private val mmsModelDir: File
        get() = File(modelBaseDir, "mms")

    // ── Public API ──────────────────────────────────────────────

    /**
     * Initialize the TTS engine. Loads available models.
     * Idempotent: repeated calls while loading or after loading are no-ops.
     * Call once at app startup.
     */
    fun initialize() {
        if (initializeStarted) return
        initializeStarted = true
        // release() cancels the scope permanently; give re-init a live one.
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
        scope.launch {
            try {
                loadKokoroModel()
                loadMmsModel()
                isInitialized = true
                Log.i(TAG, "Sherpa TTS initialized — " +
                    "Kokoro=${kokoroTts != null}, MMS=${mmsTts != null}")
                readyListeners.forEach { listener ->
                    withContext(Dispatchers.Main) { listener() }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Sherpa TTS init failed: ${e.message}")
            }
        }
    }

    /**
     * Check if Sherpa TTS is ready to use.
     */
    fun isReady(): Boolean = isInitialized && (kokoroTts != null || mmsTts != null)

    /**
     * Check if Kokoro model is available (EN/ZH neural quality).
     */
    fun isKokoroAvailable(): Boolean = kokoroTts != null

    /**
     * Check if MMS model is available (Malay).
     */
    fun isMmsAvailable(): Boolean = mmsTts != null

    /**
     * Speak text in the specified language.
     * Auto-selects Kokoro for EN/ZH, MMS for Malay.
     *
     * @param text   The text to speak
     * @param locale The target language locale
     * @param speed  Speech speed multiplier (1.0 = normal)
     * @param volume Output volume multiplier (0..1)
     * @param onStart Invoked on the main thread when this utterance starts
     *                processing. Not invoked if cancelled by [stop].
     * @param onDone Invoked on the main thread when this utterance finishes
     *               playing (or fails). Not invoked if cancelled by [stop].
     * @param flush  True to cancel current + queued speech before speaking
     *               (QUEUE_FLUSH semantics); false to append (QUEUE_ADD).
     */
    fun speak(
        text: String,
        locale: Locale = Locale.US,
        speed: Float = 1.0f,
        volume: Float = 1.0f,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        flush: Boolean = false
    ) {
        if (flush) stop()

        if (!isReady()) {
            Log.w(TAG, "Sherpa TTS not ready — dropping utterance")
            onDone?.let { cb -> scope.launch { withContext(Dispatchers.Main) { cb() } } }
            return
        }

        speakQueue.add(QueueItem(text, locale, speed, volume.coerceIn(0f, 1f), onStart, onDone))
        startDrainIfIdle()
    }

    /**
     * Stop any current speech playback and clear the speak queue.
     * In-flight utterances are cancelled; their onDone callbacks do NOT fire.
     *
     * Does not touch the AudioTrack directly: the playback job observes the
     * generation bump within ~10 ms, exits its write loop, and its finally
     * block calls track.stop() — which discards buffered audio and silences
     * the speaker immediately. Touching the track from here would race with
     * an in-flight write.
     */
    fun stop() {
        playbackGen.incrementAndGet()
        synchronized(drainLock) {
            drainJob?.cancel()
            drainJob = null
        }
        speakQueue.clear()
        isPlaying.set(false)
    }

    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
    }

    /**
     * Check if audio is currently playing.
     */
    fun isSpeaking(): Boolean = isPlaying.get()

    /**
     * Release all resources. Models can be reloaded afterwards by calling
     * [initialize] again (the singleton instance survives app-level restarts
     * of the TTS pipeline).
     */
    fun release() {
        stop()
        kokoroTts?.release()
        kokoroTts = null
        mmsTts?.release()
        mmsTts = null
        isInitialized = false
        initializeStarted = false
        scope.cancel()
    }

    // ── Queue Drain ─────────────────────────────────────────────

    private fun startDrainIfIdle() {
        synchronized(drainLock) {
            if (drainJob?.isActive != true) {
                drainJob = scope.launch { drainQueue() }
            }
        }
    }

    private suspend fun drainQueue() {
        while (true) {
            val item = speakQueue.poll()
            if (item == null) {
                // Re-check: an item may have been appended between the empty
                // poll and this job finishing — don't strand it queueless.
                if (speakQueue.isEmpty()) break
                continue
            }
            val gen = playbackGen.get()
            try {
                withContext(Dispatchers.Main) { item.onStart?.invoke() }

                val tts = selectModelForLocale(item.locale)
                if (tts == null) {
                    Log.w(TAG, "No TTS model available for locale: ${item.locale}")
                    withContext(Dispatchers.Main) { item.onDone?.invoke() }
                    continue
                }

                val audio = withContext(Dispatchers.Default) {
                    tts.generate(item.text, speed = item.speed)
                }
                yield() // cancellation checkpoint
                if (gen != playbackGen.get()) return // stopped — queue was flushed

                if (audio != null && audio.samples.isNotEmpty()) {
                    playAudio(audio.samples, audio.sampleRate, gen, item.volume)
                } else {
                    Log.w(TAG, "TTS generation returned empty audio")
                }

                if (gen == playbackGen.get()) {
                    withContext(Dispatchers.Main) { item.onDone?.invoke() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // stop() cancelled us — normal, not an error
            } catch (e: Throwable) {
                Log.e(TAG, "TTS speak failed: ${e.message}")
                if (gen == playbackGen.get()) {
                    withContext(Dispatchers.Main) { item.onDone?.invoke() }
                }
            }
        }
    }

    // ── Model Loading ───────────────────────────────────────────

    private fun loadKokoroModel() {
        // Try external storage first, then extract from assets
        var modelFile: File? = File(kokoroModelDir, "model.onnx")
        var tokensFile: File? = File(kokoroModelDir, "tokens.txt")
        var voicesFile: File? = File(kokoroModelDir, "voices.bin")

        if (modelFile?.exists() != true || tokensFile?.exists() != true) {
            Log.i(TAG, "Kokoro not in external storage — extracting from assets")
            modelFile = extractAsset("kokoro/model.onnx", "kokoro")
            tokensFile = extractAsset("kokoro/tokens.txt", "kokoro")
            voicesFile = extractAsset("kokoro/voices.bin", "kokoro")
            // Recursively extract espeak-ng-data subtree (phontab, phon dictionaries, etc.)
            extractAssetTree("kokoro/espeak-ng-data", "kokoro/espeak-ng-data")
        }

        if (modelFile == null || !modelFile.exists() || tokensFile == null || !tokensFile.exists()) {
            Log.w(TAG, "Kokoro model not found")
            return
        }

        try {
            val modelDir = modelFile.parentFile?.absolutePath ?: ""
            val voicesPath = if (voicesFile?.exists() == true) voicesFile.absolutePath else ""
            val espeakDir = File(modelDir, "espeak-ng-data").absolutePath
            Log.i(TAG, "Kokoro model: ${modelFile.absolutePath}")
            Log.i(TAG, "Kokoro tokens: ${tokensFile.absolutePath}")
            Log.i(TAG, "Kokoro voices: $voicesPath (exists=${voicesFile?.exists()})")
            Log.i(TAG, "Kokoro dataDir: $espeakDir (exists=${File(espeakDir).exists()})")
            val config = OfflineTtsConfig(
                model = modelFile.absolutePath,
                tokens = tokensFile.absolutePath,
                dataDir = espeakDir,
                voices = voicesPath,
                numThreads = 2,
                debug = true
            )
            kokoroTts = OfflineTts(config)
            val sr = kokoroTts?.sampleRate() ?: -1
            Log.i(TAG, "Kokoro model loaded — sampleRate=$sr")
            if (sr <= 0) {
                Log.w(TAG, "Kokoro model returned invalid sample rate — unloading")
                kokoroTts?.release()
                kokoroTts = null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load Kokoro: ${e.javaClass.simpleName} — ${e.message}")
            CrashLogFile.logError(TAG, "Kokoro load failed", e)
            kokoroTts = null
        }
    }

    private fun loadMmsModel() {
        // Try external storage first, then extract from assets
        var modelFile: File? = File(mmsModelDir, "model.onnx")
        var tokensFile: File? = File(mmsModelDir, "tokens.txt")

        if (modelFile?.exists() != true || tokensFile?.exists() != true) {
            Log.i(TAG, "MMS not in external storage — extracting from assets")
            modelFile = extractAsset("mms/model.onnx", "mms")
            tokensFile = extractAsset("mms/tokens.txt", "mms")
        }

        if (modelFile == null || !modelFile.exists() || tokensFile == null || !tokensFile.exists()) {
            Log.w(TAG, "MMS model not found")
            return
        }

        try {
            val modelDir = modelFile.parentFile?.absolutePath ?: ""
            Log.i(TAG, "MMS model: ${modelFile.absolutePath}")
            Log.i(TAG, "MMS tokens: ${tokensFile.absolutePath}")
            // dataDir intentionally empty for MMS — Sherpa would otherwise look
            // for phontab/phondata files (espeak-ng lexicon data) that the Meta
            // MMS VITS model does not provide or require.
            val config = OfflineTtsConfig.forVits(
                model = modelFile.absolutePath,
                tokens = tokensFile.absolutePath,
                dataDir = "",
                numThreads = 2,
                debug = true
            )
            mmsTts = OfflineTts(config)
            Log.i(TAG, "MMS model loaded — sampleRate=${mmsTts?.sampleRate()}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load MMS: ${e.message}")
            mmsTts = null
        }
    }

    private fun extractAsset(assetPath: String, subDir: String): File? {
        return try {
            val targetDir = File(modelBaseDir, subDir)
            targetDir.mkdirs()
            val targetFile = File(targetDir, assetPath.substringAfterLast("/"))
            if (targetFile.exists()) return targetFile

            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Extracted asset: $assetPath → ${targetFile.absolutePath}")
            targetFile
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to extract asset $assetPath: ${e.message}")
            null
        }
    }

    /**
     * Recursively extract a directory tree from APK assets to internal storage.
     * Sherpa's Kokoro model expects an espeak-ng-data subtree with phontab,
     * phondata, and per-language dictionary files at [dataDir]/espeak-ng-data/.
     *
     * @param assetPrefix  Prefix inside src/main/assets/, e.g. "kokoro/espeak-ng-data"
     * @param subDir       Relative subdirectory under [modelBaseDir], e.g. "kokoro/espeak-ng-data"
     */
    private fun extractAssetTree(assetPrefix: String, subDir: String) {
        try {
            val targetDir = File(modelBaseDir, subDir)
            targetDir.mkdirs()
            val entries = try { context.assets.list(assetPrefix) } catch (_: Throwable) { null }
            if (entries.isNullOrEmpty()) {
                Log.w(TAG, "extractAssetTree: empty or missing asset prefix '$assetPrefix'")
                return
            }
            for (entry in entries) {
                val assetChild = "$assetPrefix/$entry"
                val targetChild = File(targetDir, entry)
                // Try to open as a file first — if it fails, treat as a subdirectory
                try {
                    if (targetChild.exists()) continue
                    context.assets.open(assetChild).use { input ->
                        targetChild.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Extracted: $assetChild → ${targetChild.absolutePath}")
                } catch (_: java.io.FileNotFoundException) {
                    // Not a file — recurse as subdirectory
                    extractAssetTree(assetChild, "$subDir/$entry")
                } catch (_: Throwable) {
                    // Might still be a directory Android couldn't list — try recursing
                    extractAssetTree(assetChild, "$subDir/$entry")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "extractAssetTree failed for '$assetPrefix': ${e.message}")
        }
    }

    // ── Model Selection ─────────────────────────────────────────

    /**
     * True when a loaded model can speak the given language key
     * ("en" / "ms" / "zh"), mirroring [selectModelForLocale]'s fallbacks.
     */
    fun hasVoiceFor(language: String): Boolean {
        val locale = when (language) {
            "ms" -> Locale("ms", "MY")
            "zh" -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.US
        }
        return selectModelForLocale(locale) != null
    }

    private fun selectModelForLocale(locale: Locale): OfflineTts? {
        return when (locale.language) {
            "en" -> kokoroTts ?: mmsTts  // Prefer Kokoro for English
            "zh" -> kokoroTts ?: mmsTts  // Prefer Kokoro for Chinese
            "ms" -> mmsTts ?: kokoroTts  // MMS for Malay (only option)
            else -> mmsTts ?: kokoroTts  // Fallback to MMS for other languages
        }
    }

    // ── Audio Playback ──────────────────────────────────────────

    /**
     * Stream [samples] to an AudioTrack and wait until the hardware has
     * actually played them. Cancellable two ways: [stop] bumps the playback
     * generation (checked between chunks) and flips [isPlaying]; both exit the
     * loops early so barge-in is responsive.
     */
    private fun playAudio(samples: FloatArray, sampleRate: Int, gen: Long, volume: Float) {
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            if (minBuf <= 0) {
                Log.w(TAG, "playAudio: invalid min buffer size $minBuf")
                return
            }
            val bufferSize = maxOf(minBuf, sampleRate / 2 * 4) // at least 0.5s

            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t

            t.play()
            isPlaying.set(true)

            // Stream scaled samples in chunks with NON_BLOCKING writes and a
            // short retry — a blocking write can never hang barge-in because
            // every iteration re-checks the playback generation first.
            val chunkFloats = 4096
            var offset = 0
            while (offset < samples.size && isPlaying.get() && gen == playbackGen.get()) {
                val end = minOf(offset + chunkFloats, samples.size)
                val bytes = (end - offset) * 4
                val byteBuffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val floatBuffer = byteBuffer.asFloatBuffer()
                for (k in offset until end) floatBuffer.put(samples[k] * volume)

                var bufOffset = 0
                while (bufOffset < bytes && isPlaying.get() && gen == playbackGen.get()) {
                    val written = t.write(
                        byteBuffer.array(), bufOffset, bytes - bufOffset,
                        AudioTrack.WRITE_NON_BLOCKING
                    )
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack.write failed (device may be disconnected): $written")
                        return
                    }
                    if (written == 0) {
                        Thread.sleep(10) // buffer full — retry shortly
                    } else {
                        bufOffset += written
                    }
                }
                offset = end
            }

            // Wait for the hardware to drain what was written — playbackHeadPosition
            // reaches totalFrames only when the speaker has finished the utterance.
            if (isPlaying.get() && gen == playbackGen.get()) {
                val totalFrames = samples.size
                while (isPlaying.get() && gen == playbackGen.get()) {
                    if (t.playbackHeadPosition >= totalFrames) break
                    Thread.sleep(50)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Audio playback failed: ${e.message}")
        } finally {
            // track.stop() discards anything still buffered — barge-in is
            // silent immediately, not after the buffer drains.
            try { track?.stop() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
            // Only the current-generation playback may clear the global playing
            // flag — a cancelled predecessor must not stomp a successor's state.
            if (gen == playbackGen.get()) {
                isPlaying.set(false)
            }
        }
    }

    // ── Model Download ──────────────────────────────────────────

    companion object {
        /**
         * Shared instance. Two TTSManager singletons exist in the app
         * (VyzeApplication + TtsViewModel); without sharing, each would load
         * its own copy of the 325 MB Kokoro + 114 MB MMS models — roughly a
         * gigabyte of RAM and a likely OOM on low-end devices.
         */
        @Volatile
        private var shared: SherpaTtsManager? = null

        fun getInstance(context: Context): SherpaTtsManager {
            return shared ?: synchronized(this) {
                shared ?: SherpaTtsManager(context.applicationContext).also { shared = it }
            }
        }

        /**
         * Check if all required models are downloaded.
         */
        fun areModelsDownloaded(context: Context): Boolean {
            val baseDir = File(context.getExternalFilesDir(null), "sherpa-models")
            val kokoroModel = File(baseDir, "kokoro/model.onnx")
            val mmsModel = File(baseDir, "mms/model.onnx")
            return kokoroModel.exists() && mmsModel.exists()
        }

        /**
         * Get the model directory path for showing in UI.
         */
        fun getModelPath(context: Context): String {
            return File(context.getExternalFilesDir(null), "sherpa-models").absolutePath
        }
    }
}
