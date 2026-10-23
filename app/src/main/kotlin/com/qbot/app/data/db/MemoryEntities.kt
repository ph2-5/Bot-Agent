package com.qbot.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 身份名录：senderId → 展示名。
 *
 * QQ 官方不提供群成员昵称查询接口，展示名只能来自用户自称、
 * 维护端手动备注、平台自带昵称（仅频道）三个渠道，
 * 因此本地必须维护这张表。
 */
@Entity(tableName = "identities")
data class IdentityEntity(
    @PrimaryKey val senderId: String,
    val displayName: String,
    /** NameSource 的 name：DECLARED / MANUAL / PLATFORM */
    val source: String,
    val updatedAt: Long,
)

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identities WHERE senderId = :senderId")
    suspend fun find(senderId: String): IdentityEntity?

    /** 批量查名字：组装群画像时一次查完，避免 N 次单查 */
    @Query("SELECT * FROM identities WHERE senderId IN (:senderIds)")
    suspend fun findMany(senderIds: List<String>): List<IdentityEntity>

    @Upsert
    suspend fun upsert(entry: IdentityEntity)

    @Query("DELETE FROM identities WHERE senderId = :senderId")
    suspend fun delete(senderId: String)

    /** 最近互动过的人（维护端「快速选择」用） */
    @Query("SELECT * FROM identities ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<IdentityEntity>

    @Query("SELECT COUNT(*) FROM identities")
    suspend fun count(): Int
}

/**
 * 关于某个人的持久事实（长期记忆）。
 *
 * 与 ai_messages 分开：历史是短期按线程，事实是跨对话按人。
 */
@Entity(
    tableName = "facts",
    indices = [Index("senderId")],
)
data class FactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senderId: String,
    val text: String,
    /** FactKind 的 name */
    val kind: String,
    /** FactSource 的 name */
    val source: String,
    val updatedAt: Long,
)

@Dao
interface FactDao {
    @Query("SELECT * FROM facts WHERE senderId = :senderId ORDER BY updatedAt DESC, id DESC")
    suspend fun forSender(senderId: String): List<FactEntity>

    @Insert
    suspend fun insert(fact: FactEntity): Long

    @Query("DELETE FROM facts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM facts WHERE senderId = :senderId")
    suspend fun deleteAllForSender(senderId: String)

    /** 只保留某个sender最近更新的 keep 条 */
    @Query(
        """
        DELETE FROM facts
        WHERE senderId = :senderId
          AND id NOT IN (
              SELECT id FROM facts WHERE senderId = :senderId
              ORDER BY updatedAt DESC, id DESC LIMIT :keep
          )
        """,
    )
    suspend fun trimTo(
        senderId: String,
        keep: Int,
    )

    @Query("SELECT DISTINCT senderId FROM facts")
    suspend fun allSenders(): List<String>

    @Query("SELECT COUNT(*) FROM facts")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM facts WHERE senderId = :senderId")
    suspend fun countForSender(senderId: String): Int
}

/**
 * 共享群聊背景：按人隔离记忆后，跨人指代（「那他呢」）的上下文。
 *
 * scopeKey 形如 g:群openid / u:用户openid / c:频道id。
 */
@Entity(
    tableName = "room_messages",
    indices = [Index("scopeKey")],
)
data class RoomMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scopeKey: String,
    /** 发送者 senderId；assistant 消息为 null */
    val senderId: String?,
    val senderName: String?,
    /** user / assistant */
    val role: String,
    val content: String,
    val timestampMillis: Long,
    /** 是否 @ 了机器人 */
    val isAt: Boolean = false,
    /** 平台消息 id（回填结论用） */
    val externalId: String? = null,
    /** 处理结论：已回复 / 冷却中 / 概率未命中 / AI 判断不接 */
    val decision: String? = null,
    /**
     * 写这条消息时的人格；只有 assistant（机器人自己）行会被标记。
     *
     * 群友说的话永远属于公共背景（谁说的就是谁说的），不该随人设消失；
     * 机器人自己的发言则要记下"当时是谁在说"，开启隔离后新人格
     * 不会接着旧人格的话茬继续聊。
     */
    val personaId: Long? = null,
)

