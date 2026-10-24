package com.qbot.app.data.repo

import com.qbot.app.data.db.FactDao
import com.qbot.app.data.db.FactEntity
import com.qbot.app.data.db.MemoryTermDao
import com.qbot.app.data.db.MemoryTermEntity
import com.qbot.app.data.db.RoomMessageDao
import com.qbot.app.data.db.RoomMessageEntity
import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.AuxAiConfig
import com.qbot.core.ai.ChatMessage
import com.qbot.core.ai.ConversationKeys
import com.qbot.core.ai.LlmClient
import com.qbot.core.ai.MemoryCandidate
import com.qbot.core.ai.MemoryKind
import com.qbot.core.ai.MemoryRanker
import com.qbot.core.ai.MemoryReranker
import com.qbot.core.ai.MemorySearcher
import com.qbot.core.ai.MemoryTerms
import okhttp3.OkHttpClient

/**
 * 索引写入：把事实与群消息切成索引词写进 `memory_terms`。
 *
 * 全部写入都是「尽力而为」——索引坏了顶多退化成按时间取记忆，
 * 绝不能让一条消息因为索引写失败而处理不了。
 * 主键 (kind, refId, term) + REPLACE 让重复写入天然幂等，回填可以随时重跑。
 */
class RoomMemoryIndexer(
    private val dao: MemoryTermDao,
    /** 索引总开关：关掉后不再产生新的索引行 */
    private val enabled: () -> Boolean = { true },
    private val logger: (String) -> Unit = {},
) {
    suspend fun indexFact(fact: FactEntity) {
        if (!enabled()) return
        safe("fact#${fact.id}") {
            val terms = MemoryTerms.extract(fact.text)
            dao.deleteFor(MemoryKind.FACT.name, fact.id)
            if (terms.isEmpty()) return@safe
            dao.upsertAll(
                terms.map { term ->
                    MemoryTermEntity(
                        kind = MemoryKind.FACT.name,
                        refId = fact.id,
                        term = term,
                        scopeKey = ConversationKeys.factScope(fact.senderId),
                        senderId = fact.senderId,
                        personaId = null,
                        updatedAt = fact.updatedAt,
                        // 用户亲口说过的事比群聊流水更重要
                        importance = IMPORTANCE_FACT,
                    )
                },
            )
        }
    }

    suspend fun indexRoomMessage(
        scopeKey: String,
        row: RoomMessageEntity,
    ) {
        if (!enabled()) return
        safe("room#${row.id}") {
            val terms = MemoryTerms.extract(row.content)
            dao.deleteFor(MemoryKind.ROOM.name, row.id)
            if (terms.isEmpty()) return@safe
            dao.upsertAll(
                terms.map { term ->
                    MemoryTermEntity(
                        kind = MemoryKind.ROOM.name,
                        refId = row.id,
                        term = term,
                        scopeKey = scopeKey,
                        senderId = row.senderId,
                        personaId = row.personaId,
                        updatedAt = row.timestampMillis,
                        // 机器人自己说过的话参考价值略低，避免它反复引用自己
                        importance =
                            if (row.role == "assistant") IMPORTANCE_ASSISTANT else IMPORTANCE_ROOM,
                    )
                },
            )
        }
    }

    suspend fun forgetFact(factId: Long) {
        safe("forget-fact#$factId") { dao.deleteFor(MemoryKind.FACT.name, factId) }
    }

    suspend fun forgetRoom(messageId: Long) {
        safe("forget-room#$messageId") { dao.deleteFor(MemoryKind.ROOM.name, messageId) }
    }

    /** 群背景 trim 后清掉已不存在的消息索引 */
    suspend fun pruneRoom(scopeKey: String) {
        safe("prune-room:$scopeKey") { dao.pruneRoomOrphans(scopeKey) }
    }

    /** 事实 trim / 清空后收敛某人名下的陈旧索引 */
    suspend fun pruneFacts(senderId: String) {
        safe("prune-facts:$senderId") {
            dao.pruneFactOrphans(ConversationKeys.factScope(senderId), senderId)
        }
    }

    suspend fun clearScope(
        scopeKey: String,
        kind: MemoryKind,
    ) {
        safe("clear:$scopeKey") { dao.clearScope(scopeKey, kind.name) }
    }

    suspend fun indexFactsBatch(batch: List<FactEntity>) {
        batch.forEach { indexFact(it) }
    }

    suspend fun indexRoomBatch(
        scopeKey: String,
        batch: List<RoomMessageEntity>,
    ) {
        batch.forEach { indexRoomMessage(scopeKey, it) }
    }

    private suspend fun safe(
        label: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            // 索引失败不影响回复：管线本身有回退
            logger("记忆索引写入失败 [$label]: ${t.message}")
        }
    }

    companion object {
        /** 重要性以 100 为基准存整数，检索时除以 100 变成倍率 */
        const val IMPORTANCE_FACT = 100
        const val IMPORTANCE_ROOM = 80
        const val IMPORTANCE_ASSISTANT = 55
    }
}

/**
 * 索引检索：找出命中的索引词 → 回查原文 → 交给纯 JVM 的打分器排序。
 *
 * DF（含词条的记忆数）在本次命中集合内统计，够用且不需要维护全局计数：
 * 手机端的记忆规模只有几千条，词频分布不会因为样本小就完全失真。
 */
