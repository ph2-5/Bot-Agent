package com.qbot.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 接话判断的可训练闭环。
 *
 * 大模型无法在线训练权重，但可以「记住教训」：
 * 每次判断留一条结构化快照 → 主人标记对错 → 把错例反思成一条经验 →
 * 下次判断时把经验注入提示词。效果上等同于「后天训练」，
 * 而且每一条经验都能被查看、编辑、删除，不会变成黑箱。
 *
 * 这个文件只定义数据结构与接口，落库在 app 模块（Room），
 * botcore 保持纯 JVM。
 */

/** 一次插话判断的结构化快照（只写一次，之后只更新反馈字段）。 */
data class ChimeDecisionRecord(
    val groupId: String,
    /** 发言者 openid。好感度（路线图）未来按人累计，必须留。 */
    val speakerId: String?,
    val speakerName: String?,
    val messageText: String,
    /** 最近几条对话的转录摘要，供复盘页还原"当时是什么局面"。 */
    val contextSnippet: String,
    /** 规则打分；未进入打分环节（Forced/Skip）时为 null。 */
    val ruleScore: Int?,
    /** FORCED / CANDIDATE / SKIP，对应漏斗走到哪一级。 */
    val gatePath: String,
    /** AI 的原始判断 YES / NO；未询问模型时为 null。 */
    val aiVerdict: String?,
    val aiReason: String?,
    /** SPOKE（说了）/ SILENT（没说）。 */
    val action: String,
    /** 人读的最终结论，如「不接：AI 判断不需要接话」。 */
    val reason: String,
    val replyText: String?,
    /** 是否 @ 了机器人（情绪值/好感度未来要用）。 */
    val addressed: Boolean,
    /** 是否被指名冒犯（情绪值未来要用）。 */
    val insulted: Boolean,
    val externalId: String?,
    val createdAt: Long,
) {
    companion object {
        const val ACTION_SPOKE = "SPOKE"
        const val ACTION_SILENT = "SILENT"

        const val GATE_FORCED = "FORCED"
        const val GATE_CANDIDATE = "CANDIDATE"
        const val GATE_SKIP = "SKIP"
    }
}

/** 快照 + 用户反馈，供复盘页展示。 */
data class ChimeDecision(
    val id: Long,
    val record: ChimeDecisionRecord,
    /** null = 未反馈；true = 判断正确；false = 判断错误。 */
    val feedback: Boolean? = null,
    /** 反馈为错时用户认为该怎么做：SHOULD_SPEAK / SHOULD_SILENT。 */
    val correctedAction: String? = null,
    val feedbackNote: String? = null,
    val feedbackAt: Long? = null,
) {
    companion object {
        const val SHOULD_SPEAK = "SHOULD_SPEAK"
        const val SHOULD_SILENT = "SHOULD_SILENT"
    }

    val wasCorrect: Boolean? get() = feedback
}

/** 近 N 天的判断质量统计。 */
data class ChimeStats(
    val total: Int = 0,
    val reviewed: Int = 0,
    val correct: Int = 0,
    /** 不该说却说了 */
    val wrongSpoke: Int = 0,
    /** 该说没说 */
    val wrongSilent: Int = 0,
) {
    /** 正确率百分比；没有反馈时返回 null（而不是 0，避免误读）。 */
    val accuracyPercent: Int?
        get() = if (reviewed == 0) null else correct * 100 / reviewed
}

/** 从反馈里学到的一条经验。 */
data class ChimeLesson(
    val id: Long = 0,
    val text: String,
    val source: String = SOURCE_REFLECTION,
    /** null = 全局经验；非空 = 只在某个群生效（不同群有不同脾气）。 */
    val scopeGroupId: String? = null,
    val fromDecisionId: Long? = null,
    /** 被注入判断提示词的次数。 */
    val hits: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    companion object {
        const val SOURCE_REFLECTION = "REFLECTION"
        const val SOURCE_MANUAL = "MANUAL"

        /** 经验库上限：太多了反而互相冲突，也会撑大每次判断的提示词。 */
        const val MAX_LESSONS = 40

        /** 单条经验字数上限（和反思提示词的要求一致）。 */
        const val MAX_TEXT_CHARS = 40

        /** 每次判断最多注入几条经验。 */
        const val MAX_INJECTED = 8
    }
}

