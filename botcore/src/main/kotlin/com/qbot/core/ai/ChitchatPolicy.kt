package com.qbot.core.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 群聊参与（插话）配置。
 *
 * 这是「机器人像群友一样自己决定要不要接话」的开关组。
 * 默认关闭：只有群主在 QQ 客户端为机器人打开「接收所有消息」后，
 * 这条链路才会收到非 @ 消息；没开时开着它也没有任何副作用。
 */
data class ChitchatConfig(
    /** 总开关 */
    val enabled: Boolean = false,
    /** 基础冷却时间（毫秒）；下限 1 秒，实际冷却按群活跃度自适应缩放 */
    val baseCooldownMillis: Long = 45_000L,
    /** 每群每小时最多接话次数；**0 表示不限制** */
    val maxPerHour: Int = 60,
    /** 每群每天最多接话次数；**0 表示不限制** */
    val maxPerDay: Int = 400,
    /** 通过预筛后是否再让 AI 判断一次「该不该插话」 */
    val aiJudge: Boolean = true,
    /** 通过预筛后的参与概率（0~1），先随机筛掉一部分再问 AI，进一步省成本 */
    val probability: Double = 0.35,
    /** 有人叫机器人的名字时必须接话 */
    val answerOnName: Boolean = true,
    /** 规则打分阈值：低于它就放弃，不进入 AI 判断 */
    val minScore: Int = 25,
)

/**
 * 预筛结果——决策漏斗的前两级（第三级是 AI 判断）。
 *
 * 漏斗设计的原因：活跃群一天几千条消息，每条都问模型既贵又慢；
 * 先用纯本地规则砍掉 95% 以上，只把少数「像是需要人接话」的候选送进模型。
 */
sealed class ChitchatGate {
    /** 有人明确叫了机器人的名字：直接接话（但仍受频率与配额保护） */
    data class Forced(val reason: String) : ChitchatGate()

    /** 值得进一步判断的候选 */
    data class Candidate(val score: Int) : ChitchatGate()

    /** 放弃 */
    data class Skip(val reason: String) : ChitchatGate()
}

/**
 * 插话决策引擎。
 *
 * 三条规则来源：
 * 1. **频率**：基础冷却 + 每时/每日配额。热群冷却自动放大（别在大家聊得正欢时插嘴），
 *    冷场时缩小（该有人接一句的时候更主动）——这就是"看聊天频率"。
 * 2. **内容**：疑问句、长度适中加分；@别人、单字/表情、刚说过话减分——"看聊天内容"。
 * 3. **身份**：被叫名字必答（唯一能绕过冷却的路径，防止群友觉得"叫了不理"）。
 *
 * 全部状态放内存：重启清零可以接受（宁可少说几句，也不要在数据库里堆计数）。
 */
