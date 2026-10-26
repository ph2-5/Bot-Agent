package com.qbot.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 一段进行中的双人对谈。
 *
 * 参与者序列化成 JSON 存一列：双人场景最多两个人，
 * 拆表反而要维护关联与级联，读的时候还得拼回去。
 */
@Entity(
    tableName = "scenes",
    indices = [Index(value = ["groupId", "status"], name = "index_scenes_group_status")],
)
data class SceneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** CROSSTALK */
    val kind: String,
    val groupId: String,
    /** SceneParticipant 数组的 JSON */
    val participantsJson: String,
    val goal: String,
    /** ACTIVE / WAITING / ENDED */
    val status: String,
    val turnIndex: Int = 0,
    val expectedSpeakerId: String? = null,
    val waitUntil: Long? = null,
    val lastActivityAt: Long,
    val createdAt: Long,
)

/**
 * 一条场景决策。
 *
 * OBSERVE 模式下 executed 恒为 0：AI 想做的动作照常记录，
 * 但不产生任何实际行为——第一期就是靠这批数据判断它会不会乱开场。
 */
@Entity(
    tableName = "scene_decisions",
    indices = [Index(value = ["groupId"], name = "index_scene_decisions_group")],
)
data class SceneDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sceneId: Long? = null,
    val groupId: String,
    /** START / WAIT / PROMPT / END / SPEAK */
    val action: String,
    val argsJson: String? = null,
    /** 0 = 只读记录，1 = 实际执行 */
    val executed: Boolean = false,
    val reason: String? = null,
    val createdAt: Long,
)

@Dao
interface SceneDao {
    @Query("SELECT * FROM scenes WHERE groupId = :groupId AND status != 'ENDED' ORDER BY id DESC LIMIT 1")
    suspend fun active(groupId: String): SceneEntity?

    @Query("SELECT * FROM scenes WHERE status != 'ENDED' ORDER BY id ASC")
    suspend fun activeScenes(): List<SceneEntity>

    @Insert
    suspend fun insert(entity: SceneEntity): Long

    @Query(
        """
        UPDATE scenes SET kind = :kind, participantsJson = :participantsJson, goal = :goal,
            status = :status, turnIndex = :turnIndex, expectedSpeakerId = :expectedSpeakerId,
            waitUntil = :waitUntil, lastActivityAt = :lastActivityAt
        WHERE id = :id
        """,
    )
    suspend fun update(
        id: Long,
        kind: String,
        participantsJson: String,
        goal: String,
        status: String,
        turnIndex: Int,
        expectedSpeakerId: String?,
        waitUntil: Long?,
        lastActivityAt: Long,
    )

    @Query("UPDATE scenes SET status = 'ENDED' WHERE id = :id")
    suspend fun end(id: Long)

    @Query("SELECT MAX(createdAt) FROM scenes WHERE groupId = :groupId AND status = 'ENDED'")
    suspend fun lastEndedAt(groupId: String): Long?

    @Query("DELETE FROM scenes WHERE status = 'ENDED' AND createdAt < :before")
    suspend fun trimEndedBefore(before: Long)

    /** 调试页：当前群最近的一段 */
    @Query("SELECT * FROM scenes WHERE groupId = :groupId ORDER BY id DESC LIMIT :limit")
    fun observeRecent(groupId: String, limit: Int): Flow<List<SceneEntity>>
}

@Dao
interface SceneDecisionDao {
    @Insert
    suspend fun insert(entity: SceneDecisionEntity): Long

    @Query("SELECT * FROM scene_decisions WHERE groupId = :groupId ORDER BY id DESC LIMIT :limit")
    suspend fun recent(groupId: String, limit: Int): List<SceneDecisionEntity>

    @Query("SELECT * FROM scene_decisions ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SceneDecisionEntity>>

    @Query("SELECT COUNT(*) FROM scene_decisions WHERE groupId = :groupId AND action = 'SPEAK' AND createdAt >= :sinceMillis")
    suspend fun speaksSince(groupId: String, sinceMillis: Long): Int

    @Query("DELETE FROM scene_decisions WHERE createdAt < :before")
    suspend fun trimBefore(before: Long)
}
