package com.qbot.core.util

/**
 * 极简日志抽象。
 *
 * botcore 是纯 JVM 模块，不能依赖 android.util.Log，
 * Android 侧在 Application 里注入一个转发到 Logcat 的实现即可。
 */
interface Logger {
    fun v(tag: String, msg: String)
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String, t: Throwable? = null)
}

/** 默认实现：输出到 stdout，供桌面端 / 单元测试使用。 */
object StdoutLogger : Logger {
    private const val TAG = "QBot"
    override fun v(tag: String, msg: String) = println("V/$tag: $msg")
    override fun d(tag: String, msg: String) = println("D/$tag: $msg")
    override fun i(tag: String, msg: String) = println("I/$tag: $msg")
    override fun w(tag: String, msg: String) = println("W/$tag: $msg")
    override fun e(tag: String, msg: String, t: Throwable?) {
        println("E/$tag: $msg")
        t?.printStackTrace()
    }
}

object NoopLogger : Logger {
    override fun v(tag: String, msg: String) = Unit
    override fun d(tag: String, msg: String) = Unit
    override fun i(tag: String, msg: String) = Unit
    override fun w(tag: String, msg: String) = Unit
    override fun e(tag: String, msg: String, t: Throwable?) = Unit
}

/**
 * 系统时钟抽象。
 *
 * 验证码刷新、心跳超时、重连退避三类逻辑都强依赖时间，
 * 单元测试里注入 [FakeClock] 即可脱离真实等待验证。
 */
interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

class FakeClock(private var current: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis(): Long = current
    fun advance(deltaMillis: Long) {
        current += deltaMillis
    }
    fun set(now: Long) {
        current = now
    }
}
