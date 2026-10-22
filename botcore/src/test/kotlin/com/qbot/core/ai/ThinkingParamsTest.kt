package com.qbot.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考开关的判断。
 *
 * 这个字段只有 DeepSeek 认识，发错服务商会 400；而该发不发时，
 * DeepSeek 会默认开启思考，把短任务的输出额度全耗在思维链上。
 */
class ThinkingParamsTest {
    private fun config(
        baseUrl: String = "https://api.deepseek.com",
        model: String = "deepseek-chat",
        thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
    ) = AiConfig(baseUrl = baseUrl, model = model, thinkingMode = thinkingMode)

    @Test
    fun `DeepSeek 端点按设置发送`() {
        assertEquals("disabled", ThinkingParams.forConfig(config()))
        assertEquals(
            "enabled",
            ThinkingParams.forConfig(config(thinkingMode = ThinkingMode.ENABLED)),
        )
        assertNull(ThinkingParams.forConfig(config(thinkingMode = ThinkingMode.AUTO)))
    }

    @Test
    fun `非 DeepSeek 服务商不发送该字段`() {
        val openai = config(baseUrl = "https://api.openai.com", model = "gpt-4o-mini")
        assertFalse(ThinkingParams.isDeepSeek(openai))
        assertNull(ThinkingParams.forConfig(openai, forceDisable = true))
    }

    @Test
    fun `结构化短任务强制关闭思考`() {
        // 即使全局开了思考，反思/归并也要关掉，否则正文会是空的
        assertEquals(
            "disabled",
            ThinkingParams.forConfig(
                config(thinkingMode = ThinkingMode.ENABLED),
                forceDisable = true,
            ),
        )
    }

    @Test
    fun `仅模型名匹配也算 DeepSeek`() {
        val proxy = config(baseUrl = "https://proxy.example.com", model = "deepseek-flash")
        assertTrue(ThinkingParams.isDeepSeek(proxy))
        assertEquals("disabled", ThinkingParams.forConfig(proxy, forceDisable = true))
    }
}
