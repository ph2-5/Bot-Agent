package com.qbot.core.engine

import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.AiResponder
import com.qbot.core.ai.InMemoryAiHistoryStore
import com.qbot.core.ai.InMemoryGroupStore
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
import kotlinx.serialization.json.put
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
 * 群感知端到端测试：生命周期事件走网关 onRawEvent 进来后的完整链路。
 *
 * 覆盖：
 * 1. 被拉进群 → 建档 + （可选）发问候语；
 * 2. 新成员进群 → 记入「见过的人」；
 * 3. 被移出群 → 标记不活跃；
 * 4. 群名片指令（叫你X）不经过 LLM 直接应答。
 */
class BotRuntimeGroupTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var groups: InMemoryGroupStore

    /** 发到群里的消息（含问候语与指令回复） */
    private val sentMessages = CopyOnWriteArrayList<String>()

    /** LLM 请求体：指令路径不应产生任何一条 */
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

    private fun buildRuntime(
        scope: CoroutineScope,
        greetingText: String? = null,
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
        groups = InMemoryGroupStore()
        val pipeline =
            MemoryPipeline(
                identities = InMemoryIdentityStore(),
                facts = InMemoryMemoryStore(),
                roomContext = InMemoryRoomContextStore(),
                configProvider = { MemoryConfig() },
                groupStore = groups,
                botNameProvider = { "官方机器人" },
            )
        return BotRuntime(
            api = api,
            gateway = gateway,
            ruleEngine = RuleEngine(emptyList()),
            scope = scope,
            logSink = logSink,
            aiResponder = AiResponder(client = LlmClient(http = http), history = InMemoryAiHistoryStore()),
            aiConfigProvider = { aiConfig() },
            memoryPipeline = pipeline,
            greetingTextProvider = { greetingText },
        )
    }

    private fun aiConfig() =
        AiConfig(
            enabled = true,
            baseUrl = server.url("/").toString(),
            apiKey = "sk-test",
            model = "deepseek-chat",
        )

    private fun groupMessage(
        content: String,
        senderId: String = "member-1",
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

    private suspend fun awaitSent(count: Int) {
        withTimeout(15_000) {
            while (sentMessages.size < count) delay(50)
        }
    }

    @Test
    fun `被拉进群时建档`() =
        runBlocking {
            val runtime = buildRuntime(scope = this)
            runtime.onRawEvent("GROUP_ADD_ROBOT", rawEvent("GROUP_ADD_ROBOT"))

            withTimeout(15_000) {
                while (groups.find("group-openid") == null) delay(50)
            }
            val profile = groups.find("group-openid")!!
            assertTrue(profile.active)
            assertTrue("拉它进来的人应记入见过的人", profile.seenMembers.contains("member-op"))
        }

    @Test
    fun `开启问候语后被拉进群会发自我介绍`() =
        runBlocking {
            val runtime = buildRuntime(scope = this, greetingText = "大家好，我是机器人")
            runtime.onRawEvent("GROUP_ADD_ROBOT", rawEvent("GROUP_ADD_ROBOT"))

            awaitSent(1)
            assertTrue(
                "应发送问候语，实际发送：$sentMessages",
                sentMessages.any { it.contains("大家好，我是机器人") },
            )
            assertTrue("问候语不应触发 LLM", llmBodies.isEmpty())
        }

    @Test
    fun `未开启问候语时被拉进群保持安静`() =
        runBlocking {
            val runtime = buildRuntime(scope = this, greetingText = null)
            runtime.onRawEvent("GROUP_ADD_ROBOT", rawEvent("GROUP_ADD_ROBOT"))

            withTimeout(15_000) {
                while (groups.find("group-openid") == null) delay(50)
            }
            // 给一点时间确保没有消息发出
            delay(500)
            assertTrue("未开启问候语时不应发任何消息", sentMessages.isEmpty())
        }

    @Test
    fun `群关闭主动消息后跳过问候`() =
        runBlocking {
            val runtime = buildRuntime(scope = this, greetingText = "大家好")
            runtime.onRawEvent("GROUP_ADD_ROBOT", rawEvent("GROUP_ADD_ROBOT"))
            awaitSent(1)

            // 群管理员关闭主动消息
            runtime.onRawEvent("GROUP_MSG_REJECT", rawEvent("GROUP_MSG_REJECT"))
            withTimeout(15_000) {
                while (groups.find("group-openid")?.proactiveAllowed != false) delay(50)
            }
            // 平台重放/重复推送同一个群的加群事件：已关闭主动消息，不再问候
            runtime.onRawEvent("GROUP_ADD_ROBOT", rawEvent("GROUP_ADD_ROBOT"))
            delay(800)
            assertEquals("被关闭主动消息的群不应再收问候", 1, sentMessages.count { it.contains("大家好") })
            // 换个没关闭主动消息的群，照常问候
            runtime.onRawEvent("GROUP_ADD_ROBOT", rawEvent("GROUP_ADD_ROBOT", groupId = "group-2"))
            awaitSent(2)
            assertEquals("第二个群应照常问候", 2, sentMessages.size)
        }

    @Test
    fun `新成员进群记入见过的人`() =
        runBlocking {
            val runtime = buildRuntime(scope = this)
            runtime.onRawEvent("GROUP_ADD_ROBOT", rawEvent("GROUP_ADD_ROBOT"))
            withTimeout(15_000) {
                while (groups.find("group-openid") == null) delay(50)
            }

            runtime.onRawEvent("GROUP_MEMBER_ADD", rawEvent("GROUP_MEMBER_ADD", actorId = "newcomer"))
            withTimeout(15_000) {
                while (groups.find("group-openid")?.seenMembers?.contains("newcomer") != true) delay(50)
            }
        }

    @Test
    fun `被移出群后标记不活跃`() =
        runBlocking {
            val runtime = buildRuntime(scope = this)
            runtime.onRawEvent("GROUP_ADD_ROBOT", rawEvent("GROUP_ADD_ROBOT"))
            withTimeout(15_000) {
                while (groups.find("group-openid") == null) delay(50)
            }

            runtime.onRawEvent("GROUP_DEL_ROBOT", rawEvent("GROUP_DEL_ROBOT"))
            withTimeout(15_000) {
                while (groups.find("group-openid")?.active != false) delay(50)
            }
            assertFalse(groups.find("group-openid")!!.active)
        }

    @Test
    fun `群里@我的消息也记入见过的人`() =
        runBlocking {
            val runtime = buildRuntime(scope = this)
            runtime.onRawEvent("GROUP_ADD_ROBOT", rawEvent("GROUP_ADD_ROBOT", actorId = null))
            withTimeout(15_000) {
                while (groups.find("group-openid") == null) delay(50)
            }

            runtime.onMessage(groupMessage("大家好呀", senderId = "chatter"))
            withTimeout(15_000) {
                while (groups.find("group-openid")?.seenMembers?.contains("chatter") != true) delay(50)
            }
        }

    @Test
    fun `叫你X 指令不经过 LLM 直接改群名片`() =
        runBlocking {
            val runtime = buildRuntime(scope = this)
            runtime.onRawEvent("GROUP_ADD_ROBOT", rawEvent("GROUP_ADD_ROBOT", actorId = null))
            withTimeout(15_000) {
                while (groups.find("group-openid") == null) delay(50)
            }

            runtime.onMessage(groupMessage("叫你小白"))
            awaitSent(1)
            assertTrue(
                "应回复确认改名，实际：$sentMessages",
                sentMessages.any { it.contains("小白") },
            )
            assertTrue("改名指令不应触发 LLM", llmBodies.isEmpty())
            assertEquals("小白", groups.find("group-openid")!!.botName)
        }
}
