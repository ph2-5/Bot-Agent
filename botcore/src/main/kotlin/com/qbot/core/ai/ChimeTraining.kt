package com.qbot.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 接话判断的可训练闭环。
 *
 * 大模型无法在线训练权重，但可以「记住教训」：
 * 每次判断留一条结构化快照 → 主人标记对错 → 把错例反思成一条经验 →
 * 下次判断时把经验注入提示词。效果上等同于「后天训练」，
 * 而且每一条经验都能被查看、编辑、删除，不会变成黑箱。
 *
 * 2.1 起经验库从「扁平淡句列表」升级为**带目录的结构**：
 * 固定类别 + 同类合并 + 每类上限 + 支撑计数，解决「同一类判断散成多条」。
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
    /** 这句话大概率是对谁说的：BOT / MEMBER / GROUP / UNKNOWN */
    val addresseeKind: String? = null,
    /** 对某个成员说时的名字 */
    val addresseeName: String? = null,
    /**
     * 做出这次判断时实际扮演的人格。
     *
     * 开启「按人设分开记忆」后，由这次判断反思出的经验只归这个人格；
     * null 表示未开隔离（经验共用）。
     */
    val personaId: Long? = null,
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

/**
 * 经验目录：固定类别，不让模型自由发挥。
 *
 * 自由生成类别很快会漂移成几十个近义词（"私聊"/"两人对话"/"一对一"），
 * 目录就失去意义了。这里给一组稳定、互斥的类别，模型只能从中选。
 */
object LessonCategories {
    const val PRIVATE_CHAT = "PRIVATE_CHAT"
    const val COLD_START = "COLD_START"
    const val WORK = "WORK"
    const val TIME = "TIME"
    const val HELP = "HELP"
    const val HUMOR = "HUMOR"
    const val CONFLICT = "CONFLICT"
    const val OTHER = "OTHER"

    /** 顺序即展示顺序：从最常见的场景到兜底。 */
    val ALL: List<Pair<String, String>> =
        listOf(
            PRIVATE_CHAT to "两人对话",
            COLD_START to "冷场搭话",
            HELP to "求助提问",
            WORK to "工作正经",
            TIME to "时段禁忌",
            HUMOR to "玩笑边界",
            CONFLICT to "争执情绪",
            OTHER to "其他",
        )

    fun label(id: String?): String =
        ALL.firstOrNull { it.first == id }?.second ?: "其他"

    /** 模型给的类别可能大小写不一或带解释，归一化到已知值，否则归入其他。 */
    fun normalize(raw: String?): String {
        val key = raw?.trim()?.uppercase()?.replace('-', '_')?.replace(' ', '_') ?: return OTHER
        return if (ALL.any { it.first == key }) key else OTHER
    }

    /**
     * 关键词兜底分类。
     *
     * 模型输出不合法时（纯文本或类别瞎写）用它猜一个，比一律归入"其他"有用得多。
     */
    fun classify(text: String): String =
        when {
            listOf("私聊", "两人", "两个人", "一对一", "互相", "对话时").any { text.contains(it) } ->
                PRIVATE_CHAT
            listOf("冷场", "没人说话", "没人接", "气氛", "抛话题").any { text.contains(it) } -> COLD_START
            listOf("工作", "开会", "正经", "业务", "加班").any { text.contains(it) } -> WORK
            listOf("凌晨", "深夜", "半夜", "早上", "晚上", "时段", "点后").any { text.contains(it) } -> TIME
            listOf("求助", "提问", "问题", "帮忙", "求解").any { text.contains(it) } -> HELP
            listOf("玩笑", "梗", "调侃", "幽默", "逗").any { text.contains(it) } -> HUMOR
            listOf("争", "吵", "骂", "生气", "情绪", "冲突").any { text.contains(it) } -> CONFLICT
            else -> OTHER
        }
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
    /** 目录类别（[LessonCategories] 里的常量）。 */
    val category: String = LessonCategories.OTHER,
    /** 被多少条反馈支撑：同类相似反馈会累加，用于排序与淘汰。 */
    val supportCount: Int = 1,
    /**
     * 这条经验属于哪个人格。
     *
     * null = 通用经验（任何人格都能用）；非空 = 只在该人格下生效。
     * 关闭「按人设分开记忆」时读写都不看这一列。
     */
    val personaId: Long? = null,
) {
    companion object {
        const val SOURCE_REFLECTION = "REFLECTION"
        const val SOURCE_MANUAL = "MANUAL"

        /**
         * 经验库容量：可选范围与默认值。
         *
         * 容量只影响存储与相似去重池，不影响每次判断的提示词长度——
         * 长期使用的群会不断积累，所以默认给到 80，允许调到 200。
         */
        const val MIN_LESSONS = 20
        const val MAX_LESSONS = 200
        const val DEFAULT_MAX_LESSONS = 80

        /** 单条经验字数上限（和反思提示词的要求一致）。 */
        const val MAX_TEXT_CHARS = 40

        /**
         * 每次判断注入几条经验：范围与默认值。
         *
         * 这才是真正影响提示词成本与稳定性的参数，默认 8 条。
         */
        const val MIN_INJECTED = 3
        const val MAX_INJECTED = 12
        const val DEFAULT_INJECTED = 8

        /**
         * 同一类别的条数上限。
         *
         * 超过就触发一次「该类归并」：让模型把同类的多条压成更概括的几条，
         * 避免「两人私聊」这一个场景散出十几条近义经验。
         */
        const val MAX_PER_CATEGORY = 6

        /** 注入时同一类别最多占几条，防止八条经验全是同一件事。 */
        const val MAX_PER_CATEGORY_INJECTED = 3
    }
}

