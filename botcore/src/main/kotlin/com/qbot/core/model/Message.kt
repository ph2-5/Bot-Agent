package com.qbot.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 消息来源场景。 */
enum class MessageKind {
    /** QQ 群内 @机器人 的消息 */
    GROUP_AT,

    /**
     * QQ 群内的普通消息（非 @机器人）。
     *
     * 只有群主在 QQ 客户端打开机器人的「接收所有消息」后才会推送；
     * 事件名为 GROUP_MESSAGE_CREATE，与 @ 消息共用订阅位 1<<25。
     * 它让机器人第一次能"听见"整个群在聊什么。
     */
    GROUP_MESSAGE,

    /** QQ 消息列表里的单聊消息 */
    C2C,

    /** 频道内消息 */
    GUILD,

    /** 频道私信 */
    DIRECT,
}

/**
 * 归一化后的入站消息。
 *
 * 官方不同事件的字段命名并不统一（群聊用 `author.member_openid`，
 * 单聊用 `author.user_openid`，频道用 `author.id`），
 * 这里统一抽成一条结构供规则引擎消费；同时保留 [raw]，
 * 让上层能读到尚未纳入模型的字段，避免因平台新增字段而丢信息。
 */
data class IncomingMessage(
    val kind: MessageKind,
    /** Dispatch 事件的 t 字段，如 GROUP_AT_MESSAGE_CREATE */
    val eventType: String,
    val messageId: String?,
    /** 已去除首尾空白的纯文本；非文本消息可能为空串 */
    val content: String,
    /** 回复目标：群聊为 group_openid，单聊为 user_openid */
    val targetId: String?,
    /**
     * 发送者的稳定标识。
     *
     * 群聊为 `author.member_openid`（官方 botpy SDK 的 GroupMessage.author.member_openid），
     * 单聊为 `author.user_openid`，频道为 `author.id`。
     * 注意群内的 member_openid 是按群分配的，同一个人在不同群里值不同。
     */
    val senderId: String?,
    /**
     * 发送者展示名（若报文直接提供）。
     *
     * 目前仅频道消息的 `member.nick` 会带昵称；群聊与单聊官方不提供
     * 昵称查询接口，展示名由上层身份名录（自称名/人工备注）补齐。
     */
    val senderName: String? = null,
    val timestampMillis: Long,
    val raw: JsonObject,
)

/** 事件类型常量。 */
object EventType {
    const val READY = "READY"
    const val RESUMED = "RESUMED"
    const val GROUP_AT_MESSAGE_CREATE = "GROUP_AT_MESSAGE_CREATE"

    /**
     * 群内全量消息（含未 @ 机器人的）。
     *
     * 官方 wiki：https://bot.qq.com/wiki/develop/api-v2/autogen/event/group_message_create.html
     * Intent 同为 GROUP_AND_C2C_EVENT (1<<25)；但需要群主在群设置里
     * 打开「接收所有消息」才会推送，按群独立授权（回调 GROUP_MSG_RECEIVE）。
     */
    const val GROUP_MESSAGE_CREATE = "GROUP_MESSAGE_CREATE"
    const val C2C_MESSAGE_CREATE = "C2C_MESSAGE_CREATE"
    const val AT_MESSAGE_CREATE = "AT_MESSAGE_CREATE"
    const val MESSAGE_CREATE = "MESSAGE_CREATE"
    const val DIRECT_MESSAGE_CREATE = "DIRECT_MESSAGE_CREATE"
    const val INTERACTION_CREATE = "INTERACTION_CREATE"

    // ---- 群/好友生命周期通知（Intent GROUP_AND_C2C_EVENT 1<<25） ----
    const val GROUP_ADD_ROBOT = "GROUP_ADD_ROBOT"
    const val GROUP_DEL_ROBOT = "GROUP_DEL_ROBOT"
    const val FRIEND_ADD = "FRIEND_ADD"
    const val FRIEND_DEL = "FRIEND_DEL"
    const val GROUP_MSG_RECEIVE = "GROUP_MSG_RECEIVE"
    const val GROUP_MSG_REJECT = "GROUP_MSG_REJECT"

