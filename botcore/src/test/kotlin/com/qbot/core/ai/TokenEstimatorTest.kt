package com.qbot.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEstimatorTest {
    @Test
    fun `空文本为 0`() {
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun `中文按一字一 token 估算`() {
        // 4 个汉字 + 1 个字母 = 4 + ceil(1/4) = 5
        assertEquals(5, TokenEstimator.estimate("你好世界abcde".take(5)))
        assertEquals(5, TokenEstimator.estimate("你好世界友"))
    }

    @Test
    fun `英文按四字符一 token 估算`() {
        assertEquals(1, TokenEstimator.estimate("abcd"))
        assertEquals(2, TokenEstimator.estimate("abcdefgh"))
        // 除不尽时向上取整，宁可多估不可少估
        assertEquals(2, TokenEstimator.estimate("abcde"))
    }

    @Test
    fun `中英混排分别计算`() {
        // 4 个汉字 + 4 个英文字符 = 4 + 1
        assertEquals(5, TokenEstimator.estimate("你好世界abcd"))
    }

    @Test
    fun `单条消息至少估算 1 token`() {
        assertEquals(1, TokenEstimator.estimate("a"))
    }

    @Test
    fun `多条消息合计`() {
        val messages =
            listOf(
                ChatMessage("system", "你好世界"),
                ChatMessage("user", "abcd"),
            )
        assertEquals(4 + 1, TokenEstimator.estimate(messages))
    }

    @Test
    fun `预算内不丢弃任何历史`() {
        // 直接走 AiResponder 的组装逻辑验证（公开 API 层面）
        val history =
            listOf(
                ChatMessage("user", "问题"),
                ChatMessage("assistant", "回答"),
            )
        val responder = AiResponder(client = LlmClient(http = okhttp3.OkHttpClient()))
        val messages =
            responder.assembleMessages(
                systemPrompt = "系统",
                history = history,
                question = "本轮",
                tokenBudget = 1_000,
            )
        assertTrue("历史应全部保留", messages.any { it.content == "问题" })
        assertTrue("历史应全部保留", messages.any { it.content == "回答" })
    }
}
