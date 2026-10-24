package com.qbot.core.ai

/**
 * 单次生成的 token 预算。
 *
 * 唯一来源是用户设置里的「单次生成上限」（`AiConfig.maxTokens`）——
 * 判断、反思、归并这些内部调用不再各自硬编码一个小数字，也不加隐藏下限。
 *
 * 重要语义：该额度统计的是「思考 + 正文」的总和，不是只看正文。
 * 实测依据：额度 40 时思维链把额度吃光，正文为空、`finish_reason=length`。
 * 因此开启思考时要留足余量，否则正文会被思考挤掉。
 */
object GenerationLimits {
    /** 与设置页滑杆下限一致 */
    const val MIN = 200

    /** 上限：主流服务商的单次输出上限普遍不超过 32k */
    const val MAX = 32000

    /** 本次调用实际可用的输出额度。 */
    fun forConfig(config: AiConfig): Int = config.maxTokens.coerceIn(MIN, MAX)

    /** 被截断（finish=length）时的重试额度：翻倍，仍受上限约束。 */
    fun retryFor(base: Int): Int = (base.toLong() * 2).coerceAtMost(MAX.toLong()).toInt()
}
