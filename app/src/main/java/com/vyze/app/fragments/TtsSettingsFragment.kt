package com.vyze.app.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.vyze.app.R
import com.vyze.app.TTSManager

/**
 * Accessible TTS settings screen for visually impaired users.
 *
 * Provides three large, high-contrast sliders for:
 *  - **Speech Rate** (0.5x to 2.0x)
 *  - **Pitch** (0.5 to 1.5)
 *  - **Audio Volume** (0% to 100%)
 *
 * All values are persisted to [SharedPreferences] under [TTSManager.PREFS_NAME]
 * and applied to the shared [TTSManager] instance immediately.
 *
 * A "Test Voice" button speaks a sample phrase so the user can instantly
 * hear the effect of their changes.
 *
 * ## Accessibility
 * - All sliders use `android:minHeight="48dp"` for touch target compliance.
 * - Large text labels (22sp) and value readouts (36sp) for low-vision users.
 * - Content descriptions on every interactive element for TalkBack.
 * - High-contrast yellow-on-black color scheme.
 */
class TtsSettingsFragment : Fragment() {

    private lateinit var ttsManager: TTSManager
    private lateinit var prefs: android.content.SharedPreferences

    // Views
    private lateinit var valueLanguage: TextView
    private lateinit var spinnerLanguage: Spinner
    private lateinit var valueSpeechRate: TextView
    private lateinit var sliderSpeechRate: SeekBar
    private lateinit var valuePitch: TextView
    private lateinit var sliderPitch: SeekBar
    private lateinit var valueVolume: TextView
    private lateinit var sliderVolume: SeekBar
    private lateinit var btnTestVoice: Button

    // Debounce for slider changes — speak sample only after 500ms of inactivity
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSpeakRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_tts_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize TTS Manager and preferences
        ttsManager = TTSManager(requireContext().applicationContext)
        prefs = requireContext().getSharedPreferences(
            TTSManager.PREFS_NAME, Context.MODE_PRIVATE
        )

        // Apply saved settings immediately
        ttsManager.applySettings(requireContext())

        // Bind views
        valueLanguage = view.findViewById(R.id.value_language)
        spinnerLanguage = view.findViewById(R.id.spinner_language)
        valueSpeechRate = view.findViewById(R.id.value_speech_rate)
        sliderSpeechRate = view.findViewById(R.id.slider_speech_rate)
        valuePitch = view.findViewById(R.id.value_pitch)
        sliderPitch = view.findViewById(R.id.slider_pitch)
        valueVolume = view.findViewById(R.id.value_volume)
        sliderVolume = view.findViewById(R.id.slider_volume)
        btnTestVoice = view.findViewById(R.id.btn_test_voice)

        // Load saved values and set initial positions
        loadSavedSettings()

