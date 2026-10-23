package com.qbot.app.data.repo

import com.qbot.app.data.db.ChimeDecisionDao
import com.qbot.app.data.db.ChimeDecisionEntity
import com.qbot.app.data.db.ChimeLessonDao
import com.qbot.app.data.db.ChimeLessonEntity
import com.qbot.app.data.db.InnerThoughtDao
import com.qbot.app.data.db.InnerThoughtEntity
import com.qbot.core.ai.ChimeDecision
import com.qbot.core.ai.ChimeDecisionRecord
import com.qbot.core.ai.ChimeDecisionStore
import com.qbot.core.ai.ChimeLesson
import com.qbot.core.ai.ChimeLessonStore
import com.qbot.core.ai.ChimeStats
import com.qbot.core.ai.ChimeTrainer
import com.qbot.core.ai.InnerThought
import com.qbot.core.ai.InnerThoughtSink
import com.qbot.core.ai.LessonSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 接话决策日志的 Room 实现。
 *
 * 除了接口要求的查询/统计，额外提供 [record]：它是训练闭环的写入口，
 * BotRuntime 通过 [RoomChimeTrainer] 调用。
 */
class RoomChimeDecisionStore(
    private val dao: ChimeDecisionDao,
) : ChimeDecisionStore {
    suspend fun record(record: ChimeDecisionRecord): Long = dao.insert(record.toEntity())

    override fun observe(groupId: String?, limit: Int): Flow<List<ChimeDecision>> =
        dao.observe(groupId, limit).map { rows -> rows.map { it.toModel() } }

    override suspend fun find(id: Long): ChimeDecision? = dao.find(id)?.toModel()

    override suspend fun markFeedback(
        id: Long,
        correct: Boolean,
        correctedAction: String?,
        note: String?,
        at: Long,
    ) {
        dao.markFeedback(id, correct, correctedAction, note, at)
    }

    override suspend fun stats(sinceMillis: Long): ChimeStats =
        ChimeStats(
            total = dao.countSince(sinceMillis),
            reviewed = dao.reviewedSince(sinceMillis),
            correct = dao.correctSince(sinceMillis),
            wrongSpoke = dao.wrongSpokeSince(sinceMillis),
            wrongSilent = dao.wrongSilentSince(sinceMillis),
        )

    override suspend fun trim(beforeMillis: Long, keep: Int) {
        dao.deleteBefore(beforeMillis)
        dao.trimTo(keep)
    }
}

