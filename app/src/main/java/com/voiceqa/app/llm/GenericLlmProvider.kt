package com.voiceqa.app.llm

import android.util.Log
import com.voiceqa.app.batching.AnalysisBatch
import com.voiceqa.app.batching.AnalysisResult
import com.voiceqa.app.batching.QuestionAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class GenericLlmProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
) : LlmProvider {

    companion object {
        private const val TAG = "GenericLlmProvider"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override suspend fun analyze(
        batch: AnalysisBatch,
        settings: LlmSettings,
        apiKey: String?
    ): AnalysisResult = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) {
            return@withContext AnalysisResult(
                hasQuestion = true,
                questions = listOf(
                    QuestionAnswer(
                        question = batch.newText,
                        answer = "❌ [鉴权失败] 未配置 API Key，请前往“设置”检查密钥配置。",
                        sourceSegmentIds = batch.segmentIds,
                        isError = true
                    )
                ),
                message = "未配置 API Key"
            )
        }

        val requestUrl = resolveUrl(settings.baseUrl)
        val systemPrompt = LlmPromptFactory.SYSTEM_PROMPT
        val userPrompt = LlmPromptFactory.createUserPrompt(batch)

        val isMiniMax = isMiniMaxRequest(settings.model, settings.baseUrl)
        val thinking = if (isMiniMax) OpenAiThinking(type = "disabled") else null

        val requestPayload = OpenAiChatRequest(
            model = settings.model,
            messages = listOf(
                OpenAiMessage(role = "system", content = systemPrompt),
                OpenAiMessage(role = "user", content = userPrompt)
            ),
            temperature = settings.temperature,
            maxTokens = settings.maximumOutputTokens,
            responseFormat = null,
            thinking = thinking
        )

        val requestJson = json.encodeToString(requestPayload)

        try {
            executeRequest(requestUrl, requestJson, apiKey, batch)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Unexpected error during analyze: ${e.message}", e)
            AnalysisResult(
                hasQuestion = true,
                questions = listOf(
                    QuestionAnswer(
                        question = batch.newText,
                        answer = "❌ [未知错误] ${e.localizedMessage ?: e.message}",
                        sourceSegmentIds = batch.segmentIds,
                        isError = true
                    )
                ),
                message = "请求异常"
            )
        }
    }

    private fun resolveUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) {
            trimmed
        } else {
            "$trimmed/chat/completions"
        }
    }

    private fun executeRequest(
        url: String,
        jsonBody: String,
        apiKey: String,
        batch: AnalysisBatch
    ): AnalysisResult {
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            return AnalysisResult(
                hasQuestion = true,
                questions = listOf(
                    QuestionAnswer(
                        question = batch.newText,
                        answer = "❌ [网络异常] 无法连接到大模型服务: ${e.localizedMessage ?: e.message}\n请检查网络连接及 Base URL 配置。",
                        sourceSegmentIds = batch.segmentIds,
                        isError = true
                    )
                ),
                message = "网络异常"
            )
        }

        response.use { resp ->
            val code = resp.code
            val bodyString = resp.body?.string().orEmpty()
            val safeBody = bodyString.take(500)

            if (code == 401 || code == 403) {
                return AnalysisResult(
                    hasQuestion = true,
                    questions = listOf(
                        QuestionAnswer(
                            question = batch.newText,
                            answer = "❌ [鉴权失败 (HTTP $code)] API Key 无效或未授权，请前往“设置”检查密钥配置。\n响应: $safeBody",
                            sourceSegmentIds = batch.segmentIds,
                            isError = true
                        )
                    ),
                    message = "鉴权失败"
                )
            }
            if (code == 429) {
                return AnalysisResult(
                    hasQuestion = true,
                    questions = listOf(
                        QuestionAnswer(
                            question = batch.newText,
                            answer = "❌ [请求限流 (HTTP 429)] 触发频率限制，请稍后再试。\n响应: $safeBody",
                            sourceSegmentIds = batch.segmentIds,
                            isError = true
                        )
                    ),
                    message = "触发限流"
                )
            }
            if (!resp.isSuccessful) {
                return AnalysisResult(
                    hasQuestion = true,
                    questions = listOf(
                        QuestionAnswer(
                            question = batch.newText,
                            answer = "❌ [服务异常 (HTTP $code)]\n响应: $safeBody",
                            sourceSegmentIds = batch.segmentIds,
                            isError = true
                        )
                    ),
                    message = "服务异常"
                )
            }

            val chatResponse = try {
                json.decodeFromString<OpenAiChatResponse>(bodyString)
            } catch (e: Exception) {
                return AnalysisResult(
                    hasQuestion = true,
                    questions = listOf(
                        QuestionAnswer(
                            question = batch.newText,
                            answer = "❌ [解析异常] 无法解析大模型响应: ${e.localizedMessage ?: e.message}\n响应: $safeBody",
                            sourceSegmentIds = batch.segmentIds,
                            isError = true
                        )
                    ),
                    message = "响应解析失败"
                )
            }

            val rawMessageContent = chatResponse.choices.firstOrNull()?.message?.content
            val strippedContent = rawMessageContent?.let { stripThinkingTags(it) }?.ifBlank { null }
            val rawAssistantContent = strippedContent ?: "（模型未返回任何内容）"

            return AnalysisResult(
                hasQuestion = true,
                questions = listOf(
                    QuestionAnswer(
                        question = batch.newText,
                        answer = rawAssistantContent,
                        sourceSegmentIds = batch.segmentIds
                    )
                ),
                message = "已收到回复"
            )
        }
    }

    internal fun isMiniMaxRequest(model: String, baseUrl: String): Boolean {
        return model.contains("minimax", ignoreCase = true) ||
            baseUrl.contains("minimax", ignoreCase = true)
    }

    internal fun stripThinkingTags(content: String): String {
        val closedRemoved = content.replace(Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE), "")
        val unclosedRemoved = closedRemoved.replace(Regex("""<think>[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        return unclosedRemoved.trim()
    }
}
