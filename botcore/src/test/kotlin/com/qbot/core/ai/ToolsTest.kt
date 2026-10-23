package com.qbot.core.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具层的核心约束：模型只能「选」，不能「造」。
 *
 * 参数由模型给，但地址是否真实存在、能不能发，由本地库判定——
 * 这是接 tools 之后新出现的外部输入面，必须在这里挡住。
 */
class ToolsTest {
    private class FakeLibrary(
        private val urls: List<String> = listOf("https://a.com/1.jpg"),
    ) : MemeLibrary {
        val used = mutableListOf<String>()

        override suspend fun contains(url: String): Boolean = url in urls

        override suspend fun recordUse(url: String) {
            used += url
        }

        override suspend fun pick(): String? = urls.firstOrNull()
    }

    private fun args(url: String) = buildJsonObject { put("url", url) }

    @Test
    fun `候选库里的图会被选中并带回地址`() =
        runBlocking {
            val result = SendMemeTool(FakeLibrary()).execute(args("https://a.com/1.jpg"))
            assertEquals("https://a.com/1.jpg", result.memeUrl)
        }

    @Test
    fun `库外的地址一律不发`() =
        runBlocking {
            val result = SendMemeTool(FakeLibrary()).execute(args("https://evil.example/x.jpg"))
            assertNull("编造的地址不能进入发送链路", result.memeUrl)
            assertTrue(result.content.contains("不在候选库"))
        }

    @Test
    fun `缺少地址时不发`() =
        runBlocking {
            val result = SendMemeTool(FakeLibrary()).execute(buildJsonObject { })
            assertNull(result.memeUrl)
            assertTrue(result.content.contains("缺少"))
        }

    @Test
    fun `注册表能按名字执行已知工具`() =
        runBlocking {
            val registry = ToolRegistry(listOf(SendMemeTool(FakeLibrary())))
            val result =
                registry.execute(
                    ToolCall(
                        id = "call_1",
                        name = "send_meme",
                        arguments = """{"url":"https://a.com/1.jpg"}""",
                    ),
                )
            assertEquals("https://a.com/1.jpg", result.memeUrl)
        }

    @Test
    fun `未知工具返回说明而不是抛异常`() =
        runBlocking {
            val result =
                ToolRegistry().execute(ToolCall("call_1", "delete_everything", "{}"))
            assertNull(result.memeUrl)
            assertTrue(result.content.contains("未知工具"))
        }

    @Test
    fun `非法参数 JSON 被本地拦住`() =
        runBlocking {
            val registry = ToolRegistry(listOf(SendMemeTool(FakeLibrary())))
            val result = registry.execute(ToolCall("call_1", "send_meme", "{not-json"))
            assertNull(result.memeUrl)
            assertTrue(result.content.contains("不是合法 JSON"))
        }

    @Test
    fun `参数不是对象时也拒绝执行`() =
        runBlocking {
            val registry = ToolRegistry(listOf(SendMemeTool(FakeLibrary())))
            val result = registry.execute(ToolCall("call_1", "send_meme", "\"https://a.com/1.jpg\""))
            assertNull(result.memeUrl)
            assertTrue(result.content.contains("必须是 JSON 对象"))
        }

    @Test
    fun `空注册表不暴露任何 schema`() {
        assertTrue(ToolRegistry().isEmpty)
        assertTrue(ToolRegistry().specs.isEmpty())
    }
}
