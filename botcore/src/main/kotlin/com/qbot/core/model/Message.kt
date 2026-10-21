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
 * 官方不同事件的字段命名并不统一（群聊用 `group_openid`，单聊用 `author.user_openid`），
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
    val senderId: String?,
    val timestampMillis: Long,
    val raw: JsonObject,
)

/** 事件类型常量。 */
object EventType {
    const val READY = "READY"
    const val RESUMED = "RESUMED"
    const val GROUP_AT_MESSAGE_CREATE = "GROUP_AT_MESSAGE_CREATE"
    const val C2C_MESSAGE_CREATE = "C2C_MESSAGE_CREATE"
    const val AT_MESSAGE_CREATE = "AT_MESSAGE_CREATE"
    const val MESSAGE_CREATE = "MESSAGE_CREATE"
    const val DIRECT_MESSAGE_CREATE = "DIRECT_MESSAGE_CREATE"
    const val INTERACTION_CREATE = "INTERACTION_CREATE"
}

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
            EventType.C2C_MESSAGE_CREATE -> MessageKind.C2C
            EventType.AT_MESSAGE_CREATE, EventType.MESSAGE_CREATE -> MessageKind.GUILD
            EventType.DIRECT_MESSAGE_CREATE -> MessageKind.DIRECT
            else -> return null
        }

    val targetId =
        when (kind) {
            MessageKind.GROUP_AT -> obj.stringOrNull("group_openid")
            MessageKind.GUILD -> obj.stringOrNull("channel_id") ?: obj.stringOrNull("guild_id")
            MessageKind.DIRECT -> obj.stringOrNull("guild_id")
            MessageKind.C2C -> obj.nestedStringOrNull("author", "user_openid")
        }

    val senderId =
        when (kind) {
            MessageKind.C2C -> obj.nestedStringOrNull("author", "id")
                ?: obj.nestedStringOrNull("author", "user_openid")
            MessageKind.GUILD, MessageKind.DIRECT -> obj.nestedStringOrNull("author", "id")
            MessageKind.GROUP_AT -> obj.stringOrNull("op_member_openid")
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
        timestampMillis = if (timestamp > 1_000_000_000_000L) timestamp else timestamp * 1000,
        raw = obj,
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
