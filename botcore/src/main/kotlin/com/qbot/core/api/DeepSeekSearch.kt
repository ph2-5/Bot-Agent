package com.qbot.core.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** 一条网页搜索结果。 */
@Serializable
data class SearchResult(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val summary: String = "",
    @SerialName("site_name") val siteName: String? = null,
) {
    /** 拼给模型看的短摘要：优先 summary，没有就退回 snippet。 */
    fun brief(): String =
        (summary.ifBlank { snippet }).replace("\n", " ").trim().take(160)
}

/**
 * DeepSeek 网页搜索。
 *
 * 2026-10 上线的独立接口，按成功请求计费（约 5 元 / 1000 次），
 * 与对话共用同一个 API Key。**只有 DeepSeek 自家的端点有**——
 * 用户如果把 baseUrl 换成别家，这里返回空列表，调用方照常降级。
 *
 * 注意它搜的是网页，不是图片：返回的是标题/链接/摘要，
 * 所以它解决的是"这个词什么意思"，解决不了"给我找张图"。
 */
class DeepSeekSearch(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * @param count 返回条数，1~10。默认 5：够判断一个词，再多久是在浪费上下文。
     * @return 失败一律返回空列表——搜索是锦上添花，不能因为它挂了就不回复。
     */
    suspend fun search(
        query: String,
        apiKey: String,
        baseUrl: String,
        count: Int = 5,
    ): List<SearchResult> {
        if (query.isBlank() || apiKey.isBlank()) return emptyList()
        val endpoint = endpointOf(baseUrl) ?: return emptyList()
        val payload =
            buildJsonObject {
                put("query", query.take(128))
                put("count", count.coerceIn(1, 10))
            }.toString()
        val request =
            Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
        return withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val body = response.body?.string().orEmpty()
                    json.decodeFromString<SearchResponse>(body).results
                }
            }.getOrDefault(emptyList())
        }
    }

    /** 非 DeepSeek 端点没有搜索接口，直接视为不可用。 */
    internal fun endpointOf(baseUrl: String): String? {
        // 与 LlmClient.normalizeEndpoint 保持一致：用户可能填根地址、
        // 带 /v1 的地址，也可能直接粘完整的 /v1/chat/completions。
        // 只剥 /v1 会把最后一种拼成 .../chat/completions/v1/tools/search。
        val root =
            baseUrl.trim()
                .trimEnd('/')
                .removeSuffix("/chat/completions")
                .removeSuffix("/v1")
        if (root.isBlank() || !root.contains("deepseek", ignoreCase = true)) return null
        return "$root/v1/tools/search"
    }

    @Serializable
    private data class SearchResponse(
        val results: List<SearchResult> = emptyList(),
    )
}
