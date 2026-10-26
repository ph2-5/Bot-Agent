package com.qbot.core.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 规则打分明细权重（用户可调）。
 *
 * 每个字段对应 [ChitchatPolicy] 里一条评分规则的贡献值，
 * 默认值即出厂行为——全部保持默认时与硬编码时代完全一致。
 * 负值表示扣分。用户在维护端把它们调大调小，
 * 本质上就是在调「机器人多爱接话」而不必改代码。
 */
data class ScoreWeights(
    /** 长度适中（2~60 字）加分 */
    val lengthScore: Int = 10,
    /** 疑问句加分：最强的「需要有人接」信号 */
    val questionScore: Int = 25,
    /** 单字/表情消息扣分（负值） */
    val shortPenalty: Int = -20,
    /** @ 了别人扣分：大概率是两人对话，不该插嘴（负值） */
    val mentionPenalty: Int = -20,
    /** 话题延续（与最近消息共享词组）加分 */
    val topicScore: Int = 15,
    /** 有人在回应机器人（上一条是它自己说的）加分 */
    val respondBotScore: Int = 15,
    /** 冷场（群里几乎没有消息）加分 */
    val quietScore: Int = 10,
    /** 同一人连续发言（刷屏/抢话）扣分（负值） */
    val spamPenalty: Int = -10,
)

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
    /**
     * 基础冷却时间（毫秒）；下限 1 秒，实际冷却按群活跃度自适应缩放。
     *
     * 默认 3 秒而不是 45 秒：AI 判断开启时，"该不该接"已经由模型决定，
     * 冷却只该防"同一秒连发两条"。旧默认 45 秒是规则时代的产物——
     * 那时没有模型判断，冷却同时兼任节奏与成本闸门。
     */
    val baseCooldownMillis: Long = 3_000L,
    /** 每群每小时最多接话次数；**0 表示不限制** */
    val maxPerHour: Int = 60,
    /** 每群每天最多接话次数；**0 表示不限制** */
    val maxPerDay: Int = 400,
    /** 通过预筛后是否再让 AI 判断一次「该不该插话」。
     *
     * 开启后为 **AI 主导**：规则层只挡明显噪音（单字、@别人、刷屏等强负分），
     * 其余每条消息都交给模型判断，不再按分数与概率卡——语境理解交给 AI。
     * 关闭时为规则主导：分数达阈值 → 按概率抽样 → 直接接话，零模型调用。
     */
    val aiJudge: Boolean = true,
    /** 通过预筛后的参与概率（0~1），先随机筛掉一部分再问 AI，进一步省成本 */
    val probability: Double = 0.35,
    /** 有人叫机器人的名字时必须接话 */
    val answerOnName: Boolean = true,
    /** 规则打分阈值：低于它就放弃，不进入 AI 判断 */
    val minScore: Int = 15,
    /** 打分明细权重：各情形加/扣多少分，全部可调 */
    val scoreWeights: ScoreWeights = ScoreWeights(),
    /**
     * 用户自定义的 AI 接话规则（自然语言，逐条注入判断提示词）。
     *
     * 例如「群里讨论工作时不要插话」「有人求助时优先接」。
     * 优先级高于内置的通用 YES/NO 规则。
     */
    val chimeRules: List<String> = emptyList(),
    /**
     * 用户自定义的侮辱词（追加在内置词表之后）。
     *
     * 语言的黑话与谐音层出不穷，内置表不可能穷尽——
     * 用户把自己群里实际出现的词加进来，被指名冒犯时的反应才准。
     */
    val insultExtraWords: List<String> = emptyList(),
    /**
     * 经验库容量（条）。
     *
     * 只影响存储与相似去重池，不改变每次注入的条数——长期使用的群
     * 会不断积累经验，40 条很快不够；上限 200 防止无限膨胀。
     */
    val maxLessons: Int = 80,
    /** 每次判断注入几条经验（本群优先）。这一项才是提示词成本。 */
    val maxInjectedLessons: Int = 8,
    /**
     * 判断时携带多少条最近群消息。
     *
     * 过去写死 8 条、每条砍到 60 字：快群几秒钟就刷满，模型看到的是断章；
     * 也看不到消息间隔，判断不出「冷场」还是「两人快速私聊」。
     * 现在可调，并在提示词里带上相对时间。
     */
    val judgeContextSize: Int = 12,
    /** 判断上下文条数的可选范围 */
    val MIN_JUDGE_CONTEXT: Int = 6,
    val MAX_JUDGE_CONTEXT: Int = 24,
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

    /**
     * 只是「还没轮到」——等一会儿重新判定，不是放弃。
     *
     * 冷却的本质是节奏而不是拒绝：群里刚说完一句，两秒后再接完全自然。
     * 但超过 [MAX_DEFER_MILLIS] 的冷却（比如默认几十秒）等到了话题也过去了，
     * 由调用方转成 Skip。
     */
    data class Defer(val reason: String, val delayMillis: Long) : ChitchatGate() {
        /** 等待期间又来了新消息时，用新消息替换旧内容（覆盖式，不排队） */
        fun capped(maxMillis: Long): Defer = copy(delayMillis = delayMillis.coerceAtMost(maxMillis))
    }

    /** 放弃 */
    data class Skip(val reason: String) : ChitchatGate()

    companion object {
        /** 最长等多久：超过这个时长，等到也没有意义了 */
        const val MAX_DEFER_MILLIS = 10_000L
    }
}

