package com.voiceqa.app.llm

import com.voiceqa.app.batching.AnalysisBatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeLlmProviderTest {

    @Test
    fun testFakeLlmProviderReturnsSimulatedAnswer() = runBlocking {
        val provider = FakeLlmProvider()
        val batch = AnalysisBatch(
            id = "b1",
            sessionId = "s1",
            segmentIds = listOf(201L),
            context = "",
            newText = "测试模拟回复",
            previouslyAnswered = emptyList()
        )

        val result = provider.analyze(batch, LlmSettings(), null)

        assertTrue(result.hasQuestion)
        assertEquals(1, result.questions.size)
        assertEquals("测试模拟回复", result.questions[0].question)
        assertEquals("这是离线模拟回复：已收到您发送的“测试模拟回复”。", result.questions[0].answer)
        assertEquals(listOf(201L), result.questions[0].sourceSegmentIds)
        assertEquals("已模拟回复", result.message)
    }
}
