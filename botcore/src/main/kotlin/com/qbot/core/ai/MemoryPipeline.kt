package com.qbot.core.ai

import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.MessageKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 记忆系统运行配置（来自维护端设置）。 */
data class MemoryConfig(
    /** 总开关：关闭后退化为纯对话机器人 */
    val enabled: Boolean = true,
    /** 每个人最多保留多少条事实 */
    val factsPerPerson: Int = 50,
    /** 是否启用共享群聊背景（跨人指代的上下文） */
    val roomContextEnabled: Boolean = true,
    /** 共享背景保留条数 */
    val roomContextSize: Int = 20,
    /** 是否从消息里学习自称名 */
    val learnNames: Boolean = true,
    /** 是否从消息里提取事实 */
    val learnFacts: Boolean = true,
)

/**
 * 身份名录条目。 */
data class IdentityEntry(
    val senderId: String,
    val displayName: String,
    val source: NameSource,
    val updatedAt: Long,
)

/** 来源优先级：维护端手动备注 > 用户自称 > 平台自带昵称。 */
val NameSource.priority: Int
    get() =
        when (this) {
            NameSource.MANUAL -> 3
            NameSource.DECLARED -> 2
            NameSource.PLATFORM -> 1
        }

/**
 * 身份名录：senderId → 展示名。
 *
 * QQ 官方不提供群成员昵称查询接口，展示名只能来自三个渠道：
 * 用户自称（「叫我小明」）、维护端手动备注、平台自带昵称（仅频道）。
 * 查不到时回退成「群友xxxxxx」，保证群里始终能区分不同的人。
 */
interface IdentityStore {
    /** 查条目；没有返回 null */
    suspend fun find(senderId: String): IdentityEntry?

    /** 记录/更新展示名。仅当来源优先级不低于已有条目时才覆盖。 */
    suspend fun remember(
        senderId: String,
        name: String,
        source: NameSource,
        updatedAt: Long,
    )

    /** 忘记某个人的名字 */
    suspend fun forget(senderId: String)

    /** 最近互动过的人（新的在前），供维护端快速选择 */
    suspend fun recentKnown(limit: Int): List<KnownPerson>
}

/**
 * 记忆管线：把「身份 → 事实 → 共享背景」三个存储编排成一条流水线。
 *
 * BotRuntime 在每条消息处理时调用：
 * 1. [buildContext] 回复前：解析展示名（顺带学习自称名）、
 *    提取并落库本条消息里的事实、装载此人的事实与所在范围的近期对话；
 * 2. [recordExchange] 回复后：把这一轮问答写进共享背景；
 * 3. [handleMemoryCommand] 回复前：拦截「我的记忆 / 忘掉X」等记忆管理指令。
 *
 * 所有存储都经接口注入，botcore 不依赖 Android；
 * 关闭 [MemoryConfig.enabled] 时全部方法退化为直通。
 */
