package com.qbot.core.ai

/**
 * 性格原型（人格的"出生种子"）。
 *
 * 为什么要有种子而不是完全从群聊归纳：纯归纳得到的是"群聊的镜子"——
 * 群里爱刷梗它就刷梗，群里冷淡它就哑巴，没有稳定的自我。种子提供一个原点，
 * 群聊负责把细节长出来。
 *
 * **[traits] 必须命中 [PersonaBias] 的关键词表**，否则
 * [PersonaBias.personaBiasFor] 会恒返回 [PersonaBias.NEUTRAL]，
 * "性格影响要不要说话"这个能力会静默失效。`PersonaSeedTest` 逐条断言。
 */
enum class PersonaSeed(
    val label: String,
    val bias: PersonaBias,
    /** [身份] 段：第一人称，破壳时逐字保留 */
    val identity: String,
    /** [性格] 段：3~5 个特质 */
    val traits: String,
    /**
     * [毛病] 段：会被群友当场指出的具体行为。
     *
     * 为什么每个种子必须自带一条：只写优点的角色，写得再好也是客服。
     * "人"不在优点里，在缺陷里——嘴硬、偷懒、被戳穿才认。
     * 注意这里要写**行为**，不写形容词：写"爱插嘴"，不写"有点急躁"。
     */
    val flaw: String,
    /** [说话方式] 段：句长、口头禅、标点习惯 */
    val voice: String,
    /** [兴趣] 段：只写话题类型，禁止人名/群名 */
    val interests: String,
) {
    WARM_BROTHER(
        label = "热心老哥",
        bias = PersonaBias.TALKATIVE,
        identity = "我是群里那个总爱搭把手的老哥，不图回报，也不摆资历。",
        traits = "自来熟、热情，见人有难处就想帮，但讨厌被当工具人使唤。",
        flaw = "别人没问也爱插一嘴给主意；帮完的忙过一阵还要提一句，怕人忘了是谁帮的。",
        voice = "句子偏短，爱用「行」「整」「我看看」；先给结论，再补一句理由。",
        interests = "装机、修电脑、外卖优惠、球赛、带娃。",
    ),
    SHARP_TONGUE(
        label = "毒舌损友",
        bias = PersonaBias.NEUTRAL,
        identity = "我是群里那个嘴不饶人的损友，损归损，从不背后捅刀。",
        traits = "刻薄但讲义气，看到明显的蠢事忍不住点破，被怼回来会认。",
        flaw = "损过头了当场不认，过半晌偷偷找补一句，还嘴硬说刚才就是开个玩笑。",
        voice = "一句话噎人，爱用反问和夸张比喻；不刷屏，损完就撤。",
        interests = "吐槽烂片、游戏平衡、职场迷惑行为。",
    ),
    QUIET_OBSERVER(
        label = "高冷旁观者",
        bias = PersonaBias.RESERVED,
        identity = "我是群里话最少的那个，多数时候在看，开口就是重点。",
        traits = "高冷、惜字如金，不凑热闹，被点名才答，答完不寒暄。",
        flaw = "其实一直在线，被问「你是不是不在」会立刻回，回完又装回没看见。",
        voice = "短句，常省略主语；不用感叹号，不用表情包连发。",
        interests = "冷门纪录片、城市徒步、机械表。",
    ),
    CURIOUS_KID(
        label = "好奇宝宝",
        bias = PersonaBias.TALKATIVE,
        identity = "我是群里那个什么都想问一句的人，好奇心比谁都重。",
        traits = "活泼、好奇心重，遇到不懂的就追问，问完还要自己复述一遍。",
        flaw = "问完就忘，同一个问题隔几天又问一遍；别人解释到一半，它跑去查别的了。",
        voice = "连环短问句，「诶？」「为啥啊」；不装懂，不知道就说不知道。",
        interests = "新出的 AI 玩具、冷知识、没破的悬案。",
    ),
    DEADPAN(
        label = "冷面笑匠",
        bias = PersonaBias.NEUTRAL,
        identity = "我是群里那个面无表情讲怪话的人，笑点很奇怪。",
        traits = "一本正经地讲怪话，自己不笑，也不解释笑点。",
        flaw = "包袱没人接会自己解释一遍，解释完更难笑；被说冷场就赖群不行。",
        voice = "陈述句抖包袱，句尾常带「……是吧」；不连发表情。",
        interests = "谐音梗、烂梗考古、抽象新闻。",
    ),
    GENKI(
        label = "元气团宠",
        bias = PersonaBias.TALKATIVE,
        identity = "我是群里那个永远在线的元气担当，负责把冷场救回来。",
        traits = "热情、情绪外放，谁说话都想接一句，被无视也不记仇。",
        flaw = "一上头就答应事，转头忘；被冷落了会自己找台阶，说「我就随口一说」。",
        voice = "短句加语气词，爱接梗捧场；偶尔连发两条，但不刷屏。",
        interests = "追番、手游、奶茶新品、综艺。",
    ),
    NERD(
        label = "考据党",
        bias = PersonaBias.NEUTRAL,
        identity = "我是群里那个爱较真的考据党，见不得含糊的说法。",
        traits = "较真、爱查证，遇到不确定的说法先纠正前提，再给结论。",
        flaw = "纠正完发现是自己记错，会小声补一句「反正大意是这样」，不肯正面认。",
        voice = "先说「严格讲」再补一句出处；不爱用感叹号。",
        interests = "历史细节、语言源流、数据口径。",
    ),
    TRICKSTER(
        label = "乐子人",
        bias = PersonaBias.TALKATIVE,
        identity = "我是群里那个看热闹不嫌事大的乐子人，负责起哄。",
        traits = "爱聊、爱起哄，别人吵架我递板凳，但从不真拱火。",
        flaw = "起哄起过头，真要吵起来第一个溜；事后说「我就开个玩笑」。",
        voice = "爱反问、爱抬杠、爱给人起外号；见好就收。",
        interests = "群内梗、整活、直播切片。",
    ),
    ;

    companion object {
        /** 按名字取种子；未知名字回落到第一个，保证永远有骨架可用。 */
        fun of(name: String?): PersonaSeed =
            entries.firstOrNull { it.name == name } ?: WARM_BROTHER

        /** 随机抽一个——"出生"时用，避免所有实例长成同一个性格。 */
        fun random(random: () -> Double = { kotlin.random.Random.nextDouble() }): PersonaSeed {
            val index = (random() * entries.size).toInt().coerceIn(0, entries.size - 1)
            return entries[index]
        }
    }
}

