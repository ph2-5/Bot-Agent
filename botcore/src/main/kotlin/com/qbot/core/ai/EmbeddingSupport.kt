package com.qbot.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * 向量检索（embedding）配置。
 *
 * 与主 AI / 辅助 AI 完全独立：用户可以只配一个便宜的服务商做语义检索，
 * 不影响对话模型。默认关闭——**没配就退回纯倒排，行为与 2.14.1 一致**。
 *
 * [dim] 不是猜出来的：首次保存时由「测试连接」实际调一次接口，用返回向量
 * 长度写入。为 0 表示还没探测过，此时 [ready] 为 false，整条向量链路不启用。
 */
data class EmbeddingConfig(
    val enabled: Boolean = false,
    /** siliconflow / zhipu / openai / custom */
    val provider: String = "siliconflow",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "BAAI/bge-m3",
    /** 由「测试连接」自动探测后写入；0 表示未探测 */
    val dim: Int = 0,
    val timeoutSeconds: Long = 8,
) {
    /** 是否具备调用条件（开关 + 地址 + Key + 模型 + 已探测维度） */
    val ready: Boolean
        get() =
            enabled &&
                baseUrl.isNotBlank() &&
                apiKey.isNotBlank() &&
                model.isNotBlank() &&
                dim > 0

    /** 只判断连接信息是否齐全；「测试连接」在未探测维度时也能用 */
    val callable: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    companion object {
        /** 预置服务商：填 Key 即可用；维度仍以探测结果为准 */
        data class Preset(
            val label: String,
            val baseUrl: String,
            val model: String,
            val expectedDim: Int,
        )

        val PRESETS: Map<String, Preset> =
            linkedMapOf(
                "siliconflow" to
                    Preset(
                        label = "硅基流动",
                        baseUrl = "https://api.siliconflow.cn/v1",
                        model = "BAAI/bge-m3",
                        expectedDim = 1024,
                    ),
                "zhipu" to
                    Preset(
                        label = "智谱",
                        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                        model = "embedding-3",
                        expectedDim = 2048,
                    ),
                "openai" to
                    Preset(
                        label = "OpenAI",
                        baseUrl = "https://api.openai.com/v1",
                        model = "text-embedding-3-small",
                        expectedDim = 1536,
                    ),
                "custom" to Preset(label = "自定义", baseUrl = "", model = "", expectedDim = 0),
            )

        val PROVIDERS: List<String> = PRESETS.keys.toList()
    }
}

/** embedding 调用失败。失败只影响本次检索，调用方必须静默退回倒排。 */
class EmbeddingException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * OpenAI 兼容的 Embeddings 客户端。
 *
 * 选这个协议同样是为了「一套接所有」：硅基流动、智谱、OpenAI、
 * 以及本地 Ollama / vLLM 都提供 `/embeddings`。
 */