/**
 * 人设性格偏置——评分体系与人设（系统提示词）的关联点。
 *
 * 参考 HUMA（arXiv 2511.17315）的策略评分与 Big Five 人格调制：
 * 外向/话痨的人格应该更愿意接话，高冷/寡言的人格应该更挑剔。
 * 实现上不改人设本身，只微调漏斗参数：
 * - [scoreDelta] 加在打分阈值上（负值 = 更容易过筛）；
 * - [probabilityFactor] 乘在抽样概率上。
 *
 * 由 [Companion.personaBiasFor] 从人设提示词的关键词自动识别，
 * 识别不出就保持中性，行为与旧版一致。
 */
enum class PersonaBias(
    val scoreDelta: Int,
    val probabilityFactor: Double,
) {
    /** 话痨/外向：更容易接话 */
    TALKATIVE(-10, 1.4),

    /** 中性：与旧版一致 */
    NEUTRAL(0, 1.0),

    /** 高冷/寡言：更挑剔 */
    RESERVED(10, 0.7),
    ;

    companion object {
        private val TALKATIVE_WORDS =
            listOf("话痨", "话多", "健谈", "外向", "热情", "爱聊", "活泼", "自来熟")
        private val RESERVED_WORDS =
            listOf("高冷", "内向", "沉默", "寡言", "冷淡", "高傲", "高岭之花", "惜字如金", "不爱说话")

        /** 从人设提示词推断性格偏置。 */
        fun personaBiasFor(personaPrompt: String?): PersonaBias {
            val prompt = personaPrompt?.takeIf { it.isNotBlank() } ?: return NEUTRAL
            val talkative = TALKATIVE_WORDS.any { prompt.contains(it) }
            val reserved = RESERVED_WORDS.any { prompt.contains(it) }
            // 两种都命中时保持中性，不猜
            return when {
                talkative && !reserved -> TALKATIVE
                reserved && !talkative -> RESERVED
                else -> NEUTRAL
            }
        }
    }
}

