package dev.octoshrimpy.quik.interactor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecuteAutomationsTest {

    @Test
    fun testTruncateUtf8_shortAscii() {
        val input = "Hello, world!"
        val result = ExecuteAutomations.truncateUtf8(input, 100)
        assertEquals("Hello, world!", result)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 100)
    }

    @Test
    fun testTruncateUtf8_exactBoundary() {
        val input = "12345"
        val result = ExecuteAutomations.truncateUtf8(input, 5)
        assertEquals("12345", result)
        assertEquals(5, result.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun testTruncateUtf8_truncateAscii() {
        val input = "abcdefghij"
        val result = ExecuteAutomations.truncateUtf8(input, 5)
        assertEquals("abcde", result)
        assertEquals(5, result.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun testTruncateUtf8_empty() {
        val input = ""
        val result = ExecuteAutomations.truncateUtf8(input, 10)
        assertEquals("", result)
    }

    @Test
    fun testTruncateUtf8_multibyteEmojisDoesNotCutSurrogates() {
        // Emojis are 4 bytes each: 🔥 (4 bytes), 🚀 (4 bytes), 🎉 (4 bytes)
        val input = "🔥🔥🔥" // 12 bytes
        // Truncate at 7 bytes: should fit only 1 emoji (4 bytes), because 2nd needs 8 bytes
        val result = ExecuteAutomations.truncateUtf8(input, 7)
        assertEquals("🔥", result)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 7)
    }

    @Test
    fun testTruncateUtf8_japaneseCharacters() {
        // Japanese characters: こんにちは (each is 3 bytes, total 15 bytes)
        val input = "こんにちは"
        // Truncate at 7 bytes: 2 characters = 6 bytes, 3 characters = 9 bytes
        val result = ExecuteAutomations.truncateUtf8(input, 7)
        assertEquals("こん", result)
        assertEquals(6, result.toByteArray(Charsets.UTF_8).size)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 7)
    }

    @Test
    fun testEmptyCollectionJoinToStringDoesNotCrash() {
        val emptyList = emptyList<String>()
        // In SmsReceivedReceiver, empty collection joinToString returns "" without throwing
        val result = emptyList.joinToString("")
        assertEquals("", result)
    }

    @Test
    fun testTruncateUtf8_zeroAndNegative() {
        assertEquals("", ExecuteAutomations.truncateUtf8("hello", 0))
        assertEquals("", ExecuteAutomations.truncateUtf8("hello", -1))
        assertEquals("", ExecuteAutomations.truncateUtf8("hello", -100))
    }

    @Test(expected = java.util.concurrent.CancellationException::class)
    fun testInterruptibleCharSequence_interruptedThrowsCancellationException() {
        val input = "abcdef"
        Thread.currentThread().interrupt()
        try {
            val cls = Class.forName("dev.octoshrimpy.quik.interactor.ExecuteAutomations\$InterruptibleCharSequence")
            val constructor = cls.getDeclaredConstructor(CharSequence::class.java).apply { isAccessible = true }
            val seq = constructor.newInstance(input) as CharSequence
            seq[0]
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun testSafeRegexMatch_normalMatch() {
        assertTrue(ExecuteAutomations.safeRegexMatch("hello", false, "Say hello to my friend"))
        assertTrue(ExecuteAutomations.safeRegexMatch("^[0-9]+$", false, "123456"))
    }

    @Test
    fun testSafeRegexMatch_normalMismatch() {
        assertFalse(ExecuteAutomations.safeRegexMatch("goodbye", false, "Say hello to my friend"))
        assertFalse(ExecuteAutomations.safeRegexMatch("^[0-9]+$", false, "1234a56"))
    }

    @Test
    fun testSafeRegexMatch_invalidPattern() {
        assertFalse(ExecuteAutomations.safeRegexMatch("[unclosed-bracket", false, "test"))
    }

    @Test
    fun testSafeRegexMatch_excessivePatternLength() {
        val longPattern = "a".repeat(300)
        assertFalse(ExecuteAutomations.safeRegexMatch(longPattern, false, "a"))
    }
}
