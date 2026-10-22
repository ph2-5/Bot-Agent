package com.qbot.app.data.repo

import com.qbot.app.data.db.IdentityDao
import com.qbot.app.data.db.PersonaDao
import com.qbot.app.data.db.PersonaEntity
import com.qbot.core.ai.KnownPerson
import com.qbot.core.ai.Persona
import com.qbot.core.ai.PersonaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 人设仓库。
 *
 * 一头连 Room（实现 botcore 的 [PersonaStore]，供 AiResponder 的人设解析用），
 * 一头连维护端 UI（Flow 形式的列表、最近互动的人）。
 */
class PersonaRepository(
    private val personaDao: PersonaDao,
    private val identityDao: IdentityDao,
) : PersonaStore {
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