class EmbeddingClient(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun normalizeEndpoint(baseUrl: String): String {
        var url = baseUrl.trim().trimEnd('/')
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // 本机 / 内网服务（Ollama、vLLM）默认没有证书，猜 https 会直接连不上
            url = if (isLocalHost(url)) "http://$url" else "https://$url"
        }
        return when {
            url.endsWith("/embeddings") -> url
            url.endsWith("/v1") || url.endsWith("/v3") || url.endsWith("/v4") -> "$url/embeddings"
            else -> "$url/v1/embeddings"
        }
    }

    private fun isLocalHost(url: String): Boolean {
        val host = url.substringBefore('/').substringBefore(':').lowercase()
        return host == "localhost" ||
            host == "0.0.0.0" ||
            host.startsWith("127.") ||
            host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host.endsWith(".local")
    }

    /** 批量取向量；返回顺序与输入一致。 */
    suspend fun embed(
        texts: List<String>,
        config: EmbeddingConfig,
    ): List<FloatArray> {
        require(texts.isNotEmpty()) { "没有要向量化的文本" }
        if (!config.callable) throw EmbeddingException("向量服务未配置完整")
        val endpoint = normalizeEndpoint(config.baseUrl)
        if (endpoint.isEmpty()) throw EmbeddingException("向量服务地址未填写")

        val payload =
            buildJsonObject {
                put("model", config.model.trim())
                put(
                    "input",
                    buildJsonArray { texts.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } },
                )
            }

        val client =
            if (config.timeoutSeconds > 0) {
                http.newBuilder()
                    .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
                    .build()
            } else {
                http
            }
        val request =
            Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

        val body =
            withContext(Dispatchers.IO) {
                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val text = response.body?.string().orEmpty()
                            throw httpError(response.code, text)
                        }
                        response.body?.string() ?: throw EmbeddingException("响应体为空")
                    }
                } catch (e: IOException) {
                    throw EmbeddingException("网络异常：${e.message}", cause = e)
                }
            }

        return parseVectors(body, texts.size)
    }

    /** 单条向量化（查询语句用）。 */
    suspend fun embedOne(
        text: String,
        config: EmbeddingConfig,
    ): FloatArray = embed(listOf(text), config).first()

    /** 测试连接：实际返回的向量长度就是维度，绝不猜。 */
    suspend fun probe(config: EmbeddingConfig): Int {
        val vector = embedOne(PROBE_TEXT, config)
        if (vector.isEmpty()) throw EmbeddingException("服务返回了空向量")
        return vector.size
    }

    private fun parseVectors(
        body: String,
        expected: Int,
    ): List<FloatArray> {
        val root =
            runCatching { json.parseToJsonElement(body) as? JsonObject }
                .getOrNull()
                ?: throw EmbeddingException("响应格式异常（不是 JSON 对象）")

        (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull?.let {
            if (it.isNotBlank()) throw EmbeddingException(it)
        }

        val data =
            root["data"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?: throw EmbeddingException("响应缺少 data 字段")

        val rows =
            data.mapIndexedNotNull { index, element ->
                val obj = element as? JsonObject ?: return@mapIndexedNotNull null
                val order =
                    runCatching { obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }
                        .getOrNull() ?: index
                val values =
                    obj["embedding"]?.let { runCatching { it.jsonArray }.getOrNull() }
                        ?: return@mapIndexedNotNull null
                val vector =
                    FloatArray(values.size) { i ->
                        runCatching { values[i].jsonPrimitive.contentOrNull?.toFloatOrNull() }
                            .getOrNull() ?: 0f
                    }
                if (vector.isEmpty()) null else order to vector
            }

        if (rows.size < expected) {
            throw EmbeddingException("返回向量数量不足（期望 $expected，实际 ${rows.size}）")
        }
        return rows.sortedBy { it.first }.take(expected).map { it.second }
    }

    private fun httpError(
        code: Int,
        body: String,
    ): EmbeddingException {
        val hint =
            when (code) {
                401, 403 -> "API Key 无效或无权限"
                404 -> "地址或模型名不存在，请检查 baseUrl 与 model"
                429 -> "请求过于频繁或额度不足"
                500, 502, 503 -> "服务商暂时不可用"
                else -> "HTTP $code"
            }
        val detail =
            runCatching {
                json.parseToJsonElement(body)
                    .let { it as? JsonObject }
                    ?.get("error")
                    ?.let { it as? JsonObject }
                    ?.get("message")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull()
        return EmbeddingException(
            if (detail.isNullOrBlank()) hint else "$hint（$detail）",
            statusCode = code,
        )
    }

    private companion object {
        /** 探测用短文本：只关心长度，不关心语义 */
        const val PROBE_TEXT = "连接测试"
    }
}

/**
 * 向量工具：归一化、点积、二进制序列化。
 *
 * 入库时就做 L2 归一化，检索阶段只算点积——归一化后点积等于余弦相似度，
 * 省掉每条候选一次 sqrt。
 */
object EmbeddingVectors {
    /** L2 归一化；零向量原样返回（调用方会因相似度为 0 自然跳过） */
    fun normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value
        val norm = sqrt(sum)
        if (norm <= 0.0 || !norm.isFinite()) return vector.copyOf()
        return FloatArray(vector.size) { i -> (vector[i] / norm).toFloat() }
    }

    /** 归一化向量的点积 = 余弦相似度 */
    fun dot(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        if (a.size != b.size) return 0f
        var sum = 0.0
        for (i in a.indices) sum += a[i].toDouble() * b[i]
        return sum.toFloat()
    }

    /** float32 小端 BLOB（与任务书约定一致） */
    fun toBlob(vector: FloatArray): ByteArray =
        ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { asFloatBuffer().put(vector) }
            .array()

    /** 解析 BLOB；长度不足时返回空数组，调用方应跳过 */
    fun fromBlob(
        blob: ByteArray,
        dim: Int,
    ): FloatArray {
        if (dim <= 0 || blob.size < dim * Float.SIZE_BYTES) return FloatArray(0)
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(dim)
        buffer.get(out)
        return out
    }
}

/**
 * Reciprocal Rank Fusion：把倒排与向量两个榜单合成一个候选集。
 *
 * score(d) = Σ 1/(60 + rank_i(d))，对榜单长度不敏感，
 * 不需要把「词权重」和「余弦相似度」校准到同一量纲。
 */
object Rrf {
    const val DEFAULT_K = 60

    fun fuse(
        rankings: List<List<String>>,
        limit: Int,
        k: Int = DEFAULT_K,
    ): List<String> {
        if (limit <= 0) return emptyList()
        val scores = LinkedHashMap<String, Double>()
        rankings.forEach { ranking ->
            ranking.forEachIndexed { index, key ->
                scores[key] = (scores[key] ?: 0.0) + 1.0 / (k + index + 1)
            }
        }
        // sortedByDescending 是稳定排序：同分时保持第一次出现的先后
        return scores.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }
}

/**
 * 向量检索的健康状态 + 查询缓存。
 *
 * - **熔断**：连续失败 [FAILURES_BEFORE_PAUSE] 次暂停 [pauseMillis]，
 *   期间第 4 次起不再发起任何 embedding 调用，避免每次聊天都白等 8 秒。
 * - **缓存**：同一句话 60 秒内不重复调（群聊里连续消息常重复）。
 *
 * 线程安全：索引队列在 IO、检索在 Default，两者共用同一份状态。
 */
class EmbeddingGuard(
    private val pauseMillis: Long = PAUSE_MILLIS,
    private val cacheMillis: Long = CACHE_MILLIS,
    private val maxCache: Int = MAX_CACHE,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Cached(
        val vector: FloatArray,
        val at: Long,
    )

    private var consecutiveFailures = 0
    private var pausedUntil = 0L

    private val cache =
        object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>): Boolean =
                size > maxCache
        }

    @Synchronized
    fun isPaused(): Boolean {
        if (pausedUntil <= 0L) return false
        if (clock() >= pausedUntil) {
            pausedUntil = 0L
            consecutiveFailures = 0
            return false
        }
        return true
    }

    /** 暂停剩余毫秒；未暂停返回 0 */
    @Synchronized
    fun pauseRemainingMillis(): Long =
        if (isPaused()) (pausedUntil - clock()).coerceAtLeast(0L) else 0L

    @Synchronized
    fun cachedQuery(key: String): FloatArray? {
        val entry = cache[key] ?: return null
        if (clock() - entry.at > cacheMillis) {
            cache.remove(key)
            return null
        }
        return entry.vector
    }

    @Synchronized
    fun cacheQuery(
        key: String,
        vector: FloatArray,
    ) {
        cache[key] = Cached(vector, clock())
    }

    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        pausedUntil = 0L
    }

    /** @return 本次失败是否触发了暂停 */
    @Synchronized
    fun recordFailure(): Boolean {
        consecutiveFailures++
        if (consecutiveFailures < FAILURES_BEFORE_PAUSE) return false
        consecutiveFailures = 0
        pausedUntil = clock() + pauseMillis
        return true
    }

    /** 设置页「停止」后立刻解除暂停（用户明确要求重试） */
    @Synchronized
    fun reset() {
        consecutiveFailures = 0
        pausedUntil = 0L
        cache.clear()
    }

    companion object {
        /** 连续失败多少次进入暂停 */
        const val FAILURES_BEFORE_PAUSE = 3

        /** 暂停时长 */
        const val PAUSE_MILLIS = 10 * 60_000L

        /** 查询缓存有效期 */
        const val CACHE_MILLIS = 60_000L

        private const val MAX_CACHE = 64
    }
}