class MemoryPipeline(
    private val identities: IdentityStore,
    private val facts: MemoryStore,
    private val roomContext: RoomContextStore,
    private val extractor: MemoryExtractor = MemoryExtractor(),
    private val configProvider: () -> MemoryConfig = { MemoryConfig() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** 查看自己记忆的指令 */
        val SHOW_MEMORY_COMMANDS =
            setOf("我的记忆", "我的记忆?", "我的记忆？", "你知道我什么", "你记得我什么", "你记得关于我的什么")

        /** 查自己名字的指令 */
        val WHO_AM_I_COMMANDS = setOf("我叫什么", "我是谁", "你还记得我叫什么吗")

        /** 清空自己全部事实的指令 */
        val FORGET_ALL_COMMANDS =
            setOf("忘掉我", "忘记我", "清空我的记忆", "删除我的记忆", "忘掉关于我的所有记忆", "忘掉我的记忆")

        private const val TAG = "MemoryPipeline"
    }

    /** 本条消息的记忆上下文（回复前调用）。 */
    suspend fun buildContext(
        message: IncomingMessage,
        botId: String,
        cleanText: String,
    ): MemoryContext {
        val config = configProvider()
        if (!config.enabled) {
            return MemoryContext(senderLabel = fallbackLabel(message))
        }

        val senderId = message.senderId
        if (senderId.isNullOrBlank()) {
            return MemoryContext(senderLabel = fallbackLabel(message))
        }

        val now = clock()
        val label = resolveLabel(message, senderId, cleanText, config, now)

        // 本条消息里新声明的事实先落库，再整包装载——
        // 「我喜欢吃辣，推荐道菜」这种同一句话里既声明又提问的场景才能用上。
        if (config.learnFacts) {
            val existing = facts.facts(senderId).map { it.text }.toSet()
            extractor.extractFacts(cleanText).forEach { draft ->
                if (draft.text !in existing) {
                    facts.add(
                        UserFact(
                            senderId = senderId,
                            text = draft.text,
                            kind = draft.kind,
                            source = FactSource.EXTRACTED,
                            updatedAt = now,
                        ),
                    )
                }
            }
            facts.trimTo(senderId, config.factsPerPerson)
        }

        val personFacts = facts.facts(senderId).take(config.factsPerPerson)

        val digest =
            if (config.roomContextEnabled) {
                roomContext.recent(ConversationKeys.room(message), config.roomContextSize)
            } else {
                emptyList()
            }

        return MemoryContext(senderLabel = label, facts = personFacts, roomDigest = digest)
    }

    /** 把一轮问答写进共享背景（回复后调用）。 */
    suspend fun recordExchange(
        message: IncomingMessage,
        cleanText: String,
        answer: String,
    ) {
        val config = configProvider()
        if (!config.enabled || !config.roomContextEnabled) return
        if (message.targetId.isNullOrBlank()) return

        val now = clock()
        val key = ConversationKeys.room(message)
        roomContext.append(
            key,
            RoomMessage(
                senderId = message.senderId,
                senderName = identities.find(message.senderId ?: "")?.displayName,
                role = "user",
                content = cleanText,
                timestampMillis = now,
            ),
        )
        roomContext.append(
            key,
            RoomMessage(
                senderId = null,
                senderName = null,
                role = "assistant",
                content = answer,
                timestampMillis = now,
            ),
        )
        roomContext.trim(key, config.roomContextSize)
    }

    /**
     * 记忆管理指令拦截。
     *
     * @return 回复文本；不是记忆指令返回 null（交回正常处理流程）
     */
    suspend fun handleMemoryCommand(
        message: IncomingMessage,
        cleanText: String,
    ): String? {
        val config = configProvider()
        if (!config.enabled) return null
        val senderId = message.senderId ?: return null
        val text = cleanText.trim().removePrefix("/ai").trim()
        if (text.isEmpty()) return null

        // 我叫什么 / 我是谁
        if (text in WHO_AM_I_COMMANDS) {
            val entry = identities.find(senderId)
            return if (entry != null) {
                "你叫${entry.displayName}呀，我一直记得。"
            } else {
                "我还不认识你——发一句「叫我XXX」告诉我吧。"
            }
        }

        // 查看事实
        if (text in SHOW_MEMORY_COMMANDS) {
            val list = facts.facts(senderId)
            return if (list.isEmpty()) {
                "我还没有关于你的记忆。多聊几句，或者直接告诉我「我喜欢XXX」。"
            } else {
                val body = list.take(20).joinToString("\n") { "- ${it.text}" }
                "关于你，我记得这些：\n$body\n（说「忘掉X」可以删掉某条，「忘掉我」全部清空）"
            }
        }

        // 清空全部
        if (text in FORGET_ALL_COMMANDS) {
            val count = facts.facts(senderId).size
            facts.deleteAll(senderId)
            identities.forget(senderId)
            return if (count > 0) "已清空关于你的 $count 条记忆，我们重新认识。" else "我本来就没有关于你的记忆。"
        }

        // 忘掉 X
        val forgetMatch = Regex("^(?:忘掉|忘记)(.{1,30})$").find(text)
        if (forgetMatch != null) {
            val keyword = forgetMatch.groupValues[1].trim()
            if (keyword.isNotEmpty()) {
                val matched = facts.facts(senderId).filter { it.text.contains(keyword) }
                matched.forEach { facts.delete(it.id) }
                return if (matched.isEmpty()) {
                    "我没有找到关于「$keyword」的记忆。"
                } else {
                    "已忘掉关于「$keyword」的 ${matched.size} 条记忆。"
                }
            }
        }

        return null
    }

    /** 启动时收敛：事实总量与共享背景条数。 */
    suspend fun trimAll() {
        val config = configProvider()
        if (!config.enabled) return
        facts.trimAll(config.factsPerPerson)
    }

    /**
     * 解析（并顺带学习）本条消息发送者的展示名。
     *
     * 公开出来是为了让运行日志能显示「小明: 你好」而不是一串 openid；
     * 与 [buildContext] 内部走同一套逻辑，重复调用是幂等的。
     *
     * @param cleanText 已剥离 @ 前缀的消息文本
     */
    suspend fun resolveLabel(
        message: IncomingMessage,
        cleanText: String,
    ): String {
        val config = configProvider()
        if (!config.enabled) return fallbackLabel(message)
        val senderId = message.senderId
        if (senderId.isNullOrBlank()) return fallbackLabel(message)
        return resolveLabel(message, senderId, cleanText, config, clock())
    }

    // ---------- 内部 ----------

    /**
     * 展示名解析顺序：
     * 1. 本条消息自称（且优先级不低于已存条目）→ 学习并采用；
     * 2. 已存条目（手动备注 / 以前学过的）；
     * 3. 平台自带昵称（频道）→ 学习并采用；
     * 4. 回退标签。
     */
    private suspend fun resolveLabel(
        message: IncomingMessage,
        senderId: String,
        cleanText: String,
        config: MemoryConfig,
        now: Long,
    ): String {
        val declared =
            if (config.learnNames) extractor.extractName(cleanText) else null
        val entry = identities.find(senderId)

        if (declared != null && (entry == null || entry.source.priority <= NameSource.DECLARED.priority)) {
            identities.remember(senderId, declared, NameSource.DECLARED, now)
            return declared
        }
        if (entry != null) return entry.displayName

        val platformName = message.senderName?.takeIf { it.isNotBlank() }
        if (platformName != null) {
            identities.remember(senderId, platformName, NameSource.PLATFORM, now)
            return platformName
        }

        return fallbackLabel(message)
    }

    private fun fallbackLabel(message: IncomingMessage): String {
        val tail = message.senderId?.takeLast(6).orEmpty()
        val prefix =
            when (message.kind) {
                MessageKind.C2C, MessageKind.DIRECT -> "好友"
                else -> "群友"
            }
        return if (tail.isEmpty()) "${prefix}用户" else "$prefix$tail"
    }
}

/** 内存实现（测试用；Android 侧见 RoomIdentityStore）。 */
class InMemoryIdentityStore : IdentityStore {
    private val mutex = Mutex()
    private val store = mutableMapOf<String, IdentityEntry>()

    override suspend fun find(senderId: String): IdentityEntry? =
        mutex.withLock { store[senderId] }

    override suspend fun remember(
        senderId: String,
        name: String,
        source: NameSource,
        updatedAt: Long,
    ) {
        mutex.withLock {
            val existing = store[senderId]
            // 优先级守卫：手动备注不会被用户自称覆盖
            if (existing != null && source.priority < existing.source.priority) return@withLock
            store[senderId] =
                IdentityEntry(
                    senderId = senderId,
                    displayName = name,
                    source = source,
                    updatedAt = updatedAt,
                )
        }
    }

    override suspend fun forget(senderId: String) {
        mutex.withLock { store.remove(senderId) }
    }

    override suspend fun recentKnown(limit: Int): List<KnownPerson> =
        mutex.withLock {
            store.values
                .sortedByDescending { it.updatedAt }
                .take(limit)
                .map { KnownPerson(it.senderId, it.displayName) }
        }
}
