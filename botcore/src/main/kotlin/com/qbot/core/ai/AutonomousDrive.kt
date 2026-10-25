package com.qbot.core.ai

import kotlin.math.max
import kotlin.math.min

/**
 * 自主档位。
 *
 * 之所以是三档而不是一个开关：「被叫才说」和「自己想插一句」本来就是两种行为，
 * 用户想要的往往是中间态——保留插话（chime），但不要它自己找话题。
 */
enum class AutonomyMode {
    /** 纯响应：只有被 @ / 叫名字 / 明确对话才回；chime 与 drive 全关 */
    OFF,

    /** 被动参与（默认）：被叫必回 + chime 轻度插话；drive 不跑 */
    PASSIVE,

    /** 自主：PASSIVE + drive 驱动的自主开口 */
    ACTIVE,
    ;

    companion object {
        /** 解析设置里的字符串；任何非法值都回落到默认档（PASSIVE）。 */
        fun parse(raw: String?): AutonomyMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: PASSIVE
    }
}

/** ACTIVE 档的开口阈值档位（高级项，供用户微调）。 */
enum class DriveSensitivity(
    val threshold: Double,
) {
    /** 挑剔：很少开口 */
    LOW(0.75),

    /** 默认 */
    MEDIUM(0.62),

    /** 活跃：容易被话题勾住 */
    HIGH(0.45),
    ;

    companion object {
        fun parse(raw: String?): DriveSensitivity =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: MEDIUM

        fun fromThreshold(value: Double): DriveSensitivity =
            entries.minByOrNull { kotlin.math.abs(it.threshold - value) } ?: MEDIUM
    }
}

/**
 * 自主开口配置。
 *
 * [quietHours] 用 `HH:mm-HH:mm` 表示，支持跨零点（23:00-08:00 是最常见的写法）。
 */
data class DriveConfig(
    val mode: AutonomyMode = AutonomyMode.PASSIVE,
    /** 开口阈值；默认取 [DriveSensitivity.MEDIUM] */
    val threshold: Double = DriveSensitivity.MEDIUM.threshold,
    /** 安静时段：此区间内只 HOLD */
    val quietHours: String = DEFAULT_QUIET_HOURS,
    /** ACTIVE 档每群每小时最多开口次数（0 表示不限制） */
    val maxSpeaksPerHour: Int = 2,
) {
    companion object {
        const val DEFAULT_QUIET_HOURS: String = "23:00-08:00"
    }
}

/**
 * 自主开口的输入状态。
 *
 * 全部由调用方从已有数据算好：mood 来自 `bot_mood`，closeness 来自 `affinities`，
 * interest 来自话题与人格/记忆的相似度，其余来自群消息与决策表。
 * 本类刻意不碰存储，保证纯函数可单测。
 */
data class DriveState(
    val mood: Int,
    /** 0..1：话题与人格/记忆的相关度 */
    val interest: Double,
    /** 0..1：与最近说话人的关系 */
    val closeness: Double,
    /** 距上次在本群开口多少分钟；负数表示还没说过 */
    val minutesSinceLastSpoke: Int,
    /** 最近 10 分钟的群消息数 */
    val recentMessageCount: Int,
    /** 最近被无视的次数 */
    val ignoredCount: Int,
    /** 0..23 */
    val hourOfDay: Int,
    /** 有没有可用素材（最近的对话 / 没说出口的内心戏） */
    val hasMaterial: Boolean,
)

enum class DriveOutcome { SPEAK, HOLD }

data class DriveDecision(
    val outcome: DriveOutcome,
    val score: Double,
    /** 供日志与调试页展示的一句话结论 */
    val reason: String,
) {
    val shouldSpeak: Boolean get() = outcome == DriveOutcome.SPEAK
}

/**
 * 第一道闸门：想不想说（纯本地，0 API 成本）。
 *
 * 打分模型参考 ChitchatPolicy 的风格：几个乘性因子 + 一个减性的打扰成本。
 * 乘性因子各自把「完全不想说」压到 0.5~0.6 的系数，只有全部向好时才可能过阈值；
 * 打扰成本负责把「聊得正热 / 刚被无视 / 刚说完」这类局面直接压下去。
 *
 * 关键产品约束：PASSIVE 与 OFF 档下第一道闸门**不计算**，不是算了再丢弃——
 * 否则每来一条群消息都要白跑一次。
 */
object AutonomousDrive {
    /** 基础分：所有因子中性（≈1.0）时略低于默认阈值 */
    private const val BASE = 0.55

    /** 同一话题连续忍住多少次后进入冷却 */
    const val HELD_COOLDOWN_THRESHOLD = 3

    /** 话题冷却时长：30 分钟 */
    const val HELD_COOLDOWN_MILLIS = 30 * 60_000L

    /** 话题指纹最多取几个词 */
    private const val FINGERPRINT_TERMS = 4

