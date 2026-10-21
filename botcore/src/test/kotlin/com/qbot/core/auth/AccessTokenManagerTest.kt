package com.qbot.core.auth

import com.qbot.core.util.FakeClock
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AccessTokenManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var clock: FakeClock

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().build()
        clock = FakeClock()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun manager(leadMillis: Long = 300_000L) =
        AccessTokenManager(
            appId = "1020xxxx",
            clientSecret = "secret-value",
            http = client,
            tokenUrl = server.url("/app/getAppAccessToken").toString(),
            clock = clock,
            refreshLeadMillis = leadMillis,
        )

    private fun enqueueToken(
        token: String = "TOKEN-1",
        expiresIn: String = "7200",
    ) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"$token","expires_in":$expiresIn}"""),
        )
    }

    @Test
    fun `首次获取会发起一次请求`() =
        runBlocking {
            enqueueToken()
            val m = manager()
            assertEquals("TOKEN-1", m.getToken())

            val request = server.takeRequest()
            assertEquals("/app/getAppAccessToken", request.path)
            assertEquals("POST", request.method)
            val body = request.body.readUtf8()
            assertTrue("请求体应包含 appId", body.contains("\"appId\""))
            assertTrue("请求体应包含 clientSecret", body.contains("\"clientSecret\""))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `有效期内复用缓存不重复请求`() =
        runBlocking {
            enqueueToken("TOKEN-1")
            val m = manager()
            m.getToken()
            clock.advance(3_600_000L) // 过了 1 小时，仍远未到刷新点
            assertEquals("TOKEN-1", m.getToken())
            assertEquals("只应请求一次", 1, server.requestCount)
        }

    @Test
    fun `临近过期时主动换新`() =
        runBlocking {
            enqueueToken("TOKEN-1")
            val m = manager(leadMillis = 300_000L)
            m.getToken()

            clock.advance(7_200_000L - 200_000L) // 距离过期仅剩 200s，已进入 300s 刷新窗口
            enqueueToken("TOKEN-2")

            assertEquals("TOKEN-2", m.getToken())
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `过期后必定换新`() =
        runBlocking {
            enqueueToken("TOKEN-1")
            val m = manager()
            m.getToken()
            clock.advance(7_300_000L) // 完全过期
            enqueueToken("TOKEN-2")
            assertEquals("TOKEN-2", m.getToken())
        }

    @Test
    fun `强制刷新忽略缓存`() =
        runBlocking {
            enqueueToken("TOKEN-1")
            val m = manager()
            m.getToken()
            enqueueToken("TOKEN-2")
            assertEquals("TOKEN-2", m.getToken(force = true))
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `凭证失效后下次取用会换新`() =
        runBlocking {
            enqueueToken("TOKEN-1")
            val m = manager()
            m.getToken()
            m.invalidate()
            enqueueToken("TOKEN-2")
            assertEquals("TOKEN-2", m.getToken())
        }

    @Test
    fun `业务错误码被包装成异常`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"code":100016,"message":"invalid appid or secret"}"""),
            )
            val m = manager()
            try {
                m.getToken()
                fail("应当抛出异常")
            } catch (e: BotAuthException) {
                assertEquals(100016, e.code)
                assertTrue(e.message!!.contains("invalid appid or secret"))
            }
        }

    @Test
    fun `HTTP 失败被包装成异常`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500))
            val m = manager()
            try {
                m.getToken()
                fail("应当抛出异常")
            } catch (e: BotAuthException) {
                assertTrue(e.message!!.contains("500"))
            }
            assertNotNull(m.lastError)
        }

    @Test
    fun `过期时间以数字返回时也能解析`() =
        runBlocking {
            // 部分接口文档把 expires_in 写成数字而非字符串，需要兼容
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"access_token":"TOKEN-N","expires_in":7200}"""),
            )
            val m = manager()
            assertEquals("TOKEN-N", m.getToken())
            assertTrue("剩余时间应接近 2 小时", m.remainingMillis() > 7_000_000L)
        }

    @Test
    fun `剩余时间随时间推进而减少`() =
        runBlocking {
            enqueueToken()
            val m = manager()
            m.getToken()
            val before = m.remainingMillis()
            clock.advance(60_000L)
            assertEquals(before - 60_000L, m.remainingMillis())
        }
}
