package com.voiceqa.app.llm

import com.voiceqa.app.batching.AnalysisBatch
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPromptFactoryTest {

    @Test
    fun testSystemPromptRequirements() {
        val systemPrompt = LlmPromptFactory.SYSTEM_PROMPT
        assertTrue(systemPrompt.contains("你是语音转写问题识别与回答模块"))
        assertTrue(systemPrompt.contains("<context>"))
        assertTrue(systemPrompt.contains("<new_text>"))
        assertTrue(systemPrompt.contains("<previously_answered>"))
        assertTrue(systemPrompt.contains("has_question"))
        assertTrue(systemPrompt.contains("questions"))
    }

    @Test
    fun testUserPromptFormatting() {
        val batch = AnalysisBatch(
            id = "b1",
            sessionId = "s1",
            segmentIds = listOf(99L, 100L),
            context = "[98] 我们刚才讨论了北京旅游",
            newText = "[99] 那故宫几点关门\n[100] 门票多少钱",
            previouslyAnswered = listOf("北京有哪些著名景点")
        )

        val userPrompt = LlmPromptFactory.createUserPrompt(batch)
        assertTrue(userPrompt.contains("<context>\n[98] 我们刚才讨论了北京旅游\n</context>"))
        assertTrue(userPrompt.contains("<new_text>\n[99] 那故宫几点关门\n[100] 门票多少钱\n</new_text>"))
        assertTrue(userPrompt.contains("<previously_answered>\n- 北京有哪些著名景点\n</previously_answered>"))
    }
}
