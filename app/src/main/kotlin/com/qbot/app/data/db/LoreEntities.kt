package com.qbot.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 人设设定：从角色卡里抽出来的「认识的人 / 说法 / 关系」。
 *
 * 与 facts 的分工：
 * - facts 是「聊天中了解到关于某个 openid 的事」，按人隔离；
 * - persona_lore 是「角色卡里本来就写好的设定」，按人格挂在提示词上，
 *   不需要对应任何真实 openid（例如「老爹」「主人」这类称呼）。
 */
@Entity(
    tableName = "persona_lore",
    indices = [Index("personaId"), Index("kind")],
)
data class LoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 归属的人格档案；null 表示全局设定 */
    val personaId: Long?,
    /** PERSON / SLANG / RELATION / NOTE */
    val kind: String,
    val name: String,
    val note: String,
    /** 只对某个群生效；null 表示所有群 */
    val scopeGroupId: String?,
    val enabled: Boolean,
    /** AI / MANUAL */
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 「去 AI 味」的说话约束。
 *
 * 内置规则不在表里建行，只在用户调整过启停时写一条覆盖行
 * （id 为内置 id、source = BUILTIN）；用户自定义规则 source = USER。
 * 这样升级时新增内置规则会自动出现，不会被旧数据挡住。
 */
@Entity(tableName = "style_rules")
data class StyleRuleEntity(
    @PrimaryKey val id: String,
    val text: String,
    /** LENGTH / TONE / FORMAT / ECHO / BOUNDARY */
    val category: String,
    /** BUILTIN / USER / PLUGIN */
    val source: String,
    val enabled: Boolean,
    val updatedAt: Long,
)

@Dao
interface LoreDao {
    @Query("SELECT * FROM persona_lore ORDER BY updatedAt DESC")
    suspend fun all(): List<LoreEntity>

    @Query("SELECT * FROM persona_lore ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<LoreEntity>>

    @Query("SELECT COUNT(*) FROM persona_lore")
    suspend fun count(): Int

    @Query(
        """
        SELECT * FROM persona_lore
        WHERE enabled = 1
          AND (personaId IS NULL OR personaId = :personaId)
          AND (scopeGroupId IS NULL OR scopeGroupId = :groupId)
        ORDER BY updatedAt DESC
        LIMIT 30
        """,
    )
    suspend fun forPersona(
        personaId: Long?,
        groupId: String?,
    ): List<LoreEntity>

    @Query(
        """
        SELECT * FROM persona_lore
        WHERE kind = :kind AND name = :name
          AND (personaId IS :personaId)
          AND (scopeGroupId IS :scopeGroupId)
        LIMIT 1
        """,
    )
    suspend fun find(
        personaId: Long?,
        kind: String,
        name: String,
        scopeGroupId: String?,
    ): LoreEntity?

    /** 返回主键：新增时是自增 id，覆盖时是原 id。 */
    @Upsert
    suspend fun upsert(entry: LoreEntity): Long

    @Query("UPDATE persona_lore SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)

    @Query("DELETE FROM persona_lore WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM persona_lore WHERE personaId IS :personaId")
    suspend fun clearPersona(personaId: Long?)

    /** 导入备份前整表清空：备份里是完整设定集，不清会残留上一份的旧梗/旧人。 */
    @Query("DELETE FROM persona_lore")
    suspend fun clearAll()
}

@Dao
interface StyleRuleDao {
    @Query("SELECT * FROM style_rules ORDER BY source ASC, id ASC")
    suspend fun all(): List<StyleRuleEntity>

    @Query("SELECT * FROM style_rules ORDER BY source ASC, id ASC")
    fun observeAll(): Flow<List<StyleRuleEntity>>

    @Upsert
    suspend fun upsert(rule: StyleRuleEntity)

    @Query("UPDATE style_rules SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long)

    @Query("DELETE FROM style_rules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM style_rules WHERE source = 'USER'")
    suspend fun clearUserRules()

    /** 清空某个来源（从人设重新提取前先删上一批 PERSONA 规则）。 */
    @Query("DELETE FROM style_rules WHERE source = :source")
    suspend fun clearSource(source: String)
}
