package com.qbot.core.gateway

import com.qbot.core.api.OpenApi
import com.qbot.core.auth.AccessTokenManager
import com.qbot.core.model.EventType
import com.qbot.core.model.GatewayCloseCode
import com.qbot.core.model.GatewayPayload
import com.qbot.core.model.Intents
import com.qbot.core.model.OpCode
import com.qbot.core.model.ReadyUser
import com.qbot.core.model.buildHeartbeat
import com.qbot.core.model.buildIdentify
import com.qbot.core.model.buildResume
import com.qbot.core.model.parseHeartbeatInterval
import com.qbot.core.model.parseIncomingMessage
import com.qbot.core.model.parseSessionId
import com.qbot.core.util.Clock
import com.qbot.core.util.Logger
import com.qbot.core.util.NoopLogger
import com.qbot.core.util.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * QQ 机器人 WebSocket 网关客户端。
 *
 * 完整实现官方网关状态机：
 *
 * ```
 * 连接 → Hello(10) → Identify(2) / Resume(6) → READY(0) → 心跳(1) ⇄ ACK(11)
 *                                                        ↘ 断线 → 退避重连 ↺
 * ```
 *
 * 针对手机 7x24 挂机场景做了几处关键加固：
 *
 * 1. **心跳僵死检测**：TCP 长连接在移动网络下会变成"半死"状态——
 *    既不报错也收不到数据。因此除了按时发心跳，还会监控 ACK 是否逾期，
 *    超时即判定僵死并主动重连。
 * 2. **连接纪元（epoch）**：每次新建连接递增，所有异步回调先校验纪元再处理，
 *    避免旧连接的迟到回调污染新连接状态。
 * 3. **错误码分流**：4009 走 Resume 保留会话，4914/4915 属不可恢复直接停机，
 *    其余重新 Identify——避免封禁状态下无意义地反复重连耗光电量。
 * 4. **网关地址缓存**：`/gateway/bot` 是有限频的 HTTP 接口，而重连在弱网下
 *    可能频繁发生。每次重连都重新取地址会在几十秒内打满频控（HTTP 400
 *    "接口调用超过频率限制"），把机器人锁死在重连循环里。因此地址取到一次后
 *    缓存复用，只有握手被拒（401/403/404/410，地址可能已轮换）才清缓存重取。
 * 5. **限频冷静期**：万一仍然触发频控，自动把重连间隔拉长到分钟级，
 *    给服务端恢复的时间，而不是按秒级退避继续撞墙。
 */
