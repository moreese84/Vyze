package com.vyze.app

/**
 * Coordinates TTS announcements with rate limiting and voice-command suppression.
 *
 * Extracted from [CameraFragment] to separate speech coordination from
 * UI lifecycle concerns. This class has NO dependency on:
 * - Fragment lifecycle
 * - Camera or ML pipeline
 * - Battery monitoring
 *
 * ## Responsibilities
 * 1. Rate-limit repeated error TTS messages (e.g. prevent "no... no... no..." loops)
 * 2. Suppress voice-command state announcements during user-initiated gestures
 *    (e.g. suppress "Listening stopped" when the user tapped to hear objects)
 * 3. Track first-listen state to avoid re-announcing "Listening for commands"
 *
 * ## State Flow
 * ```
 * User taps screen
 *   → suppressVoiceAnnouncements()
 *   → voiceCommandManager.stopListening()
 *   → [OD announcement plays]
 *
 * Voice recognizer restarts
 *   → onListeningStateChanged(true)
 *   → shouldAnnounceListening() returns false → no TTS
 *
 * Voice recognizer stops naturally
 *   → onListeningStateChanged(false)
 *   → shouldAnnounceStop() returns true (was suppressed) → no TTS
 *   → suppression cleared
 * ```
 */
class AnnouncementCoordinator(
    private val ttsManager: TTSManager
) {

    // ── Voice Suppression State ───────────────────────────────────

    /**
     * When true, voice-command state announcements ("Listening for commands...",
     * "Listening stopped.") are suppressed so they don't override in-progress
     * OD/OCR announcements.
     */
    @Volatile
    var voiceAnnouncementSuppressed = false
        private set

    /**
     * Tracks whether "Listening for commands..." has already been announced
     * for the current listening session. Prevents re-announcement on every
     * recognizer restart cycle.
     */
    @Volatile
    var voiceListeningAlreadyAnnounced = false
        private set

    // ── Rate Limiting State ───────────────────────────────────────

    /** Timestamp of the last rate-limited TTS call. */
    private var lastErrorTime = 0L

    /** Text of the last rate-limited TTS call. */
    private var lastErrorText = ""

    // ── Rate-Limited Speech ───────────────────────────────────────

    /**
     * Speak a message with a minimum cooldown between identical messages.
     * Prevents continuous error loops when the ML pipeline fails on every
     * frame (e.g. "no... no... no...").
     *
     * If the same message was spoken within [TTS_ERROR_COOLDOWN_MS],
     * the duplicate is silently dropped. Different messages are always
     * allowed through.
     */
    fun rateLimitSpeak(message: String) {
        val now = System.currentTimeMillis()
        if (message == lastErrorText && (now - lastErrorTime) < TTS_ERROR_COOLDOWN_MS) {
            return // Suppress duplicate within cooldown window
        }
        lastErrorTime = now
        lastErrorText = message
        ttsManager.speak(message)
    }

    // ── Voice Suppression Controls ────────────────────────────────

    /**
     * Suppress all voice-command state announcements.
     * Call when the user initiates a gesture (tap, double-tap, long-press)
     * so that OD/OCR TTS is not interrupted by "Listening stopped."
     */
    fun suppressVoiceAnnouncements() {
        voiceAnnouncementSuppressed = true
    }

    /**
     * Should "Listening for commands..." be announced?
     *
     * Returns true only if this is the FIRST time listening has started
     * in the current session AND voice announcements are not suppressed.
     */
    fun shouldAnnounceListening(): Boolean {
        return !voiceListeningAlreadyAnnounced && !voiceAnnouncementSuppressed
    }

    /**
     * Mark that "Listening for commands..." has been announced.
     * Call after the TTS speak call for the listening message.
     */
    fun markListeningAnnounced() {
        voiceListeningAlreadyAnnounced = true
    }

    /**
     * Called when voice listening stops.
     *
     * @return true if the stop was suppressed (caller should NOT speak
     *         "Listening stopped"), false if it should be announced.
     */
    fun onListeningStopped(): Boolean {
        voiceListeningAlreadyAnnounced = false
        val suppressed = voiceAnnouncementSuppressed
        voiceAnnouncementSuppressed = false
        return suppressed
    }

    // ── Companion ─────────────────────────────────────────────────

    companion object {
        /** Minimum milliseconds between repeated error TTS announcements. */
        private const val TTS_ERROR_COOLDOWN_MS = 3000L
    }
}
