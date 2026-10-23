package com.qbot.core.ai

import com.qbot.core.api.SearchResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 搜索工具：模型可以用，但不能绕开开关、端点能力和失败处理。 */
class WebSearchToolTest {
    private fun cfg(
        webSearch: Boolean = true,
        baseUrl: String = "https://api.deepseek.com",
    ) = AiConfig(webSearch = webSearch, baseUrl = baseUrl)

    private fun query(text: String) = buildJsonObject { put("query", text) }

    @Test
    fun `搜索结果会整理成模型可读的文本`() =
        runBlocking {
            var called = 0
            val tool =
                WebSearchTool(
                    config = { cfg() },
                    search = { keyword, _ ->
                        called++
                        assertEquals("DeepSeek V4", keyword)
                        listOf(
                            SearchResult(
                                title = "发布公告",
                                url = "https://example.com/a",
                                summary = "DeepSeek 发布了新模型。",
                            ),
                        )
                    },
                )

            val result = tool.execute(query("DeepSeek V4"))

            assertEquals(1, called)
            assertTrue(result.content.contains("发布公告"))
            assertTrue(result.content.contains("https://example.com/a"))
            assertTrue("应提示结果可能不准确", result.content.contains("可能不准确"))
        }

    @Test
    fun `关闭搜索时直接拒绝且不发起请求`() =
        runBlocking {
            var called = 0
            val tool =
                WebSearchTool(
                    config = { cfg(webSearch = false) },
                    search = { _, _ ->
                        called++
                        emptyList()
                    },
                )

            val result = tool.execute(query("今天新闻"))

            assertEquals(0, called)
            assertTrue(result.content.contains("已关闭"))
        }

    @Test
    fun `非 DeepSeek 端点提示没有搜索接口`() =
        runBlocking {
            var called = 0
            val tool =
                WebSearchTool(
                    config = { cfg(baseUrl = "https://api.openai.com") },
                    search = { _, _ ->
                        called++
                        emptyList()
                    },
                )

            val result = tool.execute(query("今天新闻"))

            assertEquals(0, called)
            assertTrue(result.content.contains("没有搜索接口"))
        }

    @Test
    fun `空关键词不搜索`() =
        runBlocking {
            var called = 0
            val tool =
                WebSearchTool(
                    config = { cfg() },
                    search = { _, _ ->
                        called++
                        emptyList()
                    },
                )

            val result = tool.execute(buildJsonObject { })

            assertEquals(0, called)
            assertTrue(result.content.contains("缺少搜索关键词"))
        }

    @Test
    fun `搜索抛异常时返回说明而不是炸掉回复`() =
        runBlocking {
            val tool =
                WebSearchTool(
                    config = { cfg() },
                    search = { _, _ -> error("network down") },
                )

            val result = tool.execute(query("今天新闻"))

            assertFalse(result.content.isBlank())
            assertTrue(result.content.contains("搜索失败"))
        }
}
