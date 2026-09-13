package com.voiceqa.app.llm

import com.voiceqa.app.batching.AnalysisBatch

object LlmPromptFactory {

    const val SYSTEM_PROMPT = """你是一个乐于助人的智能助理。
请直接针对用户发送的语音转写内容进行清晰、准确、自然的直接回复。
禁止输出与回答无关的额外标记，无需使用 JSON 格式包装，直接输出纯文本回答。"""

    private val SEGMENT_ID_REGEX = Regex("^\\[\\d+\\]\\s*")

    private fun cleanSegmentIds(text: String): String {
        return text.lines()
            .joinToString("\n") { line -> line.replace(SEGMENT_ID_REGEX, "") }
            .trim()
    }

    fun createUserPrompt(batch: AnalysisBatch): String {
        val cleanContext = cleanSegmentIds(batch.context)
        val cleanNewText = cleanSegmentIds(batch.newText)

        return if (cleanContext.isBlank()) {
            cleanNewText
        } else {
            """
            [前文背景]
            $cleanContext

            [用户当前发言]
            $cleanNewText
            """.trimIndent()
        }
    }
}
