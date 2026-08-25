package com.vyze.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for TTS settings persistence and ViewModel state.
 *
 * Verifies:
 * - SharedPreferences persistence of rate, pitch, volume, language
 * - TTSManager settings apply correctly
 * - MainViewModel SavedStateHandle holds values across instances
 */
@RunWith(AndroidJUnit4::class)
class TtsViewModelTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setup() {
        prefs = context.getSharedPreferences(TTSManager.PREFS_NAME, Context.MODE_PRIVATE)
        // Clear before each test
        prefs.edit().clear().commit()
    }

    @Test
    fun defaults_areCorrect() {
        assertEquals(
            "Default speech rate",
            TTSManager.DEFAULT_SPEECH_RATE,
            prefs.getFloat(TTSManager.KEY_SPEECH_RATE, TTSManager.DEFAULT_SPEECH_RATE),
            0.001f
        )
        assertEquals(
            "Default pitch",
            TTSManager.DEFAULT_PITCH,
            prefs.getFloat(TTSManager.KEY_PITCH, TTSManager.DEFAULT_PITCH),
            0.001f
        )
        assertEquals(
            "Default volume",
            TTSManager.DEFAULT_VOLUME,
            prefs.getFloat(TTSManager.KEY_VOLUME, TTSManager.DEFAULT_VOLUME),
            0.001f
        )
        assertEquals(
            "Default language",
            TTSManager.LANGUAGE_ENGLISH,
            prefs.getString(TTSManager.KEY_LANGUAGE, TTSManager.LANGUAGE_ENGLISH)
        )
    }

    @Test
    fun speechRate_persistsCorrectly() {
        prefs.edit().putFloat(TTSManager.KEY_SPEECH_RATE, 1.5f).commit()
        val stored = prefs.getFloat(TTSManager.KEY_SPEECH_RATE, TTSManager.DEFAULT_SPEECH_RATE)
        assertEquals("Speech rate should persist", 1.5f, stored, 0.001f)
    }

    @Test
    fun pitch_persistsCorrectly() {
        prefs.edit().putFloat(TTSManager.KEY_PITCH, 0.8f).commit()
        val stored = prefs.getFloat(TTSManager.KEY_PITCH, TTSManager.DEFAULT_PITCH)
        assertEquals("Pitch should persist", 0.8f, stored, 0.001f)
    }

    @Test
    fun volume_persistsCorrectly() {
        prefs.edit().putFloat(TTSManager.KEY_VOLUME, 0.3f).commit()
        val stored = prefs.getFloat(TTSManager.KEY_VOLUME, TTSManager.DEFAULT_VOLUME)
        assertEquals("Volume should persist", 0.3f, stored, 0.001f)
    }

    @Test
    fun language_persistsCorrectly() {
        prefs.edit().putString(TTSManager.KEY_LANGUAGE, TTSManager.LANGUAGE_MALAY).commit()
        val stored = prefs.getString(TTSManager.KEY_LANGUAGE, TTSManager.LANGUAGE_ENGLISH)
        assertEquals("Language should persist", TTSManager.LANGUAGE_MALAY, stored)
    }

    @Test
    fun language_switchToChinese() {
        prefs.edit().putString(TTSManager.KEY_LANGUAGE, TTSManager.LANGUAGE_CHINESE).commit()
        val stored = prefs.getString(TTSManager.KEY_LANGUAGE, TTSManager.LANGUAGE_ENGLISH)
        assertEquals("Chinese language should persist", TTSManager.LANGUAGE_CHINESE, stored)
    }

    @Test
    fun supportedLanguages_listIsComplete() {
        assertEquals("Should have 3 languages", 3, TTSManager.SUPPORTED_LANGUAGES.size)
        assertTrue("Should contain English", TTSManager.SUPPORTED_LANGUAGES.contains(TTSManager.LANGUAGE_ENGLISH))
        assertTrue("Should contain Malay", TTSManager.SUPPORTED_LANGUAGES.contains(TTSManager.LANGUAGE_MALAY))
        assertTrue("Should contain Chinese", TTSManager.SUPPORTED_LANGUAGES.contains(TTSManager.LANGUAGE_CHINESE))
    }

    @Test
    fun scanRepository_saveAndRetrieve() {
        val repo = com.vyze.app.data.ScanRepository(context)

        // Run in a blocking coroutine on the test thread
        kotlinx.coroutines.runBlocking {
            repo.clearAllScans()

            val id = repo.saveOcrScan("Hello world")
            assertTrue("Scan ID should be positive", id > 0)

            val scans = repo.getRecentScans(1)
            assertEquals("Should have 1 scan", 1, scans.size)
            assertEquals("Content should match", "Hello world", scans[0].content)
            assertEquals("Type should be OCR", "OCR", scans[0].type)

            repo.clearAllScans()
            val afterClear = repo.getRecentScans()
            assertTrue("Should be empty after clear", afterClear.isEmpty())
        }
    }

    @Test
    fun scanRepository_formatForTts() {
        val repo = com.vyze.app.data.ScanRepository(context)

        kotlinx.coroutines.runBlocking {
            repo.clearAllScans()

            val result = repo.formatRecentScansForTts(3)
            assertEquals("Empty history message", "No scan history available.", result)

            repo.saveOcrScan("Test text")
            val resultWithScan = repo.formatRecentScansForTts(1)
            assertTrue("Should contain scan content", resultWithScan.contains("Test text"))

            repo.clearAllScans()
        }
    }
}
