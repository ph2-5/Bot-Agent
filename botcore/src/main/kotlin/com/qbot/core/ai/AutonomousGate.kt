package com.qbot.core.ai

/**
 * 第二道闸门的结论。
 *
 * 只给"要不要说"和一句理由，**不给正文**——正文由主模型生成，
 * 便宜模型不负责写台词。
 */
data class AutonomousDecision(
    val speak: Boolean,
    /** 一句话理由，写进决策表供复盘页与调参 */
    val reason: String = "",
    /** 想说的主题（可选） */
    val topic: String? = null,
)

/**
 * 第二道闸门：该不该说。
 *
 * 由 app 侧用便宜模型实现（见 `LlmAutonomousGate`）；返回 null 表示这次不判断，
 * 一律按 HOLD 处理——宁可少说一句，也不要因为判定服务抖动而在群里乱开口。
 */
fun interface AutonomousGate {
    suspend fun shouldSpeak(
        context: String,
        config: AuxAiConfig,
    ): AutonomousDecision?
}

/**
 * 一条自主决策记录。
 *
 * SPOKEN / HELD 分开记：只记开口的话，"忍住"这件事就看不见了，
 * 而调阈值恰恰最需要看"它本来想说但忍住了多少次"。
 */
data class AutonomousRecord(
    val groupId: String,
    val personaId: Long?,
    val score: Double,
    val outcome: String,
    val reason: String?,
    /** 话题指纹，用于同一话题的冷却 */
    val fingerprint: String?,
    val createdAt: Long,
) {
    val spoken: Boolean get() = outcome == OUTCOME_SPOKEN

    companion object {
        const val OUTCOME_SPOKEN: String = "SPOKEN"
        const val OUTCOME_HELD: String = "HELD"
    }
}

/**
 * 自主决策的存储接口。
 *
 * botcore 不依赖 Room：调度器拿到的是这个接口，Android 侧用 DAO 实现。
 * 多机器人天然隔离——每个实例拿的是自己那个库的实现。
 */
interface DriveStore {
    suspend fun record(record: AutonomousRecord)

    /** 最近的决策记录，按时间从旧到新 */
    suspend fun recent(
        groupId: String,
        limit: Int,
    ): List<AutonomousRecord>

    /** 某时间点之后该群真正开口的次数（配额用） */
    suspend fun countSpokenSince(
        groupId: String,
        sinceMillis: Long,
    ): Int

    /** 某时间点之后该群「忍住」的指纹记录（冷却用） */
    suspend fun recentHeldFingerprints(
        groupId: String,
        sinceMillis: Long,
    ): List<Pair<String, Long>>
}

/**
 * 内心戏来源：自主开口的素材之一。
 *
 * 只取最近几条**没说出口**的思考，不全量塞进提示词——
 * 一次塞十几条内心戏，模型会开始复述它们。
 */
fun interface RecentThoughtsProvider {
    suspend fun recent(
        groupId: String,
        limit: Int,
    ): List<String>
}