/** 经验库的 Room 实现。 */
class RoomChimeLessonStore(
    private val dao: ChimeLessonDao,
    /**
     * 当前人格（开启隔离后用于合并同类经验、忽略其它人格的旧经验）。
     *
     * 返回 null 表示未开启隔离：经验是共用的，行为与旧版本一致。
     */
    private val personaIdProvider: (suspend () -> Long?)? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : ChimeLessonStore {
    override fun observeAll(): Flow<List<ChimeLesson>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun all(): List<ChimeLesson> = dao.all().map { it.toModel() }

    override suspend fun promptLessons(
        groupId: String,
        limit: Int,
        personaId: Long?,
    ): List<ChimeLesson> {
        // SQL 只做粗筛（启用 / 作用域 / 人格）并多取几倍，
        // 时间衰减与「每类最多 3 条」的配额统一由 LessonSelection 决定——
        // 过去配额只在内存实现里，生产链路其实没生效（见审计 B1）。
        val rows =
            dao.promptLessons(
                groupId = groupId,
                limit = (limit * 4).coerceAtLeast(limit).coerceAtMost(MAX_SCAN),
                personaId = personaId,
            ).map { it.toModel() }
        return LessonSelection.pick(rows, limit, groupId, personaId, now())
    }

    override suspend fun insert(lesson: ChimeLesson): Long = dao.insert(lesson.toEntity())

    override suspend fun updateText(id: Long, text: String, updatedAt: Long) {
        dao.updateText(id, text, updatedAt)
    }

    override suspend fun mergeInto(id: Long, text: String, supportDelta: Int, updatedAt: Long) {
        dao.mergeInto(id, text, supportDelta, updatedAt)
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        dao.setEnabled(id, enabled)
    }

    override suspend fun delete(id: Long) {
        dao.delete(id)
    }

    override suspend fun deleteMany(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        dao.deleteMany(ids.toList())
    }

    override suspend fun sameCategory(
        groupId: String?,
        category: String,
        personaId: Long?,
    ): List<ChimeLesson> =
        // 调用方给了人格就按它查；没给则沿用存储侧的当前视角
        dao.sameCategory(groupId, category, personaId ?: personaIdProvider?.invoke()).map { it.toModel() }

    override suspend fun bumpHits(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        dao.bumpHits(ids.toList())
    }

    override suspend fun trim(keep: Int) {
        dao.trimTo(keep)
    }

    companion object {
        /** SQL 粗筛上限：再大也不会一次注入这么多，只为给配额留出选择空间。 */
        private const val MAX_SCAN = 200
    }
}

/**
 * 训练器 = 决策落库 + 经验读取 + 命中计数。
 *
 * 现在直接组合两个仓储；未来加入情绪值/好感度时，在 BotController 里
 * 换成 `CompositeDecisionSink(listOf(trainer, moodUpdater, affinityUpdater))`，
 * BotRuntime 侧不需要改动。
 */
class RoomChimeTrainer(
    private val decisions: RoomChimeDecisionStore,
    private val lessons: RoomChimeLessonStore,
) : ChimeTrainer {
    override suspend fun onDecision(record: ChimeDecisionRecord) {
        decisions.record(record)
    }

    override suspend fun lessonsForPrompt(
        groupId: String,
        limit: Int,
        personaId: Long?,
    ): List<ChimeLesson> = lessons.promptLessons(groupId, limit, personaId)

    override suspend fun bumpHits(ids: Collection<Long>) {
        lessons.bumpHits(ids)
    }
}

// ---------- 映射 ----------

private fun ChimeDecisionRecord.toEntity() =
    ChimeDecisionEntity(
        groupId = groupId,
        externalId = externalId,
        speakerId = speakerId,
        speakerName = speakerName,
        messageText = messageText,
        contextSnippet = contextSnippet,
        ruleScore = ruleScore,
        gatePath = gatePath,
        aiVerdict = aiVerdict,
        aiReason = aiReason,
        action = action,
        reason = reason,
        replyText = replyText,
        addressed = addressed,
        insulted = insulted,
        addresseeKind = addresseeKind,
        addresseeName = addresseeName,
        personaId = personaId,
        createdAt = createdAt,
    )

private fun ChimeDecisionEntity.toModel() =
    ChimeDecision(
        id = id,
        record =
            ChimeDecisionRecord(
                groupId = groupId,
                speakerId = speakerId,
                speakerName = speakerName,
                messageText = messageText,
                contextSnippet = contextSnippet,
                ruleScore = ruleScore,
                gatePath = gatePath,
                aiVerdict = aiVerdict,
                aiReason = aiReason,
                action = action,
                reason = reason,
                replyText = replyText,
                addressed = addressed,
                insulted = insulted,
                addresseeKind = addresseeKind,
                addresseeName = addresseeName,
                personaId = personaId,
                externalId = externalId,
                createdAt = createdAt,
            ),
        feedback = feedback,
        correctedAction = correctedAction,
        feedbackNote = feedbackNote,
        feedbackAt = feedbackAt,
    )

private fun ChimeLesson.toEntity() =
    ChimeLessonEntity(
        id = id,
        text = text,
        source = source,
        scopeGroupId = scopeGroupId,
        fromDecisionId = fromDecisionId,
        hits = hits,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        category = category,
        supportCount = supportCount,
        personaId = personaId,
    )

private fun ChimeLessonEntity.toModel() =
    ChimeLesson(
        id = id,
        text = text,
        source = source,
        scopeGroupId = scopeGroupId,
        fromDecisionId = fromDecisionId,
        hits = hits,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        category = category,
        supportCount = supportCount,
        personaId = personaId,
    )

/**
 * 内心戏的 Room 出口。
 *
 * 上限 500 条：一天几十条够回看一周多，再多也没人翻。
 * 每写一条顺带 trim 一次——单条 DELETE 很轻，不值得为它单独起一个定期任务；
 * 长期挂机也不会把库撑到几十万行。
 */
class RoomInnerThoughtSink(
    private val dao: InnerThoughtDao,
    private val keep: Int = 500,
) : InnerThoughtSink {
    override suspend fun record(thought: InnerThought) {
        dao.insert(
            InnerThoughtEntity(
                groupId = thought.groupId,
                senderId = thought.senderId,
                senderName = thought.senderName,
                spoken = thought.spoken,
                thought = thought.thought,
                createdAt = thought.createdAt,
                personaId = thought.personaId,
            ),
        )
        dao.trim(keep)
    }

    fun observeRecent(limit: Int = 100): Flow<List<InnerThought>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toModel() } }

    suspend fun clear() = dao.clear()
}

private fun InnerThoughtEntity.toModel() =
    InnerThought(
        id = id,
        groupId = groupId,
        senderId = senderId,
        senderName = senderName,
        spoken = spoken,
        thought = thought,
        createdAt = createdAt,
        personaId = personaId,
    )
