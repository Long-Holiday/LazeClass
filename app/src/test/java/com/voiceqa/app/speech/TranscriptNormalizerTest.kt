package com.voiceqa.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TranscriptNormalizerTest {

    @Before
    fun setUp() {
        TranscriptNormalizer.clearCache()
    }

    @Test
    fun testNormalizeQuestion() {
        val q1 = "北京有哪些著名景点？"
        val q2 = "  北京有哪些著名景点...  "
        val q3 = "北京有哪些著名景点!"

        assertEquals("北京有哪些著名景点", TranscriptNormalizer.normalizeQuestion(q1))
        assertEquals("北京有哪些著名景点", TranscriptNormalizer.normalizeQuestion(q2))
        assertEquals("北京有哪些著名景点", TranscriptNormalizer.normalizeQuestion(q3))

        val englishQ1 = "What is the capital of France?"
        val englishQ2 = "  what is the capital of france ?  "
        assertEquals("whatisthecapitaloffrance", TranscriptNormalizer.normalizeQuestion(englishQ1))
        assertEquals("whatisthecapitaloffrance", TranscriptNormalizer.normalizeQuestion(englishQ2))
    }

    @Test
    fun testSha256Consistency() {
        val hash1 = TranscriptNormalizer.sha256("北京有哪些著名景点")
        val hash2 = TranscriptNormalizer.sha256("北京有哪些著名景点")
        val hash3 = TranscriptNormalizer.sha256("上海有哪些著名景点")

        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
        assertTrue(hash1 != hash3)
    }

    @Test
    fun testFiveMinuteDeduplication() {
        val hash = TranscriptNormalizer.sha256("故宫几点关门")
        val initialTime = 1000000L

        // First occurrence: not duplicate
        val isDup1 = TranscriptNormalizer.isDuplicateAndRecord(hash, currentTimeMs = initialTime)
        assertFalse(isDup1)

        // Within 5 minutes (e.g. 2 minutes later = 120,000ms): should be duplicate!
        val isDup2 = TranscriptNormalizer.isDuplicateAndRecord(hash, currentTimeMs = initialTime + 120_000L)
        assertTrue(isDup2)

        // After 5 minutes (e.g. 5 minutes and 1 second later = 300,001ms): expired, not duplicate!
        val isDup3 = TranscriptNormalizer.isDuplicateAndRecord(hash, currentTimeMs = initialTime + 300_001L)
        assertFalse(isDup3)
    }
}
