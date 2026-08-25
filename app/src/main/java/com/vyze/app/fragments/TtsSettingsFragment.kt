package com.vyze.app.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
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
        setupSpeechRateSlider()
        setupPitchSlider()
        setupVolumeSlider()
        setupTestButton()
    }

    /**
     * Loads saved settings from SharedPreferences and positions sliders accordingly.
     */
    private fun loadSavedSettings() {
        val savedRate = prefs.getFloat(TTSManager.KEY_SPEECH_RATE, TTSManager.DEFAULT_SPEECH_RATE)
        val savedPitch = prefs.getFloat(TTSManager.KEY_PITCH, TTSManager.DEFAULT_PITCH)
        val savedVolume = prefs.getFloat(TTSManager.KEY_VOLUME, TTSManager.DEFAULT_VOLUME)

        // Convert values to SeekBar positions
        // Rate: 0.5–2.0 mapped to 0–150 (each step = 0.01)
        sliderSpeechRate.progress = ((savedRate - RATE_MIN) / RATE_STEP).toInt()
        valueSpeechRate.text = formatRate(savedRate)

        // Pitch: 0.5–1.5 mapped to 0–100 (each step = 0.01)
        sliderPitch.progress = ((savedPitch - PITCH_MIN) / PITCH_STEP).toInt()
        valuePitch.text = String.format("%.2f", savedPitch)

        // Volume: 0–1 mapped to 0–100
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

    // ── Sample Speaking ─────────────────────────────────────────────────────

    /**
     * Speaks a sample phrase so the user can hear the current TTS settings.
     */
    private fun speakSample() {
        ttsManager.speakImmediate(SAMPLE_PHRASE)
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
        const val SAMPLE_PHRASE = "This is how I will sound with your chosen settings."

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
