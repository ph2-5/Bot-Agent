package com.qbot.core.api

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.qbot.core.auth.AccessTokenManager

/**
 * 图片字节直传（`file_data`）的请求形状。
 *
 * 这条链路的意义是不再让平台去拉 QQ CDN 的临时链接，
 * 所以重点验证：走的是 base64 字节、不是 URL 字段；超限本地就拦住、不发请求。
 */
class OpenApiImageTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var api: OpenApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.path == "/token" ->
                            MockResponse().setBody("""{"access_token":"TK","expires_in":7200}""")

                        request.path!!.startsWith("/v2/groups/") ->
                            MockResponse().setBody("""{"file_info":"FI-1"}""")

                        else -> MockResponse().setResponseCode(404)
                    }
            }
        server.start()
        http =
            OkHttpClient
                .Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        val auth =
            AccessTokenManager(
                appId = "1020xxxx",
                clientSecret = "secret",
                http = http,
                tokenUrl = server.url("/token").toString(),
            )
        api = OpenApi(auth = auth, http = http, baseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `字节上传用 file_data 而不是 url`() =
        runBlocking {
            val bytes = byteArrayOf(0x01, 0x02, 0x03)

            val fileInfo = api.uploadGroupImageBytes("g1", bytes)

            assertEquals("FI-1", fileInfo)
            server.takeRequest() // 取 token
            val request = server.takeRequest()
            assertEquals("/v2/groups/g1/files", request.path)
            val body = request.body.readUtf8()
            // 纯 base64，不带 data:image/... 前缀
            assertTrue(
                "file_data 应是纯 base64，实际=$body",
                body.contains("\"file_data\":\"${Base64.getEncoder().encodeToString(bytes)}\""),
            )
            assertTrue(body.contains("\"file_type\":1"))
            assertTrue(body.contains("\"srv_send_msg\":false"))
            assertFalse("不该再传 url 让平台去拉", body.contains("\"url\""))
        }

    @Test
    fun `超过 5MB 的图片本地就拦住，不发请求`() =
        runBlocking {
            val tooLarge = ByteArray(5 * 1024 * 1024 + 1)

            val fileInfo = api.uploadGroupImageBytes("g1", tooLarge)

            assertNull(fileInfo)
            // 超限在本地就拦住，连取 token 的请求都不该发
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `空字节直接返回 null`() =
        runBlocking {
            assertNull(api.uploadGroupImageBytes("g1", ByteArray(0)))
            assertNull(api.uploadUserImageBytes("u1", ByteArray(0)))
            assertEquals(0, server.requestCount)
        }
}
