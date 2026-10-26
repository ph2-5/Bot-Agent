package com.qbot.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 记忆向量（派生物，不进备份）。
 *
 * 与 `memory_terms` 是两层互补的索引：
 * - 倒排强在专有名词 / 人名 / 梗的精确匹配；
 * - 向量强在「上次说的那个游戏」这类语义召回。
 *
 * 主键 `(kind, refId, model)`：
 * - 同一条记忆重复向量化直接覆盖，回填幂等；
 * - 换模型后旧向量与新的不可比，按 model 隔离，检索只读当前模型那一份。
 *
 * `vec` 存 float32 小端 **且已 L2 归一化**：检索只做点积，省一次 sqrt。
 * 冗余存 `scopeKey` 是为了暴力检索时不用 join 源表。
 */
@Entity(
    tableName = "memory_vectors",
    primaryKeys = ["kind", "refId", "model"],
    indices = [
        Index("scopeKey"),
        Index("kind"),
    ],
)
data class MemoryVectorEntity(
    /** MemoryKind 的 name：FACT / ROOM / LORE / SLANG */
    val kind: String,
    /** 源表主键：facts.id / room_messages.id / persona_lore.id / slang_terms.id */
    val refId: Long,
    val model: String,
    val dim: Int,
    /** 检索范围：g:群id / g:群id@p:人id / f:人id / p:人格id */
    val scopeKey: String,
    /** float32 小端、已 L2 归一化 */
    val vec: ByteArray,
    val createdAt: Long,
)

@Dao
interface MemoryVectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MemoryVectorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MemoryVectorEntity>)

    /**
     * 暴力检索：按范围 + 类型 + 当前模型取全部候选，点积在 Kotlin 侧算。
     *
     * 1 万条 × 1024 维 ≈ 1000 万次乘加（手机 20-50ms），
     * 5 万条以上才需要考虑 ANN——那已经超出单机群聊机器人的规模。
     */
    @Query(
        "SELECT * FROM memory_vectors WHERE scopeKey IN (:scopes) " +
            "AND kind IN (:kinds) AND model = :model",
    )
    suspend fun candidates(
        scopes: List<String>,
        kinds: List<String>,
        model: String,
    ): List<MemoryVectorEntity>

    @Query("DELETE FROM memory_vectors WHERE kind = :kind AND refId = :refId")
    suspend fun deleteFor(
        kind: String,
        refId: Long,
    )

    @Query(
        "DELETE FROM memory_vectors WHERE kind = 'ROOM' " +
            "AND refId NOT IN (SELECT id FROM room_messages)",
    )
    suspend fun pruneRoomOrphans()

    @Query(
        "DELETE FROM memory_vectors WHERE kind = 'FACT' " +
            "AND refId NOT IN (SELECT id FROM facts)",
    )
    suspend fun pruneFactOrphans()

    @Query(
        "DELETE FROM memory_vectors WHERE kind = 'LORE' " +
            "AND refId NOT IN (SELECT id FROM persona_lore)",
    )
    suspend fun pruneLoreOrphans()

    @Query(
        "DELETE FROM memory_vectors WHERE kind = 'SLANG' " +
            "AND refId NOT IN (SELECT id FROM slang_terms)",
    )
    suspend fun pruneSlangOrphans()

    @Query("DELETE FROM memory_vectors WHERE kind = :kind AND model = :model")
    suspend fun clearKind(
        kind: String,
        model: String,
    )

    @Query("DELETE FROM memory_vectors")
    suspend fun clear()

    // ---------- 进度与占用 ----------

    @Query("SELECT COUNT(*) FROM memory_vectors WHERE model = :model")
    suspend fun countForModel(model: String): Int

    /** 字节数 = Σ(dim × 4) */
    @Query("SELECT COALESCE(SUM(dim), 0) FROM memory_vectors WHERE model = :model")
    suspend fun sumDims(model: String): Long

    @Query("SELECT COUNT(*) FROM memory_vectors")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM facts")
    suspend fun factTotal(): Int

    @Query("SELECT COUNT(*) FROM room_messages")
    suspend fun roomTotal(): Int

    @Query("SELECT COUNT(*) FROM persona_lore")
    suspend fun loreTotal(): Int

    @Query("SELECT COUNT(*) FROM slang_terms")
    suspend fun slangTotal(): Int

    // ---------- 手动回填：按 id 递增取「还没有当前模型向量」的行 ----------

    @Query(
        "SELECT * FROM facts WHERE id > :afterId " +
            "AND id NOT IN (SELECT refId FROM memory_vectors WHERE kind = 'FACT' AND model = :model) " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun factsWithoutVectors(
        model: String,
        afterId: Long,
        limit: Int,
    ): List<FactEntity>

    @Query(
        "SELECT * FROM room_messages WHERE id > :afterId " +
            "AND id NOT IN (SELECT refId FROM memory_vectors WHERE kind = 'ROOM' AND model = :model) " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun roomsWithoutVectors(
        model: String,
        afterId: Long,
        limit: Int,
    ): List<RoomMessageEntity>

    @Query(
        "SELECT * FROM persona_lore WHERE id > :afterId " +
            "AND id NOT IN (SELECT refId FROM memory_vectors WHERE kind = 'LORE' AND model = :model) " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun loreWithoutVectors(
        model: String,
        afterId: Long,
        limit: Int,
    ): List<LoreEntity>

    @Query(
        "SELECT * FROM slang_terms WHERE id > :afterId " +
            "AND id NOT IN (SELECT refId FROM memory_vectors WHERE kind = 'SLANG' AND model = :model) " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun slangWithoutVectors(
        model: String,
        afterId: Long,
        limit: Int,
    ): List<SlangEntity>
}
