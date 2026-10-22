package com.qbot.core.ai

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LlmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = LlmClient(http = OkHttpClient.Builder().build())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---------- normalizeEndpoint ----------

    @Test
    fun `裸域名会补全 v1 与 chat completions`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            client.normalizeEndpoint("https://api.deepseek.com"),
        )
    }

    @Test
    fun `末尾斜杠会被去掉`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            client.normalizeEndpoint("https://api.deepseek.com/"),
        )
    }

    @Test
    fun `已带 v1 时只拼 chat completions`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            client.normalizeEndpoint("https://api.deepseek.com/v1"),
        )
    }

    @Test
    fun `完整路径原样返回`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            client.normalizeEndpoint("https://api.deepseek.com/v1/chat/completions"),
        )
    }

    @Test
    fun `缺少协议时补 https`() {
        assertEquals(
            "https://api.moonshot.cn/v1/chat/completions",
            client.normalizeEndpoint("api.moonshot.cn"),
        )
    }

    @Test
    fun `本地 http 地址保持 http 不强制升级`() {
        assertEquals(
            "http://192.168.1.5:11434/v1/chat/completions",
            client.normalizeEndpoint("http://192.168.1.5:11434"),
        )
    }

    @Test
    fun `空地址返回空串`() {
        assertEquals("", client.normalizeEndpoint("   "))
    }

    // ---------- chat ----------

    @Test
    fun `成功请求会带上鉴权头与标准字段`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {"choices":[{"message":{"role":"assistant","content":"你好，有什么可以帮你？"}}]}
                        """.trimIndent(),
                    ),
            )

            val reply =
                client.chat(
                    baseUrl = server.url("/").toString(),
                    apiKey = "sk-test",
                    model = "deepseek-chat",
                    messages =
                        listOf(
                            ChatMessage("system", "你是助手"),
                            ChatMessage("user", "在吗"),
                        ),
                    temperature = 0.5,
                    maxTokens = 100,
                )

            assertEquals("你好，有什么可以帮你？", reply.content)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer sk-test", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"model\":\"deepseek-chat\""))
            assertTrue(body.contains("\"role\":\"user\""))
            assertTrue(body.contains("\"content\":\"在吗\""))
            assertTrue(body.contains("\"stream\":false"))
        }

    @Test
    fun `401 会翻译成用户能看懂的提示`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"error":{"message":"invalid api key"}}"""),
            )

            val error =
                runCatching {
                    client.chat(
                        baseUrl = server.url("/").toString(),
                        apiKey = "bad",
                        model = "m",
                        messages = listOf(ChatMessage("user", "hi")),
                    )
                }.exceptionOrNull()

            assertTrue(error is LlmException)
            val msg = (error as LlmException).message.orEmpty()
            assertTrue(msg.contains("API Key"))
            assertTrue(msg.contains("invalid api key"))
            assertEquals(401, error.statusCode)
        }

    @Test
    fun `200 但带 error 字段时也算失败`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"error":{"message":"quota exceeded"}}"""),
            )

            val error =
                runCatching {
                    client.chat(
                        baseUrl = server.url("/").toString(),
                        apiKey = "k",
                        model = "m",
                        messages = listOf(ChatMessage("user", "hi")),
                    )
                }.exceptionOrNull()

            assertTrue(error is LlmException)
            assertTrue((error as LlmException).message.orEmpty().contains("quota exceeded"))
        }

    @Test
    fun `缺少 choices 时抛出异常`() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"id":"x"}"""),
            )

            val error =
                runCatching {
                    client.chat(
                        baseUrl = server.url("/").toString(),
                        apiKey = "k",
                        model = "m",
                        messages = listOf(ChatMessage("user", "hi")),
                    )
                }.exceptionOrNull()

            assertTrue(error is LlmException)
        }

    @Test
    fun `content 为 JSON null 时不会把字符串 null 当回复`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"choices":[{"message":{"role":"assistant","content":null}}]}"""),
            )

            val error =
                runCatching {
                    client.chat(
                        baseUrl = server.url("/").toString(),
                        apiKey = "k",
                        model = "m",
                        messages = listOf(ChatMessage("user", "hi")),
                    )
                }.exceptionOrNull()

            assertTrue("应是空正文异常", error is EmptyContentException)
            assertTrue(
                "应说清是空内容，实际=${error?.message}",
                error?.message.orEmpty().contains("空内容"),
            )
        }

    @Test
    fun `只有 reasoning_content 时不再冒充正文`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {"choices":[{"message":{"role":"assistant","content":null,
                        "reasoning_content":"用户问的是打招呼"},
                        "finish_reason":"length"}]}
                        """.trimIndent(),
                    ),
            )

            val error =
                runCatching {
                    client.chat(
                        baseUrl = server.url("/").toString(),
                        apiKey = "k",
                        model = "m",
                        messages = listOf(ChatMessage("user", "hi")),
                    )
                }.exceptionOrNull()

            assertTrue("思考过程不能当答案返回", error is EmptyContentException)
            val empty = error as EmptyContentException
            assertEquals("用户问的是打招呼", empty.reasoning)
            assertTrue("应识别为输出被截断", empty.truncated)
            assertTrue(
                "报错应说明正文被截断，实际=${empty.message}",
                empty.message.orEmpty().contains("截断"),
            )
        }

    @Test
    fun `thinking disabled 时会写入请求体且不再发送 temperature`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"choices":[{"message":{"content":"ok"}}]}"""),
            )

            client.chat(
                baseUrl = server.url("/").toString(),
                apiKey = "k",
                model = "deepseek-chat",
                messages = listOf(ChatMessage("user", "hi")),
                temperature = 0.5,
                thinkingType = "disabled",
            )

            val body = server.takeRequest().body.readUtf8()
            assertTrue("应包含 thinking 字段，实际=$body", body.contains("\"thinking\""))
            assertTrue("应为 disabled，实际=$body", body.contains("disabled"))
            assertTrue("思考关闭时仍应发送 temperature，实际=$body", body.contains("\"temperature\""))
        }

    @Test
    fun `thinking enabled 时不发送 temperature`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"choices":[{"message":{"content":"ok"}}]}"""),
            )

            client.chat(
                baseUrl = server.url("/").toString(),
                apiKey = "k",
                model = "deepseek-chat",
                messages = listOf(ChatMessage("user", "hi")),
                temperature = 0.5,
                thinkingType = "enabled",
            )

            val body = server.takeRequest().body.readUtf8()
            assertTrue("思考模式下不应发送 temperature，实际=$body", !body.contains("\"temperature\""))
            assertTrue("应为 enabled，实际=$body", body.contains("enabled"))
        }

    @Test
    fun `usage 与缓存命中字段会被解析`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {"choices":[{"message":{"content":"ok"}}],
                         "usage":{"prompt_tokens":1200,"completion_tokens":56,
                         "prompt_cache_hit_tokens":900,"prompt_cache_miss_tokens":300}}
                        """.trimIndent(),
                    ),
            )

            val reply =
                client.chat(
                    baseUrl = server.url("/").toString(),
                    apiKey = "k",
                    model = "m",
                    messages = listOf(ChatMessage("user", "hi")),
                )

            assertEquals(1200, reply.usage.promptTokens)
            assertEquals(56, reply.usage.completionTokens)
            assertEquals(900, reply.usage.cacheHitTokens)
            assertEquals(300, reply.usage.cacheMissTokens)
            assertEquals(0.75f, reply.usage.cacheHitRatio!!, 0.001f)
        }

    @Test
    fun `上下文超限会抛出 ContextTooLongException 供调用方裁剪重试`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":{"message":"maximum context length is 65536 tokens"}}"""),
            )

            val error =
                runCatching {
                    client.chat(
                        baseUrl = server.url("/").toString(),
                        apiKey = "k",
                        model = "m",
                        messages = listOf(ChatMessage("user", "hi")),
                    )
                }.exceptionOrNull()

            assertTrue("应为上下文超限异常，实际=$error", error is ContextTooLongException)
        }

    @Test
    fun `空白回复的错误信息带原始返回片段`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"choices":[{"message":{"content":"   "}}]}"""),
            )

            val error =
                runCatching {
                    client.chat(
                        baseUrl = server.url("/").toString(),
                        apiKey = "k",
                        model = "m",
                        messages = listOf(ChatMessage("user", "hi")),
                    )
                }.exceptionOrNull()

            assertTrue(error is LlmException)
            assertTrue(
                "错误信息应包含原始返回，实际=${(error as LlmException).message}",
                error.message.orEmpty().contains("原始返回"),
            )
        }
}
