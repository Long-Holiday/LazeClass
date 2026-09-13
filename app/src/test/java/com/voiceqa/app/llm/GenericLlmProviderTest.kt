package com.voiceqa.app.llm

import com.voiceqa.app.batching.AnalysisBatch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GenericLlmProviderTest {

    private val defaultBatch = AnalysisBatch(
        id = "test-batch-1",
        sessionId = "session-1",
        segmentIds = listOf(101L, 102L),
        context = "之前聊了机器学习",
        newText = "什么是过拟合？",
        previouslyAnswered = emptyList()
    )

    private val defaultSettings = LlmSettings(
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini"
    )

    private fun createMockClient(handler: (okhttp3.Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                handler(chain.request())
            })
            .build()
    }

    private fun mockResponse(
        request: okhttp3.Request,
        code: Int,
        body: String,
        message: String = "OK"
    ): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    @Test
    fun testSuccessfulChatCompletionReturnsDirectText() = runBlocking {
        val jsonResponse = """
            {
              "id": "chatcmpl-test",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "过拟合是指模型在训练数据上表现极好，但在测试数据上泛化能力差的现象。"
                  },
                  "finish_reason": "stop"
                }
              ]
            }
        """.trimIndent()

        val client = createMockClient { req ->
            mockResponse(req, 200, jsonResponse)
        }

        val provider = GenericLlmProvider(client)
        val result = provider.analyze(defaultBatch, defaultSettings, "test-api-key")

        assertTrue(result.hasQuestion)
        assertEquals(1, result.questions.size)
        val qa = result.questions[0]
        assertEquals("什么是过拟合？", qa.question)
        assertEquals("过拟合是指模型在训练数据上表现极好，但在测试数据上泛化能力差的现象。", qa.answer)
        assertEquals(listOf(101L, 102L), qa.sourceSegmentIds)
        assertEquals("已收到回复", result.message)
    }

    @Test
    fun testEmptyContentReturnsFallbackText() = runBlocking {
        val jsonResponse = """
            {
              "id": "chatcmpl-empty",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": ""
                  }
                }
              ]
            }
        """.trimIndent()

        val client = createMockClient { req ->
            mockResponse(req, 200, jsonResponse)
        }

        val provider = GenericLlmProvider(client)
        val result = provider.analyze(defaultBatch, defaultSettings, "test-api-key")

        assertTrue(result.hasQuestion)
        assertEquals("（模型未返回任何内容）", result.questions[0].answer)
    }

    @Test
    fun testMissingApiKeyReturnsDiagnostic() = runBlocking {
        val provider = GenericLlmProvider()
        val result = provider.analyze(defaultBatch, defaultSettings, null)

        assertTrue(result.hasQuestion)
        assertEquals("未配置 API Key", result.message)
        assertTrue(result.questions[0].answer.startsWith("❌ [鉴权失败]"))
        assertTrue(result.questions[0].answer.contains("未配置 API Key"))
    }

    @Test
    fun testBlankApiKeyReturnsDiagnostic() = runBlocking {
        val provider = GenericLlmProvider()
        val result = provider.analyze(defaultBatch, defaultSettings, "   ")

        assertTrue(result.hasQuestion)
        assertEquals("未配置 API Key", result.message)
        assertTrue(result.questions[0].answer.startsWith("❌ [鉴权失败]"))
    }

    @Test
    fun test401UnauthorizedReturnsDiagnostic() = runBlocking {
        val client = createMockClient { req ->
            mockResponse(req, 401, """{"error":{"message":"Invalid API Key provided"}}""", "Unauthorized")
        }

        val provider = GenericLlmProvider(client)
        val result = provider.analyze(defaultBatch, defaultSettings, "bad-key")

        assertTrue(result.hasQuestion)
        assertEquals("鉴权失败", result.message)
        assertTrue(result.questions[0].answer.startsWith("❌ [鉴权失败 (HTTP 401)]"))
        assertTrue(result.questions[0].answer.contains("Invalid API Key provided"))
    }

    @Test
    fun test403ForbiddenReturnsDiagnostic() = runBlocking {
        val client = createMockClient { req ->
            mockResponse(req, 403, """{"error":{"message":"Access forbidden"}}""", "Forbidden")
        }

        val provider = GenericLlmProvider(client)
        val result = provider.analyze(defaultBatch, defaultSettings, "bad-key")

        assertTrue(result.hasQuestion)
        assertEquals("鉴权失败", result.message)
        assertTrue(result.questions[0].answer.startsWith("❌ [鉴权失败 (HTTP 403)]"))
        assertTrue(result.questions[0].answer.contains("Access forbidden"))
    }

    @Test
    fun test429RateLimitReturnsDiagnostic() = runBlocking {
        val client = createMockClient { req ->
            mockResponse(req, 429, """{"error":{"message":"Rate limit exceeded"}}""", "Too Many Requests")
        }

        val provider = GenericLlmProvider(client)
        val result = provider.analyze(defaultBatch, defaultSettings, "test-key")

        assertTrue(result.hasQuestion)
        assertEquals("触发限流", result.message)
        assertTrue(result.questions[0].answer.startsWith("❌ [请求限流 (HTTP 429)]"))
        assertTrue(result.questions[0].answer.contains("Rate limit exceeded"))
    }

    @Test
    fun test500ServerErrorReturnsDiagnostic() = runBlocking {
        val client = createMockClient { req ->
            mockResponse(req, 500, "Internal Server Error", "Server Error")
        }

        val provider = GenericLlmProvider(client)
        val result = provider.analyze(defaultBatch, defaultSettings, "test-key")

        assertTrue(result.hasQuestion)
        assertEquals("服务异常", result.message)
        assertTrue(result.questions[0].answer.startsWith("❌ [服务异常 (HTTP 500)]"))
        assertTrue(result.questions[0].answer.contains("Internal Server Error"))
    }

    @Test
    fun testNetworkIOExceptionReturnsDiagnostic() = runBlocking {
        val client = createMockClient {
            throw IOException("Connect to api.openai.com failed")
        }

        val provider = GenericLlmProvider(client)
        val result = provider.analyze(defaultBatch, defaultSettings, "test-key")

        assertTrue(result.hasQuestion)
        assertEquals("网络异常", result.message)
        assertTrue(result.questions[0].answer.startsWith("❌ [网络异常]"))
        assertTrue(result.questions[0].answer.contains("Connect to api.openai.com failed"))
    }

    @Test
    fun testMalformedJsonResponseReturnsDiagnostic() = runBlocking {
        val client = createMockClient { req ->
            mockResponse(req, 200, "<html>Bad Gateway</html>", "OK")
        }

        val provider = GenericLlmProvider(client)
        val result = provider.analyze(defaultBatch, defaultSettings, "test-key")

        assertTrue(result.hasQuestion)
        assertEquals("响应解析失败", result.message)
        assertTrue(result.questions[0].answer.startsWith("❌ [解析异常]"))
        assertTrue(result.questions[0].answer.contains("<html>Bad Gateway</html>"))
    }
}
