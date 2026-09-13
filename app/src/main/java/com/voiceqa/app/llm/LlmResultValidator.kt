package com.voiceqa.app.llm

import com.voiceqa.app.batching.AnalysisResult
import com.voiceqa.app.batching.QuestionAnswer
import kotlinx.serialization.json.Json

class LlmValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)

object LlmResultValidator {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Parses and validates raw LLM text into AnalysisResult.
     * Throws LlmValidationException on failure.
     */
    fun parseAndValidate(rawText: String): AnalysisResult {
        val cleanJson = extractJson(rawText)
            ?: throw LlmValidationException("未能从模型响应中提取出有效 JSON: $rawText")

        val dto = try {
            json.decodeFromString<LlmAnalysisResponseDto>(cleanJson)
        } catch (e: Exception) {
            throw LlmValidationException("JSON 解析失败: ${e.message}", e)
        }

        val questions = dto.questions.map { q ->
            QuestionAnswer(
                question = q.question.trim(),
                answer = q.answer.trim(),
                sourceSegmentIds = q.sourceSegmentIds
            )
        }

        return AnalysisResult(
            hasQuestion = dto.hasQuestion,
            questions = questions,
            message = dto.message.ifBlank {
                if (dto.hasQuestion) "识别到 ${questions.size} 个问题" else "未识别到问题"
            }
        )
    }

    /**
     * Extracts JSON substring, stripping markdown code fences if present.
     */
    fun extractJson(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }

        // Match markdown ```json ... ``` or ``` ... ```
        val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
        val match = codeBlockRegex.find(trimmed)
        if (match != null) {
            val content = match.groupValues[1].trim()
            if (content.startsWith("{") && content.endsWith("}")) {
                return content
            }
        }

        // Fallback: search for first '{' and last '}'
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1)
        }

        return null
    }
}
