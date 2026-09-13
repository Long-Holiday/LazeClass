package com.voiceqa.app.settings

enum class HistoryRetentionPolicy {
    NONE,
    SESSION_ONLY,
    HOURS_24,
    PERMANENT
}

data class AppSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-3.5-turbo",
    val language: String = "zh-CN",
    val silenceTimeoutMs: Long = 1_500L,
    val maximumWaitMs: Long = 10_000L,
    val maximumChars: Int = 120,
    val minimumChars: Int = 8,
    val contextChars: Int = 200,
    val ttsEnabled: Boolean = true,
    val continuousMode: Boolean = true,
    val historyRetention: HistoryRetentionPolicy = HistoryRetentionPolicy.PERMANENT,
    val useFakeLlm: Boolean = false
)