/**
 * 决策出口。
 *
 * [com.qbot.core.engine.BotRuntime] 全局只调用一次（handleChitchat 的 finally），
 * 未来插件化时把实现换成事件总线即可，运行时不用动。
 */
fun interface ChimeDecisionSink {
    suspend fun onDecision(record: ChimeDecisionRecord)
}

/** 决策日志仓储（复盘页用）。 */
interface ChimeDecisionStore {
    fun observe(groupId: String?, limit: Int): Flow<List<ChimeDecision>>

    suspend fun find(id: Long): ChimeDecision?

    suspend fun markFeedback(
        id: Long,
        correct: Boolean,
        correctedAction: String?,
        note: String?,
        at: Long,
    )

    suspend fun stats(sinceMillis: Long): ChimeStats

    /** 保留最近 [keep] 条且不早于 [beforeMillis]。 */
    suspend fun trim(beforeMillis: Long, keep: Int)
}

/** 经验库仓储。 */
interface ChimeLessonStore {
    fun observeAll(): Flow<List<ChimeLesson>>

    suspend fun all(): List<ChimeLesson>

    /** 注入用：该群专属优先，其次全局；各按更新时间倒序。 */
    suspend fun promptLessons(groupId: String, limit: Int): List<ChimeLesson>

    suspend fun insert(lesson: ChimeLesson): Long

    suspend fun updateText(id: Long, text: String, updatedAt: Long)

    suspend fun setEnabled(id: Long, enabled: Boolean)

    suspend fun delete(id: Long)

    suspend fun bumpHits(ids: Collection<Long>)

    suspend fun trim(keep: Int)
}

/**
 * 训练器：决策落库 + 经验注入 + 命中计数。
 *
 * Android 侧由两个 Room 仓储组合实现；测试用 [InMemoryChimeTrainer]。
 */
interface ChimeTrainer : ChimeDecisionSink {
    suspend fun lessonsForPrompt(groupId: String, limit: Int = ChimeLesson.MAX_INJECTED): List<ChimeLesson>

    suspend fun bumpHits(ids: Collection<Long>)
}

/** botcore 自带的内存实现，供测试与「不落库」模式使用。 */
class InMemoryChimeTrainer : ChimeTrainer {
    private val records = mutableListOf<ChimeDecisionRecord>()
    private val stored = mutableListOf<ChimeLesson>()
    private var nextId = 1L

    val decisions: List<ChimeDecisionRecord> get() = records.toList()
    val lessons: List<ChimeLesson> get() = stored.toList()

    override suspend fun onDecision(record: ChimeDecisionRecord) {
        records += record
    }

    override suspend fun lessonsForPrompt(groupId: String, limit: Int): List<ChimeLesson> =
        stored
            .filter { it.enabled && (it.scopeGroupId == null || it.scopeGroupId == groupId) }
            .sortedWith(
                compareByDescending<ChimeLesson> { it.scopeGroupId != null }
                    .thenByDescending { it.updatedAt },
            ).take(limit)

    override suspend fun bumpHits(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        stored.replaceAll { lesson ->
            if (lesson.id in set) lesson.copy(hits = lesson.hits + 1) else lesson
        }
    }

    /** 测试辅助：直接放一条经验进去。 */
    fun addLesson(
        text: String,
        scopeGroupId: String? = null,
        enabled: Boolean = true,
        updatedAt: Long = 0,
    ): ChimeLesson {
        val lesson =
            ChimeLesson(
                id = nextId++,
                text = text,
                scopeGroupId = scopeGroupId,
                enabled = enabled,
                createdAt = updatedAt,
                updatedAt = updatedAt,
            )
        stored += lesson
        return lesson
    }
}

/**
 * 反思：把「一次错判 + 主人的纠正」浓缩成一条可复用经验。
 *
 * 提示词与解析都放在 botcore，app 只负责调用模型与落库，
 * 这样规则可以被单元测试固定住。
 */
object ChimeReflection {
    const val MIN_TEXT_CHARS = 4

