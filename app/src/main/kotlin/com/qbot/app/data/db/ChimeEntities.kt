package com.qbot.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 插话决策日志（训练闭环的第一环）。
 *
 * 与 `room_messages.decision` 的区别：后者是"这条消息为什么没回"的一句话，
 * 会被 200 条滚动窗口挤掉；这里是结构化快照，保留 7 天供复盘，
 * 也是未来情绪值/好感度可以直接消费的事件源。
 */
@Entity(
    tableName = "chime_decisions",
    indices = [
        Index("groupId"),
        Index("createdAt"),
        Index("feedback"),
    ],
)
data class ChimeDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: String,
    val externalId: String? = null,
    /** 发言者 openid：好感度（路线图）未来按人累计 */
    val speakerId: String? = null,
    val speakerName: String? = null,
    val messageText: String,
    val contextSnippet: String,
    val ruleScore: Int? = null,
    /** FORCED / CANDIDATE / SKIP */
    val gatePath: String,
    /** YES / NO；未询问模型时为空 */
    val aiVerdict: String? = null,
    val aiReason: String? = null,
    /** SPOKE / SILENT */
    val action: String,
    val reason: String,
    val replyText: String? = null,
    @ColumnInfo(defaultValue = "0") val addressed: Boolean = false,
    @ColumnInfo(defaultValue = "0") val insulted: Boolean = false,
    /** 这句话大概率对谁说的：BOT / MEMBER / GROUP / UNKNOWN */
    val addresseeKind: String? = null,
    val addresseeName: String? = null,
    /** 做出判断时扮演的人格；null = 未开隔离 */
    val personaId: Long? = null,
    /** null = 未反馈；1 = 正确；0 = 错误 */
    val feedback: Boolean? = null,
    /** SHOULD_SPEAK / SHOULD_SILENT（仅错误时） */
    val correctedAction: String? = null,
    val feedbackNote: String? = null,
    val feedbackAt: Long? = null,
    val createdAt: Long,
)

/**
 * 从反馈学到的经验。
 *
 * `scopeGroupId` 为空表示全局；非空表示只在该群生效——
 * 不同群的文化差异很大，A 群学到的"别插话"搬到 B 群往往是错的。
 */
@Entity(
    tableName = "chime_lessons",
    indices = [Index("updatedAt")],
)
data class ChimeLessonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** REFLECTION / MANUAL */
    val source: String,
    val scopeGroupId: String? = null,
    val fromDecisionId: Long? = null,
    @ColumnInfo(defaultValue = "0") val hits: Int = 0,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    /** 目录类别（固定枚举，见 LessonCategories） */
    @ColumnInfo(defaultValue = "OTHER") val category: String = "OTHER",
    /** 被多少条反馈支撑：同类相似反馈累加，用于排序与淘汰 */
    @ColumnInfo(defaultValue = "1") val supportCount: Int = 1,
    /**
     * 归属人格；null 表示通用经验。
     *
     * 开启记忆隔离后，A 人设学到的「别抢话」不该作用到 B 人设——
     * 两者的性格与判定标准本来就不同。
     */
    val personaId: Long? = null,
)

@Dao
interface ChimeDecisionDao {
    @Insert
    suspend fun insert(row: ChimeDecisionEntity): Long

    @Query(
        "SELECT * FROM chime_decisions WHERE (:groupId IS NULL OR groupId = :groupId) " +
            "ORDER BY id DESC LIMIT :limit",
    )
    fun observe(groupId: String?, limit: Int): Flow<List<ChimeDecisionEntity>>

    @Query("SELECT * FROM chime_decisions WHERE id = :id")
    suspend fun find(id: Long): ChimeDecisionEntity?

    @Query(
        "UPDATE chime_decisions SET feedback = :correct, correctedAction = :action, " +
            "feedbackNote = :note, feedbackAt = :at WHERE id = :id",
    )
    suspend fun markFeedback(
        id: Long,
        correct: Boolean,
        action: String?,
        note: String?,
        at: Long,
    )

