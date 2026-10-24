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
    /**
     * 2.3.0 起废弃：人格由 [Persona] 档案提供，这里不再承载「机器人是谁」。
     *
     * 保留字段只为两个用途：
     * 1. 兼容旧备份与旧 DataStore 数据（启动引导会把它迁移成第一条人格档案）；
     * 2. 未注入 [PersonaResolver] 的纯测试/外部调用路径。
     */
    val systemPrompt: String = "",
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
    /**
     * 视觉模型名。带图的消息用它；留空则沿用 [model]。
     *
     * 单独留一个字段，是因为主模型可能不支持图片：这时把 visionModel
     * 填成 deepseek-flash 即可，不用为了看图把主模型也换掉。
     */
    val visionModel: String = "",
    /**
     * 允许联网搜索（DeepSeek 搜索接口，按成功请求计费）。
     *
     * 目前只用在「遇到不懂的梗，查一下」这一处，不参与每轮对话——
     * 否则每条消息都要花一次搜索费，而绝大多数消息根本不需要。
     */
    val webSearch: Boolean = true,
) {
    /** 配置是否可用。缺任何一项都会让每次请求都白跑一趟，故提前拦住。 */
    val isReady: Boolean
        get() = enabled && apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()

    /**
     * 本轮是否真的能把 web_search 工具交给模型。
     *
     * 只有 DeepSeek 官方端点实现了这套搜索接口；开关关着或换了服务商时，
     * 工具说明不能出现在请求里——否则模型会反复调用一个注定失败的函数，
     * 白烧一轮工具循环。
     */
    val webSearchAvailable: Boolean
        get() = webSearch && baseUrl.contains("deepseek", ignoreCase = true)

    /**
     * 带图时实际使用的模型名。
     *
     * 用户填了 [visionModel] 就用它；没填时，DeepSeek 官方端点退回
     * `deepseek-flash`——默认的 `deepseek-chat` 不接受图片，若沿用它会直接 400，
     * 表现就是「机器人完全看不见图」。其他服务商仍沿用主模型，
     * 因为无法假设它们也有同名视觉模型。
     */
    fun modelForImage(): String =
        visionModel.ifBlank {
            if (baseUrl.contains("deepseek", ignoreCase = true)) "deepseek-flash" else model
        }

    companion object {
        /**
         * 2.2.0 及更早版本的出厂默认提示词。
         *
         * 迁移时用它判断"这条提示词是出厂值还是用户自己写的"——
         * 出厂值不能被当成用户的人格档案导进来，否则新用户一升级
         * 就凭空多出一个"QQ 助手"人格。
         */
        const val LEGACY_DEFAULT_SYSTEM_PROMPT: String =
            "你是一个运行在 QQ 里的助手。回答简洁友好，不要使用 Markdown 标题和代码块，因为 QQ 不支持渲染。"
    }
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
    /**
     * 这条消息的发送者跟你的关系。
     *
     * 只影响"想不想接"：熟人多搭两句，被这个人伤过会犹豫。
     * 不影响礼数——好感再低，对方说正事也照常回。
     */
    val relation: PersonRelation? = null,
)

/**
 * 插话判断结果：想不想接 + 一句理由。
 *
 * 2.2 起语义从「应不应该」改为「想不想」——机器人不是客服，判断的是
 * 这个"人"此刻有没有说话的冲动。
 */
