package com.voiceqa.app.batching

data class BatchPolicy(
    val silenceTimeoutMs: Long = 1_500L,
    val maximumWaitMs: Long = 10_000L,
    val maximumChars: Int = 120,
    val minimumChars: Int = 8,
    val contextChars: Int = 200
)

data class TranscriptSegment(
    val id: Long,
    val sessionId: String,
    val text: String,
    val isFinal: Boolean,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val createdAtMs: Long
)

data class AnalysisBatch(
    val id: String,
    val sessionId: String,
    val segmentIds: List<Long>,
    val context: String,
    val newText: String,
    val previouslyAnswered: List<String>
)

data class QuestionAnswer(
    val question: String,
    val answer: String,
    val sourceSegmentIds: List<Long> = emptyList(),
    val isError: Boolean = false
)

data class AnalysisResult(
    val hasQuestion: Boolean,
    val questions: List<QuestionAnswer>,
    val message: String
)
