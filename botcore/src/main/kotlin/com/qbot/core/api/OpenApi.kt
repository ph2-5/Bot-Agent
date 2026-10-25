package com.qbot.core.api

import com.qbot.core.auth.AccessTokenManager
import com.qbot.core.util.Logger
import com.qbot.core.util.NoopLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/** OpenAPI 调用失败。 */
class ApiException(message: String, val code: Int? = null, val httpStatus: Int? = null) :
    Exception(message)

/** 网关接入点信息。 */
@Serializable
data class GatewayInfo(
    val url: String,
    val shards: Int = 1,
    @SerialName("session_start_limit") val sessionStartLimit: SessionStartLimit? = null,
)

@Serializable
data class SessionStartLimit(
    val total: Int = 0,
    val remaining: Int = 0,
    @SerialName("reset_after") val resetAfter: Long = 0,
    @SerialName("max_concurrency") val maxConcurrency: Int = 1,
)

/** 发送结果。 */
@Serializable
data class SendResult(
    val id: String? = null,
    val timestamp: String? = null,
    val code: Int? = null,
    val message: String? = null,
) {
    val isSuccess: Boolean get() = id != null
}

/**
 * 消息类型。
 *
 * 参考官方 openapi 定义，常用的是文本(0)与 Markdown(2)。
 */
object MsgType {
    const val TEXT = 0
    const val MEDIA = 7
    const val MARKDOWN = 2
    const val ARK = 3
    const val EMBED = 4
}

/**
 * QQ 机器人 OpenAPI 封装。
 *
 * 统一地址 `https://api.sgroup.qq.com`，请求头 `Authorization: QQBot {access_token}`。
 *
 * 所有方法内部都会自动带上有效凭证，并在遇到鉴权类错误时
 * [AccessTokenManager.invalidate] 后重试一次——这对 7x24 运行很关键：
 * 平台偶尔会提前吊销凭证，否则机器人会一直拿着废票重试到下次定时刷新。
 */