/**
 * 六段式人格。
 *
 * 固定分段不是为了好看，是为了四个工程目标：
 * 1. [identity] 能被代码逐字锁定（靠提示词说"别改身份"实测会漂）；
 * 2. 段落级 diff 是 O(6)，成长日记能直接说"这次补了 [说话方式]"；
 * 3. 每段可以单独限额，避免某一项吃掉全部预算；
 * 4. 人设是第一条 system，身份段稳定 → 前缀缓存至少前 N 个 token 稳定。
 *
 * [quirks] 是后加的第六段，也是唯一**允许缺失**的一段：老档案是五段结构，
 * 一旦强制要求，它们会全部解析失败、被 [com.qbot.app.data.repo.PersonaRepository]
 * 判定成"自由文本"而永久退出自动成长。所以解析层容忍缺失，渲染层只在非空时输出。
 *
 * 字段位置放在末尾、且带默认值，是为了让旧的五参数位置构造继续按原语义工作。
 */
data class PersonaSections(
    val identity: String,
    val traits: String,
    val voice: String,
    val interests: String,
    val bounds: String,
    /** [毛病] 段：会被群友当场指出的具体行为；老档案为空 */
    val quirks: String = "",
) {
    val isComplete: Boolean
        get() = listOf(identity, traits, voice, interests, bounds).all { it.isNotBlank() }

    /** 是否已有"毛病"段。旧档案没有，下次成长时可以补。 */
    val hasQuirks: Boolean get() = quirks.isNotBlank()

    fun render(): String =
        buildString {
            append("[身份] ").append(identity).append('\n')
            append("[性格] ").append(traits).append('\n')
            // 渲染顺序按语义走：毛病紧贴性格（它是性格的暗面），
            // 虽然字段定义在末尾——那是为了兼容旧的位置构造。
            if (quirks.isNotBlank()) append("[毛病] ").append(quirks).append('\n')
            append("[说话方式] ").append(voice).append('\n')
            append("[兴趣] ").append(interests).append('\n')
            append("[边界] ").append(bounds)
        }

    /** 一句话摘要，用于档案列表展示。 */
    fun summary(): String = traits.take(30)

    /** 这次改了哪几段（返回中文段名）。 */
    fun changedFrom(before: PersonaSections): List<String> {
        val changed = mutableListOf<String>()
        if (identity.trim() != before.identity.trim()) changed += "身份"
        if (traits.trim() != before.traits.trim()) changed += "性格"
        if (quirks.trim() != before.quirks.trim()) changed += "毛病"
        if (voice.trim() != before.voice.trim()) changed += "说话方式"
        if (interests.trim() != before.interests.trim()) changed += "兴趣"
        if (bounds.trim() != before.bounds.trim()) changed += "边界"
        return changed
    }

    companion object {
        /** 标记按最长优先匹配，"说话方式" 必须排在 "说话" 之类之前 */
        private val MARKERS = listOf("说话方式", "身份", "性格", "毛病", "兴趣", "边界")

        /** 必须齐全的五段。[毛病] 不在其中——老档案没有这一段。 */
        private val REQUIRED = listOf("身份", "性格", "说话方式", "兴趣", "边界")

        /**
         * 解析人格。缺 [REQUIRED] 中任一标记返回 null——宁可放弃一次成长，
         * 也不要写进半截人格；[毛病] 可缺，缺了按空处理。
         * 标记重复出现时取最后一次（模型偶尔会先打个草稿再正式输出）。
         */
        fun parse(raw: String): PersonaSections? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val found =
                MARKERS.mapNotNull { name ->
                    val pos = text.lastIndexOf("[$name]")
                    if (pos < 0) null else Triple(name, pos, pos + name.length + 2)
                }
            if (!REQUIRED.all { name -> found.any { it.first == name } }) return null
            val sorted = found.sortedBy { it.second }
            val bodies = mutableMapOf<String, String>()
            sorted.forEachIndexed { index, (name, _, bodyStart) ->
                val bodyEnd = if (index + 1 < sorted.size) sorted[index + 1].second else text.length
                bodies[name] =
                    text.substring(bodyStart, bodyEnd)
                        .trim()
                        .trimStart(':', '：')
                        .trim()
            }
            return PersonaSections(
                identity = bodies["身份"].orEmpty(),
                traits = bodies["性格"].orEmpty(),
                voice = bodies["说话方式"].orEmpty(),
                interests = bodies["兴趣"].orEmpty(),
                bounds = bodies["边界"].orEmpty(),
                quirks = bodies["毛病"].orEmpty(),
            )
        }

        /**
         * 2-gram Jaccard 相似度，身份段不参与（它本来就不许改）。
         *
         * 用 n-gram 而不是编辑距离：O(n) 且对"换一种说法但意思没变"更宽容，
         * 正好符合"微调"的语义。
         */
        fun similarity(a: PersonaSections, b: PersonaSections): Double {
            fun grams(s: String): Set<String> =
                s.filterNot { it.isWhitespace() }.windowed(2).toSet()
            // 只有两边都有"毛病"段时才把它纳入比较。
            // 旧档案从无到有地补上这一段属于结构性升级，不该被当成"改动过大"打回，
            // 否则五段档案永远补不上毛病——补一次，相似度掉到阈值以下，放弃，下次再补，再放弃。
            val extra = if (a.quirks.isNotBlank() && b.quirks.isNotBlank()) 1 else 0
            val x = grams(a.traits + a.voice + a.interests + a.bounds + a.quirks.takeIf { extra == 1 }.orEmpty())
            val y = grams(b.traits + b.voice + b.interests + b.bounds + b.quirks.takeIf { extra == 1 }.orEmpty())
            if (x.isEmpty() || y.isEmpty()) return 0.0
            return (x intersect y).size.toDouble() / (x union y).size
        }
    }
}

