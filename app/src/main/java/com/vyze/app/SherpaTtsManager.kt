package com.vyze.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.GeneratedAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Offline TTS manager using a single Sherpa-ONNX VITS model.
 *
 * Uses the Meta MMS model (multilingual, 1100+ languages) loaded once
 * via VITS config. Audio output: FloatArray → PCM 16-bit conversion
 * for universal AudioTrack compatibility.
 *
 * Models load lazily on first [speak] call, off the main thread.
 */
class SherpaTtsManager(private val context: Context) {

    private val TAG = "SherpaTtsManager"

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val drainLock = Any()
    private var drainJob: Job? = null

    private val playbackGen = AtomicLong(0)

    // ── Single VITS Model ───────────────────────────────────────
    private var vitsTts: OfflineTts? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    private var modelLoadAttempted = false

    private var nativeLibOk = false
    private var nativeLibChecked = false

    private val readyListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

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
    private val modelDir: File
        get() = File(context.getExternalFilesDir(null), "sherpa-models/mms")

    // ── Lazy Init ───────────────────────────────────────────────

    private fun ensureNativeLib(): Boolean {
        if (nativeLibChecked) return nativeLibOk
        nativeLibChecked = true
        nativeLibOk = OfflineTts.ensureLoaded()
        if (!nativeLibOk) Log.e(TAG, "sherpa-onnx-jni native library failed to load")
        return nativeLibOk
    }

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
                withContext(Dispatchers.IO) {
                    if (!ensureNativeLib()) return@withContext
                    loadVitsModel()
                    modelLoadAttempted = true
                    isInitialized = true
                    Log.i(TAG, "TTS init — VITS loaded=${vitsTts != null}")
                    if (vitsTts != null) Log.i(TAG, "VITS sampleRate=${vitsTts?.sampleRate() ?: -1} Hz")
                }
                readyListeners.forEach { l -> withContext(Dispatchers.Main) { l() } }
            } catch (e: Throwable) {
                Log.e(TAG, "TTS init failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ── VITS Model Loading ──────────────────────────────────────

    private fun loadVitsModel() {
        val modelFile = File(modelDir, "model.onnx")
        val tokensFile = File(modelDir, "tokens.txt")

        if (!modelFile.exists() || !tokensFile.exists()) {
            modelDir.mkdirs()
            extractAsset("mms/model.onnx", modelDir)
            extractAsset("mms/tokens.txt", modelDir)
        }

        // ── PRE-FLIGHT FILE VALIDATION ───────────────────────────
        val issues = mutableListOf<String>()
        if (!modelFile.exists()) issues.add("model.onnx not found")
        else if (modelFile.length() < 1_000_000L) issues.add("model.onnx too small (${modelFile.length()} bytes, need >1MB)")
        else Log.i(TAG, "model.onnx OK: ${modelFile.length()} bytes")

        if (!tokensFile.exists()) issues.add("tokens.txt not found")
        else if (tokensFile.length() == 0L) issues.add("tokens.txt is empty")
        else Log.i(TAG, "tokens.txt OK: ${tokensFile.length()} bytes")

        if (issues.isNotEmpty()) {
            issues.forEach { Log.w(TAG, "PRE-FLIGHT FAIL: $it") }
            return
        }

        try {
            val config = OfflineTtsConfig.forVits(
                model = modelFile.absolutePath,
                tokens = tokensFile.absolutePath,
                dataDir = "",
                numThreads = 2,
                debug = true
            )
            vitsTts = OfflineTts(config)
            val sr = vitsTts?.sampleRate() ?: -1
            Log.i(TAG, "VITS model loaded — sampleRate=$sr Hz")
            if (sr <= 0) {
                vitsTts?.release()
                vitsTts = null
                Log.w(TAG, "VITS returned sampleRate=$sr")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "VITS load failed: ${e.javaClass.simpleName}: ${e.message}")
            vitsTts = null
        }
    }

    private fun extractAsset(assetName: String, targetDir: File) {
        val targetFile = File(targetDir, assetName.substringAfterLast("/"))
        if (targetFile.exists()) return
        try {
            context.assets.open(assetName).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Extracted $assetName → ${targetFile.absolutePath} (${targetFile.length()} bytes)")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to extract $assetName: ${e.message}")
        }
    }

    // ── Public API ──────────────────────────────────────────────

    fun isReady(): Boolean = isInitialized && vitsTts != null
    
    /**
     * Directly generate PCM samples without going through the speak queue.
     * Used by TTSManager for immediate synchronous generation + playback.
     */
    fun generateSamples(text: String, speed: Float = 1.0f): com.k2fsa.sherpa.onnx.GeneratedAudio? {
        if (vitsTts == null) return null
        return try {
            vitsTts?.generate(text, speed = speed)
        } catch (e: Throwable) {
            Log.e(TAG, "generateSamples failed: " + e.message)
            null
        }
    }
    fun isTtsAvailable(): Boolean = vitsTts != null
    fun hasVoiceFor(language: String): Boolean = vitsTts != null

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

        if (!isInitialized) ensureModelsLoaded()

        if (!isReady()) {
            Log.w(TAG, "TTS not ready — dropping utterance (init=$isInitialized, loaded=$modelLoadAttempted)")
            if (onDone != null) scope.launch { withContext(Dispatchers.Main) { onDone() } }
            return
        }

        speakQueue.add(QueueItem(text, locale, speed, volume.coerceIn(0f, 1f), onStart, onDone))
        startDrainIfIdle()
    }

    fun stop() {
        playbackGen.incrementAndGet()
        synchronized(drainLock) {
            drainJob?.cancel()
            drainJob = null
        }
        speakQueue.clear()
        isPlaying.set(false)
    }

    fun setVolume(volume: Float) { currentVolume = volume.coerceIn(0f, 1f) }
    fun isSpeaking(): Boolean = isPlaying.get()

    fun release() {
        stop()
        vitsTts?.release()
        vitsTts = null
        isInitialized = false
        modelLoadAttempted = false
        scope.cancel()
    }

    // ── Queue Drain ─────────────────────────────────────────────

    private fun startDrainIfIdle() {
// Safety kick: if drain doesn't start within 2s, retry        scope.launch {            delay(2000L)            if (!speakQueue.isEmpty() && drainJob?.isActive != true) {                Log.w(TAG, "Drain queue kick-start — drain stalled")                startDrainIfIdle()            }        }
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
                if (speakQueue.isEmpty()) break
                continue
            }
            val gen = playbackGen.get()
            try {
                withContext(Dispatchers.Main) { item.onStart?.invoke() }

                val tts = vitsTts
                if (tts == null) {
                    Log.w(TAG, "No TTS model available for locale: ${item.locale}")
                    withContext(Dispatchers.Main) { item.onDone?.invoke() }
                    continue
                }

                Log.d(TAG, "Synthesizing: \"${item.text.take(80)}\" (len=${item.text.length}, locale=${item.locale})")
                val audio = withContext(Dispatchers.Default) {
                    tts.generate(item.text, speed = item.speed)
                }
                yield()
                if (gen != playbackGen.get()) return

                // ── DIAGNOSTIC: log raw PCM output ───────────────
                if (audio != null) {
                    Log.i(TAG, "GENERATE RESULT: samples=${audio.samples.size}, sampleRate=${audio.sampleRate}")
                    Log.i(TAG, "GENERATE: null=${audio == null}, samples.isEmpty=${audio.samples.isEmpty()}")
                    if (audio.samples.isNotEmpty()) {
                        val firstFew = audio.samples.take(5).joinToString(", ") { "%.4f".format(it) }
                        val lastFew = audio.samples.takeLast(5).joinToString(", ") { "%.4f".format(it) }
                        Log.i(TAG, "GENERATE: first5=[$firstFew], last5=[$lastFew]")
                        Log.i(TAG, "GENERATE: min=${audio.samples.min()}, max=${audio.samples.max()}")
                    }
                } else {
                    Log.w(TAG, "GENERATE RESULT: audio is NULL — model returned nothing")
                }

                if (audio != null && audio.samples.isNotEmpty()) {
                    val dur = audio.samples.size / audio.sampleRate.toFloat()
                    Log.i(TAG, "Playback: ${audio.samples.size} samples @ ${audio.sampleRate}Hz, ~${"%.1f".format(dur)}s")
                    playPcm16(audio.samples, audio.sampleRate, gen, item.volume)
                } else {
                    Log.w(TAG, "Synthesis returned empty audio — null=${audio == null}, size=${audio?.samples?.size}")
                }

                if (gen == playbackGen.get()) {
                    withContext(Dispatchers.Main) { item.onDone?.invoke() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Speak failed: ${e.message}")
                if (gen == playbackGen.get()) {
                    withContext(Dispatchers.Main) { item.onDone?.invoke() }
                }
            }
        }
    }

    // ── Audio Playback (PCM 16-bit, MODE_STREAM) ────────────────

    private fun playPcm16(samples: FloatArray, sampleRate: Int, gen: Long, volume: Float) {
        if (samples.isEmpty() || sampleRate <= 0) return

        val durSec = samples.size / sampleRate.toFloat()
        Log.i(TAG, "playPcm16: ${samples.size} samples @ ${sampleRate}Hz, ${"%.1f".format(durSec)}s, vol=${"%.2f".format(volume)}")

        // Convert FloatArray → ShortArray (PCM 16-bit)
        val pcm16 = ShortArray(samples.size)
        val v = volume.coerceIn(0f, 1f)
        for (i in samples.indices) {
            pcm16[i] = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }

        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                Log.w(TAG, "getMinBufferSize returned $minBuf — cannot play")
                return
            }
            val bufBytes = maxOf(minBuf, pcm16.size * 2)
            Log.i(TAG, "minBuf=$minBuf, bufBytes=$bufBytes")

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
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t

            if (t.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "AudioTrack not initialized (state=${t.state})")
                return
            }
            Log.i(TAG, "AudioTrack INITIALIZED, state=${t.state}")

            t.play()
            Log.i(TAG, "AudioTrack.play(), state=${t.playState}")

            isPlaying.set(true)

            // Write in chunks (streaming mode)
            val chunkSize = 2048
            var offset = 0
            var totalWritten = 0
            while (offset < pcm16.size && isPlaying.get() && gen == playbackGen.get()) {
                val end = minOf(offset + chunkSize, pcm16.size)
                val written = t.write(pcm16, offset, end - offset)
                if (written > 0) {
                    totalWritten += written
                    offset += written
                } else if (written == 0) {
                    Thread.sleep(10)
                } else {
                    Log.w(TAG, "AudioTrack.write error=$written")
                    break
                }
            }
            Log.i(TAG, "AudioTrack: wrote $totalWritten shorts of ${pcm16.size}")

            // Wait for hardware drain
            if (isPlaying.get() && gen == playbackGen.get()) {
                var waitedMs = 0
                while (isPlaying.get() && gen == playbackGen.get()) {
                    if (t.playbackHeadPosition >= pcm16.size) break
                    Thread.sleep(50)
                    waitedMs += 50
                    if (waitedMs > (durSec * 1000).toInt() + 3000) {
                        Log.w(TAG, "Drain timeout after ${waitedMs}ms (headPos=${t.playbackHeadPosition})")
                        break
                    }
                }
                Log.i(TAG, "Drain done: headPos=${t.playbackHeadPosition}, waited=${waitedMs}ms")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "AudioTrack error: ${e.javaClass.simpleName}: ${e.message}")
            if (gen == playbackGen.get()) isPlaying.set(false)
        } finally {
            try { track?.stop() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
            if (gen == playbackGen.get()) isPlaying.set(false)
            Log.i(TAG, "AudioTrack released")
        }
    }

    companion object {
        @Volatile
        private var shared: SherpaTtsManager? = null

        fun getInstance(context: Context): SherpaTtsManager {
            return shared ?: synchronized(this) {
                shared ?: SherpaTtsManager(context.applicationContext).also { shared = it }
            }
        }

        fun areModelsDownloaded(context: Context): Boolean {
            val baseDir = File(context.getExternalFilesDir(null), "sherpa-models/mms")
            return File(baseDir, "model.onnx").exists()
        }
    }
}