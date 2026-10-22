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
    entities = [
        LogEntity::class,
        RuleEntity::class,
        AiMessageEntity::class,
        IdentityEntity::class,
        FactEntity::class,
        RoomMessageEntity::class,
        PersonaEntity::class,
        PersonaSnapshotEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        ChimeDecisionEntity::class,
        ChimeLessonEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    abstract fun ruleDao(): RuleDao

    abstract fun aiHistoryDao(): AiHistoryDao

    abstract fun identityDao(): IdentityDao

    abstract fun factDao(): FactDao

    abstract fun roomMessageDao(): RoomMessageDao

    abstract fun personaDao(): PersonaDao

    abstract fun personaSnapshotDao(): PersonaSnapshotDao

    abstract fun groupDao(): GroupDao

    abstract fun groupMemberDao(): GroupMemberDao

    abstract fun chimeDecisionDao(): ChimeDecisionDao

    abstract fun chimeLessonDao(): ChimeLessonDao

    companion object {
        /**
         * v2 → v3：记忆系统与人设表。
         *
         * 纯增量迁移（只建新表，不动 logs / rules / ai_messages），
         * 老用户的日志、规则、AI 历史全部保留。
         * 注意 ai_messages 里旧格式的会话键（按群不按人）会自然失效：
         * 新键按人隔离，旧桶不会再被读到，由 trimAll 定期收敛。
         */
        val MIGRATION_2_3 =
            object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS identities (
                            senderId TEXT NOT NULL PRIMARY KEY,
                            displayName TEXT NOT NULL,
                            source TEXT NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS facts (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            senderId TEXT NOT NULL,
                            text TEXT NOT NULL,
                            kind TEXT NOT NULL,
                            source TEXT NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_facts_senderId ON facts(senderId)")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS room_messages (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            scopeKey TEXT NOT NULL,
                            senderId TEXT,
                            senderName TEXT,
                            role TEXT NOT NULL,
                            content TEXT NOT NULL,
                            timestampMillis INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_room_messages_scopeKey ON room_messages(scopeKey)",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS personas (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            senderId TEXT,
                            scopeId TEXT,
                            name TEXT NOT NULL,
                            prompt TEXT NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
            }

        /**
         * v3 → v4：群感知表（群档案 + 群成员样本）。
         *
         * 同样是纯增量迁移：老用户的身份、事实、背景、人设全部保留。
         * 索引名必须与 Room 为实体生成的名字一致（index_group_members_groupId 等），
         * 否则打开数据库时 schema 校验会失败。
         */
        val MIGRATION_3_4 =
            object : androidx.room.migration.Migration(3, 4) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS groups (
                            groupId TEXT NOT NULL PRIMARY KEY,
                            botName TEXT,
                            joinedAt INTEGER NOT NULL,
                            lastSeenAt INTEGER NOT NULL,
                            active INTEGER NOT NULL,
                            proactiveAllowed INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS group_members (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            groupId TEXT NOT NULL,
                            memberId TEXT NOT NULL,
                            lastSeenAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_group_members_groupId ON group_members(groupId)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_groupId_memberId" +
                            " ON group_members(groupId, memberId)",
                    )
                }
            }

        /**
         * v4 → v5：人设版本快照。
         *
         * 纯增量迁移。`personaId` 允许 NULL（全局系统提示词），
         * 因此 Room 生成的列类型是 INTEGER（可空）。
         */
        val MIGRATION_4_5 =
            object : androidx.room.migration.Migration(4, 5) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS persona_snapshots (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            personaId INTEGER,
                            name TEXT NOT NULL,
                            prompt TEXT NOT NULL,
                            kind TEXT NOT NULL,
                            note TEXT NOT NULL,
                            createdAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_persona_snapshots_personaId" +
                            " ON persona_snapshots(personaId)",
                    )
                }
            }

        /**
         * v5 → v6：群消息区分「@ 我」与「普通消息」，并回填处理结论。
         *
         * room_messages 是纯缓存性质的历史，但老用户已经积累了几百条，
         * 直接清表不如加列——默认值让老消息一律视为"非 @ 普通消息"。
         */
        val MIGRATION_5_6 =
            object : androidx.room.migration.Migration(5, 6) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE room_messages ADD COLUMN isAt INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL("ALTER TABLE room_messages ADD COLUMN externalId TEXT")
                    db.execSQL("ALTER TABLE room_messages ADD COLUMN decision TEXT")
                }
            }

        /**
         * v6 → v7：接话训练闭环（决策日志 + 经验库）。
         *
         * 纯增量：只建两张新表，不碰任何既有数据。
         * 索引名必须与 Room 生成规则一致（index_<表>_<列>），
         * 否则打开数据库时 schema 校验失败——而本项目开着
         * fallbackToDestructiveMigration，校验失败会直接清库。
         */
        val MIGRATION_6_7 =
            object : androidx.room.migration.Migration(6, 7) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS chime_decisions (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            groupId TEXT NOT NULL,
                            externalId TEXT,
                            speakerId TEXT,
                            speakerName TEXT,
                            messageText TEXT NOT NULL,
                            contextSnippet TEXT NOT NULL,
                            ruleScore INTEGER,
                            gatePath TEXT NOT NULL,
                            aiVerdict TEXT,
                            aiReason TEXT,
                            action TEXT NOT NULL,
                            reason TEXT NOT NULL,
                            replyText TEXT,
                            addressed INTEGER NOT NULL DEFAULT 0,
                            insulted INTEGER NOT NULL DEFAULT 0,
                            feedback INTEGER,
                            correctedAction TEXT,
                            feedbackNote TEXT,
                            feedbackAt INTEGER,
                            createdAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_chime_decisions_groupId" +
                            " ON chime_decisions(groupId)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_chime_decisions_createdAt" +
                            " ON chime_decisions(createdAt)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_chime_decisions_feedback" +
                            " ON chime_decisions(feedback)",
                    )

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS chime_lessons (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            text TEXT NOT NULL,
                            source TEXT NOT NULL,
                            scopeGroupId TEXT,
                            fromDecisionId INTEGER,
                            hits INTEGER NOT NULL DEFAULT 0,
                            enabled INTEGER NOT NULL DEFAULT 1,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_chime_lessons_updatedAt" +
                            " ON chime_lessons(updatedAt)",
                    )
                }
            }
    }
}
