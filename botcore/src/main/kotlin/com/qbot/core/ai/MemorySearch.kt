package com.qbot.core.ai

import kotlin.math.ln
import kotlin.math.pow

/**
 * 记忆条目的来源分类。
 *
 * 只区分「关于某个人的事实」与「群里说过的话」：这两类最容易在
 * “按时间取最近 N 条”时被漏掉——旧但相关的事实永远排不进窗口。
 */
enum class MemoryKind {
    FACT,
    ROOM,
}

/**
 * 一条被检索命中的候选记忆。
 *
 * [rarity] 由存储层按命中词的信息量累加（越少见的词权重越高），
 * 排序与时间衰减统一交给 [MemoryRanker]——这样 Android 侧只负责
 * 「找出命中行」，排序逻辑留在纯 JVM 里可测。
 */
data class MemoryCandidate(
    val kind: MemoryKind,
    /** 源表主键：facts.id / room_messages.id */
    val refId: Long,
    val text: String,
    val updatedAt: Long,
    /** 命中词的稀有度总和 */
    val rarity: Double = 0.0,
    val matchedTerms: Int = 0,
    /** 重要性倍率：事实默认 1.0，机器人的自言自语略低 */
    val importance: Double = 1.0,
    val senderId: String? = null,
    val senderName: String? = null,
    /** ROOM 专用：user / assistant */
    val role: String? = null,
    val externalId: String? = null,
    val personaId: Long? = null,
) {
    /** 去重用的稳定标识 */
    val key: String get() = "${kind.name}:$refId"
}

/**
 * 记忆检索器（由 Android 侧实现，索引表在 Room 里）。
 *
 * 约定：返回的是**未排序的候选**，排序/截断由 [MemoryRanker] 做；
 * 实现方必须保证查询失败时抛异常而不是静默返回空——
 * 管线据此回退到「按时间取最近」，不会因为索引坏了就不回复。
 */
interface MemorySearcher {
    suspend fun recall(
        query: String,
        /** 允许命中的 scopeKey 列表：群背景 g:xxx、某人事实 f:xxx */
        scopes: List<String>,
        kinds: Set<MemoryKind>,
        limit: Int,
    ): List<MemoryCandidate>
}

/**
 * 第二级精排（可选，走辅助 API）。
 *
 * 只有候选多、彼此又不确定时才值得多付一次模型调用；
 * 返回 null 表示「这次不精排」，调用方保留本地排序结果。
 */
interface MemoryReranker {
    suspend fun rerank(
        query: String,
        candidates: List<MemoryCandidate>,
        limit: Int,
    ): List<MemoryCandidate>?
}

/**
 * 中英混排的轻量分词：中文取二元组，英文/数字取整词。
 *
 * 不用系统分词器也不用 FTS：Android 自带 SQLite 不保证编译了 FTS5，
 * trigram 分词器更要 3.34+。二元组倒排表用普通 B-tree 索引就能工作，
 * 对群聊这种短句语境足够，而且行为完全可测。
 */
object MemoryTerms {
    /** 单条记忆最多建多少个索引词，防止长文把索引表撑爆 */
    const val MAX_TERMS = 48

    /** 只由虚词组成、对检索没有区分度的二元组 */
    private val STOP_TERMS =
        setOf(
            "我们", "你们", "他们", "她们", "什么", "怎么", "这个", "那个",
            "就是", "不是", "可以", "现在", "今天", "明天", "昨天", "一个",
            "没有", "还有", "如果", "因为", "所以", "但是", "而且", "然后",
            "自己", "觉得", "知道", "时候", "已经", "这么", "那么",
        )

    /**
     * 含这些字的二元组一律不建索引。
     *
     * 中文没有空格，二元组会跨词边界生成「们这」「个就」这类噪音；
     * 与其维护一张永远不够长的词表，不如按字过滤：虚词、代词、
     * 高频副词几乎不携带检索信息，删掉它们同时把索引缩小一半。
     */
    private val STOP_CHARS =
        setOf(
            '的', '了', '是', '在', '有', '和', '与', '就', '都', '也',
            '吗', '呢', '吧', '啊', '哦', '着', '过', '地', '得', '这',
            '那', '个', '们', '我', '你', '他', '她', '它', '很', '不',
            '没', '会', '要', '把', '被', '给', '让', '来', '去',
        )

