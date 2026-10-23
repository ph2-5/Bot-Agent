package com.qbot.app.data.repo

import com.qbot.app.data.db.LoreDao
import com.qbot.app.data.db.LoreEntity
import com.qbot.app.data.db.StyleRuleDao
import com.qbot.app.data.db.StyleRuleEntity
import com.qbot.core.ai.BuiltinStyleRules
import com.qbot.core.ai.LoreEntry
import com.qbot.core.ai.LoreKind
import com.qbot.core.ai.LoreSource
import com.qbot.core.ai.LoreStore
import com.qbot.core.ai.StyleRule
import com.qbot.core.ai.StyleRuleCategory
import com.qbot.core.ai.StyleRuleSource
import com.qbot.core.ai.StyleRuleStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 人设设定（认识的人 / 说法）的 Room 实现。
 *
 * 与 [RoomFactStore] 的分工见 `LoreEntity` 注释：这里是角色卡里写死的设定，
 * 不需要对应真实 openid；聊天里学到的东西仍然走 facts。
 */
class RoomLoreStore(
    private val dao: LoreDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : LoreStore {
    fun observeAll(): Flow<List<LoreEntry>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun forPersona(
        personaId: Long?,
        groupId: String?,
    ): List<LoreEntry> = dao.forPersona(personaId, groupId).map { it.toModel() }

    override suspend fun all(): List<LoreEntry> = dao.all().map { it.toModel() }

    override suspend fun save(entry: LoreEntry): Long {
        val name = entry.name.trim()
        val note = entry.note.trim()
        if (name.isEmpty() || note.isEmpty()) return 0
        val now = clock()
        val existing = dao.find(entry.personaId, entry.kind.name, name, entry.scopeGroupId)
        if (existing != null) {
            // 同一条设定重复抽取/手动改：覆盖正文与来源，保留 id 与创建时间
            dao.upsert(
                entry.copy(name = name, note = note).toEntity(now).copy(
                    id = existing.id,
                    createdAt = existing.createdAt,
                    updatedAt = now,
                ),
            )
            return existing.id
        }
        return dao.upsert(
            entry.copy(name = name, note = note).toEntity(now).copy(
                id = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun setEnabled(
        id: Long,
        enabled: Boolean,
    ) = dao.setEnabled(id, enabled, clock())

    override suspend fun clearPersona(personaId: Long?) = dao.clearPersona(personaId)
}

/**
 * 「去 AI 味」约束的 Room 实现。
 *
 * 内置规则不建行：只有用户调整过启停时才写一条覆盖行（id 与内置规则一致、
 * source = BUILTIN），这样升级新增内置规则会自动出现，不会被旧数据挡住。
 */
class RoomStyleRuleStore(
    private val dao: StyleRuleDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : StyleRuleStore {
    /** 数据库里的原始行（含内置规则的启停覆盖），交给 [com.qbot.core.ai.StyleRuleComposer] 合并 */
    fun observeRows(): Flow<List<StyleRule>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun all(): List<StyleRule> = dao.all().map { it.toModel() }

    override suspend fun upsert(rule: StyleRule): Long {
        val text = rule.text.trim()
        if (text.isEmpty()) return 0
        val id = rule.id.ifBlank { "user.${UUID.randomUUID()}" }
        dao.upsert(rule.copy(id = id, text = text).toEntity(clock()))
        return id.hashCode().toLong()
    }

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        if (dao.all().any { it.id == id }) {
            dao.setEnabled(id, enabled, clock())
            return
        }
        // 内置规则第一次被停用：补一条覆盖行，而不是修改不存在的行
        val builtin = BuiltinStyleRules.ALL.firstOrNull { it.id == id } ?: return
        dao.upsert(builtin.copy(enabled = enabled).toEntity(clock()))
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun clearUserRules() = dao.clearUserRules()

    override suspend fun clearSource(source: StyleRuleSource) = dao.clearSource(source.name)
}

internal fun LoreEntity.toModel() =
    LoreEntry(
        id = id,
        personaId = personaId,
        kind =
            runCatching { LoreKind.valueOf(kind) }.getOrDefault(LoreKind.NOTE),
        name = name,
        note = note,
        scopeGroupId = scopeGroupId,
        enabled = enabled,
        source =
            runCatching { LoreSource.valueOf(source) }.getOrDefault(LoreSource.MANUAL),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun LoreEntry.toEntity(now: Long) =
    LoreEntity(
        id = id,
        personaId = personaId,
        kind = kind.name,
        name = name,
        note = note,
        scopeGroupId = scopeGroupId,
        enabled = enabled,
        source = source.name,
        createdAt = if (createdAt > 0) createdAt else now,
        updatedAt = if (updatedAt > 0) updatedAt else now,
    )

internal fun StyleRuleEntity.toModel() =
    StyleRule(
        id = id,
        text = text,
        category =
            runCatching { StyleRuleCategory.valueOf(category) }
                .getOrDefault(StyleRuleCategory.TONE),
        source =
            runCatching { StyleRuleSource.valueOf(source) }
                .getOrDefault(StyleRuleSource.USER),
        enabled = enabled,
    )

private fun StyleRule.toEntity(now: Long) =
    StyleRuleEntity(
        id = id,
        text = text,
        category = category.name,
        source = source.name,
        enabled = enabled,
        updatedAt = now,
    )
