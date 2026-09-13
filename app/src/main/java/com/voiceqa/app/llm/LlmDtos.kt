package com.voiceqa.app.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenAiThinking(
    val type: String = "disabled"
)

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val temperature: Double = 0.1,
    @SerialName("max_tokens")
    val maxTokens: Int = 300,
    @SerialName("response_format")
    val responseFormat: OpenAiResponseFormat? = null,
    val thinking: OpenAiThinking? = null
)

@Serializable
data class OpenAiResponseFormat(
    val type: String
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null
)

@Serializable
data class OpenAiChatResponse(
    val id: String? = null,
    val choices: List<OpenAiChoice> = emptyList()
)

@Serializable
data class OpenAiChoice(
    val index: Int = 0,
    val message: OpenAiMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

/**
 * Expected JSON payload returned inside message.content
 */
@Serializable
data class LlmAnalysisResponseDto(
    @SerialName("has_question")
    val hasQuestion: Boolean = false,
    val questions: List<LlmQuestionDto> = emptyList(),
    val message: String = ""
)

@Serializable
data class LlmQuestionDto(
    val question: String,
    val answer: String,
    @SerialName("source_segment_ids")
    val sourceSegmentIds: List<Long> = emptyList()
)
