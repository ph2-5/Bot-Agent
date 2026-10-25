package com.qbot.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 对某个人的关系。
 *
 * 两个维度见 [com.qbot.core.ai.PersonRelation]：熟识只涨不跌（认识就是认识），
 * 好感可升可降且会回归中性（不记一辈子仇）。
 */
@Entity(tableName = "affinities", primaryKeys = ["personaId", "userId"])
data class AffinityEntity(
    /**
     * 归属人格；0 表示「不分人设」的共用关系。
     *
     * 默认（记忆隔离开关关闭）下所有互动都写 0，与旧版本行为一致；
     * 开启后按当前人格各存一份，换人设不会继承旧人格攒下的好感。
     */
    val personaId: Long = 0,
    val userId: String,
    val displayName: String? = null,
    /** 0..100 */
    val familiarity: Int = 0,
    /** -100..100 */
    val affinity: Int = 0,
    val interactionCount: Int = 0,
    val lastInteractionAt: Long = 0,
    val lastNote: String? = null,
    val updatedAt: Long = 0,
)

/**
 * 机器人自己的短期心情（单行）。
 *
 * 与好感分开存：好感是"对谁"，心情是"这会儿"。被骂了心情差，
 * 但几小时后自己会缓过来——这也是人。
 */
@Entity(tableName = "bot_mood")
data class BotMoodEntity(
    @PrimaryKey val id: Int = 1,
    /** 0..100，50 为平常 */
    val value: Int = 50,
    val updatedAt: Long = 0,
    val reason: String? = null,
)

/**
 * 群内梗 / 黑话 / 缩写。
 *
 * 只收"这个群自己反复在说"的说法：网络流行语有半衰期，模型容易用错代际，
 * 而群内梗的语义和分寸都在上下文里，用错的概率低得多。
 */
@Entity(
    tableName = "slang_terms",
    indices = [Index("scopeGroupId"), Index("hits")],
)
data class SlangEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val term: String,
    val meaning: String,
    val usage: String? = null,
    /** 只在这个群成立；null 表示全局 */
    val scopeGroupId: String? = null,
    val hits: Int = 0,
    val enabled: Boolean = true,
    /** HARVEST / MANUAL */
    val source: String = "HARVEST",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Dao
interface AffinityDao {
    @Query("SELECT * FROM affinities WHERE personaId = :personaId AND userId = :userId")
    suspend fun find(
        personaId: Long,
        userId: String,
    ): AffinityEntity?

    /** 某个视角下的关系排行；[personaId] = 0 即不分人设。 */
    @Query(
        "SELECT * FROM affinities WHERE personaId = :personaId " +
            "ORDER BY affinity DESC, familiarity DESC LIMIT :limit",
    )
    fun observeTop(
        personaId: Long,
        limit: Int = 50,
    ): Flow<List<AffinityEntity>>

    /** 全部视角的原始行（备份导出用）。 */
    @Query("SELECT * FROM affinities")
    suspend fun all(): List<AffinityEntity>

    @Query("SELECT * FROM affinities WHERE personaId = :personaId")
    suspend fun allFor(personaId: Long): List<AffinityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AffinityEntity)

    @Query("DELETE FROM affinities WHERE personaId = :personaId AND userId = :userId")
    suspend fun delete(
        personaId: Long,
        userId: String,
    )

    @Query("DELETE FROM affinities")
    suspend fun clear()
}

@Dao
interface BotMoodDao {
    @Query("SELECT * FROM bot_mood WHERE id = 1")
    suspend fun get(): BotMoodEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: BotMoodEntity)
}

/**
 * 一张表情包。
 *
 * 群友发的图由运行时顺手收进来（HARVEST），维护端也可以贴链接（MANUAL）。
 * 全局共享不分群：图本身不带语境，A 群的图在 B 群照样能用。
 */
@Entity(tableName = "meme_images")
data class MemeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    /** HARVEST / MANUAL */
    val source: String = "HARVEST",
    /** 被采用次数，用于淘汰排序 */
    val hits: Int = 0,
    val createdAt: Long = 0,
    /**
     * 本地 JPEG 的相对文件名（相对 `filesDir/memes`）。
     *
     * 老数据为 null：那时只存 URL，而 QQ 链接几小时就过期，
     * 发送时只能回退「让平台按 URL 拉」，失败就跳过。
     */
    val path: String? = null,
)

@Dao
interface MemeDao {
    @Query("SELECT * FROM meme_images ORDER BY hits DESC, createdAt DESC LIMIT :limit")
    suspend fun promptImages(limit: Int): List<MemeEntity>

    @Query("SELECT * FROM meme_images ORDER BY hits DESC, createdAt DESC")
    fun observeAll(): Flow<List<MemeEntity>>

    @Query("SELECT * FROM meme_images")
    suspend fun all(): List<MemeEntity>

    @Query("SELECT * FROM meme_images WHERE url = :url LIMIT 1")
    suspend fun find(url: String): MemeEntity?

    @Query("SELECT * FROM meme_images WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): MemeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MemeEntity): Long

    @Query("UPDATE meme_images SET hits = hits + :delta WHERE id = :id")
    suspend fun bumpHits(id: Long, delta: Int)

    @Query("DELETE FROM meme_images WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "DELETE FROM meme_images WHERE id NOT IN (" +
            "SELECT id FROM meme_images ORDER BY hits DESC, createdAt DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM meme_images")
    suspend fun clear()
}

@Dao
interface SlangDao {
    @Query(
        "SELECT * FROM slang_terms WHERE enabled = 1 " +
            "AND (scopeGroupId IS NULL OR scopeGroupId = :groupId) " +
            "ORDER BY hits DESC, updatedAt DESC LIMIT :limit",
    )
    suspend fun promptTerms(groupId: String?, limit: Int): List<SlangEntity>

    @Query("SELECT * FROM slang_terms ORDER BY hits DESC, updatedAt DESC")
    fun observeAll(): Flow<List<SlangEntity>>

    @Query("SELECT * FROM slang_terms")
    suspend fun all(): List<SlangEntity>

    @Query(
        "SELECT * FROM slang_terms WHERE term = :term " +
            "AND IFNULL(scopeGroupId, '') = IFNULL(:scopeGroupId, '') LIMIT 1",
    )
    suspend fun find(term: String, scopeGroupId: String?): SlangEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SlangEntity): Long

    @Query("UPDATE slang_terms SET hits = hits + :delta, updatedAt = :at WHERE id = :id")
    suspend fun bumpHits(id: Long, delta: Int, at: Long)

    @Query("UPDATE slang_terms SET enabled = :enabled, updatedAt = :at WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, at: Long)

    @Query("DELETE FROM slang_terms WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "DELETE FROM slang_terms WHERE id NOT IN (" +
            "SELECT id FROM slang_terms ORDER BY hits DESC, updatedAt DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM slang_terms")
    suspend fun clear()
}