@Dao
interface RoomMessageDao {
    /**
     * 取某个会话最近的背景消息。
     *
     * [personaId] 非空（开启人设隔离）时，机器人自己的旧发言只保留
     * 属于当前人格的那些；群友发言始终保留——那是公共对话。
     */
    @Query(
        "SELECT * FROM room_messages WHERE scopeKey = :scopeKey " +
            "AND (:personaId IS NULL OR role != 'assistant' OR personaId = :personaId) " +
            "ORDER BY id DESC LIMIT :limit",
    )
    suspend fun recent(
        scopeKey: String,
        limit: Int,
        personaId: Long?,
    ): List<RoomMessageEntity>

    /**
     * 实时观察某个范围的消息流（维护端「群消息」查看器用）。
     *
     * 倒序取最近 N 条，UI 再反转成正序——这样 limit 限制的是"最近"而不是"最早"。
     */
    @Query("SELECT * FROM room_messages WHERE scopeKey = :scopeKey ORDER BY id DESC LIMIT :limit")
    fun observeRecent(
        scopeKey: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<RoomMessageEntity>>

    @Insert
    suspend fun insert(message: RoomMessageEntity)

    @Query(
        """
        DELETE FROM room_messages
        WHERE scopeKey = :scopeKey
          AND id NOT IN (
              SELECT id FROM room_messages WHERE scopeKey = :scopeKey
              ORDER BY id DESC LIMIT :keep
          )
        """,
    )
    suspend fun trim(
        scopeKey: String,
        keep: Int,
    )

    @Query("SELECT DISTINCT scopeKey FROM room_messages")
    suspend fun allScopes(): List<String>

    // ---------- 人格成长的"新增消息"统计 ----------

    /** 全局最大 id：成长游标的初值 */
    @Query("SELECT MAX(id) FROM room_messages")
    suspend fun maxId(): Long?

    /**
     * 自游标以来的新增消息数。
     *
     * trim 只删 id 更小的旧行、id 单调递增，所以按 id 计数不会被 trim 影响——
     * 这正是用游标而不是时间窗的原因（三天不开机、一开涌进 500 条时，
     * 时间窗会骗着连跑多次成长）。
     */
    @Query("SELECT COUNT(*) FROM room_messages WHERE id > :afterId")
    suspend fun countSince(afterId: Long): Int

    /** 自游标以来最活跃的几个会话范围，用于跨群均衡取样 */
    @Query(
        "SELECT scopeKey FROM room_messages WHERE id > :afterId " +
            "GROUP BY scopeKey ORDER BY COUNT(*) DESC LIMIT :limit",
    )
    suspend fun topScopesSince(
        afterId: Long,
        limit: Int,
    ): List<String>

    @Query(
        "SELECT * FROM room_messages WHERE scopeKey = :scopeKey AND id > :afterId " +
            "ORDER BY id DESC LIMIT :limit",
    )
    suspend fun recentSince(
        scopeKey: String,
        afterId: Long,
        limit: Int,
    ): List<RoomMessageEntity>

    /** 回填处理结论：判断耗时较长，先入库再更新，查看器才能显示"为什么没回" */
    @Query(
        "UPDATE room_messages SET decision = :decision " +
            "WHERE scopeKey = :scopeKey AND externalId = :externalId",
    )
    suspend fun updateDecision(
        scopeKey: String,
        externalId: String,
        decision: String,
    )
}

/**
 * 人设（角色卡 / 人格档案）。
 *
 * 2.3.0 起这张表同时承担两件事：
 * - `kind = PROFILE`：完整的人格档案，可以有多份，但只有一条 [activeSlot] = 1；
 * - `kind = OVERRIDE`：绑定到某个人 / 某个群的局部覆盖，永远优先于档案。
 *
 * 唯一索引 + 可空槽位：激活行写 1、其余写 NULL。SQLite 的唯一索引允许多个 NULL，
 * 于是"最多一条激活"成了数据库级约束，不靠代码自觉。
 */
@Entity(
    tableName = "personas",
    indices = [Index(value = ["activeSlot"], unique = true)],
)
data class PersonaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 绑定的 senderId；null 表示不绑定到具体某个人 */
    val senderId: String?,
    /** 绑定的群/频道 openid；null 表示不绑定到具体群 */
    val scopeId: String?,
    val name: String,
    /** 人格正文（六段，老档案为五段）；EMBRYO 阶段为空 */
    val prompt: String,
    val updatedAt: Long,
    /** PROFILE / OVERRIDE */
    @ColumnInfo(defaultValue = "OVERRIDE") val kind: String = "OVERRIDE",
    /** EMBRYO / GROWING / MATURE / MANUAL */
    @ColumnInfo(defaultValue = "MANUAL") val stage: String = "MANUAL",
    val seedId: String? = null,
    val activeSlot: Int? = null,
    @ColumnInfo(defaultValue = "0") val frozen: Boolean = false,
    @ColumnInfo(defaultValue = "0") val birthAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastGrownAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val growthCount: Int = 0,
    /** 上次成长时的 room_messages 最大 id */
    @ColumnInfo(defaultValue = "0") val msgCursor: Long = 0,
    @ColumnInfo(defaultValue = "") val lastNote: String = "",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
)