    fun evaluate(
        state: DriveState,
        config: DriveConfig,
        nowMillis: Long,
    ): DriveDecision {
        // 档位先行：不跑就是真的不跑
        if (config.mode == AutonomyMode.OFF) return hold(0.0, "OFF 档：不自主开口")
        if (config.mode != AutonomyMode.ACTIVE) return hold(0.0, "PASSIVE 档：只参与不主动")
        if (!state.hasMaterial) return hold(0.0, "没有可接的素材")
        if (inQuietHours(state.hourOfDay, config.quietHours)) {
            return hold(0.0, "安静时段（${config.quietHours}）")
        }

        val interest = 0.5 + clamp01(state.interest) // 0.5 .. 1.5
        val mood = 0.6 + clamp01(state.mood / 100.0) * 0.8 // 0.6 .. 1.4
        val closeness = 0.7 + clamp01(state.closeness) * 0.6 // 0.7 .. 1.3
        val timing = timingFactor(state.minutesSinceLastSpoke)
        val cost = interruptCost(state)

        val raw = BASE * interest * mood * closeness * timing - cost
        val score = clamp01(raw)
        val outcome = if (score >= config.threshold) DriveOutcome.SPEAK else DriveOutcome.HOLD
        val reason =
            buildString {
                append("兴趣×").append(fmt(interest))
                append(" 情绪×").append(fmt(mood))
                append(" 关系×").append(fmt(closeness))
                append(" 时机×").append(fmt(timing))
                if (cost > 0) append(" 打扰-").append(fmt(cost))
                append(" → ").append(fmt(score))
                append(if (outcome == DriveOutcome.SPEAK) " ≥ " else " < ")
                append(fmt(config.threshold))
            }
        return DriveDecision(outcome, score, reason)
    }

    /**
     * 时机因子：说话太密的惩罚。
     *
     * 阈值取 5 / 15 / 30 分钟：刚说完（<5）压到 0.3，基本不可能再开口；
     * 半小时以上才恢复满值。
     */
    fun timingFactor(minutesSinceLastSpoke: Int): Double =
        when {
            minutesSinceLastSpoke < 0 -> 1.2 // 还没说过：不受限
            minutesSinceLastSpoke < 5 -> 0.3
            minutesSinceLastSpoke < 15 -> 0.6
            minutesSinceLastSpoke < 30 -> 0.9
            else -> 1.2
        }

    /**
     * 打扰成本。
     *
     * 三个来源都是「这句话说出来会很突兀」：
     * - 群里聊得正热（≥12 条/10 分钟）：插进去没人有空看；
     * - 刚被无视（≥3 次）：再说只会更尴尬；
     * - 不到 5 分钟前刚说过：刷屏。
     */
    fun interruptCost(state: DriveState): Double {
        var cost = 0.0
        if (state.recentMessageCount >= 12) cost += 0.12
        if (state.ignoredCount >= 3) cost += 0.15
        if (state.minutesSinceLastSpoke in 0..4) cost += 0.10
        return cost
    }

    /**
     * 是否在安静时段。
     *
     * 支持跨零点；格式非法时视为无安静时段（宁可多开口，也不要因为配置写错而永久静默）。
     */
    fun inQuietHours(
        hourOfDay: Int,
        quietHours: String,
    ): Boolean {
        val range = parseHours(quietHours) ?: return false
        val (start, end) = range
        if (start == end) return false
        val hour = hourOfDay.coerceIn(0, 23)
        return if (start < end) {
            hour in start until end
        } else {
            hour >= start || hour < end
        }
    }

    /**
     * 话题指纹：用于"同一句话反复想"的冷却。
     *
     * 用关键词而不是原文 hash——原文差一个字就换指纹的话，冷却永远不触发。
     * 复用记忆层的二元组分词，顺带把虚词噪音过滤掉。
     */
    fun fingerprintOf(text: String): String {
        val terms = MemoryTerms.extract(text).take(FINGERPRINT_TERMS)
        return if (terms.isEmpty()) "" else terms.joinToString("/")
    }

    /**
     * 话题相似度：消息与「人格正文 + 记忆片段」的重叠程度，映射到 0..1。
     *
     * 这是 interest 因子的唯一来源。没有可比较的语料时给 0.5（中性），
     * 不让"没配置记忆"直接变成"不开口"。
     */
    fun interestOf(
        text: String,
        topics: Collection<String>,
    ): Double {
        if (text.isBlank()) return 0.5
        val textTerms = MemoryTerms.extract(text).toSet()
        if (textTerms.isEmpty()) return 0.5
        val topicTerms = HashSet<String>()
        topics.forEach { topic -> topicTerms.addAll(MemoryTerms.extract(topic)) }
        if (topicTerms.isEmpty()) return 0.5
        val hit = textTerms.count { it in topicTerms }
        // 完全不相关时给一个低位值而不是 0：兴趣低只是"不那么想接"，
        // 不等于"绝对不说"，否则人格正文写得再全也永远开不了口。
        // 两三个词命中就足以说明"在聊我懂的事"，不必要求全覆盖。
        return if (hit == 0) 0.15 else clamp01(hit / 2.0)
    }

    /**
     * 同一指纹是否处于冷却期。
     *
     * @param held 近期「忍住」记录（指纹 → 时间），按时间从旧到新
     */
    fun isCooling(
        held: List<Pair<String, Long>>,
        fingerprint: String,
        nowMillis: Long,
    ): Boolean {
        if (fingerprint.isBlank()) return false
        val count =
            held.count { (fp, at) ->
                fp == fingerprint && nowMillis - at <= HELD_COOLDOWN_MILLIS
            }
        return count >= HELD_COOLDOWN_THRESHOLD
    }

    // ---------- 内部 ----------

    private fun hold(
        score: Double,
        reason: String,
    ) = DriveDecision(DriveOutcome.HOLD, score, reason)

    private fun clamp01(value: Double): Double = max(0.0, min(1.0, value))

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

    /** 解析 `HH:mm-HH:mm`；非法返回 null */
    private fun parseHours(raw: String): Pair<Int, Int>? {
        val parts = raw.split("-")
        if (parts.size != 2) return null
        val start = hourOf(parts[0]) ?: return null
        val end = hourOf(parts[1]) ?: return null
        return start to end
    }

    private fun hourOf(raw: String): Int? {
        val head = raw.trim().substringBefore(':')
        val hour = head.toIntOrNull() ?: return null
        return if (hour in 0..23) hour else null
    }
}
