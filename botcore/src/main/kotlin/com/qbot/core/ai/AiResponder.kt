package com.qbot.core.ai

import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.cleanText
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** AI 触发策略。 */
enum class AiMode {
    /** 先匹配本地规则，未命中才交给 AI（默认） */
    RULE_FIRST,

    /** 所有消息都走 AI，忽略本地规则 */
    AI_ALWAYS,

    /** 只有以 /ai 开头的消息才走 AI */
    COMMAND_ONLY,
}

/**
 * 思考模式开关。
 *
 * DeepSeek 官方文档：思考模式**默认开启**（effort=high），会先输出一段思维链
 * 再给最终答案——这就是"感觉思考时间过长"的直接原因。关闭方式是在请求体
 * 顶层传 `{"thinking": {"type": "disabled"}}`。
 *
 * 注意该字段是 DeepSeek 扩展，OpenAI 等严格的服务商遇到未知字段会直接 400，
 * 因此仅当端点域名包含 deepseek 时才会真正发送（见 [AiResponder.thinkingParam]）。
 */
enum class ThinkingMode {
    /** 关闭思考（默认）：响应最快，适合即时对话 */
    DISABLED,

    /** 强制开启思考：回答更严谨但明显更慢 */
    ENABLED,

    /** 不发送该参数，由服务端默认行为决定 */
    AUTO,
}

/**
 * AI 配置。
 *
 * 只保留最常用的字段，全部可由用户在维护端里直接填写，无需改代码。
 */
data class AiConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val systemPrompt: String =
        "你是一个运行在 QQ 里的助手。回答简洁友好，不要使用 Markdown 标题和代码块，因为 QQ 不支持渲染。",
    val temperature: Double = 0.7,
    val maxTokens: Int = 800,
    val mode: AiMode = AiMode.RULE_FIRST,
    /** 每个会话保留的历史轮数（1 轮 = 一问一答 2 条消息） */
    val contextTurns: Int = 6,
    /** AI 失败时回复给用户的话，留空则不回复 */
    val replyOnError: String = "AI 暂时无法回答，请稍后再试。",
    /** 思考模式开关，默认关闭（DeepSeek 默认开启思考，慢） */
    val thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
    /**
     * 上下文 token 预算：system 提示词 + 历史 + 本轮问题的总量上限。
     *
     * 超预算时从最早的历史开始成对丢弃，保证长对话不会把请求撑爆。
     * 同时这也是缓存友好的关键：system 提示词越短越稳定，前缀缓存越容易命中。
     */
    val contextTokenBudget: Int = 3000,
) {
    /** 配置是否可用。缺任何一项都会让每次请求都白跑一趟，故提前拦住。 */
    val isReady: Boolean
        get() = enabled && apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()
}

/**
 * 会话历史存储。
 *
 * 抽成接口是为了让 botcore 不依赖 Room：Android 侧用数据库实现，
 * 单元测试则可用内存实现。
 */
interface AiHistoryStore {
    suspend fun load(
        conversationKey: String,
        limit: Int,
    ): List<ChatMessage>

    suspend fun append(
        conversationKey: String,
        message: ChatMessage,
    )

    suspend fun clear(conversationKey: String)

    /** 清理单个会话中超出保留数量的旧消息 */
    suspend fun trim(
        conversationKey: String,
        keep: Int,
    )

    /** 清理所有会话的旧消息（按全局保留量），供启动时调用一次 */
    suspend fun trimAll(keep: Int) {}
}

/** 内存实现，供测试与「不落库」模式使用。 */
class InMemoryAiHistoryStore : AiHistoryStore {
    private val mutex = Mutex()
    private val store = mutableMapOf<String, MutableList<ChatMessage>>()

    override suspend fun load(
        conversationKey: String,
        limit: Int,
    ): List<ChatMessage> =
        mutex.withLock {
            store[conversationKey]?.takeLast(limit) ?: emptyList()
        }

    override suspend fun append(
        conversationKey: String,
        message: ChatMessage,
    ) {
        // 用大括号体：表达式体会把 add() 的 Boolean 返回值推断成函数返回类型
        mutex.withLock {
            store.getOrPut(conversationKey) { mutableListOf() }.add(message)
        }
    }

    override suspend fun clear(conversationKey: String) = mutex.withLock {
        store.remove(conversationKey)
        Unit
    }

    override suspend fun trim(
        conversationKey: String,
        keep: Int,
    ) = mutex.withLock {
        val list = store[conversationKey] ?: return@withLock
        while (list.size > keep) list.removeAt(0)
    }

