package com.voiceqa.app.batching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TranscriptBufferTest {

    private lateinit var buffer: TranscriptBuffer
    private val policy = BatchPolicy(
        silenceTimeoutMs = 1500L,
        maximumWaitMs = 10000L,
        maximumChars = 120,
        minimumChars = 8,
        contextChars = 200
    )

    @Before
    fun setUp() {
        buffer = TranscriptBuffer()
    }

    @Test
    fun testAppendAndPendingChars() {
        assertEquals(0, buffer.pendingChars())
        assertEquals(0, buffer.pendingCount())

        buffer.append(
            TranscriptSegment(
                id = 1L,
                sessionId = "s1",
                text = "你好世界",
                isFinal = true,
                startTimeMs = 0L,
                endTimeMs = 1000L,
                createdAtMs = 1000L
            )
        )

        assertEquals(4, buffer.pendingChars())
        assertEquals(1, buffer.pendingCount())
    }

    @Test
    fun testSnapshotBelowMinimumCharsReturnsNullUnlessForced() {
        buffer.append(
            TranscriptSegment(
                id = 1L,
                sessionId = "s1",
                text = "短文本", // 3 chars < 8 minimumChars
                isFinal = true,
                startTimeMs = 0L,
                endTimeMs = 1000L,
                createdAtMs = 1000L
            )
        )

        // Without force: should return null
        val normalBatch = buffer.snapshot(policy, force = false)
        assertNull(normalBatch)
        assertEquals(3, buffer.pendingChars())

        // With force: should return batch
        val forcedBatch = buffer.snapshot(policy, force = true)
        assertNotNull(forcedBatch)
        assertEquals("s1", forcedBatch!!.sessionId)
        assertEquals(listOf(1L), forcedBatch.segmentIds)
        assertEquals("[1] 短文本", forcedBatch.newText)
        assertEquals(0, buffer.pendingChars()) // pending was emptied
    }

    @Test
    fun testCommitAndRollbackWorkflow() {
        buffer.append(
            TranscriptSegment(
                id = 1L,
                sessionId = "s1",
                text = "这是第一个超过八个字符的长文本段落",
                isFinal = true,
                startTimeMs = 0L,
                endTimeMs = 1000L,
                createdAtMs = 1000L
            )
        )

        val batch = buffer.snapshot(policy, force = false)
        assertNotNull(batch)
        assertEquals(0, buffer.pendingChars())

        // Simulate failure -> Rollback
        buffer.rollback(batch!!.id)
        assertEquals(1, buffer.pendingCount())
        assertEquals("这是第一个超过八个字符的长文本段落".length, buffer.pendingChars())

        // Re-snapshot and commit
        val batch2 = buffer.snapshot(policy, force = false)
        assertNotNull(batch2)
        buffer.commit(batch2!!.id)
        assertEquals(0, buffer.pendingChars())

        // Now add another segment, check that committed segments become context
        buffer.append(
            TranscriptSegment(
                id = 2L,
                sessionId = "s1",
                text = "这是第二个段落测试上下文生成",
                isFinal = true,
                startTimeMs = 1000L,
                endTimeMs = 2000L,
                createdAtMs = 2000L
            )
        )

        val batch3 = buffer.snapshot(policy, force = false)
        assertNotNull(batch3)
        assertTrue(batch3!!.context.contains("[1] 这是第一个超过八个字符的长文本段落"))
        assertTrue(batch3.newText.contains("[2] 这是第二个段落测试上下文生成"))
    }
}