class ChitchatPolicy(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** 活跃度统计窗口 */
        private const val RATE_WINDOW_MILLIS = 5 * 60_000L
        private const val HOUR_MILLIS = 60 * 60_000L
        private const val DAY_MILLIS = 24 * 60 * 60_000L

        /** 被叫名字时的最小间隔，避免名字是常用词时刷屏 */
        private const val NAME_MIN_GAP_MILLIS = 5_000L

        /** 冷却下限：配置再小也不低于 1 秒，避免每条都触发 */
        private const val MIN_COOLDOWN_MILLIS = 1_000L

        /** 最多跟踪多少个群的状态，防止长期运行时无限增长 */
        private const val MAX_TRACKED_GROUPS = 128

        private val QUESTION_WORDS =
            listOf(
                "吗", "呢", "怎么", "为什么", "为啥", "谁", "哪", "多少",
                "是不是", "能不能", "要不要", "有没有", "?", "？",
            )
    }

    private class GroupState {
        var lastSpokeAt: Long = 0L
        val speakTimes = ArrayDeque<Long>()
        val recentMessages = ArrayDeque<Long>()
    }

    private val mutex = Mutex()
    private val states = LinkedHashMap<String, GroupState>()

    /** 记录一条群消息（用于计算活跃度）。不回复也应调用。 */
    suspend fun observe(groupId: String) {
        val now = clock()
        mutex.withLock {
            val state = stateOf(groupId)
            state.recentMessages.addLast(now)
            // 只留统计窗口内的
            while (state.recentMessages.isNotEmpty() &&
                state.recentMessages.first() < now - RATE_WINDOW_MILLIS
            ) {
                state.recentMessages.removeFirst()
            }
        }
    }

    /** 记录一次"我说了话"。 */
    suspend fun markSpoke(groupId: String) {
        val now = clock()
        mutex.withLock {
            val state = stateOf(groupId)
            state.lastSpokeAt = now
            state.speakTimes.addLast(now)
            while (state.speakTimes.isNotEmpty() && state.speakTimes.first() < now - DAY_MILLIS) {
                state.speakTimes.removeFirst()
            }
        }
    }

    /**
     * 预筛：判断这条消息值不值得让机器人接。
     *
     * @param botNames 机器人在本群的称呼（群名片优先，其次官方名）
     */
    suspend fun gate(
        groupId: String,
        text: String,
        botNames: Collection<String>,
        config: ChitchatConfig,
    ): ChitchatGate {
        if (!config.enabled) return ChitchatGate.Skip("参与开关关闭")
        if (text.isBlank()) return ChitchatGate.Skip("空消息")

        val now = clock()

        // 名字提及不受冷却限制，但仍有最小间隔与配额——
        // 否则群名恰好叫「小白」时会被刷屏。
        val named = config.answerOnName && botNames.any { it.isNotBlank() && text.contains(it) }

        mutex.withLock {
            val state = stateOf(groupId)
            pruneQuota(state, now)

            // 0 = 不限制。上限为 0 时直接跳过检查，方便"只想靠冷却控制节奏"的用法。
            if (config.maxPerHour > 0) {
                val hourCount = state.speakTimes.count { it >= now - HOUR_MILLIS }
                if (hourCount >= config.maxPerHour) {
                    return ChitchatGate.Skip("本小时已达上限 ${config.maxPerHour} 次")
                }
            }
            if (config.maxPerDay > 0 && state.speakTimes.size >= config.maxPerDay) {
                return ChitchatGate.Skip("本日已达上限 ${config.maxPerDay} 次")
            }

            if (named) {
                if (now - state.lastSpokeAt < NAME_MIN_GAP_MILLIS) {
                    return ChitchatGate.Skip("刚说过话，名字问候冷却中")
                }
                return ChitchatGate.Forced("有人叫了机器人的名字")
            }

            val cooldown = adaptiveCooldown(state, config, now)
            if (now - state.lastSpokeAt < cooldown) {
                return ChitchatGate.Skip("冷却中（${cooldown / 1000} 秒）")
            }

            val score = score(text)
            if (score < config.minScore) {
                return ChitchatGate.Skip("规则打分 $score < ${config.minScore}")
            }
            return ChitchatGate.Candidate(score)
        }
    }

    /** 当前自适应冷却时长（测试与日志用）。 */
    suspend fun cooldownFor(
        groupId: String,
        config: ChitchatConfig,
    ): Long {
        val now = clock()
        return mutex.withLock { adaptiveCooldown(stateOf(groupId), config, now) }
    }

    // ---------- 内部 ----------

    private fun stateOf(groupId: String): GroupState {
        states[groupId]?.let { return it }
        if (states.size >= MAX_TRACKED_GROUPS) {
            // 淘汰最早插入的群（LinkedHashMap 保序）
            states.remove(states.keys.first())
        }
        return GroupState().also { states[groupId] = it }
    }

    private fun pruneQuota(
        state: GroupState,
        now: Long,
    ) {
        while (state.speakTimes.isNotEmpty() && state.speakTimes.first() < now - DAY_MILLIS) {
            state.speakTimes.removeFirst()
        }
        while (state.recentMessages.isNotEmpty() &&
            state.recentMessages.first() < now - RATE_WINDOW_MILLIS
        ) {
            state.recentMessages.removeFirst()
        }
    }

    /**
     * 活跃度自适应冷却：
     * - 5 分钟内 ≥12 条（聊得正热）→ 冷却 ×2，少插嘴；
     * - 5 分钟内 ≤2 条（冷场）→ 冷却 ×0.6，更愿意接话；
     * - 其余不变。
     *
     * 结果不低于 1 秒：把基础冷却调到 1 秒的用户想要的是"几乎每条都能评估"，
     * 但 0.6 倍会算出 0，等于绕过了"每条只评估一次"的语义。
     */
    private fun adaptiveCooldown(
        state: GroupState,
        config: ChitchatConfig,
        now: Long,
    ): Long {
        val recent = state.recentMessages.count { it >= now - RATE_WINDOW_MILLIS }
        val factor =
            when {
                recent >= 12 -> 2.0
                recent <= 2 -> 0.6
                else -> 1.0
            }
        return (config.baseCooldownMillis * factor).toLong().coerceAtLeast(MIN_COOLDOWN_MILLIS)
    }

    /**
     * 内容打分。分数只决定"要不要进入下一级判断"，不代表一定回复。
     *
     * 频率控制已由冷却与配额负责，这里不再叠加"刚说过话"的惩罚——
     * 否则冷却刚结束又会被扣到阈值以下，等于冷却时间形同虚设。
     */
    private fun score(text: String): Int {
        var score = 0
        if (text.length in 2..60) score += 10
        // 疑问句是最强的"需要有人接"信号：普通提问应正好达到默认阈值
        if (QUESTION_WORDS.any { text.contains(it) }) score += 30
        if (text.length <= 3) score -= 20
        // @ 了别人：大概率是两个人之间的对话，强负分压到阈值以下
        if (text.contains("<@!") || text.startsWith("@")) score -= 20
        return score
    }
}
