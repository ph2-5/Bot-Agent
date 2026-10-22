package com.qbot.core.ai

/**
 * 提示词组装器——**顺序本身就是缓存策略**。
 *
 * 注入顺序（对前缀缓存的影响从大到小）：
 *
 * 1. **人设（system）**：固定最前、内容逐字节稳定。DeepSeek 的前缀缓存要求
 *    「从第 0 个 token 起完全相同」，人设里插时间戳之类变化内容会让后面全部失效。
 * 2. **关于对方的事实（system）**：按人隔离，且只追加不重写——
 *    同一个人后续对话的前缀保持稳定，缓存持续命中。
 * 3. **群聊背景（system）**：动态内容，每条消息都变，注定无法缓存，
 *    因此放在事实之后、历史之前——它打断缓存的代价被控制在最小范围。
 * 4. **会话历史（user/assistant）**：按人隔离的对话线程。
 * 5. **本轮问题（user）**：带 `[展示名]` 前缀，固定最后。
 *
 * 预算不足时的降级顺序：先砍群聊背景，再砍历史（成对丢弃），
 * 事实保底保留前 [MIN_FACTS_KEPT] 条，人设与本轮问题永不丢弃。
 */
class PromptAssembler {
    companion object {
        /** 事实块最多占用预算的比例 */
        private const val FACTS_BUDGET_RATIO = 0.3

        /** 群聊背景最多占用预算的比例 */
        private const val DIGEST_BUDGET_RATIO = 0.25

        /** 预算再紧也至少保留几条事实 */
        private const val MIN_FACTS_KEPT = 3

        /** 群聊背景单条最大字符数 */
        private const val DIGEST_ENTRY_MAX = 80

        /** 群聊背景条数上限（与存储层的保留量解耦，双保险） */
        private const val DIGEST_ENTRY_LIMIT = 20
    }

    /**
     * 组装消息列表。
     *
     * @param personaPrompt 人设系统提示词（空串则不注入 system 人设）
     * @param memory 本条消息的记忆上下文（展示名 / 事实 / 群聊背景），可为空
     * @param history 按人隔离的会话历史（正序）
     * @param question 本轮问题（已剥离 /ai 前缀与 @ 前缀）
     * @param tokenBudget 「人设 + 事实 + 背景 + 历史 + 问题」的总量上限
     * @param senderPrefix 是否给本轮问题加 `[展示名]` 前缀
     */
    fun assemble(
        personaPrompt: String,
        memory: MemoryContext?,
        history: List<ChatMessage>,
        question: String,
        tokenBudget: Int,
        senderPrefix: Boolean = true,
    ): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()

        // 1) 人设：永不丢弃
        if (personaPrompt.isNotBlank()) {
            result += ChatMessage(role = "system", content = personaPrompt)
        }

        val budget = tokenBudget.coerceAtLeast(TokenEstimator.estimate(question) + 16)

        // 2) 事实：有预算上限的保底内容
        var remaining =
            budget - TokenEstimator.estimate(result) - TokenEstimator.estimate(question)
        val factsCap = (budget * FACTS_BUDGET_RATIO).toInt()
        val facts = memory?.facts.orEmpty()
        if (facts.isNotEmpty() && remaining > 0) {
            val lines = mutableListOf<String>()
            var cost = 0
            for (fact in facts) {
                val line = "- ${fact.text}"
                val lineCost = TokenEstimator.estimate(line)
                if (cost + lineCost > factsCap && lines.size >= MIN_FACTS_KEPT) break
                if (cost + lineCost > factsCap && lines.isEmpty()) break // 一条都放不下
                lines += line
                cost += lineCost
            }
            if (lines.isNotEmpty()) {
                val header = "关于对方（${memory?.senderLabel ?: "对方"}）你记得的事："
                val block = header + "\n" + lines.joinToString("\n")
                result += ChatMessage(role = "system", content = block)
                remaining -= TokenEstimator.estimate(block)
            }
        }

        // 3) 群聊背景：动态内容，预算紧张时最先牺牲
        val digest = memory?.roomDigest.orEmpty()
        if (digest.isNotEmpty() && remaining > 0) {
            val digestCap = (budget * DIGEST_BUDGET_RATIO).toInt()
            val lines = mutableListOf<String>()
            var cost = 0
            for (msg in digest.takeLast(DIGEST_ENTRY_LIMIT)) {
                val who = msg.senderName?.takeIf { it.isNotBlank() }
                    ?: if (msg.role == "assistant") "机器人" else "某人"
                val body = msg.content.replace("\n", " ").take(DIGEST_ENTRY_MAX)
                val line = "[$who] $body"
                val lineCost = TokenEstimator.estimate(line)
                if (cost + lineCost > digestCap) break
                lines += line
                cost += lineCost
            }
            if (lines.isNotEmpty()) {
                val block = "以下是所在群/频道里最近的对话（可能包含其他人）：\n" +
                    lines.joinToString("\n")
                result += ChatMessage(role = "system", content = block)
                remaining -= TokenEstimator.estimate(block)
            }
        }

        // 4) 历史：成对丢弃（一问一答算一对），保证配对完整
        val kept = ArrayDeque<ChatMessage>()
        var i = history.size - 1
        while (i >= 1) {
            val cost =
                TokenEstimator.estimate(history[i - 1].content) +
                    TokenEstimator.estimate(history[i].content)
            if (cost > remaining) break
            kept.addFirst(history[i])
            kept.addFirst(history[i - 1])
            remaining -= cost
            i -= 2
        }
        if (i == 0 && TokenEstimator.estimate(history[0].content) <= remaining) {
            kept.addFirst(history[0])
        }
        result += kept

        // 5) 本轮问题：带展示名前缀，固定最后
        val label = memory?.senderLabel?.takeIf { it.isNotBlank() }
        val finalQuestion =
            if (senderPrefix && label != null) "[$label] $question" else question
        result += ChatMessage(role = "user", content = finalQuestion)

        return result
    }
}
