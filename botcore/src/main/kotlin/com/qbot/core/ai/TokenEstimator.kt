package com.qbot.core.ai

import kotlin.math.ceil

/**
 * 轻量 token 估算器。
 *
 * 为什么不引入真实分词器：
 * - 各家模型的分词器不同，且需要打包几十 MB 的词表；
 * - 我们只需要一个"预算守门员"级别的估算，误差 ±20% 完全可接受；
 * - 宁多估勿少估：超预算的后果（服务端 400）比浪费一点预算严重得多。
 *
 * 经验值（对 DeepSeek/Qwen 等中英文混合场景足够准）：
 * - CJK 字符（汉字、全角标点）约 1 字 = 1 token；
 * - 其余字符（英文、数字、半角符号）约 4 字符 = 1 token。
 */
object TokenEstimator {
    /** 单条文本的估算 token 数，至少为 1（除非为空）。 */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code >= 0x2E80) cjk++ else other++
        }
        val tokens = cjk + ceil(other / 4.0).toInt()
        return tokens.coerceAtLeast(1)
    }

    /** 多条文本的合计估算 token 数。 */
    fun estimate(messages: List<ChatMessage>): Int = messages.sumOf { estimate(it.content) }
}
