package com.qbot.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 一条自主开口决策。
 *
 * 与 `chime_decisions` 分开建表：那张表回答「这条消息值不值得接」，
 * 这张表回答「它此刻自己想说吗」。语义不同，混在一起会让复盘页
 * 把两种判断都显示成「接话判断」。
 */
@Entity(
    tableName = "autonomous_decisions",
    indices = [Index("groupId"), Index("fingerprint")],
)
data class AutonomousDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: String,
    val personaId: Long? = null,
    val score: Double,
    /** SPOKEN / HELD */
    val outcome: String,
    val reason: String? = null,
    /** 话题指纹：同一指纹连续忍住会进冷却 */
    val fingerprint: String? = null,
    val createdAt: Long,
)

/**
 * 按群覆盖全局档位。
 *
 * 不塞进全局 settings：A 群想活跃、B 群想安静是常态，
 * 而全局只有一个配置。
 */
@Entity(tableName = "group_behavior")
data class GroupBehaviorEntity(
    @PrimaryKey val groupId: String,
    /** OFF / PASSIVE / ACTIVE */
    val autonomyMode: String,
    val updatedAt: Long,
)

@Dao
interface AutonomousDecisionDao {
    @Insert
    suspend fun insert(entity: AutonomousDecisionEntity): Long

    /** 先取最近的，调用方按需反转 */
    @Query("SELECT * FROM autonomous_decisions WHERE groupId = :groupId ORDER BY id DESC LIMIT :limit")
    suspend fun recent(
        groupId: String,
        limit: Int,
    ): List<AutonomousDecisionEntity>

    @Query(
        "SELECT COUNT(*) FROM autonomous_decisions " +
            "WHERE groupId = :groupId AND outcome = 'SPOKEN' AND createdAt >= :sinceMillis",
    )
    suspend fun countSpokenSince(
        groupId: String,
        sinceMillis: Long,
    ): Int

    @Query(
        "SELECT * FROM autonomous_decisions " +
            "WHERE groupId = :groupId AND outcome = 'HELD' AND createdAt >= :sinceMillis " +
            "ORDER BY id ASC",
    )
    suspend fun heldSince(
        groupId: String,
        sinceMillis: Long,
    ): List<AutonomousDecisionEntity>

    /** 最近有消息活动的群（调度器只评估这些群，避免给死群白跑） */
    @Query(
        "SELECT DISTINCT groupId FROM autonomous_decisions WHERE createdAt >= :sinceMillis",
    )
    suspend fun activeGroupsSince(sinceMillis: Long): List<String>

    @Query("DELETE FROM autonomous_decisions WHERE createdAt < :before")
    suspend fun trimBefore(before: Long)

    @Query("DELETE FROM autonomous_decisions")
    suspend fun clear()

    /** 复盘页：最近的自主决策 */
    @Query("SELECT * FROM autonomous_decisions ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AutonomousDecisionEntity>>
}

@Dao
interface GroupBehaviorDao {
    @Query("SELECT * FROM group_behavior")
    suspend fun all(): List<GroupBehaviorEntity>

    @Query("SELECT * FROM group_behavior")
    fun observeAll(): Flow<List<GroupBehaviorEntity>>

    @Query("SELECT * FROM group_behavior WHERE groupId = :groupId")
    suspend fun byGroup(groupId: String): GroupBehaviorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GroupBehaviorEntity)

    @Query("DELETE FROM group_behavior WHERE groupId = :groupId")
    suspend fun delete(groupId: String)
}
