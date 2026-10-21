package com.qbot.core.gateway

/** 网关连接状态。 */
enum class ConnectionState {
    /** 未启动或已停止 */
    IDLE,

    /** 正在获取网关地址 / 建立 WebSocket 连接 */
    CONNECTING,

    /** 已连接，正在发送 Identify */
    IDENTIFYING,

    /** 已就绪，正常收发事件（含心跳保活中） */
    READY,

    /** 正在尝试 Resume 恢复会话 */
    RESUMING,

    /** 连接中断，等待退避重连 */
    RECONNECTING,

    /** 因不可恢复错误（下架 / 封禁）而停止 */
    FATAL,
}

/**
 * 网关运行快照。
 *
 * 由 [GatewayClient.status] 以 StateFlow 暴露，UI 直接订阅即可渲染仪表盘，
 * 无需自己维护一份易失的连接状态副本。
 */
data class GatewayStatus(
    val state: ConnectionState = ConnectionState.IDLE,
    val sessionId: String? = null,
    /** 最近一次收到的事件序号，用于 Resume */
    val lastEventSeq: Int? = null,
    /** 本轮生命周期内的重连次数 */
    val reconnectCount: Int = 0,
    val lastError: String? = null,
    /** 本次成功就绪的起始时刻，用于计算在线时长 */
    val readySinceMillis: Long? = null,
    val lastHeartbeatAckMillis: Long? = null,
    /** 累计收到的事件数 */
    val totalEvents: Long = 0,
    /** 累计收到的消息数 */
    val totalMessages: Long = 0,
) {
    val isConnected: Boolean
        get() = state == ConnectionState.READY
}
