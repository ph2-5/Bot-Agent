package com.qbot.core.ai

import com.qbot.core.model.IncomingMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 人设的两种角色。
 *
 * 2.3.0 之前这张表只存"按人/按群覆盖的人设"，全局人设藏在
 * [AiConfig.systemPrompt] 一个字符串里。现在两种都进这张表，靠这个字段区分：
 *
 * - [PROFILE]：完整的人格档案。可以有多份，但同一时刻只有一份 active；
 * - [OVERRIDE]：只对某个人/某个群生效的局部覆盖，永远优先于档案。
 */
enum class PersonaKind {
    PROFILE,
    OVERRIDE,
}

/**
 * 人格所处的阶段。
 *
 * ```
 * EMBRYO ──破壳──▶ GROWING ──成长 8 次──▶ MATURE
 *                    ▲
 *                 MANUAL（迁移来的旧人设：不自动改）
 * ```
 */
enum class PersonaStage {
    /** 胚胎：还没有正文，运行时用种子骨架兜底 */
    EMBRYO,

    /** 成长期：按消息量与间隔自动微调 */
    GROWING,

    /** 成熟：仍可成长，但间隔翻倍，防止越改越花 */
    MATURE,

    /** 手动：用户自己写的，不参与自动成长 */
    MANUAL,
}

/** 本次解析实际用了哪一层，用于日志排查。 */
enum class PersonaSource {
    /** 按人/按群覆盖 */
    OVERRIDE,

    /** 当前激活的人格档案 */
    PROFILE,

    /** 旧版本遗留的兜底提示词（几乎不会出现） */
    LEGACY_FALLBACK,

    /** 没有任何档案，用种子骨架 */
    EMBRYO,
}

/**
 * 一份人设（角色卡 / 人格档案）。
 *
 * 与 [UserFact] 的分工：事实是「关于对方我知道什么」，人设是「我是谁、
 * 怎么说话」。解析优先级见 [PersonaResolver]。
 */
data class Persona(
    val id: Long = 0,
    /** 绑定的 senderId；空表示不绑定到具体某个人 */
    val senderId: String? = null,
    /** 绑定的群/频道 openid；空表示不绑定到具体群 */
    val scopeId: String? = null,
    /** 人设名称，如「默认助手」「傲娇猫娘」，仅用于维护端展示 */
    val name: String,
    /** 人格正文（六段，见 [PersonaSections]）；EMBRYO 阶段为空 */
    val prompt: String,
    val updatedAt: Long = 0,
    val kind: PersonaKind = PersonaKind.OVERRIDE,
    val stage: PersonaStage = PersonaStage.MANUAL,
    /** 出生时抽到的性格原型（[PersonaSeed] 的 name） */
    val seedId: String? = null,
    /** 是否为当前激活档案；只有 PROFILE 有意义 */
    val active: Boolean = false,
    /** 冻结后不再自动成长 */
    val frozen: Boolean = false,
    val growthCount: Int = 0,
    val birthAt: Long = 0,
    val lastGrownAt: Long = 0,
    /** 上次成长时的 room_messages 最大 id，用来算"又攒了多少条" */
    val msgCursor: Long = 0,
    val lastNote: String = "",
) {
    /** 绑定到人/群的行才算覆盖层；未绑定的全局行只能是档案 */
    val isScoped: Boolean
        get() = !senderId.isNullOrBlank() || !scopeId.isNullOrBlank()
}

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
 * 按 人→群 优先级挑出本条消息该用的**覆盖层**人设。
 *
 * 只处理绑定了 [Persona.senderId] 或 [Persona.scopeId] 的行；
 * 未绑定的行由 [PersonaResolver] 单独处理（它们是被激活的那份档案）。
 *
 * 同粒度有多条时取最近更新的（updatedAt 最大）。
 * 没有任何匹配时返回 null，由调用方回退到激活档案。
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

/** 解析结果：正文 + 来源，便于排查"这个群为什么不像新人格"。 */
data class ResolvedPersona(
    val prompt: String,
    val personaId: Long?,
    val source: PersonaSource,
    val stage: PersonaStage = PersonaStage.MANUAL,
)

/**
 * 人设解析器：给 [AiResponder] 与 [com.qbot.core.engine.BotRuntime] 用的适配层。
 *
 * 四级优先级：
 * 1. 覆盖层（绑定到这个人 / 这个群）——最高；
 * 2. 当前激活的人格档案（activeSlot = 1）；
 * 3. 旧版本兜底（[fallbackProvider]，仅兼容场景）；
 * 4. 种子骨架——**永不返回空**。
 *
 * 第 4 条是这个类最关键的行为：空 system 会让 [PromptAssembler] 整条不注入，
 * 模型立刻退化成"助手腔 + Markdown 标题 + 复读群友"。所以哪怕没有任何档案，
 * 也要给一段极简的人格底座。
 */
class PersonaResolver(
    private val store: PersonaStore,
    /** 仅用于兼容旧数据；生产链路由激活档案或种子骨架兜底 */
    private val fallbackProvider: (suspend () -> String?)? = null,
) {
    suspend fun resolve(message: IncomingMessage): ResolvedPersona {
        val all = store.all()

        // 1) 覆盖层：只把绑定到人/群的行交给 resolvePersona。
        // 如果传全表，未绑定的档案行会被当成"全局人设"参与竞争，
        // 结果就是未激活的档案也能生效——多档案管理会失效。
        resolvePersona(message, all.filter { it.isScoped })?.let {
            return ResolvedPersona(it.prompt, it.id, PersonaSource.OVERRIDE, it.stage)
        }

        // 2) 当前激活档案；正文为空（EMBRYO）时用种子骨架顶上
        val active = all.firstOrNull { it.active && it.kind == PersonaKind.PROFILE }
        if (active != null) {
            val body = active.prompt.ifBlank { PersonaGrowth.embryoSkeleton(active.seedId) }
            return ResolvedPersona(body, active.id, PersonaSource.PROFILE, active.stage)
        }

        // 3) 兼容兜底
        fallbackProvider?.invoke()?.takeIf { it.isNotBlank() }?.let {
            return ResolvedPersona(it, null, PersonaSource.LEGACY_FALLBACK, PersonaStage.MANUAL)
        }

        // 4) 种子骨架
        return ResolvedPersona(
            prompt = PersonaGrowth.embryoSkeleton(null),
            personaId = null,
            source = PersonaSource.EMBRYO,
            stage = PersonaStage.EMBRYO,
        )
    }

    /** 旧调用点兼容：永不返回 null。 */
    suspend fun promptFor(message: IncomingMessage): String = resolve(message).prompt
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
