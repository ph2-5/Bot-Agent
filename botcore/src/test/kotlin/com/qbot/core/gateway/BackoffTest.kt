package com.qbot.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test
    fun `首次重连等待约等于基准值`() {
        Backoff.random = { 0.5 } // 抖动因子取中间值 → 不放大也不缩小
        val delay = Backoff.delayFor(0)
        assertEquals(1000L, delay)
    }

    @Test
    fun `退避随重试次数指数增长`() {
        Backoff.random = { 0.5 }
        assertEquals(1000L, Backoff.delayFor(0))
        assertEquals(2000L, Backoff.delayFor(1))
        assertEquals(4000L, Backoff.delayFor(2))
        assertEquals(8000L, Backoff.delayFor(3))
    }

    @Test
    fun `退避上限被正确截断`() {
        Backoff.random = { 0.5 }
        // 2^20 * 1000 远超 30s 上限
        assertEquals(Backoff.DEFAULT_MAX_MILLIS, Backoff.delayFor(20))
        assertEquals(Backoff.DEFAULT_MAX_MILLIS, Backoff.delayFor(50))
    }

    @Test
    fun `抖动不会让等待时间变负或超过上限`() {
        // 最不利情况：随机值始终取到边界
        listOf({ 0.0 }, { 1.0 }).forEach { randomFn ->
            Backoff.random = randomFn
            for (attempt in 0..25) {
                val delay = Backoff.delayFor(attempt)
                assertTrue("delay 不能为负: $delay", delay >= 0L)
                assertTrue("delay 不能超过上限: $delay", delay <= Backoff.DEFAULT_MAX_MILLIS)
            }
        }
    }

    @Test
    fun `抖动确实引入了随机性`() {
        Backoff.random = { 0.0 } // 因子 = 1 - 0.2 = 0.8
        val low = Backoff.delayFor(3)
        Backoff.random = { 1.0 } // 因子 = 1 + 0.2 = 1.2
        val high = Backoff.delayFor(3)
        assertTrue("抖动应产生不同结果: low=$low high=$high", high > low)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `负的重试次数是非法的`() {
        Backoff.delayFor(-1)
    }
}
