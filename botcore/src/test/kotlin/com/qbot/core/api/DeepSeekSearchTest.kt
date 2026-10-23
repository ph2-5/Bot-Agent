package com.qbot.core.api

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 搜索端点的拼接：用户填的 baseUrl 形态很多，拼错就是一次无效请求。 */
class DeepSeekSearchTest {
    private val search = DeepSeekSearch(OkHttpClient())

    @Test
    fun `根地址与带 v1 的地址都拼到标准端点`() {
        assertEquals(
            "https://api.deepseek.com/v1/tools/search",
            search.endpointOf("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/search",
            search.endpointOf("https://api.deepseek.com/v1/"),
        )
    }

    @Test
    fun `直接粘完整对话地址时不会拼出重复路径`() {
        assertEquals(
            "https://api.deepseek.com/v1/tools/search",
            search.endpointOf("https://api.deepseek.com/v1/chat/completions"),
        )
    }

    @Test
    fun `非 DeepSeek 端点没有搜索接口`() {
        assertNull(search.endpointOf("https://api.openai.com/v1"))
        assertNull(search.endpointOf(""))
    }
}