@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas ORDER BY updatedAt DESC")
    fun all(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas ORDER BY updatedAt DESC")
    suspend fun allOnce(): List<PersonaEntity>

    @Upsert
    suspend fun upsert(persona: PersonaEntity): Long

    @Query("DELETE FROM personas WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun findById(id: Long): PersonaEntity?

    // ---------- 人格档案 ----------

    @Query("SELECT * FROM personas WHERE activeSlot = 1 LIMIT 1")
    fun observeActive(): Flow<PersonaEntity?>

    @Query("SELECT * FROM personas WHERE activeSlot = 1 LIMIT 1")
    suspend fun active(): PersonaEntity?

    @Query("SELECT * FROM personas WHERE kind = 'PROFILE' ORDER BY activeSlot DESC, createdAt ASC")
    fun profiles(): Flow<List<PersonaEntity>>

    @Query("SELECT COUNT(*) FROM personas WHERE kind = 'PROFILE'")
    suspend fun countProfiles(): Int

    @Query("UPDATE personas SET activeSlot = NULL WHERE activeSlot IS NOT NULL")
    suspend fun clearActive()

    @Query("UPDATE personas SET activeSlot = 1, updatedAt = :at WHERE id = :id")
    suspend fun setActive(id: Long, at: Long)

    /**
     * 切换激活档案。
     *
     * 必须先清空再置位：单条 `CASE WHEN` 更新在 SQLite 里是逐行执行的，
     * 给新行写 1 的瞬间旧行还是 1，唯一索引会直接报冲突。
     */
    @Transaction
    suspend fun switchActive(id: Long, at: Long) {
        clearActive()
        setActive(id, at)
    }

    /** 激活行不可删，否则会出现"没有激活却还有档案"的中空态 */
    @Query("DELETE FROM personas WHERE id = :id AND activeSlot IS NULL")
    suspend fun deleteIfInactive(id: Long): Int

    /**
     * 只推进游标，不动人格。
     *
     * 模型看完这批消息说「没什么可学的」时用它：既避免同样的消息被反复送去问，
     * 又不会因为一次 NO_CHANGE 就假装"成长过"（growthCount 与 lastGrownAt 都不变）。
     */
    @Query("UPDATE personas SET msgCursor = :cursor WHERE id = :id")
    suspend fun advanceCursor(
        id: Long,
        cursor: Long,
    )

    /** 冻结 / 解冻、重抽种子、转手动等元数据更新 */
    @Query(
        "UPDATE personas SET stage = :stage, seedId = :seedId, frozen = :frozen, " +
            "updatedAt = :at WHERE id = :id",
    )
    suspend fun updateMeta(
        id: Long,
        stage: String,
        seedId: String?,
        frozen: Boolean,
        at: Long,
    )

    /**
     * 破壳写回。
     *
     * `AND stage = 'EMBRYO'` 是防重复破壳的第二道闸（第一道是调度器里的 Mutex）：
     * 两次并发破壳时，第二次必然影响 0 行。
     */
    @Query(
        "UPDATE personas SET prompt = :prompt, stage = 'GROWING', seedId = :seedId, " +
            "msgCursor = :cursor, growthCount = 1, birthAt = :at, lastGrownAt = :at, " +
            "lastNote = :note, updatedAt = :at WHERE id = :id AND stage = 'EMBRYO'",
    )
    suspend fun commitBirth(
        id: Long,
        prompt: String,
        seedId: String?,
        cursor: Long,
        at: Long,
        note: String,
    ): Int

    /**
     * 成长写回。
     *
     * `AND updatedAt = :expected` 是 CAS：期间用户手改过（updatedAt 变了），
     * 本次成长结果直接丢弃——手改永远赢。
     */
    @Query(
        "UPDATE personas SET prompt = :prompt, stage = :stage, msgCursor = :cursor, " +
            "growthCount = growthCount + 1, lastGrownAt = :at, lastNote = :note, " +
            "updatedAt = :at WHERE id = :id AND updatedAt = :expected",
    )
    suspend fun commitGrowth(
        id: Long,
        prompt: String,
        stage: String,
        cursor: Long,
        at: Long,
        note: String,
        expected: Long,
    ): Int
}

/**
 * 人设版本快照。
 *
 * 每次 AI 进化前后各存一版，初始版本（INITIAL）在第一次进化前自动写入，
 * 不可删除——这是"人设被改坏还能回去"的最后一道保险。
 *
 * personaId 为 null 表示全局系统提示词（AiConfig.systemPrompt）。
 */
@Entity(
    tableName = "persona_snapshots",
    indices = [Index("personaId")],
)
data class PersonaSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personaId: Long?,
    val name: String,
    val prompt: String,
    /** INITIAL / EVOLVED / MANUAL */
    val kind: String,
    val note: String,
    val createdAt: Long,
)

