package com.qbot.core.ai

/** 一条待入库的事实草稿。 */
data class FactDraft(
    val text: String,
    val kind: FactKind,
)

/**
 * 从消息文本里提取「值得记住的事」。
 *
 * 设计原则是**宁缺毋滥**：记忆系统的误报比漏报更伤体验
 * （把「你是管理员」记成别人名字会很违和），因此全部采用保守规则：
 *
 * - 自称名只在短消息（≤24 字）里识别，且带停用词表；
 * - 事实识别拒绝问句（以「吗/呢/？」结尾的一律跳过）；
 * - 捕获内容截断到标点、限长、含代词或疑问词的一律丢弃；
 * - 单条消息最多产出 2 条事实。
 *
 * 全部为纯函数，可脱离 Android 与网络直接测试。
 * 更高级的「AI 反思总结」留给将来扩展（FactSource.REFLECTED 已预留）。
 */
class MemoryExtractor {
    companion object {
        /** 自称名识别的最大消息长度 */
        private const val NAME_MAX_TEXT = 24

        /** 事实识别的最大消息长度 */
        private const val FACT_MAX_TEXT = 80

        /** 捕获内容最大长度 */
        private const val CAPTURE_MAX = 40

        /** 单条消息最多产出几条事实 */
        private const val MAX_FACTS_PER_MESSAGE = 2

        /**
         * 自称名停用词。
         *
         * 「我是学生/管理员」里的身份词不是名字；称呼代词更不是。
         * 角色词同时出现在事实模式白名单里，两边不会互抢。
         */
        private val NAME_STOPWORDS =
            setOf(
                "我", "你", "他", "她", "它", "我们", "你们", "他们", "谁", "人", "人类",
                "大家", "所有人", "管理员", "群主", "机器人", "bot", "新手", "大佬", "萌新", "小白",
                "学生", "老师", "程序员", "工程师", "设计师", "产品经理", "运营", "研究生",
                "博士", "硕士", "本科生", "摄影师", "画手", "主播", "厨师", "医生", "律师",
                "会计", "销售", "客服", "测试", "前端", "后端", "全栈", "策划", "作者",
                "作家", "编辑", "翻译", "自由职业者", "打工人", "上班族",
            )

        /** 捕获内容里的疑问词/代词：整段命中或以其开头即视为误匹配 */
        private val CAPTURE_STOPWORDS =
            setOf(
                "什么", "啥", "哪", "哪里", "哪个", "谁", "怎么", "怎样", "如何", "吗", "吧", "呢",
                "我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "咱", "咱们",
                "自己", "大家",
            )

        /** 事实模式里的角色白名单（「我是X」句式） */
        private val ROLE_WORDS =
            setOf(
                "学生", "老师", "程序员", "工程师", "设计师", "产品经理", "运营", "研究生",
                "博士", "硕士", "本科生", "摄影师", "画手", "主播", "厨师", "医生", "律师",
                "会计", "销售", "客服", "测试", "前端", "后端", "全栈", "策划", "作者",
                "作家", "编辑", "翻译", "自由职业者", "打工人", "上班族", "新手", "萌新",
            )

        /** 结尾语气词，自称名里直接剥掉 */
        private const val PARTICLES = "呀啊哦吧呢嘛啦哟欸"

        /** 截断用的标点集合 */
        private const val CUT_CHARS = "，。！？、,.!?~～\\s:：;；|/"

        private val NAME_PATTERNS =
            listOf(
                Regex("我的昵称是(.{1,15})"),
                Regex("我的名字是(.{1,15})"),
                Regex("昵称(?:是|叫)?(.{1,15})"),
                Regex("叫我(.{1,15})"),
                Regex("我是(.{1,15})"),
            )

        private data class FactPattern(
            val regex: Regex,
            /** 用净化后的捕获组（不含整段匹配）渲染事实正文 */
            val render: (List<String>) -> String,
        )

        /** 负面模式排在正面模式之前，避免「挺不喜欢X」被误判。 */
        private val FACT_PATTERNS =
            listOf(
                FactPattern(Regex("我(?:不太|不怎么|不)喜欢(.+)")) { "不喜欢${it[0]}" } to FactKind.PREFERENCE,
                FactPattern(Regex("我讨厌(.+)")) { "讨厌${it[0]}" } to FactKind.PREFERENCE,
                FactPattern(Regex("我(?:很|非常|超|特别|挺|蛮)?喜爱(.+)")) { "喜欢${it[0]}" } to FactKind.PREFERENCE,
                FactPattern(Regex("我(?:很|非常|超|特别|挺|蛮)?喜欢(.+)")) { "喜欢${it[0]}" } to FactKind.PREFERENCE,
                FactPattern(Regex("我(?:想|想要|想学|想买|打算)(.+)")) { "想${it[0]}" } to FactKind.PREFERENCE,
                FactPattern(
                    Regex("我(?:在|正在)(备考|学习|学|准备|加班|出差|旅游|旅行|实习|找工作|减肥|健身|创业)(.*)"),
                ) { "在${it[0]}${it.getOrElse(1) { "" }}" } to FactKind.PROFILE,
                FactPattern(Regex("我(?:养了|家里养了)(.+)")) { "养了${it[0]}" } to FactKind.PROFILE,
                FactPattern(Regex("我(住在|家在|生在)(.+)")) { "${it[0]}${it[1]}" } to FactKind.PROFILE,
                FactPattern(Regex("我的(生日|年龄|电话|邮箱|微信|家乡|职业|工作)是(.+)")) { "${it[0]}是${it[1]}" } to FactKind.PROFILE,
            )
    }