    // ---- 群成员事件（Intent GROUP_MEMBER_EVENT 1<<24，官方 wiki 已上线） ----
    const val GROUP_MEMBER_ADD = "GROUP_MEMBER_ADD"
}

/** 群/好友生命周期通知的类型。 */
enum class GroupNoticeKind {
    /** 机器人被拉进群 */
    ROBOT_ADDED,

    /** 机器人被移出群 */
    ROBOT_REMOVED,

    /** 新成员加入群 */
    MEMBER_ADDED,

    /** 用户添加机器人为好友 */
    FRIEND_ADDED,

    /** 用户删除机器人好友 */
    FRIEND_REMOVED,

    /** 群管理员开启了机器人的主动消息通知 */
    PROACTIVE_ALLOWED,

    /** 群管理员关闭了机器人的主动消息通知 */
    PROACTIVE_REJECTED,
}

/**
 * 群/好友生命周期通知。
 *
 * 这是机器人「感知群里有谁」的主要渠道：平台不提供群成员列表接口，
 * 但每次有人进群都会推 [GroupNoticeKind.MEMBER_ADDED]，积少成多即可
 * 拼出群成员画像（openid 级别，展示名仍靠自称/人工备注补齐）。
 */
data class GroupNotice(
    val kind: GroupNoticeKind,
    /** 群 openid；好友类事件为 null */
    val groupId: String?,
    /** 操作者/成员 openid（op_member_openid / member_openid / openid） */
    val actorId: String?,
    val timestampMillis: Long,
)

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

private fun JsonObject.nestedStringOrNull(parent: String, key: String): String? =
    (get(parent) as? JsonObject)?.stringOrNull(key)

/**
 * 把 Dispatch 帧解析为 [IncomingMessage]。
 *
 * 只处理消息类事件，其余事件（READY / RESUMED / 互动事件等）返回 null，
 * 由网关层走其他分支处理。
 */
fun parseIncomingMessage(eventType: String, d: JsonElement?): IncomingMessage? {
    val obj = d as? JsonObject ?: return null

    val kind =
        when (eventType) {
            EventType.GROUP_AT_MESSAGE_CREATE -> MessageKind.GROUP_AT
            EventType.GROUP_MESSAGE_CREATE -> MessageKind.GROUP_MESSAGE
            EventType.C2C_MESSAGE_CREATE -> MessageKind.C2C
            EventType.AT_MESSAGE_CREATE, EventType.MESSAGE_CREATE -> MessageKind.GUILD
            EventType.DIRECT_MESSAGE_CREATE -> MessageKind.DIRECT
            else -> return null
        }

    val targetId =
        when (kind) {
            MessageKind.GROUP_AT, MessageKind.GROUP_MESSAGE -> obj.stringOrNull("group_openid")
            MessageKind.GUILD -> obj.stringOrNull("channel_id") ?: obj.stringOrNull("guild_id")
            MessageKind.DIRECT -> obj.stringOrNull("guild_id")
            MessageKind.C2C -> obj.nestedStringOrNull("author", "user_openid")
        }

    val senderId =
        when (kind) {
            // 官方 botpy：GroupMessage.author.member_openid（群里每个人的稳定标识）
            MessageKind.GROUP_AT, MessageKind.GROUP_MESSAGE ->
                obj.nestedStringOrNull("author", "member_openid")
                    ?: obj.stringOrNull("op_member_openid")
                    ?: obj.nestedStringOrNull("author", "user_openid")
                    ?: obj.nestedStringOrNull("author", "id")
            // 官方 botpy：C2CMessage.author.user_openid
            MessageKind.C2C ->
                obj.nestedStringOrNull("author", "user_openid")
                    ?: obj.nestedStringOrNull("author", "id")
            // 频道消息 author.id + member.nick（官方 MessagePayload）
            MessageKind.GUILD, MessageKind.DIRECT ->
                obj.nestedStringOrNull("author", "id")
                    ?: obj.nestedStringOrNull("author", "user_openid")
        }

    val senderName =
        when (kind) {
            MessageKind.GUILD, MessageKind.DIRECT -> obj.nestedStringOrNull("member", "nick")
            // 全量群消息的 author.username 是真实 QQ 昵称——
            // 这是平台唯一主动提供昵称的场景，值得记进身份名录。
            MessageKind.GROUP_MESSAGE -> obj.nestedStringOrNull("author", "username")
            else -> null
        }

    val timestamp =
        obj["timestamp"]?.jsonPrimitive?.longOrNull
            ?: run {
                val iso = obj.stringOrNull("timestamp") ?: obj.stringOrNull("timestamp_ms")
                iso?.toLongOrNull() ?: System.currentTimeMillis()
            }

    val content = obj.stringOrNull("content")?.trim().orEmpty()

    return IncomingMessage(
        kind = kind,
        eventType = eventType,
        messageId = obj.stringOrNull("id"),
        content = content,
        targetId = targetId,
        senderId = senderId,
        senderName = senderName,
        timestampMillis = if (timestamp > 1_000_000_000_000L) timestamp else timestamp * 1000,
        raw = obj,
    )
}