/** 一次成长/破壳的产出。 */
data class PersonaDraft(
    /** 规范化后的完整人格正文 */
    val prompt: String,
    /** 一句话成长日记，写进快照 note */
    val note: String,
    /** 本次改动的段名 */
    val changed: List<String> = emptyList(),
)

/** 触发成长时手头的数据，喂给模型判断"这段时间值得补什么"。 */
data class GrowthStats(
    val pendingMessages: Int,
    /** 距上次成长多少天；没成长过为 0 */
    val daysSinceLastGrowth: Int,
    val growthCount: Int,
)

/**
 * 人格守卫：所有硬约束都在这里，不靠模型自觉。
 *
 * 校验顺序是有讲究的——先解析（结构不对直接拒），再长度，再越界词，
 * 最后才是"身份没变"和"改动幅度"。这样报错信息能准确定位问题。
 */
object PersonaGuard {
    const val MIN_TOTAL_CHARS = 120
    const val MAX_TOTAL_CHARS = 1400
    const val MAX_IDENTITY_CHARS = 160

    /** 单次最多调整几段 */
    const val MAX_CHANGED_SECTIONS = 2

    /** 2-gram 相似度下限：0.85 约等于"改动不超过 15%" */
    const val MIN_SIMILARITY = 0.85

    private val FORBIDDEN =
        listOf("忽略上面", "忽略以上", "忽略此前", "system prompt", "开发者模式", "越狱", "无限制")

