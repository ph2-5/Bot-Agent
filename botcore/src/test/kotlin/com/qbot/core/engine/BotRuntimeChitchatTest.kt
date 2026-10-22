package com.qbot.core.engine

import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.AiResponder
import com.qbot.core.ai.ChitchatConfig
import com.qbot.core.ai.InMemoryAiHistoryStore
import com.qbot.core.ai.InMemoryChimeTrainer
import com.qbot.core.ai.InMemoryGroupStore
import com.qbot.core.ai.InMemoryIdentityStore
import com.qbot.core.ai.InMemoryMemoryStore
import com.qbot.core.ai.InMemoryRoomContextStore
import com.qbot.core.ai.LlmClient
import com.qbot.core.ai.MemoryConfig
import com.qbot.core.ai.MemoryPipeline
import com.qbot.core.api.OpenApi
import com.qbot.core.auth.AccessTokenManager
import com.qbot.core.gateway.GatewayClient
import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.Intents
import com.qbot.core.model.MessageKind
import com.qbot.core.model.ReadyUser
import com.qbot.core.util.FakeClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
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
 * 群聊参与（插话）端到端测试。
 *
 * 关键行为：
 * 1. 全量消息无论是否回复，都进群记忆；
 * 2. 参与关闭时只记不说；
 * 3. 被叫名字会接话；
 * 4. @ 机器人的全量消息交给 @ 链路，不重复回复。
 */
class BotRuntimeChitchatTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var groups: InMemoryGroupStore
    private lateinit var room: InMemoryRoomContextStore
    private lateinit var trainer: InMemoryChimeTrainer

    /** 判断调用的模型输出；测试里可改（默认 YES + 理由） */
    private var llmVerdict = "YES\n有人在征求意见"

    /** 判断调用的 HTTP 状态，用来模拟限流/服务端错误 */
    private var llmJudgeStatus = 200

    private val sentMessages = CopyOnWriteArrayList<String>()
    private val llmBodies = CopyOnWriteArrayList<String>()

    private var messageSeq = 0

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
                            val body = request.body.readUtf8()
                            llmBodies += body
                            // 同一个端点承担两件事：插话判断与正式回复。
                            // 用 system 提示词里的特征句区分，避免两套 mock。
                            val isJudge = body.contains(AiResponder.JUDGE_PROMPT_TAG)
                            if (isJudge && llmJudgeStatus != 200) {
                                MockResponse().setResponseCode(llmJudgeStatus)
                                    .setBody("""{"error":{"message":"upstream error"}}""")
                            } else {
                                val content = if (isJudge) llmVerdict else "行啊，带我一个"
                                val escaped =
                                    content.replace("\\", "\\\\")
                                        .replace("\n", "\\n")
                                        .replace("\"", "\\\"")
                                MockResponse()
                                    .setBody("""{"choices":[{"message":{"content":"$escaped"}}]}""")
                            }
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

    private fun buildRuntime(
        scope: CoroutineScope,
        chitchat: ChitchatConfig = ChitchatConfig(enabled = true, aiJudge = false),
        random: () -> Double = { 0.0 },
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
        groups = InMemoryGroupStore()
        room = InMemoryRoomContextStore()
        trainer = InMemoryChimeTrainer()
        val pipeline =
            MemoryPipeline(
                identities = InMemoryIdentityStore(),
                facts = InMemoryMemoryStore(),
                roomContext = room,
                configProvider = { MemoryConfig() },
                groupStore = groups,
                botNameProvider = { "小白" },
            )
        return BotRuntime(
            api = api,
            gateway = gateway,
            ruleEngine = RuleEngine(emptyList()),
            scope = scope,
            aiResponder = AiResponder(client = LlmClient(http = http), history = InMemoryAiHistoryStore()),
            aiConfigProvider = {
                AiConfig(
                    enabled = true,
                    baseUrl = server.url("/").toString(),
                    apiKey = "sk-test",
                    model = "deepseek-chat",
                )
            },
            memoryPipeline = pipeline,
            chitchatConfigProvider = { chitchat },
            chimeTrainer = trainer,
            // 概率固定命中，避免测试随机抖动
            random = random,
        )
    }

    private fun rawEvent(
        eventType: String,
        groupId: String? = "group-openid",
        actorId: String? = "member-op",
    ) = buildJsonObject {
        put("timestamp", 1784570534)
        groupId?.let { put("group_openid", it) }
        actorId?.let {
            if (eventType == "GROUP_MEMBER_ADD") put("member_openid", it) else put("op_member_openid", it)
        }
    }

    private fun fullMessage(
        content: String,
        senderId: String = "member-1",
        mentionsBot: Boolean = false,
    ) = IncomingMessage(
        kind = MessageKind.GROUP_MESSAGE,
        eventType = "GROUP_MESSAGE_CREATE",
        messageId = "full-${messageSeq++}",
        content = content,
        targetId = "group-openid",
        senderId = senderId,
        senderName = "小明",
        timestampMillis = 1_700_000_000_000,
        raw =
            buildJsonObject {
                if (mentionsBot) {
                    putJsonArray("mentions") {
                        addJsonObject { put("id", "bot-1") }
                    }
                }
            },
    )

    private suspend fun prepareRuntime(
        scope: CoroutineScope,
        config: ChitchatConfig = ChitchatConfig(enabled = true, aiJudge = false),
        random: () -> Double = { 0.0 },
    ): BotRuntime {
        val runtime = buildRuntime(scope, config, random)
        // 模拟 READY：botId / botName 来自网关下发
        runtime.onReady("sid-1", ReadyUser(id = "bot-1", username = "小白"))
        runtime.onRawEvent("GROUP_ADD_ROBOT", rawEvent("GROUP_ADD_ROBOT", actorId = null))
        withTimeout(15_000) {
            while (groups.find("group-openid") == null) delay(50)
        }
        return runtime
    }

    private suspend fun awaitSent(count: Int) {
        withTimeout(15_000) {
            while (sentMessages.size < count) delay(50)
        }
    }

    private suspend fun awaitRoomContains(text: String) {
        withTimeout(15_000) {
            while (room.recent("g:group-openid", 50).none { it.content.contains(text) }) delay(50)
        }
    }

    private suspend fun awaitDecision(
        content: String,
        contains: String,
    ) {
        withTimeout(15_000) {
            while (
                room
                    .recent("g:group-openid", 50)
                    .none { it.content.contains(content) && it.decision?.contains(contains) == true }
            ) {
                delay(50)
            }
        }
    }

    @Test
    fun `全量消息写入群背景`() =
        runBlocking {
            val runtime = prepareRuntime(this)

            runtime.onMessage(fullMessage("今晚有人开黑吗"))
            awaitRoomContains("今晚有人开黑吗")

            val digest = room.recent("g:group-openid", 10)
            assertEquals("小明", digest.last().senderName)
            assertEquals("user", digest.last().role)
        }

    @Test
    fun `参与关闭时只记录不回复`() =
        runBlocking {
            val runtime = prepareRuntime(this, ChitchatConfig(enabled = false))

            runtime.onMessage(fullMessage("小白你在吗"))
            awaitRoomContains("小白你在吗")
            delay(800)

            assertTrue("关闭参与后不应发消息，实际：$sentMessages", sentMessages.isEmpty())
            assertTrue("也不该调用模型", llmBodies.isEmpty())
            awaitDecision("小白你在吗", "未开启")
        }

    @Test
    fun `被叫名字时会接话`() =
        runBlocking {
            val runtime = prepareRuntime(this)

            runtime.onMessage(fullMessage("小白，今晚开黑不"))
            awaitSent(1)
            awaitDecision("今晚开黑不", "已接话")

            assertEquals("应只回一条", 1, sentMessages.size)
            assertTrue("回复应带上模型输出", sentMessages.first().contains("行啊"))
        }

    @Test
    fun `档案缺失时首条叫名字的消息也能接话`() =
        runBlocking {
            // 不发 GROUP_ADD_ROBOT，模拟"全量消息先于加群事件到达"
            val runtime = buildRuntime(this)
            runtime.onReady("sid-1", ReadyUser(id = "bot-1", username = "小白"))

            runtime.onMessage(fullMessage("小白，今晚开黑不"))
            awaitSent(1)
            awaitDecision("今晚开黑不", "已接话")
        }

    @Test
    fun `叫短昵称也能接话`() =
        runBlocking {
            // 群名片设为「过去的阿远」，群里只喊「阿远」也应命中（后缀匹配）
            val runtime = prepareRuntime(this)
            groups.upsert(
                com.qbot.core.ai.GroupProfile(
                    groupId = "group-openid",
                    botName = "过去的阿远",
                    joinedAt = 0,
                    lastSeenAt = 0,
                ),
            )

            runtime.onMessage(fullMessage("阿远，今晚开黑不"))
            awaitSent(1)
            awaitDecision("今晚开黑不", "已接话")
        }

    @Test
    fun `艾特机器人的全量消息交给艾特链路不重复回复`() =
        runBlocking {
            val runtime = prepareRuntime(this)

            runtime.onMessage(fullMessage("@我 帮我看个问题", mentionsBot = true))
            delay(800)

            assertTrue("该消息应由 GROUP_AT 处理，这里不应回复", sentMessages.isEmpty())
            assertTrue(
                "但内容仍应记进群背景",
                room.recent("g:group-openid", 10).any { it.content.contains("帮我看个问题") },
            )
        }

    @Test
    fun `同一消息重复推送只处理一次`() =
        runBlocking {
            val runtime = prepareRuntime(this)
            val message = fullMessage("小白在吗")

            runtime.onMessage(message)
            runtime.onMessage(message)
            awaitSent(1)
            delay(500)

            assertEquals("重复推送不应回两次", 1, sentMessages.size)
        }

    // ---------- 训练闭环：决策记录 ----------

    @Test
    fun `不接话时也留下结构化决策记录`() =
        runBlocking {
            // 概率抽样必定不命中 → 走「概率未命中」分支
            val runtime = prepareRuntime(this, random = { 1.0 })

            runtime.onMessage(fullMessage("今晚有人开黑吗"))
            withTimeout(15_000) {
                while (trainer.decisions.isEmpty()) delay(50)
            }

            val record = trainer.decisions.single()
            assertEquals("SILENT", record.action)
            assertEquals("CANDIDATE", record.gatePath)
            assertTrue("原因应保留分支文案，实际=${record.reason}", record.reason.contains("概率"))
            assertEquals("member-1", record.speakerId)
            assertEquals("小明", record.speakerName)
            assertTrue("应记录对话对象，实际=${record.addresseeKind}", record.addresseeKind != null)
            assertTrue("应带上规则分", (record.ruleScore ?: -1) >= 0)
        }

    @Test
    fun `AI 判断不接时记录判断与理由`() =
        runBlocking {
            llmVerdict = "NO\n两人正在私聊"
            val runtime =
                prepareRuntime(this, ChitchatConfig(enabled = true, aiJudge = true))

            runtime.onMessage(fullMessage("你吃了吗"))
            withTimeout(15_000) {
                while (trainer.decisions.isEmpty()) delay(50)
            }

            val record = trainer.decisions.single()
            assertEquals("SILENT", record.action)
            assertEquals("NO", record.aiVerdict)
            assertTrue("应记录模型理由，实际=${record.aiReason}", record.aiReason?.contains("私聊") == true)
        }

    @Test
    fun `接话后记录动作与回复内容`() =
        runBlocking {
            llmVerdict = "YES\n有人在征求意见"
            val runtime =
                prepareRuntime(this, ChitchatConfig(enabled = true, aiJudge = true))

            runtime.onMessage(fullMessage("这个周末有人去爬山吗"))
            awaitSent(1)
            withTimeout(15_000) {
                while (trainer.decisions.none { it.action == "SPOKE" }) delay(50)
            }

            val record = trainer.decisions.last { it.action == "SPOKE" }
            assertEquals("YES", record.aiVerdict)
            assertTrue("应记录实际回复，实际=${record.replyText}", record.replyText?.contains("行啊") == true)
            assertTrue("应记录原始消息", record.messageText.contains("爬山"))
        }

    @Test
    fun `学到的经验注入判断提示词并累计命中`() =
        runBlocking {
            llmVerdict = "YES\n有人在征求意见"
            val runtime =
                prepareRuntime(this, ChitchatConfig(enabled = true, aiJudge = true))
            trainer.addLesson("有人提问时主动接话", scopeGroupId = "group-openid", updatedAt = 1)

            runtime.onMessage(fullMessage("这个怎么设置"))
            awaitSent(1)

            val body = llmBodies.first { it.contains(AiResponder.JUDGE_PROMPT_TAG) }
            assertTrue("经验应进入判断提示词，实际=$body", body.contains("有人提问时主动接话"))
            assertTrue("应有偏好块标题", body.contains("从主人反馈里学到的偏好"))

            withTimeout(15_000) {
                while (trainer.lessons.single().hits == 0) delay(50)
            }
            assertEquals(1, trainer.lessons.single().hits)
        }

    @Test
    fun `判断失败时记下具体原因而不是笼统失败`() =
        runBlocking {
            llmJudgeStatus = 500
            val runtime = prepareRuntime(this, ChitchatConfig(enabled = true, aiJudge = true))

            runtime.onMessage(fullMessage("这个周末有人去爬山吗"))
            withTimeout(15_000) {
                while (trainer.decisions.isEmpty()) delay(50)
            }

            val record = trainer.decisions.single()
            assertEquals("SILENT", record.action)
            assertTrue(
                "原因应带 HTTP 状态，实际=${record.reason}",
                record.reason.contains("AI 判断失败（HTTP 500）"),
            )
        }
}
