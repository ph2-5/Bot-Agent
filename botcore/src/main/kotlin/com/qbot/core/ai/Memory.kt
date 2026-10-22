package com.qbot.core.ai

import com.qbot.core.model.IncomingMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一条关于某个人的持久记忆（长期记忆 / 用户画像）。
 *
 * 与「会话历史」分开存放：历史是短期的、按对话线程；
 * 事实是跨对话的、按人。机器人重启、清空上下文都不影响事实。
 */
data class UserFact(
    val id: Long = 0,
    /** 发送者稳定标识（群内 member_openid / 单聊 user_openid） */
    val senderId: String,
    /** 事实正文，简短陈述句，如「喜欢打篮球」「在备考研究生」 */
    val text: String,
    val kind: FactKind = FactKind.PROFILE,
    val source: FactSource = FactSource.EXTRACTED,
    val updatedAt: Long = 0,
)

/** 事实分类。 */
enum class FactKind {
    /** 自称名（同时也会进身份名录） */
    NAME,

    /** 偏好：喜欢 / 讨厌 / 想要 */
    PREFERENCE,

    /** 状态：在备考 / 出差 / 养了猫 */
    PROFILE,

    /** 其他备注 */
    NOTE,
}

/** 事实来源。 */
enum class FactSource {
    /** 从消息里自动提取 */
    EXTRACTED,

    /** 维护端手动添加 */
    MANUAL,

    /** AI 反思总结产生（预留） */
    REFLECTED,
}

/** 展示名来源。 */
enum class NameSource {
    /** 用户自己说「叫我XX」 */
    DECLARED,

    /** 维护端手动备注 */
    MANUAL,

    /** 平台报文自带昵称（频道 member.nick） */
    PLATFORM,
}

/** 维护端「最近互动的人」快速选择用。 */
data class KnownPerson(
    val senderId: String,
    val displayName: String,
)

/**
 * 长期记忆存储。
 *
 * 抽成接口是为了让 botcore 不依赖 Room：Android 侧用数据库实现，
 * 单元测试用内存实现。
 */
interface MemoryStore {
    /** 某个人的全部事实（新的在前，供指令回放与注入） */
    suspend fun facts(senderId: String): List<UserFact>

    /** 追加一条事实，返回 id */
    suspend fun add(fact: UserFact): Long

    /** 删除单条 */
    suspend fun delete(factId: Long)

    /** 清空某个人的全部事实 */
    suspend fun deleteAll(senderId: String)

    /** 把某个人的事实收敛到上限（写入后调用，防止刷屏刷爆） */
    suspend fun trimTo(senderId: String, keep: Int)

    /** 把每个人的事实收敛到上限（启动时调用一次） */
    suspend fun trimAll(keep: Int)
}

/**
 * 共享群聊背景。
 *
 * 按人隔离记忆后，群里 A 说的话 B 的线程里看不到；
 * 这条「房间里最近发生了什么」的滚动缓冲专门解决跨人指代
 * （「那他呢」「换个话题」这类承接）。
 */
data class RoomMessage(
    val senderId: String?,
    val senderName: String?,
    /** user / assistant */
    val role: String,
    val content: String,
    val timestampMillis: Long,
)

/** 共享背景存储。scopeKey 形如 g:群openid / u:用户openid / c:频道id。 */
interface RoomContextStore {
    /** 最近的消息（正序） */
    suspend fun recent(scopeKey: String, limit: Int): List<RoomMessage>

    suspend fun append(scopeKey: String, message: RoomMessage)

    /** 只保留最近 keep 条 */
    suspend fun trim(scopeKey: String, keep: Int)
}

/**
 * 身份名录（senderId → 展示名）定义在 [MemoryPipeline] 同文件：
 * QQ 官方不提供群成员昵称查询接口，展示名只能来自用户自称、
 * 维护端手动备注、平台自带昵称（仅频道）三个渠道。
 */

// ---------- 内存实现（测试与「不落库」模式用） ----------

class InMemoryMemoryStore : MemoryStore {
    private val mutex = Mutex()
    private val store = mutableMapOf<String, MutableList<UserFact>>()
    private var nextId = 1L

    override suspend fun facts(senderId: String): List<UserFact> =
        mutex.withLock { store[senderId]?.sortedByDescending { it.updatedAt }.orEmpty() }