data class ChimeVerdict(
    val wants: Boolean,
    /** ≤40 字；模型没给理由时为 null。复盘页与反思都依赖它。 */
    val reason: String? = null,
) {
    /** 旧名字，保留以免外部调用方断裂 */
    @Deprecated("语义已改为想不想，请用 wants", ReplaceWith("wants"))
    val should: Boolean get() = wants
}

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
    /**
     * 本轮可用的工具。为空时行为与之前完全一致（纯文本 + 表情标记）。
     *
     * 工具只给主回复链路，不给插话判断/反思等结构化短任务——
     * 那些调用需要的是确定性，不需要模型自主选动作。
     */
    private val tools: ToolRegistry = ToolRegistry(),
    /**
     * 会话作用域：返回值会拼进历史 key。
     *
     * Android 侧传入当前生效的人设 id，切换档案后自动新开一条线程，
     * 避免新人设继续沿用旧人设的语气与话题。
     */
    private val conversationScopeProvider: (suspend (IncomingMessage) -> String?)? = null,
    /**
     * 图片内联：把 QQ 的临时图片链接下载后转成 data URL。
     *
     * 默认原样返回地址（不下载），这样纯测试/外部调用路径不发额外网络请求。
     * Android 侧传入 [RemoteImageLoader]，避免链接过期或防盗链导致模型看不到图。
     */
    private val imageInliner: ImageInliner = ImageInliner { it },
) {
    /**
     * 服务商不支持 tools 时记下「是哪套配置不认」，后续同一端点/模型不再携带。
     * 只在 400（且不是上下文超限）时降级。
     *
     * 记配置而不是记一个布尔，是因为用户换模型/换服务商后工具可能又能用了；
     * 一个进程级开关会让表情包和联网搜索在换配置后「永久失效到重启」。
     */
    @Volatile
    private var toolsUnsupportedFor: String? = null

    private fun toolSignature(config: AiConfig): String = "${config.baseUrl}|${config.model}"

    private fun toolsBlocked(config: AiConfig): Boolean =
        toolsUnsupportedFor == toolSignature(config)

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

        /**
         * 一次回复里最多允许几轮工具调用。
         *
         * 群聊回复要快，工具也不该串成链：一轮选图足够；
         * 给两轮是为了兼容"先查再答"的简单组合，超过就强制收口。
         */
        const val MAX_TOOL_ROUNDS = 2

        /**
         * 判断提示词的固定标记。
         *
         * 测试与日志用它识别"这是一次接话判断"，不依赖具体措辞——
         * 提示词改版时不会连带改一堆断言。
         */
        const val JUDGE_PROMPT_TAG = "【接话判断】"

        /** 会话锁表上限：超过后回收空闲锁，避免长期运行时 map 无限膨胀。 */
        private const val MAX_LOCKS = 128

    }

    // 同一会话串行处理：群聊里多人连问时，避免并发请求把历史顺序打乱。
    // 超过上限时只回收「当前没人持有」的锁；清空整张表会让正在排队的会话
    // 各自拿到新的 Mutex，历史写入重新变成并发。
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(key: String): Mutex {
        if (locks.size > MAX_LOCKS) {
            locks.entries.removeIf { (_, mutex) ->
                if (!mutex.tryLock()) return@removeIf false
                mutex.unlock()
                true
            }
        }
        return locks.getOrPut(key) { Mutex() }
    }

    /**
     * 历史 key：按人隔离，再叠加当前生效的人设。
     *
     * 覆盖层（按人/按群）也有独立 id，所以"这个群用覆盖人设"与
     * "全局档案"不会共享上下文；切换档案后自然就是一条新线程。
     */
    private suspend fun historyKey(message: IncomingMessage): String {
        val base = ConversationKeys.history(message)
        val scope = conversationScopeProvider?.invoke(message)?.takeIf { it.isNotBlank() }
        return if (scope == null) base else "$base|$scope"
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
        // 只发了图没写字也算有内容：群里 @ 机器人甩一张表情包过来，
        // 是视觉模型最常见的开场——这里挡住的话，"能看图"就白接了。
        if (message.content.isBlank() && message.imageUrl.isNullOrBlank()) return false
        // 纯图消息不可能是 /ai 指令，COMMAND_ONLY 下直接放行只会把它
        // 当成一句没有文字的提问——所以仍然要求有文字。
        if (config.mode == AiMode.COMMAND_ONLY &&
            message.content.isBlank()
        ) {
            return false
        }

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
    ): String = replyDetailed(message, config, memory, directive).content

    /**
     * 同 [reply]，但把模型没说出口的那段（思考）一并带出来。
     *
     * 思考只在用户开了思考模式时才有；关闭时 [AiReply.reasoning] 为 null。
     * 调用方自行决定要不要落库（见 [InnerThought]），这里不做任何存储。
     */
    suspend fun replyDetailed(
        message: IncomingMessage,
        config: AiConfig,
        memory: MemoryContext? = null,
        directive: String? = null,
    ): AiReply {
        val key = historyKey(message)
        // 只发了图没写字：给模型一个默认问题，否则它会回"请问你想问什么"——
        // 对方明明发了图，这么问等于没看见。
        val rawQuestion =
            extractQuestion(message).let { question ->
                if (question.isBlank() && !message.imageUrl.isNullOrBlank()) {
                    "（对方发来一张图片，没有配文字）"
                } else {
                    question
                }
            }
        val systemPrompt = personaResolver?.invoke(message) ?: config.systemPrompt
        // 先本地下载再交图：QQ 图片链接带签名、会过期，也有防盗链；
        // 直接让模型去抓，失败时用户只看到"看图失败"。下载失败则回退原地址。
        val imageUrl =
            message.imageUrl?.let { raw ->
                runCatching { imageInliner.inline(raw) }.getOrNull() ?: raw
            }

        lockFor(key).withLock {
            // 清空指令不调用模型，直接重置上下文
            if (rawQuestion.lowercase() in CLEAR_COMMANDS) {
                history.clear(key)
                return AiReply("已清空与本会话的上下文，可以开始新对话了。")
            }

            if (rawQuestion.isBlank()) return AiReply("请问你想问什么？")

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
                    callLlmWithTools(stored, question, config, memory, systemPrompt, imageUrl)
                } catch (e: ContextTooLongException) {
                    // 估算偏差或历史里混进超长消息：砍掉一半历史重试一次，
                    // 仍失败就把异常抛给调用方走兜底话术。
                    val halved = stored.drop(stored.size / 2)
                    callLlmWithTools(halved, question, config, memory, systemPrompt, imageUrl)
                }

            // 成功后才写入历史：失败的请求不应污染上下文。
            // 记录用户的原始话术而非包装后的任务说明——
            // 否则下一轮历史里会残留「【指令结果】…」这类内部文本。
            // 表情包标记在进历史前剥掉：历史里只留说出口的话，
            // 但图地址要带出去给发送链路（见 [AiReply.memeUrl]）。
            val reply = result.first
            val (spoken, markerMeme) = MemeMarker.parse(reply.content)
            // 工具选图优先于文本标记：前者已经过本地库校验。
            val memeUrl = result.second ?: markerMeme
            // 纯表情包回复：正文是空的，历史里不能留一条空 assistant 消息——
            // 下一轮它会看见"自己刚才啥也没说"。留一句只有它看得懂的记号。
            val forHistory =
                spoken.ifBlank { memeUrl?.let { "（回了一张表情包）" }.orEmpty() }
            history.append(key, ChatMessage(role = "user", content = rawQuestion))
            history.append(key, ChatMessage(role = "assistant", content = forHistory))
            history.trim(key, historyLimit)

            return AiReply(
                content = truncate(spoken),
                // 思考模式关闭时这里恒为 null，不会产生空的内心记录
                reasoning = reply.reasoning?.takeIf { it.isNotBlank() },
                memeUrl = memeUrl,
            )
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
    suspend fun wantsToChimeIn(
        message: IncomingMessage,
        config: AiConfig,
        recent: List<RoomMessage>,
        botName: String?,
        personaPrompt: String? = null,
        behavior: BehaviorContext = BehaviorContext(),
        /** 机器人上次在本群发言距今多久；null 表示还没说过 */
        lastBotSpokeAgoMillis: Long? = null,
        /** 这句话大概率是对谁说的；null 时不下结论 */
        addressee: Addressee? = null,
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
                "你的性格：「${persona.take(300)}」\n" +
                    "按这个性格来：外向的人更容易被话题勾住，高冷的人大部分时候懒得开口。\n"
            }.orEmpty()
        val behaviorBlock = renderBehavior(behavior)
        val spokeBlock =
            lastBotSpokeAgoMillis
                ?.let { "你上次在这个群发言是${humanAgo(it)}。\n" }
                ?: "你今晚还没在这个群说过话。\n"
        val addresseeBlock = addressee?.let { describeAddressee(it) }.orEmpty()
        val system =
            JUDGE_PROMPT_TAG + "\n" +
                "你在 QQ 群里旁听。你不是客服，也不欠任何人回复——现在判断的只有一件事：" +
                "你此刻**想不想**接这一句。\n" +
                "注意：这条消息没有 @ 你。\n" +
                personaBlock +
                spokeBlock +
                addresseeBlock +
                "先读上面的对话：他们在聊什么、谁在跟谁说话、有没有人在等回应。\n" +
                "你会想接的情况：\n" +
                "1) 有人问你、点你，或明显在等一个回应；\n" +
                "2) 这话让你有共鸣、想吐槽、想补充、想纠正；\n" +
                "3) 冷场了，你想救一下；\n" +
                "4) 话题正好是你懂的 / 感兴趣的，你有话可说；\n" +
                "5) 按你的性格，这种场面你会忍不住。\n" +
                "你不想接的情况：\n" +
                "- 两个人正在互聊，尤其这句话是对其中一个人说的；\n" +
                "- 别人在互相玩梗、拌嘴、吵架：不要站队、劝架或替谁出头，你不是群管；\n" +
                "- 有人明确说「关你什么事」「没跟你说话」，这是划界，不是邀请；\n" +
                "- 你的名字只是被提到（例如「我不是阿远」「阿远今天没来」），不等于在叫你；\n" +
                "- 你刚说完，再说就是刷屏；\n" +
                "- 你没什么想说的，只是「可以回」；\n" +
                "- 这话与你无关，或者你只是在复述别人。\n" +
                "想接就 YES，不想就 NO。安静完全正常，没人会怪你。\n" +
                behaviorBlock +
                "输出格式（严格遵守）：\n" +
                "第一行：YES（想接）或 NO（不想）\n" +
                "第二行：不超过 20 字的中文理由\n" +
                "不要输出其它内容。"
        val user =
            buildString {
                if (transcript.isNotBlank()) append("最近消息：\n").append(transcript).append("\n\n")
                append("现在 [").append(speaker).append("] 说：「")
                    .append(message.content.take(160)).append("」")
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
                // 和反思/破壳一样是结构化短任务：必须关思考。
                // 否则思维链会吃掉输出额度，判断必然落空 → 全群静默。
                thinkingType = ThinkingParams.forConfig(config, forceDisable = true),
            )

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

    /** 把对话对象推断渲染成一句可执行的提示。 */
    private fun describeAddressee(addressee: Addressee): String =
        when (addressee.kind) {
            AddresseeKind.BOT -> "【这句话是对你说的】${addressee.evidence}。\n"
            AddresseeKind.MEMBER ->
                "【这句话是对「${addressee.name ?: "某位群友"}」说的】${addressee.evidence}。" +
                    "别人之间的对话，插进去通常很怪。\n"
            AddresseeKind.GROUP -> "【这句话像是对全群说的】${addressee.evidence}。\n"
            AddresseeKind.UNKNOWN -> ""
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
        val relation = behavior.relation
        if (rules.isEmpty() && lessons.isEmpty() && relation == null) return ""
        return buildString {
            if (relation != null) {
                append(RelationPolicy.describe(relation))
                append("\n这只是倾向：熟人可以多搭一句，不熟就别硬接；")
                append("好感低也不许冷落、阴阳或翻旧账。\n")
            }
            if (rules.isNotEmpty()) {
                append("用户补充规则（优先级最高，必须遵守）：\n")
                rules.forEach { append("- ").append(it.take(MAX_RULE_CHARS)).append('\n') }
            }
            if (lessons.isNotEmpty()) {
                append("从主人反馈里学到的偏好（与上面的用户规则冲突时，以用户规则为准）：\n")
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
        return ChimeVerdict(wants = should, reason = reason)
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

    /** 组装本轮消息：system + 记忆 + 历史 + 问题，并只给最后一条 user 附图。 */
    private fun buildMessages(
        storedHistory: List<ChatMessage>,
        question: String,
        config: AiConfig,
        memory: MemoryContext?,
        systemPrompt: String,
        imageUrl: String? = null,
    ): List<ChatMessage> {
        val assembled =
            assembler.assemble(
                personaPrompt = systemPrompt,
                memory = memory,
                history = storedHistory,
                question = question,
                tokenBudget = config.contextTokenBudget,
            )
        // 只有本轮带图：历史里的图不再重复送，否则同一张图每轮都要付一次 token。
        if (imageUrl.isNullOrBlank()) return assembled
        val last = assembled.lastOrNull() ?: return assembled
        return if (last.role == "user") {
            assembled.dropLast(1) + last.copy(images = listOf(imageUrl))
        } else {
            assembled
        }
    }

    /** 选择模型并真正发起请求；tools 不支持时自动降级一次。 */
    private suspend fun executeChat(
        messages: List<ChatMessage>,
        config: AiConfig,
        imageUrl: String?,
        useTools: Boolean,
        unavailableTools: Set<String> = emptySet(),
    ): LlmReply {
        // 带图时优先用视觉模型；没填时 DeepSeek 端点会自动退回 deepseek-flash
        val model =
            if (!imageUrl.isNullOrBlank()) config.modelForImage() else config.model
        val availableSpecs = tools.specsExcept(unavailableTools)
        val sendTools = useTools && !toolsBlocked(config) && availableSpecs.isNotEmpty()

        suspend fun call(withTools: Boolean): LlmReply =
            client.chat(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = model,
                messages = messages,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                thinkingType = thinkingParam(config),
                tools = if (withTools) availableSpecs else emptyList(),
            )

        val reply =
            try {
                call(sendTools)
            } catch (e: LlmException) {
                // 一些 OpenAI 兼容端点不认识 tools，会直接 400。
                // 只降级一次并记住，后续请求不再带工具；上下文超限另走裁剪逻辑。
                //
                // 但 400 不一定怪 tools：带图时也可能是「模型不支持图片」。
                // 只有报文明确提到 tools/function，或本轮根本没带图时，
                // 才认定是工具不兼容——否则一次看图失败会让表情包工具永久失效。
                val looksLikeTools =
                    e.message.orEmpty().lowercase().let {
                        it.contains("tool") || it.contains("function")
                    }
                if (sendTools &&
                    e.statusCode == 400 &&
                    e !is ContextTooLongException &&
                    (looksLikeTools || imageUrl.isNullOrBlank())
                ) {
                    toolsUnsupportedFor = toolSignature(config)
                    call(false)
                } else {
                    throw e
                }
            }
        _lastUsage.value = reply.usage
        return reply
    }

    /**
     * 主回复链路：允许模型在一次回复里调用工具。
     *
     * 返回「最终回复 + 工具选定的表情地址」。工具只执行一轮或两轮，
     * 超过上限时强制不带工具再请求一次，避免模型陷入调用循环。
     */
    private suspend fun callLlmWithTools(
        storedHistory: List<ChatMessage>,
        question: String,
        config: AiConfig,
        memory: MemoryContext?,
        systemPrompt: String,
        imageUrl: String? = null,
    ): Pair<LlmReply, String?> {
        var messages = buildMessages(storedHistory, question, config, memory, systemPrompt, imageUrl)
        var memeUrl: String? = null
        var round = 0
        // 本轮不能用的工具不发给模型：联网关着 / 端点不支持 / 表情库为空。
        // 注意只有在**拿到记忆上下文且库确实为空**时才摘掉表情工具：
        // memory 为 null 表示调用方没提供候选，不等于库是空的。
        val unavailable =
            buildSet {
                if (!config.webSearchAvailable) add("web_search")
                if (memory != null && memory.social?.memes?.isEmpty() == true) add("send_meme")
            }

        while (true) {
            val reply = executeChat(messages, config, imageUrl, useTools = true, unavailableTools = unavailable)
            if (reply.toolCalls.isEmpty()) return reply to memeUrl

            if (round >= MAX_TOOL_ROUNDS) {
                // 已经给过机会：收口成文字，别把用户晾着。
                val forced =
                    messages +
                        ChatMessage(
                            role = "user",
                            content = "请直接用一句自然的话回复，不要再调用工具。",
                        )
                return executeChat(forced, config, imageUrl, useTools = false) to memeUrl
            }

            messages =
                messages +
                    ChatMessage(
                        role = "assistant",
                        content = reply.content,
                        toolCalls = reply.toolCalls,
                    )
            reply.toolCalls.forEach { call ->
                val result = tools.execute(call)
                result.memeUrl?.takeIf { it.isNotBlank() }?.let { memeUrl = it }
                messages =
                    messages +
                        ChatMessage(
                            role = "tool",
                            content = result.content,
                            toolCallId = call.id,
                        )
            }
            round++
        }
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

    /** 清空某个会话的上下文（按人 + 当前人设隔离的线程）。 */
    suspend fun clearHistory(message: IncomingMessage) {
        history.clear(historyKey(message))
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
