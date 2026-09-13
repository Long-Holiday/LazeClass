package com.voiceqa.app.llm

import com.voiceqa.app.batching.AnalysisBatch
import com.voiceqa.app.batching.AnalysisResult

data class LlmSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-3.5-turbo",
    val maximumOutputTokens: Int = 300,
    val temperature: Double = 0.1
)

class LlmAuthException(message: String) : Exception(message)

interface LlmProvider {
    suspend fun analyze(
        batch: AnalysisBatch,
        settings: LlmSettings,
        apiKey: String?
    ): AnalysisResult
}
