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
    /**
     * 结构化指令（我叫什么 / 我的记忆 / 叫你X / 群里都有谁）的回复
     * 是否交给 AI 按人设复述。
     *
     * 关闭时退回固定句式（"你叫小明呀"那种）——与人设割裂，
     * 但不消耗 API、不依赖 AI 配置，适合只把机器人当工具用的场景。
     */
    val personaRewrite: Boolean = true,
    /**
     * 群背景存储上限（条）。
     *
     * 与 [roomContextSize] 分工：这个管**存多少**，那个管**注入多少**。
     * 开启「接收所有消息」后，群里每条消息都会进来，只留 20 条的话
     * 机器人对"刚才在聊什么"几乎没有记忆，因此默认保留 200 条，
     * 注入提示词时仍按 [roomContextSize] 截取，不撑爆预算。
     */
    val chitchatRetention: Int = 200,
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

/** 来源的自然语言描述，供指令复述时向 AI 交代名字的来历。 */
fun NameSource.describe(): String =
    when (this) {
        NameSource.MANUAL -> "维护者手动备注的"
        NameSource.DECLARED -> "对方自己告诉你的"
        NameSource.PLATFORM -> "平台报文里自带的"
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

    /**
     * 批量查条目。
     *
     * 组装群画像时要一次拿到几十个成员的名字，逐个 find 在 Room 上会是几十次查询；
     * 默认实现是循环，数据库实现应覆盖成单条 IN 查询。
     */
    suspend fun findMany(senderIds: Collection<String>): Map<String, IdentityEntry> =
        senderIds.mapNotNull { id -> find(id)?.let { id to it } }.toMap()

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
    /**
     * 群档案存储（机器人进过哪些群、群里见过谁）。
     *
     * 为 null 时群感知整体关闭：不建群档、不注入群画像、
     * 群相关指令（叫你X / 你叫什么 / 群里都有谁）不生效。
     */
    private val groupStore: GroupStore? = null,
    /** 机器人全局名（READY 下发的 username），回答「你叫什么」时兜底 */
    private val botNameProvider: (() -> String?)? = null,
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

        /** 问机器人名字的指令 */
        val BOT_NAME_COMMANDS = setOf("你叫什么", "你叫什么名字", "你叫啥", "你是谁")

        /** 查本群都有谁的指令 */
        val GROUP_MEMBERS_COMMANDS =
            setOf("群里都有谁", "这群里都有谁", "群里有多少人", "这群有多少人", "群记忆", "群里的人")

        /** 给机器人改群名片：「叫你小白 / 以后叫你小白 / 我以后就叫你小白」 */
        val RENAME_BOT_REGEX =
            Regex("^我?(?:以后|今后|从那以后)?(?:就)?叫你([\\u4e00-\\u9fa5A-Za-z0-9_-]{1,12})$")

        private const val TAG = "MemoryPipeline"
    }

    /** 本条消息的记忆上下文（回复前调用）。 */
    suspend fun buildContext(
        message: IncomingMessage,
        botId: String,
        cleanText: String,
        insult: InsultContext? = null,
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

        // 群画像：只有群聊且装了这个群的档案才注入
        val groupDigest = loadGroupDigest(message)

        // 引用关系：把它回复的那条原文找出来（在 200 条群背景里按 id 查）
        val quoted =
            if (config.roomContextEnabled) {
                message.quotedMessageId?.let { id ->
                    roomContext.recent(ConversationKeys.room(message), 200)
                        .lastOrNull { it.externalId == id }
                        ?.let { QuotedMessage(senderName = it.senderName, content = it.content) }
                }
            } else {
                null
            }

        return MemoryContext(
            senderLabel = label,
            facts = personFacts,
            roomDigest = digest,
            group = groupDigest,
            quoted = quoted,
            insult = insult,
        )
    }

    /**
     * 记录一次群内互动：把发送者记入「本群见过的人」并刷新活跃时间。
     *
     * 每条群消息都调用（不论走规则、记忆指令还是 AI）——
     * 这是群成员画像唯一的积累渠道，漏一次就少一个样本。
     */
    suspend fun noteGroupActivity(message: IncomingMessage) {
        val config = configProvider()
        if (!config.enabled) return
        val store = groupStore ?: return
        if (message.kind != MessageKind.GROUP_AT && message.kind != MessageKind.GROUP_MESSAGE) return
        val groupId = message.targetId?.takeIf { it.isNotBlank() } ?: return
        store.touch(groupId, message.senderId, clock())
    }

    /** 处理群生命周期通知（加群/退群/新成员/主动消息开关）。 */
    suspend fun handleGroupNotice(
        notice: com.qbot.core.model.GroupNotice,
        onGreeting: (suspend (String) -> Unit)? = null,
    ) {
        val config = configProvider()
        if (!config.enabled) return
        val store = groupStore ?: return
        val now = clock()
        val groupId = notice.groupId ?: return

        when (notice.kind) {
            com.qbot.core.model.GroupNoticeKind.ROBOT_ADDED -> {
                val existing = store.find(groupId)
                store.upsert(
                    GroupProfile(
                        groupId = groupId,
                        botName = existing?.botName, // 再加回旧群时保留群名片
                        joinedAt = now,
                        lastSeenAt = now,
                        active = true,
                        proactiveAllowed = existing?.proactiveAllowed ?: true,
                        seenMembers = existing?.seenMembers.orEmpty(),
                    ),
                )
                notice.actorId?.let { store.touch(groupId, it, now) }
                // 是否发问候由调用方（BotRuntime 读设置开关）决定，这里只负责回调
                onGreeting?.invoke(groupId)
            }

            com.qbot.core.model.GroupNoticeKind.ROBOT_REMOVED -> {
                val existing = store.find(groupId) ?: return
                store.upsert(existing.copy(active = false, lastSeenAt = now))
            }

            com.qbot.core.model.GroupNoticeKind.MEMBER_ADDED -> {
                // 新成员进群：确保群档存在 + 记入见过的人。
                // 没有成员名单接口，每一次 MEMBER_ADDED 都是一个珍贵样本。
                val existing = store.find(groupId)
                if (existing == null) {
                    store.upsert(
                        GroupProfile(
                            groupId = groupId,
                            joinedAt = now,
                            lastSeenAt = now,
                        ),
                    )
                }
                notice.actorId?.let { store.touch(groupId, it, now) }
            }

            com.qbot.core.model.GroupNoticeKind.PROACTIVE_ALLOWED -> {
                val existing = store.find(groupId) ?: return
                store.upsert(existing.copy(proactiveAllowed = true, lastSeenAt = now))
            }

            com.qbot.core.model.GroupNoticeKind.PROACTIVE_REJECTED -> {
                val existing = store.find(groupId) ?: return
                store.upsert(existing.copy(proactiveAllowed = false, lastSeenAt = now))
            }

            com.qbot.core.model.GroupNoticeKind.FRIEND_ADDED,
            com.qbot.core.model.GroupNoticeKind.FRIEND_REMOVED,
            -> Unit // 好友事件不建群档
        }
    }

    /** 查询群档案（运行层判断主动消息权限、维护端群管理都用它）。 */
    suspend fun groupProfile(groupId: String): GroupProfile? = groupStore?.find(groupId)

    /** 把一轮问答写进共享背景（回复后调用）。 */
    suspend fun recordExchange(        message: IncomingMessage,
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
                isAt = message.kind == MessageKind.GROUP_AT,
                externalId = message.messageId,
                decision = "已回复",
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
        roomContext.trim(key, retention(config))
    }

    /**
     * 记录一条群内普通消息（非 @机器人）。
     *
     * 只有群主开启「接收所有消息」后才会走这里。它与 [recordExchange] 的区别：
     * 这是"别人在聊天"，不是"机器人在对话"——只进群背景，不进按人隔离的历史。
     *
     * 展示名优先用报文里的真实昵称（全量消息才带 `author.username`），
     * 没有再查身份名录。
     */
    suspend fun recordChitchat(
        message: IncomingMessage,
        cleanText: String,
        isAt: Boolean = message.kind == MessageKind.GROUP_AT,
        decision: String? = null,
    ) {
        val config = configProvider()
        if (!config.enabled || !config.roomContextEnabled) return
        if (message.targetId.isNullOrBlank()) return
        if (cleanText.isBlank()) return

        val key = ConversationKeys.room(message)
        val senderId = message.senderId
        roomContext.append(
            key,
            RoomMessage(
                senderId = senderId,
                senderName =
                    message.senderName?.takeIf { it.isNotBlank() }
                        ?: senderId?.let { identities.find(it)?.displayName },
                role = "user",
                content = cleanText,
                timestampMillis = clock(),
                isAt = isAt,
                externalId = message.messageId,
                decision = decision,
            ),
        )
        roomContext.trim(key, retention(config))

        // 顺带学习事实：群里其他人说的「我在考研」「我不吃香菜」同样是记忆来源。
        // 先跑纯正则抽取，抽不到就直接返回——绝大多数闲聊天不会触发下面的数据库读写。
        if (config.learnFacts && senderId != null) {
            val drafts = extractor.extractFacts(cleanText)
            if (drafts.isNotEmpty()) {
                val existing = facts.facts(senderId).map { it.text }.toSet()
                val now = clock()
                drafts.filter { it.text !in existing }.forEach { draft ->
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
                facts.trimTo(senderId, config.factsPerPerson)
            }
        }
    }

    /**
     * 记录机器人对一条群消息的回复（只写 assistant 那一条）。
     *
     * 与 [recordExchange] 的区别：群消息的 user 部分已经由 [recordChitchat] 写过，
     * 再调 recordExchange 会出现两条一模一样的用户发言。
     */
    suspend fun recordChitchatAnswer(
        message: IncomingMessage,
        answer: String,
    ) {
        val config = configProvider()
        if (!config.enabled || !config.roomContextEnabled) return
        if (message.targetId.isNullOrBlank() || answer.isBlank()) return
        val key = ConversationKeys.room(message)
        roomContext.append(
            key,
            RoomMessage(
                senderId = null,
                senderName = null,
                role = "assistant",
                content = answer,
                timestampMillis = clock(),
            ),
        )
        roomContext.trim(key, retention(config))
    }

    /** 回填一条群消息的处理结论（"为什么没回"）。 */
    suspend fun markChitchatDecision(
        message: IncomingMessage,
        decision: String,
    ) {
        val config = configProvider()
        if (!config.enabled || !config.roomContextEnabled) return
        val externalId = message.messageId ?: return
        roomContext.updateDecision(ConversationKeys.room(message), externalId, decision)
    }

    /** 群背景存储上限：取「注入条数」与「保留条数」的较大者。 */
    private fun retention(config: MemoryConfig): Int =
        maxOf(config.roomContextSize, config.chitchatRetention)

    /**
     * 读取某个范围最近的对话，供插话判断使用。
     *
     * 与 [buildContext] 里的 roomDigest 是同一份数据：
     * 这里单独暴露是因为「该不该接话」的判断发生在组装回复之前，
     * 又不该为了一次判断把完整上下文（事实/群画像）都装一遍。
     */
    suspend fun recentRoomMessages(
        message: IncomingMessage,
        limit: Int,
    ): List<RoomMessage> {
        val config = configProvider()
        if (!config.enabled || !config.roomContextEnabled) return emptyList()
        if (message.targetId.isNullOrBlank()) return emptyList()
        return roomContext.recent(ConversationKeys.room(message), limit)
    }

    /** 按群 id 读最近对话（人设进化取样等场景，手上没有消息对象）。 */
    suspend fun recentGroupMessages(
        groupId: String,
        limit: Int,
    ): List<RoomMessage> {
        val config = configProvider()
        if (!config.enabled || !config.roomContextEnabled) return emptyList()
        return roomContext.recent(ConversationKeys.groupRoom(groupId), limit)
    }

    /**
     * 实时观察某个群的消息流（维护端「群消息」查看器）。
     *
     * 数据就是机器人用来理解群氛围的同一份群背景——看到的就是它"听到"的。
     */
    fun observeGroupMessages(
        groupId: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<RoomMessage>> =
        roomContext.observeRecent(ConversationKeys.groupRoom(groupId), limit)

    /**
     * 记忆/群管理指令拦截。
     *
     * 副作用（改名片、删记忆）在这里本地完成；回复文案不写死——
     * 返回 [CommandOutcome]：directive 是给 AI 复述的事实材料，
     * fallback 是 AI 不可用时的兜底。由调用方（BotRuntime）按
     * [MemoryConfig.personaRewrite] 与 AI 可用性二选一。
     *
     * @return 指令结果；不是记忆指令返回 null（交回正常处理流程）
     */
    suspend fun handleMemoryCommand(
        message: IncomingMessage,
        cleanText: String,
    ): CommandOutcome? {
        val config = configProvider()
        if (!config.enabled) return null
        val senderId = message.senderId ?: return null
        val text = cleanText.trim().removePrefix("/ai").trim()
        if (text.isEmpty()) return null

        // 我叫什么 / 我是谁
        if (text in WHO_AM_I_COMMANDS) {
            val entry = identities.find(senderId)
            return if (entry != null) {
                CommandOutcome(
                    directive = "对方的展示名是「${entry.displayName}」" +
                        "（${entry.source.describe()}），你一直记得。",
                    fallback = "你叫${entry.displayName}呀，我一直记得。",
                )
            } else {
                CommandOutcome(
                    directive = "你还不认识对方：对方没有告诉过你名字，" +
                        "你也没有任何关于对方名字的记录。",
                    fallback = "我还不认识你——发一句「叫我XXX」告诉我吧。",
                )
            }
        }

        // 查看事实
        if (text in SHOW_MEMORY_COMMANDS) {
            val list = facts.facts(senderId)
            return if (list.isEmpty()) {
                CommandOutcome(
                    directive = "你还没有关于对方的任何记忆记录。",
                    fallback = "我还没有关于你的记忆。多聊几句，或者直接告诉我「我喜欢XXX」。",
                )
            } else {
                val body = list.take(20).joinToString("\n") { "- ${it.text}" }
                CommandOutcome(
                    directive = "你记录的关于对方的事实如下：\n$body",
                    fallback = "关于你，我记得这些：\n$body\n" +
                        "（说「忘掉X」可以删掉某条，「忘掉我」全部清空）",
                )
            }
        }

        // 清空全部
        if (text in FORGET_ALL_COMMANDS) {
            val count = facts.facts(senderId).size
            facts.deleteAll(senderId)
            identities.forget(senderId)
            return CommandOutcome(
                directive = "你刚刚清空了关于对方的全部记忆" +
                    "（共 $count 条）以及对方的名字记录，" +
                    "现在你不记得关于对方的任何事情。",
                fallback = if (count > 0) {
                    "已清空关于你的 $count 条记忆，我们重新认识。"
                } else {
                    "我本来就没有关于你的记忆。"
                },
            )
        }

        // 忘掉 X
        val forgetMatch = Regex("^(?:忘掉|忘记)(.{1,30})$").find(text)
        if (forgetMatch != null) {
            val keyword = forgetMatch.groupValues[1].trim()
            if (keyword.isNotEmpty()) {
                val matched = facts.facts(senderId).filter { it.text.contains(keyword) }
                matched.forEach { facts.delete(it.id) }
                return CommandOutcome(
                    directive = if (matched.isEmpty()) {
                        "对方让你忘掉关于「$keyword」的记忆，" +
                            "但你没有找到相关记录，什么也没有删除。"
                    } else {
                        "你刚刚忘掉了关于「$keyword」的 ${matched.size} 条记忆。"
                    },
                    fallback = if (matched.isEmpty()) {
                        "我没有找到关于「$keyword」的记忆。"
                    } else {
                        "已忘掉关于「$keyword」的 ${matched.size} 条记忆。"
                    },
                )
            }
        }

        // ---- 群感知指令（需要群档案存储） ----
        val store = groupStore ?: return null
        val groupId = message.targetId?.takeIf { it.isNotBlank() }

        // 你叫什么 / 你是谁 → 本群名片优先，全局名兜底
        if (text in BOT_NAME_COMMANDS) {
            val groupName = groupId?.let { store.find(it) }?.botName
            val globalName = botNameProvider?.invoke()?.takeIf { it.isNotBlank() }
            return when {
                groupName != null -> CommandOutcome(
                    directive = "你在这个群的名字叫「$groupName」，" +
                        "是群里的人给你取的，大家会用这个名字称呼你。",
                    fallback = "我在这个群叫「$groupName」呀，有事叫我这个名字就行。",
                )

                globalName != null -> CommandOutcome(
                    directive = "你在这个群还没有群昵称；" +
                        "你的官方名字是「$globalName」。",
                    fallback = "官方名字是「$globalName」。" +
                        "想给我改个名的话，在群里说「叫你XXX」就行。",
                )

                else -> CommandOutcome(
                    directive = "你还没有名字：既没有群昵称，也查不到官方名字。",
                    fallback = "我还没有名字——在群里说「叫你XXX」就能给我取一个。",
                )
            }
        }

        // 叫你X → 设置/更新本群名片（仅群聊有效）
        val renameMatch = RENAME_BOT_REGEX.find(text)
        if (renameMatch != null) {
            val newName = renameMatch.groupValues[1].trim()
            if (message.kind != MessageKind.GROUP_AT || groupId == null) {
                return CommandOutcome(
                    directive = "对方想在私聊里给你改名字，但改名只在群里有效，" +
                        "你向对方说明了这一点。",
                    fallback = "改名字这件事只在群里有效哦——在那个群里说「叫你$newName」就行。",
                )
            }
            val existing = store.find(groupId)
            store.upsert(
                GroupProfile(
                    groupId = groupId,
                    botName = newName,
                    joinedAt = existing?.joinedAt ?: clock(),
                    lastSeenAt = clock(),
                    active = true,
                    proactiveAllowed = existing?.proactiveAllowed ?: true,
                    seenMembers = existing?.seenMembers.orEmpty(),
                ),
            )
            return CommandOutcome(
                directive = "你刚刚在这个群把名字改成了「$newName」，" +
                    "从此大家会用这个名字称呼你。",
                fallback = "好，以后在这个群里我就叫「$newName」了。",
            )
        }

        // 群里都有谁 / 群里有多少人 → 本群见过的人（有名字的列名字）
        if (text in GROUP_MEMBERS_COMMANDS) {
            if (message.kind != MessageKind.GROUP_AT || groupId == null) {
                return CommandOutcome(
                    directive = "对方在群外问群里有哪些人，你说明这只能在群里问。",
                    fallback = "这个只能在群里问我哦。",
                )
            }
            val profile = store.find(groupId)
            val seen = profile?.seenMembers.orEmpty()
            if (seen.isEmpty()) {
                return CommandOutcome(
                    directive = "这个群你还不熟悉：没有见过任何成员，" +
                        "也不知道任何人的名字。",
                    fallback = "这个群我还不熟悉——大家多@我聊几句，慢慢就认识了。" +
                        "想让我记住你的话，说一句「叫我XXX」。",
                )
            }
            val named =
                seen.mapNotNull { id ->
                    identities.find(id)?.displayName?.let { name -> id to name }
                }
            val unnamedCount = seen.size - named.size
            val nameList = named.joinToString("、") { (_, name) -> name }
            return CommandOutcome(
                directive = "这个群里跟你有过交流的成员共 ${seen.size} 人。" +
                    "其中你知道名字的有：$nameList" +
                    if (unnamedCount > 0) {
                        "；还有 $unnamedCount 位没有告诉你名字。"
                    } else {
                        "。"
                    } +
                    "（平台不提供完整成员名单，以上是你通过群事件与聊天积累到的，不要编造其他成员。）",
                fallback = "这个群里跟机器人有过交流的有 ${seen.size} 人：\n" +
                    named.joinToString("\n") { (_, name) -> "- $name" } +
                    (if (unnamedCount > 0) "\n- 还有 $unnamedCount 位没告诉我名字" else "") +
                    "\n（说「叫我XXX」可以让我记住你）",
            )
        }

        return null
    }

    /** 当前记忆配置（运行层判断指令复述开关用，避免重复读 DataStore）。 */
    fun currentConfig(): MemoryConfig = configProvider()

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

    /** 装载群画像摘要（群聊 + 有群档时才非空）。 */
    private suspend fun loadGroupDigest(message: IncomingMessage): GroupDigest? {
        val store = groupStore ?: return null
        if (message.kind != MessageKind.GROUP_AT && message.kind != MessageKind.GROUP_MESSAGE) return null
        val groupId = message.targetId?.takeIf { it.isNotBlank() } ?: return null
        val profile = store.find(groupId) ?: return null
        val seen = profile.seenMembers
        // 只查前 40 个成员的名称（一次 IN 查询），最多展示 8 个：
        // 群画像要短、要稳定，塞 500 个名字既费 token 也刷屏。
        val known =
            if (seen.isEmpty()) {
                emptyList()
            } else {
                identities.findMany(seen.take(40)).values
                    .map { it.displayName }
                    .distinct()
                    .take(8)
            }
        return GroupDigest(
            groupId = groupId,
            botName = profile.botName,
            seenCount = profile.seenCount,
            proactiveAllowed = profile.proactiveAllowed,
            knownMembers = known,
            unnamedCount = (seen.size - known.size).coerceAtLeast(0),
        )
    }

    /**
     * 观察机器人在本群被怎么称呼。
     *
     * 群昵称没有查询接口，但 @ 消息的 mentions 里可能带着机器人自己的
     * username（往往就是群名片）。只在用户还没手动设置时采用——
     * 「叫你X」与维护端备注永远优先。
     */
    suspend fun observeBotName(
        groupId: String,
        name: String?,
    ) {
        val config = configProvider()
        if (!config.enabled) return
        val store = groupStore ?: return
        val clean = name?.trim().orEmpty()
        if (clean.isEmpty()) return
        val existing = store.find(groupId) ?: return
        if (existing.botName != null) return
        store.upsert(existing.copy(botName = clean))
    }

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
