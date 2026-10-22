package com.qbot.core.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 群档案：机器人对「自己在哪些群、群里见过谁」的记忆。
 *
 * 平台不提供群成员列表接口，这些档案完全靠事件拼出来：
 * - GROUP_ADD_ROBOT → 建档（joinedAt）
 * - GROUP_MEMBER_ADD → seenMembers 追加（新成员进群）
 * - GROUP_AT_MESSAGE_CREATE → seenMembers 追加 + lastSeenAt 刷新
 * - GROUP_DEL_ROBOT → active = false（被移出群）
 * - GROUP_MSG_REJECT / RECEIVE → proactiveAllowed（群管开关）
 *
 * @param seenMembers 本群见过的 member_openid。按群分配的 openid 跨群不复用，
 *   因此这个集合就是「本群跟机器人有过交集的人」。有上限，超出丢最旧的。
 */
data class GroupProfile(
    val groupId: String,
    /** 机器人在本群的称呼；null 表示用全局名（READY 下发的 username） */
    val botName: String? = null,
    val joinedAt: Long,
    val lastSeenAt: Long,
    /** 机器人是否还在群里（被移出后为 false，再加回来会重新置 true） */
    val active: Boolean = true,
    /** 群是否允许机器人主动发言 */
    val proactiveAllowed: Boolean = true,
    val seenMembers: Set<String> = emptySet(),
) {
    val seenCount: Int
        get() = seenMembers.size
}

/** 群档案存储。 */
interface GroupStore {
    suspend fun find(groupId: String): GroupProfile?

    suspend fun all(): List<GroupProfile>

    suspend fun allActive(): List<GroupProfile> = all().filter { it.active }

    suspend fun upsert(profile: GroupProfile)

    suspend fun delete(groupId: String)

    /**
     * 记录一次群内互动：刷新 lastSeenAt，并把 sender 记入「见过的人」。
     *
     * @param seenCap 每群最多记住多少个 openid，超出丢最旧的（保序追加）
     */
    suspend fun touch(
        groupId: String,
        senderId: String?,
        at: Long,
        seenCap: Int = 500,
    )

    suspend fun count(): Int
}

/** 内存实现：测试与「轻量模式」用；Android 侧走 Room。 */
class InMemoryGroupStore(
    private val clock: () -> Long = System::currentTimeMillis,
) : GroupStore {
    private val mutex = Mutex()
    private val store = LinkedHashMap<String, GroupProfile>()

    override suspend fun find(groupId: String): GroupProfile? =
        mutex.withLock {
            store[groupId]
        }

    override suspend fun all(): List<GroupProfile> =
        mutex.withLock {
            store.values.toList()
        }

    override suspend fun upsert(profile: GroupProfile) {
        mutex.withLock {
            store[profile.groupId] = profile
        }
    }

    override suspend fun delete(groupId: String) {
        mutex.withLock {
            store.remove(groupId)
        }
    }

    override suspend fun touch(
        groupId: String,
        senderId: String?,
        at: Long,
        seenCap: Int,
    ) {
        mutex.withLock {
            val current = store[groupId]
            val seen =
                current?.seenMembers?.toMutableList() ?: mutableListOf()
            if (senderId != null && senderId.isNotBlank()) {
                seen.remove(senderId) // 移到队尾，保证淘汰的是最久未出现的
                seen.add(senderId)
                while (seen.size > seenCap) seen.removeAt(0)
            }
            store[groupId] =
                GroupProfile(
                    groupId = groupId,
                    botName = current?.botName,
                    joinedAt = current?.joinedAt ?: at,
                    lastSeenAt = maxOf(at, current?.lastSeenAt ?: 0L),
                    active = true,
                    proactiveAllowed = current?.proactiveAllowed ?: true,
                    seenMembers = seen.toSet(),
                )
        }
    }

    override suspend fun count(): Int =
        mutex.withLock {
            store.size
        }
}
