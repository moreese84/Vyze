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
            applySettings(application.applicationContext)
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.onDestroy()
    }
}
