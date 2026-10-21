package com.qbot.core.gateway

import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.ReadyUser
import kotlinx.serialization.json.JsonObject

/**
 * 网关事件回调。
 *
 * 所有回调都在网关所在的后台线程触发，**不要在这里直接操作 UI**；
 * UI 请订阅 [GatewayClient.status]。
 */
interface GatewayListener {
    /** 鉴权成功、会话建立。 */
    fun onReady(sessionId: String, botUser: ReadyUser?)

    /** 收到一条已归一化的入站消息。 */
    fun onMessage(message: IncomingMessage)

    /** 收到任意事件（含非消息类），可用于审计日志与后续扩展。 */
    fun onRawEvent(eventType: String, payload: JsonObject)

    /** 连接状态变化。 */
    fun onStatusChanged(status: GatewayStatus)
}

/** 默认空实现，便于只覆写关心的方法。 */
open class SimpleGatewayListener : GatewayListener {
    override fun onReady(sessionId: String, botUser: ReadyUser?) = Unit
    override fun onMessage(message: IncomingMessage) = Unit
    override fun onRawEvent(eventType: String, payload: JsonObject) = Unit
    override fun onStatusChanged(status: GatewayStatus) = Unit
}
