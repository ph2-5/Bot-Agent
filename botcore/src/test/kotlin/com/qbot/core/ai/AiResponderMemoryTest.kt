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

/**
 * AiResponder 的记忆与人设注入测试。
 *
 * 验证三件事：
 * 1. 会话键按人隔离——群里 A 的历史不会喂给 B；
 * 2. MemoryContext 的事实/展示名被注入请求体；
 * 3. personaResolver 返回的人设覆盖 AiConfig.systemPrompt。
 */
class AiResponderMemoryTest {
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

    private fun config() =
        AiConfig(
            enabled = true,
            baseUrl = server.url("/").toString(),
            apiKey = "sk-test",
            model = "deepseek-chat",
        )

    private fun message(
        content: String,
        senderId: String = "user-1",
        targetId: String = "group-1",
        kind: MessageKind = MessageKind.GROUP_AT,
    ) = IncomingMessage(
        kind = kind,
        eventType = "GROUP_AT_MESSAGE_CREATE",
        messageId = "msg-1",
        content = content,
        targetId = targetId,
        senderId = senderId,
        timestampMillis = 1_700_000_000_000,
        raw = buildJsonObject { },
    )

    private fun enqueueAnswer(answer: String = "这是答案") {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"$answer"}}]}"""),
        )
    }

    private fun memory(
        label: String = "小明",
        facts: List<UserFact> = emptyList(),
    ) = MemoryContext(senderLabel = label, facts = facts)

    @Test
    fun `同一群里不同人的历史互相隔离`() =
        runBlocking {
            enqueueAnswer("给 A 的回答")
            responder.reply(message("A 的问题", senderId = "user-a"), config())

            enqueueAnswer("给 B 的回答")
            responder.reply(message("B 的问题", senderId = "user-b"), config())

            server.takeRequest() // 消费 A 的请求
            val bBody = server.takeRequest().body.readUtf8()
            assertFalse("B 不应看到 A 的提问", bBody.contains("A 的问题"))
            assertTrue(bBody.contains("B 的问题"))

            // 两个人的历史键不同
            assertEquals(2, history.load("g:group-1:user-a", 10).size)
            assertEquals(2, history.load("g:group-1:user-b", 10).size)
        }

    @Test
    fun `事实与展示名被注入请求体`() =
        runBlocking {
            enqueueAnswer()
            responder.reply(
                message("推荐道菜"),
                config(),
                memory(
                    label = "小明",
                    facts =
                        listOf(
                            UserFact(senderId = "user-1", text = "喜欢吃辣"),
                            UserFact(senderId = "user-1", text = "在备考研究生"),
                        ),
                ),
            )

            val body = server.takeRequest().body.readUtf8()
            assertTrue("应注入事实", body.contains("喜欢吃辣"))
            assertTrue("应注入事实", body.contains("在备考研究生"))
            assertTrue("本轮问题应带展示名前缀", body.contains("[小明] 推荐道菜"))
        }

    @Test
    fun `无记忆时请求体没有人设记忆块`() =
        runBlocking {
            enqueueAnswer()
            responder.reply(message("你好"), config(), memory = null)

            val body = server.takeRequest().body.readUtf8()
            assertFalse(body.contains("关于对方"))
            assertFalse("无展示名可加，问题不应带方括号前缀", body.contains("[小明]"))
        }

    @Test
    fun `人设解析器覆盖配置里的系统提示词`() =
        runBlocking {
            val custom =
                AiResponder(
                    client = client,
                    history = history,
                    personaResolver = { "你是傲娇猫娘" },
                )
            enqueueAnswer()
            custom.reply(message("你好"), config().copy(systemPrompt = "你是默认助手"))

            val body = server.takeRequest().body.readUtf8()
            assertTrue("应使用解析出的人设", body.contains("傲娇猫娘"))
            assertFalse("配置里的默认人设不应出现", body.contains("默认助手"))
        }

    @Test
    fun `人设解析器返回空时回退到配置提示词`() =
        runBlocking {
            val custom =
                AiResponder(
                    client = client,
                    history = history,
                    personaResolver = { null },
                )
            enqueueAnswer()
            custom.reply(message("你好"), config().copy(systemPrompt = "你是默认助手"))

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("默认助手"))
        }

    @Test
    fun `单聊的会话键不带群维度`() =
        runBlocking {
            enqueueAnswer()
            responder.reply(
                message("在吗", targetId = "user-openid", kind = MessageKind.C2C),
                config(),
            )
            assertEquals(2, history.load("u:user-openid", 10).size)
        }
}
