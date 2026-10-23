package com.qbot.app.data.repo

import com.qbot.app.data.db.AffinityDao
import com.qbot.app.data.db.AffinityEntity
import com.qbot.app.data.db.BotMoodDao
import com.qbot.app.data.db.BotMoodEntity
import com.qbot.app.data.db.MemeDao
import com.qbot.app.data.db.MemeEntity
import com.qbot.app.data.db.SlangDao
import com.qbot.app.data.db.SlangEntity
import com.qbot.core.ai.BotMood
import com.qbot.core.ai.MemeImage
import com.qbot.core.ai.MoodPolicy
import com.qbot.core.model.IncomingMessage
import com.qbot.core.ai.PersonRelation
import com.qbot.core.ai.RelationEvent
import com.qbot.core.ai.RelationPolicy
import com.qbot.core.ai.RelationRecorder
import com.qbot.core.ai.SlangTerm
import com.qbot.core.ai.SocialContext
import com.qbot.core.ai.SocialContextProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 关系 / 心情 / 梗库 / 表情包库的 Room 实现。
 *
 * 同时扮演两个角色：
 * - [SocialContextProvider]：每条消息前查一次，注入提示词；
 * - [RelationRecorder]：互动发生时写回。
 *
 * 读的时候顺手做时间回归（好感向中性靠、心情向平常靠），
 * 这样不需要额外的定时任务——有消息进来就会自然收敛。
 */