/**
 * 经验注入的统一挑选规则。
 *
 * 内存实现与 Room 实现都走这里，保证「测试里绿的」和「真机上的」是同一套逻辑：
 * 1. 过滤：启用 + 本群或全局 + 人格可见（null 通用 / 等于当前人格）；
 * 2. 排序：本群优先 → 时间衰减后的支撑分（30 天前的经验权重减半）；
 * 3. 配额：同一类别最多 [ChimeLesson.MAX_PER_CATEGORY_INJECTED] 条。
 *
 * 衰减解决的是「一条早期错判经验永远排第一」——支撑再多，过几个月也该让位。
 */
object LessonSelection {
    private const val DECAY_DAYS = 30.0
    private const val DAY_MILLIS = 24.0 * 3600.0 * 1000.0

    fun decayedScore(
        lesson: ChimeLesson,
        now: Long,
    ): Double {
        val ageDays = ((now - lesson.updatedAt).coerceAtLeast(0L)) / DAY_MILLIS
        return lesson.supportCount / (1.0 + ageDays / DECAY_DAYS)
    }

    fun pick(
        lessons: List<ChimeLesson>,
        limit: Int,
        groupId: String?,
        personaId: Long?,
        now: Long,
    ): List<ChimeLesson> {
        val sorted =
            lessons
                .asSequence()
                .filter { it.enabled }
                .filter { it.scopeGroupId == null || it.scopeGroupId == groupId }
                .filter { personaId == null || it.personaId == null || it.personaId == personaId }
                .sortedWith(
                    compareByDescending<ChimeLesson> { it.scopeGroupId != null }
                        .thenByDescending { decayedScore(it, now) }
                        .thenByDescending { it.updatedAt },
                )
                .toList()
        val perCategory = mutableMapOf<String, Int>()
        val picked = mutableListOf<ChimeLesson>()
        for (lesson in sorted) {
            if (picked.size >= limit) break
            val used = perCategory[lesson.category] ?: 0
            if (used >= ChimeLesson.MAX_PER_CATEGORY_INJECTED) continue
            perCategory[lesson.category] = used + 1
            picked += lesson
        }
        return picked
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

    /** 注入用：该群专属优先，其次全局；人格与类别配额由 [LessonSelection] 统一裁剪。 */
    suspend fun promptLessons(
        groupId: String,
        limit: Int,
        personaId: Long? = null,
    ): List<ChimeLesson>

    suspend fun insert(lesson: ChimeLesson): Long

    suspend fun updateText(id: Long, text: String, updatedAt: Long)

    /** 同类命中：改写文本并累加支撑数。 */
    suspend fun mergeInto(id: Long, text: String, supportDelta: Int, updatedAt: Long)

    suspend fun setEnabled(id: Long, enabled: Boolean)

    suspend fun delete(id: Long)

    suspend fun deleteMany(ids: Collection<Long>)

    suspend fun bumpHits(ids: Collection<Long>)

    suspend fun sameCategory(
        groupId: String?,
        category: String,
        /** 只看该人格的经验；null 表示沿用存储的当前视角（未开隔离时即全部） */
        personaId: Long? = null,
    ): List<ChimeLesson>

    suspend fun trim(keep: Int)
}

/**
 * 训练器：决策落库 + 经验注入 + 命中计数。
 *
 * Android 侧由两个 Room 仓储组合实现；测试用 [InMemoryChimeTrainer]。
 */
interface ChimeTrainer : ChimeDecisionSink {
    suspend fun lessonsForPrompt(
        groupId: String,
        limit: Int = ChimeLesson.DEFAULT_INJECTED,
        personaId: Long? = null,
    ): List<ChimeLesson>

