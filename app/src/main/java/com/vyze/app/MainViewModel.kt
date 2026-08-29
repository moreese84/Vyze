package com.vyze.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.vyze.app.data.ScanRepository
import com.vyze.app.data.ScanEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Main ViewModel for Vyze, holding all shared application state.
 *
 * Uses [SavedStateHandle] to persist critical settings across process death.
 *
 * ## State Categories
 * 1. **Active Language** — TTS language key (en/ms/zh)
 * 2. **Battery State** — current level, last warning level
 * 3. **Scan History** — reactive flow of recent scans
 */
class MainViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val scanRepository = ScanRepository(application.applicationContext)

    // ══════════════════════════════════════════════════════════════════
    // Active Language State
    // ══════════════════════════════════════════════════════════════════

    private val _activeLanguage = MutableStateFlow(
        savedStateHandle[KEY_ACTIVE_LANGUAGE] ?: TTSManager.LANGUAGE_ENGLISH
    )

    /** Current TTS language key. Observed by UI for language selector binding. */
    val activeLanguage: StateFlow<String> = _activeLanguage.asStateFlow()

    fun setActiveLanguage(languageKey: String) {
        savedStateHandle[KEY_ACTIVE_LANGUAGE] = languageKey
        _activeLanguage.value = languageKey
    }

    // ══════════════════════════════════════════════════════════════════
    // Battery State
    // ══════════════════════════════════════════════════════════════════

    private val _batteryLevel = MutableStateFlow(-1)

    /** Current battery level (0–100), or -1 if unknown. */
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _lastBatteryWarningLevel = MutableStateFlow(-1)

    /** Last level at which a battery warning was spoken. */
    val lastBatteryWarningLevel: StateFlow<Int> = _lastBatteryWarningLevel.asStateFlow()

    fun updateBatteryLevel(level: Int) {
        _batteryLevel.value = level
    }

    fun setLastBatteryWarningLevel(level: Int) {
        _lastBatteryWarningLevel.value = level
        savedStateHandle[KEY_LAST_BATTERY_WARNING] = level
    }

    // ══════════════════════════════════════════════════════════════════
    // Scan History (reactive via Room)
    // ══════════════════════════════════════════════════════════════════

    private val _recentScans = MutableStateFlow<List<ScanEntity>>(emptyList())

    /** Recent scan history. Updated whenever a new scan is saved. */
    val recentScans: StateFlow<List<ScanEntity>> = _recentScans.asStateFlow()

    /** Load the most recent scans from the database. */
    fun loadRecentScans(limit: Int = 3) {
        viewModelScope.launch {
            _recentScans.value = scanRepository.getRecentScans(limit)
        }
    }

    /** Save a scan and refresh the list. */
    fun saveScan(type: String, content: String, metadata: String = "") {
        viewModelScope.launch {
            scanRepository.saveScan(type, content, metadata)
            loadRecentScans()
        }
    }

    /** Save a scene summary. */
    fun saveSceneScan(summary: String) {
        saveScan(ScanRepository.TYPE_SCENE, summary)
    }

    /** Format recent scans for TTS readout. */
    fun formatRecentScansForTts(limit: Int = 3, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val text = scanRepository.formatRecentScansForTts(limit)
            onResult(text)
        }
    }

    /** Clear all scan history. */
    fun clearScanHistory() {
        viewModelScope.launch {
            scanRepository.clearAllScans()
            loadRecentScans()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Companion — SavedStateHandle Keys
    // ══════════════════════════════════════════════════════════════════

    companion object {
        private const val KEY_ACTIVE_LANGUAGE = "active_language"
        private const val KEY_LAST_BATTERY_WARNING = "last_battery_warning"
    }
}
