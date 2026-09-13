package com.voiceqa.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LlmResultValidatorTest {

    @Test
    fun testParseStandardValidJson() {
        val raw = """
        {
          "has_question": true,
          "questions": [
            {
              "question": "故宫几点关门？",
              "answer": "故宫通常下午五点停止入场并关门。",
              "source_segment_ids": [99]
            }
          ],
          "message": "识别到 1 个问题"
        }
        """.trimIndent()

        val result = LlmResultValidator.parseAndValidate(raw)
        assertTrue(result.hasQuestion)
        assertEquals(1, result.questions.size)
        assertEquals("故宫几点关门？", result.questions[0].question)
        assertEquals("故宫通常下午五点停止入场并关门。", result.questions[0].answer)
        assertEquals(listOf(99L), result.questions[0].sourceSegmentIds)
    }

    @Test
    fun testParseJsonWithMarkdownFences() {
        val raw = """
        一些开头的无用说明...
        ```json
        {
          "has_question": true,
          "questions": [
            {
              "question": "门票多少钱",
              "answer": "淡季40元，旺季60元。",
              "source_segment_ids": [100]
            }
          ],
          "message": "识别到问题"
        }
        ```
        结尾补充文字
        """.trimIndent()

        val result = LlmResultValidator.parseAndValidate(raw)
        assertTrue(result.hasQuestion)
        assertEquals(1, result.questions.size)
        assertEquals("门票多少钱", result.questions[0].question)
        assertEquals("淡季40元，旺季60元。", result.questions[0].answer)
    }

    @Test
    fun testParseNoQuestionResponse() {
        val raw = """
        {
          "has_question": false,
          "questions": [],
          "message": "未识别到问题"
        }
        """.trimIndent()

        val result = LlmResultValidator.parseAndValidate(raw)
        assertFalse(result.hasQuestion)
        assertTrue(result.questions.isEmpty())
        assertEquals("未识别到问题", result.message)
    }

    @Test
    fun testInvalidJsonThrowsLlmValidationException() {
        val invalidRaw = "这不是一个合法的 JSON 字符串"
        try {
            LlmResultValidator.parseAndValidate(invalidRaw)
            fail("Should have thrown LlmValidationException")
        } catch (e: LlmValidationException) {
            assertTrue(e.message!!.contains("未能从模型响应中提取出有效 JSON"))
        }
    }
}
