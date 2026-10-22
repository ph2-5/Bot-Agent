package com.qbot.app.data.repo

import com.qbot.app.data.db.GroupDao
import com.qbot.app.data.db.GroupEntity
import com.qbot.app.data.db.GroupMemberDao
import com.qbot.app.data.db.GroupMemberEntity
import com.qbot.app.data.db.GroupWithCount
import com.qbot.core.ai.GroupProfile
import com.qbot.core.ai.GroupStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 基于 Room 的群档案存储。
 *
 * 与 [com.qbot.core.ai.InMemoryGroupStore] 语义对齐：
 * - [upsert] 是全量替换（成员集合以传入值为准，差量同步）；
 * - [touch] 是增量记账（删后插，把再次出现的人移到队尾，超出上限丢最旧的）。
 *
 * 成员单独建表而不是塞进 groups 的 JSON 字段，是为了让
 * 「按 id 淘汰最旧的」可以用 SQL 直接表达，也方便维护端按群查成员。
 */
class RoomGroupStore(
    private val groupDao: GroupDao,
    private val memberDao: GroupMemberDao,
) : GroupStore {
    override suspend fun find(groupId: String): GroupProfile? =
        groupDao.find(groupId)?.toProfile()

    override suspend fun all(): List<GroupProfile> =
        groupDao.all().map { it.toProfile() }

    override suspend fun allActive(): List<GroupProfile> =
        groupDao.allActive().map { it.toProfile() }

    override suspend fun upsert(profile: GroupProfile) {
        groupDao.upsert(profile.toEntity())
        syncMembers(profile)
    }

    override suspend fun delete(groupId: String) {
        memberDao.clearFor(groupId)
        groupDao.delete(groupId)
    }

    override suspend fun touch(
        groupId: String,
        senderId: String?,
        at: Long,
        seenCap: Int,
    ) {
        val existing = groupDao.find(groupId)
        groupDao.upsert(
            GroupEntity(
                groupId = groupId,
                botName = existing?.botName,
                joinedAt = existing?.joinedAt ?: at,
                lastSeenAt = maxOf(at, existing?.lastSeenAt ?: 0L),
                // 群里一有动静就认为机器人还在群里：被移出后收到补发事件的场景
                active = true,
                proactiveAllowed = existing?.proactiveAllowed ?: true,
            ),
        )
        val member = senderId?.takeIf { it.isNotBlank() } ?: return
        // 删后插：自增 id 即「最近出现顺序」，队尾永远是最新的
        memberDao.delete(groupId, member)
        memberDao.insert(
            GroupMemberEntity(groupId = groupId, memberId = member, lastSeenAt = at),
        )
        memberDao.trim(groupId, seenCap)
    }

    override suspend fun count(): Int = groupDao.count()

    /** 维护端群列表：按最近活跃倒序，附带成员数。 */
    fun observeWithCounts(): Flow<List<GroupWithCount>> = groupDao.observeWithCounts()

    /** 维护端看某个群的成员样本（id 顺序即出现顺序，旧的在前）。 */
    suspend fun members(groupId: String): List<String> =
        memberDao.members(groupId).map { it.memberId }

    /** 维护端手动改群名片。 */
    suspend fun rename(
        groupId: String,
        botName: String?,
    ) {
        val existing = groupDao.find(groupId) ?: return
        groupDao.upsert(existing.copy(botName = botName?.takeIf { it.isNotBlank() }))
    }

    // ---------- 内部 ----------

    private suspend fun GroupEntity.toProfile(): GroupProfile =
        GroupProfile(
            groupId = groupId,
            botName = botName,
            joinedAt = joinedAt,
            lastSeenAt = lastSeenAt,
            active = active,
            proactiveAllowed = proactiveAllowed,
            seenMembers = memberDao.members(groupId).map { it.memberId }.toSet(),
        )

    private fun GroupProfile.toEntity() =
        GroupEntity(
            groupId = groupId,
            botName = botName,
            joinedAt = joinedAt,
            lastSeenAt = lastSeenAt,
            active = active,
            proactiveAllowed = proactiveAllowed,
        )

    /** 成员集合全量对齐：调用方传入的集合即事实来源。 */
    private suspend fun syncMembers(profile: GroupProfile) {
        val current = memberDao.members(profile.groupId).map { it.memberId }.toSet()
        val target = profile.seenMembers
        if (current == target) return
        (current - target).forEach { memberDao.delete(profile.groupId, it) }
        (target - current).forEach { member ->
            memberDao.insert(
                GroupMemberEntity(
                    groupId = profile.groupId,
                    memberId = member,
                    lastSeenAt = profile.lastSeenAt,
                ),
            )
        }
    }
}

/** 维护端列表用的展示模型：档案 + 成员数。 */
data class GroupSummary(
    val groupId: String,
    val botName: String?,
    val active: Boolean,
    val proactiveAllowed: Boolean,
    val joinedAt: Long,
    val lastSeenAt: Long,
    val seenCount: Int,
)

/** 群档案 → 维护端展示模型。 */
fun GroupWithCount.toSummary(): GroupSummary =
    GroupSummary(
        groupId = group.groupId,
        botName = group.botName,
        active = group.active,
        proactiveAllowed = group.proactiveAllowed,
        joinedAt = group.joinedAt,
        lastSeenAt = group.lastSeenAt,
        seenCount = seenCount,
    )

/** 维护端群列表 Flow（带成员数）。 */
fun RoomGroupStore.observeSummaries(): Flow<List<GroupSummary>> =
    observeWithCounts().map { list -> list.map { it.toSummary() } }
