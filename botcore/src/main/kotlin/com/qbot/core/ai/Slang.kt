package com.qbot.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一条群内/网络说法。
 *
 * 为什么不让模型"自由发挥"用梗：模型知道的网络流行语有半衰期，
 * 而且很容易用错代际和场合。真正安全的是**这个群自己在说的话**——
 * 它由群友反复使用，语义和分寸都在上下文里。
 *
 * 所以这里的定位是：群内梗自动收获（[SlangHarvest]）+ 手动补充，
 * 网络流行语交给模型自己的语感，只在提示词里给一条"别硬塞"的约束。
 */
data class SlangTerm(
    val id: Long = 0,
    val term: String,
    val meaning: String,
    /** 什么场合用，一句话 */
    val usage: String? = null,
    /** 只在这个群成立；null 表示全局 */
    val scopeGroupId: String? = null,
    /** 在群聊里出现过的次数，用于排序与淘汰 */
    val hits: Int = 0,
    val enabled: Boolean = true,
    /** HARVEST / MANUAL */
    val source: String = "HARVEST",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    companion object {
        const val SOURCE_HARVEST = "HARVEST"
        const val SOURCE_MANUAL = "MANUAL"

        /** 聊天时遇到、当场问出来的 */
        const val SOURCE_LEARNED = "LEARNED"
    }
}

/** 梗库存储。botcore 不依赖 Room，Android 侧实现。 */
interface SlangStore {
    suspend fun promptTerms(groupId: String?, limit: Int): List<SlangTerm>

    suspend fun all(): List<SlangTerm>

    suspend fun save(term: SlangTerm): Long

    suspend fun bumpHits(id: Long, delta: Int = 1)

    suspend fun setEnabled(id: Long, enabled: Boolean)

    suspend fun delete(id: Long)

    /** 备份导入：整体覆盖 */
    suspend fun replaceAll(terms: List<SlangTerm>)

    suspend fun trim(max: Int)
}

interface SlangSink {
    suspend fun record(term: SlangTerm)
}

/**
 * 群内梗的候选提取。
 *
 * 只用统计，不调模型：按标点切块、统计 2~8 字的重复片段、剔掉日常用语和人名。
 * 噪声一定会有（"真的假的"这种也会冒出来），所以它只负责**缩小范围**，
 * 最终是不是梗由模型判断——一次调用比本地猜十次都准。
 */
object SlangHarvest {
    internal val SEPARATORS =
        Regex("[\\s，。！？、；：,.!?;:\"'“”‘’（）()\\[\\]【】<>《》~\\-—_/\\\\|]+")

    /**
     * 汉语本身，不是梗。
     *
     * 这张表决定了候选的噪声量：漏一个，模型就多一条要否决的；
     * 但宁可漏也别误杀——把"绷不住"当停用词剔掉，才是真损失。
     */
    internal val STOP =
        setOf(
            "我们", "你们", "他们", "她们", "这个", "那个", "这些", "那些",
            "什么", "怎么", "可以", "知道", "觉得", "感觉", "就是", "不是",
            "没有", "现在", "时候", "应该", "可能", "真的", "好像", "已经",
            "还是", "但是", "所以", "因为", "如果", "然后", "而且", "或者",
            "一下", "一点", "这样", "那样", "自己", "别人", "大家", "今天",
            "明天", "昨天", "晚上", "早上", "中午", "下午", "东西", "问题",
            "事情", "地方", "朋友", "老师", "同学", "工作", "吃饭", "出来",
            "回来", "过来", "起来", "上去", "下去", "进来", "出去", "一样",
            "哈哈", "哈哈哈", "哈哈哈哈", "呵呵", "嘿嘿", "嗯嗯", "哦哦",
            "好的", "行吧", "是啊", "对啊", "确实", "有点", "非常", "其实",
            "主要", "反正", "随便", "没事", "加油", "厉害", "牛啊", "草",
        )