    override suspend fun trimAll(keep: Int) = mutex.withLock {
        store.values.forEach { list ->
            while (list.size > keep) list.removeAt(0)
        }
        Unit
    }
}

/**
 * 判断链路的全部「行为约束」。
 *
 * 优先级：userRules（用户显式规则）> lessons（从反馈学到的经验）> 通用规则。
 * 未来扩展点放在这里，而不是继续往 [AiResponder.shouldChimeIn] 加参数：
 * mood（情绪语气）、affinity（好感亲疏）、memes（热梗）都会是新的字段。
 */
data class BehaviorContext(
    val userRules: List<String> = emptyList(),
    val lessons: List<String> = emptyList(),
)

/** 插话判断结果：是否接话 + 一句理由。 */
data class ChimeVerdict(
    val should: Boolean,
    /** ≤40 字；模型没给理由时为 null。复盘页与反思都依赖它。 */
    val reason: String? = null,
)

/**
 * AI 应答器。
 *
 * 职责：
 * 1. 按 [AiConfig.mode] 决定一条消息是否该由 AI 回答；
 * 2. 组装 system prompt + 记忆（事实/群聊背景）+ 历史上下文 + 当前问题，调用 LLM；
 * 3. 把本轮问答写回历史，实现多轮对话。
 *
 * 会话键由 [ConversationKeys] 按人隔离：群里 A 与机器人的恩怨
 * 不会串到 B 的线程里；记忆内容（展示名/事实/背景）由 [MemoryPipeline]
 * 准备好后经 [reply] 的 memory 参数传入，本类不直接依赖存储。
 */
