package com.qbot.app.data.repo

import com.qbot.app.data.db.FactEntity
import com.qbot.app.data.db.LoreEntity
import com.qbot.app.data.db.MemoryVectorDao
import com.qbot.app.data.db.MemoryVectorEntity
import com.qbot.app.data.db.RoomMessageDao
import com.qbot.app.data.db.RoomMessageEntity
import com.qbot.app.data.db.SlangEntity
import com.qbot.core.ai.ConversationKeys
import com.qbot.core.ai.EmbeddingClient
import com.qbot.core.ai.EmbeddingConfig
import com.qbot.core.ai.EmbeddingGuard
import com.qbot.core.ai.EmbeddingVectors
import com.qbot.core.ai.MemoryCandidate
import com.qbot.core.ai.MemoryKind
import com.qbot.core.ai.MemorySearcher
import com.qbot.core.ai.Rrf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 向量化任务（只进内存队列，不落库）。 */
private sealed class VectorTask {
    data class Fact(val row: FactEntity) : VectorTask()

    data class Room(
        val scopeKey: String,
        val rowId: Long,
    ) : VectorTask()

    data class Lore(val row: LoreEntity) : VectorTask()

    data class Slang(val row: SlangEntity) : VectorTask()
}

/**
 * 向量写入：新增记忆时投递一条任务，后台串行调 embedding 并落 `memory_vectors`。
 *
 * 三条铁律：
 * - **异步**：调用方（消息处理）只 `trySend`，绝不等待网络；
 * - **失败只丢任务**：不回滚记忆本身、不重试，连续失败由 [EmbeddingGuard] 熔断；
 * - **短消息不向量化**：「哈哈哈」的向量没有信息量，白烧 token。
 */
class MemoryVectorIndexer(
    private val dao: MemoryVectorDao,
    private val roomDao: RoomMessageDao,
    private val client: EmbeddingClient,
    private val guard: EmbeddingGuard,
    private val configProvider: () -> EmbeddingConfig,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val queue = Channel<VectorTask>(Channel.UNLIMITED)

    @Suppress("unused")
    private val worker =
        scope.launch {
            for (task in queue) {
                runCatching { process(task) }.onFailure {
                    logger("向量入库失败：${it.message ?: it::class.simpleName}")
                }
            }
        }

    fun enqueueFact(row: FactEntity) = offer(VectorTask.Fact(row))

    fun enqueueRoom(
        scopeKey: String,
        row: RoomMessageEntity,
    ) = offer(VectorTask.Room(scopeKey, row.id))

    fun enqueueLore(row: LoreEntity) = offer(VectorTask.Lore(row))

    fun enqueueSlang(row: SlangEntity) = offer(VectorTask.Slang(row))

    /** 源记录被删除：向量一并删掉（不区分模型） */
    fun forget(
        kind: MemoryKind,
        refId: Long,
    ) {
        scope.launch { runCatching { dao.deleteFor(kind.name, refId) } }
    }

    /** trim 后收敛已被删除源记录的向量 */
    fun pruneOrphans() {
        scope.launch {
            runCatching {
                dao.pruneRoomOrphans()
                dao.pruneFactOrphans()
                dao.pruneLoreOrphans()
                dao.pruneSlangOrphans()
            }
        }
    }

    /** 机器人停止时收敛写队列 */
    fun close() {
        queue.close()
    }

    private fun offer(task: VectorTask) {
        val config = configProvider()
        if (!config.ready || guard.isPaused()) return
        queue.trySend(task)
    }

    private suspend fun process(task: VectorTask) {
        val config = configProvider()
        if (!config.ready || guard.isPaused()) return
        val payload = payload(task) ?: return
        if (payload.text.trim().length < MIN_TEXT_CHARS) return

        val vector =
            try {
                EmbeddingVectors.normalize(client.embedOne(payload.text, config))
            } catch (t: Throwable) {
                val paused = guard.recordFailure()
                logger(
                    "向量化失败${if (paused) "（连续 3 次，暂停 10 分钟）" else ""}：" +
                        "${t.message ?: t::class.simpleName}",
                )
                return
            }
        if (vector.isEmpty()) return
        guard.recordSuccess()

        dao.upsert(
            MemoryVectorEntity(
                kind = payload.kind.name,
                refId = payload.refId,
                model = config.model.trim(),
                dim = vector.size,
                scopeKey = payload.scopeKey,
                vec = EmbeddingVectors.toBlob(vector),
                createdAt = clock(),
            ),
        )
    }

    private data class Payload(
        val kind: MemoryKind,
        val refId: Long,
        val scopeKey: String,
        val text: String,
    )

    private suspend fun payload(task: VectorTask): Payload? =
        when (task) {
            is VectorTask.Fact ->
                Payload(
                    kind = MemoryKind.FACT,
                    refId = task.row.id,
                    scopeKey = ConversationKeys.factScope(task.row.senderId),
                    text = task.row.text,
                )

            is VectorTask.Room -> {
                // 滑动窗口 3 条 + 说话人：单条「哈哈哈」没有信息量，
                // 加上前后文才能表达「谁在什么语境下说了什么」。
                val window = roomDao.windowBefore(task.scopeKey, task.rowId, ROOM_WINDOW).asReversed()
                if (window.isEmpty()) {
                    null
                } else {
                    Payload(
                        kind = MemoryKind.ROOM,
                        refId = task.rowId,
                        scopeKey = task.scopeKey,
                        text = window.joinToString("\n") { "${speaker(it)}：${it.content}" },
                    )
                }
            }

            is VectorTask.Lore ->
                Payload(
                    kind = MemoryKind.LORE,
                    refId = task.row.id,
                    scopeKey = loreScope(task.row.personaId, task.row.scopeGroupId),
                    text = "${task.row.name}：${task.row.note}",
                )

            is VectorTask.Slang ->
                Payload(
                    kind = MemoryKind.SLANG,
                    refId = task.row.id,
                    scopeKey = loreScope(null, task.row.scopeGroupId),
                    text = buildString {
                        append(task.row.term).append("：").append(task.row.meaning)
                        val usage = task.row.usage?.trim().orEmpty()
                        if (usage.isNotEmpty()) append("。用法：").append(usage)
                    },
                )
        }

    private fun speaker(row: RoomMessageEntity): String =
        if (row.role == "assistant") {
            "你"
        } else {
            row.senderName?.takeIf { it.isNotBlank() } ?: "群友"
        }

    private fun loreScope(
        personaId: Long?,
        scopeGroupId: String?,
    ): String = scopeGroupId?.takeIf { it.isNotBlank() }?.let { ConversationKeys.groupRoom(it) }
        ?: "p:${personaId ?: 0}"

    companion object {
        /** ROOM 向量化的滑动窗口条数 */
        const val ROOM_WINDOW = 3

        /** 少于这个长度不向量化（「哈哈哈」「在吗」） */
        const val MIN_TEXT_CHARS = 6
    }
}

