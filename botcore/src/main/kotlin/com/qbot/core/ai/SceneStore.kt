package com.qbot.core.ai

/** 场景决策的动作名。落库后调试页按这个分类统计。 */
object SceneAction {
    const val START = "START"
    const val WAIT = "WAIT"
    const val PROMPT = "PROMPT"
    const val END = "END"

    /** 场景内真正说出去的一句（不算"决策"，但要计数，用于每小时限额） */
    const val SPEAK = "SPEAK"
}

/**
 * 一条场景决策记录。
 *
 * OBSERVE 模式下 [executed] 恒为 false：AI 想做的动作照常记录，
 * 但不产生任何实际行为。这是第一期最有价值的数据——用来看 AI 会不会乱开场。
 */
data class SceneDecisionRecord(
    val id: Long = 0,
    val sceneId: Long? = null,
    val groupId: String,
    val action: String,
    val argsJson: String? = null,
    val executed: Boolean = false,
    val reason: String? = null,
    val createdAt: Long,
)

/**
 * 场景存储。
 *
 * botcore 不依赖 Room：Android 侧实现（RoomSceneStore），测试用内存实现。
 * 只暴露运行时真正需要的操作，调试页的观察流留在 app 侧。
 */
interface SceneStore {
    /** 本群进行中的场景（ACTIVE / WAITING）；没有返回 null。 */
    suspend fun active(groupId: String): Scene?

    /** 所有进行中的场景：定时巡检用（超时收尾、等待过期催场）。 */
    suspend fun activeScenes(): List<Scene>

    /** 插入（id == 0）或更新（id != 0），返回带 id 的场景。 */
    suspend fun save(scene: Scene): Scene

    /** 结束并把状态定为 ENDED。 */
    suspend fun end(sceneId: Long, nowMillis: Long)

    /** 本群最近一次场景结束时间；没有返回 null。 */
    suspend fun lastEndedAt(groupId: String): Long?

    /** 落一条决策记录（包括 OBSERVE 的"只读"记录）。 */
    suspend fun record(decision: SceneDecisionRecord)

    /** 本群在 [sinceMillis] 之后说了多少句（SPEAK 计数，用于每小时限额）。 */
    suspend fun speaksSince(groupId: String, sinceMillis: Long): Int
}