    /**
     * @param minHits 至少出现几次才算候选。3 是经验值：2 次可能只是重复一句，
     *   4 次又会漏掉刚火起来的新说法。
     */
    fun candidates(
        messages: List<RoomMessage>,
        names: Collection<String> = emptyList(),
        minHits: Int = 3,
        limit: Int = 20,
    ): List<String> {
        if (messages.isEmpty()) return emptyList()
        val counter = mutableMapOf<String, Int>()
        fun add(piece: String) {
            if (piece.length !in 2..8) return
            counter[piece] = (counter[piece] ?: 0) + 1
        }

        for (message in messages) {
            // 机器人自己的话不算"群里的说法"：它会放大自己的口癖
            if (message.role == "assistant") continue
            for (chunk in SEPARATORS.split(message.content)) {
                val piece = chunk.trim()
                if (piece.length < 2 || piece.length > 24) continue
                // 整块：群友常把梗单独发一句
                if (piece.length <= 8) add(piece)
                // 句中的梗：补 3~5 字滑窗。噪声一定多，
                // 但"绷不住了"经常嵌在"这波真的绷不住了"里，只统计整块会漏掉。
                // 短句也要做——"我绷不住了"本身只有 5 个字，不做滑窗就永远凑不够次数。
                if (piece.length >= 3) {
                    for (n in 3..minOf(5, piece.length)) {
                        // 窗口正好等于整块时跳过，否则同一个词会被计两次
                        if (n == piece.length) continue
                        for (i in 0..piece.length - n) add(piece.substring(i, i + n))
                    }
                }
            }
        }
        val blocked = names.filter { it.length >= 2 }
        return counter.entries
            .asSequence()
            .filter { it.value >= minHits }
            .filter { it.key !in STOP }
            .filter { entry -> blocked.none { name -> entry.key.contains(name) } }
            .filter { !it.key.contains("@") && !it.key.contains("<") }
            // 同样次数时长的优先：它更可能是完整的梗，而不是"不住"这种碎片
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { it.key.length }
                    .thenBy { it.key },
            )
            .take(limit)
            .map { it.key }
            .toList()
    }
}

/** 把梗库渲染成提示词里的一小段。 */
object SlangRender {
    /** 最多注入几条：三条够用，多了模型会为了用梗而用梗。 */
    const val MAX_INJECTED = 5

    fun block(terms: List<SlangTerm>): String? {
        val usable = terms.filter { it.enabled && it.meaning.isNotBlank() }.take(MAX_INJECTED)
        if (usable.isEmpty()) return null
        return buildString {
            append("群里最近常出现的说法（你听得懂就行，不必刻意用）：")
            usable.forEach { term ->
                append("\n- 「").append(term.term).append("」：").append(term.meaning)
                term.usage?.takeIf { it.isNotBlank() }?.let { append("（").append(it).append("）") }
            }
            append("\n用错场合比不用更糟；对方没在用的时候别硬塞，一条回复最多用一个。")
        }
    }
}

/**
 * 让模型判断"候选里哪些真的是梗"。
 *
 * 本地统计只能缩小范围，判断语义必须交给模型——它知道"绷不住了"是梗、
 * "今天天气"不是。代价是一次很小的调用，换来的是不入库一堆废话。
 */
class SlangJudge(private val client: LlmClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun filter(
        candidates: List<String>,
        samples: List<RoomMessage>,
        config: AiConfig,
    ): List<SlangTerm> {
        if (candidates.isEmpty()) return emptyList()
        val system =
            "你在帮一个 QQ 群机器人整理「群内黑话表」。\n" +
                "下面是从这个群最近聊天里统计出的高频说法，判断哪些是**梗 / 黑话 / 缩写 / 群内特有表达**，\n" +
                "哪些只是普通日常用语。\n" +
                "只输出 JSON 数组，不要解释、不要代码块。每项格式：\n" +
                "{\"term\":\"说法\",\"meaning\":\"15字内解释\",\"usage\":\"什么场合用，10字内\"}\n" +
                "规则：\n" +
                "1. 不是梗的不要输出；拿不准的不要输出；最多 10 条；\n" +
                "2. 不要输出人名、地名、公司名、单纯的数量词；\n" +
                "3. 不要输出脏话与攻击性词汇；\n" +
                "4. 如果一条都不像梗，输出 []。"
        val user =
            buildString {
                append("候选说法：\n")
                append(candidates.joinToString("、"))
                append("\n\n")
                if (samples.isNotEmpty()) {
                    append("最近的群聊（只看语境，不要照抄）：\n")
                    append(
                        samples.takeLast(20).joinToString("\n") { message ->
                            val who =
                                message.senderName?.takeIf { it.isNotBlank() }
                                    ?: if (message.role == "assistant") "机器人" else "群友"
                            "[$who] ${message.content.replace("\n", " ").take(60)}"
                        },
                    )
                }
            }
        val budget = GenerationLimits.forConfig(config)
        val reply =
            client.chat(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                temperature = 0.2,
                maxTokens = budget,
                thinkingType = ThinkingParams.forConfig(config, forceDisable = true),
            )
        return parse(reply.content)
    }

    private fun parse(raw: String): List<SlangTerm> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val body = raw.substring(start, end + 1)
        return runCatching { json.decodeFromString<List<RawSlang>>(body) }
            .getOrDefault(emptyList())
            .filter { it.term.isNotBlank() && it.meaning.isNotBlank() }
            .filter { it.term.trim().length in 2..12 }
            .distinctBy { it.term.trim() }
            .take(10)
            .map {
                SlangTerm(
                    term = it.term.trim(),
                    meaning = it.meaning.trim().take(40),
                    usage = it.usage?.trim()?.take(30)?.takeIf { usage -> usage.isNotBlank() },
                    source = SlangTerm.SOURCE_HARVEST,
                )
            }
    }

    @Serializable
    private data class RawSlang(
        val term: String = "",
        val meaning: String = "",
        val usage: String? = null,
    )
}