/**
 * 混合检索：倒排 + 向量 → RRF 融合 → 交给现有 [com.qbot.core.ai.MemoryRanker]。
 *
 * 降级顺序（严格）：
 * 1. 未配置 / 开关关闭 / 熔断中 → **原样返回倒排结果**（与 2.14.1 逐字节一致）；
 * 2. 调用正常 → RRF 融合候选；
 * 3. 调用失败 → 记日志退回倒排，连续 3 次由 [EmbeddingGuard] 暂停 10 分钟。
 */
class HybridMemorySearcher(
    private val inverted: MemorySearcher,
    private val vectorDao: MemoryVectorDao,
    private val factDao: com.qbot.app.data.db.FactDao,
    private val roomDao: RoomMessageDao,
    private val client: EmbeddingClient,
    private val guard: EmbeddingGuard,
    private val configProvider: () -> EmbeddingConfig,
    private val logger: (String) -> Unit = {},
) : MemorySearcher {
    override suspend fun recall(
        query: String,
        scopes: List<String>,
        kinds: Set<MemoryKind>,
        limit: Int,
    ): List<MemoryCandidate> {
        val base = inverted.recall(query, scopes, kinds, limit)

        val config = configProvider()
        if (!config.ready || guard.isPaused()) return base

        val cleanScopes = scopes.filter { it.isNotBlank() }.distinct()
        if (cleanScopes.isEmpty() || limit <= 0) return base

        val cacheKey = "${config.model.trim()}|$query"
        val queryVector =
            guard.cachedQuery(cacheKey)
                ?: try {
                    EmbeddingVectors.normalize(client.embedOne(query, config)).also {
                        guard.recordSuccess()
                        guard.cacheQuery(cacheKey, it)
                    }
                } catch (t: Throwable) {
                    val paused = guard.recordFailure()
                    logger(
                        "向量检索失败，本次退回倒排" +
                            if (paused) "（连续 3 次，暂停 10 分钟）" else "" +
                            "：${t.message ?: t::class.simpleName}",
                    )
                    return base
                }
        if (queryVector.isEmpty()) return base

        val rows =
            runCatching {
                vectorDao.candidates(
                    scopes = cleanScopes,
                    kinds = kinds.map { it.name },
                    model = config.model.trim(),
                )
            }.getOrElse {
                logger("向量候选查询失败，本次退回倒排：${it.message}")
                return base
            }
        if (rows.isEmpty()) return base

        val scored =
            rows.asSequence()
                .mapNotNull { row ->
                    if (row.dim <= 0 || row.dim != queryVector.size) return@mapNotNull null
                    val vector = EmbeddingVectors.fromBlob(row.vec, row.dim)
                    if (vector.size != queryVector.size) return@mapNotNull null
                    val similarity = EmbeddingVectors.dot(queryVector, vector)
                    if (similarity <= 0f) null else row to similarity
                }
                .sortedByDescending { it.second }
                .take((limit * 2).coerceAtLeast(MIN_VECTOR_CANDIDATES))
                .toList()
        if (scored.isEmpty()) return base

        val vectorCandidates = resolve(scored)
        if (vectorCandidates.isEmpty()) return base

        // 倒排榜单先按稀有度排（RoomMemorySearcher 只负责找出命中行，不排序）
        val invertedRank =
            base.sortedWith(
                compareByDescending<MemoryCandidate> { it.rarity }
                    .thenByDescending { it.updatedAt },
            ).map { it.key }
        val vectorRank = scored.map { "${it.first.kind}:${it.first.refId}" }

        val fused = Rrf.fuse(listOf(invertedRank, vectorRank), limit = (limit * 3).coerceAtLeast(limit))
        val byKey = (base + vectorCandidates).associateBy { it.key }
        return fused.mapNotNull { byKey[it] }
    }

    /** 把向量命中还原成候选记忆；倒排也命中时以倒排那条为准（带词权重）。 */
    private suspend fun resolve(
        scored: List<Pair<MemoryVectorEntity, Float>>,
    ): List<MemoryCandidate> {
        val factIds = scored.filter { it.first.kind == MemoryKind.FACT.name }.map { it.first.refId }
        val roomIds = scored.filter { it.first.kind == MemoryKind.ROOM.name }.map { it.first.refId }
        val facts = if (factIds.isEmpty()) emptyMap() else factDao.byIds(factIds).associateBy { it.id }
        val rooms = if (roomIds.isEmpty()) emptyMap() else roomDao.byIds(roomIds).associateBy { it.id }

        return scored.mapNotNull { (row, similarity) ->
            val kind = runCatching { MemoryKind.valueOf(row.kind) }.getOrNull() ?: return@mapNotNull null
            when (kind) {
                MemoryKind.FACT -> {
                    val fact = facts[row.refId] ?: return@mapNotNull null
                    MemoryCandidate(
                        kind = MemoryKind.FACT,
                        refId = fact.id,
                        text = fact.text,
                        updatedAt = fact.updatedAt,
                        // 语义相似度直接当稀有度：归一化后点积就在 0..1
                        rarity = similarity.toDouble(),
                        matchedTerms = 1,
                        importance = 1.0,
                        senderId = fact.senderId,
                    )
                }

                MemoryKind.ROOM -> {
                    val message = rooms[row.refId] ?: return@mapNotNull null
                    MemoryCandidate(
                        kind = MemoryKind.ROOM,
                        refId = message.id,
                        text = message.content,
                        updatedAt = message.timestampMillis,
                        rarity = similarity.toDouble(),
                        matchedTerms = 1,
                        importance = if (message.role == "assistant") 0.55 else 0.8,
                        senderId = message.senderId,
                        senderName = message.senderName,
                        role = message.role,
                        externalId = message.externalId,
                        personaId = message.personaId,
                    )
                }

                // 设定与梗库目前只做向量入库，不参与回复上下文；
                // 将来接入时在这里补 LoreDao / SlangDao 的回查即可。
                MemoryKind.LORE, MemoryKind.SLANG -> null
            }
        }
    }

    private companion object {
        /** 向量候选至少取这么多，避免 limit 很小时召回面太窄 */
        const val MIN_VECTOR_CANDIDATES = 16
    }
}