    /** 抽取索引词（去重、保序） */
    fun extract(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = text.lowercase()
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < normalized.length && out.size < MAX_TERMS) {
            val ch = normalized[i]
            when {
                isCjk(ch) -> {
                    var end = i
                    while (end < normalized.length && isCjk(normalized[end])) end++
                    val run = normalized.substring(i, end)
                    if (run.length == 1) {
                        if (ch !in STOP_CHARS) out += run
                    } else {
                        for (k in 0 until run.length - 1) {
                            val bigram = run.substring(k, k + 2)
                            if (bigram in STOP_TERMS) continue
                            if (bigram.any { it in STOP_CHARS }) continue
                            out += bigram
                        }
                        // 短专名整词再收一次：「原神」比两个二元组更精确
                        if (run.length in 2..4 && run !in STOP_TERMS && run.none { it in STOP_CHARS }) {
                            out += run
                        }
                    }
                    i = end
                }

                ch.isLetterOrDigit() -> {
                    var end = i
                    while (end < normalized.length &&
                        normalized[end].isLetterOrDigit() &&
                        !isCjk(normalized[end])
                    ) {
                        end++
                    }
                    val word = normalized.substring(i, end)
                    if (word.length >= 2 && word !in STOP_TERMS) out += word
                    i = end
                }

                else -> i++
            }
        }
        return out.take(MAX_TERMS)
    }

    private fun isCjk(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF
}

/**
 * 记忆排序：稀有度 × 时间衰减 × 重要性。
 *
 * 这是 Generative Agents 那套打分的简化版——去掉了需要全量语料的
 * 归一化项，用「1 / (1 + ln(1 + df))」直接给词权，避免为了算 IDF
 * 在手机上维护一份全局文档计数。
 */
object MemoryRanker {
    /** 半衰期默认值：两周前的记忆权重剩一半 */
    const val DEFAULT_HALF_LIFE_DAYS: Int = 14

    /**
     * 词的权重：出现得越少越值钱。
     *
     * df = 含该词的记忆条数。df=1 时约 0.59，df=100 时约 0.18。
     */
    fun termWeight(df: Int): Double = 1.0 / (1.0 + ln(1.0 + df.coerceAtLeast(0).toDouble()))

    /** 一条候选的最终得分 */
    fun score(
        candidate: MemoryCandidate,
        nowMillis: Long,
        halfLifeDays: Int = DEFAULT_HALF_LIFE_DAYS,
    ): Double {
        val ageDays =
            ((nowMillis - candidate.updatedAt).coerceAtLeast(0L).toDouble() /
                MILLIS_PER_DAY)
        val decay = 0.5.pow(ageDays / halfLifeDays.coerceAtLeast(1).toDouble())
        // 只命中一个常见词时不该压过命中三个词的中等条目
        val coverage = 1.0 + 0.15 * candidate.matchedTerms.coerceAtMost(6)
        return candidate.rarity.coerceAtLeast(0.01) * decay * candidate.importance * coverage
    }

    /** 排序并截断（同分时新的在前，保证“刚发生的事”优先） */
    fun pick(
        candidates: List<MemoryCandidate>,
        limit: Int,
        nowMillis: Long,
        halfLifeDays: Int = DEFAULT_HALF_LIFE_DAYS,
    ): List<MemoryCandidate> {
        if (limit <= 0) return emptyList()
        return candidates
            .sortedWith(
                compareByDescending<MemoryCandidate> { score(it, nowMillis, halfLifeDays) }
                    .thenByDescending { it.updatedAt },
            )
            .take(limit)
    }

    private const val MILLIS_PER_DAY = 86_400_000.0
}

/**
 * 辅助模型的连接配置（记忆精排用）。
 *
 * 留空时调用方回退到主 AI 配置：用户只开开关、不填第二套地址也能用，
 * 但精排走的是同一条链路，限流与主模型共享。
 */
data class AuxAiConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val timeoutSeconds: Long = 12L,
    /** 精排只要编号，不需要长输出 */
    val maxTokens: Int = 160,
) {
    /** 是否填了独立的地址与模型（否则用主配置） */
    val standalone: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()
}
