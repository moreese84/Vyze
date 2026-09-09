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

    /** True once native lib load was attempted. */
    private var nativeLibChecked = false
    private var nativeLibOk = false

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

    // ── Model Initialization (off-thread, lazy) ─────────────────

    /**
     * Ensure the native JNI library is loaded. Safe to call multiple times.
     * Returns true if the library is loaded and usable.
     */
    private fun ensureNativeLoaded(): Boolean {
        if (nativeLibChecked) return nativeLibOk
        nativeLibChecked = true
        val loaded = OfflineTts.ensureLoaded()
        nativeLibOk = loaded
        if (!loaded) {
            Log.e(TAG, "sherpa-onnx-jni native library failed to load — TTS unavailable")
        }
        return loaded
    }

    /**
     * Attempt to initialize TTS models (Kokoro + MMS). Runs on [Dispatchers.IO]
     * so any native exit(255) inside libsherpa-onnx-jni.so does not block the
     * main thread or crash during app startup.
     *
     * Models are loaded lazily — the first [speak] call triggers this. The
     * caller's onDone callback fires (on the main thread) regardless of outcome
     * so the app never hangs waiting for TTS.
     */
    private var initLaunched = false
    private val initLock = Any()

    private fun ensureModelsLoaded() {
        if (isInitialized) return
        synchronized(initLock) {
            if (initLaunched) return
            initLaunched = true
        }
        scope.launch {
            try {
                // Run asset extraction and model init on IO thread — the native
                // exit(255) inside newFromFile would kill the process regardless,
                // but at least it won't happen during onCreate/onViewCreated.
                withContext(Dispatchers.IO) {
                    if (!ensureNativeLoaded()) return@withContext

                    // Extract assets if needed (file I/O)
                    extractKokoroAssets()
                    extractMmsAssets()

                    // Attempt to load each model; both may fail gracefully
                    loadKokoroModel()
                    loadMmsModel()

                    isInitialized = true
                    Log.i(TAG, "Sherpa TTS init — Kokoro=${kokoroTts != null}, MMS=${mmsTts != null}")
                    if (kokoroTts != null) Log.i(TAG, "Kokoro sampleRate=${kokoroTts?.sampleRate() ?: -1} Hz")
                    if (mmsTts != null) Log.i(TAG, "MMS sampleRate=${mmsTts?.sampleRate() ?: -1} Hz")
                }
                readyListeners.forEach { l -> withContext(Dispatchers.Main) { l() } }
            } catch (e: Throwable) {
                Log.e(TAG, "TTS init failed: ${e.javaClass.simpleName}: ${e.message}")
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

        // Lazy init: first speak call triggers model loading on IO thread.
        // If not ready yet, the utterance is queued and drain will retry.
        if (!isInitialized) {
            ensureModelsLoaded()
        }

        if (!isReady()) {
            Log.w(TAG, "Sherpa TTS not ready — utterance will be buffered")
            if (onDone != null) {
                scope.launch { withContext(Dispatchers.Main) { onDone() } }
            }
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
        Log.d(TAG, "Kokoro available=${isKokoroAvailable()}, MMS available=${isMmsAvailable()}, isInitialized=$isInitialized")
                    withContext(Dispatchers.Main) { item.onDone?.invoke() }
                    continue
                }

                Log.d(TAG, "Synthesizing text: \"${item.text.take(80)}\" (len=${item.text.length}, locale=${item.locale})")
                val audio = withContext(Dispatchers.Default) {
                    tts.generate(item.text, speed = item.speed)
                }
                yield() // cancellation checkpoint
                if (gen != playbackGen.get()) return // stopped — queue was flushed

                if (audio != null && audio.samples.isNotEmpty()) {
                    Log.d(TAG, "Synthesis OK — samples=${audio.samples.size}, sampleRate=${audio.sampleRate}, duration=${audio.samples.size / audio.sampleRate}s")
                    playAudio(audio.samples, audio.sampleRate, gen, item.volume)
                } else {
                    Log.w(TAG, "TTS generation returned empty audio — null=${audio == null}, samples=${audio?.samples?.size}")
                    if (audio != null) {
                        Log.w(TAG, "samples.size=${audio.samples.size}, sampleRate=${audio.sampleRate}")
                    }
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

    // ── Asset Extraction ────────────────────────────────────────

    /**
     * Extract all Kokoro model files from APK assets to external storage.
     * Safe to call multiple times — skips existing files.
     * Runs on IO thread.
     */
    private fun extractKokoroAssets() {
        extractAsset("sherpa-models/kokoro/model.onnx", "kokoro")
        extractAsset("sherpa-models/kokoro/tokens.txt", "kokoro")
        extractAsset("sherpa-models/kokoro/voices.bin", "kokoro")
        extractAssetTree("sherpa-models/kokoro/espeak-ng-data", "kokoro/espeak-ng-data")
    }

    /**
     * Extract all MMS model files from APK assets to external storage.
     */
    private fun extractMmsAssets() {
        extractAsset("sherpa-models/mms/model.onnx", "mms")
        extractAsset("sherpa-models/mms/tokens.txt", "mms")
    }

    // ── Kokoro Model (EN/ZH) ───────────────────────────────────

    private fun loadKokoroModel() {
        val modelFile = File(kokoroModelDir, "model.onnx")
        val tokensFile = File(kokoroModelDir, "tokens.txt")
        val voicesFile = File(kokoroModelDir, "voices.bin")
        val espeakDir = File(kokoroModelDir, "espeak-ng-data")
        val phontabFile = File(espeakDir, "phontab")
        val phondataFile = File(espeakDir, "phondata")

        // ── PRE-FLIGHT VALIDATION ───────────────────────────────
        // libsherpa-onnx-jni.so calls ::exit(255) from C++ when any expected
        // file is missing or empty. A Kotlin try-catch cannot intercept this.
        // If validation fails, log detailed error and abort — never call JNI.

        val missingFiles = mutableListOf<String>()
        if (!modelFile.exists()) missingFiles.add("model.onnx (missing)")
        else if (modelFile.length() < 1_000_000L) missingFiles.add("model.onnx (${modelFile.length()} bytes — expected >1MB)")
        if (!tokensFile.exists()) missingFiles.add("tokens.txt (missing)")
        else if (tokensFile.length() == 0L) missingFiles.add("tokens.txt (empty)")
        if (!voicesFile.exists()) missingFiles.add("voices.bin (missing)")
        else if (voicesFile.length() < 10_000L) missingFiles.add("voices.bin (${voicesFile.length()} bytes — expected >10KB)")
        if (!espeakDir.isDirectory) missingFiles.add("espeak-ng-data/ (not a directory)")
        else {
            if (!phontabFile.exists()) missingFiles.add("espeak-ng-data/phontab (missing)")
            if (!phondataFile.exists()) missingFiles.add("espeak-ng-data/phondata (missing)")
        }

        if (missingFiles.isNotEmpty()) {
            Log.w(TAG, "Kokoro pre-flight FAILED — ${missingFiles.size} issue(s):")
            missingFiles.forEach { Log.w(TAG, "  $it") }
            // Auto-repair: clear and re-extract on next app launch
            Log.w(TAG, "Clearing kokoro model directory for re-extraction")
            kokoroModelDir.deleteRecursively()
            return
        }

        Log.i(TAG, "Kokoro pre-flight OK — model=${modelFile.length()/1024/1024}MB, voices.bin=${voicesFile.length()/1024}KB, espeak-ng-data/ has phontab+phondata")

        try {
            val modelPath = modelFile.absolutePath
            val tokensPath = tokensFile.absolutePath
            val voicesPath = voicesFile.absolutePath
            val espeakPath = espeakDir.absolutePath
            Log.i(TAG, "Calling OfflineTts(config) for Kokoro")
            Log.i(TAG, "  model: $modelPath")
            Log.i(TAG, "  tokens: $tokensPath")
            Log.i(TAG, "  voices: $voicesPath")
            Log.i(TAG, "  dataDir: $espeakPath")

            val kokoroCfg = com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig(
                model = modelPath,
                voices = voicesPath,
                tokens = tokensPath,
                dataDir = espeakPath,
                lengthScale = 1.0f,
                lexicon = "",
                lang = "",
                dictDir = ""
            )
            val modelCfg = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
                vits = com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(),
                matcha = com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig(),
                kokoro = kokoroCfg,
                zipvoice = com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig(),
                kitten = com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig(),
                pocket = com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig(),
                supertonic = com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig(),
                numThreads = 2,
                debug = true,
                provider = "cpu"
            )
            val ttsConfig = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
                model = modelCfg,
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 0.2f
            )

            // JNI call — may call exit(255) internally. We cannot catch it,
            // but by this point all files are verified. If the process exits,
            // it's a C++ bug in this .so build.
            kokoroTts = OfflineTts(ttsConfig)
            val sr = kokoroTts?.sampleRate() ?: -1
            Log.i(TAG, "Kokoro model loaded — sampleRate=$sr Hz")
            if (sr <= 0) {
                Log.w(TAG, "Kokoro returned sampleRate=$sr — unloading")
                kokoroTts?.release()
                kokoroTts = null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Kokoro load failed: ${e.javaClass.simpleName}: ${e.message}")
            CrashLogFile.logError(TAG, "Kokoro load failed", e)
            kokoroTts = null
        }
    }

    // ── MMS Model (Malay) ────────────────────────────────────────

    private fun loadMmsModel() {
        val modelFile = File(mmsModelDir, "model.onnx")
        val tokensFile = File(mmsModelDir, "tokens.txt")

        if (!modelFile.exists() || !tokensFile.exists()) {
            Log.w(TAG, "MMS model files not found")
            return
        }

        try {
            Log.i(TAG, "Calling OfflineTts(config) for MMS")
            val config = OfflineTtsConfig.forVits(
                model = modelFile.absolutePath,
                tokens = tokensFile.absolutePath,
                dataDir = "",
                numThreads = 2,
                debug = true
            )
            mmsTts = OfflineTts(config)
            val sr = mmsTts?.sampleRate() ?: -1
            Log.i(TAG, "MMS model loaded — sampleRate=$sr Hz")
            if (sr <= 0) {
                Log.w(TAG, "MMS returned sampleRate=$sr — unloading")
                mmsTts?.release()
                mmsTts = null
            }
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
     *
     * Sherpa-ONNX Kokoro outputs 32-bit float PCM samples at the model's native
     * sample rate (typically 24000 Hz). The AudioTrack is configured with
     * ENCODING_PCM_FLOAT and the dynamic sample rate from the generated audio.
     */
    private fun playAudio(samples: FloatArray, sampleRate: Int, gen: Long, volume: Float) {
        if (samples.isEmpty()) {
            Log.w(TAG, "playAudio: samples array is empty — nothing to play")
            return
        }
        if (sampleRate <= 0) {
            Log.w(TAG, "playAudio: invalid sampleRate=$sampleRate — cannot play")
            return
        }

        val durationSec = samples.size / sampleRate.toFloat()
        Log.d(TAG, "playAudio: samples=${samples.size}, sampleRate=$sampleRate, duration=%.1fs, volume=%.2f"
            .format(durationSec, volume))

        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            if (minBuf <= 0) {
                Log.w(TAG, "playAudio: getMinBufferSize returned $minBuf for sr=$sampleRate — playback unavailable")
                return
            }
            val bufferSize = maxOf(minBuf, sampleRate / 2 * 4) // at least 0.5s
            Log.d(TAG, "playAudio: minBuf=$minBuf, bufferSize=$bufferSize")

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
            Log.d(TAG, "playAudio: AudioTrack created and playing (state=${t.playState})")
            isPlaying.set(true)

            // Stream scaled samples in chunks with NON_BLOCKING writes and a
            // short retry — a blocking write can never hang barge-in because
            // every iteration re-checks the playback generation first.
            val chunkFloats = 4096
            var offset = 0
            var totalWritten = 0
            while (offset < samples.size && isPlaying.get() && gen == playbackGen.get()) {
                val end = minOf(offset + chunkFloats, samples.size)
                val bytes = (end - offset) * 4
                val byteBuffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val floatBuffer = byteBuffer.asFloatBuffer()
                for (k in offset until end) {
                    floatBuffer.put(samples[k] * volume.coerceIn(0f, 1f))
                }

                var bufOffset = 0
                var writeAttempts = 0
                while (bufOffset < bytes && isPlaying.get() && gen == playbackGen.get()) {
                    val written = try {
                        t.write(
                            byteBuffer.array(), bufOffset, bytes - bufOffset,
                            AudioTrack.WRITE_NON_BLOCKING
                        )
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "AudioTrack.write IllegalStateException at offset=$offset: ${e.message}")
                        return
                    }
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack.write error=$written at offset=$offset — aborting playback")
                        return
                    }
                    if (written == 0) {
                        writeAttempts++
                        if (writeAttempts > 50) { // ~500ms stall guard
                            Log.w(TAG, "AudioTrack.write stalled for 500ms at offset=$offset — aborting")
                            return
                        }
                        Thread.sleep(10) // buffer full — retry shortly
                    } else {
                        bufOffset += written
                        writeAttempts = 0
                    }
                }
                totalWritten += (end - offset)
                offset = end
            }
            Log.d(TAG, "playAudio: streaming complete — totalWritten=$totalWritten samples, interrupted=${!isPlaying.get() || gen != playbackGen.get()}")

            // Wait for the hardware to drain what was written — playbackHeadPosition
            // reaches totalFrames only when the speaker has finished the utterance.
            if (isPlaying.get() && gen == playbackGen.get()) {
                val totalFrames = samples.size
                var drainWaitMs = 0
                while (isPlaying.get() && gen == playbackGen.get()) {
                    if (t.playbackHeadPosition >= totalFrames) break
                    Thread.sleep(50)
                    drainWaitMs += 50
                    if (drainWaitMs > 5000) { // 5s safety timeout
                        Log.w(TAG, "playAudio: drain wait timed out after 5s (headPos=${t.playbackHeadPosition}, total=$totalFrames)")
                        break
                    }
                }
                Log.d(TAG, "playAudio: drain complete after ${drainWaitMs}ms (headPos=${t.playbackHeadPosition}, totalFrames=$totalFrames)")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "playAudio: exception during playback: ${e.javaClass.simpleName}: ${e.message}")
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
            Log.d(TAG, "playAudio: AudioTrack released")
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
