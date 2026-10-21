package com.qbot.core.gateway

import kotlin.math.pow
import kotlin.random.Random

/**
 * 重连退避策略。
 *
 * 手机会在地铁、电梯、电梯口频繁断网，如果断线后立刻按固定间隔猛重连，
 * 既耗电又会被平台限频（错误码 4008）。这里用带抖动的指数退避：
 *
 * ```
 * delay = min(base * 2^attempt, max) * (1 ± jitter)
 * ```
 *
 * 抖动的作用是把多台设备/多次重连的时刻打散，避免"重连风暴"。
 */
object Backoff {
    const val DEFAULT_BASE_MILLIS = 1_000L
    const val DEFAULT_MAX_MILLIS = 30_000L
    const val DEFAULT_JITTER = 0.2

    /** 随机源独立注入，便于单元测试固定序列。 */
    var random: () -> Double = { Random.nextDouble() }

    /**
     * 计算第 [attempt] 次重连（从 0 开始）应等待的毫秒数。
     */
    fun delayFor(
        attempt: Int,
        baseMillis: Long = DEFAULT_BASE_MILLIS,
        maxMillis: Long = DEFAULT_MAX_MILLIS,
        jitter: Double = DEFAULT_JITTER,
    ): Long {
        require(attempt >= 0) { "attempt 必须非负" }
        val raw = baseMillis.toDouble() * 2.0.pow(attempt.coerceAtMost(20))
        val capped = raw.coerceAtMost(maxMillis.toDouble())
        val factor = 1.0 + (random() * 2.0 - 1.0) * jitter
        return (capped * factor).toLong().coerceIn(0L, maxMillis)
    }
}
