package com.qbot.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 群档案：机器人进过哪些群、在每个群的昵称与主动消息权限。
 *
 * 与 botcore 的 [com.qbot.core.ai.GroupProfile] 一一对应。
 * 平台不提供群信息查询接口，这些字段完全靠网关事件拼出来：
 * - GROUP_ADD_ROBOT → 建档（joinedAt）
 * - GROUP_DEL_ROBOT → active = false
 * - GROUP_MSG_RECEIVE / REJECT → proactiveAllowed
 * - 「叫你X」指令 / 维护端手动 → botName
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    /** 机器人在本群的称呼；null 表示用全局名（READY 下发的 username） */
    val botName: String?,
    val joinedAt: Long,
    val lastSeenAt: Long,
    /** 机器人是否还在群里（被移出后为 false，再加回来重新置 true） */
    val active: Boolean,
    /** 群是否允许机器人主动发言（管理员开关） */
    val proactiveAllowed: Boolean,
)

/**
 * 群成员样本：本群跟机器人有过交集的 member_openid。
 *
 * 按群分配的 openid 跨群不复用，因此 (groupId, memberId) 唯一。
 * 自增 id 即「最近一次出现的顺序」——重新出现时删后插，
 * 队尾永远是最活跃的，淘汰时从队首（最久未出现）开始。
 */
@Entity(
    tableName = "group_members",
    indices = [
        Index("groupId"),
        Index(value = ["groupId", "memberId"], unique = true),
    ],
)
data class GroupMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: String,
    val memberId: String,
    val lastSeenAt: Long,
)

/** 群档案 + 成员数（维护端群列表用，避免 N+1 查询）。 */
data class GroupWithCount(
    @Embedded val group: GroupEntity,
    val seenCount: Int,
)

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    suspend fun find(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups ORDER BY lastSeenAt DESC")
    suspend fun all(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE active = 1 ORDER BY lastSeenAt DESC")
    suspend fun allActive(): List<GroupEntity>

    @Upsert
    suspend fun upsert(group: GroupEntity)

    @Query("DELETE FROM groups WHERE groupId = :groupId")
    suspend fun delete(groupId: String)

    @Query("SELECT COUNT(*) FROM groups")
    suspend fun count(): Int

    /** 维护端群列表：按最近活跃倒序，附带成员数 */
    @Query(
        """
        SELECT g.*, (
            SELECT COUNT(*) FROM group_members m WHERE m.groupId = g.groupId
        ) AS seenCount
        FROM groups g
        ORDER BY g.lastSeenAt DESC
        """,
    )
    fun observeWithCounts(): Flow<List<GroupWithCount>>
}

@Dao
interface GroupMemberDao {
    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY id ASC")
    suspend fun members(groupId: String): List<GroupMemberEntity>

    @Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId")
    suspend fun countFor(groupId: String): Int

    @Insert
    suspend fun insert(member: GroupMemberEntity): Long

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND memberId = :memberId")
    suspend fun delete(
        groupId: String,
        memberId: String,
    )

    /** 只保留每个群最近出现的 keep 个成员，淘汰更久未出现的 */
    @Query(
        """
        DELETE FROM group_members
        WHERE groupId = :groupId
          AND id NOT IN (
              SELECT id FROM group_members WHERE groupId = :groupId
              ORDER BY id DESC LIMIT :keep
          )
        """,
    )
    suspend fun trim(
        groupId: String,
        keep: Int,
    )

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun clearFor(groupId: String)
}
