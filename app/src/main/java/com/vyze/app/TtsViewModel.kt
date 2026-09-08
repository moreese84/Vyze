package com.vyze.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel

/**
 * ViewModel that holds a singleton [TTSManager] instance.
 *
 * Ensures all fragments (CameraFragment, TtsSettingsFragment, etc.)
 * share the same TTS engine and settings state. The TTSManager is
 * created once and survives configuration changes within the same
 * Activity scope.
 *
 * Usage in Fragment:
 * ```kotlin
 * val ttsViewModel: TtsViewModel by activityViewModels()
 * val ttsManager = ttsViewModel.ttsManager
 * ```
 */
class TtsViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Singleton TTSManager instance. Initialized once per ViewModel lifecycle.
     */
    val ttsManager: TTSManager by lazy {
        TTSManager(application.applicationContext).apply {
            // Prefer offline Sherpa TTS when model files are present; otherwise fall
            // back to the default Google/system TTS engine.
            // Sherpa model files ship in src/main/assets (kokoro/ + mms/) and are
            // extracted to the app-scoped external directory on first use.
            applySettings(application.applicationContext)
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.onDestroy()
    }
}