class GatewayClient(
    private val api: OpenApi,
    private val auth: AccessTokenManager,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val intents: Int = Intents.DEFAULT,
    private val clock: Clock = SystemClock,
    private val logger: Logger = NoopLogger,
    private val listener: GatewayListener? = null,
    private val properties: Map<String, String> =
        mapOf(
            "\$os" to "android",
            "\$browser" to "QBotMobile",
            "\$device" to "QBotMobile",
        ),
    /** 僵死检测轮询间隔；测试里调小以免等 10 秒 */
    private val monitorIntervalMillis: Long = MONITOR_INTERVAL_MILLIS,
) {
    companion object {
        private const val TAG = "Gateway"

        /** 心跳 ACK 逾期倍数：超过 interval * 该值未收到 ACK 即判定连接僵死 */
        private const val HEARTBEAT_TIMEOUT_FACTOR = 2.5

        /** 僵死检测轮询间隔 */
        private const val MONITOR_INTERVAL_MILLIS = 10_000L

        /**
         * 心跳发送失败后立即重连的频率保护窗口。
         *
         * 发送失败说明连接已废，没必要再等一个心跳周期；
         * 但若服务端整体不可达，每次失败都立即重连会变成秒级空转，
         * 因此同一窗口内只触发一次，其余交给退避节奏。
         */
        private const val HEARTBEAT_SEND_FAIL_GUARD_MILLIS = 5_000L

        /**
         * 触发限频后的冷静期时长。
         *
         * 官方对 /gateway/bot 的频控窗口通常以分钟计，秒级退避等不到窗口结束，
         * 只会让每次重试都再次触发限频，形成死循环。
         */
        private const val RATE_LIMIT_COOLDOWN_MILLIS = 90_000L

        /** 冷静期内的重连基准间隔（指数退避上限同样拉长） */
        private const val RATE_LIMIT_BASE_MILLIS = 60_000L
        private const val RATE_LIMIT_MAX_MILLIS = 120_000L
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private val mutex = Mutex()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var heartbeatInterval: Long = 45_000L

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var lastSeq: Int? = null

    @Volatile
    private var lastHeartbeatSentAt: Long = 0L

    @Volatile
    private var lastHeartbeatAckAt: Long = 0L

    /** 上一次心跳发送失败的时刻，用于立即重连的频率保护 */
    @Volatile
    private var lastHeartbeatSendFailAt: Long = 0L

    @Volatile
    private var running: Boolean = false

    /**
     * 网关地址缓存。
     *
     * `/gateway/bot` 限频严格，重连必须复用地址；仅在握手被拒时清除。
     */
    @Volatile
    private var cachedGatewayUrl: String? = null

    /** 限频冷静期截止时刻，期间重连间隔拉长到分钟级 */
    @Volatile
    private var rateLimitCooldownUntil: Long = 0L

    /** 当前连接纪元，用于丢弃旧连接的迟到回调 */
    private val epoch = AtomicInteger(0)

    /** 已见过的事件类型，用于"首次收到"日志只打一次 */
    private val seenEventTypes: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val reconnectScheduled = AtomicBoolean()

    /**
     * 初始订阅位。降级后可通过 [stop] + [start] 恢复
     * （用户在平台申请到权限后重新保存凭证即走这条路）。
     */
    private val initialIntents: Int = intents

    /** 当前生效的订阅位，可能被自动降级 */
    @Volatile
    private var currentIntents: Int = intents

    /**
     * 降级等级：0=完整订阅，1=仅消息事件，2=基础事件。
     * 每被服务端拒绝一次就降一级，全部被拒才停机。
     */
    @Volatile
    private var degradeLevel: Int = 0

    private var heartbeatJob: Job? = null
    private var monitorJob: Job? = null
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null

    private var _status = GatewayStatus()

    /** 对外暴露的运行快照流。 */
    private val _statusFlow = MutableStateFlow(GatewayStatus())
    val status: StateFlow<GatewayStatus> = _statusFlow

    /**
     * 事件监听器。
     *
     * 做成可写字段而非构造参数，是为了解开一处循环依赖：
     * 消息处理器（BotRuntime）需要网关来发消息，而网关又需要它来收消息。
     * 构造完成后再 [setListener] 接上即可。
     */
    @Volatile
    private var listenerRef: GatewayListener? = listener

    fun setListener(listener: GatewayListener?) {
        listenerRef = listener
    }

    private fun updateStatus(mutate: GatewayStatus.() -> GatewayStatus) {
        _status = _status.mutate()
        _statusFlow.value = _status
        try {
            listenerRef?.onStatusChanged(_status)
        } catch (t: Throwable) {
            logger.e(TAG, "onStatusChanged 回调异常", t)
        }
    }

    /** 启动网关。重复调用无副作用。 */
    fun start() {
        if (running) {
            logger.d(TAG, "已在运行中，忽略重复 start")
            return
        }
        running = true
        logger.i(TAG, "启动网关")
        launchConnect()
        startMonitor()
    }

    /** 停止网关并释放所有协程。 */
    fun stop() {
        if (!running) return
        running = false
        logger.i(TAG, "停止网关")
        epoch.incrementAndGet()
        heartbeatJob?.cancel()
        monitorJob?.cancel()
        connectJob?.cancel()
        reconnectJob?.cancel()
        try {
            webSocket?.cancel()
        } catch (t: Throwable) {
            logger.w(TAG, "关闭 WebSocket 时异常：${t.message}")
        }
        webSocket = null
        sessionId = null
        lastSeq = null
        // 恢复初始订阅位：用户去平台申请到权限后重新保存凭证，
        // 应该用完整事件重试，而不是沿用上次的降级集合。
        currentIntents = initialIntents
        degradeLevel = 0
        updateStatus { copy(state = ConnectionState.IDLE, sessionId = null, readySinceMillis = null) }
    }

    /**
     * 主动立即重连。
     *
     * 网络恢复广播、用户手动点"重新连接"时调用。会重置退避计数，
     * 因为此时通常意味着网络已就绪，没必要继续等待退避窗口。
     */
    fun reconnectNow(reason: String) {
        if (!running) {
            logger.w(TAG, "未运行，忽略重连请求：$reason")
            return
        }
        logger.i(TAG, "立即重连：$reason")
        reconnectJob?.cancel()
        epoch.incrementAndGet()
        closeSocketQuietly()
        launchConnect(resetBackoff = true)
    }

    private fun launchConnect(resetBackoff: Boolean = false) {
        connectJob?.cancel()
        if (resetBackoff) updateStatus { copy(reconnectCount = 0) }
        val myEpoch = epoch.incrementAndGet()
        reconnectScheduled.set(false)

        connectJob =
            scope.launch {
                try {
                    doConnect(myEpoch)
                } catch (t: Throwable) {
                    logger.e(TAG, "连接流程异常", t)
                    if (myEpoch == epoch.get() && running) {
                        handleDisconnect(myEpoch, 0, "连接异常：${t.message}")
                    }
                }
            }
    }

    private suspend fun doConnect(myEpoch: Int) {
        updateStatus { copy(state = ConnectionState.CONNECTING, lastError = null) }

        // 建连前就取好凭证：之后 Hello 到达时可直接使用，
        // 避免在 WebSocket 回调线程里做网络请求。
        val token = auth.getToken()
        if (myEpoch != epoch.get()) return

        val url =
            cachedGatewayUrl
                ?: run {
                    val gateway =
                        try {
                            api.getGateway()
                        } catch (t: Throwable) {
                            // 命中频控：标记冷静期，让重连节奏自动放慢，
                            // 否则秒级退避会一直撞在频控窗口上。
                            if (t.isRateLimited()) {
                                rateLimitCooldownUntil =
                                    clock.nowMillis() + RATE_LIMIT_COOLDOWN_MILLIS
                                logger.w(TAG, "网关接口触发频率限制，进入 ${RATE_LIMIT_COOLDOWN_MILLIS / 1000}s 冷静期")
                            }
                            logger.e(TAG, "获取网关地址失败", t)
                            updateStatus { copy(lastError = "获取网关失败：${t.message}") }
                            throw t
                        }
                    if (myEpoch != epoch.get()) return
                    // 取到即缓存：后续重连（含网络抖动、服务端 op7）都复用它，
                    // 不再打这个限频接口。
                    cachedGatewayUrl = gateway.url
                    logger.i(TAG, "网关地址：${gateway.url}（分片 ${gateway.shards}）")
                    gateway.url
                }

        if (url.isBlank()) {
            cachedGatewayUrl = null
            throw IllegalStateException("网关地址为空")
        }

        logger.d(TAG, "连接网关：$url")
        val request = Request.Builder().url(url).build()
        webSocket = http.newWebSocket(request, createSocketListener(myEpoch, token))
    }

    /** 判断一次取地址失败是否由接口限频引起。 */
    private fun Throwable.isRateLimited(): Boolean {
        val msg = message.orEmpty()
        return msg.contains("频率限制") || msg.contains("rate limit", ignoreCase = true) ||
            msg.contains("429")
    }

    private fun createSocketListener(
        myEpoch: Int,
        token: String,
    ) =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                if (myEpoch != epoch.get()) {
                    logger.d(TAG, "忽略过期连接的 onOpen")
                    return
                }
                logger.i(TAG, "WebSocket 已建立，等待 Hello")
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                if (myEpoch != epoch.get()) return
                handleFrame(myEpoch, text, token)
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                logger.w(TAG, "服务端发起关闭：code=$code reason=$reason")
                webSocket.close(code, reason)
                if (myEpoch == epoch.get()) handleDisconnect(myEpoch, code, reason)
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) {
                logger.e(TAG, "WebSocket 异常：${t.message}", t)
                // 握手阶段就被拒（OkHttp 仅在握手失败时给出 response）：
                // 401/403 多为凭证问题，404/410 说明地址已轮换。
                // 两种情况都清掉缓存，让下次重连重新取地址，而不是拿着废地址反复撞墙。
                val code = response?.code
                if (code != null && (code == 401 || code == 403 || code == 404 || code == 410)) {
                    logger.w(TAG, "握手被拒（HTTP $code），清除网关地址缓存，下次重新获取")
                    cachedGatewayUrl = null
                }
                if (myEpoch == epoch.get()) {
                    handleDisconnect(myEpoch, 0, t.message ?: "网络异常")
                }
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                logger.w(TAG, "WebSocket 已关闭：code=$code reason=$reason")
                if (myEpoch == epoch.get()) handleDisconnect(myEpoch, code, reason)
            }
        }

    private fun handleFrame(
        myEpoch: Int,
        text: String,
        token: String,
    ) {
        val payload =
            try {
                json.decodeFromString<GatewayPayload>(text)
            } catch (t: Throwable) {
                logger.e(TAG, "无法解析帧：$text", t)
                return
            }

        when (payload.op) {
            OpCode.HELLO -> onHello(myEpoch, payload, token)
            OpCode.DISPATCH -> onDispatch(payload)
            OpCode.HEARTBEAT_ACK -> onHeartbeatAck()
            OpCode.RECONNECT -> {
                logger.w(TAG, "服务端要求重连（op 7）")
                reconnectNow("服务端要求重连")
            }

            OpCode.INVALID_SESSION -> {
                if (sessionId == null) {
                    // 本地没有会话，说明刚发出的 Identify 被服务端拒绝。
                    // 典型原因：intents 含未定义位、或订阅了未申请的事件权限。
                    // 此时无脑重连只会永远卡在「连接中 → 鉴权中」循环，
                    // 必须按事件订阅拒绝处理：先降级重试，再拒才停机。
                    logger.w(TAG, "Identify 被服务端拒绝（op9 Invalid Session）")
                    handleIntentRejection(myEpoch, "op9 Invalid Session")
                } else {
                    logger.w(TAG, "无效会话（op 9），清除会话并重新登录")
                    sessionId = null
                    lastSeq = null
                    reconnectNow("会话失效，重新登录")
                }
            }

            else -> logger.w(TAG, "未处理的 op=${payload.op}")
        }
    }

    private fun onHello(
        myEpoch: Int,
        payload: GatewayPayload,
        token: String,
    ) {
        heartbeatInterval = parseHeartbeatInterval(payload)
        logger.i(TAG, "收到 Hello，心跳周期 ${heartbeatInterval}ms")

        val sid = sessionId
        if (sid != null) {
            updateStatus { copy(state = ConnectionState.RESUMING, intents = currentIntents) }
            send(buildResume("QQBot $token", sid, lastSeq ?: 0))
            logger.i(TAG, "已发送 Resume（session=$sid, seq=$lastSeq）")
        } else {
            updateStatus { copy(state = ConnectionState.IDENTIFYING, intents = currentIntents) }
            send(buildIdentify("QQBot $token", currentIntents, listOf(0, 1), properties))
            logger.i(TAG, "已发送 Identify（intents=$currentIntents）")
        }

        lastHeartbeatAckAt = clock.nowMillis()
        startHeartbeat(myEpoch)
    }

    private fun onDispatch(payload: GatewayPayload) {
        payload.s?.let { lastSeq = it }
        val eventType = payload.t ?: return
        val now = clock.nowMillis()

        // 首次收到某类事件时记一条 INFO：排障时能直接看出
        // 「全量消息到底有没有推过来」，而不用翻原始帧。
        if (seenEventTypes.add(eventType)) {
            logger.i(TAG, "首次收到事件类型：$eventType")
        }

        updateStatus {
            copy(
                lastEventSeq = lastSeq,
                totalEvents = this.totalEvents + 1,
                eventCounts =
                    eventCounts + (eventType to (eventCounts[eventType] ?: 0L) + 1L),
            )
        }

        val data = payload.d

        when (eventType) {
            EventType.READY -> {
                val sid = parseSessionId(payload)
                sessionId = sid
                logger.i(TAG, "READY：session=$sid")
                val user =
                    try {
                        data?.jsonObject?.get("user")?.let {
                            json.decodeFromJsonElement<ReadyUser>(it)
                        }
                    } catch (t: Throwable) {
                        null
                    }
                updateStatus {
                    copy(
                        state = ConnectionState.READY,
                        sessionId = sid,
                        readySinceMillis = clock.nowMillis(),
                        reconnectCount = 0,
                        lastError = null,
                    )
                }
                sid?.let {
                    try {
                        listenerRef?.onReady(it, user)
                    } catch (t: Throwable) {
                        logger.e(TAG, "onReady 回调异常", t)
                    }
                }
            }

            EventType.RESUMED -> {
                logger.i(TAG, "会话恢复完成，遗漏事件已补发")
                updateStatus {
                    copy(
                        state = ConnectionState.READY,
                        readySinceMillis = readySinceMillis ?: clock.nowMillis(),
                        reconnectCount = 0,
                        lastError = null,
                    )
                }
            }

            else -> {
                val message = parseIncomingMessage(eventType, data)
                if (message != null) {
                    updateStatus { copy(totalMessages = totalMessages + 1) }
                    try {
                        listenerRef?.onMessage(message)
                    } catch (t: Throwable) {
                        logger.e(TAG, "onMessage 回调异常", t)
                    }
                } else {
                    try {
                        data?.jsonObject?.let { listenerRef?.onRawEvent(eventType, it) }
                    } catch (t: Throwable) {
                        logger.e(TAG, "onRawEvent 回调异常", t)
                    }
                }
            }
        }

        logger.v(TAG, "事件 $eventType (seq=$lastSeq) @ $now")
    }

    private fun onHeartbeatAck() {
        lastHeartbeatAckAt = clock.nowMillis()
        updateStatus { copy(lastHeartbeatAckMillis = lastHeartbeatAckAt) }
        logger.v(TAG, "心跳 ACK")
    }

    private fun startHeartbeat(myEpoch: Int) {
        heartbeatJob?.cancel()
        heartbeatJob =
            scope.launch {
                while (isActive && myEpoch == epoch.get()) {
                    delay(heartbeatInterval)
                    if (myEpoch != epoch.get()) break
                    if (!running) break
                    val currentState = _status.state
                    if (currentState != ConnectionState.READY &&
                        currentState != ConnectionState.RESUMING &&
                        currentState != ConnectionState.IDENTIFYING
                    ) {
                        continue
                    }
                    if (!send(buildHeartbeat(lastSeq))) {
                        logger.w(TAG, "心跳发送失败，连接已废，立即重连")
                        val now = clock.nowMillis()
                        if (now - lastHeartbeatSendFailAt > HEARTBEAT_SEND_FAIL_GUARD_MILLIS) {
                            lastHeartbeatSendFailAt = now
                            reconnectNow("心跳发送失败")
                        }
                        continue
                    }
                    lastHeartbeatSentAt = clock.nowMillis()
                    logger.v(TAG, "已发送心跳 seq=$lastSeq")
                }
            }
    }

    /**
     * 僵死检测。
     *
     * 移动网络下最棘手的是"半开连接"：Wi-Fi 切 4G、进入电梯、NAT 老化，
     * 都会让 Socket 既不报错也收不到任何数据。此时心跳照发但永远等不到 ACK，
     * 若不主动干预，机器人会"看起来在线"实则已掉线数小时。
     */
    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob =
            scope.launch {
                while (isActive) {
                    delay(monitorIntervalMillis)
                    if (!running) continue
                    if (_status.state != ConnectionState.READY) continue
                    if (lastHeartbeatAckAt == 0L) continue

                    // 只依据 ACK 时间戳判定，不看 awaitingAck：
                    // 发送失败时它不会被置位，早期实现据此跳过检测，
                    // 把"连接已废但通知栏仍显示已在线"的僵尸连接误判成正常。
                    val overdue = clock.nowMillis() - lastHeartbeatAckAt
                    val threshold = (heartbeatInterval * HEARTBEAT_TIMEOUT_FACTOR).toLong()
                    if (overdue > threshold) {
                        logger.w(TAG, "心跳 ACK 逾期 ${overdue}ms（阈值 ${threshold}ms），判定连接僵死")
                        reconnectNow("心跳超时")
                    }
                }
            }
    }

    private fun handleDisconnect(
        myEpoch: Int,
        code: Int,
        reason: String?,
    ) {
        if (!running) return
        if (myEpoch != epoch.get()) return

        // 同一次断开可能同时触发 onClosing 与 onClosed 两个回调
        // （onClosing 里我们主动补了一次 close）。用 CAS 保证整段逻辑只执行一次，
        // 否则降级、重连调度会被跑两遍——第二次进来时降级标记已置位，
        // 会把本该"降级重试"的连接直接判成 FATAL。
        if (!reconnectScheduled.compareAndSet(false, true)) {
            logger.d(TAG, "本次断开已在处理中，忽略重复回调（code=$code）")
            return
        }

        heartbeatJob?.cancel()

        // intents 权限问题：官方错误码表标注 4013/4014「不可重试」。
        // 盲目重新 Identify 只会永远卡在「连接中 → 鉴权中」的循环里，
        // 因此首次遇到时自动降级到基础事件集重连（机器人至少能上线收频道消息），
        // 降级后仍被拒才停机，并给出平台侧的操作指引。
        if (GatewayCloseCode.isIntentError(code)) {
            handleIntentRejection(myEpoch, "code=$code")
            return
        }

        if (GatewayCloseCode.isFatal(code)) {
            val msg =
                when (code) {
                    GatewayCloseCode.BOT_OFFLINE -> "机器人已下架，仅允许连接沙箱环境"
                    GatewayCloseCode.BOT_BANNED -> "机器人已被封禁，请联系官方解封"
                    else -> "连接参数被服务端拒绝（code=$code），请检查配置后重试"
                }
            logger.e(TAG, msg)
            updateStatus { copy(state = ConnectionState.FATAL, lastError = msg) }
            running = false
            return
        }

        // 无法 Resume 的关闭码需要放弃旧会话，重新走 Identify
        if (code != 0 && !GatewayCloseCode.canResume(code) && code != 1000) {
            logger.w(TAG, "关闭码 $code 无法恢复会话，将重新登录")
            sessionId = null
            lastSeq = null
        }

        val attempt = _status.reconnectCount
        // 处于限频冷静期时改用分钟级退避：官方频控窗口通常以分钟计，
        // 继续按秒级重试只会反复触发限频，把机器人锁死在重连循环里。
        val rateLimited = clock.nowMillis() < rateLimitCooldownUntil
        val delayMillis =
            if (rateLimited) {
                Backoff.delayFor(
                    attempt,
                    baseMillis = RATE_LIMIT_BASE_MILLIS,
                    maxMillis = RATE_LIMIT_MAX_MILLIS,
                )
            } else {
                Backoff.delayFor(attempt)
            }
        val displayReason =
            if (rateLimited) {
                "接口调用超过频率限制，已延长重试间隔（约 ${delayMillis / 1000} 秒后重试）"
            } else {
                reason ?: "连接断开"
            }
        logger.w(TAG, "连接断开（code=$code, $reason），第 ${attempt + 1} 次重连，${delayMillis}ms 后重试")

        updateStatus {
            copy(
                state = ConnectionState.RECONNECTING,
                reconnectCount = attempt + 1,
                lastError = displayReason,
                readySinceMillis = null,
            )
        }

        reconnectJob?.cancel()
        reconnectJob =
            scope.launch {
                delay(delayMillis)
                if (!running || myEpoch != epoch.get()) return@launch
                launchConnect()
            }
    }

    /**
     * 处理「Identify 被服务端拒绝」。
     *
     * 两种触发路径：
     * 1. 关闭码 4013/4014（intent 无效/无权限，官方错误码表标注不可重试）；
     * 2. op9 Invalid Session 且本地无会话——说明刚发出的 Identify 参数被拒
     *    （intents 含未定义位、或订阅了未申请的权限都会走到这里）。
     *
     * 盲目重新 Identify 只会永远卡在「连接中 → 鉴权中」的循环里，
     * 因此按四级阶梯逐级降级重连：
     *   完整订阅 → 去掉群成员事件位 → 仅消息事件 → 基础事件（官方默认授予、免申请）
     * 每级只砍掉最可能未获授权的部分，尽量保住「群聊@/单聊」核心能力；
     * 全部被拒才停机，并给出平台侧的操作指引。
     *
     * 注意：从 op9 路径进来时连接可能仍然打开，这里主动关闭，
     * 并预先占用 reconnectScheduled 哨兵，防止关闭回调再走一遍重连调度。
     */
    private fun handleIntentRejection(
        myEpoch: Int,
        detail: String,
    ) {
        reconnectScheduled.set(true)
        heartbeatJob?.cancel()
        closeSocketQuietly()

        val guide =
            "事件订阅权限不足（$detail）。请到 QQ 开放平台 → 机器人管理 → 消息 Intent，" +
                "开启「群聊@消息」与「单聊消息」并等待审核通过，然后回到本应用设置页重新保存凭证。"
        val nextLevel = degradeLevel + 1
        val nextIntents =
            when (nextLevel) {
                // 第一档：只摘掉最可能未获授权的群成员事件位，保住其余订阅
                1 -> Intents.DEFAULT_WITHOUT_GROUP_MEMBER
                2 -> Intents.MESSAGES_ONLY
                3 -> Intents.BASIC
                else -> null
            }
        if (nextIntents != null && nextIntents != currentIntents) {
            degradeLevel = nextLevel
            currentIntents = nextIntents
            logger.w(TAG, "服务端拒绝当前事件订阅（$detail），降级到第 $nextLevel 级（intents=$nextIntents）重连")
            updateStatus {
                copy(
                    state = ConnectionState.RECONNECTING,
                    reconnectCount = 0,
                    lastError = "当前事件订阅未获授权，已自动降级重连。$guide",
                    readySinceMillis = null,
                )
            }
            reconnectJob?.cancel()
            reconnectJob =
                scope.launch {
                    delay(1_000L)
                    if (!running || myEpoch != epoch.get()) return@launch
                    launchConnect(resetBackoff = true)
                }
            return
        }
        logger.e(TAG, "事件订阅无权限且降级后仍被拒：$guide")
        updateStatus { copy(state = ConnectionState.FATAL, lastError = guide) }
        running = false
    }

    private fun send(payload: GatewayPayload): Boolean {
        val ws = webSocket
        if (ws == null) {
            logger.w(TAG, "WebSocket 未就绪，丢弃帧 op=${payload.op}")
            return false
        }
        return try {
            ws.send(json.encodeToString(payload))
        } catch (t: Throwable) {
            logger.e(TAG, "发送帧失败 op=${payload.op}", t)
            false
        }
    }

    private fun closeSocketQuietly() {
        try {
            webSocket?.cancel()
        } catch (t: Throwable) {
            logger.w(TAG, "关闭连接异常：${t.message}")
        }
        webSocket = null
    }

    /** 最近一次心跳 ACK 距今的毫秒数，用于 UI 展示"心跳延迟"。 */
    fun heartbeatAgeMillis(): Long =
        if (lastHeartbeatAckAt == 0L) -1L else clock.nowMillis() - lastHeartbeatAckAt

    /** 当前是否已就绪。 */
    fun isReady(): Boolean = _status.state == ConnectionState.READY
}