class RoomSocialStore(
    private val affinityDao: AffinityDao,
    private val moodDao: BotMoodDao,
    private val slangDao: SlangDao,
    private val thoughtDao: com.qbot.app.data.db.InnerThoughtDao? = null,
    private val memeDao: MemeDao? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : SocialContextProvider, RelationRecorder {
    /** 读-改-写要串行，否则并发两条消息会把彼此的增量覆盖掉 */
    private val mutex = Mutex()

    override suspend fun provide(message: IncomingMessage): SocialContext {
        val now = clock()
        val userId = message.senderId?.takeIf { it.isNotBlank() }
        val relation = userId?.let { loadRelation(it, now) }
        val mood = loadMood(now)
        val groupId = message.targetId?.takeIf { it.isNotBlank() }
        val slang =
            runCatching { slangDao.promptTerms(groupId, 8).map { it.toModel() } }
                .getOrDefault(emptyList())
        // 候选表情包：全局池，取最常用的几张
        val memes =
            runCatching { memeDao?.promptImages(8)?.map { it.url } ?: emptyList() }
                .getOrDefault(emptyList())
        // 上一次对这个人的内心：让"我忍过你"成为关系的一部分
        val lastThought =
            if (userId == null) {
                null
            } else {
                runCatching { thoughtDao?.lastFor(userId)?.thought?.take(120) }.getOrNull()
            }
        return SocialContext(
            relation = relation,
            mood = mood,
            slang = slang,
            memes = memes,
            lastThought = lastThought?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun loadRelation(userId: String, now: Long): PersonRelation? {
        val row = affinityDao.find(userId) ?: return null
        val model = row.toModel()
        val decayed = RelationPolicy.decay(model, now)
        // 只在真的衰减时才写：读路径不能变成写路径
        if (decayed.affinity != model.affinity) affinityDao.upsert(decayed.toEntity(now))
        return decayed
    }

    private suspend fun loadMood(now: Long): BotMood {
        val row = moodDao.get() ?: return BotMood(updatedAt = now)
        val decayed = MoodPolicy.decay(row.toModel(), now)
        if (decayed.value != row.value) moodDao.put(decayed.toEntity())
        return decayed
    }

    override suspend fun record(
        message: IncomingMessage,
        event: RelationEvent,
        note: String?,
    ) {
        val userId = message.senderId?.takeIf { it.isNotBlank() } ?: return
        mutex.withLock {
            val now = clock()
            val current = affinityDao.find(userId)?.toModel()
            val next =
                RelationPolicy.apply(
                    current = current,
                    userId = userId,
                    displayName = message.senderName,
                    event = event,
                    at = now,
                    note = note,
                )
            affinityDao.upsert(next.toEntity(now))

            val mood = moodDao.get()?.toModel() ?: BotMood(updatedAt = now)
            val nextMood = MoodPolicy.apply(mood, event, now)
            if (nextMood != mood) moodDao.put(nextMood.toEntity())
        }
    }

    /** 关系列表（按好感降序），维护端用。 */
    fun observeRelations(limit: Int = 50): Flow<List<PersonRelation>> =
        affinityDao.observeTop(limit).map { rows -> rows.map { it.toModel() } }

    suspend fun relationOf(userId: String): PersonRelation? = affinityDao.find(userId)?.toModel()

    suspend fun forgetRelation(userId: String) = affinityDao.delete(userId)

    suspend fun allRelations(): List<PersonRelation> = affinityDao.all().map { it.toModel() }

    /** 备份导入：原样写回，不走 RelationPolicy（那会按事件再改一次数值） */
    suspend fun putRelation(relation: PersonRelation) {
        affinityDao.upsert(relation.toEntity(clock()))
    }

    /** 备份导入：原样写回，不累加 hits */
    suspend fun putSlang(term: SlangTerm) {
        slangDao.upsert(term.copy(id = 0).toEntity())
    }

    suspend fun clearRelations() = affinityDao.clear()

    suspend fun currentMood(): BotMood = moodDao.get()?.toModel() ?: BotMood()

    /** 手动调心情（维护端"哄一下 / 气一下"），也用于测试 */
    suspend fun setMood(value: Int, reason: String? = null) {
        moodDao.put(
            BotMoodEntity(
                value = value.coerceIn(0, 100),
                updatedAt = clock(),
                reason = reason,
            ),
        )
    }

    // ---- 梗库 ----

    fun observeSlang(): Flow<List<SlangTerm>> =
        slangDao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun allSlang(): List<SlangTerm> = slangDao.all().map { it.toModel() }

    suspend fun promptSlang(groupId: String?, limit: Int = 8): List<SlangTerm> =
        slangDao.promptTerms(groupId, limit).map { it.toModel() }

    /**
     * 写入一条梗。同名同群视为同一条，累加出现次数而不是堆重复行。
     * @return 落库后的 id
     */
    suspend fun upsertSlang(term: SlangTerm): Long {
        val now = clock()
        val existing = slangDao.find(term.term, term.scopeGroupId)
        return if (existing == null) {
            slangDao.upsert(term.copy(createdAt = now, updatedAt = now).toEntity())
        } else {
            slangDao.upsert(
                existing.copy(
                    meaning = term.meaning.ifBlank { existing.meaning },
                    usage = term.usage ?: existing.usage,
                    hits = existing.hits + term.hits.coerceAtLeast(1),
                    enabled = true,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun setSlangEnabled(id: Long, enabled: Boolean) =
        slangDao.setEnabled(id, enabled, clock())

    suspend fun deleteSlang(id: Long) = slangDao.delete(id)

    suspend fun clearSlang() = slangDao.clear()

    suspend fun trimSlang(keep: Int) = slangDao.trim(keep)

    // ---- 表情包库 ----

    fun observeMemes(): Flow<List<MemeImage>> =
        memeDao?.observeAll()?.map { rows -> rows.map { it.toModel() } } ?: kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun allMemes(): List<MemeImage> =
        runCatching { memeDao?.all()?.map { it.toModel() } ?: emptyList() }.getOrDefault(emptyList())

    suspend fun promptMemes(limit: Int = 8): List<String> =
        runCatching { memeDao?.promptImages(limit)?.map { it.url } ?: emptyList() }
            .getOrDefault(emptyList())

    /**
     * 群里见到一张图，收进来。
     *
     * 重复的图不堆行：同一地址再次出现只累加 hits——
     * 一张被反复发的图正是"这群人爱用"的信号，比十张一次性图有价值。
     * @return true 表示是新入库的图
     */
    suspend fun harvestMeme(url: String): Boolean {
        val dao = memeDao ?: return false
        val clean = url.trim()
        if (clean.isBlank()) return false
        return mutex.withLock {
            val existing = dao.find(clean)
            if (existing != null) {
                dao.bumpHits(existing.id, 1)
                false
            } else {
                dao.upsert(
                    MemeEntity(
                        url = clean,
                        source = MemeImage.SOURCE_HARVEST,
                        createdAt = clock(),
                    ),
                )
                true
            }
        }
    }

    /** 维护端手动贴一张图进来。 */
    suspend fun addMeme(url: String): Long? {
        val dao = memeDao ?: return null
        val clean = url.trim()
        if (clean.isBlank()) return null
        return mutex.withLock {
            val existing = dao.find(clean)
            if (existing != null) {
                dao.bumpHits(existing.id, 1)
                existing.id
            } else {
                dao.upsert(
                    MemeEntity(
                        url = clean,
                        source = MemeImage.SOURCE_MANUAL,
                        createdAt = clock(),
                    ),
                )
            }
        }
    }

    suspend fun deleteMeme(id: Long) = memeDao?.delete(id)

    suspend fun clearMemes() = memeDao?.clear()

    /** 图库上限：比梗库宽松——图不占上下文，留多点选择面。 */
    suspend fun trimMemes(keep: Int) = memeDao?.trim(keep)

    /** 备份导入：原样写回。 */
    suspend fun putMeme(meme: MemeImage) {
        memeDao?.upsert(meme.copy(id = 0).toEntity())
    }
}

private fun MemeEntity.toModel() =
    MemeImage(id = id, url = url, source = source, hits = hits, createdAt = createdAt)

private fun MemeImage.toEntity() =
    MemeEntity(id = id, url = url, source = source, hits = hits, createdAt = createdAt)

private fun AffinityEntity.toModel() =
    PersonRelation(
        userId = userId,
        displayName = displayName,
        familiarity = familiarity,
        affinity = affinity,
        interactionCount = interactionCount,
        lastInteractionAt = lastInteractionAt,
        lastNote = lastNote,
    )

private fun PersonRelation.toEntity(now: Long) =
    AffinityEntity(
        userId = userId,
        displayName = displayName,
        familiarity = familiarity,
        affinity = affinity,
        interactionCount = interactionCount,
        lastInteractionAt = lastInteractionAt,
        lastNote = lastNote,
        updatedAt = now,
    )

private fun BotMoodEntity.toModel() = BotMood(value = value, updatedAt = updatedAt, reason = reason)

private fun BotMood.toEntity() = BotMoodEntity(value = value, updatedAt = updatedAt, reason = reason)

private fun SlangEntity.toModel() =
    SlangTerm(
        id = id,
        term = term,
        meaning = meaning,
        usage = usage,
        scopeGroupId = scopeGroupId,
        hits = hits,
        enabled = enabled,
        source = source,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun SlangTerm.toEntity() =
    SlangEntity(
        id = id,
        term = term,
        meaning = meaning,
        usage = usage,
        scopeGroupId = scopeGroupId,
        hits = hits,
        enabled = enabled,
        source = source,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
