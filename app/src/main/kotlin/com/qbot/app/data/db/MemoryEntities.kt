package com.qbot.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
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
)

@Dao
interface RoomMessageDao {
    @Query("SELECT * FROM room_messages WHERE scopeKey = :scopeKey ORDER BY id DESC LIMIT :limit")
    suspend fun recent(
        scopeKey: String,
        limit: Int,
    ): List<RoomMessageEntity>

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
}

/**
 * 人设（角色卡）。
 *
 * senderId / scopeId 的绑定组合决定生效范围，解析优先级为
 * 人 → 群 → 全局（见 botcore 的 resolvePersona）。
 */
@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 绑定的 senderId；null 表示不绑定到具体某个人 */
    val senderId: String?,
    /** 绑定的群/频道 openid；null 表示不绑定到具体群 */
    val scopeId: String?,
    val name: String,
    val prompt: String,
    val updatedAt: Long,
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

    /** 只保留最近 N 条非初始版本，防止无限增长 */
    @Query(
        "DELETE FROM persona_snapshots WHERE personaId IS :personaId AND kind != 'INITIAL' " +
            "AND id NOT IN (SELECT id FROM persona_snapshots WHERE personaId IS :personaId " +
            "ORDER BY createdAt DESC LIMIT :keep)",
    )
    suspend fun trim(personaId: Long?, keep: Int)
}
