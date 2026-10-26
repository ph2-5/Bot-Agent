package com.qbot.core.ai

/**
 * 场景类型。
 *
 * 第一期只做双人对谈（相声/对口）：两个角色一来一回，有"轮次"和"等谁"的概念。
 * 其他场景（主持、接龙、多方讨论）需要不同的状态机，等这一套跑通再扩。
 */
enum class SceneKind {
    CROSSTALK,
}

/** 双人对谈里的角色。逗哏抛，捧哏接。 */
enum class SceneRole {
    COMEDIAN,
    STRAIGHT,
}

enum class SceneStatus {
    /** 正常进行，等轮到的人说话 */
    ACTIVE,

    /** 正在等某个人，waitUntil 之前不催 */
    WAITING,

    /** 已结束（正常收底 / 超时 / 催场失败） */
    ENDED,
}

/**
 * 场景开关。
 *
 * OBSERVE 是第一期的主要形态：工具照常被调用、决策照常落表，
 * 但不产生任何实际行为——先看 AI 判断得准不准，再决定要不要真的演。
 */
enum class SceneMode {
    OFF,
    OBSERVE,
    ACTIVE;

    companion object {
        fun parse(raw: String?): SceneMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OFF
    }
}

/**
 * 场景参与者。
 *
 * [senderId] 可能是空串：创建场景时只知道对方的名字（由 AI 从对话里读出），
 * 不知道对方的 openid。等对方第一次说话时再由运行时"认领"补上。
 */
data class SceneParticipant(
    val senderId: String,
    val displayName: String,
    val isBot: Boolean,
    val role: SceneRole?,
)

/**
 * 一段进行中的双人对谈。
 *
 * 纯数据 + 纯函数：所有"该不该说"的判断都能单测，不依赖数据库与网络。
 */
data class Scene(
    val id: Long,
    val kind: SceneKind,
    val groupId: String,
    val participants: List<SceneParticipant>,
    /** 这段要说到哪儿：由 AI 在开场时给出，作为后续每轮的约束 */
    val goal: String,
    val status: SceneStatus,
    /** 已经说了几轮 */
    val turnIndex: Int,
    /** 现在等谁开口；null 表示参与者谁都可以接 */
    val expectedSpeakerId: String?,
    /** 等待截止时间；null 表示没有在等 */
    val waitUntil: Long?,
    val lastActivityAt: Long,
    val createdAt: Long,
    /**
     * 本场的契约（语言风格 / 角色规则 / 禁忌 / 收尾）。
     *
     * 由 AI 开场时自己写；null 或全空表示老场景，回退固定模板。
     * 契约在场景内保持不变——中途改规则会让前后几句对不上。
     */
    val contract: SceneContract? = null,
) {
    /** 我在这个场景里的角色；不在参与者里返回 null。 */
    fun roleOf(senderId: String): SceneRole? =
        participants.firstOrNull { it.senderId.isNotBlank() && it.senderId == senderId }?.role

    /**
     * 轮到我了吗。
     *
     * - 已结束：永远不是；
     * - 指定了等谁：只有那个人能接；
     * - 没指定：所有参与者都可以接。
     */
    fun isMyTurn(senderId: String): Boolean {
        if (status == SceneStatus.ENDED) return false
        if (participants.none { it.senderId == senderId }) return false
        val expected = expectedSpeakerId
        return expected.isNullOrBlank() || expected == senderId
    }

    /** 正在等 [senderId] 吗（等待未超时时）。 */
    fun isWaitingFor(senderId: String, nowMillis: Long): Boolean =
        status == SceneStatus.WAITING && (waitUntil ?: 0L) > nowMillis && expectedSpeakerId == senderId

    /** 等待窗口是否已经过去（用来触发催场或收尾）。 */
    fun waitExpired(nowMillis: Long): Boolean =
        status == SceneStatus.WAITING && (waitUntil ?: 0L) <= nowMillis

    /** 是否超过单场景最长时长。 */
    fun isExpired(nowMillis: Long, maxMillis: Long = SceneLimits.MAX_SCENE_MILLIS): Boolean =
        status != SceneStatus.ENDED && nowMillis - createdAt > maxMillis

    /** 除我之外的参与者（双人对谈里就是"对方"）。 */
    fun partnerOf(senderId: String): SceneParticipant? =
        participants.firstOrNull { it.senderId != senderId }

    /**
     * 把一个陌生发送者认领成"对方"。
     *
     * 创建场景时只知道对方叫什么，不知道 openid；对方第一次说话时，
     * 把占位的空 id 换成真实 id，后续轮次判定才能对上。
     */
    fun claimPartner(
        senderId: String,
        displayName: String?,
        isBot: Boolean = false,
    ): Scene {
        if (senderId.isBlank()) return this
        if (participants.any { it.senderId == senderId }) return this
        val index = participants.indexOfFirst { it.senderId.isBlank() }
        // 没有占位说明这不是"对方"，而是第三方（人类插话），不该改写参与者
        if (index < 0) return this
        val updated =
            participants.toMutableList().also {
                val old = it[index]
                it[index] =
                    old.copy(
                        senderId = senderId,
                        displayName = displayName ?: old.displayName,
                        isBot = isBot,
                    )
            }
        return copy(participants = updated)
    }
}

/** 场景的硬上限：AI 决定做什么，代码决定最多能做什么。 */
object SceneLimits {
    /** 单场景最长 10 分钟 */
    const val MAX_SCENE_MILLIS = 10 * 60_000L

    /** wait_for_reply 的取值区间（秒） */
    const val MIN_WAIT_SECONDS = 5
    const val MAX_WAIT_SECONDS = 60

    /** 连续催场几次仍无回应就收尾 */
    const val MAX_PROMPTS = 3

    /** 单群每小时最多多少条场景发言 */
    const val MAX_SPEAKS_PER_HOUR = 30

    /** 场景结束后多久内仍把场景工具发给模型（秒） */
    const val RECENT_SCENE_WINDOW_MILLIS = 3 * 60_000L
}