class RoomMemorySearcher(
    private val termDao: MemoryTermDao,
    private val factDao: FactDao,
    private val roomDao: RoomMessageDao,
) : MemorySearcher {
    override suspend fun recall(
        query: String,
        scopes: List<String>,
        kinds: Set<MemoryKind>,
        limit: Int,
    ): List<MemoryCandidate> {
        val terms = MemoryTerms.extract(query)
        val cleanScopes = scopes.filter { it.isNotBlank() }.distinct()
        if (terms.isEmpty() || cleanScopes.isEmpty()) return emptyList()

        val rows = termDao.match(terms, cleanScopes, MAX_MATCH_ROWS)
        if (rows.isEmpty()) return emptyList()

        val df = rows.groupBy { it.term }.mapValues { (_, list) -> list.map { it.refId }.distinct().size }
        val grouped = rows.groupBy { it.kind to it.refId }

        val factIds = grouped.keys.filter { it.first == MemoryKind.FACT.name }.map { it.second }
        val roomIds = grouped.keys.filter { it.first == MemoryKind.ROOM.name }.map { it.second }
        val facts = if (factIds.isEmpty()) emptyMap() else factDao.byIds(factIds).associateBy { it.id }
        val rooms = if (roomIds.isEmpty()) emptyMap() else roomDao.byIds(roomIds).associateBy { it.id }

        return grouped.mapNotNull { (key, hits) ->
            val kind = runCatching { MemoryKind.valueOf(key.first) }.getOrNull() ?: return@mapNotNull null
            if (kind !in kinds) return@mapNotNull null
            val rarity =
                hits.distinctBy { it.term }.sumOf { MemoryRanker.termWeight(df[it.term] ?: 1) }
            when (kind) {
                MemoryKind.FACT -> {
                    val fact = facts[key.second] ?: return@mapNotNull null
                    MemoryCandidate(
                        kind = MemoryKind.FACT,
                        refId = fact.id,
                        text = fact.text,
                        updatedAt = fact.updatedAt,
                        rarity = rarity,
                        matchedTerms = hits.size,
                        importance = hits.maxOf { it.importance } / 100.0,
                        senderId = fact.senderId,
                    )
                }

                MemoryKind.ROOM -> {
                    val message = rooms[key.second] ?: return@mapNotNull null
                    MemoryCandidate(
                        kind = MemoryKind.ROOM,
                        refId = message.id,
                        text = message.content,
                        updatedAt = message.timestampMillis,
                        rarity = rarity,
                        matchedTerms = hits.size,
                        importance = hits.maxOf { it.importance } / 100.0,
                        senderId = message.senderId,
                        senderName = message.senderName,
                        role = message.role,
                        externalId = message.externalId,
                        personaId = message.personaId,
                    )
                }
            }
        }.take(limit)
    }

    companion object {
        /** 单次命中行上限：常见二元组可能命中上千行，取到够用即可 */
        private const val MAX_MATCH_ROWS = 4_000
    }
}

/**
 * 辅助 API 精排：把候选原文交给第二个模型挑相关性。
 *
 * 只在用户显式打开「辅助 API」且候选足够多时被调用；
 * 没填独立地址就沿用主模型配置（省去二套配置的门槛），
 * 任何失败都返回 null，调用方保留本地排序结果。
 */
class LlmMemoryReranker(
    http: OkHttpClient,
    private val configProvider: () -> Pair<AiConfig, AuxAiConfig>,
) : MemoryReranker {
    private val client = LlmClient(http = http)

    override suspend fun rerank(
        query: String,
        candidates: List<MemoryCandidate>,
        limit: Int,
    ): List<MemoryCandidate>? {
        if (candidates.size < MIN_CANDIDATES) return null
        val (main, aux) = configProvider()
        if (!aux.enabled) return null

        val useAux = aux.standalone
        val baseUrl = if (useAux) aux.baseUrl else main.baseUrl
        val apiKey = if (useAux) aux.apiKey.ifBlank { main.apiKey } else main.apiKey
        val model = if (useAux) aux.model else main.model
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return null

        val numbered =
            candidates.mapIndexed { index, candidate ->
                "${index + 1}. ${candidate.text.replace("\n", " ").take(ENTRY_MAX_CHARS)}"
            }
        val messages =
            listOf(
                ChatMessage(
                    role = "system",
                    content =
                        "你是记忆检索的排序器。根据「用户当前这句话」判断每条候选记忆的相关程度，" +
                            "只输出编号，不要解释。宁缺毋滥：不相关的不要选。",
                ),
                ChatMessage(
                    role = "user",
                    content =
                        "当前消息：${query.replace("\n", " ").take(QUERY_MAX_CHARS)}\n" +
                            "候选记忆：\n${numbered.joinToString("\n")}\n" +
                            "按相关度从高到低输出最多 $limit 个编号，用逗号分隔。",
                ),
            )

        val reply =
            runCatching {
                client.chat(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    messages = messages,
                    temperature = 0.0,
                    maxTokens = aux.maxTokens,
                    timeoutSeconds = aux.timeoutSeconds,
                )
            }.getOrNull() ?: return null

        val picked =
            NUMBER.findAll(reply.content)
                .mapNotNull { it.value.toIntOrNull() }
                .filter { it in 1..candidates.size }
                .distinct()
                .take(limit)
                .map { candidates[it - 1] }

        // 模型一个都没选（或格式跑偏）时保留本地排序，避免整块记忆变空
        return if (picked.isEmpty()) null else picked
    }

    companion object {
        private const val MIN_CANDIDATES = 4
        private const val ENTRY_MAX_CHARS = 80
        private const val QUERY_MAX_CHARS = 200
        private val NUMBER = Regex("\\d+")
    }
}
