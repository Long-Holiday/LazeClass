package com.voiceqa.app.llm

import android.util.Log
import com.voiceqa.app.batching.AnalysisBatch
import com.voiceqa.app.batching.AnalysisResult
import com.voiceqa.app.batching.QuestionAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
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
                        sourceSegmentIds = batch.segmentIds
                    )
                ),
                message = "未配置 API Key"
            )
        }

        val requestUrl = resolveUrl(settings.baseUrl)
        val systemPrompt = LlmPromptFactory.SYSTEM_PROMPT
        val userPrompt = LlmPromptFactory.createUserPrompt(batch)

        val requestPayload = OpenAiChatRequest(
            model = settings.model,
            messages = listOf(
                OpenAiMessage(role = "system", content = systemPrompt),
                OpenAiMessage(role = "user", content = userPrompt)
            ),
            temperature = settings.temperature,
            maxTokens = settings.maximumOutputTokens,
            responseFormat = null
        )

        val requestJson = json.encodeToString(requestPayload)

        try {
            executeRequest(requestUrl, requestJson, apiKey, batch)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during analyze: ${e.message}", e)
            AnalysisResult(
                hasQuestion = true,
                questions = listOf(
                    QuestionAnswer(
                        question = batch.newText,
                        answer = "❌ [未知错误] ${e.localizedMessage ?: e.message}",
                        sourceSegmentIds = batch.segmentIds
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
                        sourceSegmentIds = batch.segmentIds
                    )
                ),
                message = "网络异常"
            )
        }

        response.use { resp ->
            val code = resp.code
            val bodyString = resp.body?.string().orEmpty()

            if (code == 401 || code == 403) {
                return AnalysisResult(
                    hasQuestion = true,
                    questions = listOf(
                        QuestionAnswer(
                            question = batch.newText,
                            answer = "❌ [鉴权失败 (HTTP $code)] API Key 无效或未授权，请前往“设置”检查密钥配置。\n响应: $bodyString",
                            sourceSegmentIds = batch.segmentIds
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
                            answer = "❌ [请求限流 (HTTP 429)] 触发频率限制，请稍后再试。\n响应: $bodyString",
                            sourceSegmentIds = batch.segmentIds
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
                            answer = "❌ [服务异常 (HTTP $code)]\n响应: $bodyString",
                            sourceSegmentIds = batch.segmentIds
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
                            answer = "❌ [解析异常] 无法解析大模型响应: ${e.localizedMessage ?: e.message}\n响应: $bodyString",
                            sourceSegmentIds = batch.segmentIds
                        )
                    ),
                    message = "响应解析失败"
                )
            }

            val rawAssistantContent = chatResponse.choices.firstOrNull()?.message?.content?.ifBlank { null }
                ?: "（模型未返回任何内容）"

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
}
