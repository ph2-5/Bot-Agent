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
 * 2.5. **群画像（system）**：机器人在本群的名字、本群有过交流的成员数。
 *    内容短且极少变化，位置同样在动态内容之前。
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

        /** 私人对话最多占用预算的比例 */
        private const val PERSONAL_DIGEST_BUDGET_RATIO = 0.2

        /** 私人对话注入条数上限 */
        private const val PERSONAL_DIGEST_LIMIT = 10

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

        // 1.4) 说话约束：紧接着人设，且内容稳定（改设置才会变），
        // 放在这里既能压住人设里的修辞，也不破坏后续前缀缓存。
        StyleRender.block(memory?.styleRules.orEmpty())?.let { text ->
            result += ChatMessage(role = "system", content = text)
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
                // 称呼声明并入事实块：改名后必须压倒历史里的旧称呼。
                // 模型很容易从历史对话里捡起旧名字（真实反馈过），
                // 所以每轮都明确"以此为准"。
                val who = memory?.senderLabel?.takeIf { it.isNotBlank() }
                val header =
                    buildString {
                        if (who != null) {
                            append("对方现在的名字是「$who」，以此为准；")
                            append("历史对话里出现过的旧称呼一律作废。\n")
                        }
                        append("关于对方")
                        if (who != null) append("（$who）")
                        append("你记得的事：")
                    }
                val block = header + "\n" + lines.joinToString("\n")
                result += ChatMessage(role = "system", content = block)
                remaining -= TokenEstimator.estimate(block)
            }
        }

        // 2.5) 群画像：机器人在本群的名字 + 见过多少成员。
        // 内容短且极少变化（仅新成员进群/改名时变），放在事实之后、
        // 动态群背景之前，把前缀缓存的失效控制在最小范围。
        val group = memory?.group
        if (group != null) {
            val lines = mutableListOf("你正在一个 QQ 群里聊天。")
            lines +=
                if (group.botName != null) {
                    "你在这个群的名字叫「${group.botName}」，大家会用这个名字称呼你。"
                } else {
                    "你在这个群还没有昵称，被问到名字时用官方名字即可。"
                }
            memory?.senderLabel?.takeIf { it.isNotBlank() }?.let { who ->
                lines += "正在跟你说话的这个人叫「$who」，以此为准；历史里的旧称呼作废。"
            }
            lines += "这个群里跟你有过交流的成员约 ${group.seenCount} 人。"
            if (group.people.isNotEmpty()) {
                lines += "本群你认识的人（每人一行；别把一个人的事算到另一个人头上）："
                group.people.take(6).forEach { person ->
                    val facts = person.facts.filter { it.isNotBlank() }.joinToString("、")
                    lines +=
                        "- ${person.displayName}" +
                            if (facts.isNotBlank()) "：$facts" else ""
                }
                lines += "被问到群里有哪些人时只报上面这些名字，不要编造；" +
                    "也别把甲的喜好、经历说成乙的。"
            } else if (group.knownMembers.isNotEmpty()) {
                lines +=
                    "你认识的人：" + group.knownMembers.joinToString("、") +
                        if (group.unnamedCount > 0) {
                            "（另有 ${group.unnamedCount} 位没告诉你名字）"
                        } else {
                            ""
                        }
                lines += "被问到群里有哪些人时只报上面这些名字，不要编造。"
            } else {
                lines += "你还不知道任何人的名字，被问到时如实说明，不要编造群成员姓名。"
            }
            if (!group.proactiveAllowed) {
                lines += "本群管理员关闭了主动消息，不要发送与当前对话无关的内容。"
            }
            // 群聊常识：这是"把不是骂自己的话当成骂自己"的直接解药。
            // 人设自由发挥，但指代规则必须写死，否则模型会把自己代入第三方。
            lines += "群聊常识：只有确实冲你来的消息，「你」才指你；别人互相玩梗、拌嘴时，" +
                "里面的「你」指的是对方（例如「我是你爹陈泽」是在调侃另一位群友，不是对你说的）；" +
                "「他/她/这个人/那人/楼上/某人」通常指第三方，不要往自己身上套。"
            group.botName?.takeIf { it.isNotBlank() }?.let { name ->
                lines += "「$name」只指你自己：别人说「我不是$name」是在澄清身份，不是叫你；" +
                    "也不要把别人叫作 $name。"
            }
            // 玩梗不是事实：群里常见的「我是你爹」被当真后会演成认亲、站队，
            // 这是审计里"回得莫名其妙"的来源之一。
            lines += "别人玩「我是你爹」这类梗时只当玩笑：不要真的认亲，也不要自称是谁的爹或儿子。"
            lines += "你只能看到 @ 你的消息（以及群主授权后的普通消息），看不到的部分不要编造；" +
                "对方指代不明时先问清楚，例如「说的是谁啊」。"
            val block = lines.joinToString("\n")
            result += ChatMessage(role = "system", content = block)
            remaining -= TokenEstimator.estimate(block)
        }

        // 2.6) 人设设定：从角色卡里抽出来的「认识的人 / 雷点 / 特殊说法」。
        // 与人设正文分开存，改人设时不会把这些长期设定一起改没。
        memory?.social?.lore?.let { lore ->
            LoreRender.block(lore)?.let { text ->
                result += ChatMessage(role = "system", content = text)
                remaining -= TokenEstimator.estimate(text)
            }
        }

        // 2.7) 被引用的原文：指代不明时最有效的一条线索
        memory?.quoted?.let { quoted ->
            val who = quoted.senderName?.takeIf { it.isNotBlank() }
            val block =
                buildString {
                    append("对方正在回复")
                    if (who != null) append("「$who」")
                    append("的这条消息：")
                    append(quoted.content.replace("\n", " ").take(120))
                    append("\n判断「他/这个人/楼上」指的是谁时，优先看这条原文。")
                }
            result += ChatMessage(role = "system", content = block)
            remaining -= TokenEstimator.estimate(block)
        }

        // 2.8) 被指名冒犯：给人设一个"反应许可"。
        // 不注入这条的话，模型会继续按"旁听者"口吻装没看见，
        // 脾气不好的人设就完全立不住。
        memory?.insult?.let { insult ->
            val who = insult.byWhom?.takeIf { it.isNotBlank() } ?: "对方"
            val known =
                memory.facts.take(3).joinToString("、") { it.text }
                    .takeIf { it.isNotBlank() }
            val block =
                buildString {
                    appendLine("刚刚「$who」当众骂了你：「${insult.quote.take(80)}」。")
                    append("这不是旁听，是冲你来的，必须按你的人设做出反应：")
                    append("脾气暴躁的人设可以直接怼回去，阴阳怪气的人设可以冷嘲，")
                    append("温和的人设也可以冷处理或点破对方。")
                    if (known != null) {
                        append("你对「$who」的了解：$known。")
                        append("熟人开玩笑和陌生人挑衅，反应程度应当不一样。")
                    }
                    append("底线：不攻击家人、不威胁、不连续对骂，一两句到位即可。")
                }
            result += ChatMessage(role = "system", content = block)
            remaining -= TokenEstimator.estimate(block)
        }

        // 2.85) 关系与心情：决定「愿不愿意开口」，不决定「说话的态度」。
        // 放在冒犯之后、群背景之前：它比背景稳定，也不该被背景挤出预算。
        memory?.social?.let { social ->
            val lines = mutableListOf<String>()
            social.relation?.let { lines += RelationPolicy.describe(it) }
            social.mood?.let { mood -> MoodPolicy.describe(mood)?.let { lines += it } }
            social.lastThought?.let { thought ->
                lines +=
                    "你上次回他时，没说出口的是：「$thought」。" +
                        "它可以影响你这次的态度，但别把它当成事实讲出来，也别翻旧账。"
            }
            if (lines.isNotEmpty()) {
                val text = lines.joinToString("\n")
                result += ChatMessage(role = "system", content = text)
                remaining -= TokenEstimator.estimate(text)
            }
        }

        // 2.87) 群内梗：懂就行，别硬用。
        // 单独立块而不是塞进人设——它会随群变化，而人设要保持前缀缓存稳定。
        memory?.social?.slang?.let { slang ->
            SlangRender.block(slang)?.let { text ->
                result += ChatMessage(role = "system", content = text)
                remaining -= TokenEstimator.estimate(text)
            }
        }

        // 2.89) 表情包：图是配菜不是主食。
        // 与梗同样的理由：候选图随群变化，不进人设。
        // 列表为空时整块不注入——没有图可发，就别让模型知道"能发图"这件事。
        memory?.social?.memes?.takeIf { it.isNotEmpty() }?.let { urls ->
            MemeRender.block(urls)?.let { text ->
                result += ChatMessage(role = "system", content = text)
                remaining -= TokenEstimator.estimate(text)
            }
        }

        // 3.0) 你和当前这个人之间的往返（含机器人自己的回复）。
        // 这是「多人记忆」的关键一层：群背景是全群混在一起的，
        // 这里只放两人之间的对话，模型才不会张冠李戴。
        val personal = memory?.personalDigest.orEmpty()
        if (personal.isNotEmpty() && remaining > 0) {
            val who = memory?.senderLabel?.takeIf { it.isNotBlank() } ?: "对方"
            val cap = (budget * PERSONAL_DIGEST_BUDGET_RATIO).toInt()
            val lines = mutableListOf<String>()
            var cost = 0
            for (msg in personal.takeLast(PERSONAL_DIGEST_LIMIT)) {
                val speaker = if (msg.role == "assistant") "你" else who
                val body = msg.content.replace("\n", " ").take(DIGEST_ENTRY_MAX)
                val line = "[$speaker] $body"
                val lineCost = TokenEstimator.estimate(line)
                if (cost + lineCost > cap && lines.isNotEmpty()) break
                if (cost + lineCost > cap && lines.isEmpty()) break
                lines += line
                cost += lineCost
            }
            if (lines.isNotEmpty()) {
                val block =
                    "你和「$who」之前的对话（只属于你们两个人；" +
                        "这里没有别人的话，别把群背景里其他人的事算到他头上）：\n" +
                        lines.joinToString("\n")
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
