package com.voiceqa.app.llm

import android.util.Log
import com.voiceqa.app.batching.AnalysisBatch
import com.voiceqa.app.batching.AnalysisResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
            throw LlmAuthException("未配置 API Key，请在设置中配置 API Key")
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
            maxTokens = settings.maximumOutputTokens
        )

        val requestJson = json.encodeToString(requestPayload)

        // Attempt execution with at most 1 retry for network/5xx/json parse issues
        var attempts = 0
        val maxAttempts = 2

        while (true) {
            attempts++
            try {
                return@withContext executeRequest(requestUrl, requestJson, apiKey)
            } catch (authEx: LlmAuthException) {
                // 401/403 do not retry
                throw authEx
            } catch (e: Exception) {
                if (attempts >= maxAttempts) {
                    Log.e(TAG, "Request failed after $attempts attempts: ${e.message}")
                    throw e
                }
                Log.w(TAG, "Attempt $attempts failed (${e.message}), retrying once...")
                delay(1000)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("Unexpected exit from retry loop")
    }

    private fun resolveUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) {
            trimmed
        } else {
            "$trimmed/chat/completions"
        }
    }

    private suspend fun executeRequest(
        url: String,
        jsonBody: String,
        apiKey: String
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
            throw IOException("网络请求失败: ${e.message}", e)
        }

        response.use { resp ->
            val code = resp.code
            val bodyString = resp.body?.string().orEmpty()

            when {
                code == 401 || code == 403 -> {
                    throw LlmAuthException("API Key 无效或未授权 ($code)，请检查设置中的密钥")
                }
                code == 429 -> {
                    val retryAfterSeconds = resp.header("Retry-After")?.toLongOrNull() ?: 2L
                    Log.w(TAG, "Rate limited (429), retry after $retryAfterSeconds seconds")
                    delay(retryAfterSeconds * 1000L)
                    throw IOException("触发请求限流 (429)")
                }
                code >= 500 -> {
                    throw IOException("服务提供方服务器异常 ($code): $bodyString")
                }
                !resp.isSuccessful -> {
                    throw IOException("请求失败 ($code): $bodyString")
                }
            }

            // Parse chat completions response
            val chatResponse = try {
                json.decodeFromString<OpenAiChatResponse>(bodyString)
            } catch (e: Exception) {
                throw LlmValidationException("无法解析 OpenAI API 格式响应: ${e.message}", e)
            }

            val rawAssistantContent = chatResponse.choices.firstOrNull()?.message?.content
                ?: throw LlmValidationException("模型响应中 choices[0].message.content 为空")

            // Validate and parse structured QA JSON
            return LlmResultValidator.parseAndValidate(rawAssistantContent)
        }
    }
}
