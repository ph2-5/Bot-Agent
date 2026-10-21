package com.qbot.core.auth

import com.qbot.core.util.Clock
import com.qbot.core.util.Logger
import com.qbot.core.util.NoopLogger
import com.qbot.core.util.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 凭证获取失败。
 *
 * @param code 平台业务错误码（如 100016 = appId 或 clientSecret 不正确），非 HTTP 状态码。
 */
class BotAuthException(message: String, val code: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

@Serializable
private data class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    /**
     * 官方示例里该字段有的接口返回字符串 `"7200"`、有的返回数字 `7200`，
     * 用 [JsonPrimitive] 承接再自行解析，避免两侧文档不一致导致反序列化崩溃。
     */
    @SerialName("expires_in") val expiresIn: JsonPrimitive? = null,
    val code: Int? = null,
    val message: String? = null,
)

private data class CachedToken(val value: String, val expiresAtMillis: Long)

/**
 * AccessToken 管理。
 *
 * 官方语义：
 * - 生命周期默认 7200 秒，过期后必须自行刷新；
 * - 有效期内重复获取返回同一个 token，不会续期；
 * - 距离过期不足 60 秒时再次获取会返回新 token，且旧 token 在此 60 秒内仍有效。
 *
 * 因此这里的策略是：**提前 [refreshLeadMillis] 主动换新**，而不是等到真正过期才补，
 * 避免机器人刚好在刷新窗口内收到消息而调用失败。默认提前 5 分钟。
 *
 * 所有获取走 [Mutex] 串行化，保证高并发下只会发出一次网络请求。
 */
class AccessTokenManager(
    private val appId: String,
    private val clientSecret: String,
    private val http: OkHttpClient,
    /**
     * 凭证地址。
     *
     * 抽成可注入参数有两个用途：一是正式/沙箱环境切换，
     * 二是让单元测试能用 MockWebServer 替换掉真实域名。
     */
    private val tokenUrl: String = DEFAULT_TOKEN_URL,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: Clock = SystemClock,
    private val logger: Logger = NoopLogger,
    private val refreshLeadMillis: Long = 300_000L,
) {
    companion object {
        private const val TAG = "Auth"
        const val DEFAULT_TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken"
        const val DEFAULT_EXPIRES_MILLIS = 7_200_000L
    }

    private val mutex = Mutex()

    @Volatile
    private var cached: CachedToken? = null

    /** 供 UI 观察最近一次刷新结果。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 取一个当前可用的 access_token。
     *
     * @param force 为 true 时忽略缓存强制换新（凭证疑似失效时用）。
     */
    suspend fun getToken(force: Boolean = false): String =
        mutex.withLock {
            val now = clock.nowMillis()
            cached?.let { token ->
                if (!force && now < token.expiresAtMillis - refreshLeadMillis) {
                    logger.v(TAG, "复用缓存凭证，剩余 ${(token.expiresAtMillis - now) / 1000}s")
                    return@withLock token.value
                }
            }

            logger.i(TAG, if (force) "强制刷新凭证" else "凭证已过期或即将过期，刷新中")
            refresh().also { lastError = null }.value
        }

    /**
     * 使缓存失效。
     *
     * 当某个 OpenAPI 返回鉴权失败错误码时调用，下次获取会直接换新，
     * 避免拿着一张被平台提前吊销的凭证反复重试。
     */
    suspend fun invalidate() =
        mutex.withLock {
            logger.w(TAG, "凭证已手动失效")
            cached = null
        }

    /** 当前缓存凭证的剩余有效毫秒数；无缓存时返回 0。 */
    fun remainingMillis(): Long {
        val token = cached ?: return 0L
        return (token.expiresAtMillis - clock.nowMillis()).coerceAtLeast(0L)
    }

    private fun refresh(): CachedToken {
        val body =
            buildString {
                append("{\"appId\":")
                append(jsonPrimitiveString(appId))
                append(",\"clientSecret\":")
                append(jsonPrimitiveString(clientSecret))
                append("}")
            }

        val request =
            Request.Builder()
                .url(tokenUrl)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

        val responseBody =
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val msg = "获取凭证失败：HTTP ${response.code}"
                        lastError = msg
                        throw BotAuthException(msg)
                    }
                    response.body?.string() ?: throw BotAuthException("获取凭证失败：响应体为空")
                }
            } catch (e: IOException) {
                lastError = "网络异常：${e.message}"
                throw BotAuthException("获取凭证网络异常", cause = e)
            }

        val parsed =
            try {
                json.decodeFromString<AccessTokenResponse>(responseBody)
            } catch (e: Exception) {
                lastError = "凭证响应解析失败"
                throw BotAuthException("凭证响应解析失败", cause = e)
            }

        parsed.accessToken?.takeIf { it.isNotBlank() }?.let { value ->
            val ttlMillis =
                parsed.expiresIn?.contentOrNull?.toLongOrNull()?.times(1000L)
                    ?: DEFAULT_EXPIRES_MILLIS
            val token = CachedToken(value, clock.nowMillis() + ttlMillis)
            cached = token
            logger.i(TAG, "凭证刷新成功，有效期 ${ttlMillis / 1000}s")
            return token
        }

        val msg = "获取凭证失败：${parsed.message ?: "未知错误"}${parsed.code?.let { " (code=$it)" } ?: ""}"
        lastError = msg
        throw BotAuthException(msg, parsed.code)
    }

    /** 手工拼 JSON 字符串，避免为两个字段引入序列化依赖，同时保证转义安全。 */
    private fun jsonPrimitiveString(value: String): String {
        val sb = StringBuilder("\"")
        value.forEach { ch ->
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.append("\"").toString()
    }
}
