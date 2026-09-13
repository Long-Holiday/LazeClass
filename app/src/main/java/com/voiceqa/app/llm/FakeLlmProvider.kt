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
        delay(500)
        val text = batch.newText.trim()
        val answer = "这是离线模拟回复：已收到您发送的“$text”。"
        return AnalysisResult(
            hasQuestion = true,
            questions = listOf(
                QuestionAnswer(question = text, answer = answer, sourceSegmentIds = batch.segmentIds)
            ),
            message = "已模拟回复"
        )
    }
}
