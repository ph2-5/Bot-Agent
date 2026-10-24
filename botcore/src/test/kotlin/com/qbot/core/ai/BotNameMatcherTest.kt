package com.qbot.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 机器人名字匹配与角色卡自称抽取。
 *
 * 群名片与角色卡经常不是一个名字：群名片「过去的阿远」，角色卡写"你叫阿远"。
 * 只认群名片会漏掉群友的日常称呼，导致"叫了名字却不回"、
 * 或者接话了却把对话对象记成"对全群说"。
 */
class BotNameMatcherTest {
    @Test
    fun `简称按后缀命中全名`() {
        assertTrue(BotNameMatcher.mentioned("阿远你个傻福", listOf("过去的阿远")))
    }

    @Test
    fun `单字后缀不启用避免误伤普通词`() {
        assertTrue(!BotNameMatcher.mentioned("远远不够", listOf("过去的阿远")))
    }

    @Test
    fun `从角色卡抽取自称`() {
        val names =
            BotNameMatcher.namesFromPrompt(
                "你叫阿远，是一个群友。你的名字是阿远。",
            )
        assertEquals(listOf("阿远"), names)
    }

    @Test
    fun `角色卡开头的你是阿远也能抽到`() {
        // 用户真实的角色卡就是这种写法，不带「叫」字
        val names = BotNameMatcher.namesFromPrompt("你是阿远，是这个群里最熟的老面孔。")
        assertEquals(listOf("阿远"), names)
    }

    @Test
    fun `泛称不会被当成名字`() {
        assertEquals(emptyList<String>(), BotNameMatcher.namesFromPrompt("你是一个 AI 助手，要保持自然。"))
    }

    @Test
    fun `问句与代词不会被当成名字`() {
        assertEquals(emptyList<String>(), BotNameMatcher.namesFromPrompt("你叫什么名字？"))
        assertEquals(emptyList<String>(), BotNameMatcher.namesFromPrompt("你叫他来一下"))
    }

    @Test
    fun `名字出现在划界句里不算在叫机器人`() {
        // 真实群聊：「我和陈泽说话关你阿远什么事」——
        // contains 会把名字夹在中间判成"被叫"，机器人于是硬插进两人的对话
        assertTrue(!BotNameMatcher.called("我和陈泽说话关你阿远什么事", listOf("过去的阿远")))
        assertTrue(BotNameMatcher.isRefusal("我和陈泽说话关你阿远什么事"))
    }

    @Test
    fun `否认身份时不算在叫机器人`() {
        // 「我不是阿远，认错人了」是在澄清，不是叫人
        assertTrue(!BotNameMatcher.called("我不是阿远。认错人了。", listOf("阿远")))
        assertTrue(!BotNameMatcher.called("阿远今天没来", listOf("阿远")))
    }

    @Test
    fun `正常称呼仍然算叫机器人`() {
        assertTrue(BotNameMatcher.called("阿远，在吗", listOf("阿远")))
        assertTrue(BotNameMatcher.called("阿远你个傻福", listOf("过去的阿远")))
    }

    @Test
    fun `没跟你说话这类拒斥句直接识别`() {
        assertTrue(BotNameMatcher.isRefusal("没跟你说话，别插嘴"))
        assertTrue(!BotNameMatcher.isRefusal("阿远你今晚来吗"))
    }
}
