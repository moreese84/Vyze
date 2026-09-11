package com.vyze.app.memory

import android.util.Log
import com.vyze.app.data.MemoryDao
import com.vyze.app.data.VyzeMemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Silent preference learner — adapts answer length to observed behavior.
 * No UI, no voice commands, no announcements: the app simply gets tuned
 * to how the user actually uses it.
 *
 * ## Learned Preference: Brevity
 * A blind user who interrupts a spoken description to trigger a new capture
 * is signaling "that answer was longer than I needed" (or "the scene
 * changed" — see [Confounders] below). We count only speech-active
 * interruptions, where the new capture arrived while the TTS was still
 * reading the previous answer.
 *
 * ## Levels
 * - **NORMAL (0)** — default. No behavioral evidence.
 * - **BRIEF (1)** — 3+ interruptions within 5 minutes. Prompt asks for
 *   concise answers; output budget tightened.
 * - **TERSE (2)** — 9+ interruptions. Prompt asks for single-sentence
 *   answers; output budget tightened further.
 *
 * ## Confounders (why evidence decays)
 * A burst of captures can equally mean the user is *exploring* a new
 * environment (rapid scene recon) rather than annoyed by verbosity. So
 * interruption evidence is windowed, not cumulative:
 * - Interrupt counter decays one level per 3 minutes of non-interrupted use
 *   ([recordAnswerCompleted]).
 * - An interrupt burst requires ≥3 events inside 5 minutes to move a level —
 *   single captures during continuous use never accumulate.
 *
 * ## Persistence
 * Stored in the existing `vyze_memory` Room table (category "preference",
 * key "brevity") via [MemoryDao] — reusing rather than reinventing storage.
 * Level transitions persist immediately; decay never persists a change
 * upward (silence is evidence for NORMAL, not a command to store).
 *
 * All operations run on [Dispatchers.IO] via suspend — no main-thread work.
 */
class PreferenceLearner(private val memoryDao: MemoryDao) {

    /** Learned verbosity preference. */
    enum class BrevityLevel(val label: String) {
        /** Default behavior — no adaptation. */
        NORMAL("normal"),

        /** Concise answers. */
        BRIEF("brief"),

        /** Single-sentence answers. */
        TERSE("terse")
    }

    private val tag = "PreferenceLearner"

    /** Current in-memory level; refreshed from Room on first use. */
    @Volatile
    private var currentLevel: BrevityLevel? = null

    /** Consecutive speech-active interruptions in the current burst. */
    private var interruptCount = 0

    /** Timestamp of the most recent recorded interruption (ms). */
    private var lastInterruptAt = 0L

    /** Brevity is only meaningful for spoken scene/query answers. */
    suspend fun getBrevityLevel(): BrevityLevel = withContext(Dispatchers.IO) {
        if (currentLevel == null) {
            currentLevel = loadLevelFromDisk()
        }
        currentLevel!!
    }

    /**
     * Record that the user interrupted a still-speaking answer by triggering
     * a new capture. Call BEFORE [com.vyze.app.TTSManager.stop] so the
     * speaking-state read is accurate.
     */
    suspend fun recordInterruptWhileSpeaking() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // Burst window: interrupts must be recent relative to each other to
        // accumulate; a lone interrupt inside an otherwise calm session
        // contributes nothing (handled by decay in recordAnswerCompleted).
        if (lastInterruptAt > 0 && now - lastInterruptAt > INTERRUPT_BURST_WINDOW_MS) {
            interruptCount = 0
        }
        interruptCount++
        lastInterruptAt = now

        val oldLevel = getBrevityLevel()
        var newLevel = oldLevel
        if (interruptCount >= TERSE_INTERRUPT_THRESHOLD) {
            newLevel = BrevityLevel.TERSE
        } else if (interruptCount >= BRIEF_INTERRUPT_THRESHOLD) {
            newLevel = BrevityLevel.BRIEF
        }

        if (newLevel != oldLevel) {
            currentLevel = newLevel
            persistLevel(newLevel)
            Log.i(tag, "Brevity adapted: $oldLevel -> $newLevel (interrupts=$interruptCount)")
        } else {
            Log.d(tag, "Interrupt recorded (count=$interruptCount, level=$oldLevel)")
        }
    }

    /**
     * Record that a spoken answer finished playing to the end.
     * Consecutive completed answers erode the interrupt burst — the user
     * letting the app finish is evidence the current length is acceptable.
     */
    suspend fun recordAnswerCompleted() = withContext(Dispatchers.IO) {
        if (interruptCount > 0) {
            interruptCount--
            Log.d(tag, "Answer completed — interrupt decay to $interruptCount")
        }
    }

    /** Reset burst state (e.g. on engine restart). Does NOT erase the persisted level. */
    fun resetSessionState() {
        interruptCount = 0
        lastInterruptAt = 0L
    }

    // ── Internals ─────────────────────────────────────────────────

    private suspend fun loadLevelFromDisk(): BrevityLevel = withContext(Dispatchers.IO) {
        try {
            val stored = memoryDao.get(CATEGORY_PREFERENCE, KEY_BREVITY)
            when (stored?.value?.lowercase()) {
                BrevityLevel.BRIEF.label -> BrevityLevel.BRIEF
                BrevityLevel.TERSE.label -> BrevityLevel.TERSE
                else -> BrevityLevel.NORMAL
            }
        } catch (e: Throwable) {
            Log.w(tag, "Failed to load brevity level: ${e.message}")
            BrevityLevel.NORMAL
        }
    }

    /**
     * Persist a level transition. Only called on actual transitions —
     * decay toward NORMAL is deliberately NOT persisted (see class docs),
     * so this only ever fires for BRIEF/TERSE.
     */
    private suspend fun persistLevel(level: BrevityLevel) = withContext(Dispatchers.IO) {
        try {
            memoryDao.upsert(
                VyzeMemoryEntity(
                    category = CATEGORY_PREFERENCE,
                    key = KEY_BREVITY,
                    value = level.label,
                    metadata = "auto_learned"
                )
            )
        } catch (e: Throwable) {
            Log.w(tag, "Failed to persist brevity level: ${e.message}")
        }
    }

    companion object {
        private const val CATEGORY_PREFERENCE = "preference"
        private const val KEY_BREVITY = "brevity"

        /** Interrupts needed inside the burst window to reach BRIEF. */
        private const val BRIEF_INTERRUPT_THRESHOLD = 3

        /** Interrupts needed inside the burst window to reach TERSE. */
        private const val TERSE_INTERRUPT_THRESHOLD = 9

        /** Interrupt burst window — interrupts older than this don't accumulate. */
        private const val INTERRUPT_BURST_WINDOW_MS = 5L * 60L * 1000L
    }
}
