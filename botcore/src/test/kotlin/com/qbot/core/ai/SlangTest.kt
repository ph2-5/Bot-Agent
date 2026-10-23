package com.qbot.core.ai

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 群内梗：候选提取要能收敛噪声，渲染要能管住"别硬用"。 */
class SlangTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun msg(content: String, role: String = "user", name: String? = "小明") =
        RoomMessage(
            senderId = "u1",
            senderName = name,
            role = role,
            content = content,
            timestampMillis = 1,
        )

    @Test
    fun `出现三次以上的说法才进候选`() {
        val messages =
            listOf(
                msg("这波真的绷不住了"),
                msg("绷不住了兄弟们"),
                msg("我绷不住了"),
                msg("今天天气不错"),
                msg("今天天气不错"),
            )
        val candidates = SlangHarvest.candidates(messages, minHits = 3)
        assertTrue(
            "「绷不住」嵌在三句不同的话里，也该被滑窗捞出来，实际=$candidates",
            candidates.any { it.contains("绷不住") },
        )
        assertTrue("只出现两次的不该入选", !candidates.contains("今天天气不错"))
    }

    @Test
    fun `日常用语不进候选`() {
        val messages = List(5) { msg("可以") }
        val candidates = SlangHarvest.candidates(messages, minHits = 3)
        assertTrue("「可以」是汉语本身，不是梗", candidates.isEmpty())
    }

    @Test
    fun `机器人自己的话不参与统计`() {
        val messages =
            listOf(
                msg("绷不住了", role = "assistant"),
                msg("绷不住了", role = "assistant"),
                msg("绷不住了", role = "assistant"),
            )
        assertTrue(
            "机器人会放大自己的口癖，统计进去就变成自我循环",
            SlangHarvest.candidates(messages, minHits = 3).isEmpty(),
        )
    }

    @Test
    fun `人名不会被当成梗`() {
        val messages = List(4) { msg("小明来了") }
        val candidates = SlangHarvest.candidates(messages, names = listOf("小明"), minHits = 3)
        assertTrue(candidates.none { it.contains("小明") })
    }

    @Test
    fun `按出现次数排序`() {
        val messages =
            listOf(
                msg("绝绝子"), msg("绝绝子"), msg("绝绝子"), msg("绝绝子"),
                msg("破防了"), msg("破防了"), msg("破防了"),
            )
        val candidates = SlangHarvest.candidates(messages, minHits = 3)
        assertEquals("绝绝子", candidates.first())
    }

    @Test
    fun `渲染时最多注入五条且提醒别硬塞`() {
        val terms =
            (1..8).map {
                SlangTerm(id = it.toLong(), term = "词$it", meaning = "意思$it", enabled = true)
            }
        val block = SlangRender.block(terms)!!
        assertTrue(block.contains("词1"))
        assertTrue("第 6 条起不该注入", !block.contains("词6"))
        assertTrue(block.contains("别硬塞"))
        assertTrue(block.contains("用错场合比不用更糟"))
    }

    @Test
    fun `没有可用梗时不注入空块`() {
        assertNull(SlangRender.block(emptyList()))
        assertNull(SlangRender.block(listOf(SlangTerm(term = "x", meaning = "", enabled = true))))
        assertNull(
            SlangRender.block(listOf(SlangTerm(term = "x", meaning = "意思", enabled = false))),
        )
    }

    @Test
    fun `模型返回的 JSON 被解析成梗`() =
        runBlocking {
            enqueue(
                """[{"term":"绷不住了","meaning":"忍不住笑了","usage":"看到离谱的事"},""" +
                    """{"term":"绝绝子","meaning":"好到极点"}]""",
            )
            val judge = SlangJudge(LlmClient(http = OkHttpClient()))
            val judged =
                judge.filter(
                    candidates = listOf("绷不住了", "绝绝子"),
                    samples = listOf(msg("绷不住了")),
                    config = config(),
                )
            assertEquals(2, judged.size)
            assertEquals("绷不住了", judged[0].term)
            assertEquals("忍不住笑了", judged[0].meaning)
            assertNull("没有用法时不该编一个", judged[1].usage)
        }

    @Test
    fun `模型胡言乱语时返回空而不是崩`() =
        runBlocking {
            enqueue("我觉得这些都不像梗")
            val judge = SlangJudge(LlmClient(http = OkHttpClient()))
            assertTrue(judge.filter(listOf("啊这"), emptyList(), config()).isEmpty())
        }

    private fun enqueue(content: String) {
        val escaped =
            content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"choices":[{"message":{"content":"$escaped"},"finish_reason":"stop"}]}""",
                ),
        )
    }

    private fun config() =
        AiConfig(
            enabled = true,
            baseUrl = server.url("/").toString(),
            apiKey = "sk-test",
            model = "deepseek-chat",
            maxTokens = 800,
        )
}
