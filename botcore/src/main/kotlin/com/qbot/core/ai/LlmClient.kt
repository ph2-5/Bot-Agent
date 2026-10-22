package com.qbot.core.ai

import com.qbot.core.util.Logger
import com.qbot.core.util.NoopLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** 一条对话消息。role 取值 user / assistant / system。 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

/** LLM 调用失败。 */
open class LlmException(message: String, val statusCode: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * 模型返回了空正文。
 *
 * 常见于「思考模式 + 输出额度太小」：思维链把 maxTokens 吃完，content 为空。
 * 单独类型是为了让调用方能识别并降级重试，而不是把所有失败混成一句报错。
 */
class EmptyContentException(
    message: String,
    val reasoning: String? = null,
    val finishReason: String? = null,
) : LlmException(message) {
    /** 是否因为输出额度被截断（finish_reason = length） */
    val truncated: Boolean get() = finishReason == "length"
}

/**
 * 请求上下文超出模型窗口。
 *
 * 服务端返回 400 且报文含 context length / maximum context 等字样时抛出，
 * 调用方应裁剪历史后重试，而不是把原文抛给用户。
 */
class ContextTooLongException(message: String, cause: Throwable? = null) :
    LlmException(message, cause = cause)

/** 一次调用的 token 用量。DeepSeek 还会给出缓存命中/未命中拆分。 */
data class LlmUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
) {
    /** 缓存命中率（0.0 - 1.0），无数据时返回 null。 */
    val cacheHitRatio: Float?
        get() =
            if (promptTokens <= 0) {
                null
            } else {
                cacheHitTokens.toFloat() / promptTokens
            }
}

/** 一次成功的对话补全结果：回复文本 + 用量。 */
data class LlmReply(
    val content: String,
    val usage: LlmUsage = LlmUsage(),
    /** 思考过程（DeepSeek 等），仅在开启思考时有值；不作为正文使用 */
    val reasoning: String? = null,
    /** stop / length / content_filter …；length 表示被输出额度截断 */
    val finishReason: String? = null,
)

/**
 * OpenAI 兼容的 Chat Completions 客户端。
 *
 * 选这个协议是因为它是事实标准：DeepSeek、通义千问、Kimi、智谱、OpenAI、
 * 乃至本地 Ollama 都提供了兼容端点，一套代码全都能接。
 * 用户只需在设置里填 baseUrl + apiKey + model 三项。
 */
