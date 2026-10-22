package com.qbot.core.engine

import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.AiResponder
import com.qbot.core.ai.InMemoryAiHistoryStore
import com.qbot.core.ai.InMemoryIdentityStore
import com.qbot.core.ai.InMemoryMemoryStore
import com.qbot.core.ai.InMemoryRoomContextStore
import com.qbot.core.ai.LlmClient
import com.qbot.core.ai.MemoryConfig
import com.qbot.core.ai.MemoryPipeline
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BotRuntime 接入记忆系统后的端到端测试。
 *
 * 覆盖用户最关心的三件事：
 * 1. 群里能认出是谁（日志显示展示名而不是 openid）；
 * 2. 记忆管理指令（我的记忆 / 忘掉我）不经过 LLM 直接应答；
 * 3. 不同人的会话历史互相隔离。
 */
class BotRuntimeMemoryTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    private val sentMessages = CopyOnWriteArrayList<String>()

    /** LLM 请求体按序记录，避免从 MockWebServer 队列里抽头带来的竞态 */
    private val llmBodies = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.path == "/app/getAppAccessToken" ->
                            MockResponse().setBody("""{"access_token":"TK","expires_in":7200}""")

                        request.path == "/v1/chat/completions" -> {
                            llmBodies += request.body.readUtf8()
                            MockResponse()
                                .setBody("""{"choices":[{"message":{"content":"AI 的回答"}}]}""")
                        }

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

    private fun message(
        content: String,
        senderId: String = "user-openid",
    ) = IncomingMessage(
        kind = MessageKind.GROUP_AT,
        eventType = "GROUP_AT_MESSAGE_CREATE",
        messageId = "msg-1",
        content = content,
        targetId = "group-openid",
        senderId = senderId,
        timestampMillis = 1_700_000_000_000,
        raw = buildJsonObject { },
    )

    private fun buildRuntime(
        scope: CoroutineScope,
        config: AiConfig,
        memoryConfig: MemoryConfig = MemoryConfig(),
        logSink: LogSink? = null,
    ): Triple<BotRuntime, MemoryPipeline, InMemoryAiHistoryStore> {
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
        val history = InMemoryAiHistoryStore()
        val pipeline =
            MemoryPipeline(
                identities = InMemoryIdentityStore(),
                facts = InMemoryMemoryStore(),
                roomContext = InMemoryRoomContextStore(),
                configProvider = { memoryConfig },
            )
        val runtime =
            BotRuntime(
                api = api,
                gateway = gateway,
                ruleEngine = RuleEngine(emptyList()),
                scope = scope,
                logSink = logSink,
                aiResponder = AiResponder(client = LlmClient(http = http), history = history),
                aiConfigProvider = { config },
                memoryPipeline = pipeline,
            )
        return Triple(runtime, pipeline, history)
    }

    private fun capturingSink(logs: CopyOnWriteArrayList<LogEntry>) =
        object : LogSink {
            override fun append(entry: LogEntry) {
                logs.add(entry)
            }
        }

    /**
     * 等待日志里出现 expected 次预期关键词。
     *
     * 按次数等而不是按“出现过”等：多条消息连续处理时，
     * 上一条的日志会让“出现过”判断立即通过，从而读到处理到一半的状态。
     */
    private suspend fun awaitOutcome(
        logs: CopyOnWriteArrayList<LogEntry>,
        vararg keywords: String,
        expected: Int = 1,
    ) {
        withTimeout(15_000) {
            while (
                logs.count { entry -> keywords.any { entry.text.contains(it) } } < expected
            ) {
                delay(50)
            }
        }
    }

    private fun aiConfig() =
        AiConfig(
            enabled = true,
            baseUrl = server.url("/").toString(),
            apiKey = "sk-test",
            model = "deepseek-chat",
        )

    @Test
    fun `日志显示展示名而不是 openid`() =
        runBlocking {
            val logs = CopyOnWriteArrayList<LogEntry>()
            val (runtime, _, _) =
                buildRuntime(scope = this, config = aiConfig(), logSink = capturingSink(logs))

            runtime.onMessage(message("叫我小明，你好"))
            awaitOutcome(logs, "AI 已回复")

            assertTrue(
                "日志应显示展示名，实际：${logs.map { it.text }}",
                logs.any { it.text.contains("小明") && it.text.contains("收到[") },
            )
        }

    @Test
    fun `我的记忆指令不经过 LLM 直接应答`() =
        runBlocking {
            val logs = CopyOnWriteArrayList<LogEntry>()
            val (runtime, _, _) =
                buildRuntime(scope = this, config = aiConfig(), logSink = capturingSink(logs))

            // 先让机器人认识这个人并记住一条事实
            runtime.onMessage(message("叫我小明"))
            awaitOutcome(logs, "AI 已回复", expected = 1)
            runtime.onMessage(message("我喜欢吃辣"))
            awaitOutcome(logs, "AI 已回复", expected = 2)

            sentMessages.clear()
            val llmBefore = llmBodies.size
            runtime.onMessage(message("我的记忆"))
            awaitOutcome(logs, "记忆指令")

            assertTrue("应发出回复", sentMessages.isNotEmpty())
            assertTrue(
                "回复应回放事实，实际：${sentMessages.lastOrNull()}",
                sentMessages.last().contains("喜欢吃辣"),
            )
            assertEquals("记忆指令不应打 LLM", llmBefore, llmBodies.size)
        }

    @Test
    fun `忘掉我清空记忆`() =
        runBlocking {
            val logs = CopyOnWriteArrayList<LogEntry>()
            val (runtime, _, _) =
                buildRuntime(scope = this, config = aiConfig(), logSink = capturingSink(logs))

            runtime.onMessage(message("我喜欢吃辣"))
            awaitOutcome(logs, "AI 已回复", expected = 1)

            runtime.onMessage(message("忘掉我"))
            awaitOutcome(logs, "记忆指令")

            assertTrue(sentMessages.last().contains("已清空"))
        }

    @Test
    fun `不同人的会话线程隔离但共享群聊背景`() =
        runBlocking {
            val logs = CopyOnWriteArrayList<LogEntry>()
            val (runtime, _, history) =
                buildRuntime(scope = this, config = aiConfig(), logSink = capturingSink(logs))

            runtime.onMessage(message("我的秘密", senderId = "user-a"))
            awaitOutcome(logs, "AI 已回复", expected = 1)
            runtime.onMessage(message("你好", senderId = "user-b"))
            awaitOutcome(logs, "AI 已回复", expected = 2)

            // 第一层隔离：会话线程按人分开——B 的历史里没有 A 的问答
            assertEquals(2, history.load("g:group-openid:user-a", 10).size)
            assertEquals(2, history.load("g:group-openid:user-b", 10).size)
            assertTrue(
                "B 的线程不应包含 A 的提问",
                history.load("g:group-openid:user-b", 10).none { it.content.contains("我的秘密") },
            )

            // 第二层共享：群聊背景里能看到房间里最近的发言（跨人指代的语义基础）
            runtime.onMessage(message("那他呢", senderId = "user-b"))
            awaitOutcome(logs, "AI 已回复", expected = 3)
            assertTrue(
                "B 的请求应通过共享背景看到 A 的发言",
                llmBodies.last().contains("我的秘密"),
            )
        }

    @Test
    fun `关闭记忆后不认人也不拦截指令`() =
        runBlocking {
            val logs = CopyOnWriteArrayList<LogEntry>()
            val (runtime, _, _) =
                buildRuntime(
                    scope = this,
                    config = aiConfig(),
                    memoryConfig = MemoryConfig(enabled = false),
                    logSink = capturingSink(logs),
                )

            runtime.onMessage(message("叫我小明"))
            awaitOutcome(logs, "AI 已回复")

            // 日志里不应出现展示名（回退到 openid 尾段）
            assertTrue(
                logs.none { it.text.contains("小明:") },
            )
        }
}
