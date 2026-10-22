package com.qbot.app.data.repo

import com.qbot.app.data.db.IdentityDao
import com.qbot.app.data.db.PersonaDao
import com.qbot.app.data.db.PersonaEntity
import com.qbot.app.data.db.PersonaSnapshotDao
import com.qbot.app.data.db.PersonaSnapshotEntity
import com.qbot.core.ai.KnownPerson
import com.qbot.core.ai.Persona
import com.qbot.core.ai.PersonaSnapshot
import com.qbot.core.ai.PersonaSnapshotKind
import com.qbot.core.ai.PersonaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 人设仓库。
 *
 * 一头连 Room（实现 botcore 的 [PersonaStore]，供 AiResponder 的人设解析用），
 * 一头连维护端 UI（Flow 形式的列表、最近互动的人）。
 *
 * 另外管理「人设版本快照」：每次 AI 进化前把当前版本存一份，
 * 初始版本（INITIAL）不可删除，保证随时能回到最初的样子。
 */
class PersonaRepository(
    private val personaDao: PersonaDao,
    private val identityDao: IdentityDao,
    private val snapshotDao: PersonaSnapshotDao? = null,
) : PersonaStore {
    companion object {
        /** 每个目标保留的非初始版本数量 */
        private const val KEEP_SNAPSHOTS = 20
    }

    /** 全部人设（按更新时间倒序），供管理界面展示 */
    val personas: Flow<List<Persona>> =
        personaDao.all().map { list -> list.map { it.toPersona() } }

    /** 最近互动过的人，供人设绑定时快速选择 */
    suspend fun recentPeople(limit: Int = 50): List<KnownPerson> =
        identityDao.recent(limit).map { KnownPerson(it.senderId, it.displayName) }

    override suspend fun all(): List<Persona> = personaDao.allOnce().map { it.toPersona() }

    override suspend fun save(persona: Persona): Long =
        personaDao.upsert(
            PersonaEntity(
                id = persona.id,
                senderId = persona.senderId?.takeIf { it.isNotBlank() },
                scopeId = persona.scopeId?.takeIf { it.isNotBlank() },
                name = persona.name,
                prompt = persona.prompt,
                updatedAt = System.currentTimeMillis(),
            ),
        )

    override suspend fun delete(id: Long) {
        personaDao.deleteById(id)
    }

    // ---------- 版本快照 ----------

    /** 某个目标的历史版本（按时间倒序）；personaId 为 null 表示全局提示词 */
    suspend fun snapshots(personaId: Long?): List<PersonaSnapshot> =
        snapshotDao?.listFor(personaId)?.map { it.toSnapshot() }.orEmpty()

    /** 当前目标是否已有初始存档（没有就先存一份再进化） */
    suspend fun hasInitialSnapshot(personaId: Long?): Boolean =
        snapshotDao?.countInitial(personaId)?.let { it > 0 } ?: false

    suspend fun addSnapshot(
        personaId: Long?,
        name: String,
        prompt: String,
        kind: PersonaSnapshotKind,
        note: String,
    ) {
        val dao = snapshotDao ?: return
        dao.insert(
            PersonaSnapshotEntity(
                personaId = personaId,
                name = name,
                prompt = prompt,
                kind = kind.name,
                note = note,
                createdAt = System.currentTimeMillis(),
            ),
        )
        // 初始版本永不淘汰
        dao.trim(personaId, KEEP_SNAPSHOTS)
    }

    /** 删除非初始版本；初始版本静默忽略 */
    suspend fun deleteSnapshot(id: Long) {
        snapshotDao?.deleteNonInitial(id)
    }

    private fun PersonaSnapshotEntity.toSnapshot(): PersonaSnapshot =
        PersonaSnapshot(
            id = id,
            personaId = personaId,
            name = name,
            prompt = prompt,
            kind =
                runCatching { PersonaSnapshotKind.valueOf(kind) }
                    .getOrDefault(PersonaSnapshotKind.MANUAL),
            note = note,
            createdAt = createdAt,
        )

    private fun PersonaEntity.toPersona(): Persona =
        Persona(
            id = id,
            senderId = senderId,
            scopeId = scopeId,
            name = name,
            prompt = prompt,
            updatedAt = updatedAt,
        )
}
