package com.voiceqa.app.llm

import com.voiceqa.app.batching.AnalysisBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPromptFactoryTest {

    @Test
    fun testSystemPromptRequirements() {
        val systemPrompt = LlmPromptFactory.SYSTEM_PROMPT
        assertTrue(systemPrompt.contains("智能助理"))
        assertTrue(systemPrompt.contains("纯文本回答"))
        assertTrue(systemPrompt.contains("无需使用 JSON 格式包装"))
        assertTrue(systemPrompt.contains("直接针对用户发送的语音转写内容进行清晰、准确、自然的直接回复"))
    }

    @Test
    fun testUserPromptFormattingWithoutContext() {
        val batch = AnalysisBatch(
            id = "b1",
            sessionId = "s1",
            segmentIds = listOf(101L),
            context = "   ",
            newText = "  今天天气怎么样？  ",
            previouslyAnswered = emptyList()
        )

        val userPrompt = LlmPromptFactory.createUserPrompt(batch)
        assertEquals("今天天气怎么样？", userPrompt)
    }

    @Test
    fun testUserPromptFormattingWithContext() {
        val batch = AnalysisBatch(
            id = "b2",
            sessionId = "s1",
            segmentIds = listOf(102L),
            context = "我们在讨论周末去哪里露营",
            newText = "有什么好推荐吗？",
            previouslyAnswered = emptyList()
        )

        val userPrompt = LlmPromptFactory.createUserPrompt(batch)
        assertTrue(userPrompt.contains("[前文背景]"))
        assertTrue(userPrompt.contains("我们在讨论周末去哪里露营"))
        assertTrue(userPrompt.contains("[用户当前发言]"))
        assertTrue(userPrompt.contains("有什么好推荐吗？"))
    }
}