@Dao
interface PersonaSnapshotDao {
    @Query("SELECT * FROM persona_snapshots WHERE personaId IS :personaId ORDER BY createdAt DESC")
    fun observeFor(personaId: Long?): Flow<List<PersonaSnapshotEntity>>

    @Query("SELECT * FROM persona_snapshots WHERE personaId IS :personaId ORDER BY createdAt DESC")
    suspend fun listFor(personaId: Long?): List<PersonaSnapshotEntity>

    @Query(
        "SELECT COUNT(*) FROM persona_snapshots WHERE personaId IS :personaId AND kind = 'INITIAL'",
    )
    suspend fun countInitial(personaId: Long?): Int

    @Insert
    suspend fun insert(snapshot: PersonaSnapshotEntity): Long

    @Query("DELETE FROM persona_snapshots WHERE id = :id AND kind != 'INITIAL'")
    suspend fun deleteNonInitial(id: Long)

    /**
     * 删掉某个目标的初始快照。
     *
     * 只在「重抽种子」时用：换了一个性格原点之后，旧骨架不能再当作"最初的样子"，
     * 否则用户点回滚会还原成上一个种子，而新种子永远没有初始版本。
     */
    @Query("DELETE FROM persona_snapshots WHERE personaId IS :personaId AND kind = 'INITIAL'")
    suspend fun deleteInitial(personaId: Long?)

    /** 只保留最近 N 条非初始版本，防止无限增长 */
    @Query(
        "DELETE FROM persona_snapshots WHERE personaId IS :personaId AND kind != 'INITIAL' " +
            "AND id NOT IN (SELECT id FROM persona_snapshots WHERE personaId IS :personaId " +
            "ORDER BY createdAt DESC LIMIT :keep)",
    )
    suspend fun trim(personaId: Long?, keep: Int)
}
