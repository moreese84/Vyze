package com.vyze.app.ui
import com.vyze.app.VyzeApplication
import com.vyze.app.speech.TTSManager

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/**
 * ViewModel that provides the shared [TTSManager] singleton.
 *
 * TTSManager is now a strict process-wide singleton ([TTSManager.getInstance]),
 * so this ViewModel simply exposes it rather than creating a new instance.
 * Previously, both VyzeApplication and this ViewModel created independent
 * TTSManager instances, causing double native JNI state and silent audio.
 */
class TtsViewModel(application: Application) : AndroidViewModel(application) {

    val ttsManager: TTSManager by lazy {
        TTSManager.getInstance(application.applicationContext)
    }

    override fun onCleared() {
        super.onCleared()
        // Do NOT call ttsManager.onDestroy() — the singleton lives for the
        // process lifetime. Calling onDestroy from a ViewModel's onCleared
        // would tear down TTS for other consumers (VyzeApplication, etc.).
    }
}
