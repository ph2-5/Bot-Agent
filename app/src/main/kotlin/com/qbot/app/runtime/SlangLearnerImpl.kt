package com.qbot.app.runtime

import com.qbot.app.data.repo.RoomSocialStore
import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.LlmClient
import com.qbot.core.ai.SlangLearner
import com.qbot.core.ai.SlangLookup
import com.qbot.core.ai.UnknownTermDetector
import com.qbot.core.api.DeepSeekSearch
import com.qbot.core.model.IncomingMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 聊天时学梗。
 *
 * 流程：遇到形态可疑、又不在梗库里的词 → 限流放行 → 联网搜索 →
 * 让模型结合搜索结果给一句话解释 → 入库。
 *
 * 三条克制的地方：
 * 1. **异步**：不挂在这轮回复上，它答得快比答得懂重要；
 * 2. **限流**：全局 3 分钟一次、同词 6 小时一次——每次要一次搜索 + 一次模型调用；
 * 3. **查不到就不入库**：模型说 UNKNOWN 就放弃，宁可没有，也不要写进一条胡编的解释。
 */
class SlangLearnerImpl(
    private val store: RoomSocialStore,
    private val client: LlmClient,
    private val search: DeepSeekSearch,
    private val config: () -> AiConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) : SlangLearner {
    private val mutex = Mutex()

    /** 梗库词表缓存，避免每条消息都查一次库 */
    private var knownCache: Pair<Long, Set<String>>? = null

    /** 查过的词与时间，用于"同一个词 6 小时内只查一次" */
    private val checkedAt = LinkedHashMap<String, Long>()

    private var lastRunAt = 0L

    override suspend fun maybeLearn(message: IncomingMessage, text: String) {
        val cfg = config()
        if (!cfg.enabled || !cfg.isReady) return

        val known = knownTerms()
        val term =
            UnknownTermDetector.pick(text, known, listOfNotNull(message.senderName))
                ?: return
        if (!admit(term)) return

        val evidence =
            if (cfg.webSearch) {
                runCatching { search.search("$term 什么意思 梗", cfg.apiKey, cfg.baseUrl, 5) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
        val explained =
            runCatching { SlangLookup(client).explain(term, text, cfg, evidence) }
                .getOrNull()
                ?: return
        if (explained.meaning.isBlank()) return

        // 记在当前群里：不同群的同一句话可能意思完全不同
        store.upsertSlang(explained.copy(scopeGroupId = message.targetId))
        knownCache = null
    }

    private suspend fun knownTerms(): Set<String> {
        val now = clock()
        knownCache?.let { (at, terms) -> if (now - at < 60_000) return terms }
        val terms =
            runCatching { store.allSlang().map { it.term }.toSet() }
                .getOrDefault(emptySet())
        knownCache = now to terms
        return terms
    }

    private suspend fun admit(term: String): Boolean =
        mutex.withLock {
            val now = clock()
            if (now - lastRunAt < 3 * 60_000) return@withLock false
            if (now - (checkedAt[term] ?: 0) < 6 * 3600_000) return@withLock false
            lastRunAt = now
            checkedAt[term] = now
            // 最多记 200 个词，超了就丢最早的那个——这是内存缓存，不是账本
            while (checkedAt.size > 200) {
                val oldest = checkedAt.entries.minByOrNull { it.value } ?: break
                checkedAt.remove(oldest.key)
            }
            true
        }
}
