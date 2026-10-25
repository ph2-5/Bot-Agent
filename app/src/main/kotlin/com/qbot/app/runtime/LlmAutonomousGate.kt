package com.qbot.app.runtime

import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.AutonomousDecision
import com.qbot.core.ai.AutonomousGate
import com.qbot.core.ai.AuxAiConfig
import com.qbot.core.ai.ChatMessage
import com.qbot.core.ai.LlmClient
import okhttp3.OkHttpClient

/**
 * 第二道闸门：用便宜模型判断"这句话该不该现在说"。
 *
 * 与 [com.qbot.app.data.repo.LlmMemoryReranker] 一样复用 [AuxAiConfig]：
 * 用户为记忆精排配过的辅助模型，可以顺手给自主开口判定用，
 * 不填独立地址就沿用主模型。**只返回要不要说，不写正文**——
 * 正文交给主模型，便宜模型省下来的钱不能被它自己写台词烧掉。
 *
 * 任何失败（超时 / 报错 / 输出跑偏）都返回 null：调用方一律按 HOLD 处理，
 * 宁可少说一句，也不要因为判定服务抖动在群里乱开口。
 */
class LlmAutonomousGate(
    http: OkHttpClient,
    private val configProvider: () -> Pair<AiConfig, AuxAiConfig>,
) : AutonomousGate {
    private val client = LlmClient(http = http)

    override suspend fun shouldSpeak(
        context: String,
        config: AuxAiConfig,
    ): AutonomousDecision? {
        if (context.isBlank()) return null
        val (main, aux) = configProvider()
        if (!aux.enabled) return null

        val useAux = aux.standalone
        val baseUrl = if (useAux) aux.baseUrl else main.baseUrl
        val apiKey = if (useAux) aux.apiKey.ifBlank { main.apiKey } else main.apiKey
        val model = if (useAux) aux.model else main.model
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return null

        val messages =
            listOf(
                ChatMessage(
                    role = "system",
                    content =
                        SYSTEM_PROMPT,
                ),
                ChatMessage(
                    role = "user",
                    content = context.take(CONTEXT_MAX_CHARS) + "\n\n" + INSTRUCTION,
                ),
            )
        val reply =
            runCatching {
                client.chat(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    messages = messages,
                    temperature = 0.0,
                    maxTokens = config.maxTokens.coerceIn(60, 400),
                    timeoutSeconds = config.timeoutSeconds,
                )
            }.getOrNull() ?: return null

        return parse(reply.content)
    }

    private fun parse(raw: String): AutonomousDecision? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val upper = text.uppercase()
        // 判定词优先取第一行：模型常常先写结论再解释
        val head = upper.lineSequence().firstOrNull { it.isNotBlank() } ?: upper
        val speak =
            when {
                HOLD_WORDS.any { it in head } && SPEAK_WORDS.none { it in head } -> false
                SPEAK_WORDS.any { it in head } -> true
                else -> return null
            }
        val reason =
            text.lineSequence()
                .drop(1)
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.removePrefix("理由：")
                ?.removePrefix("理由:")
                ?.take(120)
                ?: ""
        val topic =
            TOPIC.findAll(text).firstOrNull()?.groupValues?.getOrNull(1)?.trim()?.take(40)
        return AutonomousDecision(speak = speak, reason = reason, topic = topic)
    }

    companion object {
        /** 群气氛复杂，一次判定最多看这么多字就够；再长也是重复信息 */
        private const val CONTEXT_MAX_CHARS = 2600

        private val SPEAK_WORDS = listOf("SPEAK", "可以说", "应该说", "开口")
        private val HOLD_WORDS = listOf("HOLD", "不要说", "别说", "忍住", "不必说")
        private val TOPIC = Regex("主题[：:]\\s*(.+)")

        private val SYSTEM_PROMPT =
            "你在帮一个 QQ 群机器人判断：现在要不要自己接一句话。\n" +
                "注意：它不是被叫，是自己想开口。所以标准比被动回复更严。\n" +
                "倾向 HOLD 的情况：\n" +
                "- 两个人正在一对一互怼 / 有人明确说「关你什么事」「别插嘴」；\n" +
                "- 话题与它的性格、记忆完全无关；\n" +
                "- 群里聊得正快，插话只会显得突兀；\n" +
                "- 它刚说过话，或说的内容只是复述别人。\n" +
                "倾向 SPEAK 的情况：\n" +
                "- 聊的正是它懂、有兴趣的事，能接一句有用或有梗的话；\n" +
                "- 有人抛出了问题/情绪，而没有人接。\n" +
                "只输出两行：第一行 SPEAK 或 HOLD；第二行「理由：」加一句话。\n" +
                "如果确定要说，可以再补一行「主题：」写它想说的方向（不超过 20 字）。"

        private const val INSTRUCTION = "现在输出你的判断。"
    }
}