class OpenApi(
    private val auth: AccessTokenManager,
    private val http: OkHttpClient,
    /**
     * OpenAPI 根地址。
     *
     * 可注入是为了支持正式/沙箱环境切换，以及让测试用 MockWebServer 接管。
     */
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val logger: Logger = NoopLogger,
) {
    companion object {
        private const val TAG = "OpenApi"
        const val DEFAULT_BASE_URL = "https://api.sgroup.qq.com"

        /** 官方图片大小硬限制：5MB（原始字节）。 */
        private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    }

    private val jsonMediaType = "application/json".toMediaType()

    /** 获取 WebSocket 网关地址（含建议分片数）。 */
    suspend fun getGateway(): GatewayInfo =
        withContext(Dispatchers.IO) {
            val body = call("GET", "/gateway/bot", null, retryOnAuthError = true)
            json.decodeFromString<GatewayInfo>(body)
        }

    /**
     * 发送群消息。
     *
     * @param groupOpenId 群 openid（非群号，来自事件里的 group_openid）
     * @param content 文本内容
     * @param replyMessageId 触发本次回复的原消息 id。传入即为「被动回复」，
     *                       不占用主动消息配额，且客户端会展示为一条回复。
     * @param eventId 互动/事件 id，新版接口用其做被动回复关联。
     */
    suspend fun sendGroupMessage(
        groupOpenId: String,
        content: String,
        replyMessageId: String? = null,
        eventId: String? = null,
        msgType: Int = MsgType.TEXT,
        /** 富媒体消息的 file_info；非空时忽略 content，走 msg_type=7 */
        mediaFileInfo: String? = null,
    ): SendResult {
        require(groupOpenId.isNotBlank()) { "groupOpenId 不能为空" }
        val payload = buildSendBody(content, replyMessageId, eventId, msgType, mediaFileInfo)
        val body =
            withContext(Dispatchers.IO) {
                call("POST", "/v2/groups/$groupOpenId/messages", payload, retryOnAuthError = true)
            }
        return json.decodeFromString<SendResult>(body)
    }

    /**
     * 发送单聊（C2C）消息。
     *
     * @param userOpenId 对方 user_openid
     */
    suspend fun sendUserMessage(
        userOpenId: String,
        content: String,
        replyMessageId: String? = null,
        eventId: String? = null,
        msgType: Int = MsgType.TEXT,
        mediaFileInfo: String? = null,
    ): SendResult {
        require(userOpenId.isNotBlank()) { "userOpenId 不能为空" }
        val payload = buildSendBody(content, replyMessageId, eventId, msgType, mediaFileInfo)
        val body =
            withContext(Dispatchers.IO) {
                call("POST", "/v2/users/$userOpenId/messages", payload, retryOnAuthError = true)
            }
        return json.decodeFromString<SendResult>(body)
    }

    /** 撤回群消息。 */
    suspend fun recallGroupMessage(groupOpenId: String, messageId: String) {
        withContext(Dispatchers.IO) {
            call("DELETE", "/v2/groups/$groupOpenId/messages/$messageId", null, retryOnAuthError = true)
        }
    }

    private fun buildSendBody(
        content: String,
        replyMessageId: String?,
        eventId: String?,
        msgType: Int,
        mediaFileInfo: String? = null,
    ): RequestBody =
        buildJsonObject {
            if (mediaFileInfo.isNullOrBlank()) {
                put("content", content)
                put("msg_type", msgType)
            } else {
                // 富媒体：content 必须为空，由 media.file_info 承载
                put("msg_type", MsgType.MEDIA)
                put("media", buildJsonObject { put("file_info", mediaFileInfo) })
            }
            replyMessageId?.let { put("msg_id", it) }
            eventId?.let { put("event_id", it) }
        }.toString().toRequestBody(jsonMediaType)

    /**
     * 上传一张网络图片到群聊，返回 file_info（供 msg_type=7 使用）。
     *
     * 走 URL 上传：平台自己去下载转存，我们不用把图读进内存。
     * 失败返回 null——发不出图只是少个表情，不该影响文字回复。
     */
    suspend fun uploadGroupImage(
        groupOpenId: String,
        imageUrl: String,
    ): String? = uploadImage("/v2/groups/$groupOpenId/files", groupOpenId, imageUrl)

    /**
     * 上传一张网络图片到单聊，返回 file_info（供 msg_type=7 使用）。
     *
     * 与群聊同一套上传协议，只是路径不同：`/v2/users/{openid}/files`。
     */
    suspend fun uploadUserImage(
        userOpenId: String,
        imageUrl: String,
    ): String? = uploadImage("/v2/users/$userOpenId/files", userOpenId, imageUrl)

    /**
     * 直接上传图片字节到群聊（`file_data` 模式），返回 file_info。
     *
     * QQ CDN 的图片链接带签名、几小时失效，还有防盗链；由平台服务器去拉
     * 那个链接，存得越久失败率越高。把字节捏在自己手里才稳。
     */
    suspend fun uploadGroupImageBytes(
        groupOpenId: String,
        bytes: ByteArray,
    ): String? = uploadImageBytes("/v2/groups/$groupOpenId/files", groupOpenId, bytes)

    /** 直接上传图片字节到单聊（`file_data` 模式）。 */
    suspend fun uploadUserImageBytes(
        userOpenId: String,
        bytes: ByteArray,
    ): String? = uploadImageBytes("/v2/users/$userOpenId/files", userOpenId, bytes)

    private suspend fun uploadImageBytes(
        path: String,
        targetId: String,
        bytes: ByteArray,
    ): String? {
        if (targetId.isBlank() || bytes.isEmpty()) return null
        // 5MB 是**原始字节**的硬限制；base64 会再膨胀 33%，不能按编码后长度判断
        if (bytes.size > MAX_IMAGE_BYTES) return null
        val payload =
            buildJsonObject {
                put("file_type", 1) // 1 = 图片
                // 官方参数名就是 file_data，值是纯 base64，不带 data:image/... 前缀
                put("file_data", Base64.getEncoder().encodeToString(bytes))
                put("srv_send_msg", false)
            }.toString().toRequestBody(jsonMediaType)
        val body =
            withContext(Dispatchers.IO) {
                runCatching {
                    call("POST", path, payload, retryOnAuthError = true)
                }.getOrNull()
            } ?: return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["file_info"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun uploadImage(
        path: String,
        targetId: String,
        imageUrl: String,
    ): String? {
        if (targetId.isBlank() || imageUrl.isBlank()) return null
        val payload =
            buildJsonObject {
                put("file_type", 1) // 1 = 图片
                put("url", imageUrl)
                put("srv_send_msg", false)
            }.toString().toRequestBody(jsonMediaType)
        val body =
            withContext(Dispatchers.IO) {
                runCatching {
                    call("POST", path, payload, retryOnAuthError = true)
                }.getOrNull()
            } ?: return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["file_info"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 执行一次带鉴权的 HTTP 调用。
     *
     * @param retryOnAuthError 遇到鉴权错误时，作废凭证后是否重试一次。
     */
    private suspend fun call(
        method: String,
        path: String,
        body: RequestBody?,
        retryOnAuthError: Boolean,
    ): String {
        val url = if (path.startsWith("http")) path else "${baseUrl.trimEnd('/')}$path"

        suspend fun attempt(): Pair<Int, String> =
            withContext(Dispatchers.IO) {
                val request =
                    Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "QQBot ${auth.getToken()}")
                        .method(method, body)
                        .build()
                http.newCall(request).execute().use { response ->
                    response.code to (response.body?.string().orEmpty())
                }
            }

        val (status, responseBody) = attempt()

        if (status == 401 && retryOnAuthError) {
            logger.w(TAG, "$method $path 返回 401，作废凭证后重试")
            auth.invalidate()
            val (retryStatus, retryBody) = attempt()
            return ensureSuccess(retryStatus, retryBody, method, path)
        }

        return ensureSuccess(status, responseBody, method, path)
    }

    private fun ensureSuccess(status: Int, body: String, method: String, path: String): String {
        if (status !in 200..299) {
            val err = runCatching { json.decodeFromString<SendResult>(body) }.getOrNull()
            throw ApiException(
                message = "$method $path 失败：HTTP $status ${err?.message.orEmpty()}",
                code = err?.code,
                httpStatus = status,
            )
        }
        // 官方部分接口即使返回 200，也会在 body 里用 code 表示业务失败
        val maybeError = runCatching { json.decodeFromString<SendResult>(body) }.getOrNull()
        if (maybeError != null && maybeError.code != null && maybeError.code != 0 && maybeError.id == null) {
            throw ApiException(
                message = "$method $path 业务失败：${maybeError.message} (code=${maybeError.code})",
                code = maybeError.code,
                httpStatus = status,
            )
        }
        return body
    }
}
