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
