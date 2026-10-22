package com.qbot.core.ai

/** 人格成长的用户可调参数（设置 → 智能 → 人格成长）。 */
data class PersonaGrowthConfig(
    val enabled: Boolean = true,
    /** 破壳所需消息数 */
    val birthMessages: Int = 60,
    /** 一次成长所需的新增消息数 */
    val growMessages: Int = 150,
    /**
     * 两次成长的最小间隔（小时）。
     *
     * 这就是频率上限本身——不再额外叠"每天最多一次"的硬限，
     * 否则用户把间隔调到 12 小时也永远触发不了。
     * 默认 12 小时 ≈ 一天最多两次，每次改动不超过 15%，不会越改越花。
     */
    val growIntervalHours: Int = 12,
) {
    companion object {
        const val MIN_BIRTH_MESSAGES = 20
        const val MAX_BIRTH_MESSAGES = 200
        const val MIN_GROW_MESSAGES = 50
        const val MAX_GROW_MESSAGES = 500
        const val MIN_INTERVAL_HOURS = 1
        const val MAX_INTERVAL_HOURS = 72

        fun normalize(
            enabled: Boolean,
            birthMessages: Int,
            growMessages: Int,
            growIntervalHours: Int,
        ): PersonaGrowthConfig =
            PersonaGrowthConfig(
                enabled = enabled,
                birthMessages = birthMessages.coerceIn(MIN_BIRTH_MESSAGES, MAX_BIRTH_MESSAGES),
                growMessages = growMessages.coerceIn(MIN_GROW_MESSAGES, MAX_GROW_MESSAGES),
                growIntervalHours =
                    growIntervalHours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS),
            )
    }
}

/** 判定成长所需的人格状态切片（只保留判定需要的字段）。 */
data class PersonaGrowthState(
    val stage: PersonaStage,
    val frozen: Boolean = false,
    val growthCount: Int = 0,
    /** 破壳时间；未破壳时是档案创建时间 */
    val birthAt: Long = 0,
    /** 上次成长时间；新破壳的档案应设为破壳时间，避免立刻再长一次 */
    val lastGrownAt: Long = 0,
)

/** 该做什么。 */
enum class GrowthAction {
    NONE,
    BIRTH,
    GROW,
}

/**
 * 成长节奏的纯函数判定。
 *
 * 放在 botcore 而不是调度器里，是为了能脱离 Android / Room 直接单测边界值。
 */
object PersonaGrowthPolicy {
    /** 破壳的快速通道：消息不算多但已经等了一会儿 */
    const val BIRTH_MIN_AGE_MILLIS = 30 * 60_000L

    /** 快速通道的最低消息量 */
    const val BIRTH_FALLBACK_MESSAGES = 20

    /** 长时间没动静时的兜底：攒够这些就长一次，避免"没人聊天永远不长" */
    const val IDLE_MESSAGES = 40
    const val IDLE_INTERVAL_MILLIS = 7L * 24 * 3600 * 1000

    /** 成长到这个次数后进入 MATURE，间隔翻倍 */
    const val MATURE_GROWTHS = 8

    fun next(
        state: PersonaGrowthState,
        pendingMessages: Int,
        now: Long,
        config: PersonaGrowthConfig = PersonaGrowthConfig(),
    ): GrowthAction {
        if (!config.enabled || state.frozen) return GrowthAction.NONE

        return when (state.stage) {
            PersonaStage.EMBRYO -> {
                val enough = pendingMessages >= config.birthMessages
                val quick =
                    pendingMessages >= BIRTH_FALLBACK_MESSAGES &&
                        state.birthAt > 0 &&
                        now - state.birthAt >= BIRTH_MIN_AGE_MILLIS
                if (enough || quick) GrowthAction.BIRTH else GrowthAction.NONE
            }

            PersonaStage.GROWING, PersonaStage.MATURE -> {
                val since =
                    if (state.lastGrownAt <= 0) Long.MAX_VALUE else now - state.lastGrownAt
                val base = config.growIntervalHours.coerceIn(1, 72) * 3_600_000L
                val interval = if (state.stage == PersonaStage.MATURE) base * 2 else base
                when {
                    pendingMessages >= config.growMessages && since >= interval -> GrowthAction.GROW
                    pendingMessages >= IDLE_MESSAGES && since >= IDLE_INTERVAL_MILLIS ->
                        GrowthAction.GROW
                    else -> GrowthAction.NONE
                }
            }

            // 用户手写的人设不参与自动成长
            PersonaStage.MANUAL -> GrowthAction.NONE
        }
    }
}
