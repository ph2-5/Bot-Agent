package com.qbot.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * WebSocket 网关 OpCode。
 *
 * 参考：https://bot.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/websocket.html
 */
object OpCode {
    /** 服务端推送事件 */
    const val DISPATCH = 0

    /** 客户端发送心跳 */
    const val HEARTBEAT = 1

    /** 客户端登录鉴权 */
    const val IDENTIFY = 2

    /** 客户端恢复会话 */
    const val RESUME = 6

    /** 服务端要求客户端重连 */
    const val RECONNECT = 7

    /** 请求失败 / 无效会话 */
    const val INVALID_SESSION = 9

    /** 服务端下发 Hello（含心跳周期） */
    const val HELLO = 10

    /** 心跳应答 */
    const val HEARTBEAT_ACK = 11
}

/**
 * 网关帧。
 *
 * `d` 的类型随 op 变化，因此保留为 [JsonElement]，
 * 由各状态处理分支按 op 自行解码，避免泛型序列化带来的脆弱性。
 */
@Serializable
data class GatewayPayload(
    val op: Int,
    val d: JsonElement? = null,
    val s: Int? = null,
    val t: String? = null,
)

/** Hello（op 10）载荷。 */
@Serializable
data class HelloData(
    @SerialName("heartbeat_interval") val heartbeatInterval: Long,
)

/** Identify（op 2）载荷。 */
@Serializable
data class IdentifyData(
    val token: String,
    val intents: Int,
    val shard: List<Int>,
    val properties: Map<String, String> = emptyMap(),
)

/** Resume（op 6）载荷。 */
@Serializable
data class ResumeData(
    val token: String,
    @SerialName("session_id") val sessionId: String,
    val seq: Int,
)

/** READY 事件（op 0 / t=READY）载荷。 */
@Serializable
data class ReadyData(
    val version: Int,
    @SerialName("session_id") val sessionId: String,
    val user: ReadyUser? = null,
    val shard: List<Int> = emptyList(),
)

@Serializable
data class ReadyUser(
    val id: String,
    val username: String,
    val bot: Boolean = true,
)

/**
 * 网关错误码。
 *
 * 处理方式参考官方建议：
 * - 4009 / 4006：可重连并 resume（4006 需退回 identify）
 * - 4914（已下架）/ 4915（已封禁）：不可连接，需人工处理
 * - 其他：重新 identify
 */
object GatewayCloseCode {
    const val INVALID_OPCODE = 4001
    const val INVALID_PAYLOAD = 4002
    const val INVALID_SEQ = 4007
    const val INVALID_SESSION_ID = 4006
    const val RATE_LIMITED = 4008
    const val SESSION_EXPIRED = 4009
    const val INVALID_SHARD = 4010
    const val TOO_MANY_GUILDS = 4011
    const val INVALID_VERSION = 4012
    const val INVALID_INTENT = 4013
    const val UNAUTHORIZED_INTENT = 4014

    /** 机器人已下架，只允许连接沙箱环境 */
    const val BOT_OFFLINE = 4914

    /** 机器人已封禁 */
    const val BOT_BANNED = 4915

    /** 内部错误区间，可重连 */
    fun isInternalError(code: Int): Boolean = code in 4900..4913

    /**
     * 该关闭码是否表示 intents 配置问题。
     *
     * 官方错误码表将 4013（无效的 intent）/ 4014（intent 无权限）
     * 标注为「不可重试」——重新 Identify 一万次也不会成功，
     * 盲目重连只会把机器人锁死在「连接中 → 鉴权中」的循环里。
     */
    fun isIntentError(code: Int): Boolean = code == INVALID_INTENT || code == UNAUTHORIZED_INTENT

    /**
     * 该关闭码是否属于重连也无济于事的配置/参数错误。
     * 这些码需要用户修正配置后才能恢复，直接停机并给出指引，
     * 比无限重连更省电、也更不容易让人误以为"再等等就好了"。
     */
    fun isConfigError(code: Int): Boolean =
        code == INVALID_OPCODE ||
            code == INVALID_PAYLOAD ||
            code == INVALID_SHARD ||
            code == TOO_MANY_GUILDS ||
            code == INVALID_VERSION

    /**
     * 该关闭码是否值得继续重连。
     * 下架 / 封禁属于不可恢复状态，继续重连只会白白耗电。
     */
    fun isFatal(code: Int): Boolean =
        code == BOT_OFFLINE || code == BOT_BANNED || isConfigError(code)

    /** 是否可以通过 Resume 恢复（否则需要重新 Identify）。 */
    fun canResume(code: Int): Boolean =
        code == SESSION_EXPIRED ||
            code == RATE_LIMITED ||
            code == INVALID_SEQ ||
            isInternalError(code)
}

/** 构造 Identify 帧。 */
fun buildIdentify(
    token: String,
    intents: Int,
    shard: List<Int> = listOf(0, 1),
    properties: Map<String, String> = emptyMap(),
): GatewayPayload =
    GatewayPayload(
        op = OpCode.IDENTIFY,
        d = buildJsonObject {
            put("token", token)
            put("intents", intents)
            put("shard", buildJsonArray { shard.forEach { add(JsonPrimitive(it)) } })
            put("properties", buildJsonObject { properties.forEach { (k, v) -> put(k, v) } })
        },
    )

/** 构造 Resume 帧。 */
fun buildResume(token: String, sessionId: String, seq: Int): GatewayPayload =
    GatewayPayload(
        op = OpCode.RESUME,
        d = buildJsonObject {
            put("token", token)
            put("session_id", sessionId)
            put("seq", seq)
        },
    )

/**
 * 构造心跳帧。
 *
 * 报文形如 `{"op":1,"d":251}`；首次连接还没有收到过任何事件时 `d` 为 null。
 */
fun buildHeartbeat(seq: Int?): GatewayPayload =
    GatewayPayload(
        op = OpCode.HEARTBEAT,
        d = if (seq == null) JsonNull else JsonPrimitive(seq),
    )

/** 从 Hello 帧中安全取出心跳周期，解析失败时回落到 45s。 */
fun parseHeartbeatInterval(payload: GatewayPayload): Long =
    payload.d
        ?.jsonObject
        ?.get("heartbeat_interval")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?: 45_000L

/** 从 Dispatch 帧中取出 READY 的 session_id。 */
fun parseSessionId(payload: GatewayPayload): String? =
    payload.d?.jsonObject?.get("session_id")?.jsonPrimitive?.contentOrNull

/** 从 Dispatch 帧中取出 seq（s 字段）。 */
fun parseSeq(payload: GatewayPayload): Int? = payload.s