/**
 * 把 Dispatch 帧解析为 [GroupNotice]。
 *
 * 覆盖官方 GROUP_AND_C2C_EVENT（1<<25）与 GROUP_MEMBER_EVENT（1<<24）
 * 下的全部群/好友生命周期事件；其余事件返回 null。
 *
 * 载荷字段（官方 wiki）：
 * - GROUP_ADD_ROBOT / GROUP_DEL_ROBOT：{timestamp, group_openid, op_member_openid}
 * - GROUP_MEMBER_ADD：{timestamp, group_openid, member_openid, user_openid}
 * - GROUP_MSG_RECEIVE / GROUP_MSG_REJECT：{timestamp, group_openid}
 * - FRIEND_ADD / FRIEND_DEL：{timestamp, openid}
 */
fun parseGroupNotice(
    eventType: String,
    d: JsonElement?,
): GroupNotice? {
    val obj = d as? JsonObject ?: return null

    val kind =
        when (eventType) {
            EventType.GROUP_ADD_ROBOT -> GroupNoticeKind.ROBOT_ADDED
            EventType.GROUP_DEL_ROBOT -> GroupNoticeKind.ROBOT_REMOVED
            EventType.GROUP_MEMBER_ADD -> GroupNoticeKind.MEMBER_ADDED
            EventType.FRIEND_ADD -> GroupNoticeKind.FRIEND_ADDED
            EventType.FRIEND_DEL -> GroupNoticeKind.FRIEND_REMOVED
            EventType.GROUP_MSG_RECEIVE -> GroupNoticeKind.PROACTIVE_ALLOWED
            EventType.GROUP_MSG_REJECT -> GroupNoticeKind.PROACTIVE_REJECTED
            else -> return null
        }

    val seconds =
        obj["timestamp"]?.jsonPrimitive?.longOrNull
            ?: obj.stringOrNull("timestamp")?.toLongOrNull()

    // 与消息解析同一套 heuristic：超过 1e12 视为毫秒，否则按秒换算
    val millis =
        when {
            seconds == null || seconds <= 0L -> System.currentTimeMillis()
            seconds > 1_000_000_000_000L -> seconds
            else -> seconds * 1000
        }

    return GroupNotice(
        kind = kind,
        groupId = obj.stringOrNull("group_openid"),
        actorId =
            obj.stringOrNull("op_member_openid")
                ?: obj.stringOrNull("member_openid")
                ?: obj.stringOrNull("openid")
                ?: obj.nestedStringOrNull("author", "user_openid"),
        timestampMillis = millis,
    )
}

/**
 * 取出用户实际想说的话。
 *
 * 群聊里 @机器人 的内容会以 `<@!botid>` 形式混在 content 开头，
 * 规则匹配前需要剥掉，否则每条消息都带一串无意义前缀。
 */
fun IncomingMessage.cleanText(botId: String = ""): String {
    var text = content
    if (botId.isNotBlank()) {
        text = text.replace("<@!$botId>", "").replace("@$botId", "")
    }
    return text.replace(Regex("<@!?\\d+>"), "").trim()
}