    @Query("SELECT COUNT(*) FROM chime_decisions WHERE createdAt >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM chime_decisions WHERE createdAt >= :since AND feedback IS NOT NULL")
    suspend fun reviewedSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM chime_decisions WHERE createdAt >= :since AND feedback = 1")
    suspend fun correctSince(since: Long): Int

    /** 误接：说了话但用户标错 */
    @Query(
        "SELECT COUNT(*) FROM chime_decisions " +
            "WHERE createdAt >= :since AND feedback = 0 AND action = 'SPOKE'",
    )
    suspend fun wrongSpokeSince(since: Long): Int

    /** 漏接：没说话但用户标错 */
    @Query(
        "SELECT COUNT(*) FROM chime_decisions " +
            "WHERE createdAt >= :since AND feedback = 0 AND action = 'SILENT'",
    )
    suspend fun wrongSilentSince(since: Long): Int

    @Query("DELETE FROM chime_decisions WHERE createdAt < :before")
    suspend fun deleteBefore(before: Long)

    @Query(
        "DELETE FROM chime_decisions WHERE id NOT IN " +
            "(SELECT id FROM chime_decisions ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)
}

@Dao
interface ChimeLessonDao {
    @Query("SELECT * FROM chime_lessons ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChimeLessonEntity>>

    @Query("SELECT * FROM chime_lessons ORDER BY updatedAt DESC")
    suspend fun all(): List<ChimeLessonEntity>

    /**
     * 注入用：本群专属排在前、全局排在后，各自按更新时间倒序。
     * SQLite 里布尔表达式返回 0/1，直接用来排序即可。
     *
     * [personaId] 为 null（未开隔离）时不过滤人格；非空时只取
     * 「归属该人格 + 通用」的经验。多取几倍由仓储侧做衰减与类别配额。
     */
    @Query(
        "SELECT * FROM chime_lessons WHERE enabled = 1 " +
            "AND (scopeGroupId IS NULL OR scopeGroupId = :groupId) " +
            "AND (:personaId IS NULL OR personaId IS NULL OR personaId = :personaId) " +
            "ORDER BY (scopeGroupId IS NOT NULL) DESC, supportCount DESC, updatedAt DESC " +
            "LIMIT :limit",
    )
    suspend fun promptLessons(
        groupId: String,
        limit: Int,
        personaId: Long?,
    ): List<ChimeLessonEntity>

    /** 同一作用域 + 同一类别 + 同一人格：入库前用它做合并判断 */
    @Query(
        "SELECT * FROM chime_lessons WHERE category = :category AND " +
            "((:groupId IS NULL AND scopeGroupId IS NULL) OR scopeGroupId = :groupId) AND " +
            "personaId IS :personaId " +
            "ORDER BY supportCount DESC, updatedAt DESC",
    )
    suspend fun sameCategory(
        groupId: String?,
        category: String,
        personaId: Long?,
    ): List<ChimeLessonEntity>

    @Query("SELECT * FROM chime_lessons WHERE id = :id")
    suspend fun find(id: Long): ChimeLessonEntity?

    @Query("SELECT COUNT(*) FROM chime_lessons")
    suspend fun count(): Int

    @Insert
    suspend fun insert(row: ChimeLessonEntity): Long

    @Query("UPDATE chime_lessons SET text = :text, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateText(id: Long, text: String, updatedAt: Long)

    @Query(
        "UPDATE chime_lessons SET text = :text, " +
            "supportCount = supportCount + :delta, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun mergeInto(id: Long, text: String, delta: Int, updatedAt: Long)

    @Query("UPDATE chime_lessons SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM chime_lessons WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM chime_lessons WHERE id IN (:ids)")
    suspend fun deleteMany(ids: List<Long>)

    @Query("UPDATE chime_lessons SET hits = hits + 1 WHERE id IN (:ids)")
    suspend fun bumpHits(ids: List<Long>)

    /** 容量淘汰：先丢支撑少、最久没更新的，把高价值经验留住 */
    @Query(
        "DELETE FROM chime_lessons WHERE id NOT IN " +
            "(SELECT id FROM chime_lessons ORDER BY supportCount DESC, updatedAt DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)
}
