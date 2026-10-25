package com.qbot.app.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.qbot.core.ai.ImageTranscoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/**
 * 表情包的本地仓库：下载 → 转 JPEG → 落盘 → 读回。
 *
 * 为什么必须落盘：QQ 的图片地址是带签名的临时链接，几小时就失效。
 * 只存 URL 的图库，当天收的图第二天就全是死链；把字节留在自己手里，
 * 发送时走 `file_data` 直传，链接怎么变都无关。
 *
 * 目录只存 JPEG：png/gif/webp 统一转码，省空间，也让上传格式单一可控。
 */
class LocalImageStore(
    private val dir: File,
    private val http: OkHttpClient,
    private val maxFiles: Int = MAX_FILES,
    private val maxDirBytes: Long = MAX_DIR_BYTES,
) : ImageTranscoder {
    companion object {
        /** 文件数上限：一张几百 KB，200 张约几十 MB，够用又不至于胀满存储。 */
        const val MAX_FILES = 200

        /** 目录体积上限；与文件数谁先超就按「最久未用」淘汰。 */
        const val MAX_DIR_BYTES = 50L * 1024 * 1024

        private const val TIMEOUT_SECONDS = 10L
        private const val MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024
    }

    /** 下载原图字节；超过上限或非 2xx 返回 null。 */
    suspend fun download(url: String): ByteArray? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val client = http.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
                val request =
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) QBotMobile")
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    if (body.contentLength() > MAX_DOWNLOAD_BYTES) return@use null
                    val buffer = Buffer()
                    body.source().read(buffer, MAX_DOWNLOAD_BYTES + 1)
                    if (buffer.size > MAX_DOWNLOAD_BYTES) return@use null
                    buffer.readByteArray()
                }
            }.getOrNull()
        }
    }

    /**
     * 任意格式 → JPEG。
     *
     * 用 `inSampleSize` 先粗降采样再精确缩放，避免几千万像素的图直接
     * `createScaledBitmap` 时把内存打爆（群友发的截图经常是原图长图）。
     */
    suspend fun transcodeToJpeg(
        bytes: ByteArray,
        maxEdge: Int = ImageTranscoder.DEFAULT_MAX_EDGE,
        quality: Int = ImageTranscoder.DEFAULT_QUALITY,
    ): ByteArray? = toJpeg(bytes, maxEdge, quality)

    override suspend fun toJpeg(
        bytes: ByteArray,
        maxEdge: Int,
        quality: Int,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext null
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            // 降到「不超过 2 倍目标」即可，剩下的交给精确缩放，避免过度糊
            while (longest / (sample * 2) >= maxEdge) sample *= 2

            bitmap =
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: return@withContext null

            val source = bitmap
            val edge = maxOf(source.width, source.height)
            scaled =
                if (edge > maxEdge) {
                    val ratio = maxEdge.toFloat() / edge
                    Bitmap.createScaledBitmap(
                        source,
                        (source.width * ratio).toInt().coerceAtLeast(1),
                        (source.height * ratio).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    source
                }

            ByteArrayOutputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) return@withContext null
                out.toByteArray()
            }
        } catch (_: Throwable) {
            // 损坏的图 / OOM 都按失败处理：调用方跳过这张，不阻断主流程
            null
        } finally {
            if (scaled != null && scaled !== bitmap) scaled.recycle()
            bitmap?.recycle()
        }
    }

    /** 落盘，返回相对文件名；文件名取内容 SHA-1，同图天然去重。 */
    suspend fun save(jpeg: ByteArray): String? = withContext(Dispatchers.IO) {
        if (jpeg.isEmpty()) return@withContext null
        runCatching {
            dir.mkdirs()
            val name = "${sha1(jpeg)}.jpg"
            File(dir, name).writeBytes(jpeg)
            trim()
            name
        }.getOrNull()
    }

    /** 按相对文件名读回字节；文件不存在 / 名字非法都返回 null。 */
    suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        resolve(name)?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
    }

    /** 删除一张本地副本（图库淘汰 / 手动删图时用），文件不在也不报错。 */
    suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { resolve(name)?.let { runCatching { it.delete() } } }
    }

    /**
     * 淘汰：文件数或总体积超限时，按「最久未修改」删最老的。
     *
     * 用修改时间而不是入库时间：用过的图会被 [markUsed] 摸一下时间戳，
     * 常用的图就先留下来。
     */
    suspend fun trim() {
        withContext(Dispatchers.IO) {
            runCatching {
                val files =
                    dir.listFiles()
                        ?.filter { it.isFile }
                        ?.sortedBy { it.lastModified() }
                        ?: return@runCatching
                if (files.isEmpty()) return@runCatching
                var total = files.sumOf { it.length() }
                var count = files.size
                for (file in files) {
                    if (count <= maxFiles && total <= maxDirBytes) break
                    val size = file.length()
                    if (file.delete()) {
                        count -= 1
                        total -= size
                    }
                }
            }
        }
    }

    /** 被采用一次，摸一下时间戳，让淘汰时排到后面。 */
    suspend fun markUsed(name: String) {
        withContext(Dispatchers.IO) {
            resolve(name)?.let { runCatching { it.setLastModified(System.currentTimeMillis()) } }
        }
    }

    /** 相对文件名 → 文件；含路径分隔符的一律拒绝，防止越出目录。 */
    private fun resolve(name: String): File? {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains('\\') || clean.contains("..")) {
            return null
        }
        return File(dir, clean)
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
