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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        baseUrl = "https://api.minimax.cn/v1",
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
        assertTrue(result.questions[0].isError)
        assertTrue(result.questions[0].answer.startsWith("❌ [解析异常]"))
        assertTrue(result.questions[0].answer.contains("<html>Bad Gateway</html>"))
    }

    @Test
    fun testSerializationOmitsExplicitNulls() = runBlocking {
        var capturedBody = ""
        val client = createMockClient { req ->
            val buffer = okio.Buffer()
            req.body?.writeTo(buffer)
            capturedBody = buffer.readUtf8()
            mockResponse(req, 200, """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""")
        }

        val provider = GenericLlmProvider(client)
        val result = provider.analyze(defaultBatch, defaultSettings, "test-key")

        assertFalse(capturedBody.contains("response_format"))
        assertFalse(result.questions[0].isError)
    }

    @Test
    fun testCancellationExceptionIsRethrown() = runBlocking {
        val client = createMockClient {
            throw kotlinx.coroutines.CancellationException("Job cancelled")
        }

        val provider = GenericLlmProvider(client)
        try {
            provider.analyze(defaultBatch, defaultSettings, "test-key")
            fail("Should have rethrown CancellationException")
        } catch (e: Exception) {
            assertTrue(e is kotlinx.coroutines.CancellationException)
        }
    }

    @Test
    fun testLongErrorBodyIsTruncatedTo500Chars() = runBlocking {
        val longBody = "A".repeat(1200)
        val client = createMockClient { req ->
            mockResponse(req, 500, longBody, "Server Error")
        }

        val provider = GenericLlmProvider(client)
        val result = provider.analyze(defaultBatch, defaultSettings, "test-key")

        assertTrue(result.questions[0].isError)
        val answer = result.questions[0].answer
        val responseSnippet = answer.substringAfter("响应: ")
        assertEquals(500, responseSnippet.length)
    }

    @Test
    fun testMiniMaxModelIncludesThinkingDisabledInRequest() = runBlocking {
        var capturedBody = ""
        val client = createMockClient { req ->
            val buffer = okio.Buffer()
            req.body?.writeTo(buffer)
            capturedBody = buffer.readUtf8()
            mockResponse(req, 200, """{"choices":[{"message":{"role":"assistant","content":"直接回答"}}]}""")
        }

        val provider = GenericLlmProvider(client)
        val miniMaxSettings = LlmSettings(
            baseUrl = "https://api.minimaxi.com/v1",
            model = "MiniMax-M3"
        )
        val result = provider.analyze(defaultBatch, miniMaxSettings, "test-key")

        assertTrue("MiniMax 请求体应包含 thinking disabled 参数", capturedBody.contains(""""thinking":{"type":"disabled"}"""))
        assertEquals("直接回答", result.questions[0].answer)
    }

    @Test
    fun testMiniMaxBaseUrlIncludesThinkingDisabledInRequest() = runBlocking {
        var capturedBody = ""
        val client = createMockClient { req ->
            val buffer = okio.Buffer()
            req.body?.writeTo(buffer)
            capturedBody = buffer.readUtf8()
            mockResponse(req, 200, """{"choices":[{"message":{"role":"assistant","content":"直接回答"}}]}""")
        }

        val provider = GenericLlmProvider(client)
        val miniMaxSettings = LlmSettings(
            baseUrl = "https://api.minimax.chat/v1",
            model = "custom-model"
        )
        val result = provider.analyze(defaultBatch, miniMaxSettings, "test-key")

        assertTrue("MiniMax BaseUrl 应触发 thinking disabled 参数", capturedBody.contains(""""thinking":{"type":"disabled"}"""))
        assertEquals("直接回答", result.questions[0].answer)
    }

    @Test
    fun testNonMiniMaxModelOmitsThinkingParameter() = runBlocking {
        var capturedBody = ""
        val client = createMockClient { req ->
            val buffer = okio.Buffer()
            req.body?.writeTo(buffer)
            capturedBody = buffer.readUtf8()
            mockResponse(req, 200, """{"choices":[{"message":{"role":"assistant","content":"直接回答"}}]}""")
        }

        val provider = GenericLlmProvider(client)
        val openAiSettings = LlmSettings(
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o"
        )
        val result = provider.analyze(defaultBatch, openAiSettings, "test-key")

        assertFalse("非 MiniMax 请求不应包含 thinking 字段", capturedBody.contains("thinking"))
        assertEquals("直接回答", result.questions[0].answer)
    }

    @Test
    fun testResponseContentWithThinkingTagsIsStripped() = runBlocking {
        val jsonResponse = """
            {
              "id": "chatcmpl-minimax",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "<think>\n这里是模型的内部思考过程：\n1. 分析问题\n2. 给出答案\n</think>\n机器学习是人工智能的一个分支。"
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
        val miniMaxSettings = LlmSettings(
            baseUrl = "https://api.minimaxi.com/v1",
            model = "MiniMax-M3"
        )
        val result = provider.analyze(defaultBatch, miniMaxSettings, "test-key")

        assertEquals("机器学习是人工智能的一个分支。", result.questions[0].answer)
        assertFalse(result.questions[0].answer.contains("<think>"))
        assertFalse(result.questions[0].answer.contains("内部思考过程"))
    }

    @Test
    fun testUnclosedThinkingTagIsCleanedSafely() = runBlocking {
        val jsonResponse = """
            {
              "id": "chatcmpl-minimax-truncated",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "<think>\n截断在思考过程中..."
                  },
                  "finish_reason": "length"
                }
              ]
            }
        """.trimIndent()

        val client = createMockClient { req ->
            mockResponse(req, 200, jsonResponse)
        }

        val provider = GenericLlmProvider(client)
        val miniMaxSettings = LlmSettings(
            baseUrl = "https://api.minimaxi.com/v1",
            model = "MiniMax-M3"
        )
        val result = provider.analyze(defaultBatch, miniMaxSettings, "test-key")

        assertEquals("（模型未返回任何内容）", result.questions[0].answer)
    }
}