    /**
     * 提取自称名，如「叫我小明」→「小明」。
     *
     * @param cleanText 已剥离 @ 前缀的消息文本
     * @return 展示名；没有自称返回 null
     */
    fun extractName(cleanText: String): String? {
        val text = cleanText.trim()
        if (text.isEmpty() || text.length > NAME_MAX_TEXT) return null

        for (pattern in NAME_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val name = sanitizeName(match.groupValues[1])
            if (name != null) return name
        }
        return null
    }

    /**
     * 提取值得记住的事实（偏好 / 状态 / 资料）。
     *
     * @return 事实草稿列表，最多 [MAX_FACTS_PER_MESSAGE] 条
     */
    fun extractFacts(cleanText: String): List<FactDraft> {
        val text = cleanText.trim()
        if (text.isEmpty() || text.length > FACT_MAX_TEXT) return emptyList()

        // 问句不是陈述：「你喜欢猫吗」是在提问，不是在声明偏好
        if (text.endsWith("吗") || text.endsWith("呢") || text.endsWith("?") || text.endsWith("？")) {
            return emptyList()
        }

        val drafts = mutableListOf<FactDraft>()
        val seen = mutableSetOf<String>()

        for ((pattern, kind) in FACT_PATTERNS) {
            if (drafts.size >= MAX_FACTS_PER_MESSAGE) break
            val match = pattern.regex.find(text) ?: continue
            val captures = match.groupValues.drop(1).map { sanitizeCapture(it).orEmpty() }
            // 至少要有一个非空捕获，否则这个模式没有实际匹配到内容
            if (captures.all { it.isEmpty() }) continue
            val factText = pattern.render(captures).trim().take(CAPTURE_MAX + 12)
            if (factText.isBlank() || factText in seen) continue
            seen += factText
            drafts += FactDraft(text = factText, kind = kind)
        }

        // 「我是程序员」这类角色陈述：与自称名共用「我是」句式。
        // 自称名已先行拦截名字场景，到这里剩下的身份词才当事实。
        if (drafts.isEmpty()) {
            val role = Regex("我是(?:一名|一个)?([\\u4e00-\\u9fa5]{2,6})").find(text)
            val candidate = role?.groupValues?.get(1)
            if (candidate != null && candidate in ROLE_WORDS) {
                drafts += FactDraft(text = "是$candidate", kind = FactKind.PROFILE)
            }
        }

        return drafts
    }

    /** 净化自称名：截到标点、剥语气词、校验字符集与停用词。 */
    private fun sanitizeName(raw: String): String? {
        var s = raw.trim()
        s = s.split(Regex("[$CUT_CHARS]")).firstOrNull().orEmpty()
        s = s.trimEnd { it in PARTICLES }
        if (s.length !in 2..12) return null
        if (!s.matches(Regex("[\\u4e00-\\u9fa5A-Za-z0-9_\\-]+"))) return null
        if (s in NAME_STOPWORDS) return null
        return s
    }

    /** 净化捕获内容：截断、限长、丢弃含疑问词/代词的误匹配。 */
    private fun sanitizeCapture(raw: String): String? {
        var s = raw.trim()
        s = s.split(Regex("[$CUT_CHARS]")).firstOrNull().orEmpty()
        s = s.trimEnd { it in PARTICLES }
        if (s.isEmpty() || s.length > CAPTURE_MAX) return null
        // 以疑问词/代词开头（「你怎么样」「我们」）或整段就是代词 → 误匹配。
        // 注意不能按「包含」判断：「吉他」「其他」里的他/她是正常汉字。
        if (CAPTURE_STOPWORDS.any { s == it || s.startsWith(it) }) return null
        return s
    }
}
