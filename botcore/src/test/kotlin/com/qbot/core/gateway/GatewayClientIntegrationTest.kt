package com.qbot.core.gateway

import com.qbot.core.api.OpenApi
import com.qbot.core.auth.AccessTokenManager
import com.qbot.core.model.GatewayCloseCode
import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.Intents
import com.qbot.core.model.OpCode
import com.qbot.core.model.cleanText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 网关客户端集成测试。
 *
 * 用 MockWebServer 扮演 QQ 网关，真正跑通
 * `建连 → Hello → Identify → READY → 事件分发` 这条主链路，
 * 而不只是验证几个纯函数——状态机写错往往只在真实交互中才暴露。
 */
class GatewayClientIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var scope: CoroutineScope
    private lateinit var client: GatewayClient

    /** 服务端收到的所有客户端帧原始文本 */
    private val receivedFrames = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        receivedFrames.clear()
    }

    @After
    fun tearDown() {
        runCatching { client.stop() }
        runCatching { scope.cancel() }
        runCatching { server.shutdown() }
    }

    private fun buildClient(
        listener: GatewayListener? = null,
        monitorIntervalMillis: Long = 10_000L,
    ): GatewayClient {
        val auth =
            AccessTokenManager(
                appId = "appid",
                clientSecret = "secret",
                http = http,
                tokenUrl = server.url("/app/getAppAccessToken").toString(),
            )
        val api =
            OpenApi(
                auth = auth,
                http = http,
                baseUrl = server.url("/").toString().trimEnd('/'),
            )
        return GatewayClient(
            api = api,
            auth = auth,
            scope = scope,
            http = http,
            intents = Intents.DEFAULT,
            listener = listener,
            properties = mapOf("\$os" to "test"),
            monitorIntervalMillis = monitorIntervalMillis,
        )
    }

    private fun enqueueToken() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"TEST-TOKEN","expires_in":"7200"}"""),
        )
    }

    private fun enqueueGateway() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"url":"ws://${server.hostName}:${server.port}/websocket","shards":1}"""),
        )
    }

    /**
     * 排队一个可升级为 WebSocket 的响应。
     *
     * @param onClientFrame 收到客户端帧时的回调，参数即服务端侧 socket，
     *                      可直接用它在握手关键时刻回推 READY / 事件。
     */
    private fun enqueueWebSocketUpgrade(onClientFrame: (WebSocket, String) -> Unit) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send("""{"op":10,"d":{"heartbeat_interval":45000}}""")
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        synchronized(receivedFrames) { receivedFrames.add(text) }
                        onClientFrame(webSocket, text)
                    }
                },
            ),
        )
    }

    private fun waitForStatus(
        target: ConnectionState,
        timeoutMs: Long = 5_000L,
    ): GatewayStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (client.status.value.state == target) return client.status.value
            Thread.sleep(20)
        }
        return client.status.value
    }

    /** 等待任意条件满足（带超时），用于降级这类中间态的断言。 */
    private fun waitFor(
        timeoutMs: Long = 8_000L,
        predicate: (GatewayStatus) -> Boolean,
    ): GatewayStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = client.status.value
        while (System.currentTimeMillis() < deadline) {
            last = client.status.value
            if (predicate(last)) return last
            Thread.sleep(20)
        }
        return last
    }

    private fun Json.frameOp(text: String): Int =
        parseToJsonElement(text).jsonObject["op"]!!.jsonPrimitive.content.toInt()

    @Test
    fun `完整握手流程能够走到 READY`() {
        enqueueToken()
        enqueueGateway()
        enqueueWebSocketUpgrade { ws, frame ->
            if (Json.frameOp(frame) == OpCode.IDENTIFY) {
                ws.send(
                    """{"op":0,"s":1,"t":"READY","d":{"version":1,"session_id":"sid-1",""" +
                        """"user":{"id":"100200","username":"测试机器人","bot":true}}}""",
                )
            }
        }

        client = buildClient()
        client.start()

        val status = waitForStatus(ConnectionState.READY)
        assertEquals(
            "期望进入 READY，实际=${status.state} error=${status.lastError}",
            ConnectionState.READY,
            status.state,
        )
        assertEquals("sid-1", status.sessionId)
        assertTrue("应记录会话就绪时间", status.readySinceMillis != null)
    }

    @Test
    fun `Identify 帧携带正确的凭证与订阅位`() {
        enqueueToken()
        enqueueGateway()
        enqueueWebSocketUpgrade { ws, frame ->
            if (Json.frameOp(frame) == OpCode.IDENTIFY) {
                ws.send("""{"op":0,"s":1,"t":"READY","d":{"version":1,"session_id":"sid-1"}}""")
            }
        }

        client = buildClient()
        client.start()
        waitForStatus(ConnectionState.READY)

        val identify = synchronized(receivedFrames) { receivedFrames.first { Json.frameOp(it) == OpCode.IDENTIFY } }
        val d = Json.parseToJsonElement(identify).jsonObject["d"]!!.jsonObject

        assertEquals("QQBot TEST-TOKEN", d["token"]!!.jsonPrimitive.content)
        assertEquals(Intents.DEFAULT, d["intents"]!!.jsonPrimitive.content.toInt())
        assertEquals("test", d["properties"]!!.jsonObject["\$os"]!!.jsonPrimitive.content)
    }

    @Test
    fun `收到消息事件会回调监听器`() {
        val received = AtomicReference<IncomingMessage>()
        val latch = CountDownLatch(1)

        enqueueToken()
        enqueueGateway()
        enqueueWebSocketUpgrade { ws, frame ->
            if (Json.frameOp(frame) == OpCode.IDENTIFY) {
                ws.send("""{"op":0,"s":1,"t":"READY","d":{"version":1,"session_id":"sid-1"}}""")
                ws.send(
                    """{"op":0,"s":2,"t":"GROUP_AT_MESSAGE_CREATE","d":{"id":"m1",""" +
                        """"group_openid":"g1","op_member_openid":"u1","content":"<@!100200> 你好","timestamp":"1"}}""",
                )
            }
        }

        client =
            buildClient(
                listener =
                    object : SimpleGatewayListener() {
                        override fun onMessage(message: IncomingMessage) {
                            received.set(message)
                            latch.countDown()
                        }
                    },
            )
        client.start()
        waitForStatus(ConnectionState.READY)

        assertTrue("等待消息回调超时", latch.await(5, TimeUnit.SECONDS))
        assertEquals("你好", received.get().cleanText("100200"))
        assertEquals("g1", received.get().targetId)
    }

    @Test
    fun `服务端下发 op7 时复用缓存地址重连而不重新取网关`() {
        enqueueToken()
        enqueueGateway()

        var op7Sent = false
        enqueueWebSocketUpgrade { ws, frame ->
            if (Json.frameOp(frame) == OpCode.IDENTIFY) {
                ws.send("""{"op":0,"s":1,"t":"READY","d":{"version":1,"session_id":"sid-1"}}""")
                if (!op7Sent) {
                    op7Sent = true
                    // 稍后下发重连指令
                    Thread {
                        Thread.sleep(150)
                        runCatching { ws.send("""{"op":7,"d":null}""") }
                    }.start()
                }
            }
        }

        client = buildClient()
        client.start()
        waitForStatus(ConnectionState.READY)

        // op7 到达后状态应离开 READY（重连已启动）。
        // 此时客户端正拿着缓存的地址发起新连接，请求会挂在 MockWebServer 队列里
        // 等我们补上第二次升级响应——这正好证明它没有先去要网关地址。
        var leftReady = false
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            if (client.status.value.state != ConnectionState.READY) {
                leftReady = true
                break
            }
            Thread.sleep(20)
        }
        assertTrue("op7 后应离开 READY 并重连", leftReady)

        // 会话仍在，重连后应走 Resume 而不是重新 Identify
        enqueueWebSocketUpgrade { ws, frame ->
            if (Json.frameOp(frame) == OpCode.RESUME) {
                ws.send("""{"op":0,"s":1,"t":"RESUMED","d":null}""")
            }
        }

        val resumed = waitForStatus(ConnectionState.READY)
        assertEquals("sid-1", resumed.sessionId)

        // 全程只取过一次网关地址：token + gateway + 两次 ws 升级
        val paths = (1..server.requestCount).map { server.takeRequest().path.orEmpty() }
        assertEquals("只应请求一次 /gateway/bot", 1, paths.count { it == "/gateway/bot" })
        assertEquals("应有两次 WebSocket 升级", 2, paths.count { it == "/websocket" })
    }

    @Test
    fun `网关接口限频后进入冷静期并放慢重连`() {
        enqueueToken()
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"code":11265,"message":"接口调用超过频率限制"}"""),
        )

        client = buildClient()
        client.start()

        val status = waitForStatus(ConnectionState.RECONNECTING, timeoutMs = 5_000L)
        assertEquals(ConnectionState.RECONNECTING, status.state)
        assertTrue("应提示触发限频，实际=${status.lastError}", status.lastError!!.contains("频率限制"))

        // 冷静期内（分钟级退避）不应出现秒级重连风暴：
        // 只有最初的 token + gateway 两个请求
        Thread.sleep(2_500)
        assertEquals("冷静期内不应反复重试", 2, server.requestCount)
    }

    @Test
    fun `intents 无权限时按四级阶梯自动降级并重连成功`() {
        enqueueToken()
        enqueueGateway()
        // 前三次连接：Identify 后服务端以 4014（intent 无权限）关闭
        repeat(3) {
            enqueueWebSocketUpgrade { ws, frame ->
                if (Json.frameOp(frame) == OpCode.IDENTIFY) {
                    ws.close(GatewayCloseCode.UNAUTHORIZED_INTENT, "intent 无权限")
                }
            }
        }
        // 第四次连接：基础事件集合获准，正常 READY
        enqueueWebSocketUpgrade { ws, frame ->
            if (Json.frameOp(frame) == OpCode.IDENTIFY) {
                ws.send("""{"op":0,"s":1,"t":"READY","d":{"version":1,"session_id":"sid-3"}}""")
            }
        }

        client = buildClient()
        client.start()

        // 应自动降级并提示，而不是无限重连
        val degraded =
            waitFor { it.lastError?.contains("降级") == true || it.state == ConnectionState.FATAL }
        assertTrue(
            "应提示自动降级，实际 state=${degraded.state} error=${degraded.lastError}",
            degraded.lastError!!.contains("降级"),
        )

        val ready = waitFor(15_000L) { it.state == ConnectionState.READY }
        assertEquals("逐级降级后应能连上", ConnectionState.READY, ready.state)

        // 校验四次 Identify 逐级降级：
        // 完整 → 去掉群成员事件位 → 仅消息 → 基础
        val identifies =
            synchronized(receivedFrames) {
                receivedFrames.filter { Json.frameOp(it) == OpCode.IDENTIFY }
            }
        assertEquals("应有四次 Identify", 4, identifies.size)
        val ladder =
            identifies.map {
                Json.parseToJsonElement(it).jsonObject["d"]!!.jsonObject["intents"]!!
                    .jsonPrimitive.content.toInt()
            }
        assertEquals(
            "降级阶梯应为 完整 → 去群成员位 → 仅消息 → 基础",
            listOf(
                Intents.DEFAULT,
                Intents.DEFAULT_WITHOUT_GROUP_MEMBER,
                Intents.MESSAGES_ONLY,
                Intents.BASIC,
            ),
            ladder,
        )
    }

    @Test
    fun `降级后仍无权限则停机并给出平台指引`() {
        enqueueToken()
        enqueueGateway()
        // 四级阶梯全部被 4014 拒绝
        repeat(4) {
            enqueueWebSocketUpgrade { ws, frame ->
                if (Json.frameOp(frame) == OpCode.IDENTIFY) {
                    ws.close(GatewayCloseCode.UNAUTHORIZED_INTENT, "intent 无权限")
                }
            }
        }

        client = buildClient()
        client.start()

        val degraded = waitFor { it.lastError?.contains("降级") == true }
        assertTrue(degraded.lastError!!.contains("降级"))

        // 四级降级全部被拒 → 应进入 FATAL 并提示去平台申请
        val fatal = waitFor(15_000L) { it.state == ConnectionState.FATAL }
        assertEquals(ConnectionState.FATAL, fatal.state)
        assertTrue(
            "应提示去平台申请权限，实际=${fatal.lastError}",
            fatal.lastError!!.contains("消息 Intent"),
        )
    }

    @Test
    fun `Identify 被 op9 拒绝时自动降级而不是死循环`() {
        enqueueToken()
        enqueueGateway()
        // 第一次连接：Identify 后服务端只回 op9 Invalid Session，不关闭连接
        enqueueWebSocketUpgrade { ws, frame ->
            if (Json.frameOp(frame) == OpCode.IDENTIFY) {
                ws.send("""{"op":9,"d":false}""")
            }
        }

        client = buildClient()
        client.start()

        // op9 且本地无会话 = Identify 参数被拒，应自动降级而不是无脑重连
        val degraded =
            waitFor { it.lastError?.contains("降级") == true || it.state == ConnectionState.FATAL }
        assertTrue(
            "op9 拒绝应触发自动降级，实际 state=${degraded.state} error=${degraded.lastError}",
            degraded.lastError!!.contains("降级"),
        )

        // 第二次连接：带着降级后的 intents 重新 Identify 并成功
        enqueueWebSocketUpgrade { ws, frame ->
            if (Json.frameOp(frame) == OpCode.IDENTIFY) {
                ws.send("""{"op":0,"s":1,"t":"READY","d":{"version":1,"session_id":"sid-op9"}}""")
            }
        }

        val ready = waitFor(10_000L) { it.state == ConnectionState.READY }
        assertEquals("降级后应能连上", ConnectionState.READY, ready.state)

        val identifies =
            synchronized(receivedFrames) {
                receivedFrames.filter { Json.frameOp(it) == OpCode.IDENTIFY }
            }
        assertEquals("应有两次 Identify", 2, identifies.size)
        val secondIntents =
            Json.parseToJsonElement(identifies[1]).jsonObject["d"]!!.jsonObject["intents"]!!
                .jsonPrimitive.content.toInt()
        // 第一级降级：只摘掉群成员事件位，其余订阅保留
        assertEquals(Intents.DEFAULT_WITHOUT_GROUP_MEMBER, secondIntents)
    }

    @Test
    fun `stop 之后回到 IDLE 且不再重连`() {
        enqueueToken()
        enqueueGateway()
        enqueueWebSocketUpgrade { ws, frame ->
            if (Json.frameOp(frame) == OpCode.IDENTIFY) {
                ws.send("""{"op":0,"s":1,"t":"READY","d":{"version":1,"session_id":"sid-1"}}""")
            }
        }

        client = buildClient()
        client.start()
        waitForStatus(ConnectionState.READY)

        client.stop()
        assertEquals(ConnectionState.IDLE, client.status.value.state)

        val after = server.requestCount
        Thread.sleep(500)
        assertEquals("停止后不应再发起任何请求", after, server.requestCount)
    }

    @Test
    fun `心跳 ACK 长期缺失时判定僵死并主动重连`() {
        enqueueToken()
        enqueueGateway()
        // Hello 给一个很短的心跳周期；服务端只收不发 ACK，模拟半开连接：
        // Socket 看着是通的，心跳也发得出去，但永远等不到回音。
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send("""{"op":10,"d":{"heartbeat_interval":100}}""")
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (Json.frameOp(text) == OpCode.IDENTIFY) {
                            webSocket.send(
                                """{"op":0,"s":1,"t":"READY","d":{"version":1,"session_id":"sid-1"}}""",
                            )
                        }
                        // 其余帧（心跳）一律不回 ACK
                    }
                },
            ),
        )

        client = buildClient(monitorIntervalMillis = 100L)
        client.start()
        assertEquals(ConnectionState.READY, waitForStatus(ConnectionState.READY).state)

        // 阈值 100ms × 2.5 = 250ms，监控 100ms 一轮：
        // 若仍按"必须等过 ACK 才检测"的老逻辑，状态会永远停在 READY（僵尸连接）。
        val deadline = System.currentTimeMillis() + 3_000L
        var leftReady = false
        while (System.currentTimeMillis() < deadline) {
            if (client.status.value.state != ConnectionState.READY) {
                leftReady = true
                break
            }
            Thread.sleep(20)
        }
        assertTrue(
            "ACK 缺失应触发僵死重连，实际 state=${client.status.value.state} " +
                "error=${client.status.value.lastError}",
            leftReady,
        )
    }

    @Test
    fun `端口号与网关地址来源于接口返回`() {
        enqueueToken()
        enqueueGateway()
        enqueueWebSocketUpgrade { ws, frame ->
            if (Json.frameOp(frame) == OpCode.IDENTIFY) {
                ws.send("""{"op":0,"s":1,"t":"READY","d":{"version":1,"session_id":"sid-1"}}""")
            }
        }

        client = buildClient()
        client.start()
        waitForStatus(ConnectionState.READY)

        // 第 1 个请求取凭证，第 2 个取网关地址，第 3 个是 ws 升级
        server.takeRequest() // token
        val gatewayRequest = server.takeRequest()
        assertEquals("/gateway/bot", gatewayRequest.path)
    }

    @Test
    fun `会话建立后事件计数会累加`() {
        enqueueToken()
        enqueueGateway()
        enqueueWebSocketUpgrade { ws, frame ->
            if (Json.frameOp(frame) == OpCode.IDENTIFY) {
                ws.send("""{"op":0,"s":1,"t":"READY","d":{"version":1,"session_id":"s"}}""")
                ws.send("""{"op":0,"s":2,"t":"GUILD_CREATE","d":{"id":"x"}}""")
                ws.send("""{"op":0,"s":3,"t":"GUILD_CREATE","d":{"id":"y"}}""")
            }
        }

        client = buildClient()
        client.start()
        waitForStatus(ConnectionState.READY)

        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline && client.status.value.totalEvents < 3L) {
            Thread.sleep(20)
        }
        assertTrue(
            "事件数应累计到 3，实际=${client.status.value.totalEvents}",
            client.status.value.totalEvents >= 3L,
        )
        assertNotNull(client.status.value.lastEventSeq)
    }
}
