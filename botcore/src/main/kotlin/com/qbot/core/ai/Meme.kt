package com.qbot.core.ai

/**
 * 一张表情包（图片）。
 *
 * 来源只有两种：群里捡的（HARVEST）和维护端手动贴链接（MANUAL）。
 * 刻意不接"联网现找图"——搜索接口返回的是网页而不是直图，
 * 把网页链接丢给上传接口只会失败；而"这个群自己的图"用起来最稳，
 * 群友刚发过，你回一张同款的，气氛才对。
 */
data class MemeImage(
    val id: Long = 0,
    val url: String,
    /** HARVEST / MANUAL */
    val source: String = SOURCE_HARVEST,
    /** 被采用次数，用于淘汰排序 */
    val hits: Int = 0,
    val createdAt: Long = 0,
) {
    companion object {
        const val SOURCE_HARVEST = "HARVEST"
        const val SOURCE_MANUAL = "MANUAL"
    }
}

/**
 * 表情包提示词块。
 *
 * 单独立块而不是塞进人设：图库会随群变化，而人设要保持前缀缓存稳定。
 */
object MemeRender {
    /** 注入提示词的候选图上限：再多就是在浪费上下文。 */
    const val MAX_PROMPT_MEMES = 8

    fun block(urls: List<String>): String? {
        val picked = urls.filter { it.isNotBlank() }.distinct().take(MAX_PROMPT_MEMES)
        if (picked.isEmpty()) return null
        return buildString {
            appendLine("你可以发表情包。想发的时候，在回复末尾单独写一行：[表情:图片地址]。")
            appendLine("地址只能从下面这些里选，原样照抄，不要自己编：")
            picked.forEach { appendLine("· $it") }
            appendLine("规矩：")
            appendLine("1. 图是配菜不是主食——大多数回复只用文字，气氛到了、或文字说不明白时才发；")
            appendLine("2. 一次最多带一张；写完标记就结束，不要解释你在发图（标记不会显示给对方）；")
            appendLine("3. 聊正经事、有人吵架、你不太想接话的时候，别发。")
        }.trim()
    }
}

/**
 * 回复里的「[表情:URL]」标记。
 *
 * 为什么不用结构化输出让模型填 JSON：群聊回复是自由文本，让模型在末尾
 * 挂一个约定标记，比让它整条输出 JSON 稳得多——JSON 格式一旦错了整条
 * 回复都废了，而标记认不出来时退化为纯文字，没有任何损失。
 */
object MemeMarker {
    private val PATTERN = Regex("""\[表情:(https?://[^\s\[\]]+)]""")

    /**
     * @return 剥离标记后的正文 + 要发的图地址；没有标记时地址为 null。
     * 认不出来的标记原样留在正文里——它看起来就像一句话，不会伤人。
     */
    fun parse(text: String): Pair<String, String?> {
        if (text.isBlank()) return text to null
        val match = PATTERN.find(text) ?: return text to null
        val url = match.groupValues[1].takeIf { it.isNotBlank() } ?: return text to null
        // 全部标记都剥掉：模型偶尔会写两个，只发第一张，剩下的不留痕迹
        val cleaned = PATTERN.replace(text, "").trim()
        return cleaned to url
    }
}

/**
 * 见到群友发表情包时收进来。
 *
 * 由 BotRuntime 在后台调用：上传、去重、淘汰都由实现方负责；
 * 为 null 时完全关闭，行为与接入前一致。
 */
fun interface MemeHarvester {
    suspend fun harvest(url: String)
}
