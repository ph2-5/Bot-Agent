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
     * 群名片是「过去的阿远」，但角色卡里写着"你是阿远"——群友喊的是角色名，
     * 只认群名片就会漏判。这里按确定性规则抽出来，不调模型：
     * 只认「你叫X / 你的名字是X / 名字：X / 你是X」且 X 后面必须跟标点或结束，
     * 抽不到的宁可不认（"你叫什么"、"你是一个助手"这类都不会命中）。
     */
    private val SELF_NAME_PATTERNS =
        listOf(
            Regex("""(?:你自己|你)(?:就)?叫(?:做|作)?\s*[「『“"']?([^\s，。！？、；：）」』”"'（(]{2,12})(?=[，。！？、；：\s）」』”"'）)]|$)"""),
            Regex("""你的名字(?:是|叫)\s*[「『“"']?([^\s，。！？、；：）」』”"'（(]{2,12})(?=[，。！？、；：\s）」』”"'）)]|$)"""),
            Regex("""(?:名字|昵称)[:：]\s*[「『“"']?([^\s，。！？、；：）」』”"'（(]{2,12})(?=[，。！？、；：\s）」』”"'）)]|$)"""),
            // 只认带引号的「你是"阿远"」：不带引号的"你是一个群友"不是名字
            Regex("""你(?:是|就是)\s*[「『“"']([^」』”"']{2,12})[」』”"']"""),
            // 角色卡常见写法：「你是阿远，是群里的老油条」——名字后面必须跟标点
            Regex("""你(?:是|就是)\s*([^\s，。！？、；：—－-]{2,12})(?=\s*[，。！？；：—－-]|$)"""),
        )

    /** 不像名字的捕获：问句、人称代词、泛指 */
    private val NOT_A_NAME =
        setOf(
            "什么", "啥", "我", "你", "他", "她", "它", "咱们", "我们",
            "自己", "名字", "昵称", "机器人", "助手", "AI", "ai", "谁",
        )

    /**
     * 明确的「划界 / 拒斥」句式。
     *
     * 名字出现 ≠ 在叫它：群里最常见的反例是「我和陈泽说话关你阿远什么事」——
     * 名字被夹在中间，`contains` 会判成"叫了名字"从而强制接话，
     * 结果机器人硬插进别人的对话，还回一句「骂人就关我事」。
     *
     * 这里只认确定的表达（宁可漏判交给 AI 判断，也不误判成"被叫"）。
     */
    private val REFUSAL_PATTERNS =
        listOf(
            Regex("""关(?:你|您).{0,10}?(?:什么事|啥事|屁事|鸟事)"""),
            Regex("""不关(?:你|您).{0,10}?事"""),
            Regex("""(?:没|不是)(?:在)?(?:跟|和|与)(?:你|您)(?:说话|聊天|说|聊)"""),
            Regex("""(?:又)?没(?:有)?(?:问你|叫你|喊你|跟你说话)"""),
            Regex("""不是(?:在)?问(?:你|您)"""),
            Regex("""(?:别|少|不要)(?:插嘴|接话|打岔|多嘴|搭话)"""),
            Regex("""没(?:你|您)的?(?:事|份)"""),
            Regex("""别自作多情"""),
            Regex("""(?:一边|上边|旁边)去"""),
        )

    /**
     * 名字处于否定/澄清语境：「我不是阿远」「别喊阿远」「阿远没来」。
     * `{{name}}` 为占位符——必须存字符串模板而不是 Regex，
     * 否则类初始化时 `Regex` 会把 `{{` 当成非法量词直接抛异常。
     */
    private val NEGATED_NAME_PATTERNS =
        listOf(
            """不是\s*[「『“"']?{{name}}""",
            """(?:别|不要|没|没再|没人)(?:叫|喊)\s*[「『“"']?{{name}}""",
            """{{name}}.{0,4}?(?:没来|不在|没有来|不在这|走了|没在)""",
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
        if (listOf("我", "你", "他", "她", "它", "咱").any { startsWith(it) }) return false
        // "你是普通人 / 一个 AI 助手 / 群里的老大哥" 都不是名字
        if (listOf("一个", "一位", "一名", "个", "名", "种").any { startsWith(it) }) return false
        if (contains("群")) return false
        return listOf("助手", "机器人", "人类", "角色", "家伙", "兄弟", "朋友").none { endsWith(it) }
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

    /** 这句话是不是在把机器人推开（「关你什么事」「没跟你说话」）。 */
    fun isRefusal(text: String): Boolean = REFUSAL_PATTERNS.any { it.containsMatchIn(text) }

    /** 名字是否被否定或澄清（「我不是阿远」「别喊阿远」「阿远没来」）。 */
    fun isNegatedName(
        text: String,
        names: Collection<String>,
    ): Boolean =
        nameTokens(names).any { name ->
            NEGATED_NAME_PATTERNS.any { template ->
                template.replace("{{name}}", Regex.escape(name)).toRegex().containsMatchIn(text)
            }
        }

    /**
     * 这句话是不是在**称呼**机器人，而不只是提到它的名字。
     *
     * 「叫名字必答」用的就是它：字面出现还不够，还要排除
     * 否定（「我不是阿远」）与划界（「关你阿远什么事」）这两种语境，
     * 否则机器人会因为别人否认身份而抢话。
     */
    fun called(
        text: String,
        names: Collection<String>,
    ): Boolean {
        if (!mentioned(text, names)) return false
        if (isRefusal(text)) return false
        if (isNegatedName(text, names)) return false
        return true
    }

    /** 名字的完整形式与后缀形式（与 [mentioned] 的匹配集合保持一致）。 */
    private fun nameTokens(names: Collection<String>): List<String> =
        names
            .filter { it.isNotBlank() }
            .flatMap { name ->
                buildList {
                    add(name)
                    for (len in name.length - 1 downTo MIN_SUFFIX) add(name.takeLast(len))
                }
            }.distinct()
}