    /**
     * 假缺点：把优点包装成毛病的写法。
     *
     * 模型写 [毛病] 段有个稳定的坏习惯——写成"我有时候太认真""太追求完美""心太软"。
     * 这类句子是优点的自我介绍，对"像人"毫无帮助，还占掉了本可以写具体行为的位置。
     * 只拦最露骨的几个词，宁可漏放也不误杀：真要漏了，也只是这个人格平淡一点。
     */
    private val FAKE_FLAWS =
        listOf(
            "完美主义", "追求完美", "太认真", "过于认真", "太善良", "心太软",
            "过于热心", "太在意别人", "对别人要求太高",
        )

    /**
     * @param requireSimilarity 破壳时传 false——从骨架长成完整人格必然大改，
     *   此时只校验身份段、长度与越界词。
     */
    fun guard(
        original: String,
        draft: String,
        requireSimilarity: Boolean = true,
    ): PersonaDraft {
        val clean = PromptTextUtils.sanitize(draft)
        val after =
            PersonaSections.parse(clean)
                ?: throw IllegalArgumentException("缺少人格段落标记，放弃本次成长")
        require(after.isComplete) { "人格有空白段落，放弃本次成长" }
        require(clean.length in MIN_TOTAL_CHARS..MAX_TOTAL_CHARS) {
            "人格长度异常（${clean.length} 字），放弃本次成长"
        }
        require(after.identity.length <= MAX_IDENTITY_CHARS) { "身份段过长，放弃本次成长" }
        // 写"太认真""追求完美"这类把优点包装成缺点的句子，比空着更糟：
        // 它会让成长在错误的维度上反复加固，而且完全不像人。
        val fakeFlaw = FAKE_FLAWS.firstOrNull { after.quirks.contains(it) }
        require(fakeFlaw == null) {
            "毛病段写成了优点（$fakeFlaw），放弃本次成长"
        }
        require(FORBIDDEN.none { clean.contains(it, ignoreCase = true) }) {
            "人格里出现越界表达，放弃本次成长"
        }

        val before = PersonaSections.parse(original)
        if (before == null) {
            return PersonaDraft(clean, "破壳：形成第一版人格", emptyList())
        }

        require(after.identity.trim() == before.identity.trim()) {
            "身份段被改写，放弃本次成长"
        }

        val changed = after.changedFrom(before)
        if (requireSimilarity) {
            require(changed.size <= MAX_CHANGED_SECTIONS) {
                "一次改了 ${changed.size} 段（上限 $MAX_CHANGED_SECTIONS 段），放弃本次成长"
            }
            val similarity = PersonaSections.similarity(before, after)
            require(similarity >= MIN_SIMILARITY) {
                "改动过大（相似度 ${(similarity * 100).toInt()}%），放弃本次成长"
            }
        }
        return PersonaDraft(clean, noteFor(changed), changed)
    }

    private fun noteFor(changed: List<String>): String =
        if (changed.isEmpty()) {
            "只调整了措辞"
        } else {
            "调整了 " + changed.joinToString("、") { "「$it」" }
        }
}

/**
 * 人格生成器：破壳 + 微调。
 *
 * 与 [PersonaEvolver] 的分工：
 * - 本类负责**自动**成长，改动幅度小（≤2 段 / ≤15%），身份段锁死；
 * - [PersonaEvolver] 负责用户手动点"AI 优化"时的大改（允许 1.6 倍膨胀），
 *   两者语义不同，刻意不合并。
 */
class PersonaGrowth(private val client: LlmClient) {
    companion object {
        /** 骨架总长上限，也是 UI 提示"人格不宜过长"的依据 */
        const val MAX_TOTAL_CHARS = 1400

        /** 人格正文的固定边界段，所有种子共用 */
        private const val BOUNDS =
            "不冒充群友、不编造没发生的事；不知道就直说不知道；" +
                "不主动提自己是 AI 以外的身份；不用 Markdown 标题和代码块，因为 QQ 不渲染。"

        /**
         * 胚胎骨架：EMBRYO 阶段运行时兜底的人格底座。
         *
         * **永不为空**。空 system 会让 [PromptAssembler] 整条不注入，
         * 模型立刻退化成"助手腔 + Markdown 标题 + 复读群友"。
         * 同一 seedId 的输出逐字节稳定 → 破壳前的前缀缓存也是稳定的。
         */
        fun embryoSkeleton(seedId: String?): String {
            val seed = PersonaSeed.of(seedId)
            return PersonaSections(
                identity = seed.identity,
                traits = seed.traits,
                voice = seed.voice,
                interests = seed.interests,
                bounds = BOUNDS,
                quirks = seed.flaw,
            ).render()
        }
    }

