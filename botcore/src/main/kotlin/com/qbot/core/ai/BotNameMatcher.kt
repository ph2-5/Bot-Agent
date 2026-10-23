package com.qbot.core.ai

/**
 * 机器人名字匹配。
 *
 * 之前「叫名字必答」只做全名 `contains`，群里喊短昵称（设定「过去的阿远」、
 * 群友只喊「阿远」）就永远匹配不上——这是"直接喊昵称不回"的主要根因之一。
 *
 * 匹配规则：
 * 1. 文本包含完整名字，或
 * 2. 文本包含名字的**后缀**（最短 2 个字）——中文称呼习惯叫后两个字；
 *    单字后缀容易误伤普通词（"远远不够"），刻意不启用。
 *
 * 同一套规则供两处使用：插话策略的「叫名字必答」与冒犯检测的「指名」。
 */
object BotNameMatcher {
    /** 名字后缀最短长度 */
    const val MIN_SUFFIX = 2

    /**
     * 人设正文里的自称。
     *
     * 群名片是「过去的阿远」，但角色卡里写着"你叫阿远"——群友喊的是角色名，
     * 只认群名片就会漏判。这里按确定性规则抽出来，不调模型：
     * 只认「你叫X / 你的名字是X / 名字：X」且 X 后面必须跟标点或结束，
     * 抽不到的宁可不认（"你叫什么"这类问句不会命中）。
     */
    private val SELF_NAME_PATTERNS =
        listOf(
            Regex("""(?:你自己|你)(?:就)?叫(?:做|作)?\s*[「『“"']?([^\s，。！？、；：）」』”"'（(]{2,12})(?=[，。！？、；：\s）」』”"'）)]|$)"""),
            Regex("""你的名字(?:是|叫)\s*[「『“"']?([^\s，。！？、；：）」』”"'（(]{2,12})(?=[，。！？、；：\s）」』”"'）)]|$)"""),
            Regex("""(?:名字|昵称)[:：]\s*[「『“"']?([^\s，。！？、；：）」』”"'（(]{2,12})(?=[，。！？、；：\s）」』”"'）)]|$)"""),
            // 只认带引号的「你是"阿远"」：不带引号的"你是一个群友"不是名字
            Regex("""你(?:是|就是)\s*[「『“"']([^」』”"']{2,12})[」』”"']"""),
        )

    /** 不像名字的捕获：问句、人称代词、泛指 */
    private val NOT_A_NAME =
        setOf(
            "什么", "啥", "我", "你", "他", "她", "它", "咱们", "我们",
            "自己", "名字", "昵称", "机器人", "助手", "AI", "ai", "谁",
        )

    /** 从人设正文里抽取自称的名字；抽不到就返回空列表。 */
    fun namesFromPrompt(prompt: String): List<String> {
        if (prompt.isBlank()) return emptyList()
        return SELF_NAME_PATTERNS
            .flatMap { pattern -> pattern.findAll(prompt).map { it.groupValues[1] }.toList() }
            .map { it.trim() }
            .filter { it.length >= MIN_SUFFIX && it.isLikelyName() }
            .distinct()
    }

    /** 问句、人称开头、代词都不是名字——宁可漏抽也不给错别名。 */
    private fun String.isLikelyName(): Boolean {
        if (this in NOT_A_NAME) return false
        if (listOf("什么", "啥", "谁", "怎么", "如何").any { contains(it) }) return false
        return listOf("我", "你", "他", "她", "它", "咱").none { startsWith(it) }
    }

    /** 文本里是否提到了这些名字之一（含后缀匹配）。 */
    fun mentioned(
        text: String,
        names: Collection<String>,
    ): Boolean =
        names.filter { it.isNotBlank() }.any { name ->
            if (text.contains(name)) {
                true
            } else {
                (name.length downTo MIN_SUFFIX).any { len ->
                    len < name.length && text.contains(name.takeLast(len))
                }
            }
        }
}
