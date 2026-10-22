package com.qbot.core.ai

import com.qbot.core.model.IncomingMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一份人设（角色卡）。
 *
 * 与 [UserFact] 的分工：事实是「关于对方我知道什么」，人设是「我是谁、
 * 怎么说话」。人设可以按粒度绑定，优先级见 [resolvePersona]：
 *
 * - [senderId] 非空：只对某个人生效（「对小明傲娇，对别人礼貌」）；
 * - [senderId] 空且 [scopeId] 非空：只对某个群/频道生效；
 * - 两者都空：全局默认人设。
 */
data class Persona(
    val id: Long = 0,
    /** 绑定的 senderId；空表示不绑定到具体某个人 */
    val senderId: String? = null,
    /** 绑定的群/频道 openid；空表示不绑定到具体群 */
    val scopeId: String? = null,
    /** 人设名称，如「默认助手」「傲娇猫娘」，仅用于维护端展示 */
    val name: String,
    /** 系统提示词正文 */
    val prompt: String,
    val updatedAt: Long = 0,
)

/**
 * 人设存储。
 *
 * 抽成接口是为了让 botcore 不依赖 Room：Android 侧用数据库实现，
 * 单元测试用内存实现。
 */
interface PersonaStore {
    /** 全部人设（新的在前） */
    suspend fun all(): List<Persona>

    /** 新增或更新，返回 id */
    suspend fun save(persona: Persona): Long

    suspend fun delete(id: Long)
}

/**
 * 按 人→群→全局 优先级挑出本条消息该用的人设。
 *
 * 同粒度有多条时取最近更新的（updatedAt 最大）。
 * 没有任何匹配时返回 null，由调用方回退到全局默认提示词。
 */
fun resolvePersona(
    message: IncomingMessage,
    personas: List<Persona>,
): Persona? {
    if (personas.isEmpty()) return null
    val sender = message.senderId?.takeIf { it.isNotBlank() }
    val target = message.targetId?.takeIf { it.isNotBlank() }

    return personas
        .asSequence()
        .filter { it.prompt.isNotBlank() }
        .filter { persona ->
            val boundSender = persona.senderId?.takeIf { it.isNotBlank() }
            val boundScope = persona.scopeId?.takeIf { it.isNotBlank() }
            when {
                // 绑了人：只对这个人生效
                boundSender != null -> boundSender == sender
                // 绑了群：只对这个群生效（未绑人）
                boundScope != null -> boundScope == target
                // 全局人设：人人有份
                else -> true
            }
        }
        .maxWithOrNull(
            compareBy<Persona> { personaLevel(it) }.thenBy { it.updatedAt },
        )
}

/** 粒度权重：人(3) > 群(2) > 全局(1)。 */
private fun personaLevel(persona: Persona): Int =
    when {
        !persona.senderId.isNullOrBlank() -> 3
        !persona.scopeId.isNullOrBlank() -> 2
        else -> 1
    }

/**
 * 人设解析器：给 [AiResponder] 的 personaResolver 用的适配层。
 *
 * 解析链：人设库（人→群→全局）→ [fallbackProvider]（通常是设置里的
 * 全局系统提示词）→ 调用方再用 AiConfig.systemPrompt 兜底。
 */
class PersonaResolver(
    private val store: PersonaStore,
    private val fallbackProvider: (suspend () -> String?)? = null,
) {
    suspend fun promptFor(message: IncomingMessage): String? {
        val best = resolvePersona(message, store.all())
        if (best != null) return best.prompt
        return fallbackProvider?.invoke()?.takeIf { it.isNotBlank() }
    }
}

/** 内存实现，供测试与「不落库」模式使用。 */
class InMemoryPersonaStore : PersonaStore {
    private val mutex = Mutex()
    private val store = mutableMapOf<Long, Persona>()
    private var nextId = 1L

    override suspend fun all(): List<Persona> =
        mutex.withLock { store.values.sortedByDescending { it.updatedAt } }

    override suspend fun save(persona: Persona): Long =
        mutex.withLock {
            val id = if (persona.id > 0) persona.id else nextId++
            store[id] = persona.copy(id = id)
            id
        }

    override suspend fun delete(id: Long) {
        mutex.withLock { store.remove(id) }
    }
}
