package com.qbot.core.ai

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
 * 只回答一个问题：**这批新消息够不够触发一次"问模型"**。
 * 真正的"要不要改"由模型决定（见 [PersonaGrowth.grow] 的 NO_CHANGE）；
 * 两次询问之间的冷却在调度器里（内存计时，不落库）。
 */
object PersonaGrowthPolicy {
    /** 破壳所需的最低消息量：太少会写出一个只会复读的假人格 */
    const val BIRTH_MIN_MESSAGES = 20

    /** 成长期：有点新东西就可以问一次模型 */
    const val GROW_MIN_MESSAGES = 10

    /** 成熟期：间隔翻倍的效果落在消息量上，少打扰 */
    const val MATURE_MIN_MESSAGES = 20

    /** 成长到这个次数后进入 MATURE */
    const val MATURE_GROWTHS = 8

    fun next(
        state: PersonaGrowthState,
        pendingMessages: Int,
    ): GrowthAction {
        // 唯一的人工刹车是「冻结」——全局开关和它完全等价，已删除，
        // 避免同一个意思在界面上出现两次。
        if (state.frozen) return GrowthAction.NONE

        return when (state.stage) {
            PersonaStage.EMBRYO ->
                if (pendingMessages >= BIRTH_MIN_MESSAGES) GrowthAction.BIRTH else GrowthAction.NONE
            PersonaStage.GROWING ->
                if (pendingMessages >= GROW_MIN_MESSAGES) GrowthAction.GROW else GrowthAction.NONE
            PersonaStage.MATURE ->
                if (pendingMessages >= MATURE_MIN_MESSAGES) GrowthAction.GROW else GrowthAction.NONE
            // 用户手写的人设不参与自动成长
            PersonaStage.MANUAL -> GrowthAction.NONE
        }
    }
}
