package com.voiceqa.app.llm

import com.voiceqa.app.batching.AnalysisBatch
import com.voiceqa.app.batching.AnalysisResult

data class LlmSettings(
    val baseUrl: String = "https://api.minimax.cn/v1",
    val model: String = "MiniMax-M3",
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
