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
    fun `问句与代词不会被当成名字`() {
        assertEquals(emptyList<String>(), BotNameMatcher.namesFromPrompt("你叫什么名字？"))
        assertEquals(emptyList<String>(), BotNameMatcher.namesFromPrompt("你叫他来一下"))
    }
}
