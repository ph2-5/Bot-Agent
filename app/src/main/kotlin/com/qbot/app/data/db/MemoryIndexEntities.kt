package com.qbot.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 记忆倒排索引（派生物，不进备份）。
 *
 * 为什么不用 FTS：
 * - Android 自带 SQLite 不保证编译了 FTS5，`trigram` 分词器要 3.34+，
 *   而项目 minSdk 26 对应 3.18；
 * - FTS4 的 simple/unicode61 分词对中文不切词，整句会变成一个 token。
 *
 * 所以自己切「中文二元组 + 英文整词」存进普通表，靠 B-tree 索引查询：
 * 一条记忆建 10~30 行，200 条背景 × 几十个群也只是一张几万行的小表。
 *
 * 主键是 (kind, refId, term)：同一条记忆重复索引时直接覆盖，
 * 天然幂等，后台回填可以放心重跑。
 */
@Entity(
    tableName = "memory_terms",
    primaryKeys = ["kind", "refId", "term"],
    indices = [
        Index("term"),
        Index("scopeKey"),
        Index(value = ["kind", "refId"]),
    ],
)
data class MemoryTermEntity(
    /** MemoryKind 的 name：FACT / ROOM */
    val kind: String,
    /** 源表主键：facts.id / room_messages.id */
    val refId: Long,
    val term: String,
    /** 检索范围：g:群id 或 f:人id */
    val scopeKey: String,
    val senderId: String?,
    val personaId: Long?,
    val updatedAt: Long,
    /** 重要性倍率 ×100：事实 100，机器人自己说过的话 60 */
    val importance: Int,
)

@Dao
interface MemoryTermDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MemoryTermEntity>)

    @Query("DELETE FROM memory_terms WHERE kind = :kind AND refId = :refId")
    suspend fun deleteFor(kind: String, refId: Long)

    /**
     * 清掉已经被主表删除的陈旧索引行。
     *
     * trim 是按条数裁剪的，删了哪些 id 不会回传；与其在写入路径追踪，
     * 不如裁剪后按「主表里不存在」一次性收敛。
     */
    @Query(
        "DELETE FROM memory_terms WHERE kind = 'ROOM' AND scopeKey = :scopeKey " +
            "AND refId NOT IN (SELECT id FROM room_messages WHERE scopeKey = :scopeKey)",
    )
    suspend fun pruneRoomOrphans(scopeKey: String)

    @Query(
        "DELETE FROM memory_terms WHERE kind = 'FACT' AND scopeKey = :scopeKey " +
            "AND refId NOT IN (SELECT id FROM facts WHERE senderId = :senderId)",
    )
    suspend fun pruneFactOrphans(
        scopeKey: String,
        senderId: String,
    )

    /**
     * 命中查询：一次取回所有「索引词 × 记忆」对。
     *
     * LIMIT 是防爆保护：常见二元组（比如「游戏」）可能命中上百条，
     * 但真正会进提示词的只有 topK 条，多余的由 Kotlin 侧打分后丢弃。
     */
    @Query(
        "SELECT * FROM memory_terms WHERE term IN (:terms) AND scopeKey IN (:scopes) " +
            "LIMIT :limit",
    )
    suspend fun match(
        terms: List<String>,
        scopes: List<String>,
        limit: Int,
    ): List<MemoryTermEntity>

    @Query("SELECT COUNT(*) FROM memory_terms")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM memory_terms WHERE scopeKey = :scopeKey")
    suspend fun countIn(scopeKey: String): Int

    @Query("DELETE FROM memory_terms WHERE scopeKey = :scopeKey AND kind = :kind")
    suspend fun clearScope(
        scopeKey: String,
        kind: String,
    )

    @Query("DELETE FROM memory_terms")
    suspend fun clear()

    /** 回填游标：某类已经索引到的最大源 id */
    @Query("SELECT MAX(refId) FROM memory_terms WHERE kind = :kind")
    suspend fun maxRefId(kind: String): Long?
}