    override suspend fun add(fact: UserFact): Long =
        mutex.withLock {
            val id = nextId++
            val at = fact.updatedAt.takeIf { it > 0 } ?: id
            store.getOrPut(fact.senderId) { mutableListOf() }
                .add(fact.copy(id = id, updatedAt = at))
            id
        }

    override suspend fun delete(factId: Long) {
        mutex.withLock {
            store.values.forEach { list -> list.removeAll { it.id == factId } }
        }
    }

    override suspend fun deleteAll(senderId: String) {
        mutex.withLock { store.remove(senderId) }
    }

    override suspend fun trimTo(senderId: String, keep: Int) {
        mutex.withLock {
            val list = store[senderId] ?: return@withLock
            while (list.size > keep) {
                val oldest = list.minByOrNull { it.updatedAt } ?: break
                list.remove(oldest)
            }
        }
    }

    override suspend fun trimAll(keep: Int) {
        mutex.withLock {
            store.keys.toList().forEach { key ->
                val list = store[key] ?: return@forEach
                while (list.size > keep) {
                    val oldest = list.minByOrNull { it.updatedAt } ?: break
                    list.remove(oldest)
                }
            }
        }
    }
}

class InMemoryRoomContextStore : RoomContextStore {
    private val mutex = Mutex()
    private val store = mutableMapOf<String, MutableList<RoomMessage>>()

    override suspend fun recent(scopeKey: String, limit: Int): List<RoomMessage> =
        mutex.withLock { store[scopeKey]?.takeLast(limit).orEmpty() }

    override suspend fun append(scopeKey: String, message: RoomMessage) {
        mutex.withLock {
            store.getOrPut(scopeKey) { mutableListOf() }.add(message)
        }
    }

    override suspend fun trim(scopeKey: String, keep: Int) {
        mutex.withLock {
            val list = store[scopeKey] ?: return@withLock
            while (list.size > keep) list.removeAt(0)
        }
    }
}

/**
 * 一条消息的记忆上下文（每次回复前组装）。
 *
 * @param senderLabel 已解析的展示名（回退值保证非空）
 * @param facts 关于这个人的持久事实（注入提示词）
 * @param roomDigest 所在范围的近期对话（注入提示词，群聊才非空）
 * @param group 所在群的画像（机器人群名片、见过多少成员；群聊才非空）
 */
data class MemoryContext(
    val senderLabel: String,
    val facts: List<UserFact> = emptyList(),
    val roomDigest: List<RoomMessage> = emptyList(),
    val group: GroupDigest? = null,
)

/**
 * 群画像摘要：注入提示词，让机器人回答「你叫什么」「群里有多少人」时有据可依。
 *
 * @param groupId 群 openid
 * @param botName 机器人在本群的名字（手动设置/聊天指令「叫你X」），null 时用全局名
 * @param seenCount 本群跟机器人打过招呼/进过群的成员数（openid 级别）
 * @param proactiveAllowed 群是否允许机器人主动发言（GROUP_MSG_REJECT 后为 false）
 */
data class GroupDigest(
    val groupId: String,
    val botName: String?,
    val seenCount: Int,
    val proactiveAllowed: Boolean = true,
)

/**
 * 会话标识：按人隔离的记忆键。
 *
 * 群聊按 (群, 人) 分开——A 与机器人的恩怨不串到 B 的线程里；
 * 单聊天然按人。同一套键同时用于会话历史与共享背景（后者不含人）。
 */
object ConversationKeys {
    fun history(message: IncomingMessage): String {
        val sender = message.senderId ?: "anon"
        return when (message.kind) {
            com.qbot.core.model.MessageKind.GROUP_AT -> "g:${message.targetId}:$sender"
            com.qbot.core.model.MessageKind.C2C -> "u:${message.targetId}"
            com.qbot.core.model.MessageKind.GUILD -> "c:${message.targetId}:$sender"
            com.qbot.core.model.MessageKind.DIRECT -> "d:${message.targetId}:$sender"
        }
    }

    /** 共享背景的作用域（不含人）：整个群 / 整个频道 / 单聊会话。 */
    fun room(message: IncomingMessage): String =
        when (message.kind) {
            com.qbot.core.model.MessageKind.GROUP_AT -> "g:${message.targetId}"
            com.qbot.core.model.MessageKind.GUILD -> "c:${message.targetId}"
            com.qbot.core.model.MessageKind.DIRECT -> "d:${message.targetId}"
            com.qbot.core.model.MessageKind.C2C -> "u:${message.targetId}"
        }
}