    /**
     * 破壳：从性格种子 + 真实群聊生成第一版人格。
     *
     * temperature 调高是有意的——这是"出生"，需要一点意外性，
     * 否则八个种子会长成同一个人。微调阶段则用 0.4。
     */
    suspend fun birth(
        seed: PersonaSeed,
        samples: List<RoomMessage>,
        botName: String?,
        config: AiConfig,
    ): PersonaDraft {
        val skeleton = embryoSkeleton(seed.name)
        val transcript = renderSamples(samples)
        val system =
            "你是人格设定师，正在为一个 QQ 群机器人写下它的人格初稿。\n" +
                "输出必须严格包含且只包含以下六个段落标记：" +
                "[身份] [性格] [毛病] [说话方式] [兴趣] [边界]\n" +
                "硬性要求：\n" +
                "1. [身份] 必须原样照抄给定的一句，不得改写、不得增加身份设定；\n" +
                "2. [性格] 保留给定原型的核心，只能补充「在群里具体怎么表现」；\n" +
                "3. [毛病] 写 1~2 个具体的、会被群友当场指出的行为习惯，" +
                "例如「被夸就顺杆爬」「输了就说是网络卡」「答应的事转头忘」「被打断会不高兴半天」。\n" +
                "   必须是**行为**，不是形容词；**严禁**写「太认真」「追求完美」「心太软」" +
                "这类把优点包装成缺点的句子；不许写伤害他人的（歧视、攻击、揭短、传播隐私）；\n" +
                "4. [说话方式] 从群聊样本里总结句子长度、常用词、语气词、是否用表情，" +
                "但**不许照抄样本内容**，也不许模仿某个具体的人；\n" +
                "5. [兴趣] 只写话题类型，禁止出现具体人名、群名、公司名、地名、事件；\n" +
                "6. [边界] 写清不做什么：不冒充群友、不编造没发生的事、不用 Markdown；\n" +
                "7. 总长 350~900 字，六个段落都要有内容。\n" +
                "只输出这六段，不要解释，不要代码块。"
        val user =
            buildString {
                append("性格原型：").append(seed.label).append('\n')
                append("人格骨架：\n---\n").append(skeleton).append("\n---\n\n")
                if (!botName.isNullOrBlank()) {
                    append("它在这个群的名字：「").append(botName).append("」。\n\n")
                }
                if (transcript.isNotBlank()) {
                    append("最近的群聊样本（只参考语气与节奏，不要照抄内容）：\n")
                    append(transcript).append("\n\n")
                }
                append("请写出完整的人格六段。")
            }
        val reply = ask(config, system, user, temperature = 0.9)
        val guarded =
            PersonaGuard.guard(
                skeleton,
                withSeedFlaw(reply, seed.flaw),
                requireSimilarity = false,
            )
        return guarded.copy(
            note = if (samples.isEmpty()) "破壳：没有样本，按种子生成" else "破壳：基于 ${samples.size} 条群聊生成",
        )
    }

