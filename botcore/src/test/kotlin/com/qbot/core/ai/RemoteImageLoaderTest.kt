package com.qbot.core.ai

import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 图片内联：只接受模型能吃的格式，并且不能因为一张坏图把请求撑爆。 */
class RemoteImageLoaderTest {
    private lateinit var server: MockWebServer

    private val pngBytes =
        Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nXQAAAAASUVORK5CYII=",
        )
    private val loader = RemoteImageLoader(OkHttpClient())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `PNG 会被转成 data URL`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setBody(Buffer().write(pngBytes))
                    .setHeader("Content-Type", "image/png"),
            )

            val result = loader.inline(server.url("/a.png").toString())

            assertTrue("应转成 PNG data URL，实际=$result", result.orEmpty().startsWith("data:image/png;base64,"))
        }

    @Test
    fun `超过大小上限的图片直接放弃`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setBody(Buffer().write(ByteArray(20) { 1 }))
                    .setHeader("Content-Type", "image/png"),
            )

            val small = RemoteImageLoader(OkHttpClient(), maxBytes = 10)
            assertNull(small.inline(server.url("/big.png").toString()))
        }

    @Test
    fun `不是支持的图片格式时放弃`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("hello").setHeader("Content-Type", "image/png"))

            // 声明的 Content-Type 不可信，必须按文件头判断
            assertNull(loader.inline(server.url("/fake.png").toString()))
        }

    @Test
    fun `下载失败返回 null 交给调用方回退`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(loader.inline(server.url("/missing.png").toString()))
        }

    @Test
    fun `非 http 地址不处理`() =
        runBlocking {
            assertNull(loader.inline("ftp://example.com/a.png"))
            assertNull(loader.inline(""))
        }

    @Test
    fun `多种格式按魔数识别`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00))))
            val jpeg = loader.inline(server.url("/a.jpg").toString())
            assertTrue(jpeg.orEmpty().startsWith("data:image/jpeg;base64,"))

            server.enqueue(MockResponse().setBody("GIF89a"))
            val gif = loader.inline(server.url("/a.gif").toString())
            assertEquals("data:image/gif;base64,${Base64.getEncoder().encodeToString("GIF89a".toByteArray())}", gif)
        }
}
