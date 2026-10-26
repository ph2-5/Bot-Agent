package com.qbot.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
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
        InnerThoughtEntity::class,
        AffinityEntity::class,
        BotMoodEntity::class,
        SlangEntity::class,
        MemeEntity::class,
        LoreEntity::class,
        StyleRuleEntity::class,
        MemoryTermEntity::class,
        MemoryVectorEntity::class,
        AutonomousDecisionEntity::class,
        GroupBehaviorEntity::class,
        SceneEntity::class,
        SceneDecisionEntity::class,
    ],
    version = 21,
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

    abstract fun innerThoughtDao(): InnerThoughtDao

    abstract fun affinityDao(): AffinityDao

    abstract fun botMoodDao(): BotMoodDao

    abstract fun slangDao(): SlangDao

    abstract fun memeDao(): MemeDao

    abstract fun loreDao(): LoreDao

    abstract fun styleRuleDao(): StyleRuleDao

    abstract fun memoryTermDao(): MemoryTermDao

    abstract fun memoryVectorDao(): MemoryVectorDao

    abstract fun autonomousDecisionDao(): AutonomousDecisionDao

    abstract fun groupBehaviorDao(): GroupBehaviorDao

    abstract fun sceneDao(): SceneDao

    abstract fun sceneDecisionDao(): SceneDecisionDao

    companion object {
        /**
         * 打开一个机器人的数据库。
         *
         * 多机器人采用「每个机器人一个库文件」而不是给所有表加 botId：
         * 人格、记忆、群档案、日志天然隔离，迁移风险为零。
         * 默认机器人沿用历史文件 `qbot.db`，升级后数据原样可用。
         */
        fun create(
            context: Context,
            fileName: String,
        ): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, fileName)
                // 刻意不挂 fallbackToDestructiveMigration：它会在 schema
                // 校验失败时静默清库，人设与记忆会一起消失。
                .addMigrations(
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                )
                .build()

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

        /**
         * v7 → v8：经验库从扁平淡句升级为带目录（类别 + 支撑计数）。
         *
         * 老经验一律归入「其他」，支撑数按 1 计——不影响使用，
         * 用户可以在复盘页手动改类别或直接删掉重学。
         */
        val MIGRATION_7_8 =
            object : androidx.room.migration.Migration(7, 8) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE chime_lessons ADD COLUMN category TEXT NOT NULL" +
                            " DEFAULT 'OTHER'",
                    )
                    db.execSQL(
                        "ALTER TABLE chime_lessons ADD COLUMN supportCount INTEGER NOT NULL" +
                            " DEFAULT 1",
                    )
                }
            }

        /**
         * v8 → v9：决策记录增加「对话对象」。
         *
         * 判断从「该不该」改成「想不想」之后，模型需要知道自己是在接谁的话；
         * 记录下来也能让复盘页核对"它以为这话是对谁说的"。
         */
        val MIGRATION_8_9 =
            object : androidx.room.migration.Migration(8, 9) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE chime_decisions ADD COLUMN addresseeKind TEXT")
                    db.execSQL("ALTER TABLE chime_decisions ADD COLUMN addresseeName TEXT")
                }
            }

        /**
         * v9 → v10：人设从「一段字符串」升格为「人格档案」。
         *
         * 加 11 列把档案的元数据补齐；同时做三件语义迁移：
         * 1. 无绑定的全局行升格为档案，有绑定的降级为覆盖；
         * 2. **旧人设一律标 MANUAL**——用户手写的东西不能被后台悄悄改写，
         *    想让它自己长，由用户在界面上显式点开；
         * 3. 激活最近更新的那一条，并补一条 INITIAL 快照，保证升级前的版本可回滚。
         *
         * 注意 DDL 的列类型 / NOT NULL / DEFAULT 必须与 [PersonaEntity] 上的
         * `@ColumnInfo(defaultValue)` 逐字符一致：本库开着
         * `fallbackToDestructiveMigration()`，schema 校验失败会**静默清库**。
         * 索引名必须是 Room 的生成规则 `index_<table>_<column>`。
         */
        val MIGRATION_9_10 =
            object : androidx.room.migration.Migration(9, 10) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    fun add(sql: String) = db.execSQL("ALTER TABLE personas ADD COLUMN $sql")
                    add("kind TEXT NOT NULL DEFAULT 'OVERRIDE'")
                    add("stage TEXT NOT NULL DEFAULT 'MANUAL'")
                    add("seedId TEXT")
                    add("activeSlot INTEGER")
                    add("frozen INTEGER NOT NULL DEFAULT 0")
                    add("birthAt INTEGER NOT NULL DEFAULT 0")
                    add("lastGrownAt INTEGER NOT NULL DEFAULT 0")
                    add("growthCount INTEGER NOT NULL DEFAULT 0")
                    add("msgCursor INTEGER NOT NULL DEFAULT 0")
                    add("lastNote TEXT NOT NULL DEFAULT ''")
                    add("createdAt INTEGER NOT NULL DEFAULT 0")

                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_personas_activeSlot " +
                            "ON personas(activeSlot)",
                    )

                    // 语义迁移：有绑定的是覆盖层，没绑定的是档案
                    db.execSQL(
                        "UPDATE personas SET kind = 'PROFILE' " +
                            "WHERE IFNULL(senderId,'') = '' AND IFNULL(scopeId,'') = ''",
                    )
                    db.execSQL(
                        "UPDATE personas SET kind = 'OVERRIDE' " +
                            "WHERE IFNULL(senderId,'') <> '' OR IFNULL(scopeId,'') <> ''",
                    )
                    db.execSQL(
                        "UPDATE personas SET createdAt = updatedAt WHERE kind = 'PROFILE'",
                    )

                    // 只激活最近更新的那一条：单行赋值，绝不能用无条件 UPDATE，
                    // 否则唯一索引冲突会让 Room 在打开数据库时直接崩。
                    db.execSQL(
                        "UPDATE personas SET activeSlot = 1 WHERE id = (" +
                            "SELECT id FROM personas WHERE kind = 'PROFILE' " +
                            "ORDER BY updatedAt DESC, id DESC LIMIT 1)",
                    )

                    // 升级前的人工版本必须永远可回滚
                    db.execSQL(
                        "INSERT INTO persona_snapshots(personaId, name, prompt, kind, note, createdAt) " +
                            "SELECT id, name, prompt, 'INITIAL', " +
                            "'升级到 2.3.0 之前的版本（自动存档）', updatedAt " +
                            "FROM personas WHERE activeSlot = 1",
                    )
                }
            }

        /**
         * v10 → v11：内心戏。
         *
         * 纯增量，只建新表，不动任何已有数据——老用户的人格、记忆、经验库原样保留。
         */
        val MIGRATION_10_11 =
            object : androidx.room.migration.Migration(10, 11) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `inner_thoughts` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`groupId` TEXT, " +
                            "`senderId` TEXT, " +
                            "`senderName` TEXT, " +
                            "`spoken` TEXT NOT NULL, " +
                            "`thought` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_inner_thoughts_createdAt` " +
                            "ON `inner_thoughts` (`createdAt`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_inner_thoughts_groupId` " +
                            "ON `inner_thoughts` (`groupId`)",
                    )
                }
            }

        /**
         * v11 → v12：关系、心情、梗库。
         *
         * 纯增量，只建新表。老用户的记忆、人格、经验、内心戏全部保留。
         * 升级后所有关系从零开始——这是对的：之前的行为没有被记录过，
         * 凭空给一个"熟识 50"反而是假的。
         */
        val MIGRATION_11_12 =
            object : androidx.room.migration.Migration(11, 12) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `affinities` (" +
                            "`userId` TEXT NOT NULL, " +
                            "`displayName` TEXT, " +
                            "`familiarity` INTEGER NOT NULL, " +
                            "`affinity` INTEGER NOT NULL, " +
                            "`interactionCount` INTEGER NOT NULL, " +
                            "`lastInteractionAt` INTEGER NOT NULL, " +
                            "`lastNote` TEXT, " +
                            "`updatedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`userId`))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `bot_mood` (" +
                            "`id` INTEGER NOT NULL, " +
                            "`value` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, " +
                            "`reason` TEXT, " +
                            "PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `slang_terms` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`term` TEXT NOT NULL, " +
                            "`meaning` TEXT NOT NULL, " +
                            "`usage` TEXT, " +
                            "`scopeGroupId` TEXT, " +
                            "`hits` INTEGER NOT NULL, " +
                            "`enabled` INTEGER NOT NULL, " +
                            "`source` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_slang_terms_scopeGroupId` " +
                            "ON `slang_terms` (`scopeGroupId`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_slang_terms_hits` " +
                            "ON `slang_terms` (`hits`)",
                    )
                }
            }

        /**
         * v12 → v13：表情包库。
         *
         * 纯增量，只建新表。老用户升级后图库是空的——
         * 要等群里有人发表情包才会开始捡，这是对的：
         * 凭空塞一堆来路不明的图，不如让它从这群人自己的喜好学起。
         */
        val MIGRATION_12_13 =
            object : androidx.room.migration.Migration(12, 13) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `meme_images` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`url` TEXT NOT NULL, " +
                            "`source` TEXT NOT NULL, " +
                            "`hits` INTEGER NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL)",
                    )
                }
            }
        val MIGRATION_13_14 =
            object : androidx.room.migration.Migration(13, 14) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `persona_lore` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `personaId` INTEGER,
                            `kind` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `note` TEXT NOT NULL,
                            `scopeGroupId` TEXT,
                            `enabled` INTEGER NOT NULL,
                            `source` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL)
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_persona_lore_personaId` ON `persona_lore` (`personaId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_persona_lore_kind` ON `persona_lore` (`kind`)")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `style_rules` (
                            `id` TEXT NOT NULL,
                            `text` TEXT NOT NULL,
                            `category` TEXT NOT NULL,
                            `source` TEXT NOT NULL,
                            `enabled` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`))
                        """.trimIndent(),
                    )
                }
            }
        /**
         * v14 → v15：人设记忆隔离。
         *
         * 三张表加 personaId：
         * - `room_messages`：只标记机器人自己的发言（群友的永远公共）；
         * - `chime_lessons`：经验归属人格，换人设后不继承旧偏好；
         * - `inner_thoughts`：内心戏归属人格。
         *
         * `affinities` 的主键从 userId 改成 (personaId, userId)：
         * SQLite 不支持改主键，只能建新表搬数据。
         * 旧行统一落在 personaId = 0（「不分人设」的共用关系），
         * 与升级前的行为完全一致；开启隔离后各人格从 0 开始攒。
         */
        val MIGRATION_14_15 =
            object : androidx.room.migration.Migration(14, 15) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `room_messages` ADD COLUMN `personaId` INTEGER")
                    db.execSQL("ALTER TABLE `chime_lessons` ADD COLUMN `personaId` INTEGER")
                    db.execSQL("ALTER TABLE `inner_thoughts` ADD COLUMN `personaId` INTEGER")
                    // 判断快照也要记下当时扮演的人格：由它反思出的经验要归到同一人格
                    db.execSQL("ALTER TABLE `chime_decisions` ADD COLUMN `personaId` INTEGER")

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `affinities_new` (
                            `personaId` INTEGER NOT NULL,
                            `userId` TEXT NOT NULL,
                            `displayName` TEXT,
                            `familiarity` INTEGER NOT NULL,
                            `affinity` INTEGER NOT NULL,
                            `interactionCount` INTEGER NOT NULL,
                            `lastInteractionAt` INTEGER NOT NULL,
                            `lastNote` TEXT,
                            `updatedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`personaId`, `userId`))
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "INSERT INTO `affinities_new` (" +
                            "`personaId`, `userId`, `displayName`, `familiarity`, `affinity`, " +
                            "`interactionCount`, `lastInteractionAt`, `lastNote`, `updatedAt`) " +
                            "SELECT 0, `userId`, `displayName`, `familiarity`, `affinity`, " +
                            "`interactionCount`, `lastInteractionAt`, `lastNote`, `updatedAt` " +
                            "FROM `affinities`",
                    )
                    db.execSQL("DROP TABLE `affinities`")
                    db.execSQL("ALTER TABLE `affinities_new` RENAME TO `affinities`")
                }
            }

        /**
         * v15 → v16：记忆倒排索引。
         *
         * 索引是派生物：老库升级时只建空表，内容由后台回填任务重建。
         * 同时给 `room_messages.externalId` 补索引——引用回复的原文
         * 从「每次扫最近 200 条」改成按 id 直查。
         */
        val MIGRATION_15_16 =
            object : androidx.room.migration.Migration(15, 16) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `memory_terms` (
                            `kind` TEXT NOT NULL,
                            `refId` INTEGER NOT NULL,
                            `term` TEXT NOT NULL,
                            `scopeKey` TEXT NOT NULL,
                            `senderId` TEXT,
                            `personaId` INTEGER,
                            `updatedAt` INTEGER NOT NULL,
                            `importance` INTEGER NOT NULL,
                            PRIMARY KEY(`kind`, `refId`, `term`))
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_terms_term` ON `memory_terms` (`term`)")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_memory_terms_scopeKey` ON `memory_terms` (`scopeKey`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_memory_terms_kind_refId` " +
                            "ON `memory_terms` (`kind`, `refId`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_room_messages_externalId` " +
                            "ON `room_messages` (`externalId`)",
                    )
                }
            }

        /**
         * v16 → v17：表情包本地副本。
         *
         * 只加一列，默认 null：老表情包没有本地字节，发送时仍按 URL 回退，
         * 能发就发、发不出就跳过——不会因为升级把老图库变成失败源。
         */
        val MIGRATION_16_17 =
            object : androidx.room.migration.Migration(16, 17) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `meme_images` ADD COLUMN `path` TEXT")
                }
            }

        /**
         * v17 → v18：自主开口。
         *
         * 三件事：
         * 1. `bot_mood` 加两列（最后开口时间 / 连续忍住次数）——情绪与"忍住"是同一份状态，
         *    不另建表；
         * 2. 新建 `autonomous_decisions`：SPOKEN 与 HELD 都记，调阈值要看"本来想说但忍住了"；
         * 3. 新建 `group_behavior`：按群覆盖全局档位。
         *
         * 两列都带默认值/可空，老库直接升级，不需要回填。
         */
        val MIGRATION_17_18 =
            object : androidx.room.migration.Migration(17, 18) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `bot_mood` ADD COLUMN `lastSpokeAt` INTEGER")
                    db.execSQL(
                        "ALTER TABLE `bot_mood` ADD COLUMN `suppressedCount` INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `autonomous_decisions` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `groupId` TEXT NOT NULL,
                            `personaId` INTEGER,
                            `score` REAL NOT NULL,
                            `outcome` TEXT NOT NULL,
                            `reason` TEXT,
                            `fingerprint` TEXT,
                            `createdAt` INTEGER NOT NULL)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_autonomous_decisions_groupId` " +
                            "ON `autonomous_decisions` (`groupId`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_autonomous_decisions_fingerprint` " +
                            "ON `autonomous_decisions` (`fingerprint`)",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `group_behavior` (
                            `groupId` TEXT NOT NULL PRIMARY KEY,
                            `autonomyMode` TEXT NOT NULL,
                            `updatedAt` INTEGER NOT NULL)
                        """.trimIndent(),
                    )
                }
            }

        /**
         * v18 → v19：场景语境。
         *
         * 纯增量：只建 `scenes` / `scene_decisions` 两张表，
         * 老库的人设、记忆、决策记录全部原样保留。
         */
        val MIGRATION_18_19 =
            object : androidx.room.migration.Migration(18, 19) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `scenes` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `kind` TEXT NOT NULL,
                            `groupId` TEXT NOT NULL,
                            `participantsJson` TEXT NOT NULL,
                            `goal` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `turnIndex` INTEGER NOT NULL DEFAULT 0,
                            `expectedSpeakerId` TEXT,
                            `waitUntil` INTEGER,
                            `lastActivityAt` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_scenes_group_status` " +
                            "ON `scenes` (`groupId`, `status`)",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `scene_decisions` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `sceneId` INTEGER,
                            `groupId` TEXT NOT NULL,
                            `action` TEXT NOT NULL,
                            `argsJson` TEXT,
                            `executed` INTEGER NOT NULL,
                            `reason` TEXT,
                            `createdAt` INTEGER NOT NULL)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_scene_decisions_group` " +
                            "ON `scene_decisions` (`groupId`)",
                    )
                }
            }

        /**
         * v19 → v20：场景契约 + 自主抑制原因。
         *
         * 两列都是可空增量：老场景 `contractJson` 为 null → 回退 2.13.0 的固定模板；
         * 老决策 `suppressedBy` 为 null → 就是没被场景抑制过。
         */
        val MIGRATION_19_20 =
            object : androidx.room.migration.Migration(19, 20) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `scenes` ADD COLUMN `contractJson` TEXT")
                    db.execSQL("ALTER TABLE `autonomous_decisions` ADD COLUMN `suppressedBy` TEXT")
                }
            }

        /**
         * v20 → v21：可选向量化记忆。
         *
         * 纯增量：只建 `memory_vectors`。老库升级后向量表为空，
         * 检索立刻退回纯倒排——与 2.14.1 的行为一致。
         */
        val MIGRATION_20_21 =
            object : androidx.room.migration.Migration(20, 21) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `memory_vectors` (
                            `kind` TEXT NOT NULL,
                            `refId` INTEGER NOT NULL,
                            `model` TEXT NOT NULL,
                            `dim` INTEGER NOT NULL,
                            `scopeKey` TEXT NOT NULL,
                            `vec` BLOB NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            PRIMARY KEY(`kind`, `refId`, `model`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_memory_vectors_scopeKey`" +
                            " ON `memory_vectors` (`scopeKey`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_memory_vectors_kind`" +
                            " ON `memory_vectors` (`kind`)",
                    )
                }
            }
    }
}
