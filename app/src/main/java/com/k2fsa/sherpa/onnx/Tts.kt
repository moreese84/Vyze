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

/**
 * Model config — the native JNI code expects this as a nested object
 * inside OfflineTtsConfig.
 */
class OfflineTtsModelConfig(
    @JvmField val model: String = "",
    @JvmField val tokens: String = "",
    @JvmField val dataDir: String = "",
    @JvmField val dictDir: String = "",
    @JvmField val modelType: String = "",
    @JvmField val debug: Boolean = false,
    @JvmField val provider: String = "cpu",
    @JvmField val numThreads: Int = 2,
    @JvmField val maxNumSentences: Int = 1
)

class OfflineTtsConfig(
    @JvmField val modelConfig: OfflineTtsModelConfig,
    @JvmField val numThreads: Int = 2,
    @JvmField val debug: Boolean = false,
    @JvmField val provider: String = "cpu"
) {
    constructor(
        model: String = "",
        tokens: String = "",
        dataDir: String = "",
        dictDir: String = "",
        modelType: String = "",
        numThreads: Int = 2,
        debug: Boolean = false,
        provider: String = "cpu",
        maxNumSentences: Int = 1
    ) : this(
        modelConfig = OfflineTtsModelConfig(
            model = model,
            tokens = tokens,
            dataDir = dataDir,
            dictDir = dictDir,
            modelType = modelType,
            debug = debug,
            provider = provider,
            numThreads = numThreads,
            maxNumSentences = maxNumSentences
        ),
        numThreads = numThreads,
        debug = debug,
        provider = provider
    )
}
