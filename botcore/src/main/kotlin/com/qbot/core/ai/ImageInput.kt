package com.qbot.core.ai

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/**
 * 把消息里的图片地址转成模型可直接读取的形式。
 *
 * QQ 的图片链接是带签名的临时地址，可能过期、防盗链，或者服务商网络到不了 QQ CDN。
 * 本地先下载再转成 `data:` URL 交给模型，能避开这些不确定性。
 */
fun interface ImageInliner {
    /** 成功返回 data URL 或原地址；失败返回 null，由调用方决定是否回退。 */
    suspend fun inline(url: String): String?
}

/**
 * 下载远程图片并内联为 base64 data URL。
 *
 * 只接受模型真正能吃的四种格式（JPEG / PNG / GIF / WebP），并按文件头判断，
 * 不信服务端声明的 Content-Type。超过 [maxBytes] 的图直接放弃：
 * 一张群聊图通常几百 KB，几 MB 的图既费内存也费 token，收益很低。
 */
class RemoteImageLoader(
    private val http: OkHttpClient,
    private val maxBytes: Long = 8L * 1024 * 1024,
    private val timeoutSeconds: Long = 10L,
) : ImageInliner {
    override suspend fun inline(url: String): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val client =
                    http.newBuilder()
                        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                        .build()
                val request =
                    Request.Builder()
                        .url(url)
                        // 部分图床会拒绝没有 UA 的请求
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) QBotMobile")
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    if (body.contentLength() > maxBytes) return@use null

                    val buffer = Buffer()
                    body.source().read(buffer, maxBytes + 1)
                    if (buffer.size > maxBytes) return@use null

                    val bytes = buffer.readByteArray()
                    val mime = sniffMime(bytes) ?: return@use null
                    "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
                }
            }.getOrNull()
        }
    }

    /** 按魔数判断格式；模型不支持的格式一律返回 null。 */
    private fun sniffMime(bytes: ByteArray): String? =
        when {
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> "image/jpeg"

            bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() &&
                bytes[3] == 'G'.code.toByte() &&
                bytes[4] == 0x0D.toByte() &&
                bytes[5] == 0x0A.toByte() &&
                bytes[6] == 0x1A.toByte() &&
                bytes[7] == 0x0A.toByte() -> "image/png"

            bytes.size >= 6 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF" -> "image/gif"

            bytes.size >= 12 &&
                String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"

            else -> null
        }
}