class AiResponder(
    private val client: LlmClient,
    private val history: AiHistoryStore = InMemoryAiHistoryStore(),
    private val assembler: PromptAssembler = PromptAssembler(),
    /**
     * 人设解析：按 人→群→全局 优先级返回系统提示词。
     * 返回 null 时回退到 [AiConfig.systemPrompt]。
     *
     * 声明为挂起函数类型：Android 侧的实现要查 Room，
     * 不该为了拿一段提示词而 runBlocking。
     */
    private val personaResolver: (suspend (IncomingMessage) -> String?)? = null,
) {
    companion object {
        /** 触发 AI 的命令前缀 */
        const val COMMAND_PREFIX = "/ai"

        /** 清空会话上下文的指令（跟在 /ai 后或直接发送） */
        val CLEAR_COMMANDS = setOf("clear", "重置", "清空", "清除上下文", "新对话")

        /**
         * QQ 单条消息长度有限制（群约 4000 字符），
         * 超出会被服务端拒绝，这里统一截断保证发送成功。
         */
        const val MAX_REPLY_LENGTH = 1800

        /** 插话判断提示词里最多注入多少条用户规则，防止把 system 撑爆 */
        const val MAX_USER_RULES = 10

        /** 每次判断注入经验的硬上限（用户可在设置里调到 3~12） */
        const val MAX_LESSONS = 12

        /** 单条用户规则的字符上限 */
        const val MAX_RULE_CHARS = 100

        /** 单条经验的字符上限（与反思的输出要求一致） */
        const val MAX_LESSON_CHARS = 40

        /** 判断理由的字符上限 */
        const val MAX_REASON_CHARS = 40


    }

    // 同一会话串行处理：群聊里多人连问时，避免并发请求把历史顺序打乱。
    // map 会在超过上限时整体清空重建，长期运行也不会无限增长。
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(key: String): Mutex {
        if (locks.size > 128) locks.clear()
        return locks.getOrPut(key) { Mutex() }
    }

    /**
     * 判断该消息是否应由 AI 处理。
     *
     * @param ruleMatched 本地规则引擎是否已经命中——RULE_FIRST 模式下已命中就不再问 AI。
     */
    fun shouldHandle(
        message: IncomingMessage,
        config: AiConfig,
        ruleMatched: Boolean,
    ): Boolean {
        if (!config.isReady) return false
        if (message.content.isBlank()) return false

        return when (config.mode) {
            AiMode.AI_ALWAYS -> true
            AiMode.RULE_FIRST -> !ruleMatched
            AiMode.COMMAND_ONLY ->
                message.cleanText().startsWith(COMMAND_PREFIX, ignoreCase = true)
        }
    }

    /** 剥掉 /ai 前缀，得到真正要问的问题。 */
    fun extractQuestion(message: IncomingMessage): String {
        val text = message.cleanText()
        return if (text.startsWith(COMMAND_PREFIX, ignoreCase = true)) {
            text.substring(COMMAND_PREFIX.length).trim()
        } else {
            text
        }
    }

    /**
     * 生成回复。
     *
     * @param memory 本条消息的记忆上下文（展示名 / 事实 / 群聊背景），
     *   由 [MemoryPipeline.buildContext] 准备；为空时退化为纯对话模式。
     * @param directive 结构化指令的执行结果（见 [CommandOutcome.directive]）。
     *   非空时本轮不是普通对话，而是「按人设复述指令结果」：任务说明会
     *   拼在本轮问题里，人设与记忆照常注入——固定句式与人设割裂的
     *   问题由此解决。会话历史只记录用户的原始话术，不记录包装文本。
     * @return 助手回复文本；失败时抛出 [LlmException]，由调用方决定兜底话术。
     */
    suspend fun reply(
        message: IncomingMessage,
        config: AiConfig,
        memory: MemoryContext? = null,
        directive: String? = null,
    ): String {
        val key = ConversationKeys.history(message)
        val rawQuestion = extractQuestion(message)
        val systemPrompt = personaResolver?.invoke(message) ?: config.systemPrompt

        lockFor(key).withLock {
            // 清空指令不调用模型，直接重置上下文
            if (rawQuestion.lowercase() in CLEAR_COMMANDS) {
                history.clear(key)
                return "已清空与本会话的上下文，可以开始新对话了。"
            }

            if (rawQuestion.isBlank()) return "请问你想问什么？"

            val historyLimit = (config.contextTurns * 2).coerceIn(2, 40)
            val stored = history.load(key, historyLimit)

            val question =
                if (directive.isNullOrBlank()) {
                    rawQuestion
                } else {
                    wrapDirective(directive, rawQuestion)
                }

            val result =
                try {
                    callLlm(stored, question, config, memory, systemPrompt)
                } catch (e: ContextTooLongException) {
                    // 估算偏差或历史里混进超长消息：砍掉一半历史重试一次，
                    // 仍失败就把异常抛给调用方走兜底话术。
                    val halved = stored.drop(stored.size / 2)
                    callLlm(halved, question, config, memory, systemPrompt)
                }

            // 成功后才写入历史：失败的请求不应污染上下文。
            // 记录用户的原始话术而非包装后的任务说明——
            // 否则下一轮历史里会残留「【指令结果】…」这类内部文本。
            history.append(key, ChatMessage(role = "user", content = rawQuestion))
            history.append(key, ChatMessage(role = "assistant", content = result.content))
            history.trim(key, historyLimit)

            return truncate(result.content)
        }
    }

    /**
     * 插话判断：这条消息需要机器人接一句吗？
     *
     * 决策漏斗的第三级。只在本地规则通过后调用：maxTokens 很小、
     * temperature=0，成本远低于一次完整回复，但也不适合对每条消息都跑。
     *
     * [personaPrompt] 是当前生效的人设（按 人→群→全局 解析）。
     * 带上它之后，"要不要接话"就由性格决定——参考 HUMA 与 Big Five 人格
     * 调制的结论：外向人格更愿意参与，内向人格更挑剔。
     *
     * [behavior] 收拢了所有行为约束：用户显式规则 + 从反馈学到的经验。
     * 未来情绪值 / 好感度 / 热梗也在这里扩展，判断链路的签名不用再变。
     *
     * 返回 [ChimeVerdict]（是否接话 + 一句理由）。理由是复盘页与反思的原料：
     * 没有它，用户只能看到「AI 判断不需要接话」，无法指出具体哪里想错了。
     *
     * 判定失败（网络/配置）一律返回 should=false——宁可少说一句，
     * 也不要因为判断服务抖动而在群里乱插话。
     */
    suspend fun shouldChimeIn(
        message: IncomingMessage,
        config: AiConfig,
        recent: List<RoomMessage>,
        botName: String?,
        personaPrompt: String? = null,
        behavior: BehaviorContext = BehaviorContext(),
        /** 机器人上次在本群发言距今多久；null 表示还没说过 */
        lastBotSpokeAgoMillis: Long? = null,
    ): ChimeVerdict {
        val speaker = message.senderName?.takeIf { it.isNotBlank() } ?: "群友"
        // 以当前消息时间为基准算相对时间：模型靠间隔判断冷场还是两人快速对聊
        val now = message.timestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        val transcript =
            recent.joinToString("\n") { msg ->
                val who =
                    if (msg.role == "assistant") {
                        "你"
                    } else {
                        msg.senderName?.takeIf { it.isNotBlank() } ?: "某人"
                    }
                val ago = humanAgo((now - msg.timestampMillis).coerceAtLeast(0L))
                "[$who · $ago] ${msg.content.replace("\n", " ").take(120)}"
            }
        val personaBlock =
            personaPrompt?.takeIf { it.isNotBlank() }?.let { persona ->
                "你的性格设定：「${persona.take(300)}」\n" +
                    "结合这个性格判断：外向爱聊的性格可以更主动接话，" +
                    "高冷话少的性格只在确有必要时才接话。\n"
            }.orEmpty()
        val behaviorBlock = renderBehavior(behavior)
        val spokeBlock =
            lastBotSpokeAgoMillis?.let { "你上次在这个群发言是${humanAgo(it)}。\n" }.orEmpty()
        val system =
            "你在一个 QQ 群里，判断最后这条消息是否需要你接话。\n" +
                personaBlock +
                "注意：这条消息**没有 @ 你**，你只是旁听的群友——不要因为看到了就抢着回。\n" +
                spokeBlock +
                "判断前先读上面的对话：他们在聊什么、谁在跟谁说话、有没有人在等回应。\n" +
                "如果最后几条是两个人快速互相回复、且没向你提问，倾向 NO；\n" +
                "如果是抛给全群的问题、明显冷场、或在延续你刚才的话题，倾向 YES。\n" +
                "回答 YES 的情况：\n" +
                "1) 有人向你提问或求助；\n" +
                "2) 话题明显需要别人搭话（冷场、抛问题、征求意见、求推荐）；\n" +
                "3) 有人说的话明显有误，且你有把握纠正；\n" +
                "4) 话题正在继续，按你的性格插一句很自然（聊到你的兴趣、你有相关经历）；\n" +
                "5) 按你的性格，这种场合你本来就会忍不住说一句。\n" +
                "回答 NO 的情况：两个人在互相聊、@ 了别人、与你无关的闲扯、刷屏。\n" +
                behaviorBlock +
                "输出格式（严格遵守）：\n" +
                "第一行：YES 或 NO\n" +
                "第二行：不超过 20 字的中文理由\n" +
                "不要输出其它内容。"
        val user =
            buildString {
                if (transcript.isNotBlank()) append("最近消息：\n").append(transcript).append("\n\n")
                append("现在 [").append(speaker).append("] 说：「")
                    .append(message.content.take(120)).append("」")
            }
        suspend fun ask(tokens: Int) =
            client.chat(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                temperature = 0.0,
                maxTokens = tokens,
                // 判断是即时动作：超时就当作不接，别把消息处理拖住
                timeoutSeconds = 20L,
                thinkingType = thinkingParam(config),
            )

        // 输出被思考过程挤爆（finish=length）时放宽额度重试一次。
        // 不重试的话，这类"想得多"的模型会在群消息密集时持续判不出来。
        // 输出预算跟随设置里的「单次生成上限」，不在代码里另立一套数字
        val budget = GenerationLimits.forConfig(config)
        val reply =
            try {
                ask(budget)
            } catch (e: EmptyContentException) {
                if (!e.truncated) throw e
                ask(GenerationLimits.retryFor(budget))
            }
        return parseVerdict(reply.content)
    }

    /** 把毫秒差写成模型能理解的时间感。 */
    private fun humanAgo(deltaMillis: Long): String =
        when {
            deltaMillis < 60_000L -> "刚刚"
            deltaMillis < 3_600_000L -> "${deltaMillis / 60_000L} 分钟前"
            deltaMillis < 86_400_000L -> "${deltaMillis / 3_600_000L} 小时前"
            else -> "${deltaMillis / 86_400_000L} 天前"
        }

    /**
     * 渲染行为约束块。用户规则在上、经验在下——冲突时以用户显式约定为准。
     * 空表不产生任何文本，避免给模型塞无意义的标题。
     */
    private fun renderBehavior(behavior: BehaviorContext): String {
        val rules =
            behavior.userRules.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_USER_RULES)
        val lessons =
            behavior.lessons.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_LESSONS)
        if (rules.isEmpty() && lessons.isEmpty()) return ""
        return buildString {
            if (rules.isNotEmpty()) {
                append("用户补充规则（优先级最高，必须遵守）：\n")
                rules.forEach { append("- ").append(it.take(MAX_RULE_CHARS)).append('\n') }
            }
            if (lessons.isNotEmpty()) {
                append("从主人反馈中学到的经验（与上面的用户规则冲突时，以用户规则为准）：\n")
                lessons.forEach { append("- ").append(it.take(MAX_LESSON_CHARS)).append('\n') }
            }
        }
    }

    /**
     * 解析「第一行 YES/NO + 第二行理由」。
     *
     * 模型并不总是严格分行：常见变体有「YES，因为…」「NO：两人在私聊」。
     * 因此首行既决定结论，也承担「同行理由」的抽取；都拿不到就判 false。
     */
    internal fun parseVerdict(raw: String): ChimeVerdict {
        val lines = raw.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val first = lines.firstOrNull().orEmpty()
        val head = first.uppercase()
        val should =
            when {
                head.startsWith("NO") || head.startsWith("否") || head.startsWith("不") -> false
                head.startsWith("YES") || head.startsWith("是") -> true
                head.contains("YES") -> true
                else -> false
            }
        val inline =
            first
                .removePrefix("YES")
                .removePrefix("NO")
                .removePrefix("是")
                .removePrefix("否")
                .removePrefix("不")
                .trim()
                .trimStart('，', ',', '。', '.', '：', ':', ' ', '、')
        val reason =
            inline
                .ifBlank { lines.drop(1).joinToString(" ").trim() }
                .take(MAX_REASON_CHARS)
                .ifBlank { null }
        return ChimeVerdict(should = should, reason = reason)
    }

    /**
     * 把指令执行结果包装成「人设复述任务」。
     *
     * 指令的执行在本地完成（改名片、删记忆都是数据库操作），
     * 但表达层统一交还人设：「你叫小明呀」配上毒舌人设就是人格分裂，
     * 不如让模型拿着事实结果用自己的语气说。
     */
    private fun wrapDirective(
        directive: String,
        rawQuestion: String,
    ): String =
        buildString {
            append("【指令结果】").append(directive)
            append("\n【任务】以上是本地指令的执行结果。请严格依据它，")
            append("用你的人设和语气自然地回复这句话：「").append(rawQuestion).append("」。")
            append("要像你本来就知道一样说出来，不要提及指令、系统、数据库等字眼；")
            append("结果中的关键信息（名字、数量、条目）必须如实保留，不得编造。")
        }

    /** 最近一次成功调用的用量（供维护端展示缓存命中率），无调用时为 null。 */
    private val _lastUsage = MutableStateFlow<LlmUsage?>(null)
    val lastUsage: StateFlow<LlmUsage?> = _lastUsage.asStateFlow()

    private suspend fun callLlm(
        storedHistory: List<ChatMessage>,
        question: String,
        config: AiConfig,
        memory: MemoryContext?,
        systemPrompt: String,
    ): LlmReply {
        val messages =
            assembler.assemble(
                personaPrompt = systemPrompt,
                memory = memory,
                history = storedHistory,
                question = question,
                tokenBudget = config.contextTokenBudget,
            )
        val reply =
            client.chat(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                messages = messages,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                thinkingType = thinkingParam(config),
            )
        _lastUsage.value = reply.usage
        return reply
    }

    /**
     * 组装消息列表——兼容旧测试的薄封装，实际逻辑在 [PromptAssembler]。
     *
     * 顺序本身就是缓存策略：
     * 1. system（人设/记忆）必须最先且内容逐字节稳定：DeepSeek 的前缀缓存要求
     *    「从第 0 个 token 起完全相同」，在 system 里插时间戳之类变化内容
     *    会让后面全部缓存失效，既费钱又慢；
     * 2. 历史按时间正序 append-only，超预算时从最早开始**成对**丢弃
     *    （一问一答算一对），保证问答配对完整；
     * 3. 本轮问题固定放最后。
     */
    internal fun assembleMessages(
        systemPrompt: String,
        history: List<ChatMessage>,
        question: String,
        tokenBudget: Int,
    ): List<ChatMessage> =
        assembler.assemble(
            personaPrompt = systemPrompt,
            memory = null,
            history = history,
            question = question,
            tokenBudget = tokenBudget,
        )

    /**
     * 是否发送 thinking 参数、发送什么值。
     *
     * thinking 是 DeepSeek 扩展字段：OpenAI 等实现严格的的服务商遇到未知字段
     * 会直接 400，因此仅在「端点域名或模型名含 deepseek」时才发送——
     * 模型名也参与判断是为了覆盖通过第三方代理转发 DeepSeek 的场景；
     * 其余情况等同 AUTO（不发送，由服务端默认行为决定）。
     */
    internal fun thinkingParam(config: AiConfig): String? = ThinkingParams.forConfig(config)

    /** 清空某个会话的上下文（按人隔离的线程）。 */
    suspend fun clearHistory(message: IncomingMessage) {
        history.clear(ConversationKeys.history(message))
    }

    /** 启动时清理一次全局过期历史。 */
    suspend fun trimAllHistory(keep: Int) {
        history.trimAll(keep)
    }

    private fun truncate(text: String): String =
        if (text.length <= MAX_REPLY_LENGTH) {
            text
        } else {
            text.take(MAX_REPLY_LENGTH) + "\n……（回复过长已截断）"
        }
}