/**
 * 插话决策引擎。
 *
 * 三条规则来源：
 * 1. **频率**：基础冷却 + 每时/每日配额。热群冷却自动放大（别在大家聊得正欢时插嘴），
 *    冷场时缩小（该有人接一句的时候更主动）——这就是"看聊天频率"。
 * 2. **内容**：疑问句、长度适中加分；@别人、单字/表情、刚说过话减分——"看聊天内容"。
 * 3. **身份**：被叫名字必答（唯一能绕过冷却的路径，防止群友觉得"叫了不理"）；
 *    人设性格（[PersonaBias]）微调阈值与概率，让人设真正影响"要不要说话"。
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
     * @param insulted 这句话是否在指名冒犯机器人（由 [InsultDetector] 判定）
     * @param talkingToOthers 是否 @ 了别的群友
     */
    suspend fun gate(
        groupId: String,
        text: String,
        botNames: Collection<String>,
        config: ChitchatConfig,
        insulted: Boolean = false,
        talkingToOthers: Boolean = false,
        personaBias: PersonaBias = PersonaBias.NEUTRAL,
        recent: List<RoomMessage> = emptyList(),
    ): ChitchatGate {
        if (!config.enabled) return ChitchatGate.Skip("参与开关关闭")
        if (text.isBlank()) return ChitchatGate.Skip("空消息")
        // @ 了别人：这是两个人之间的对话，再热闹也不接
        if (talkingToOthers) return ChitchatGate.Skip("@ 了其他群友")

        val now = clock()

        // 名字提及不受冷却限制，但仍有最小间隔与配额——
        // 否则群名恰好叫「小白」时会被刷屏。
        // 用共享匹配器：完整名与短昵称（名字后缀）都能认出来；
        // called 还会排除「我不是阿远」「关你阿远什么事」这类语境。
        val named = config.answerOnName && BotNameMatcher.called(text, botNames)

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

            // 对方已经明确划界（「关你什么事」「没跟你说话」）：
            // 名字出现了也不接。这类句子里名字常被夹在中间，只按字面匹配
            // 会被判成"叫了名字"或"被指名冒犯"，于是越被推开越要插话——
            // 所以划界的优先级高于冒犯回应。
            if (BotNameMatcher.isRefusal(text)) {
                return ChitchatGate.Skip("对方明确不需要你插话")
            }

            // 被指名冒犯是次高优先级：这是"人设是否可信"的问题，
            // 不能交给概率和 AI 判断。仍保留最小间隔，避免对骂刷屏。
            if (insulted) {
                val waited = now - state.lastSpokeAt
                if (waited < NAME_MIN_GAP_MILLIS) {
                    // 回嘴晚两秒不影响效果——等，而不是丢
                    return ChitchatGate.Defer("刚说过话，冒犯回应稍后", NAME_MIN_GAP_MILLIS - waited)
                }
                return ChitchatGate.Forced("被指名冒犯")
            }

            if (named) {
                val waited = now - state.lastSpokeAt
                if (waited < NAME_MIN_GAP_MILLIS) {
                    // 「叫了名字必答」是产品承诺，绝不能因为 5 秒间隔就装死
                    return ChitchatGate.Defer("刚说过话，名字问候稍后", NAME_MIN_GAP_MILLIS - waited)
                }
                return ChitchatGate.Forced("有人叫了机器人的名字")
            }

            val cooldown = adaptiveCooldown(state, config, now)
            val waited = now - state.lastSpokeAt
            if (waited < cooldown) {
                // 冷却 = 等一等，不是不说话；等太久（默认几十秒）则由调用方转成跳过
                return ChitchatGate.Defer("冷却中（${cooldown / 1000} 秒）", cooldown - waited)
            }

            // AI 主导模式：规则层只负责挡"明显噪音"（单字、@别人、刷屏等
            // 强负分消息），其余每条都作为候选交给 AI 判断——
            // 分数不再当门槛，语境理解这种事交给模型。
            val score = score(text, recent, state, config.scoreWeights)
            if (config.aiJudge) {
                if (score < 0) {
                    return ChitchatGate.Skip("明显插不上话（噪音）")
                }
                return ChitchatGate.Candidate(score)
            }

            // 规则主导模式：分数达到阈值才进入候选（不调用 AI 判断）
            // 阈值随人设性格浮动：话痨放宽、高冷收紧
            val threshold = config.minScore + personaBias.scoreDelta
            if (score < threshold) {
                return ChitchatGate.Skip("规则打分 $score < $threshold")
            }
            return ChitchatGate.Candidate(score)
        }
    }

    /** 当前自适应冷却时长（测试与日志用）。 */
    /**
     * 距机器人上次在本群发言过了多久（毫秒）；没说过返回 null。
     *
     * 判断"现在该不该说话"时，"我刚说过"和"我半天没说话了"是两种完全不同的局面。
     */
    suspend fun lastSpokeAgoMillis(groupId: String): Long? =
        mutex.withLock {
            val state = states[groupId] ?: return@withLock null
            if (state.lastSpokeAt <= 0L) null else (clock() - state.lastSpokeAt).coerceAtLeast(0L)
        }

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
     * 内容打分：本条消息本身 + 上下文信号。
     *
     * 分数只决定"要不要进入下一级判断"，不代表一定回复。
     *
     * 各情形分值来自 [ScoreWeights]（用户可调），默认值即出厂行为。
     * 频率控制已由冷却与配额负责，这里不再叠加"刚说过话"的惩罚——
     * 否则冷却刚结束又会被扣到阈值以下，等于冷却时间形同虚设。
     *
     * 上下文部分是"要不要插嘴"最真实的依据——同样的句子，
     * 在话题延续时接话很自然，在两人对聊中插嘴很突兀：
     * - **回应机器人**（上一条是它自己说的）+15：不接很失礼；
     * - **话题延续**（与最近消息共享词组）+15：话题没断，接话自然；
     * - **冷场**（最近几乎没消息）+10：该有人开口了；
     * - **同一人连续刷屏** −10：别人在抢话筒，不缺你一个。
     */
    private fun score(
        text: String,
        recent: List<RoomMessage>,
        state: GroupState,
        weights: ScoreWeights = ScoreWeights(),
    ): Int {
        var score = 0
        if (text.length in 2..60) score += weights.lengthScore
        // 疑问句是最强的"需要有人接"信号
        if (QUESTION_WORDS.any { text.contains(it) }) score += weights.questionScore
        if (text.length <= 3) score += weights.shortPenalty
        // @ 了别人：大概率是两个人之间的对话，强负分压到阈值以下
        if (text.contains("<@!") || text.startsWith("@")) score += weights.mentionPenalty

        // ---- 上下文信号 ----
        val prev = recent.lastOrNull()
        if (prev != null) {
            // 有人在回应机器人刚才的话
            if (prev.role == "assistant") score += weights.respondBotScore
            // 同一人连续发言（刷屏/抢话）：上一条与这一条来自同一个人
            val prevPrev = recent.getOrNull(recent.lastIndex - 1)
            if (prevPrev != null &&
                !prev.senderId.isNullOrBlank() &&
                prev.senderId == prevPrev.senderId
            ) {
                score += weights.spamPenalty
            }
        }
        if (sharesTopic(text, recent.takeLast(6))) score += weights.topicScore
        // 冷场：本条之前群里几乎没有消息
        if (recent.size <= 1) score += weights.quietScore
        return score
    }

    /**
     * 话题延续检测：新消息与最近消息共享 2 字词组。
     *
     * 中文没有分词，这里用最便宜的 bigram 重叠近似：新消息的任意
     * 2 字连续组合出现在最近 6 条消息里就算共享。会有少量误报
     * （"的吗呢"这类虚词），但方向正确——宁可多交给下一级判断，
     * 也不要在话题正热时沉默。
     */
    private fun sharesTopic(
        text: String,
        recent: List<RoomMessage>,
    ): Boolean {
        if (text.length < 4 || recent.isEmpty()) return false
        val recentGrams = HashSet<String>()
        recent.forEach { msg ->
            val content = msg.content
            if (content.length >= 2) {
                for (i in 0..content.length - 2) {
                    recentGrams.add(content.substring(i, i + 2))
                }
            }
        }
        for (i in 0..text.length - 2) {
            val gram = text.substring(i, i + 2)
            // 跳过纯符号/空白组合
            if (gram.any { it.isLetterOrDigit() } && gram in recentGrams) return true
        }
        return false
    }
}

/** 供维护端消息查看器展示的一句话结论。 */
fun ChitchatGate.describe(): String =
    when (this) {
        is ChitchatGate.Forced -> "接话：$reason"
        is ChitchatGate.Candidate -> "候选（规则 $score 分）"
        is ChitchatGate.Defer -> "等 ${delayMillis / 1000} 秒再接：$reason"
        is ChitchatGate.Skip -> "不接：$reason"
    }
