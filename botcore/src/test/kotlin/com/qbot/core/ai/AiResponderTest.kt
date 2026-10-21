package com.qbot.core.ai

import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.MessageKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiResponderTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LlmClient
    private lateinit var history: InMemoryAiHistoryStore
    private lateinit var responder: AiResponder

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = LlmClient(http = OkHttpClient.Builder().build())
        history = InMemoryAiHistoryStore()
        responder = AiResponder(client = client, history = history)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config(
        mode: AiMode = AiMode.RULE_FIRST,
        enabled: Boolean = true,
        contextTokenBudget: Int = AiConfig().contextTokenBudget,
        thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
        baseUrl: String? = null,
        model: String = "deepseek-chat",
    ) = AiConfig(
        enabled = enabled,
        baseUrl = baseUrl ?: server.url("/").toString(),
        apiKey = "sk-test",
        model = model,
        mode = mode,
        contextTokenBudget = contextTokenBudget,
        thinkingMode = thinkingMode,
    )

    private fun message(
        content: String,
        targetId: String = "group-1",
        kind: MessageKind = MessageKind.GROUP_AT,
    ) = IncomingMessage(
        kind = kind,
        eventType = "GROUP_AT_MESSAGE_CREATE",
        messageId = "msg-1",
        content = content,
        targetId = targetId,
        senderId = "user-1",
        timestampMillis = 1_700_000_000_000,
        raw = buildJsonObject { },
    )

    private fun enqueueAnswer(answer: String = "这是答案") {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {"choices":[{"message":{"role":"assistant","content":"$answer"}}]}
                    """.trimIndent(),
                ),
        )
    }

    // ---------- shouldHandle ----------

    @Test
    fun `规则优先模式下规则未命中才交给 AI`() {
        assertTrue(responder.shouldHandle(message("你好"), config(AiMode.RULE_FIRST), ruleMatched = false))
        assertFalse(responder.shouldHandle(message("你好"), config(AiMode.RULE_FIRST), ruleMatched = true))
    }

    @Test
    fun `AI 优先模式下无视规则命中`() {
        assertTrue(responder.shouldHandle(message("你好"), config(AiMode.AI_ALWAYS), ruleMatched = true))
    }

    @Test
    fun `命令模式只认 ai 前缀`() {
        val cfg = config(AiMode.COMMAND_ONLY)
        assertTrue(responder.shouldHandle(message("/ai 你好"), cfg, ruleMatched = false))
        assertFalse(responder.shouldHandle(message("你好"), cfg, ruleMatched = false))
    }

    @Test
    fun `未启用或配置不全时一律不处理`() {
        assertFalse(responder.shouldHandle(message("你好"), config(enabled = false), ruleMatched = false))
        assertFalse(
            responder.shouldHandle(
                message("你好"),
                config().copy(apiKey = ""),
                ruleMatched = false,
            ),
        )
    }

    // ---------- extractQuestion ----------

    @Test
    fun `剥离 ai 前缀得到真实问题`() {
        assertEquals("今天天气如何", responder.extractQuestion(message("/ai 今天天气如何")))
        assertEquals("今天天气如何", responder.extractQuestion(message("今天天气如何")))
    }

    // ---------- reply ----------

    @Test
    fun `回复成功后问答写入历史`() =
        runBlocking {
            enqueueAnswer("答案是 42")
            val reply = responder.reply(message("答案是什么"), config())

            assertEquals("答案是 42", reply)
            val stored = history.load("g:group-1", 10)
            assertEquals(2, stored.size)
            assertEquals("user", stored[0].role)
            assertEquals("答案是什么", stored[0].content)
            assertEquals("assistant", stored[1].role)
        }

    @Test
    fun `第二轮请求会带上历史上下文`() =
        runBlocking {
            enqueueAnswer("第一轮回答")
            responder.reply(message("问题一"), config())

            enqueueAnswer("第二轮回答")
            responder.reply(message("问题二"), config())

            server.takeRequest() // 先消费掉第一轮请求
            val second = server.takeRequest()
            val body = second.body.readUtf8()
            assertTrue("应包含第一轮提问", body.contains("问题一"))
            assertTrue("应包含第一轮回答", body.contains("第一轮回答"))
            assertTrue("应包含本轮提问", body.contains("问题二"))
        }

    @Test
    fun `请求失败时不写历史并抛出异常`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

            val error =
                runCatching { responder.reply(message("在吗"), config()) }.exceptionOrNull()

            assertTrue(error is LlmException)
            assertTrue(history.load("g:group-1", 10).isEmpty())
        }

    @Test
    fun `清空指令直接重置上下文且不调用模型`() =
        runBlocking {
            enqueueAnswer("随便")
            responder.reply(message("问题"), config())
            assertEquals(2, history.load("g:group-1", 10).size)

            val reply = responder.reply(message("/ai 清空"), config())
            assertTrue(reply.contains("已清空"))
            assertTrue(history.load("g:group-1", 10).isEmpty())
            // 只发起了第一次真实请求，清空指令没有打网络
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `不同会话的上下文互相隔离`() =
        runBlocking {
            enqueueAnswer("甲群回答")
            responder.reply(message("甲群问题"), config())

            enqueueAnswer("乙群回答")
            responder.reply(message("乙群问题", targetId = "group-2"), config())

            server.takeRequest() // 先消费掉甲群的请求
            val secondBody = server.takeRequest().body.readUtf8()
            assertFalse("乙群不应看到甲群历史", secondBody.contains("甲群问题"))
        }

    @Test
    fun `超长回复会被截断`() =
        runBlocking {
            val long = "长".repeat(3000)
            enqueueAnswer(long)

            val reply = responder.reply(message("讲个长的"), config())
            assertTrue(reply.length < long.length + 20)
            assertTrue(reply.endsWith("……（回复过长已截断）"))
        }

    @Test
    fun `空白问题直接反问不调用模型`() =
        runBlocking {
            val reply = responder.reply(message("/ai   "), config())
            assertEquals("请问你想问什么？", reply)
            assertEquals(0, server.requestCount)
        }

    // ---------- assembleMessages：缓存友好组装与预算 ----------

    @Test
    fun `组装顺序固定为 system 历史 本轮问题`() {
        val messages =
            responder.assembleMessages(
                systemPrompt = "你是助手",
                history =
                    listOf(
                        ChatMessage("user", "旧问题"),
                        ChatMessage("assistant", "旧回答"),
                    ),
                question = "新问题",
                tokenBudget = 10_000,
            )

        assertEquals(4, messages.size)
        assertEquals("system", messages[0].role)
        assertEquals("user", messages[1].role)
        assertEquals("assistant", messages[2].role)
        assertEquals("新问题", messages[3].content)
    }

    @Test
    fun `system 为空时不含 system 消息`() {
        val messages =
            responder.assembleMessages(
                systemPrompt = "  ",
                history = emptyList(),
                question = "问",
                tokenBudget = 10_000,
            )
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
    }

    @Test
    fun `超预算时从最早的历史成对丢弃`() {
        // 每条中文消息约等于其字符数个 token
        val history =
            listOf(
                ChatMessage("user", "一".repeat(400)),
                ChatMessage("assistant", "答".repeat(400)),
                ChatMessage("user", "二".repeat(400)),
                ChatMessage("assistant", "答".repeat(400)),
                ChatMessage("user", "三".repeat(400)),
                ChatMessage("assistant", "答".repeat(400)),
            )
        // 预算只够 system + 最后一对 + 本轮问题
        val messages =
            responder.assembleMessages(
                systemPrompt = "系统",
                history = history,
                question = "本轮",
                tokenBudget = 2 + 800 + 2 + 50,
            )

        assertTrue("system 必须保留", messages.first().role == "system")
        assertTrue("本轮问题必须保留", messages.last().content == "本轮")
        val contents = messages.map { it.content }
        assertTrue("最早的一对应被丢弃", !contents.contains("一".repeat(400)))
        assertTrue("最新的一对应保留", contents.contains("三".repeat(400)))
        // 成对丢弃：不应出现只剩提问没有回答的孤儿
        val userCount = messages.count { it.role == "user" && it.content != "本轮" }
        val assistantCount = messages.count { it.role == "assistant" }
        assertEquals("剩余历史应问答配对", userCount, assistantCount)
    }

    @Test
    fun `上下文超限时自动砍半历史重试并成功`() =
        runBlocking {
            enqueueAnswer("第一次")
            responder.reply(message("问题"), config(contextTokenBudget = 10_000))
            history.append("g:group-1", ChatMessage("user", "塞".repeat(5000)))

            // 第一次请求服务端报上下文超限，第二次（砍半后）成功
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":{"message":"maximum context length exceeded"}}"""),
            )
            enqueueAnswer("重试后的答案")

            val reply = responder.reply(message("再问"), config(contextTokenBudget = 10_000))
            assertEquals("重试后的答案", reply)
        }

    // ---------- thinkingParam ----------

    @Test
    fun `DeepSeek 端点默认关闭思考`() {
        val param = responder.thinkingParam(config())
        assertEquals("disabled", param)
    }

    @Test
    fun `非 DeepSeek 端点不发送 thinking 参数`() {
        val cfg = config(baseUrl = "https://api.openai.com", model = "gpt-4o")
        assertEquals(null, responder.thinkingParam(cfg))
    }

    @Test
    fun `AUTO 模式不发送 thinking 参数`() {
        val cfg = config().copy(thinkingMode = ThinkingMode.AUTO)
        assertEquals(null, responder.thinkingParam(cfg))
    }

    @Test
    fun `开启思考时参数为 enabled`() {
        val cfg = config().copy(thinkingMode = ThinkingMode.ENABLED)
        assertEquals("enabled", responder.thinkingParam(cfg))
    }

    @Test
    fun `DeepSeek 请求体默认带 thinking disabled`() =
        runBlocking {
            enqueueAnswer("ok")
            responder.reply(message("你好"), config())

            val body = server.takeRequest().body.readUtf8()
            assertTrue("应关闭思考模式，实际=$body", body.contains("\"thinking\""))
            assertTrue("应为 disabled，实际=$body", body.contains("disabled"))
        }
}