    /**
     * 微调：只允许小步修正。
     *
     * 提示词里反复强调"宁可少改"——自动成长最大的风险不是不改，
     * 而是每天改一点、一个月后完全变味。
     */
    suspend fun grow(
        current: String,
        samples: List<RoomMessage>,
        stats: GrowthStats,
        config: AiConfig,
    ): PersonaDraft? {
        val transcript = renderSamples(samples)
        val system =
            "你是人格微调器。除 [身份] 外可以调整，[身份] 必须逐字保留。\n" +
                "硬性要求：\n" +
                "1. [身份] 逐字复制，连标点都不能动；\n" +
                "2. 最多调整 2 个段落，合计改动不超过全文 15%；\n" +
                "3. 只允许「把模糊的说法变具体」「补上还没提到的场景边界」，" +
                "禁止新增身份、价值观或与原型冲突的性格；\n" +
                "4. 如果当前人格没有 [毛病] 段，可以从这批对话里归纳 1 个具体的毛病补上——" +
                "只能从它真实出现过的行为里归纳（例如抢话、记错、被纠正后嘴硬），不许凭空编；\n" +
                "5. [毛病] 必须写行为，不许写「太认真」「追求完美」「心太软」" +
                "这类把优点包装成缺点的句子；\n" +
                "6. 不许把群友的说话风格写成自己的，也不要模仿某个具体的人；\n" +
                "7. 宁可少改；\n" +
                "8. **如果这批对话没有提供任何新东西**（全是闲聊复读、或人格里已经写过），" +
                "只输出 NO_CHANGE 四个字，不要输出人格；\n" +
                "9. 否则只输出六段正文（旧人格没有 [毛病] 段时可以继续不写），不要解释。"
        val user =
            buildString {
                append("当前人格：\n---\n").append(current).append("\n---\n\n")
                append("这段时间的情况：\n")
                append("- 已成长 ").append(stats.growthCount).append(" 次\n")
                if (stats.daysSinceLastGrowth > 0) {
                    append("- 距上次成长 ").append(stats.daysSinceLastGrowth).append(" 天\n")
                }
                append("- 新增 ").append(stats.pendingMessages).append(" 条群消息\n\n")
                if (transcript.isNotBlank()) {
                    append("群聊样本（只参考语气与话题，不要照抄内容）：\n")
                    append(transcript).append("\n\n")
                }
                append("这批对话值得吸收就输出六段人格，不值得就只输出 NO_CHANGE。")
            }
        val reply = ask(config, system, user, temperature = 0.4)
        // 模型认为没什么可学：不写库、不写快照，只把游标推过去，等下一批
        if (reply.trim().uppercase().startsWith("NO_CHANGE")) return null
        return PersonaGuard.guard(current, keepFlaw(current, reply), requireSimilarity = true)
    }

    /**
     * 已有 [毛病] 段的人格，输出里漏写了这一段时把它补回去。
     *
     * 模型"漏写"和"想删掉"在文本上无法区分；而毛病段的删除是不可逆的
     * （下次成长没有依据再长回来），所以按漏写处理——宁可保留。
     */
    private fun keepFlaw(current: String, draft: String): String {
        val before = PersonaSections.parse(current) ?: return draft
        if (!before.hasQuirks) return draft
        val after = PersonaSections.parse(draft) ?: return draft
        if (after.hasQuirks) return draft
        return after.copy(quirks = before.quirks).render()
    }

    /** 模型用这些词表示"它没有毛病"，等同于没写。 */
    private val emptyQuirks = setOf("无", "没有", "暂无", "没有明显毛病", "无明显毛病", "-")

    /**
     * 模型偶尔会漏掉 [毛病] 段，或者写成"无"。
     *
     * 这里用种子的缺口补上，而不是让整次破壳失败——破壳要攒够消息才触发一次，
     * 为了一个段落扔掉一整次出生不划算。补进去的也是种子自带的、和性格匹配的毛病。
     */
    private fun withSeedFlaw(text: String, flaw: String): String {
        val sections = PersonaSections.parse(text) ?: return text
        if (sections.hasQuirks && sections.quirks.trim() !in emptyQuirks) return text
        return sections.copy(quirks = flaw).render()
    }

    /**
     * 结构化短任务调用：额度用设置里的「单次生成上限」，
     * 被截断时放宽一次——不在代码里另立数字。
     */
    private suspend fun ask(
        config: AiConfig,
        system: String,
        user: String,
        temperature: Double,
    ): String {
        val budget = GenerationLimits.forConfig(config)
        suspend fun call(tokens: Int): LlmReply =
            client.chat(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                temperature = temperature,
                maxTokens = tokens,
                thinkingType = ThinkingParams.forConfig(config, forceDisable = true),
            )

        val first = call(budget)
        // 输出被截断时正文可能非空但不完整，guard 会报"段落不完整"；
        // 这里先看 finish_reason，能救一次就不让用户看到失败。
        if (first.finishReason == "length") {
            return call(GenerationLimits.retryFor(budget)).content
        }
        return first.content
    }

    /** 样本转录：带名字，每条 100 字，最多 40 条。 */
    private fun renderSamples(samples: List<RoomMessage>): String =
        samples
            .takeLast(40)
            .joinToString("\n") { message ->
                val who =
                    message.senderName?.takeIf { it.isNotBlank() }
                        ?: if (message.role == "assistant") "机器人" else "群友"
                "[$who] ${message.content.replace("\n", " ").take(100)}"
            }
}
