package com.qbot.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 运行日志。
 *
 * 手机端不仅要跑机器人，还要当维护端看日志，
 * 因此日志落库、按时间倒序展示，并定期清理避免无限增长。
 */
@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val level: String,
    val tag: String,
    val text: String,
)

@Dao
interface LogDao {
    @Query("SELECT * FROM logs ORDER BY timestampMillis DESC LIMIT :limit")
    fun recent(limit: Int = 300): Flow<List<LogEntity>>

    @Insert
    suspend fun insert(entry: LogEntity)

    /** 清理过期日志，默认保留最近 3 天。 */
    @Query("DELETE FROM logs WHERE timestampMillis < :before")
    suspend fun trimBefore(before: Long)

    @Query("DELETE FROM logs")
    suspend fun clear()
}

/**
 * 自动回复规则。
 *
 * 以枚举的 name 存储，避免 Room 需要额外的类型转换器；
 * 读取时再映射回 [com.qbot.core.engine.MatchType] 等类型。
 */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val matchType: String,
    val pattern: String,
    val reply: String,
    val scope: String,
    val priority: Int,
)

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY priority DESC, name ASC")
    fun all(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE enabled = 1 ORDER BY priority DESC")
    fun enabledRules(): Flow<List<RuleEntity>>

    @Upsert
    suspend fun upsert(rule: RuleEntity)

    @Delete
    suspend fun delete(rule: RuleEntity)

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun findById(id: String): RuleEntity?
}

/**
 * AI 会话历史。
 *
 * 按 conversationKey 分桶（群/单聊/频道各自隔离），
 * 倒序查询取最近 N 条，写入时按正序追加。
 */
@Entity(tableName = "ai_messages")
data class AiMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationKey: String,
    /** user / assistant / system */
    val role: String,
    val content: String,
    val timestampMillis: Long,
)

@Dao
interface AiHistoryDao {
    @Query("SELECT * FROM ai_messages WHERE conversationKey = :key ORDER BY id DESC LIMIT :limit")
    suspend fun recent(
        key: String,
        limit: Int,
    ): List<AiMessageEntity>

    @Insert
    suspend fun insert(message: AiMessageEntity)

    @Query("DELETE FROM ai_messages WHERE conversationKey = :key")
    suspend fun clear(key: String)

    /** 只保留每个会话最近的 keep 条，清理更早的历史 */
    @Query(
        """
        DELETE FROM ai_messages
        WHERE conversationKey = :key
          AND id NOT IN (
              SELECT id FROM ai_messages WHERE conversationKey = :key ORDER BY id DESC LIMIT :keep
          )
        """,
    )
    suspend fun trim(
        key: String,
        keep: Int,
    )

    /** 所有出现过的会话 key，供 Kotlin 侧逐个 trim（避免用到 SQLite 窗口函数） */
    @Query("SELECT DISTINCT conversationKey FROM ai_messages")
    suspend fun allKeys(): List<String>
}

@Database(
    entities = [LogEntity::class, RuleEntity::class, AiMessageEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    abstract fun ruleDao(): RuleDao

    abstract fun aiHistoryDao(): AiHistoryDao
}
