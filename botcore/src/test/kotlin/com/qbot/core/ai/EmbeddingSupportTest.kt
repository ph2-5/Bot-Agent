package com.qbot.core.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * 向量检索的基础件：归一化、点积、序列化、RRF 融合、失败熔断。
 *
 * 全部是纯函数 / 纯状态机，不碰网络与数据库——
 * Android 侧只需要把「调接口」和「查表」接进来。
 */
class EmbeddingSupportTest {
    @Test
    fun `归一化后点积等于余弦相似度`() {
        val a = floatArrayOf(3f, 4f, 0f)
        val b = floatArrayOf(1f, 2f, 2f)
        val cosine =
            (3.0 * 1 + 4.0 * 2 + 0.0 * 2) / (sqrt((3.0 * 3 + 4.0 * 4)) * sqrt((1.0 + 4.0 + 4.0)))

        val na = EmbeddingVectors.normalize(a)
        val nb = EmbeddingVectors.normalize(b)
        val dot = EmbeddingVectors.dot(na, nb)

        assertEquals("误差应小于 1e-5", cosine, dot.toDouble(), 1e-5)
        assertEquals(1.0, sqrt(EmbeddingVectors.dot(na, na).toDouble()), 1e-5)
    }

    @Test
    fun `零向量归一化不会产生 NaN`() {
        val zero = EmbeddingVectors.normalize(floatArrayOf(0f, 0f, 0f))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), zero, 0f)
        assertEquals(0f, EmbeddingVectors.dot(zero, zero), 0f)
    }

    @Test
    fun `BLOB 序列化按小端 float32 往返一致`() {
        val vector = EmbeddingVectors.normalize(floatArrayOf(0.25f, -1.5f, 3f, 0.125f))
        val blob = EmbeddingVectors.toBlob(vector)
        assertEquals(vector.size * 4, blob.size)
        assertArrayEquals(vector, EmbeddingVectors.fromBlob(blob, vector.size), 0f)

        // 维度不匹配时不能读出半截数据
        assertEquals(0, EmbeddingVectors.fromBlob(blob, vector.size + 1).size)
        assertEquals(0, EmbeddingVectors.fromBlob(blob, 0).size)
    }

    @Test
    fun `维度不同时点积为 0 而不是越界`() {
        assertEquals(0f, EmbeddingVectors.dot(floatArrayOf(1f), floatArrayOf(1f, 2f)), 0f)
    }

    @Test
    fun `RRF 让两个榜单都靠前的文档排第一`() {
        val inverted = listOf("FACT:1", "FACT:2", "FACT:3")
        val vector = listOf("FACT:3", "FACT:4", "FACT:1")

        val fused = Rrf.fuse(listOf(inverted, vector), limit = 10)

        assertEquals("FACT:1", fused.first())
        assertEquals("FACT:3", fused[1])
        assertTrue(fused.contains("FACT:2"))
        assertTrue(fused.contains("FACT:4"))
    }

    @Test
    fun `RRF 按 limit 截断并保持确定性`() {
        val one = listOf("A", "B", "C", "D")
        val two = listOf("D", "C", "B", "A")
        val first = Rrf.fuse(listOf(one, two), limit = 2)
        val second = Rrf.fuse(listOf(one, two), limit = 2)
        assertEquals(first, second)
        assertEquals(2, first.size)
    }

    @Test
    fun `连续失败三次进入暂停且第四次不再调用`() {
        val guard = EmbeddingGuard(clock = { 1_000_000L })
        var calls = 0

        repeat(4) {
            if (guard.isPaused()) return@repeat
            calls++
            val paused = guard.recordFailure()
            if (paused) assertTrue(guard.isPaused())
        }

        assertEquals("三次失败后应停止调用", 3, calls)
        assertTrue(guard.isPaused())
        assertTrue(guard.pauseRemainingMillis() > 0)
    }

    @Test
    fun `暂停到期后自动恢复且成功一次会清零失败计数`() {
        var now = 1_000_000L
        val guard = EmbeddingGuard(clock = { now })

        guard.recordFailure()
        guard.recordFailure()
        guard.recordSuccess()
        guard.recordFailure()
        guard.recordFailure()
        assertFalse("成功一次后重新计数，不应暂停", guard.isPaused())

        guard.recordFailure()
        assertTrue(guard.isPaused())

        now += EmbeddingGuard.PAUSE_MILLIS + 1
        assertFalse("暂停到期应自动恢复", guard.isPaused())
    }

    @Test
    fun `查询缓存 60 秒内命中且按模型隔离`() {
        var now = 1_000_000L
        val guard = EmbeddingGuard(clock = { now })
        val vector = floatArrayOf(1f, 0f)

        guard.cacheQuery("bge:今天玩什么", vector)
        assertNotNull(guard.cachedQuery("bge:今天玩什么"))
        assertNull("不同模型的缓存必须分开", guard.cachedQuery("other:今天玩什么"))

        now += EmbeddingGuard.CACHE_MILLIS + 1
        assertNull("过期后不再命中", guard.cachedQuery("bge:今天玩什么"))
    }

    @Test
    fun `未探测维度时向量链路不就绪`() {
        val base =
            EmbeddingConfig(
                enabled = true,
                baseUrl = "https://api.siliconflow.cn/v1",
                apiKey = "sk-test",
                model = "BAAI/bge-m3",
                dim = 0,
            )
        assertTrue(base.callable)
        assertFalse("维度未探测不允许启用检索", base.ready)

        assertTrue(base.copy(dim = 1024).ready)
        assertFalse("关闭开关时即使配置完整也不就绪", base.copy(dim = 1024, enabled = false).ready)
    }

    @Test
    fun `端点归一化不会重复拼 v1`() {
        val client = EmbeddingClient(okhttp3.OkHttpClient())
        assertEquals(
            "https://api.siliconflow.cn/v1/embeddings",
            client.normalizeEndpoint("https://api.siliconflow.cn/v1"),
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/embeddings",
            client.normalizeEndpoint("https://open.bigmodel.cn/api/paas/v4"),
        )
        assertEquals(
            "https://api.openai.com/v1/embeddings",
            client.normalizeEndpoint("https://api.openai.com"),
        )
        assertEquals(
            "http://127.0.0.1:11434/v1/embeddings",
            client.normalizeEndpoint("127.0.0.1:11434"),
        )
    }
}
