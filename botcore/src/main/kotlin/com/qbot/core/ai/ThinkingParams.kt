package com.qbot.core.ai

/**
 * 思考模式参数的统一判断。
 *
 * `thinking` 是 DeepSeek 的扩展字段，只有 DeepSeek 端点认识——其它服务商
 * 收到未知字段可能直接 400，所以必须按端点/模型名判断后再决定发不发。
 *
 * [forConfig] 的 `forceDisable` 给「结构化短输出」的后台任务用（反思、归并）：
 * 即使全局开着思考也强制关掉。否则思维链会消耗 maxTokens，最终
 * `content` 返回空串——2.1.0 的经验生成失败就是这么来的。
 */
object ThinkingParams {
    fun isDeepSeek(config: AiConfig): Boolean =
        config.baseUrl.contains("deepseek", ignoreCase = true) ||
            config.model.contains("deepseek", ignoreCase = true)

    fun forConfig(config: AiConfig, forceDisable: Boolean = false): String? {
        if (!isDeepSeek(config)) return null
        if (forceDisable) return "disabled"
        return when (config.thinkingMode) {
            ThinkingMode.DISABLED -> "disabled"
            ThinkingMode.ENABLED -> "enabled"
            ThinkingMode.AUTO -> null
        }
    }
}