class LlmClient(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val logger: Logger = NoopLogger,
) {
    companion object {
        private const val TAG = "Llm"

        /** 常见服务的默认地址，填设置时给用户做参考 */
        val PRESETS =
            listOf(
                "https://api.deepseek.com" to "DeepSeek",
                "https://dashscope.aliyuncs.com/compatible-mode" to "通义千问",
                "https://api.moonshot.cn" to "Kimi",
                "https://open.bigmodel.cn/api/paas/v4" to "智谱 GLM",
                "https://api.openai.com" to "OpenAI",
            )
    }

    /**
     * 把用户填的 baseUrl 归一化成完整的 chat/completions 地址。
     *
     * 用户可能填 `https://api.deepseek.com`、`https://api.deepseek.com/v1`
     * 或完整路径，这里统一兜住，避免「差一个 /v1 就 404」这种挫败。
     */
    fun normalizeEndpoint(baseUrl: String): String {
        var url = baseUrl.trim().trimEnd('/')
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"

        return when {
            url.endsWith("/chat/completions") -> url
            url.endsWith("/v1") -> "$url/chat/completions"
            else -> "$url/v1/chat/completions"
        }
    }

    /**
     * 发起一次对话补全，返回助手回复与用量。
     *
     * @param systemPrompt 为空时不发送 system 消息
     * @param thinkingType 透传给服务端的思考开关："disabled" / "enabled" / null（不发送）。
     *        这是 DeepSeek 扩展字段，仅 DeepSeek 端点认识，其他服务商发送可能报 400，
     *        调用方负责按端点判断是否传值。
     */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double = 0.7,
        maxTokens: Int = 1000,
        timeoutSeconds: Long = 60L,
        thinkingType: String? = null,
    ): LlmReply {
        require(apiKey.isNotBlank()) { "API Key 未填写" }
        require(model.isNotBlank()) { "模型名称未填写" }

        val endpoint = normalizeEndpoint(baseUrl)
        if (endpoint.isEmpty()) throw LlmException("服务地址未填写")

        val payload =
            buildJsonObject {
                put("model", model)
                put(
                    "messages",
                    kotlinx.serialization.json.buildJsonArray {
                        messages.forEach { msg ->
                            add(
                                buildJsonObject {
                                    put("role", msg.role)
                                    put("content", msg.content)
                                },
                            )
                        }
                    },
                )
                // 思考模式下 temperature/presence_penalty 等采样参数不生效
                // （官方明确说明），干脆不发送，避免误导用户以为调了有用。
                if (thinkingType != "enabled") {
                    put("temperature", temperature)
                }
                put("max_tokens", maxTokens)
                put("stream", false)
                thinkingType?.let { type ->
                    put(
                        "thinking",
                        buildJsonObject { put("type", type) },
                    )
                }
            }

        val client =
            if (timeoutSeconds > 0) {
                // 按需放宽读超时：AI 生成首字可能要几十秒，
                // 用短超时的共享 client 会把慢模型误判为失败。
                http.newBuilder()
                    .readTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            } else {
                http
            }

        val request =
            Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

        // OkHttp 的 execute() 是阻塞调用，必须在 IO 线程上跑。
        // 不能依赖调用方：UI 的协程作用域（测试连接、复盘反馈）就在主线程，
        // 少了这层 withContext 会直接抛 NetworkOnMainThreadException。
        val body =
            withContext(Dispatchers.IO) {
                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val text = response.body?.string().orEmpty()
                            throw httpError(response.code, text)
                        }
                        response.body?.string() ?: throw LlmException("响应体为空")
                    }
                } catch (e: IOException) {
                    throw LlmException("网络异常：${e.message}", cause = e)
                }
            }

        return parseReply(body)
    }

    /** 读取 choices[0].finish_reason（缺失时为 null）。 */
    private fun JsonObject.finishReason(): String? =
        runCatching {
            val choices = this["choices"] as? kotlinx.serialization.json.JsonArray
            val first = choices?.firstOrNull() as? JsonObject
            first?.get("finish_reason")?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.contentOrNull
        }.getOrNull()

    /** 按状态码造异常；上下文超限单独分类，便于调用方自动裁剪重试。 */
    private fun httpError(
        code: Int,
        body: String,
    ): LlmException {
        val described = describeHttpError(code, body)
        val lower = body.lowercase()
        val contextTooLong =
            code == 400 &&
                (
                    lower.contains("context length") ||
                        lower.contains("context_length") ||
                        lower.contains("maximum context") ||
                        lower.contains("too many tokens") ||
                        lower.contains("prompt is too long")
                    )
        return if (contextTooLong) {
            ContextTooLongException("请求上下文超出模型窗口：$described")
        } else {
            LlmException(described, statusCode = code)
        }
    }

    private fun parseReply(body: String): LlmReply {
        val root =
            try {
                json.parseToJsonElement(body).let { it as? JsonObject }
                    ?: throw LlmException("响应格式异常（不是 JSON 对象）")
            } catch (e: LlmException) {
                throw e
            } catch (e: Exception) {
                throw LlmException("响应解析失败：${e.message}", cause = e)
            }

        // 正文与思考过程分开取：reasoning_content 绝不能当答案返回。
        // 之前把它当兜底，结果是"看似成功、内容其实是思维链"，下游解析全乱。
        val content = root.messageText("content")
        val reasoning = root.messageText("reasoning_content")
        val finishReason = root.finishReason()
        val snippet = body.take(120)

        if (content.isNullOrBlank()) {
            // 有些兼容实现在错误时也返回 200，靠 error 字段说明原因
            val err =
                (root["error"] as? JsonObject)?.get("message")
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.contentOrNull
            if (!err.isNullOrBlank()) throw LlmException(err)

            val reasonText =
                when {
                    reasoning.isNullOrBlank() -> "模型返回了空内容"
                    finishReason == "length" -> "模型只输出了思考过程，正文被输出额度截断"
                    else -> "模型只输出了思考过程，没有给出正文"
                }
            throw EmptyContentException(
                "$reasonText（finish=${finishReason ?: "未知"}）。原始返回：$snippet",
                reasoning = reasoning,
                finishReason = finishReason,
            )
        }

        return LlmReply(
            content = content.trim(),
            usage = parseUsage(root),
            reasoning = reasoning?.takeIf { it.isNotBlank() },
            finishReason = finishReason,
        )
    }

    /**
     * 解析 usage 字段。
     *
     * DeepSeek 额外返回 prompt_cache_hit_tokens / prompt_cache_miss_tokens，
     * 用于在维护端展示缓存命中率——前缀缓存命中不仅省钱，首 token 延迟也大幅降低。
     */
    private fun parseUsage(root: JsonObject): LlmUsage {
        val usage = root["usage"] as? JsonObject ?: return LlmUsage()
        fun int(field: String): Int =
            (usage[field] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull
                ?.toIntOrNull()
                ?: 0
        return LlmUsage(
            promptTokens = int("prompt_tokens"),
            completionTokens = int("completion_tokens"),
            cacheHitTokens = int("prompt_cache_hit_tokens"),
            cacheMissTokens = int("prompt_cache_miss_tokens"),
        )
    }

    /** 从 choices[0].message 里取出指定字段的文本，缺失或非字符串时返回 null。 */
    private fun JsonObject.messageText(field: String): String? =
        this["choices"]
            ?.let { it as? kotlinx.serialization.json.JsonArray }
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("message")
            ?.let { it as? JsonObject }
            ?.get(field)
            // contentOrNull 对 JsonNull 返回 null，不会把 "null" 当成文本
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.contentOrNull

    /** 把常见 HTTP 错误码翻译成用户能看懂的提示。 */
    private fun describeHttpError(
        code: Int,
        body: String,
    ): String {
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
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.contentOrNull
            }.getOrNull()

        return if (!detail.isNullOrBlank()) "$hint（$detail）" else hint
    }
}
