package com.voiceqa.app.llm

import com.voiceqa.app.batching.AnalysisBatch

object LlmPromptFactory {

    const val SYSTEM_PROMPT = """你是一个乐于助人的智能助理。
请直接针对用户发送的语音转写内容进行清晰、准确、自然的直接回复。
禁止输出与回答无关的额外标记，无需使用 JSON 格式包装，直接输出纯文本回答。"""

    fun createUserPrompt(batch: AnalysisBatch): String {
        return if (batch.context.isBlank()) {
            batch.newText.trim()
        } else {
            """
            [前文背景]
            ${batch.context.trim()}

            [用户当前发言]
            ${batch.newText.trim()}
            """.trimIndent()
        }
    }
}
