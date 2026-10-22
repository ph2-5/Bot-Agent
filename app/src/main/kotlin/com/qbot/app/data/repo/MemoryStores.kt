package com.qbot.app.data.repo

import com.qbot.app.data.db.FactDao
import com.qbot.app.data.db.FactEntity
import com.qbot.app.data.db.IdentityDao
import com.qbot.app.data.db.IdentityEntity
import com.qbot.app.data.db.RoomMessageDao
import com.qbot.app.data.db.RoomMessageEntity
import com.qbot.core.ai.FactKind
import com.qbot.core.ai.FactSource
import com.qbot.core.ai.IdentityEntry
import com.qbot.core.ai.IdentityStore
import com.qbot.core.ai.KnownPerson
import com.qbot.core.ai.MemoryStore
import com.qbot.core.ai.NameSource
import com.qbot.core.ai.RoomContextStore
import com.qbot.core.ai.RoomMessage
import com.qbot.core.ai.UserFact
import com.qbot.core.ai.priority
import kotlinx.coroutines.flow.map

/**
 * 基于 Room 的身份名录实现。
 *
 * 与 botcore 的 [IdentityStore] 接口对齐：优先级守卫在
 * botcore 侧已经做掉一层（见 [com.qbot.core.ai.InMemoryIdentityStore]），
 * 这里对同名覆盖场景再兜底一次——数据库是事实来源，宁可不更新。
 */
class RoomIdentityStore(
    private val dao: IdentityDao,
) : IdentityStore {
    override suspend fun find(senderId: String): IdentityEntry? =
        dao.find(senderId)?.let { entity ->
            IdentityEntry(
                senderId = entity.senderId,
                displayName = entity.displayName,
                source = runCatching { NameSource.valueOf(entity.source) }
                    .getOrDefault(NameSource.PLATFORM),
                updatedAt = entity.updatedAt,
            )
        }

    override suspend fun remember(
        senderId: String,
        name: String,
        source: NameSource,
        updatedAt: Long,
    ) {
        val existing = dao.find(senderId)
        if (existing != null && source.priority < existing.priorityValue()) return
        dao.upsert(
            IdentityEntity(
                senderId = senderId,
                displayName = name,
                source = source.name,
                updatedAt = updatedAt,
            ),
        )
    }

    override suspend fun forget(senderId: String) {
        dao.delete(senderId)
    }

    override suspend fun findMany(senderIds: Collection<String>): Map<String, IdentityEntry> {
        if (senderIds.isEmpty()) return emptyMap()
        return dao.findMany(senderIds.toList()).associate { entity ->
            entity.senderId to
                IdentityEntry(
                    senderId = entity.senderId,
                    displayName = entity.displayName,
                    source =
                        runCatching { NameSource.valueOf(entity.source) }
                            .getOrDefault(NameSource.PLATFORM),
                    updatedAt = entity.updatedAt,
                )
        }
    }

    override suspend fun recentKnown(limit: Int): List<KnownPerson> =
        dao.recent(limit).map { KnownPerson(it.senderId, it.displayName) }

    private fun IdentityEntity.priorityValue(): Int =
        runCatching { NameSource.valueOf(source).priority }.getOrDefault(0)
}

/** 基于 Room 的长期事实存储。 */
class RoomFactStore(
    private val dao: FactDao,
) : MemoryStore {
    override suspend fun facts(senderId: String): List<UserFact> =
        dao.forSender(senderId).map { it.toFact() }

    override suspend fun add(fact: UserFact): Long =
        dao.insert(
            FactEntity(
                senderId = fact.senderId,
                text = fact.text,
                kind = fact.kind.name,
                source = fact.source.name,
                updatedAt = fact.updatedAt,
            ),
        )

    override suspend fun delete(factId: Long) {
        dao.deleteById(factId)
    }

    override suspend fun deleteAll(senderId: String) {
        dao.deleteAllForSender(senderId)
    }

    override suspend fun trimTo(
        senderId: String,
        keep: Int,
    ) {
        dao.trimTo(senderId, keep)
    }

    override suspend fun trimAll(keep: Int) {
        // 逐个发送者收敛：老版本 SQLite 没有窗口函数，拆成循环最稳妥
        dao.allSenders().forEach { sender ->
            dao.trimTo(sender, keep)
        }
    }

    private fun FactEntity.toFact(): UserFact =
        UserFact(
            id = id,
            senderId = senderId,
            text = text,
            kind = runCatching { FactKind.valueOf(kind) }.getOrDefault(FactKind.NOTE),
            source = runCatching { FactSource.valueOf(source) }
                .getOrDefault(FactSource.EXTRACTED),
            updatedAt = updatedAt,
        )
}

/**
 * 基于 Room 的共享群聊背景存储。
 *
 * 按人隔离记忆后，这条「房间里最近发生了什么」的滚动缓冲
 * 专门解决跨人指代（「那他呢」「换个话题」这类承接）。
 */
class RoomSharedContextStore(
    private val dao: RoomMessageDao,
) : RoomContextStore {
    override suspend fun recent(
        scopeKey: String,
        limit: Int,
    ): List<RoomMessage> =
        // 查询是倒序取的，转回正序才是真实的时间顺序
        dao
            .recent(scopeKey, limit)
            .asReversed()
            .map { it.toRoomMessage() }

    override suspend fun append(
        scopeKey: String,
        message: RoomMessage,
    ) {
        dao.insert(
            RoomMessageEntity(
                scopeKey = scopeKey,
                senderId = message.senderId,
                senderName = message.senderName,
                role = message.role,
                content = message.content,
                timestampMillis = message.timestampMillis,
                isAt = message.isAt,
                externalId = message.externalId,
                decision = message.decision,
            ),
        )
    }

    override suspend fun trim(
        scopeKey: String,
        keep: Int,
    ) {
        dao.trim(scopeKey, keep)
    }

    override suspend fun updateDecision(
        scopeKey: String,
        externalId: String,
        decision: String,
    ) {
        dao.updateDecision(scopeKey, externalId, decision)
    }

    override fun observeRecent(
        scopeKey: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<RoomMessage>> =
        dao.observeRecent(scopeKey, limit).map { list ->
            // 查询是倒序，转回正序展示
            list.asReversed().map { it.toRoomMessage() }
        }

    private fun RoomMessageEntity.toRoomMessage(): RoomMessage =
        RoomMessage(
            senderId = senderId,
            senderName = senderName,
            role = role,
            content = content,
            timestampMillis = timestampMillis,
            isAt = isAt,
            externalId = externalId,
            decision = decision,
        )
}
