package com.vyze.app

import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [CurrencyAnalyzer].
 *
 * Uses Robolectric to provide [SystemClock.uptimeMillis] for debouncing tests.
 * Covers MYR banknotes, coins, USD banknotes, shorthand coins, debouncing,
 * and invalid format rejection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CurrencyAnalyzerTest {

    private lateinit var analyzer: CurrencyAnalyzer

    @Before
    fun setUp() {
        analyzer = CurrencyAnalyzer()
        analyzer.clearCooldowns()
    }

    // ── MYR Banknotes ────────────────────────────────────────────────────

    @Test
    fun `RM100 detected`() {
        val result = analyzer.analyzeForCurrency("RM100")
        assertEquals(1, result.size)
        assertEquals("100 Ringgit note detected", result[0])
    }

    @Test
    fun `RM50 with space detected`() {
        val result = analyzer.analyzeForCurrency("RM 50")
        assertEquals(1, result.size)
        assertEquals("50 Ringgit note detected", result[0])
    }

    @Test
    fun `RM20 detected`() {
        val result = analyzer.analyzeForCurrency("Price: RM20.00")
        assertEquals(1, result.size)
        assertEquals("20 Ringgit note detected", result[0])
    }

    @Test
    fun `RM10 detected`() {
        val result = analyzer.analyzeForCurrency("RM10")
        assertEquals(1, result.size)
        assertEquals("10 Ringgit note detected", result[0])
    }

    @Test
    fun `RM5 detected`() {
        val result = analyzer.analyzeForCurrency("RM5")
        assertEquals(1, result.size)
        assertEquals("5 Ringgit note detected", result[0])
    }

    @Test
    fun `RM1 detected`() {
        val result = analyzer.analyzeForCurrency("RM1")
        assertEquals(1, result.size)
        assertEquals("1 Ringgit note detected", result[0])
    }

    @Test
    fun `RM100 case insensitive`() {
        val result = analyzer.analyzeForCurrency("rm100")
        assertEquals(1, result.size)
        assertEquals("100 Ringgit note detected", result[0])
    }

    @Test
    fun `RM50 mixed case`() {
        val result = analyzer.analyzeForCurrency("Rm 50 note")
        assertEquals(1, result.size)
        assertEquals("50 Ringgit note detected", result[0])
    }

    // ── MYR Coins ────────────────────────────────────────────────────────

    @Test
    fun `50 sen detected`() {
        val result = analyzer.analyzeForCurrency("50 sen")
        assertEquals(1, result.size)
        assertEquals("50 sen coin detected", result[0])
    }

    @Test
    fun `20 sen without space detected`() {
        val result = analyzer.analyzeForCurrency("20sen")
        assertEquals(1, result.size)
        assertEquals("20 sen coin detected", result[0])
    }

    @Test
    fun `10 sen detected`() {
        val result = analyzer.analyzeForCurrency("10 Sen")
        assertEquals(1, result.size)
        assertEquals("10 sen coin detected", result[0])
    }

    @Test
    fun `5 sen detected`() {
        val result = analyzer.analyzeForCurrency("5 sen")
        assertEquals(1, result.size)
        assertEquals("5 sen coin detected", result[0])
    }

    // ── Shorthand Coins ──────────────────────────────────────────────────

    @Test
    fun `50c detected`() {
        val result = analyzer.analyzeForCurrency("50c")
        assertEquals(1, result.size)
        assertEquals("50 sen coin detected", result[0])
    }

    @Test
    fun `20c with space detected`() {
        val result = analyzer.analyzeForCurrency("20 c")
        assertEquals(1, result.size)
        assertEquals("20 sen coin detected", result[0])
    }

    @Test
    fun `10c detected`() {
        val result = analyzer.analyzeForCurrency("10c")
        assertEquals(1, result.size)
        assertEquals("10 sen coin detected", result[0])
    }

    @Test
    fun `5c detected`() {
        val result = analyzer.analyzeForCurrency("5c")
        assertEquals(1, result.size)
        assertEquals("5 sen coin detected", result[0])
    }

    @Test
    fun `50c uppercase detected`() {
        val result = analyzer.analyzeForCurrency("50C")
        assertEquals(1, result.size)
        assertEquals("50 sen coin detected", result[0])
    }

    // ── USD Banknotes ────────────────────────────────────────────────────

    @Test
    fun `$100 detected`() {
        val result = analyzer.analyzeForCurrency("$100")
        assertEquals(1, result.size)
        assertEquals("100 Dollar note detected", result[0])
    }

    @Test
    fun `$50 with space detected`() {
        val result = analyzer.analyzeForCurrency("$ 50")
        assertEquals(1, result.size)
        assertEquals("50 Dollar note detected", result[0])
    }

    @Test
    fun `$20 detected`() {
        val result = analyzer.analyzeForCurrency("$20")
        assertEquals(1, result.size)
        assertEquals("20 Dollar note detected", result[0])
    }

    @Test
    fun `$10 detected`() {
        val result = analyzer.analyzeForCurrency("$10")
        assertEquals(1, result.size)
        assertEquals("10 Dollar note detected", result[0])
    }

    @Test
    fun `$5 detected`() {
        val result = analyzer.analyzeForCurrency("$5")
        assertEquals(1, result.size)
        assertEquals("5 Dollar note detected", result[0])
    }

    @Test
    fun `$1 detected`() {
        val result = analyzer.analyzeForCurrency("$1")
        assertEquals(1, result.size)
        assertEquals("1 Dollar note detected", result[0])
    }

    @Test
    fun `$100 with decimal not matched as $1000`() {
        val result = analyzer.analyzeForCurrency("$100.50")
        assertTrue(result.any { it.contains("100 Dollar") })
    }

    @Test
    fun `$1000 should NOT match $100 pattern`() {
        val result = analyzer.analyzeForCurrency("$1000")
        assertFalse(result.any { it.contains("100 Dollar") })
    }

    // ── Multiple Denominations ───────────────────────────────────────────

    @Test
    fun `multiple denominations detected in single text`() {
        val result = analyzer.analyzeForCurrency("RM50 and $20")
        assertEquals(2, result.size)
        assertTrue(result.contains("50 Ringgit note detected"))
        assertTrue(result.contains("20 Dollar note detected"))
    }

    // ── Speech Debouncing ────────────────────────────────────────────────

    @Test
    fun `second call within cooldown returns empty`() {
        val first = analyzer.analyzeForCurrency("RM50")
        assertEquals(1, first.size)

        // Second call immediately — should be debounced
        val second = analyzer.analyzeForCurrency("RM50")
        assertEquals(0, second.size)
    }

    @Test
    fun `clearCooldowns allows immediate re-detection`() {
        val first = analyzer.analyzeForCurrency("RM50")
        assertEquals(1, first.size)

        analyzer.clearCooldowns()

        val second = analyzer.analyzeForCurrency("RM50")
        assertEquals(1, second.size)
    }

    @Test
    fun `different denominations not debounced`() {
        val rm50 = analyzer.analyzeForCurrency("RM50")
        assertEquals(1, rm50.size)

        val rm20 = analyzer.analyzeForCurrency("RM20")
        assertEquals(1, rm20.size)
    }

    // ── Invalid Formats ──────────────────────────────────────────────────

    @Test
    fun `empty text returns empty`() {
        val result = analyzer.analyzeForCurrency("")
        assertEquals(0, result.size)
    }

    @Test
    fun `blank text returns empty`() {
        val result = analyzer.analyzeForCurrency("   ")
        assertEquals(0, result.size)
    }

    @Test
    fun `no currency in text returns empty`() {
        val result = analyzer.analyzeForCurrency("Hello world this is a test")
        assertEquals(0, result.size)
    }

    @Test
    fun `containsCurrency returns true for valid text`() {
        assertTrue(analyzer.containsCurrency("RM50"))
        assertTrue(analyzer.containsCurrency("$20"))
        assertTrue(analyzer.containsCurrency("50 sen"))
        assertTrue(analyzer.containsCurrency("50c"))
    }

    @Test
    fun `containsCurrency returns false for empty text`() {
        assertFalse(analyzer.containsCurrency(""))
        assertFalse(analyzer.containsCurrency("   "))
    }

    @Test
    fun `containsCurrency returns false for no currency`() {
        assertFalse(analyzer.containsCurrency("Hello world"))
    }

    @Test
    fun `analyzeSingle returns first result`() {
        val result = analyzer.analyzeSingle("RM50")
        assertNotNull(result)
        assertEquals("50 Ringgit note detected", result)
    }

    @Test
    fun `analyzeSingle returns null for no match`() {
        val result = analyzer.analyzeSingle("Hello world")
        assertNull(result)
    }
}