    fun buildSystemPrompt(): String =
        "你在帮一个群聊机器人总结经验。用户会给你一次「是否该接话」的判断记录，" +
            "以及主人对这个判断的纠正。请把它总结成一条以后可以直接照着做的经验。\n" +
            "要求：\n" +
            "- 一条经验不超过 ${ChimeLesson.MAX_TEXT_CHARS} 字；\n" +
            "- 用第二人称祈使句，写清「什么场景下该/不该做什么」；\n" +
            "- 只依据这次反馈，不要编造；\n" +
            "- 只输出经验文本，不要编号、解释、引号或前后缀。"

    fun buildUserPrompt(decision: ChimeDecision, lessons: List<ChimeLesson>): String {
        val r = decision.record
        val verdict =
            when {
                r.aiVerdict == null -> "未询问模型（规则层已决定）"
                r.aiReason.isNullOrBlank() -> r.aiVerdict
                else -> "${r.aiVerdict}（理由：${r.aiReason}）"
            }
        val action =
            if (r.action == ChimeDecisionRecord.ACTION_SPOKE) {
                "接话了${r.replyText?.let { "：「${it.take(60)}」" }.orEmpty()}"
            } else {
                "没有接话"
            }
        val correction =
            when (decision.correctedAction) {
                ChimeDecision.SHOULD_SPEAK -> "判断错了，这种情况应该接话"
                ChimeDecision.SHOULD_SILENT -> "判断错了，这种情况应该保持安静"
                else -> "判断错了"
            }
        val existing =
            lessons
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "- ${it.text}" }
                .orEmpty()

        return buildString {
            if (r.contextSnippet.isNotBlank()) {
                append("【最近的对话】\n").append(r.contextSnippet).append("\n\n")
            }
            append("【当前消息】[")
                .append(r.speakerName ?: "群友")
                .append("]：")
                .append(r.messageText.take(120))
                .append("\n")
            r.ruleScore?.let { append("【规则打分】").append(it).append("\n") }
            append("【你的判断】").append(verdict).append("\n")
            append("【实际动作】").append(action).append("\n")
            append("【主人反馈】").append(correction).append("\n")
            decision.feedbackNote?.takeIf { it.isNotBlank() }?.let {
                append("【主人备注】").append(it.take(100)).append("\n")
            }
            if (existing.isNotBlank()) {
                append("\n【已有经验】（避免重复；如与某条说的是同一件事，直接给出更准确的版本）\n")
                append(existing).append("\n")
            }
            append("\n请输出一条经验。")
        }
    }

    /**
     * 清洗模型输出：去掉编号、项目符号、引号与「经验：」之类的前缀。
     *
     * 模型经常不听话，宁可多洗几层也不要把脏文本存进经验库。
     */
    fun parseLesson(raw: String): String? {
        val firstLine =
            raw.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: return null

        var text =
            firstLine
                .removePrefix("- ")
                .removePrefix("* ")
                .removePrefix("· ")
                .replace(Regex("^\\d+[.、)]\\s*"), "")
                .removePrefix("经验：")
                .removePrefix("经验:")
                .removePrefix("记住：")
                .removePrefix("记住:")
                .trim()
                .trim('"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』')
                .trim()

        if (text.length > ChimeLesson.MAX_TEXT_CHARS) {
            text = text.take(ChimeLesson.MAX_TEXT_CHARS)
        }
        return text.takeIf { it.length >= MIN_TEXT_CHARS }
    }

    /**
     * 找一条「说的是同一件事」的旧经验。
     *
     * 用中文 bigram 的重叠系数而不是编辑距离：改几个字的同义表述
     * 重叠度仍然很高，而换一件完全不同的场景几乎不重叠。
     */
    fun findSimilar(text: String, lessons: List<ChimeLesson>): ChimeLesson? {
        val target = bigrams(text)
        if (target.isEmpty()) return null
        return lessons
            .map { it to similarity(target, bigrams(it.text)) }
            .filter { (_, score) -> score >= 0.6 }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    /** bigram 重叠系数 = |A∩B| / min(|A|,|B|)，对包含关系更敏感。 */
    fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / minOf(a.size, b.size)
    }

    fun bigrams(text: String): Set<String> {
        val clean = text.filter { !it.isWhitespace() }
        if (clean.length < 2) return emptySet()
        return (0 until clean.length - 1).map { clean.substring(it, it + 2) }.toSet()
    }
}