    suspend fun bumpHits(ids: Collection<Long>)
}

/** botcore 自带的内存实现，供测试与「不落库」模式使用。 */
class InMemoryChimeTrainer(
    private val now: () -> Long = System::currentTimeMillis,
) : ChimeTrainer {
    private val records = mutableListOf<ChimeDecisionRecord>()
    private val stored = mutableListOf<ChimeLesson>()
    private var nextId = 1L

    val decisions: List<ChimeDecisionRecord> get() = records.toList()
    val lessons: List<ChimeLesson> get() = stored.toList()

    override suspend fun onDecision(record: ChimeDecisionRecord) {
        records += record
    }

    override suspend fun lessonsForPrompt(
        groupId: String,
        limit: Int,
        personaId: Long?,
    ): List<ChimeLesson> =
        LessonSelection.pick(
            lessons = stored.toList(),
            limit = limit,
            groupId = groupId,
            personaId = personaId,
            now = now(),
        )

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
        category: String = LessonCategories.OTHER,
        supportCount: Int = 1,
        personaId: Long? = null,
    ): ChimeLesson {
        val lesson =
            ChimeLesson(
                id = nextId++,
                text = text,
                scopeGroupId = scopeGroupId,
                enabled = enabled,
                createdAt = updatedAt,
                updatedAt = updatedAt,
                category = category,
                supportCount = supportCount,
                personaId = personaId,
            )
        stored += lesson
        return lesson
    }
}

/** 反思解析结果：经验文本 + 目录类别。 */
data class ParsedLesson(
    val category: String,
    val text: String,
)

/**
 * 反思：把「一次错判 + 主人的纠正」浓缩成一条可复用经验。
 *
 * 提示词与解析都放在 botcore，app 只负责调用模型与落库，
 * 这样规则可以被单元测试固定住。
 */
object ChimeReflection {
    const val MIN_TEXT_CHARS = 4

    private val json = Json { ignoreUnknownKeys = true }

