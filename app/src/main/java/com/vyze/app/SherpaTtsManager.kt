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
            // Extract from APK assets on first run
            modelDir.mkdirs()
            extractAsset("mms/model.onnx", modelDir)
            extractAsset("mms/tokens.txt", modelDir)
        }

        if (!modelFile.exists() || !tokensFile.exists()) {
            Log.w(TAG, "VITS model files not found after extraction")
            return
        }
        if (modelFile.length() < 1_000_000L) {
            Log.w(TAG, "VITS model.onnx too small (${modelFile.length()} bytes) — skipping")
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
            Log.i(TAG, "Extracted $assetName → ${targetFile.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to extract $assetName: ${e.message}")
        }
    }

    // ── Public API ──────────────────────────────────────────────

    fun isReady(): Boolean = isInitialized && vitsTts != null
    fun isTtsAvailable(): Boolean = vitsTts != null
    fun hasVoiceFor(language: String): Boolean = vitsTts != null  // single multilingual model

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

                if (audio != null && audio.samples.isNotEmpty()) {
                    val dur = audio.samples.size / audio.sampleRate
                    Log.d(TAG, "Synthesis OK — samples=${audio.samples.size}, rate=${audio.sampleRate}, dur=${dur}s")
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

    // ── Audio Playback (PCM 16-bit) ─────────────────────────────

    /**
     * Play synthesized audio. Converts 32-bit float samples to 16-bit PCM
     * for universal hardware compatibility. Uses MODE_STATIC for the full
     * utterance (fits within typical TTS duration limits).
     */
    private fun playPcm16(samples: FloatArray, sampleRate: Int, gen: Long, volume: Float) {
        if (samples.isEmpty() || sampleRate <= 0) return

        val durSec = samples.size / sampleRate.toFloat()
        Log.d(TAG, "playPcm16: ${samples.size} samples @ ${sampleRate}Hz, ${"%.1f".format(durSec)}s, vol=${"%.2f".format(volume)}")

        // Convert FloatArray → ShortArray (PCM 16-bit)
        val pcm16 = ShortArray(samples.size)
        val v = volume.coerceIn(0f, 1f)
        var peak: Float = 0f
        for (i in samples.indices) {
            val s = (samples[i] * v).coerceIn(-1f, 1f)
            pcm16[i] = (s * 32767f).toInt().toShort()
            val abs = kotlin.math.abs(s)
            if (abs > peak) peak = abs
        }
        Log.d(TAG, "PCM16 conversion: peak=${"%.3f".format(peak)}, ${pcm16.size} shorts")

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
            Log.d(TAG, "minBuf=$minBuf, bufBytes=$bufBytes")

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
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track = t

            val written = t.write(pcm16, 0, pcm16.size)
            Log.d(TAG, "AudioTrack.write: $written shorts written of ${pcm16.size}")
            if (written != pcm16.size) {
                Log.w(TAG, "AudioTrack.write wrote $written, expected ${pcm16.size}")
            }

            isPlaying.set(true)
            t.play()
            Log.d(TAG, "AudioTrack.play() called, state=${t.playState}")

            // Wait for playback to finish
            if (gen == playbackGen.get()) {
                val totalFrames = pcm16.size
                var waitedMs = 0
                while (isPlaying.get() && gen == playbackGen.get()) {
                    if (t.playbackHeadPosition >= totalFrames) break
                    Thread.sleep(50)
                    waitedMs += 50
                    if (waitedMs > durSec.toInt() * 1000 + 2000) { // duration + 2s guard
                        Log.w(TAG, "Playback wait timed out after ${waitedMs}ms")
                        break
                    }
                }
                Log.d(TAG, "Playback done: headPos=${t.playbackHeadPosition}, waited=${waitedMs}ms")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "AudioTrack error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { track?.stop() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
            if (gen == playbackGen.get()) isPlaying.set(false)
            Log.d(TAG, "AudioTrack released")
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