        // Setup listeners
        setupLanguageSpinner()
        setupSpeechRateSlider()
        setupPitchSlider()
        setupVolumeSlider()
        setupTestButton()
    }

    /**
     * Loads saved settings from SharedPreferences and positions sliders accordingly.
     */
    private fun loadSavedSettings() {
        // Language
        val savedLang = prefs.getString(TTSManager.KEY_LANGUAGE, TTSManager.LANGUAGE_ENGLISH)
            ?: TTSManager.LANGUAGE_ENGLISH
        val langIndex = TTSManager.SUPPORTED_LANGUAGES.indexOf(savedLang).coerceAtLeast(0)
        spinnerLanguage.setSelection(langIndex)
        valueLanguage.text = languageDisplayName(savedLang)

        // Rate
        val savedRate = prefs.getFloat(TTSManager.KEY_SPEECH_RATE, TTSManager.DEFAULT_SPEECH_RATE)
        sliderSpeechRate.progress = ((savedRate - RATE_MIN) / RATE_STEP).toInt()
        valueSpeechRate.text = formatRate(savedRate)

        // Pitch
        val savedPitch = prefs.getFloat(TTSManager.KEY_PITCH, TTSManager.DEFAULT_PITCH)
        sliderPitch.progress = ((savedPitch - PITCH_MIN) / PITCH_STEP).toInt()
        valuePitch.text = String.format("%.2f", savedPitch)

        // Volume
        val savedVolume = prefs.getFloat(TTSManager.KEY_VOLUME, TTSManager.DEFAULT_VOLUME)
        sliderVolume.progress = (savedVolume * 100).toInt()
        valueVolume.text = "${(savedVolume * 100).toInt()}%"
    }

    /**
     * Speech Rate slider: 0.5x to 2.0x.
     * SeekBar range 0–150, each step = 0.01.
     */
    private fun setupSpeechRateSlider() {
        sliderSpeechRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = RATE_MIN + (progress * RATE_STEP)
                valueSpeechRate.text = formatRate(rate)
                seekBar?.contentDescription = "Speech speed slider, current value ${formatRate(rate)}"
                saveAndApply(rateKey = TTSManager.KEY_SPEECH_RATE, value = rate)
                scheduleSampleSpeak()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                cancelPendingSpeak()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                speakSample()
            }
        })
    }

    /**
     * Pitch slider: 0.5 to 1.5.
     * SeekBar range 0–100, each step = 0.01.
     */
    private fun setupPitchSlider() {
        sliderPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = PITCH_MIN + (progress * PITCH_STEP)
                valuePitch.text = String.format("%.2f", pitch)
                seekBar?.contentDescription = "Voice pitch slider, current value ${String.format("%.2f", pitch)}"
                saveAndApply(pitchKey = TTSManager.KEY_PITCH, value = pitch)
                scheduleSampleSpeak()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                cancelPendingSpeak()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                speakSample()
            }
        })
    }

    /**
     * Volume slider: 0% to 100%.
     * SeekBar range 0–100.
     */
    private fun setupVolumeSlider() {
        sliderVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val volume = progress / 100f
                valueVolume.text = "${progress}%"
                seekBar?.contentDescription = "Audio volume slider, current value $progress percent"
                saveAndApply(volumeKey = TTSManager.KEY_VOLUME, value = volume)
                scheduleSampleSpeak()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                cancelPendingSpeak()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                speakSample()
            }
        })
    }

    /**
     * Sets up the test voice button to speak a sample phrase on demand.
     */
    private fun setupTestButton() {
        btnTestVoice.setOnClickListener {
            speakSample()
        }
    }

    // ── Persistence & Application ───────────────────────────────────────────

    /**
     * Saves a setting to SharedPreferences and applies it to TTSManager.
     * Only the changed parameter is updated.
     */
    private fun saveAndApply(
        rateKey: String? = null,
        pitchKey: String? = null,
        volumeKey: String? = null,
        value: Float = 0f
    ) {
        val editor = prefs.edit()

        if (rateKey != null) {
            editor.putFloat(rateKey, value)
            ttsManager.setSpeechRate(value)
        }
        if (pitchKey != null) {
            editor.putFloat(pitchKey, value)
            ttsManager.setPitch(value)
        }
        if (volumeKey != null) {
            editor.putFloat(volumeKey, value)
            ttsManager.setVolume(value)
        }

        editor.apply()
    }

    // ── Language Selector ───────────────────────────────────────────────

    private fun setupLanguageSpinner() {
        val languageLabels = TTSManager.SUPPORTED_LANGUAGES.map { languageDisplayName(it) }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            languageLabels
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = adapter

        spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedKey = TTSManager.SUPPORTED_LANGUAGES[position]
                val currentKey = prefs.getString(TTSManager.KEY_LANGUAGE, TTSManager.LANGUAGE_ENGLISH)

                if (selectedKey != currentKey) {
                    ttsManager.setLanguage(selectedKey, requireContext())
                    valueLanguage.text = languageDisplayName(selectedKey)
                    speakSample()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) { /* no op */ }
        }
    }

    private fun languageDisplayName(key: String): String {
        return when (key) {
            TTSManager.LANGUAGE_MALAY  -> requireContext().getString(R.string.tts_lang_malay)
            TTSManager.LANGUAGE_CHINESE -> requireContext().getString(R.string.tts_lang_chinese)
            else           -> requireContext().getString(R.string.tts_lang_english)
        }
    }

    // ── Sample Speaking ─────────────────────────────────────────────────────

    /**
     * Speaks a sample phrase so the user can hear the current TTS settings.
     */
    private fun speakSample() {
        val phrase = when (ttsManager.getCurrentLanguageKey()) {
            TTSManager.LANGUAGE_MALAY  -> SAMPLE_PHRASE_MS
            TTSManager.LANGUAGE_CHINESE -> SAMPLE_PHRASE_ZH
            else           -> SAMPLE_PHRASE_EN
        }
        ttsManager.speakImmediate(phrase)
    }

    /**
     * Schedules a delayed sample speak after 500ms of slider inactivity.
     * This provides continuous feedback while dragging without spamming TTS.
     */
    private fun scheduleSampleSpeak() {
        cancelPendingSpeak()
        pendingSpeakRunnable = Runnable { speakSample() }
        handler.postDelayed(pendingSpeakRunnable!!, SAMPLE_DELAY_MS)
    }

    /**
     * Cancels any pending delayed sample speak.
     */
    private fun cancelPendingSpeak() {
        pendingSpeakRunnable?.let { handler.removeCallbacks(it) }
        pendingSpeakRunnable = null
    }

    override fun onDestroyView() {
        cancelPendingSpeak()
        ttsManager.onDestroy()
        super.onDestroyView()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun formatRate(rate: Float): String {
        return String.format("%.1fx", rate)
    }

    companion object {
        private const val TAG = "TtsSettingsFragment"

        /** Sample phrase spoken on slider change and test button. */
        const val SAMPLE_PHRASE_EN = "This is how I will sound with your chosen settings."
        const val SAMPLE_PHRASE_MS = "Ini adalah bagaimana saya akan berbunyi dengan tetapan pilihan anda."
        const val SAMPLE_PHRASE_ZH = "这是我使用您选择的设置时的声音。"

        /** Delay before speaking sample after slider stops moving (ms). */
        const val SAMPLE_DELAY_MS = 500L

        // ── Slider Range Constants ──────────────────────────────────────────

        /** Minimum speech rate (0.5x). */
        const val RATE_MIN = 0.5f
        /** Maximum speech rate (2.0x). */
        const val RATE_MAX = 2.0f
        /** Step size for speech rate (each SeekBar step = 0.01x). */
        const val RATE_STEP = 0.01f

        /** Minimum pitch (0.5). */
        const val PITCH_MIN = 0.5f
        /** Maximum pitch (1.5). */
        const val PITCH_MAX = 1.5f
        /** Step size for pitch (each SeekBar step = 0.01). */
        const val PITCH_STEP = 0.01f
    }
}
