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
import com.qbot.core.ai.MemeLibrary
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
    /** 人设设定：角色卡里写死的「认识的人 / 说法」，与聊天中学到的梗互补 */
    private val loreDao: com.qbot.app.data.db.LoreDao? = null,
    /**
     * 逐消息解析「当前是哪个人格」；传 null 表示问「当前激活的是谁」。
     *
     * 必须是逐消息的：某个群 / 某个人挂了覆盖层人设时，提示词用的是覆盖层，
     * 而过去这里只取 `active()`——设定、关系与人格会对不上（见审计 A1）。
     */
    private val personaIdProvider: (suspend (IncomingMessage?) -> Long?)? = null,
    /**
     * 是否按人设隔离「关系 / 内心戏」。
     *
     * 关闭时一律落到共用视角 0：换人设只换语气，认识的人还是同一批。
     * 开启后各人格各攒各的印象。人设设定（lore）不受这个开关影响——
     * 设定本身就是人格的一部分，必须跟着本条消息实际扮演的人格走。
     */
    private val isolateMemories: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) : SocialContextProvider, RelationRecorder, MemeLibrary {
    /** 读-改-写要串行，否则并发两条消息会把彼此的增量覆盖掉 */
    private val mutex = Mutex()

    /** 这条消息视角下的人格；null = 解析失败 / 无人格 */
    private suspend fun personaFor(message: IncomingMessage?): Long? =
        runCatching { personaIdProvider?.invoke(message) }.getOrNull()

    /**
     * 关系主键里的视角：未开隔离时固定 0（共用关系），
     * 开启后拿不到人格也落到 0，避免把数据写到不存在的人设上。
     *
     * 0 不是一个真实人格的 id（自增从 1 开始），因此不会与档案冲突。
     */
    private suspend fun personaKey(message: IncomingMessage? = null): Long =
        if (!isolateMemories()) 0L else personaFor(message) ?: 0L

    override suspend fun provide(message: IncomingMessage): SocialContext {
        val now = clock()
        val userId = message.senderId?.takeIf { it.isNotBlank() }
        val personaId = personaFor(message)
        val scopeId = if (isolateMemories()) personaId ?: 0L else 0L
        val relation = userId?.let { loadRelation(it, now, scopeId) }
        val mood = loadMood(now)
        val groupId = message.targetId?.takeIf { it.isNotBlank() }
        val slang =
            runCatching { slangDao.promptTerms(groupId, 8).map { it.toModel() } }
                .getOrDefault(emptyList())
        // 候选表情包：全局池，取最常用的几张
        val memes =
            runCatching { memeDao?.promptImages(8)?.map { it.url } ?: emptyList() }
                .getOrDefault(emptyList())
        // 上一次对这个人的内心：让"我忍过你"成为关系的一部分。
        // 看共用视角还是人格视角，跟关系表保持一致。
        val lastThought =
            if (userId == null) {
                null
            } else {
                runCatching {
                    thoughtDao?.lastFor(
                        senderId = userId,
                        personaId = scopeId.takeIf { isolateMemories() },
                        // 同一会话内的内心才有意义：A 群忍住的话不该带到 B 群
                        groupId = groupId,
                    )?.thought?.take(120)
                }.getOrNull()
            }
        // 人设设定（认识的人 / 雷点 / 特殊说法）：按本条消息解析出的人格取，
        // 与提示词里实际扮演的角色同源，覆盖层人设也能拿到自己的设定。
        // 这里不受隔离开关影响：设定是人格的一部分。
        val dao = loreDao
        val lore =
            if (dao == null) {
                emptyList()
            } else {
                runCatching {
                    dao.forPersona(personaId, groupId).map { it.toModel() }
                }.getOrDefault(emptyList())
            }
        return SocialContext(
            relation = relation,
            mood = mood,
            slang = slang,
            memes = memes,
            lore = lore,
            lastThought = lastThought?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun loadRelation(
        userId: String,
        now: Long,
        personaId: Long,
    ): PersonRelation? {
        val row = affinityDao.find(personaId, userId) ?: return null
        val model = row.toModel()
        val decayed = RelationPolicy.decay(model, now)
        // 只在真的衰减时才写：读路径不能变成写路径
        if (decayed.affinity != model.affinity) {
            affinityDao.upsert(decayed.toEntity(now, personaId))
        }
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
            val personaId = personaKey(message)
            val current = affinityDao.find(personaId, userId)?.toModel()
            val next =
                RelationPolicy.apply(
                    current = current,
                    userId = userId,
                    displayName = message.senderName,
                    event = event,
                    at = now,
                    note = note,
                )
            affinityDao.upsert(next.toEntity(now, personaId))

            val mood = moodDao.get()?.toModel() ?: BotMood(updatedAt = now)
            val nextMood = MoodPolicy.apply(mood, event, now)
            if (nextMood != mood) moodDao.put(nextMood.toEntity())
        }
    }

    /**
     * 关系列表（按好感降序），维护端用。
     *
     * 跟随「当前视角」：未开隔离时是共用那一份；开启后切到哪个档案就显示
     * 哪个档案攒下的关系。用 flow 包一层是因为解析人格本身是挂起操作。
     */
    fun observeRelations(limit: Int = 50): Flow<List<PersonRelation>> =
        flow {
            val key = personaKey(null)
            emitAll(affinityDao.observeTop(key, limit))
        }.map { rows -> rows.map { it.toModel() } }

    suspend fun relationOf(userId: String): PersonRelation? =
        affinityDao.find(personaKey(null), userId)?.toModel()

    suspend fun forgetRelation(userId: String) =
        affinityDao.delete(personaKey(null), userId)

    /**
     * 所有视角的关系行（备份导出用，不丢任何人格攒下的印象）。
     *
     * 用 (personaId, relation) 返回而不是裸关系：备份要按人格名重建视角，
     * 只导数值会在换机后把各人格的关系糊成一份。
     */
    suspend fun allRelations(): List<Pair<Long, PersonRelation>> =
        affinityDao.all().map { it.personaId to it.toModel() }

    /** 备份导入：原样写回，不走 RelationPolicy（那会按事件再改一次数值） */
    suspend fun putRelation(
        relation: PersonRelation,
        personaId: Long = 0,
    ) {
        affinityDao.upsert(relation.toEntity(clock(), personaId))
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

    /** 模型给的地址必须真的在库里，防止它编造 URL 或被人用提示词注入外链。 */
    override suspend fun contains(url: String): Boolean {
        val dao = memeDao ?: return false
        val clean = url.trim()
        if (clean.isBlank()) return false
        return runCatching { dao.find(clean) != null }.getOrDefault(false)
    }

    /** 采用一次：只累加 hits，重复地址不会新增行。 */
    override suspend fun recordUse(url: String) {
        val dao = memeDao ?: return
        val clean = url.trim()
        if (clean.isBlank()) return
        runCatching {
            dao.find(clean)?.let { dao.bumpHits(it.id, 1) }
        }
    }

    /**
     * 明确要图时挑一张。
     *
     * 从常用的前 20 张里随机选，而不是永远取第一名——
     * 固定第一张会让机器人翻来覆去只发同一张，群里很快就看腻了。
     */
    override suspend fun pick(): String? =
        runCatching {
            memeDao?.promptImages(20)?.randomOrNull()?.url
        }.getOrNull()?.takeIf { it.isNotBlank() }

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

private fun PersonRelation.toEntity(
    now: Long,
    personaId: Long = 0,
) =
    AffinityEntity(
        personaId = personaId,
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
