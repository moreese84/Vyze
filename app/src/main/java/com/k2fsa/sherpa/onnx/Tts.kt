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

data class OfflineTtsConfig(
    val model: String = "",
    val tokens: String = "",
    val dataDir: String = "",
    val dictDir: String = "",
    val modelType: String = "",
    val numThreads: Int = 2,
    val debug: Boolean = false,
    val provider: String = "cpu",
    val maxNumSentences: Int = 1
)
