package com.k2fsa.sherpa.onnx

import android.content.Context
import android.util.Log

/**
 * Sherpa-ONNX Text-to-Speech engine wrapper.
 *
 * Supports Kokoro (EN/ZH) and Meta MMS (multilingual including Malay) models.
 * All inference runs fully offline on-device.
 */
class OfflineTts(
    private val config: OfflineTtsConfig
) {
    private var ptr: Long = 0
    private val lock = Any()

    companion object {
        private const val TAG = "SherpaOfflineTts"

        init {
            try {
                System.loadLibrary("sherpa-onnx-jni")
                Log.i(TAG, "sherpa-onnx-jni loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load sherpa-onnx-jni: ${e.message}")
            }
        }
    }

    init {
        ptr = newFromFile(config)
        if (ptr == 0L) {
            throw RuntimeException("Failed to create OfflineTts from config")
        }
    }

    fun generate(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio? {
        synchronized(lock) {
            if (ptr == 0L) return null
            return generateFromFile(ptr, text, sid, speed)
        }
    }

    fun sampleRate(): Int {
        synchronized(lock) {
            if (ptr == 0L) return 0
            return getSampleRate(ptr)
        }
    }

    fun numSpeakers(): Int {
        synchronized(lock) {
            if (ptr == 0L) return 0
            return getNumSpeakers(ptr)
        }
    }

    fun release() {
        synchronized(lock) {
            if (ptr != 0L) {
                delete(ptr)
                ptr = 0
            }
        }
    }

    protected fun finalize() {
        release()
    }

    private external fun newFromFile(config: OfflineTtsConfig): Long
    private external fun delete(ptr: Long)
    private external fun generateFromFile(
        ptr: Long,
        text: String,
        sid: Int,
        speed: Float
    ): GeneratedAudio?
    private external fun getSampleRate(ptr: Long): Int
    private external fun getNumSpeakers(ptr: Long): Int
}

data class GeneratedAudio(
    val sampleRate: Int,
    val samples: FloatArray
)

// ── Model Config (Vits) ──────────────────────────────────────────────

class OfflineTtsVitsModelConfig(
    @JvmField val model: String = "",
    @JvmField val lexicon: String = "",
    @JvmField val tokens: String = "",
    @JvmField val dataDir: String = "",
    @JvmField val dictDir: String = "",
    @JvmField val noiseScale: Float = 0.667f,
    @JvmField val noiseScaleW: Float = 0.8f,
    @JvmField val lengthScale: Float = 1.0f
)

// ── Model Config (Matcha) ────────────────────────────────────────────

class OfflineTtsMatchaModelConfig(
    @JvmField val acousticModel: String = "",
    @JvmField val vocoder: String = "",
    @JvmField val lexicon: String = "",
    @JvmField val tokens: String = "",
    @JvmField val dataDir: String = "",
    @JvmField val dictDir: String = "",
    @JvmField val noiseScale: Float = 1.0f,
    @JvmField val lengthScale: Float = 1.0f
)

// ── Model Config (Kokoro) ────────────────────────────────────────────

class OfflineTtsKokoroModelConfig(
    @JvmField val model: String = "",
    @JvmField val voices: String = "",
    @JvmField val tokens: String = "",
    @JvmField val dataDir: String = "",
    @JvmField val lengthScale: Float = 1.0f,
    @JvmField val lexicon: String = "",
    @JvmField val lang: String = "",
    @JvmField val dictDir: String = ""
)

// ── Model Config (ZipVoice) ──────────────────────────────────────────

class OfflineTtsZipVoiceModelConfig(
    @JvmField val tokens: String = "",
    @JvmField val encoder: String = "",
    @JvmField val decoder: String = "",
    @JvmField val vocoder: String = "",
    @JvmField val dataDir: String = "",
    @JvmField val lexicon: String = "",
    @JvmField val featScale: Float = 0.1f,
    @JvmField val tShift: Float = 0.5f,
    @JvmField val targetRms: Float = 0.1f,
    @JvmField val guidanceScale: Float = 1.0f
)

// ── Model Config (Kitten) ────────────────────────────────────────────

class OfflineTtsKittenModelConfig(
    @JvmField val model: String = "",
    @JvmField val voices: String = "",
    @JvmField val tokens: String = "",
    @JvmField val dataDir: String = "",
    @JvmField val lengthScale: Float = 1.0f
)

// ── Model Config (Pocket) ────────────────────────────────────────────

class OfflineTtsPocketModelConfig(
    @JvmField val lmFlow: String = "",
    @JvmField val lmMain: String = "",
    @JvmField val encoder: String = "",
    @JvmField val decoder: String = "",
    @JvmField val textConditioner: String = "",
    @JvmField val vocabJson: String = "",
    @JvmField val tokenScoresJson: String = "",
    @JvmField val voiceEmbeddingCacheCapacity: Int = 0
)

// ── Model Config (Supertonic) ────────────────────────────────────────

class OfflineTtsSupertonicModelConfig(
    @JvmField val durationPredictor: String = "",
    @JvmField val textEncoder: String = "",
    @JvmField val vectorEstimator: String = "",
    @JvmField val vocoder: String = "",
    @JvmField val ttsJson: String = "",
    @JvmField val unicodeIndexer: String = "",
    @JvmField val voiceStyle: String = ""
)

// ── Top-level Model Config ──────────────────────────────────────────

/**
 * Matches the JNI layout from sherpa-onnx java-api.
 * The native code calls GetObjectField on each model sub-config
 * and GetObjectField/GetIntField/GetBooleanField on the scalar fields.
 */
class OfflineTtsModelConfig(
    @JvmField val vits: OfflineTtsVitsModelConfig = OfflineTtsVitsModelConfig(),
    @JvmField val matcha: OfflineTtsMatchaModelConfig = OfflineTtsMatchaModelConfig(),
    @JvmField val kokoro: OfflineTtsKokoroModelConfig = OfflineTtsKokoroModelConfig(),
    @JvmField val zipvoice: OfflineTtsZipVoiceModelConfig = OfflineTtsZipVoiceModelConfig(),
    @JvmField val kitten: OfflineTtsKittenModelConfig = OfflineTtsKittenModelConfig(),
    @JvmField val pocket: OfflineTtsPocketModelConfig = OfflineTtsPocketModelConfig(),
    @JvmField val supertonic: OfflineTtsSupertonicModelConfig = OfflineTtsSupertonicModelConfig(),
    @JvmField val numThreads: Int = 1,
    @JvmField val debug: Boolean = true,
    @JvmField val provider: String = "cpu"
)

// ── Top-level TTS Config ────────────────────────────────────────────

/**
 * Matches the JNI layout from sherpa-onnx java-api.
 * OfflineTtsConfig wraps OfflineTtsModelConfig plus global settings.
 */
class OfflineTtsConfig(
    @JvmField val model: OfflineTtsModelConfig = OfflineTtsModelConfig(),
    @JvmField val ruleFsts: String = "",
    @JvmField val ruleFars: String = "",
    @JvmField val maxNumSentences: Int = 1,
    @JvmField val silenceScale: Float = 0.2f
) {
    /**
     * Convenience constructor for the Kokoro model (EN/ZH neural TTS).
     * Populates only the [Kokoro][OfflineTtsKokoroModelConfig] sub-config.
     */
    constructor(
        model: String = "",
        tokens: String = "",
        dataDir: String = "",
        voices: String = "",
        numThreads: Int = 2,
        debug: Boolean = false
    ) : this(
        model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = model,
                tokens = tokens,
                dataDir = dataDir,
                voices = voices
            ),
            numThreads = numThreads,
            debug = debug,
            provider = "cpu"
        ),
        maxNumSentences = 1
    )

    companion object {
        /**
         * Build a config for a VITS-based model (e.g. Meta MMS for Malay).
         * Populates only the [vits][OfflineTtsVitsModelConfig] sub-config.
         */
        fun forVits(
            model: String,
            tokens: String,
            dataDir: String,
            numThreads: Int = 2,
            debug: Boolean = false
        ): OfflineTtsConfig {
            return OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = model,
                        tokens = tokens,
                        dataDir = dataDir
                    ),
                    numThreads = numThreads,
                    debug = debug,
                    provider = "cpu"
                ),
                maxNumSentences = 1
            )
        }
    }
}