    fun buildSystemPrompt(): String =
        "你在帮一个群聊机器人总结「什么场合它想接话」的偏好。用户会给你一次判断记录，" +
            "以及主人对这个判断的纠正。请归入一个固定类别，总结成一条以后判断时参考的偏好。\n" +
            "类别只能从下面选一个：\n" +
            LessonCategories.ALL.joinToString("\n") { "- ${it.first}：${it.second}" } +
            "\n\n要求：\n" +
            "- 一条经验不超过 ${ChimeLesson.MAX_TEXT_CHARS} 字；\n" +
            "- 用第二人称写清「什么场景下你会想接 / 不想接」；\n" +
            "- 只依据这次反馈，不要编造；\n" +
            "- 只输出一个 JSON 对象，格式：{\"category\":\"类别\",\"text\":\"经验\"}，" +
            "不要输出代码块、解释或其它文字。"

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
                ChimeDecision.SHOULD_SPEAK -> "判断错了，这种场合它其实会想接话"
                ChimeDecision.SHOULD_SILENT -> "判断错了，这种场合它其实不想接话"
                else -> "判断错了"
            }
        val existing =
            lessons
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") {
                    "- [${LessonCategories.label(it.category)}] ${it.text}"
                }
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
                append("\n【已有经验】（同一类别的会在入库时合并，避免重复；")
                append("如果这次说的是已有经验的一部分，直接给出更准确的版本即可）\n")
                append(existing).append("\n")
            }
            append("\n请输出 JSON。")
        }
    }

    /**
     * 解析模型输出：优先按 JSON 取类别与文本；失败则退回纯文本 + 关键词分类。
     *
     * 模型经常不听话（加代码块、加解释、只给一句话），两种路径都要能兜住。
     */
    fun parse(raw: String): ParsedLesson? {
        val cleaned = stripCodeFence(raw).trim()
        val obj = runCatching { json.parseToJsonElement(cleaned).jsonObject }.getOrNull()
        val text =
            obj?.get("text")?.jsonPrimitive?.contentOrNull?.let { normalizeText(it) }
                ?: parseLesson(cleaned)
                ?: return null
        val category =
            obj?.get("category")?.jsonPrimitive?.contentOrNull?.let { LessonCategories.normalize(it) }
                ?: LessonCategories.classify(text)
        return ParsedLesson(category = category, text = text)
    }

    /**
     * 清洗模型输出：去掉编号、项目符号、引号与「经验：」之类的前缀。
     *
     * 模型经常不听话，宁可多洗几层也不要把脏文本存进经验库。
     */
    fun parseLesson(raw: String): String? {
        val firstLine =
            stripCodeFence(raw).lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: return null
        return normalizeText(firstLine)
    }

    private fun normalizeText(raw: String): String? {
        var text =
            raw.trim()
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

    private fun stripCodeFence(raw: String): String =
        raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

    /**
     * 找一条「说的是同一件事」的旧经验。
     *
     * 用中文 bigram 的重叠系数而不是编辑距离：改几个字的同义表述
     * 重叠度仍然很高，而换一件完全不同的场景几乎不重叠。
     *
     * 阈值 0.45 比 1.0 时代宽：调用方已先按类别过滤，同类内再卡 0.6
     * 会把「两人私聊别插话 / 别人对话时保持安静」这类同义改写漏过去。
     */
    fun findSimilar(
        text: String,
        lessons: List<ChimeLesson>,
        threshold: Double = 0.45,
    ): ChimeLesson? {
        val target = bigrams(text)
        if (target.isEmpty()) return null
        return lessons
            .map { it to similarity(target, bigrams(it.text)) }
            .filter { (_, score) -> score >= threshold }
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

    // ---------- 同类归并 ----------

    /** 归并提示词：把同一类别的多条经验压成更概括的几条。 */
    fun buildMergePrompt(
        category: String,
        lessons: List<ChimeLesson>,
    ): Pair<String, String> {
        val system =
            "你在整理一个群聊机器人的经验库。下面同一类别（${LessonCategories.label(category)}）" +
                "有多条经验，其中不少是同一件事的不同说法。请合并去重，保留所有不同场景的信息。\n" +
                "要求：\n" +
                "- 输出 1~3 条经验，每条不超过 ${ChimeLesson.MAX_TEXT_CHARS} 字；\n" +
                "- 第二人称祈使句，写清场景与做法；\n" +
                "- 只输出 JSON 数组，元素是字符串，例如 [\"经验一\",\"经验二\"]；\n" +
                "- 不要输出代码块或解释。"
        val user =
            lessons.joinToString("\n") { "- ${it.text}（被 ${it.supportCount} 次反馈支撑）" }
        return system to user
    }

    /** 解析归并结果：兼容 JSON 数组与逐行文本。 */
    fun parseMerged(raw: String): List<String> {
        val cleaned = stripCodeFence(raw).trim()
        val array = runCatching { json.parseToJsonElement(cleaned).jsonArray }.getOrNull()
        val texts =
            if (array != null) {
                array.mapNotNull { el ->
                    val prim = runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()
                    val obj =
                        runCatching { el.jsonObject.get("text")?.jsonPrimitive?.contentOrNull }
                            .getOrNull()
                    (prim ?: obj)?.let { normalizeText(it) }
                }
            } else {
                cleaned.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .mapNotNull { parseLesson(it) }
                    .toList()
            }
        return texts.distinct().take(3)
    }

    /** 从未知结构里尽量抠出可用文本（给 app 侧兜底用）。 */
    fun extractText(element: JsonObject, key: String): String? =
        runCatching { element[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
}
