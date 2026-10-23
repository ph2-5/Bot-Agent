package com.qbot.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表情包标记与提示词块。
 *
 * 标记解析是发图链路的入口：认对了才发得出去，认错了必须退化为纯文字——
 * 所以「没有标记」「标记不完整」「奇形怪状的标记」都是要有明确行为的用例。
 */
class MemeTest {
    @Test
    fun `没有标记时原样返回`() {
        val (text, url) = MemeMarker.parse("今天天气不错")
        assertEquals("今天天气不错", text)
        assertNull(url)
    }

    @Test
    fun `末尾标记被剥掉并取出地址`() {
        val (text, url) = MemeMarker.parse("笑死 [表情:https://example.com/a.jpg]")
        assertEquals("笑死", text)
        assertEquals("https://example.com/a.jpg", url)
    }

    @Test
    fun `纯表情包回复正文为空`() {
        val (text, url) = MemeMarker.parse("[表情:https://example.com/a.jpg]")
        assertEquals("", text)
        assertEquals("https://example.com/a.jpg", url)
    }

    @Test
    fun `多个标记只取第一张且全部剥掉`() {
        val (text, url) =
            MemeMarker.parse(
                "嗨 [表情:https://example.com/a.jpg] 再看这个 [表情:https://example.com/b.jpg]",
            )
        assertEquals("嗨  再看这个", text)
        assertEquals("https://example.com/a.jpg", url)
    }

    @Test
    fun `非 http 地址不算标记`() {
        val raw = "看这个 [表情:ftp://example.com/a.jpg]"
        val (text, url) = MemeMarker.parse(raw)
        assertEquals(raw, text)
        assertNull(url)
    }

    @Test
    fun `空文本不炸`() {
        val (text, url) = MemeMarker.parse("")
        assertEquals("", text)
        assertNull(url)
    }

    @Test
    fun `提示词块为空列表时返回 null`() {
        assertNull(MemeRender.block(emptyList()))
        assertNull(MemeRender.block(listOf("", "  ")))
    }

    @Test
    fun `提示词块带地址与约束`() {
        val block = MemeRender.block(listOf("https://a.com/1.jpg", "https://a.com/2.jpg"))
        requireNotNull(block)
        assertTrue(block.contains("https://a.com/1.jpg"))
        assertTrue(block.contains("[表情:图片地址]"))
        // 关键约束都要在
        assertTrue(block.contains("配菜不是主食"))
        assertTrue(block.contains("一次最多带一张"))
        assertTrue(block.contains("不要自己编"))
    }

    @Test
    fun `提示词块截断到上限`() {
        val many = (1..20).map { "https://a.com/$it.jpg" }
        val block = MemeRender.block(many)
        requireNotNull(block)
        // 上限 8 张 + 去重后仍应是 8
        val counted = many.count { block.contains(it) }
        assertEquals(MemeRender.MAX_PROMPT_MEMES, counted)
    }

    @Test
    fun `明确的要图请求会被识别`() {
        assertTrue(MemeIntent.isRequest("发个表情包"))
        assertTrue(MemeIntent.isRequest("来张图"))
        assertTrue(MemeIntent.isRequest("斗图吗"))
    }

    @Test
    fun `聊图本身的句子不会被当成要图`() {
        // 这类句子应该交给模型正常接话，而不是被本地直接回一张图
        assertTrue(!MemeIntent.isRequest("你刚才那个表情包挺好笑的"))
        assertTrue(!MemeIntent.isRequest("这个表情什么意思"))
        assertTrue(!MemeIntent.isRequest("今天好累啊"))
    }
}
