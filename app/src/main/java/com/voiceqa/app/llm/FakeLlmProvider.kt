package com.voiceqa.app.llm

import com.voiceqa.app.batching.AnalysisBatch
import com.voiceqa.app.batching.AnalysisResult
import com.voiceqa.app.batching.QuestionAnswer
import kotlinx.coroutines.delay

class FakeLlmProvider : LlmProvider {
    override suspend fun analyze(
        batch: AnalysisBatch,
        settings: LlmSettings,
        apiKey: String?
    ): AnalysisResult {
        // Simulate network latency
        delay(600)

        val newText = batch.newText
        val questions = mutableListOf<QuestionAnswer>()

        val lines = newText.lines().filter { it.isNotBlank() }
        for (line in lines) {
            val textOnly = line.replace(Regex("^\\[\\d+\\]\\s*"), "").trim()
            if (textOnly.contains("？") || textOnly.contains("?") ||
                textOnly.contains("什么") || textOnly.contains("怎么") ||
                textOnly.contains("几点") || textOnly.contains("多少") ||
                textOnly.contains("为什么") || textOnly.contains("哪")
            ) {
                // Ignore if previously answered
                if (!batch.previouslyAnswered.any { it.contains(textOnly) || textOnly.contains(it) }) {
                    questions.add(
                        QuestionAnswer(
                            question = textOnly,
                            answer = "这是针对“$textOnly”的模拟回答：根据当前讨论，相关信息已为您整理完成。",
                            sourceSegmentIds = batch.segmentIds
                        )
                    )
                }
            }
        }

        return if (questions.isNotEmpty()) {
            AnalysisResult(
                hasQuestion = true,
                questions = questions,
                message = "已识别到 ${questions.size} 个问题"
            )
        } else {
            AnalysisResult(
                hasQuestion = false,
                questions = emptyList(),
                message = "未识别到问题"
            )
        }
    }
}
