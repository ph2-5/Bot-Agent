package com.qbot.core.engine

import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.AiMode
import com.qbot.core.ai.AiResponder
import com.qbot.core.ai.InMemoryAiHistoryStore
import com.qbot.core.ai.LlmClient
import com.qbot.core.auth.AccessTokenManager
import com.qbot.core.api.OpenApi
import com.qbot.core.gateway.GatewayClient
import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.Intents
import com.qbot.core.model.MessageKind
import com.qbot.core.util.FakeClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BotRuntime 的 AI 链路集成测试。
 *
 * 不起真实 WebSocket，直接调用 [BotRuntime.onMessage]，
 * 用 MockWebServer 同时接管 token、LLM、发消息三个端点，
 * 验证「消息 → AI → 回复」整条链路。
 */
class BotRuntimeAiTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    /** 记录实际发出的消息体，供断言 */
    private val sentMessages = CopyOnWriteArrayList<String>()

    private var llmStatusCode = 200
    private var llmBody = """{"choices":[{"message":{"content":"AI 的回答"}}]}"""

    @Before
    fun setUp() {
        llmStatusCode = 200
        llmBody = """{"choices":[{"message":{"content":"AI 的回答"}}]}"""
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.path == "/app/getAppAccessToken" ->
                            MockResponse().setBody("""{"access_token":"TK","expires_in":7200}""")

                        request.path == "/v1/chat/completions" ->
                            MockResponse().setResponseCode(llmStatusCode).setBody(llmBody)

                        request.path!!.startsWith("/v2/groups/") -> {
                            sentMessages += request.body.readUtf8()
                            MockResponse().setBody("""{"id":"send-1","timestamp":"123"}""")
                        }

                        else -> MockResponse().setResponseCode(404)
                    }
            }
        server.start()
        http =
            OkHttpClient
                .Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun message(content: String) =
        IncomingMessage(
            kind = MessageKind.GROUP_AT,
            eventType = "GROUP_AT_MESSAGE_CREATE",
            messageId = "msg-1",
            content = content,
            targetId = "group-openid",
            senderId = "user-openid",
            timestampMillis = 1_700_000_000_000,
            raw = buildJsonObject { },
        )

    private fun buildRuntime(
        scope: CoroutineScope,
        config: AiConfig,
        rules: List<Rule> = emptyList(),
        logSink: LogSink? = null,
    ): BotRuntime {
        val auth =
            AccessTokenManager(
                appId = "1020xxxx",
                clientSecret = "secret",
                http = http,
                tokenUrl = server.url("/app/getAppAccessToken").toString(),
                clock = FakeClock(),
            )
        val api = OpenApi(auth = auth, http = http, baseUrl = server.url("/").toString())
        val gateway =
            GatewayClient(
                api = api,
                auth = auth,
                scope = scope,
                http = http,
                intents = Intents.DEFAULT,
                clock = FakeClock(),
            )

        return BotRuntime(
            api = api,
            gateway = gateway,
            ruleEngine = RuleEngine(rules),
            scope = scope,
            logSink = logSink,
            aiResponder =
                AiResponder(
                    client = LlmClient(http = http),
                    history = InMemoryAiHistoryStore(),
                ),
            aiConfigProvider = { config },
        )
    }

    /** 把日志收进列表，既用于断言，也作为"协程处理完了"的完成信号。 */
    private fun capturingSink(logs: CopyOnWriteArrayList<LogEntry>) =
        object : LogSink {
            override fun append(entry: LogEntry) {
                logs.add(entry)
            }
        }

    /** 等待日志里出现预期的处理结果，带超时保护，避免挂死。 */
    private suspend fun awaitOutcome(
        logs: CopyOnWriteArrayList<LogEntry>,
        vararg keywords: String,
    ) {
        withTimeout(15_000) {
            while (logs.none { entry -> keywords.any { entry.text.contains(it) } }) {
                delay(50)
            }
        }
    }

    @Test
    fun `规则未命中时走 AI 并发出回复`() =
        runBlocking {
            val logs = CopyOnWriteArrayList<LogEntry>()
            val runtime =
                buildRuntime(
                    scope = this,
                    config =
                        AiConfig(
                            enabled = true,
                            baseUrl = server.url("/").toString(),
                            apiKey = "sk-test",
                            model = "deepseek-chat",
                            mode = AiMode.RULE_FIRST,
                        ),
                    logSink = capturingSink(logs),
                )

            runtime.onMessage(message("今天天气如何"))
            awaitOutcome(logs, "AI 已回复")

            assertEquals(1, sentMessages.size)
            assertTrue(sentMessages[0].contains("AI 的回答"))
            assertTrue("被动回复应带上原消息 id", sentMessages[0].contains("msg-1"))
        }

    @Test
    fun `规则命中时不再询问 AI`() =
        runBlocking {
            val logs = CopyOnWriteArrayList<LogEntry>()
            val rules =
                listOf(
                    Rule(
                        id = "r1",
                        name = "打招呼",
                        matchType = MatchType.EXACT,
                        pattern = "你好",
                        reply = "你好呀",
                    ),
                )
            val runtime =
                buildRuntime(
                    scope = this,
                    config =
                        AiConfig(
                            enabled = true,
                            baseUrl = server.url("/").toString(),
                            apiKey = "sk-test",
                            model = "deepseek-chat",
                        ),
                    rules = rules,
                    logSink = capturingSink(logs),
                )

            runtime.onMessage(message("你好"))
            awaitOutcome(logs, "命中规则")

            assertEquals(1, sentMessages.size)
            assertTrue(sentMessages[0].contains("你好呀"))
            // 没有打到 LLM 端点
            assertEquals(0, countRequestsTo("/v1/chat/completions"))
        }

    @Test
    fun `AI 关闭时未命中规则的消息被静默忽略`() =
        runBlocking {
            val logs = CopyOnWriteArrayList<LogEntry>()
            val runtime =
                buildRuntime(
                    scope = this,
                    config = AiConfig(enabled = false),
                    logSink = capturingSink(logs),
                )

            runtime.onMessage(message("随便说点啥"))
            awaitOutcome(logs, "无匹配规则")

            assertTrue(sentMessages.isEmpty())
        }

    @Test
    fun `AI 失败时按兜底话术回复`() =
        runBlocking {
            llmStatusCode = 500
            llmBody = "{}"

            val logs = CopyOnWriteArrayList<LogEntry>()
            val runtime =
                buildRuntime(
                    scope = this,
                    config =
                        AiConfig(
                            enabled = true,
                            baseUrl = server.url("/").toString(),
                            apiKey = "sk-test",
                            model = "deepseek-chat",
                            replyOnError = "AI 开小差了",
                        ),
                    logSink = capturingSink(logs),
                )

            runtime.onMessage(message("在吗"))
            awaitOutcome(logs, "已发送兜底回复", "兜底回复也失败")

            assertEquals(1, sentMessages.size)
            assertTrue(sentMessages[0].contains("AI 开小差了"))
        }

    private fun countRequestsTo(path: String): Int =
        server.takeRequest(0, TimeUnit.MILLISECONDS)
            ?.takeIf { it.path == path }
            ?.let { 1 + countRequestsTo(path) }
            ?: 0
}
