package com.qbot.core.ai

/**
 * 把任意图片字节转成 JPEG。
 *
 * botcore 是纯 JVM 模块，不依赖 Android 的 `Bitmap`，所以解码/缩放要靠宿主注入
 * （app 用 `android.graphics.BitmapFactory` 实现）。需要它的地方有两处：
 * - 收图落盘：QQ 图是 png/gif/webp 混着来的，统一成 JPEG 省空间、也方便直传；
 * - 看图：部分视觉接口对格式挑剔，先转 JPEG 成功率更高。
 */
fun interface ImageTranscoder {
    /**
     * @param maxEdge 最长边上限（等比缩放，不裁剪）
     * @param quality JPEG 质量 0-100
     * @return 失败返回 null（调用方跳过，不阻断主流程）
     */
    suspend fun toJpeg(bytes: ByteArray, maxEdge: Int, quality: Int): ByteArray?

    companion object {
        const val DEFAULT_MAX_EDGE = 1024
        const val DEFAULT_QUALITY = 85
    }
}
