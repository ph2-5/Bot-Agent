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

/**
 * 人设进化器测试。
 *
 * 重点不是"模型写得好不好"，而是**安全边界**：
 * 空输出、过短、过度膨胀都必须被拦下，绝不能让一次坏进化把人设冲掉。
 */
class PersonaEvolverTest {
    private lateinit var server: MockWebServer
    private lateinit var evolver: PersonaEvolver

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        evolver = PersonaEvolver(LlmClient(http = OkHttpClient.Builder().build()))
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

    private fun enqueue(content: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"$content"}}]}"""),
        )
    }

    private val current = "你是一个群聊机器人，说话简短，别太客气。"

    @Test
    fun `正常进化返回新提示词与摘要`() =
        runBlocking {
            enqueue("你是一个脾气不好的群聊机器人。说话冲、爱怼人，但只针对观点不针对人；一句话说完，不解释。")
            val result =
                evolver.evolve(
                    personaName = "毒舌群友",
                    currentPrompt = current,
                    samples = emptyList(),
                    instruction = "更毒舌一点",
                    config = config(),
                )
            assertTrue(result.prompt.contains("脾气不好"))
            assertTrue("摘要应取首行", result.note.isNotEmpty())
        }

    @Test
    fun `代码围栏会被清理`() =
        runBlocking {
            enqueue("```markdown\\n你是群里的老油条，说话短、爱接梗，从不长篇大论。\\n```")
            val result =
                evolver.evolve(
                    personaName = "老油条",
                    currentPrompt = current,
                    samples = emptyList(),
                    config = config(),
                )
            assertTrue("不应残留围栏", !result.prompt.startsWith("```"))
            assertTrue(result.prompt.contains("老油条"))
        }

    @Test(expected = IllegalArgumentException::class)
    fun `输出过短时拒绝进化`() {
        runBlocking {
            enqueue("好")
            evolver.evolve(
                personaName = "x",
                currentPrompt = current,
                samples = emptyList(),
                config = config(),
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `输出膨胀过多时拒绝进化`() {
        runBlocking {
            enqueue("你是一个人设机器人。".repeat(200))
            evolver.evolve(
                personaName = "x",
                currentPrompt = current,
                samples = emptyList(),
                config = config(),
            )
        }
    }

    @Test
    fun `样本会进入请求体`() =
        runBlocking {
            enqueue("你是一个不爱说话的群友，只在被点名时回一句，语气冷淡。")
            evolver.evolve(
                personaName = "冷面",
                currentPrompt = current,
                samples =
                    listOf(
                        RoomMessage(
                            senderId = "u1",
                            senderName = "小明",
                            role = "user",
                            content = "今晚开黑吗",
                            timestampMillis = 0,
                        ),
                    ),
                config = config(),
            )
            val body = server.takeRequest().body.readUtf8()
            assertTrue("应带上群聊样本", body.contains("今晚开黑吗"))
            assertTrue("应标明样本来源", body.contains("小明"))
        }

    @Test
    fun `过长但仍在阈值内的输出被接受`() =
        runBlocking {
            val text = "你是一个群聊机器人，说话简短直白。".repeat(3)
            enqueue(text)
            val result =
                evolver.evolve(
                    personaName = "x",
                    currentPrompt = current,
                    samples = emptyList(),
                    config = config(),
                )
            assertEquals(text, result.prompt)
        }
}