/** 手动回填的进度。 */
data class VectorBackfillState(
    val running: Boolean = false,
    /** 当前模型下已向量化的条数 */
    val done: Int = 0,
    /** 可向量化的源记录总数（facts + room + lore + slang） */
    val total: Int = 0,
    /** 向量占用字节数（dim × 4 累加） */
    val bytes: Long = 0,
    val message: String? = null,
    val paused: Boolean = false,
)

/**
 * 存量记忆向量化（手动触发）。
 *
 * 不自动跑：用户可能用流量、可能被限流。每批 [BATCH] 条、批间隔 1 秒，
 * 退出页面即停止，下次从「还没有当前模型向量」的行继续。
 * 只处理 facts / room / lore / slang，**不碰 ai_messages**。
 */
class MemoryVectorBackfill(
    private val dao: MemoryVectorDao,
    private val roomDao: RoomMessageDao,
    private val client: EmbeddingClient,
    private val guard: EmbeddingGuard,
    private val configProvider: () -> EmbeddingConfig,
    private val logger: (String) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow(VectorBackfillState())
    val state: StateFlow<VectorBackfillState> = _state.asStateFlow()

    private var job: Job? = null

    /** 页面打开时刷新一次进度（不触发任何网络调用）。 */
    suspend fun refresh() {
        val model = configProvider().model.trim()
        val done = runCatching { dao.countForModel(model) }.getOrDefault(0)
        val total =
            runCatching {
                dao.factTotal() + dao.roomTotal() + dao.loreTotal() + dao.slangTotal()
            }.getOrDefault(0)
        val bytes = runCatching { dao.sumDims(model) * 4 }.getOrDefault(0L)
        _state.value = _state.value.copy(done = done, total = total, bytes = bytes)
    }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        val config = configProvider()
        if (!config.ready) {
            _state.value =
                _state.value.copy(
                    running = false,
                    message = "请先填写 Key 并「测试连接」探测维度，再开始向量化",
                )
            return
        }
        // 手动点「开始」意味着用户明确要求重试：清掉熔断与查询缓存
        guard.reset()
        job =
            scope.launch {
                _state.value = _state.value.copy(running = true, message = null, paused = false)
                try {
                    refresh()
                    processFacts(config)
                    processRooms(config)
                    processLore(config)
                    processSlang(config)
                    refresh()
                    if (_state.value.paused) {
                        _state.value =
                            _state.value.copy(message = "向量化已暂停 10 分钟，稍后可再次点「开始向量化」")
                    } else {
                        _state.value = _state.value.copy(message = "本轮已完成")
                    }
                } finally {
                    _state.value = _state.value.copy(running = false)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false, message = "已停止，下次从断点继续")
    }

    private suspend fun processFacts(config: EmbeddingConfig) {
        var cursor = 0L
        while (currentCoroutineContext().isActive && !guard.isPaused()) {
            val batch = dao.factsWithoutVectors(config.model.trim(), cursor, BATCH)
            if (batch.isEmpty()) return
            val eligible = batch.filter { it.text.trim().length >= MIN_TEXT_CHARS }
            if (eligible.isNotEmpty()) {
                val vectors = embedBatch(eligible.map { it.text }, config, "FACT") ?: return
                val at = clock()
                dao.upsertAll(
                    eligible.zip(vectors).mapNotNull { (row, vector) ->
                        if (vector.isEmpty()) return@mapNotNull null
                        MemoryVectorEntity(
                            kind = MemoryKind.FACT.name,
                            refId = row.id,
                            model = config.model.trim(),
                            dim = vector.size,
                            scopeKey = ConversationKeys.factScope(row.senderId),
                            vec = EmbeddingVectors.toBlob(vector),
                            createdAt = at,
                        )
                    },
                )
            }
            cursor = batch.last().id
            refresh()
            delay(BATCH_DELAY_MILLIS)
        }
    }

    private suspend fun processRooms(config: EmbeddingConfig) {
        var cursor = 0L
        while (currentCoroutineContext().isActive && !guard.isPaused()) {
            val batch = dao.roomsWithoutVectors(config.model.trim(), cursor, BATCH)
            if (batch.isEmpty()) return
            val payloads = batch.map { row -> row to roomText(row) }
            val eligible = payloads.filter { it.second.trim().length >= MIN_TEXT_CHARS }
            if (eligible.isNotEmpty()) {
                val vectors = embedBatch(eligible.map { it.second }, config, "ROOM") ?: return
                val at = clock()
                dao.upsertAll(
                    eligible.zip(vectors).mapNotNull { (entry, vector) ->
                        if (vector.isEmpty()) return@mapNotNull null
                        MemoryVectorEntity(
                            kind = MemoryKind.ROOM.name,
                            refId = entry.first.id,
                            model = config.model.trim(),
                            dim = vector.size,
                            scopeKey = entry.first.scopeKey,
                            vec = EmbeddingVectors.toBlob(vector),
                            createdAt = at,
                        )
                    },
                )
            }
            cursor = batch.last().id
            refresh()
            delay(BATCH_DELAY_MILLIS)
        }
    }

    private suspend fun processLore(config: EmbeddingConfig) {
        var cursor = 0L
        while (currentCoroutineContext().isActive && !guard.isPaused()) {
            val batch = dao.loreWithoutVectors(config.model.trim(), cursor, BATCH)
            if (batch.isEmpty()) return
            val eligible = batch.filter { "${it.name}：${it.note}".trim().length >= MIN_TEXT_CHARS }
            if (eligible.isNotEmpty()) {
                val vectors = embedBatch(eligible.map { "${it.name}：${it.note}" }, config, "LORE") ?: return
                val at = clock()
                dao.upsertAll(
                    eligible.zip(vectors).mapNotNull { (row, vector) ->
                        if (vector.isEmpty()) return@mapNotNull null
                        MemoryVectorEntity(
                            kind = MemoryKind.LORE.name,
                            refId = row.id,
                            model = config.model.trim(),
                            dim = vector.size,
                            scopeKey = loreScope(row.personaId, row.scopeGroupId),
                            vec = EmbeddingVectors.toBlob(vector),
                            createdAt = at,
                        )
                    },
                )
            }
            cursor = batch.last().id
            refresh()
            delay(BATCH_DELAY_MILLIS)
        }
    }

    private suspend fun processSlang(config: EmbeddingConfig) {
        var cursor = 0L
        while (currentCoroutineContext().isActive && !guard.isPaused()) {
            val batch = dao.slangWithoutVectors(config.model.trim(), cursor, BATCH)
            if (batch.isEmpty()) return
            val texts = batch.map { row -> slangText(row) }
            val eligible = batch.zip(texts).filter { it.second.trim().length >= MIN_TEXT_CHARS }
            if (eligible.isNotEmpty()) {
                val vectors = embedBatch(eligible.map { it.second }, config, "SLANG") ?: return
                val at = clock()
                dao.upsertAll(
                    eligible.zip(vectors).mapNotNull { (entry, vector) ->
                        if (vector.isEmpty()) return@mapNotNull null
                        MemoryVectorEntity(
                            kind = MemoryKind.SLANG.name,
                            refId = entry.first.id,
                            model = config.model.trim(),
                            dim = vector.size,
                            scopeKey = loreScope(null, entry.first.scopeGroupId),
                            vec = EmbeddingVectors.toBlob(vector),
                            createdAt = at,
                        )
                    },
                )
            }
            cursor = batch.last().id
            refresh()
            delay(BATCH_DELAY_MILLIS)
        }
    }

    private suspend fun embedBatch(
        texts: List<String>,
        config: EmbeddingConfig,
        label: String,
    ): List<FloatArray>? =
        try {
            client.embed(texts, config).map { EmbeddingVectors.normalize(it) }.also {
                guard.recordSuccess()
                _state.value = _state.value.copy(paused = false)
            }
        } catch (t: Throwable) {
            val paused = guard.recordFailure()
            logger("向量回填失败[$label]：${t.message ?: t::class.simpleName}")
            _state.value =
                _state.value.copy(
                    paused = paused,
                    message =
                        "向量化中断：${t.message ?: t::class.simpleName}" +
                            if (paused) "（已暂停 10 分钟）" else "",
                )
            null
        }

    private suspend fun roomText(row: RoomMessageEntity): String =
        roomDao.windowBefore(row.scopeKey, row.id, MemoryVectorIndexer.ROOM_WINDOW)
            .asReversed()
            .joinToString("\n") { "${speaker(it)}：${it.content}" }

    private fun speaker(row: RoomMessageEntity): String =
        if (row.role == "assistant") {
            "你"
        } else {
            row.senderName?.takeIf { it.isNotBlank() } ?: "群友"
        }

    private fun slangText(row: SlangEntity): String =
        buildString {
            append(row.term).append("：").append(row.meaning)
            val usage = row.usage?.trim().orEmpty()
            if (usage.isNotEmpty()) append("。用法：").append(usage)
        }

    private fun loreScope(
        personaId: Long?,
        scopeGroupId: String?,
    ): String = scopeGroupId?.takeIf { it.isNotBlank() }?.let { ConversationKeys.groupRoom(it) }
        ?: "p:${personaId ?: 0}"

    private companion object {
        /** 每批 20 条：一次请求拿 20 个向量，既快又不容易触发限流 */
        const val BATCH = 20

        /** 批间隔 ≥ 1 秒 */
        const val BATCH_DELAY_MILLIS = 1_000L

        const val MIN_TEXT_CHARS = MemoryVectorIndexer.MIN_TEXT_CHARS
    }
}
