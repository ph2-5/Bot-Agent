package com.qbot.core.engine

import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.Addressee
import com.qbot.core.ai.AddresseeDetector
import com.qbot.core.ai.AddresseeKind
import com.qbot.core.ai.AiMode
import com.qbot.core.ai.AiReply
import com.qbot.core.ai.AiResponder
import com.qbot.core.ai.BehaviorContext
import com.qbot.core.ai.BotNameMatcher
import com.qbot.core.ai.ChimeDecisionRecord
import com.qbot.core.ai.ChimeLesson
import com.qbot.core.ai.ChimeTrainer
import com.qbot.core.ai.LessonCategories
import com.qbot.core.ai.LlmException
import com.qbot.core.ai.ChitchatConfig
import com.qbot.core.ai.ChitchatGate
import com.qbot.core.ai.ChitchatPolicy
import com.qbot.core.ai.CommandOutcome
import com.qbot.core.ai.InsultContext
import com.qbot.core.ai.InsultDetector
import com.qbot.core.ai.InnerThought
import com.qbot.core.ai.InnerThoughtSink
import com.qbot.core.ai.MemeHarvester
import com.qbot.core.ai.MemeIntent
import com.qbot.core.ai.MemeLibrary
import com.qbot.core.ai.MemeMarker
import com.qbot.core.ai.MemeReader
import com.qbot.core.ai.MemoryContext
import com.qbot.core.ai.MemoryPipeline
import com.qbot.core.ai.PersonaBias
import com.qbot.core.ai.PraiseDetector
import com.qbot.core.ai.RelationEvent
import com.qbot.core.ai.RelationRecorder
import com.qbot.core.ai.Scene
import com.qbot.core.ai.SceneAction
import com.qbot.core.ai.SceneDecisionRecord
import com.qbot.core.ai.SceneLimits
import com.qbot.core.ai.SceneMode
import com.qbot.core.ai.ScenePrompt
import com.qbot.core.ai.SceneStatus
import com.qbot.core.ai.SceneStore
import com.qbot.core.ai.SlangLearner
import com.qbot.core.ai.SocialContextProvider
import com.qbot.core.ai.StyleRule
import com.qbot.core.ai.StyleRuleSource
import com.qbot.core.ai.ToolTurn
import com.qbot.core.ai.TranscriptRender
import com.qbot.core.ai.RoomMessage
import com.qbot.core.ai.describe
import com.qbot.core.api.MsgType
import com.qbot.core.api.OpenApi
import com.qbot.core.gateway.GatewayClient
import com.qbot.core.gateway.SimpleGatewayListener
import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.MessageKind
import com.qbot.core.model.ReadyUser
import com.qbot.core.model.cleanText
import com.qbot.core.model.parseGroupNotice
import com.qbot.core.util.Logger
import com.qbot.core.util.NoopLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志级别。 */
enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/** 一条运行日志。 */
data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val text: String,
)

/** 日志出口，Android 侧实现后转发到数据库/UI。 */
interface LogSink {
    fun append(entry: LogEntry)
}

/**
 * 机器人运行时。
 *
 * 把「网关 → 规则匹配 → AI 应答 → 调用 API 回复」串成一条完整链路，
 * 这一层同样不依赖 Android，因此可以在 JVM 上直接跑集成测试。
 *
 * 消息处理刻意放在后台协程里异步执行：
 * 网关回调线程必须尽快返回，否则会堵塞后续事件读取。
 */
class BotRuntime(
    private val api: OpenApi,
    private val gateway: GatewayClient,
    private val ruleEngine: RuleEngine,
    private val scope: CoroutineScope,
    private val logSink: LogSink? = null,
    private val logger: Logger = NoopLogger,
    private val aiResponder: AiResponder? = null,
    /**
     * AI 配置提供者。
     *
     * 用 lambda 而不是直接传值，是为了让用户在维护端改完 AI 设置后
     * 无需重启机器人——每条消息进来时才读取最新配置。
     */
    private val aiConfigProvider: (() -> AiConfig?)? = null,
    /**
     * 记忆管线（身份 → 事实 → 共享群聊背景）。
     *
     * 为 null 时退化为纯对话机器人：不认人、不记忆、没有人设解析，
     * 行为与接入前完全一致（测试与「轻量模式」用）。
     */
    private val memoryPipeline: MemoryPipeline? = null,
    /**
     * 加群问候语提供者。
     *
     * 返回空白/null 表示关闭问候。机器人被拉进群时若开启，
     * 会以主动消息形式发一句自我介绍（消耗主动消息配额，
     * 因此默认关闭，由用户在维护端显式打开）。
     */
    private val greetingTextProvider: (() -> String?)? = null,
    /**
     * 群聊参与（插话）配置提供者。
     *
     * 为 null 或 [ChitchatConfig.enabled] 为 false 时，
     * 非 @ 消息只会被记进群记忆，不会触发任何回复。
     */
    private val chitchatConfigProvider: (() -> ChitchatConfig)? = null,
    /**
     * 当前消息生效的人设提示词（按 人→群→全局 解析）。
     *
     * 两处消费：插话漏斗的性格偏置（话痨放宽/高冷收紧）、
     * AI 插话判断的"按性格决定要不要接话"。
     */
    private val personaPromptProvider: (suspend (IncomingMessage) -> String?)? = null,
    /**
     * 插话决策出口（训练闭环）。
     *
     * [handleChitchat] 在 finally 里只调用一次：成功、失败、提前返回都会留一条
     * 结构化记录。实现方负责落库、经验注入与命中计数；为 null 时完全关闭，
     * 行为与 1.9.x 一致（测试与「不落库」模式用）。
     */
    private val chimeTrainer: ChimeTrainer? = null,
    /**
     * 内心戏出口。
     *
     * 思考模式打开时，模型会先说一段没说出口的话。它**不进对话上下文**、
     * 不发给群友，只用于复盘与未来的情绪建模；为 null 时完全关闭。
     */
    private val innerThoughtSink: InnerThoughtSink? = null,
    /** 社交上下文：对这个人什么关系、自己什么心情、群里最近怎么说 */
    private val socialProvider: SocialContextProvider? = null,
    /** 关系事件写入口：@ / 被夸 / 被骂 / 回了一句 */
    private val relationRecorder: RelationRecorder? = null,
    /**
     * 遇到没见过的说法时去查一下。
     *
     * 异步执行，不阻塞这一轮回复——它答得快比答得懂重要，
     * 这一轮不懂就先按人设处理（"这是啥意思"本身也很像人）。
     */
    private val slangLearner: SlangLearner? = null,
    /**
     * 收表情包：群友发的图顺手捡进库，之后回复时可以配一张。
     *
     * 与学梗一样丢后台：这一轮回什么，不该被"捡图"拖慢。
     * 为 null 时完全关闭——不发图的机器人行为与接入前一致。
     */
    private val memeHarvester: MemeHarvester? = null,
    /**
     * 表情包库。
     *
     * 两个用途：用户明确要图时本地直接挑一张（不消耗模型调用）；
     * 模型通过 send_meme 工具选好图、真正发出去之后累加采用次数。
     * 为 null 时只保留提示词里的 `[表情:URL]` 约定。
     */
    private val memeLibrary: MemeLibrary? = null,
    /**
     * 表情包本地副本读取器。
     *
     * 图库里的 `path` 指向落盘的 JPEG；能读到字节就走 `file_data` 直传，
     * QQ CDN 的签名链接就不会因为过期而把老表情包变成死链。
     * 为 null 或读不到时回退到按 URL 上传。
     */
    private val memeReader: MemeReader? = null,
    /**
     * 说话约束提供者（内置 + 用户自定义 + 插件）。
     *
     * 每轮回复前取一次并注入提示词；为 null 时不注入，
     * 行为与接入前一致（测试 / 轻量模式）。
     */
    private val styleRuleProvider: (suspend () -> List<StyleRule>)? = null,
    /**
     * 场景存储。
     *
     * 为 null 或场景开关为 [SceneMode.OFF] 时，判定链完全不查场景，
     * 行为与 2.12.0 逐字节一致（默认关闭，这是 2.13.0 的硬要求）。
     */
    private val sceneStore: SceneStore? = null,
    /** 场景开关提供者；默认 OFF。 */
    private val sceneModeProvider: (() -> SceneMode)? = null,
    /**
     * 场景工具箱。
     *
     * 只用来在对方接话后重置"相邻两轮不能连续等待"的计数；
     * 工具本身注册在 [com.qbot.core.ai.ToolRegistry] 里，由 app 侧装配。
     */
    private val sceneToolbox: com.qbot.core.ai.SceneToolbox? = null,
    /** 插话决策引擎；默认实例即可，测试可注入固定时钟 */
    private val chitchatPolicy: ChitchatPolicy = ChitchatPolicy(),
    /** 冒犯检测：被指名辱骂时要按人设反应，不能装没看见 */
    private val insultDetector: InsultDetector = InsultDetector(),
    /** 概率源，测试里可注入固定值 */
    private val random: () -> Double = { kotlin.random.Random.nextDouble() },
    /**
     * 一条群消息处理完（无论回没回）之后的回调。
     *
     * 自主开口调度器用它做「消息后顺带评估」的触发点：内部应该延迟
     * 20~40 秒再评估，不要在这里做任何耗时操作——它跑在消息协程的收尾。
     */
    private val onGroupMessageHandled: ((String) -> Unit)? = null,
) : SimpleGatewayListener() {
    companion object {
        private const val TAG = "Runtime"
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

        /** 内心戏留存上限：足够看出态度，又不至于让一条记录吃掉几 KB */
        private const val MAX_THOUGHT_CHARS = 600

        /** 自动催场后重新开始计时的等待窗口 */
        private const val PROMPT_WAIT_MILLIS = 20_000L
    }

    /** 机器人自身 openid，用于剥离消息里的 @ 前缀。 */
    @Volatile
    var botId: String = ""
        private set

    @Volatile
    var botName: String = ""
        private set

    private var started = false

    /**
     * 最近处理过的消息 id。
     *
     * @ 消息可能同时以 GROUP_AT_MESSAGE_CREATE 与 GROUP_MESSAGE_CREATE 推送，
     * 用一个小 LRU 去重，避免同一条消息被回复两次。
     */
    private val seenMessageIds = ArrayDeque<String>()
    private val seenMessageIdSet = HashSet<String>()
    private val seenLock = Any()

    /**
     * 本群最近一次开口时间（chime 与自主共用）。
     *
     * 调度器据此避免「chime 刚回完，自主又接一句」——两条链路抢答
     * 是自主式最容易被群友骂的地方。
     */
    private val spokeAtMillis = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 正在生成回复的群。
     *
     * 从判定通过到真正发出去要过 AI 判断和拟人延迟（1-8 秒），这段时间里
     * 同群再进来一条消息，如果只看「上次发言时间」，它会以为"我还没说话"，
     * 于是又回一句——同一秒两条消息各回一次，就是双回。
     *
     * 用 putIfAbsent 占位：后来的消息看到已占位就放弃这一轮。
     */
    private val generatingGroups = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 正在等冷却的群。
     *
     * 冷却改成「等一会儿再判定」之后，刷屏的群会同时挂起一串等待协程；
     * 同群只保留一条，等完重判一次就够。
     */
    private val deferringGroups = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 本群机器人最后一条消息 id。
     *
     * 催场必须带 `msg_id` 走被动回复，否则算主动消息、会被额度与开关拦住
     * （任务书坑清单第 2 条）。
     */
    private val lastSentId = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 有人插话时的名字，下一次聊到我时注入场景块，用完即清。 */
    private val pendingInterruption = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 已自动催场几次（按场景）；对方接上就清零。 */
    private val scenePrompts = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    /** 启动运行时。会同时拉起网关连接。 */
    fun start() {
        if (started) return
        started = true
        log(LogLevel.INFO, TAG, "机器人启动中")
        gateway.start()
    }

    fun stop() {
        if (!started) return
        started = false
        log(LogLevel.INFO, TAG, "机器人停止中")
        gateway.stop()
    }

    override fun onReady(
        sessionId: String,
        botUser: ReadyUser?,
    ) {
        botUser?.let {
            botId = it.id
            botName = it.username
        }
        log(LogLevel.INFO, TAG, "已上线：${botUser?.username ?: "未知"}(${botUser?.id ?: "-"})")
    }

    override fun onMessage(message: IncomingMessage) {
        val text = message.cleanText(botId)
        // 打上「本机收到」的时刻：拟人延迟从这一刻算起，不从生成完算。
        // 平台事件时间只是消息创建时间，跨机器时钟可能有偏差，不能直接用来计时。
        val received = message.copy(receivedAtMillis = System.currentTimeMillis())
        // @ 消息优先记账：随后到达的同 id 全量事件会被去重挡掉
        if (message.kind == MessageKind.GROUP_AT) rememberMessage(message.messageId)

        // 整条处理链放进协程：身份解析与记忆装载都是挂起函数（要查库），
        // 网关回调线程必须尽快返回，否则会堵塞后续事件读取。
        scope.launch {
            try {
                processMessage(received, text)
            } finally {
                // 自主开口的触发点：不管这条回没回，群里刚有过对话，
                // 就有"想一想要不要接一句"的素材。回调本身必须轻量。
                if (message.kind == MessageKind.GROUP_MESSAGE ||
                    message.kind == MessageKind.GROUP_AT ||
                    message.kind == MessageKind.GUILD
                ) {
                    message.targetId?.takeIf { it.isNotBlank() }?.let { groupId ->
                        runCatching { onGroupMessageHandled?.invoke(groupId) }
                    }
                }
            }
        }
    }

    /**
     * 群/好友生命周期通知（加群、退群、新成员入群、主动消息开关）。
     *
     * 这些事件不是消息，网关会走 onRawEvent 分发过来。
     * 全部处理都放进协程：要查库、可能要发问候消息。
     */
    override fun onRawEvent(
        eventType: String,
        payload: JsonObject,
    ) {
        val notice =
            parseGroupNotice(eventType, payload) ?: return
        scope.launch { handleGroupNotice(notice) }
    }

    private suspend fun handleGroupNotice(notice: com.qbot.core.model.GroupNotice) {
        val pipeline = memoryPipeline ?: return
        pipeline.handleGroupNotice(notice) { groupId ->
            // 被拉进群：按设置决定是否发一句自我介绍（主动消息，消耗配额）
            val greeting = greetingTextProvider?.invoke()?.trim()
            if (greeting.isNullOrEmpty()) {
                log(LogLevel.INFO, TAG, "已加入群 $groupId（未开启问候语）")
                return@handleGroupNotice
            }
            val profile = runCatching { pipeline.groupProfile(groupId) }.getOrNull()
            if (profile?.proactiveAllowed == false) {
                log(LogLevel.INFO, TAG, "已加入群 $groupId（群已关闭主动消息，跳过问候）")
                return@handleGroupNotice
            }
            runCatching {
                api.sendGroupMessage(groupId, greeting)
                log(LogLevel.INFO, TAG, "已加入群 $groupId → 已发送问候语")
            }.onFailure { t ->
                log(LogLevel.WARN, TAG, "问候语发送失败：${t.message}")
            }
        }
    }

    private suspend fun processMessage(
        message: IncomingMessage,
        text: String,
    ) {
        // 单聊/频道私信没有「先 @ 再全量」的双事件窗口，必须自己挡重复投递，
        // 否则网关重连补发一次消息就会回两遍。群消息的去重在 handleChitchat 里。
        if (message.kind == MessageKind.C2C || message.kind == MessageKind.DIRECT) {
            if (isMessageSeen(message.messageId)) return
            rememberMessage(message.messageId)
        }

        // 学梗：丢到后台，不占这轮回复的时间
        slangLearner?.let { learner ->
            scope.launch { runCatching { learner.maybeLearn(message, text) } }
        }

        // 收表情包：群友发的图捡进库，之后回消息时可以用。
        // 自己的图不收——机器人发出去的原样存回来没有意义。
        val imageUrl = message.imageUrl
        if (!imageUrl.isNullOrBlank() && memeHarvester != null && message.senderId != botId) {
            scope.launch { runCatching { memeHarvester.harvest(imageUrl) } }
        }

        // 群内普通消息（非 @）走单独的"旁听"链路：
        // 先记进群记忆，再由插话策略决定要不要接一句。
        // 它不进入指令、规则、默认 AI 回复路径——否则群里每句话都会被回。
        if (message.kind == MessageKind.GROUP_MESSAGE) {
            handleChitchat(message, text)
            return
        }

        // @ 与频道消息同样要进群上下文。
        //
        // 过去只有"旁听"链路会写 room_messages，于是 @ 过机器人、最终却没回成的消息
        // 在后续判断与回复的上下文里**完全不存在**——群聊语境总是缺一块。
        // 用户看到的现象就是"它好像没看见别人刚刚叫过它"。
        if (message.kind == MessageKind.GROUP_AT || message.kind == MessageKind.GUILD) {
            memoryPipeline?.recordChitchat(
                message = message,
                cleanText = text,
                isAt = message.kind == MessageKind.GROUP_AT,
            )
        }

        // 先认出是谁：群聊里每个人有独立的记忆线程与展示名。
        // 解析失败（无记忆管线/未启用）时回退到 senderId 尾段，日志里始终能区分人。
        val label = memoryPipeline?.resolveLabel(message, text)
        val who = label ?: message.senderId?.takeLast(6) ?: "匿名"
        log(LogLevel.DEBUG, TAG, "收到[${message.kind}] $who: $text")

        // 群内互动记账：把发送者记入「本群见过的人」。
        // 不论这条消息最终走规则、记忆指令还是 AI，样本都不能漏。
        memoryPipeline?.noteGroupActivity(message)

        // 群昵称线索：@ 消息的 mentions 里可能带着机器人自己的 username，
        // 那往往就是群名片——这是除「叫你X」外唯一的来源。
        learnBotName(message, botId)

        // 关系记账：被 @ 与被夸。放在这里而不是回复成功后——
        // 被骂了但没回，同样改变关系。
        noteRelation(message, text)
        noteInsult(message, text)

        // 记忆/群管理指令（我的记忆 / 忘掉X / 我是谁 / 叫你X / 群里都有谁）
        // 优先于规则与 AI：副作用是本地数据库操作，不依赖 AI 开关，
        // 且比任何规则都更具体。回复分两层：人设复述 → 固定句式兜底。
        val pipeline = memoryPipeline
        val outcome = pipeline?.handleMemoryCommand(message, text)
        if (outcome != null) {
            replyWithCommandOutcome(pipeline, message, outcome, text)
            return
        }

        val aiConfig = aiConfigProvider?.invoke()
        val aiEnabled = aiConfig != null && aiConfig.enabled && aiConfig.isReady
        val isAiCommand =
            text.startsWith(AiResponder.COMMAND_PREFIX, ignoreCase = true)

        // AI_ALWAYS 模式下规则引擎整体让位给 AI；
        // COMMAND_ONLY 模式下 /ai 开头的消息也应绕过规则，否则用户没法"人工指定"问 AI。
        val skipRules =
            aiEnabled &&
                (
                    aiConfig?.mode == AiMode.AI_ALWAYS ||
                        (aiConfig?.mode == AiMode.COMMAND_ONLY && isAiCommand)
                )

        val rule = if (skipRules) null else ruleEngine.match(message, botId)

        if (rule != null) {
            replyWithRule(message, rule, text)
            return
        }

        // 明确要一张表情包：本地直接挑图，不为此再花一次模型调用。
        // 库里没有图时返回 false，落到下面的 AI 链路，让它按人设回应。
        if (MemeIntent.isRequest(text) && replyWithMeme(message, text)) return

        if (aiResponder != null && aiConfig != null && aiEnabled &&
            aiResponder.shouldHandle(message, aiConfig, ruleMatched = false)
        ) {
            replyWithAi(message, aiConfig, text)
            return
        }

        // 规则未命中且 AI 未启用/不处理时静默丢弃，避免对每条消息都回一句
        log(LogLevel.DEBUG, TAG, "无匹配规则，忽略")
    }

    /** 当前场景开关。未配置时视为 OFF。 */
    private fun sceneMode(): SceneMode = sceneModeProvider?.invoke() ?: SceneMode.OFF

    /**
     * 本轮要不要把 `wait / prompt / end` 三个动作工具发给模型。
     *
     * - OBSERVE：全发。第一期就是要看模型会怎么用（包括误用），
     *   工具只落表、不产生行为；
     * - ACTIVE：有进行中的场景，或刚结束不到 3 分钟（还在说收尾的话）。
     */
    private suspend fun sceneToolsVisible(
        groupId: String,
        scene: Scene?,
    ): Boolean {
        return when {
            sceneMode() == SceneMode.OFF -> false
            sceneMode() == SceneMode.OBSERVE -> true
            scene != null -> true
            else -> {
                val endedAt = runCatching { sceneStore?.lastEndedAt(groupId) }.getOrNull()
                endedAt != null &&
                    System.currentTimeMillis() - endedAt < SceneLimits.RECENT_SCENE_WINDOW_MILLIS
            }
        }
    }

    /** 一次场景查询的结论。 */
    private data class SceneLook(
        val scene: Scene,
        /** 本条消息是不是场景里"该我接"的那一句 */
        val mine: Boolean,
        /** 等对方期间收到别人的话：本条不生成回复 */
        val ignore: Boolean,
    )

    /**
     * 查本群进行中的场景，判断本条消息属于哪种情况。
     *
     * 四条分支与任务书第四节一一对应：
     * - 无场景 / 开关 OFF → 返回 null，判定链完全走原样（2.12.0 行为）；
     * - 等对方期间别人说话 → [SceneLook.ignore]，不生成；
     * - 对方接话 → [SceneLook.mine]，跳过 chime 直接生成；
     * - 其他参与者/第三方 → mine=false，走原判定链并记一条插话信号。
     */
    private suspend fun sceneLook(
        message: IncomingMessage,
        text: String,
    ): SceneLook? {
        val store = sceneStore ?: return null
        if (sceneMode() == SceneMode.OFF) return null
        val groupId = message.targetId?.takeIf { it.isNotBlank() } ?: return null
        val selfId = botId
        if (selfId.isBlank()) return null
        if (message.senderId == selfId) return null

        var scene = runCatching { store.active(groupId) }.getOrNull()
        if (scene == null || scene.status == SceneStatus.ENDED) {
            pendingInterruption.remove(groupId)
            return null
        }
        val now = System.currentTimeMillis()
        if (scene.isExpired(now)) {
            endScene(scene, now, "超过单场景最长时长")
            return null
        }

        val label = runCatching { memoryPipeline?.resolveLabel(message, text) }.getOrNull()
        // 认领占位对方：优先看名字（AI 从对话里读到的名字），
        // 名字对不上但对方也是机器人时也认——双 bot 对谈里这就是对方。
        val placeholder = scene.participants.firstOrNull { it.senderId.isBlank() }
        val senderId = message.senderId
        if (placeholder != null && senderId != null && senderId.isNotBlank()) {
            val byName = namesMatch(placeholder.displayName, message.senderName, label)
            if (byName || (message.senderIsBot && placeholder.isBot)) {
                scene =
                    scene.claimPartner(
                        senderId = senderId,
                        displayName = label ?: message.senderName,
                        isBot = message.senderIsBot,
                    )
                runCatching { store.save(scene) }
            }
        }

        val me = scene.participants.firstOrNull { it.senderId == selfId }
        if (me == null) {
            // 场景不是自己开的（理论上不会发生）：只记插话，不接管
            pendingInterruption[groupId] = label ?: "有人"
            return null
        }

        val partnerId = scene.partnerOf(selfId)?.senderId.orEmpty()
        val fromPartner = partnerId.isNotBlank() && partnerId == message.senderId
        val waiting = scene.status == SceneStatus.WAITING && (scene.waitUntil ?: 0L) > now
        if (waiting && !fromPartner) {
            // 等对方接话期间收到别人的话：不抢话，只更新活动时间并记下插话者
            pendingInterruption[groupId] = label ?: "有人"
            runCatching { store.save(scene.copy(lastActivityAt = now)) }
            return SceneLook(scene, mine = false, ignore = true)
        }

        if (!fromPartner) {
            // 轮次没到我，或第三方插话：交给原判定链（可能接话，也可能不接）
            pendingInterruption[groupId] = label ?: "有人"
            return SceneLook(scene, mine = false, ignore = false)
        }

        // 该我接了：先守单群每小时的场景发言上限
        val speaks =
            runCatching { store.speaksSince(groupId, now - 3_600_000L) }.getOrDefault(0)
        if (speaks >= SceneLimits.MAX_SPEAKS_PER_HOUR) {
            endScene(scene, now, "本群每小时场景发言已达上限")
            return null
        }
        return SceneLook(scene, mine = true, ignore = false)
    }

    /** 两个展示名是否指同一个人（短名不做包含匹配，避免"阿"命中所有人）。 */
    private fun namesMatch(
        target: String,
        vararg candidates: String?,
    ): Boolean {
        val t = target.trim()
        if (t.isBlank()) return false
        return candidates.any { raw ->
            val c = raw?.trim().orEmpty()
            when {
                c.isBlank() -> false
                c == t -> true
                t.length >= 2 && c.length >= 2 -> c.contains(t) || t.contains(c)
                else -> false
            }
        }
    }

    /** 结束一段场景，并留下可复盘的记录。 */
    private suspend fun endScene(
        scene: Scene,
        now: Long,
        reason: String,
    ) {
        val store = sceneStore ?: return
        runCatching { store.end(scene.id, now) }
        runCatching {
            store.record(
                SceneDecisionRecord(
                    sceneId = scene.id.takeIf { it > 0L },
                    groupId = scene.groupId,
                    action = SceneAction.END,
                    executed = true,
                    reason = reason,
                    createdAt = now,
                ),
            )
        }
        scenePrompts.remove(scene.id)
        pendingInterruption.remove(scene.groupId)
        sceneToolbox?.forget(scene.id)
    }

    /** 我接了一句之后推进轮次：话头交回对方，等待状态清空。 */
    private suspend fun advanceScene(
        scene: Scene,
        now: Long,
    ) {
        val store = sceneStore ?: return
        val partnerId = scene.partnerOf(botId)?.senderId.orEmpty()
        scenePrompts.remove(scene.id)
        pendingInterruption.remove(scene.groupId)
        sceneToolbox?.onPartnerSpoke(scene.id)
        val next =
            scene.copy(
                status = SceneStatus.ACTIVE,
                turnIndex = scene.turnIndex + 1,
                expectedSpeakerId = partnerId,
                waitUntil = null,
                lastActivityAt = now,
            )
        runCatching { store.save(next) }
        runCatching {
            store.record(
                SceneDecisionRecord(
                    sceneId = scene.id,
                    groupId = scene.groupId,
                    action = SceneAction.SPEAK,
                    executed = true,
                    reason = "场景内第 ${next.turnIndex} 轮",
                    createdAt = now,
                ),
            )
        }
    }

    /**
     * 场景巡检：超时收尾 + 等待过期催场。
     *
     * 由外部定时器调用（app 侧 15 秒一次）。不能靠 `delay` 挂起等待——
     * 那会把消息协程拖住（任务书坑清单第 1 条）。
     */
    suspend fun sceneTick() {
        val store = sceneStore ?: return
        if (sceneMode() != SceneMode.ACTIVE) return
        val now = System.currentTimeMillis()
        val scenes = runCatching { store.activeScenes() }.getOrDefault(emptyList())
        scenes.forEach { scene ->
            if (scene.isExpired(now)) {
                endScene(scene, now, "超过单场景最长时长")
                return@forEach
            }
            if (!scene.waitExpired(now)) return@forEach

            val count = (scenePrompts[scene.id] ?: 0) + 1
            if (count > SceneLimits.MAX_PROMPTS) {
                endScene(scene, now, "连续 ${SceneLimits.MAX_PROMPTS} 次催场无回应")
                return@forEach
            }
            if (!sendScenePrompt(scene, promptLine(scene))) {
                endScene(scene, now, "催场发送失败（被动回复窗口已过）")
                return@forEach
            }
            scenePrompts[scene.id] = count
            runCatching {
                store.save(
                    scene.copy(
                        status = SceneStatus.WAITING,
                        waitUntil = now + PROMPT_WAIT_MILLIS,
                        lastActivityAt = now,
                    ),
                )
            }
            runCatching {
                store.record(
                    SceneDecisionRecord(
                        sceneId = scene.id,
                        groupId = scene.groupId,
                        action = SceneAction.PROMPT,
                        executed = true,
                        reason = "等待超时，自动催场",
                        createdAt = now,
                    ),
                )
            }
        }
    }

    /** 催场话术：由我（机器人）说，不引用别人的话。 */
    private fun promptLine(scene: Scene): String {
        val partner = scene.participants.firstOrNull { it.senderId == scene.expectedSpeakerId }
        val name = partner?.displayName?.takeIf { it.isNotBlank() }
        return if (name != null) "该你了，$name——接着说。" else "该你了，接着往下说。"
    }

    /** 催场必须引用自己上一条消息；拿不到 msg_id 就失败，由调用方收尾。 */
    private suspend fun sendScenePrompt(
        scene: Scene,
        line: String,
    ): Boolean {
        val replyTo = lastSentId[scene.groupId] ?: return false
        return runCatching {
            val result =
                api.sendGroupMessage(
                    groupOpenId = scene.groupId,
                    content = line,
                    replyMessageId = replyTo,
                )
            if (result.isSuccess) {
                result.id?.let { lastSentId[scene.groupId] = it }
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    /**
     * 群内普通消息的"旁听"处理。
     *
     * 与 @ 消息链路的区别：
     * 1. 每条消息都记进群记忆（默认留 200 条）并学习昵称/事实，无论是否回复；
     * 2. 回复是可选的，由三级漏斗决定：本地规则 → 概率 → AI 判断；
     * 3. 不进入指令与规则引擎——群里有人随口说「你叫什么」不该触发改名逻辑。
     */
    private suspend fun handleChitchat(
        message: IncomingMessage,
        text: String,
    ) {
        val pipeline = memoryPipeline ?: return
        val groupId = message.targetId?.takeIf { it.isNotBlank() } ?: return
        val selfId = botId
        if (selfId.isBlank()) return

        // 自己的消息不处理
        if (message.senderId == selfId) return
        // 只有图片、没有文字也要处理：群里发张图配个表情，是常见表达
        if (text.isBlank() && message.imageUrl == null) return
        if (isMessageSeen(message.messageId)) return
        rememberMessage(message.messageId)

        // 1) 先判断，再带着结论入库。
        //    这样维护端的群消息查看器能直接看到"这条为什么没回"，
        //    而不是只有一句"收到了"。
        val addressed = mentionsBot(message, selfId)
        val chitchatConfig =
            if (addressed) null else chitchatConfigProvider?.invoke()?.takeIf { it.enabled }

        // 记忆与活跃度先记账：首次全量消息可能早于加群事件到达，
        // 不能因为档案还没建就把这条消息（可能正在叫名字）整个丢掉。
        pipeline.noteGroupActivity(message)
        pipeline.resolveLabel(message, text)

        // 群档案：没有就现建；群记忆整体关闭（无群仓库）时允许没有档案，
        // 名字匹配回落到官方名——之前这里会整条跳过，是"叫名字不回"的根因之一。
        val profile =
            if (addressed || chitchatConfig == null) {
                null
            } else {
                pipeline.groupProfile(groupId) ?: pipeline.ensureGroupProfile(groupId)
            }

        // 人设：性格偏置（规则层）+ AI 判断的人格注入（模型层）
        val personaPrompt = personaPromptProvider?.invoke(message)
        val personaBias = PersonaBias.personaBiasFor(personaPrompt)

        // 上下文：最近几条群消息——打分（话题延续/回应机器人/冷场）与
        // AI 判断共用同一份，本条消息尚未入库所以天然不含自身
        // 判断上下文条数可配：快群 8 条只够几秒钟，模型看到的是断章
        val recent = pipeline.recentRoomMessages(message, chitchatConfig?.judgeContextSize ?: 8)

        val mentionIds = mentionIdsOf(message)
        // 名字来源三处：本群群名片、官方 username、人设正文里的自称
        // （群名片叫「过去的阿远」，角色卡写"你叫阿远"，群友喊的是后一个）。
        val names =
            buildList {
                profile?.botName?.takeIf { it.isNotBlank() }?.let(::add)
                botName.takeIf { it.isNotBlank() }?.let(::add)
                addAll(BotNameMatcher.namesFromPrompt(personaPrompt.orEmpty()))
            }.distinct()
        // 被指名冒犯：名字（含后缀）+ 侮辱词同时成立才算。
        // 侮辱词 = 内置表 + 用户在设置里追加的自定义词（语言黑话层出不穷，
        // 用户把自己群里实际出现的词加进来，判定才准）。
        val extraInsultWords = chitchatConfig?.insultExtraWords ?: emptyList()
        val insulted =
            !addressed &&
                insultDetector.isInsult(text, extraInsultWords) &&
                insultDetector.targetsBot(text, names, extraInsultWords)
        val talkingToOthers = insultDetector.talksToOthers(mentionIds, selfId)
        if (insulted) {
            // 旁听链路的冒犯由这里记：入口的 noteInsult 只处理 @ / 私聊，
            // 两边都记会重复扣分。
            runCatching { relationRecorder?.record(message, RelationEvent.INSULTED, "他在群里骂了你") }
        }

        // 这句话大概率是对谁说的：人说话是有对象的，
        // 「两人互道晚安」和「有人吗」该不该接完全不同。
        val addressee = AddresseeDetector.infer(message, recent, names, selfId)

        // 场景优先于单条打分：只要有进行中的对谈且轮到我，就不再问"值不值得接"。
        // 开关默认 OFF，此时 sceneLook 直接返回 null，下面与 2.12.0 完全一致。
        val sceneLook = sceneLook(message, text)

        // 抽成函数是为了"等冷却之后重判一次"——等待期间可能已经有人回了话，
        // 或者对方已经划界，直接复用旧结论会答错。
        suspend fun evaluateGate(): ChitchatGate =
            when {
                // 同群正在生成上一条：不是"不该说"，是"轮不到这一条"。
                // 等一小会儿再判，避免同一秒的两条消息各回一次。
                generatingGroups.contains(groupId) ->
                    ChitchatGate.Defer("正在回上一条", 1_200L)
                sceneLook?.mine == true -> ChitchatGate.Forced("场景轮次：该我接")
                addressed -> ChitchatGate.Skip("@ 了机器人，交给 @ 链路")
                chitchatConfig == null -> ChitchatGate.Skip("群聊参与未开启")
                profile?.active == false -> ChitchatGate.Skip("群档案不可用")
                else -> {
                    // 活跃度统计对所有消息生效（热群/冷场的自适应依赖它）
                    chitchatPolicy.observe(groupId)
                    chitchatPolicy.gate(
                        groupId = groupId,
                        text = text,
                        botNames = names,
                        config = chitchatConfig,
                        insulted = insulted,
                        talkingToOthers = talkingToOthers,
                        personaBias = personaBias,
                        recent = recent,
                    )
                }
            }

        var gate = evaluateGate()
        if (gate is ChitchatGate.Defer) {
            val pending = gate as ChitchatGate.Defer
            gate =
                when {
                    // 冷却几十秒时等到也没意义：那时话题早换了，回过去反而突兀
                    pending.delayMillis > ChitchatGate.MAX_DEFER_MILLIS ->
                        ChitchatGate.Skip(
                            "冷却 ${pending.delayMillis / 1000} 秒太久，等到话题也过去了",
                        )
                    !deferringGroups.add(groupId) ->
                        ChitchatGate.Skip("已有消息在等冷却")
                    else -> {
                        try {
                            log(
                                LogLevel.VERBOSE,
                                TAG,
                                "等 ${pending.delayMillis}ms 再判定（${pending.reason}）",
                            )
                            // 多等 80ms：冷却按毫秒比，边界上容易差一两毫秒又判成冷却中
                            delay(pending.delayMillis + 80)
                            val again = evaluateGate()
                            if (again is ChitchatGate.Defer) {
                                ChitchatGate.Skip("等待后仍在冷却（${again.reason}）")
                            } else {
                                again
                            }
                        } finally {
                            deferringGroups.remove(groupId)
                        }
                    }
                }
        }

        // 规则层已经认定「这是在叫它 / 在骂它」时，对话对象必须跟着一致。
        // 否则会出现自相矛盾：它明明照着"叫名字必答"回了话，
        // 复盘却记成"对全群说"——因为两处用的是不同判定（审计发现）。
        val finalAddressee =
            when {
                sceneLook != null -> {
                    val partner = sceneLook.scene.partnerOf(selfId)
                    Addressee(
                        AddresseeKind.MEMBER,
                        partner?.displayName,
                        "场景进行中",
                    )
                }

                gate is ChitchatGate.Forced && addressee.kind == AddresseeKind.GROUP ->
                    Addressee(AddresseeKind.BOT, null, gate.reason)

                else -> addressee
            }

        pipeline.recordChitchat(
            message = message,
            cleanText = text,
            isAt = addressed,
            decision = gate.describe(),
        )
        learnBotName(message, selfId)

        // 场景正在等对方：本条只入群记忆，不抢话。
        // （等对方接话的窗口里，别人插的话不该由我接，否则就成了打断场景）
        if (sceneLook?.ignore == true) {
            pipeline.markChitchatDecision(message, "场景等待中，本条不接")
            log(LogLevel.VERBOSE, TAG, "场景等待中，忽略本条")
            return
        }

        // @ 自己的消息交给 @ 链路应答，这里只记不说
        if (addressed) return

        // 2) 三级漏斗的后两级
        // 场景轮到我了：直接生成，不因为"群聊参与未开启"被挡回去——
        // 场景是用户显式开启的状态机，优先级高于概率漏斗。
        val config =
            if (sceneLook?.mine == true) {
                chitchatConfigProvider?.invoke() ?: return
            } else {
                chitchatConfig ?: return
            }

        // 训练闭环：整个判断过程只在这里落一条记录，无论从哪个分支返回。
        // 用 finally 而不是在每个 return 前写库——既不会漏记，也是未来把
        // sink 换成事件总线（插件化）的前提。
        var aiVerdict: String? = null
        var aiReason: String? = null
        var action = ChimeDecisionRecord.ACTION_SILENT
        var finalReason = gate.describe()
        var replyText: String? = null
        // 本次判断实际注入了哪些经验：只有真的接话才计一次「被采纳」，
        // 单纯问过模型不算（否则错判经验也会显得很有用，见审计 B4）。
        var injectedLessons: List<ChimeLesson> = emptyList()
        val gatePath =
            when (gate) {
                is ChitchatGate.Forced -> ChimeDecisionRecord.GATE_FORCED
                is ChitchatGate.Candidate -> ChimeDecisionRecord.GATE_CANDIDATE
                // 上面的等待逻辑已经把 Defer 消化掉了；走到这里只可能是
                // 等待失败后的兜底，按跳过记账即可。
                is ChitchatGate.Defer -> ChimeDecisionRecord.GATE_SKIP
                is ChitchatGate.Skip -> ChimeDecisionRecord.GATE_SKIP
            }
        val ruleScore = (gate as? ChitchatGate.Candidate)?.score

        // 占位：从这一刻到发出去之间（AI 判断 + 拟人延迟）不再接同群的其他消息。
        // 只有真的准备回才占；Skip 不占，否则会把正常聊天挡掉 1-8 秒。
        var ownsInFlight = false
        if (gate is ChitchatGate.Forced || gate is ChitchatGate.Candidate) {
            if (generatingGroups.add(groupId)) {
                ownsInFlight = true
            } else {
                gate = ChitchatGate.Skip("正在回上一条")
            }
        }

        try {
            val why =
                when (val current = gate) {
                    is ChitchatGate.Skip -> {
                        log(LogLevel.VERBOSE, TAG, "群聊消息不接：${current.reason}")
                        return
                    }

                    // 不该出现：等待逻辑已经消化掉了
                    is ChitchatGate.Defer -> {
                        log(LogLevel.VERBOSE, TAG, "群聊消息仍待定：${current.reason}")
                        return
                    }

                    is ChitchatGate.Forced -> current.reason

                    is ChitchatGate.Candidate -> {
                        if (config.aiJudge) {
                            // AI 主导：每条候选都问模型，不再按概率抽样
                            val aiCfg = aiConfigProvider?.invoke()
                            val responder = aiResponder
                            if (aiCfg == null || responder == null ||
                                !aiCfg.enabled || !aiCfg.isReady
                            ) {
                                finalReason = "不接：AI 未配置"
                                pipeline.markChitchatDecision(message, finalReason)
                                return
                            }
                            // 经验注入：群级优先、全局兜底；开启人设隔离时只取
                            // 当前人格的专属经验 + 通用经验。失败不影响判断。
                            val lessons =
                                runCatching {
                                    chimeTrainer?.lessonsForPrompt(
                                        groupId,
                                        config.maxInjectedLessons,
                                        pipeline.currentPersonaId(message),
                                    ) ?: emptyList()
                                }.getOrDefault(emptyList())
                            // 关系会影响"想不想接"：熟人多搭两句，被伤过会犹豫。
                            // 查不到就当没有——判断不能因为关系表抖动而停摆。
                            val social =
                                runCatching { socialProvider?.provide(message) }.getOrNull()
                            var judgeFailure: Throwable? = null
                            val verdict =
                                runCatching {
                                    responder.wantsToChimeIn(
                                        message,
                                        aiCfg,
                                        recent,
                                        profile?.botName,
                                        personaPrompt,
                                        lastBotSpokeAgoMillis =
                                            chitchatPolicy.lastSpokeAgoMillis(groupId),
                                        addressee = finalAddressee,
                                        behavior =
                                            BehaviorContext(
                                                userRules = config.chimeRules,
                                                // 带上类别标签，模型能看出这几条各自管什么场景
                                                lessons =
                                                    lessons.map {
                                                        "【${LessonCategories.label(it.category)}】${it.text}"
                                                    },
                                                relation = social?.relation,
                                            ),
                                    )
                                }.onFailure { judgeFailure = it }.getOrNull()
                            // 判断没跑起来（网络/限流/服务端错误）按不接处理，
                            // 但原因必须留下——过去一律吞成"AI 判断失败"，
                            // 用户和日志都无从判断是超时还是 429。
                            if (verdict == null) {
                                val why = describeFailure(judgeFailure)
                                finalReason = "不接：AI 判断失败（$why）"
                                pipeline.markChitchatDecision(message, finalReason)
                                log(LogLevel.WARN, TAG, "群聊消息不接：AI 判断失败（$why）")
                                return
                            }
                            aiVerdict = if (verdict.wants) "YES" else "NO"
                            aiReason = verdict.reason
                            // 先记下被参考过的经验；等真的说出来再计一次采纳
                            injectedLessons = lessons
                            if (!verdict.wants) {
                                finalReason = "不接：AI 判断不需要接话"
                                pipeline.markChitchatDecision(message, finalReason)
                                log(LogLevel.VERBOSE, TAG, "群聊消息不接：AI 判断不需要接话")
                                return
                            }
                            "AI 判断接话"
                        } else {
                            // 规则主导：抽样概率随人设性格浮动（话痨 ×1.4，高冷 ×0.7）
                            if (random() > (config.probability * personaBias.probabilityFactor).coerceAtMost(1.0)) {
                                finalReason = "不接：概率未命中"
                                pipeline.markChitchatDecision(message, finalReason)
                                log(LogLevel.VERBOSE, TAG, "群聊消息不接：概率未命中")
                                return
                            }
                            "规则 ${current.score} 分"
                        }
                    }
                }

            // 3) 回复：人设 + 关于此人的事实 + 整个群最近的聊天
            val aiConfig = aiConfigProvider?.invoke()
            val responder = aiResponder
            if (aiConfig == null || responder == null || !aiConfig.enabled || !aiConfig.isReady) {
                finalReason = "不接：AI 未配置"
                pipeline.markChitchatDecision(message, finalReason)
                return
            }

            runCatching {
                // 被指名冒犯时告诉模型"你刚被骂了"，人设才知道该反应而不是装没看见
                val insult =
                    if (insulted) {
                        InsultContext(
                            byWhom = runCatching { pipeline.resolveLabel(message, text) }.getOrNull(),
                            quote = text,
                        )
                    } else {
                        null
                    }
                val memory = buildMemory(message, text, insult)
                // 场景只改"要不要说"，不改"以什么身份说"：记忆/关系照常注入
                // （任务书坑清单第 8 条）。
                val currentScene = sceneLook?.scene
                val turn =
                    ToolTurn(
                        groupId = groupId,
                        senderId = message.senderId,
                        senderName = message.senderName,
                        messageId = message.messageId,
                        isGroup = true,
                        nowMillis = System.currentTimeMillis(),
                        scene = currentScene,
                        sceneEnabled = sceneMode() != SceneMode.OFF,
                        sceneToolsVisible = sceneToolsVisible(groupId, currentScene),
                    )
                val sceneBlock =
                    currentScene?.let {
                        ScenePrompt.block(
                            it,
                            selfId,
                            interruptedBy = pendingInterruption.remove(groupId),
                        )
                    }
                val answer =
                    responder.replyDetailed(
                        message,
                        aiConfig,
                        memory,
                        sceneBlock = sceneBlock,
                        toolTurn = turn,
                    )
                if (answer.content.isBlank() && answer.memeUrl.isNullOrBlank()) {
                    finalReason = "不接：回复为空"
                    return@runCatching
                }
                sendReply(message, answer.content, answer.memeUrl)
                if (sceneLook?.mine == true) {
                    advanceScene(currentScene!!, System.currentTimeMillis())
                }
                // 经验真正被采纳：这一轮是看了这些经验才决定接的
                if (injectedLessons.isNotEmpty()) {
                    runCatching { chimeTrainer?.bumpHits(injectedLessons.map { it.id }) }
                }
                // 内心戏：说出口的已经发出去了，没说出口的落在这里
                recordThought(message, answer)
                chitchatPolicy.markSpoke(groupId)
                spokeAtMillis[groupId] = System.currentTimeMillis()
                // 机器人这条已由 sendReply 统一写入群上下文
                pipeline.markChitchatDecision(message, "已接话（$why）")
                action = ChimeDecisionRecord.ACTION_SPOKE
                replyText = answer.content.ifBlank { "（表情包）" }
                finalReason = "已接话（$why）"
                log(LogLevel.INFO, TAG, "群聊插话（$why，${answer.content.length} 字）")
            }.onFailure { t ->
                finalReason = "不接：回复失败"
                pipeline.markChitchatDecision(message, finalReason)
                logger.e(TAG, "群聊插话失败", t)
                log(LogLevel.WARN, TAG, "群聊插话失败：${t.message}")
            }
        } finally {
            // 先释放占位，再写复盘记录——否则复盘写库慢的时候，
            // 同群的新消息会一直被这个标记挡住。
            if (ownsInFlight) generatingGroups.remove(groupId)
            // 训练闭环的单一入口：成功 / 失败 / 提前返回都记一条
            chimeTrainer?.let { trainer ->
                runCatching {
                    trainer.onDecision(
                        ChimeDecisionRecord(
                            groupId = groupId,
                            speakerId = message.senderId,
                            speakerName = message.senderName,
                            messageText = text.take(200),
                            contextSnippet = buildContextSnippet(recent),
                            ruleScore = ruleScore,
                            gatePath = gatePath,
                            aiVerdict = aiVerdict,
                            aiReason = aiReason,
                            action = action,
                            reason = finalReason,
                            replyText = replyText?.take(200),
                            addressed = addressed,
                            insulted = insulted,
                            addresseeKind = finalAddressee.kind.name,
                            addresseeName = finalAddressee.name,
                            personaId = pipeline.currentPersonaId(message),
                            externalId = message.messageId,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    /** 把最近几条群消息压成一段转录，供复盘页还原"当时的局面"。 */
    private fun buildContextSnippet(recent: List<RoomMessage>): String =
        TranscriptRender
            .render(
                recent.takeLast(8).map { msg ->
                    val who =
                        msg.senderName?.takeIf { it.isNotBlank() }
                            ?: if (msg.role == "assistant") botName.ifBlank { "机器人" } else "某人"
                    // 机器人自己的行统一显示成「你」：复盘与反思都要能分清
                    // "它说过的" 和 "别人说过的"。
                    val speaker = if (msg.role == "assistant") "你" else who
                    TranscriptRender.Line(
                        who = speaker,
                        body = msg.content.take(60),
                        meta = if (msg.isAt) "@你" else null,
                    )
                },
            ).take(400)

    /** 把判断失败的原因压成一句人话，写进日志与决策记录。 */
    private fun describeFailure(error: Throwable?): String {
        if (error == null) return "原因未知"
        val detail =
            when (error) {
                is LlmException -> error.statusCode?.let { "HTTP $it" } ?: error.message
                else -> error.message
            } ?: error::class.simpleName ?: "未知错误"
        return detail.replace("\n", " ").take(60)
    }

    /** 消息里是否 @ 了机器人（全量模式下 @ 消息也可能从这里进来）。 */
    private fun mentionsBot(
        message: IncomingMessage,
        selfId: String,
    ): Boolean {
        if (message.content.contains("<@!$selfId>")) return true
        return mentionIdsOf(message).contains(selfId)
    }

    /** 提取消息 @ 的所有用户 id（不同事件版本字段名不一致，逐个取）。 */
    private fun mentionIdsOf(message: IncomingMessage): List<String> {
        val mentions = message.raw["mentions"] as? JsonArray ?: return emptyList()
        return mentions.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            listOf("id", "member_openid", "user_openid", "union_openid")
                .firstNotNullOfOrNull { key -> obj[key]?.jsonPrimitive?.contentOrNull }
        }
    }

    /**
     * 从 mentions 里学习机器人在本群的称呼。
     *
     * mentions 中被 @ 的用户带 username，若自己也在其中，那个名字通常就是
     * 群名片（与官方 username 不同）。用户手动设置过则不会被覆盖。
     */
    private suspend fun learnBotName(
        message: IncomingMessage,
        selfId: String,
    ) {
        if (selfId.isBlank()) return
        val groupId = message.targetId?.takeIf { it.isNotBlank() } ?: return
        val mentions = message.raw["mentions"] as? JsonArray ?: return
        val self =
            mentions.firstOrNull { el ->
                (el as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull == selfId
            } as? JsonObject ?: return
        val name = self["username"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        memoryPipeline?.observeBotName(groupId, name)
    }

    private fun rememberMessage(id: String?) {
        if (id.isNullOrBlank()) return
        synchronized(seenLock) {
            if (!seenMessageIdSet.add(id)) return
            seenMessageIds.addLast(id)
            while (seenMessageIds.size > 128) {
                seenMessageIdSet.remove(seenMessageIds.removeFirst())
            }
        }
    }

    private fun isMessageSeen(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        return synchronized(seenLock) { id in seenMessageIdSet }
    }

    /**
     * 指令结果回复：人设复述优先，固定句式兜底。
     *
     * 指令的副作用在 [MemoryPipeline.handleMemoryCommand] 里已本地完成，
     * 这里只决定「怎么说」：人设复述开着且 AI 可用，就让模型拿着
     * directive 里的事实结果自己说；否则直接发兜底文案——
     * 关掉 AI 或 API 抖动时指令功能不会退化。
     */
    private suspend fun replyWithCommandOutcome(
        pipeline: MemoryPipeline,
        message: IncomingMessage,
        outcome: CommandOutcome,
        text: String,
    ) {
        val responder = aiResponder?.takeIf { pipeline.currentConfig().personaRewrite }
        val aiConfig =
            aiConfigProvider?.invoke()?.takeIf { it.enabled && it.isReady }

        var reply = outcome.fallback
        var viaAi = false
        if (responder != null && aiConfig != null) {
            runCatching {
                // 复述同样要吃记忆上下文：人设提示词 + 关于此人的事实 +
                // 群画像，模型才知道「对方是谁、这群什么情况」。
                val memory = buildMemory(message, text)
                val answer =
                    responder.replyDetailed(
                        message,
                        aiConfig,
                        memory,
                        directive = outcome.directive,
                    )
                if (answer.content.isNotBlank()) {
                    reply = answer.content
                    viaAi = true
                    recordThought(message, answer)
                }
            }.onFailure { t ->
                logger.e(TAG, "指令人设复述失败，改用兜底文案", t)
                log(LogLevel.WARN, TAG, "指令人设复述失败，改用兜底文案：${t.message}")
            }
        }

        runCatching {
            sendReply(message, reply)
            if (isDirectChat(message)) pipeline.recordExchange(message, text, reply)
            log(
                LogLevel.INFO,
                TAG,
                if (viaAi) "记忆指令 → 人设复述已回复" else "记忆指令 → 兜底文案已回复",
            )
        }.onFailure { t ->
            logger.e(TAG, "记忆指令回复失败", t)
            log(LogLevel.ERROR, TAG, "记忆指令回复失败：${t.message}")
        }
    }

    /** 命中本地规则，按模板回复。这一轮也会写进共享群聊背景。 */
    private suspend fun replyWithRule(
        message: IncomingMessage,
        rule: Rule,
        text: String,
    ) {
        val reply = RuleEngine.render(rule.reply, message, botId)
        if (reply.isBlank()) return

        runCatching {
            sendReply(message, reply)
            if (isDirectChat(message)) memoryPipeline?.recordExchange(message, text, reply)
            log(LogLevel.INFO, TAG, "命中规则「${rule.name}」→ 已回复")
        }.onFailure { t ->
            logger.e(TAG, "回复失败", t)
            log(LogLevel.ERROR, TAG, "回复失败：${t.message}")
        }
    }

    /**
     * 明确索取表情包时，直接由本地图库挑一张。
     *
     * 这是「工具调用」之外的快路径：用户说"发个表情包"时，目的已经明确，
     * 不需要模型再判断一次心情。挑图失败（库空/上传失败）返回 false，
     * 让调用方回落到 AI——由人设说一句"我这儿没图"也比装死好。
     */
    private suspend fun replyWithMeme(
        message: IncomingMessage,
        text: String,
    ): Boolean {
        val library = memeLibrary ?: return false
        val url =
            runCatching { library.pick() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return false
        if (!sendMeme(message, url)) return false
        if (isDirectChat(message)) {
            memoryPipeline?.recordExchange(message, text, "（回了一张表情包）")
        }
        log(LogLevel.INFO, TAG, "按明确请求发送了一张表情包")
        return true
    }

    /** 交给 AI 生成回复，失败时按配置的兜底话术应答。 */
    private suspend fun replyWithAi(
        message: IncomingMessage,
        config: AiConfig,
        text: String,
    ) {
        runCatching {
            // @ 里直接骂人的（"你个傻逼"）也要让模型知道：这是冲你来的，
            // 不是旁听。判定比旁听链路宽——@ 了机器人，侮辱词大概率是说给它听的。
            // 自定义侮辱词同样参与判定（用户设置里追加的词）。
            val insult =
                if (insultDetector.isInsult(
                        text,
                        chitchatConfigProvider?.invoke()?.insultExtraWords ?: emptyList(),
                    ) &&
                    (text.contains("你") || text.contains("您"))
                ) {
                    InsultContext(
                        byWhom = memoryPipeline?.resolveLabel(message, text),
                        quote = text,
                    )
                } else {
                    null
                }
            // 组装这条消息的记忆上下文：展示名 / 关于此人的事实 / 所在范围的近期对话
            val memory = buildMemory(message, text, insult)
            // @ 链路也要带场景：对方 bot 可能 @ 你接话，这时它仍然在场景里。
            val scene = sceneLook(message, text)
            val groupId = message.targetId.orEmpty()
            val turn =
                ToolTurn(
                    groupId = message.targetId,
                    senderId = message.senderId,
                    senderName = message.senderName,
                    messageId = message.messageId,
                    isGroup = true,
                    nowMillis = System.currentTimeMillis(),
                    scene = scene?.scene,
                    sceneEnabled = sceneMode() != SceneMode.OFF,
                    sceneToolsVisible = sceneToolsVisible(groupId, scene?.scene),
                )
            val sceneBlock =
                scene?.scene?.let {
                    ScenePrompt.block(
                        it,
                        botId,
                        interruptedBy = pendingInterruption.remove(groupId),
                    )
                }
            val answer =
                aiResponder!!.replyDetailed(
                    message,
                    config,
                    memory,
                    sceneBlock = sceneBlock,
                    toolTurn = turn,
                )
            sendReply(message, answer.content, answer.memeUrl)
            if (scene?.mine == true) {
                advanceScene(scene.scene, System.currentTimeMillis())
            }
            recordThought(message, answer)
            if (isDirectChat(message)) {
                memoryPipeline?.recordExchange(message, text, answer.content)
            }
            // 用量日志：缓存命中不仅省钱，首 token 延迟也大幅降低，
            // 命中率长期为 0 说明 system 提示词不稳定，值得排查。
            val usage = aiResponder.lastUsage.value
            if (usage != null && usage.promptTokens > 0) {
                val hitPercent = (usage.cacheHitRatio ?: 0f) * 100
                log(
                    LogLevel.INFO,
                    TAG,
                    "AI 已回复（${answer.content.length} 字）｜prompt ${usage.promptTokens} tokens" +
                        "（缓存命中 ${usage.cacheHitTokens}，${hitPercent.toInt()}%）" +
                        "｜completion ${usage.completionTokens} tokens",
                )
            } else {
                log(LogLevel.INFO, TAG, "AI 已回复（${answer.content.length} 字）")
            }
        }.onFailure { t ->
            logger.e(TAG, "AI 回复失败", t)
            log(LogLevel.ERROR, TAG, "AI 回复失败：${t.message}")

            val fallback = config.replyOnError.trim()
            if (fallback.isNotEmpty()) {
                runCatching { sendReply(message, fallback) }
                    .onSuccess { log(LogLevel.INFO, TAG, "已发送兜底回复") }
                    .onFailure { e -> log(LogLevel.ERROR, TAG, "兜底回复也失败：${e.message}") }
            }
        }
    }

    /**
     * 回复一条消息。
     *
     * 带上原消息 id 作为被动回复，不消耗主动消息配额，
     * 且客户端会展示成"回复某条消息"的样式。
     */
    /**
     * 是否为一对一会话。
     *
     * 群内问答已经由 `processMessage`（用户那条）+ `sendReply`（机器人那条）记账；
     * 再调 [MemoryPipeline.recordExchange] 会让一轮 @ 交互落四行，
     * 200 行窗口实际只剩 100 轮。所以这里只给单聊/私信留口子。
     */
    private fun isDirectChat(message: IncomingMessage): Boolean =
        message.kind == MessageKind.C2C || message.kind == MessageKind.DIRECT

    /**
     * 组装记忆上下文，并附上社交层（关系 / 心情 / 梗）。
     *
     * 社交层查库失败不影响回复：宁可这一轮说话"没有态度"，
     * 也不能因为关系表出错就整条不回。
     */
    private suspend fun buildMemory(
        message: IncomingMessage,
        text: String,
        insult: InsultContext? = null,
    ): MemoryContext? {
        val pipeline = memoryPipeline ?: return null
        val base = pipeline.buildContext(message, botId, text, insult)
        // 社交层与说话约束都查失败可降级：宁可这一轮"没有态度"，
        // 也不能因为关系表/规则表抖动就整条不回。
        val social = runCatching { socialProvider?.provide(message) }.getOrNull()
        val style = runCatching { styleRuleProvider?.invoke() }.getOrNull().orEmpty()
        return base.copy(social = social ?: base.social, styleRules = styleForMessage(message, style))
    }

    /**
     * 「1~10 字」「别用 Markdown」这类内置约束是为群聊闲扯准备的。
     *
     * 私聊里让人写总结、解释代码时套上去，只会把正常回答压成残句；
     * 从人设里抽出来的规则同样属于群聊人格。用户自己手写的规则则：
     * 他明确说要全局，就全局生效。
     */
    private fun styleForMessage(
        message: IncomingMessage,
        rules: List<StyleRule>,
    ): List<StyleRule> {
        val groupChat =
            message.kind == MessageKind.GROUP_AT || message.kind == MessageKind.GROUP_MESSAGE
        if (groupChat) return rules
        return rules.filter { it.source == StyleRuleSource.USER }
    }

    /**
     * 关系记账：被 @ / 被夸。
     *
     * 放在消息入口而不是回复成功后——"被骂了但没回"同样改变关系，
     * 只记成功的那部分会让它对恶意毫无记忆。
     */
    private suspend fun noteRelation(message: IncomingMessage, text: String) {
        val recorder = relationRecorder ?: return
        val directed =
            message.kind == MessageKind.GROUP_AT ||
                message.kind == MessageKind.C2C ||
                message.kind == MessageKind.DIRECT
        if (!directed) return
        runCatching { recorder.record(message, RelationEvent.MENTIONED, null) }
        if (PraiseDetector.isPraise(text, isDirected = true)) {
            runCatching { recorder.record(message, RelationEvent.PRAISED, "他夸了你一句") }
        }
    }

    /** 被指名冒犯也记一笔。群旁听消息由 [handleChitchat] 自己记，避免重复扣分。 */
    private suspend fun noteInsult(message: IncomingMessage, text: String) {
        val recorder = relationRecorder ?: return
        if (message.kind == MessageKind.GROUP_MESSAGE) return
        val extra = chitchatConfigProvider?.invoke()?.insultExtraWords ?: emptyList()
        val names = listOfNotNull(botName.takeIf { it.isNotBlank() })
        if (insultDetector.isInsult(text, extra) &&
            insultDetector.targetsBot(text, names, extra)
        ) {
            runCatching { recorder.record(message, RelationEvent.INSULTED, "他骂了你") }
        }
    }

    /**
     * 记一条"没说出口的话"。
     *
     * 思考常常几千字，只留开头一段——够复盘看出态度，也不至于把库撑大。
     * 存储失败不影响回复主链路，吞掉并记日志。
     */
    private suspend fun recordThought(message: IncomingMessage, reply: AiReply) {
        val thought = reply.reasoning?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val sink = innerThoughtSink ?: return
        runCatching {
            sink.record(
                InnerThought(
                    groupId = message.targetId?.takeIf { it.isNotBlank() },
                    senderId = message.senderId?.takeIf { it.isNotBlank() },
                    senderName = message.senderName?.takeIf { it.isNotBlank() },
                    spoken = reply.content,
                    thought = excerpt(thought, MAX_THOUGHT_CHARS),
                    createdAt = System.currentTimeMillis(),
                    // 人设隔离开启时只带当前人格；关闭时为 null（共用一份）
                    personaId = memoryPipeline?.currentPersonaId(message),
                ),
            )
        }.onFailure { logger.w(TAG, "内心戏写入失败：${it.message}") }
    }

    /** 按句末截断，别把一句话切成两半。 */
    private fun excerpt(text: String, max: Int): String {
        if (text.length <= max) return text
        val head = text.take(max)
        val cut = head.lastIndexOfAny(charArrayOf('。', '！', '？', '\n'))
        return if (cut > max / 2) head.take(cut + 1) + "…" else head + "…"
    }

    /**
     * 拟人化的回复节奏。
     *
     * 模型几百毫秒就能出结果，直接发出去是「秒回」——群里一眼看出是机器。
     * 真人看到消息、读完、想一下、打字，短句也要一两秒；两个人演对谈时，
     * 这个间隔决定了像不像在对话。
     *
     * 计时起点是**收到消息**那一刻（[IncomingMessage.receivedAtMillis]），
     * 不是生成完那一刻：模型思考得久就少等、快就多等，总时长才稳定。
     */
    private suspend fun awaitHumanPace(
        message: IncomingMessage,
        reply: String,
    ) {
        val target = humanPaceMillis(reply)
        val elapsed =
            (System.currentTimeMillis() - message.receivedAtMillis).coerceIn(0L, 60_000L)
        val wait = target - elapsed
        if (wait > 0) delay(wait)
    }

    /**
     * 目标回复时长：0.8 秒起步 + 每字 40ms，夹在 1.5~8 秒，再叠 ±20% 抖动。
     *
     * 按字数而不是固定值：短句「是吗？」应该 1.5 秒，长句应该更久；
     * 抖动是为了两个 bot 不会每次都用一模一样的间隔。
     */
    private fun humanPaceMillis(reply: String): Long {
        val clamped = (800L + reply.length * 40L).coerceIn(1500L, 8000L)
        return (clamped * (0.8 + random() * 0.4)).toLong()
    }

    private suspend fun sendReply(
        message: IncomingMessage,
        reply: String,
        memeUrl: String? = null,
    ) {
        val target = message.targetId
        if (target.isNullOrBlank()) {
            log(LogLevel.WARN, TAG, "缺少回复目标，无法回复")
            return
        }

        // 拟人延迟放在真正发送之前：前面所有判定/生成都照常，只影响"什么时候发"
        awaitHumanPace(message, reply)

        // 表情包标记：AI 链路已在 AiResponder 里剥过一次（memeUrl 有值），
        // 这里再解析是给规则回复、指令复述等非 AI 链路兜底——
        // 它们在提示词里同样看得到"能发图"，只是没有结构化出口。
        val (body, parsedMeme) = MemeMarker.parse(reply)
        val meme = memeUrl ?: parsedMeme

        // 纯表情包（正文被标记占满）时不发文字，只发图
        var sentId: String? = null
        if (body.isNotBlank()) {
            val result =
                when (message.kind) {
                    MessageKind.GROUP_AT, MessageKind.GROUP_MESSAGE ->
                        api.sendGroupMessage(target, body, replyMessageId = message.messageId)
                    MessageKind.C2C -> api.sendUserMessage(target, body, replyMessageId = message.messageId)
                    MessageKind.GUILD ->
                        // 频道消息沿用频道 openid 发送，此处退化为按 group 通道处理
                        api.sendGroupMessage(target, body, replyMessageId = message.messageId)

                    MessageKind.DIRECT -> api.sendUserMessage(target, body, replyMessageId = message.messageId)
                }
            sentId = result.id
        }
        // 记下自己在群里说的最后一条：场景催场要引用它走被动回复，
        // 否则一条"该你了"会算成主动消息（任务书坑清单第 2 条）。
        if (sentId != null &&
            (message.kind == MessageKind.GROUP_AT ||
                message.kind == MessageKind.GROUP_MESSAGE ||
                message.kind == MessageKind.GUILD)
        ) {
            lastSentId[target] = sentId
        }

        // 图随后到：文字带 msg_id 是回复，图不带——两条连发看起来就是"说了句配个图"
        if (!meme.isNullOrBlank()) {
            sendMeme(message, meme)
        }

        // 关系记账：所有回复都走这里，一处覆盖 @、规则、指令复述与旁听。
        // 失败不影响主链路——回都回了，关系没记上只是少一点印象分。
        runCatching { relationRecorder?.record(message, RelationEvent.SPOKE, null) }

        // 所有群内回复的统一记账点：@ 回复、规则回复、指令复述都走这里。
        // 之前只有旁听链路记，导致群上下文里只有"问"没有"答"，
        // 模型判断"我刚说过话没有"时其实是瞎的。
        // 纯表情包也要留一条非空记录，否则下一轮它只看到自己"什么都没说"。
        val recorded = body.ifBlank { if (!meme.isNullOrBlank()) "（表情包）" else body }
        when (message.kind) {
            MessageKind.GROUP_AT, MessageKind.GROUP_MESSAGE, MessageKind.GUILD ->
                memoryPipeline?.recordChitchatAnswer(message, recorded)

            else -> Unit
        }
    }

    /**
     * 发一张表情包：先换 file_info，再走富媒体通道（msg_type=7）。
     *
     * 上传有两条路：本地有副本时直接传字节（`file_data`），没有副本才把 URL
     * 交给平台去拉。QQ CDN 的链接带签名、几小时就失效，老图按 URL 传基本必挂。
     *
     * 多条降级路径：上传失败或发送失败都只记日志——
     * 图是配菜，不能因为它把整条回复搞砸，更不该让群里看到一条报错。
     * 群聊与单聊的上传路径不同（`/groups` 与 `/users`），发送通道也不同。
     */
    private suspend fun sendMeme(
        message: IncomingMessage,
        imageUrl: String,
    ): Boolean {
        val target = message.targetId?.takeIf { it.isNotBlank() } ?: return false
        val bytes = runCatching { memeReader?.read(imageUrl) }.getOrNull()
        val fileInfo =
            runCatching {
                when (message.kind) {
                    MessageKind.GROUP_AT, MessageKind.GROUP_MESSAGE, MessageKind.GUILD ->
                        if (bytes != null) {
                            api.uploadGroupImageBytes(target, bytes)
                        } else {
                            api.uploadGroupImage(target, imageUrl)
                        }

                    MessageKind.C2C, MessageKind.DIRECT ->
                        if (bytes != null) {
                            api.uploadUserImageBytes(target, bytes)
                        } else {
                            api.uploadUserImage(target, imageUrl)
                        }
                }
            }.getOrNull()
        if (fileInfo.isNullOrBlank()) {
            log(
                LogLevel.WARN,
                TAG,
                "表情包上传失败，跳过（${if (bytes != null) "本地字节" else "远程链接"}）：${imageUrl.take(80)}",
            )
            return false
        }
        return runCatching {
            when (message.kind) {
                MessageKind.GROUP_AT, MessageKind.GROUP_MESSAGE, MessageKind.GUILD ->
                    api.sendGroupMessage(
                        groupOpenId = target,
                        content = "",
                        msgType = MsgType.MEDIA,
                        mediaFileInfo = fileInfo,
                    )
                MessageKind.C2C, MessageKind.DIRECT ->
                    api.sendUserMessage(
                        userOpenId = target,
                        content = "",
                        msgType = MsgType.MEDIA,
                        mediaFileInfo = fileInfo,
                    )
            }
            // 发出去了才算一次采用：失败时不能被计数，否则淘汰排序会被脏数据带偏
            runCatching { memeLibrary?.recordUse(imageUrl) }
            true
        }.getOrElse { t ->
            log(LogLevel.WARN, TAG, "表情包发送失败：${t.message}")
            false
        }
    }

    /** 本群最近一次开口时间；没说过返回 null。 */
    fun lastSpokeAtMillis(groupId: String): Long? = spokeAtMillis[groupId]

    /**
     * 自主开口：由调度器判定「想说」之后调用。
     *
     * 全链路走**引用回复**（`replyMessageId` = 最近一条群消息的 msg_id）：
     * 在群友看来是它自己接了一句，在平台看来是回复已有消息，
     * 因此不消耗主动消息额度。
     *
     * 第一批不做「群里完全安静时主动发起」——那种用法要主动消息配额，
     * 需要单独的产品决策，见下方的 TODO。
     *
     * @return 是否真的发出去了
     */
    suspend fun autonomousSpeak(
        groupId: String,
        anchor: RoomMessage,
        recent: List<RoomMessage>,
        thoughts: List<String> = emptyList(),
        topic: String? = null,
    ): Boolean {
        val aiConfig = aiConfigProvider?.invoke() ?: return false
        if (!aiConfig.enabled || !aiConfig.isReady) return false
        if (aiConfig.mode == AiMode.COMMAND_ONLY) return false
        val responder = aiResponder ?: return false
        // 没有可引用的消息 id 就不开口：第一批只在"接着聊"的场景说话
        val quoteId = anchor.externalId?.takeIf { it.isNotBlank() } ?: return false

        val proxy = incomingForAutonomy(groupId, anchor, quoteId)
        val personaPrompt = runCatching { personaPromptProvider?.invoke(proxy) }.getOrNull()
        val memory = runCatching { buildMemory(proxy, anchor.content, null) }.getOrNull()
        val answer =
            runCatching {
                responder.speakOnOwn(
                    recent = recent,
                    thoughts = thoughts,
                    topic = topic,
                    config = aiConfig,
                    personaPrompt = personaPrompt,
                    memory = memory,
                )
            }.onFailure {
                log(LogLevel.WARN, TAG, "自主开口生成失败：${it.message}")
            }.getOrNull() ?: return false

        // TODO(proactiveSend)：群里超过 5 分钟没人说话且 drive 极高时，
        //  这里改走真正主动消息 api.sendGroupMessage(groupId, ...) ——会消耗主动消息配额。

        sendReply(proxy, answer.content, answer.memeUrl)
        spokeAtMillis[groupId] = System.currentTimeMillis()
        runCatching { chitchatPolicy.markSpoke(groupId) }
        log(
            LogLevel.INFO,
            TAG,
            "自主开口（引用 ${anchor.senderName?.takeIf { it.isNotBlank() } ?: "群友"}）：" +
                answer.content.take(40),
        )
        return true
    }

    /** 为自主开口构造一条"代表被引用消息"的入参，让回复/记忆链路可以直接复用。 */
    private fun incomingForAutonomy(
        groupId: String,
        anchor: RoomMessage,
        quoteId: String,
    ): IncomingMessage =
        IncomingMessage(
            kind = MessageKind.GROUP_MESSAGE,
            eventType = "GROUP_MESSAGE_CREATE",
            messageId = quoteId,
            content = anchor.content,
            targetId = groupId,
            senderId = anchor.senderId,
            senderName = anchor.senderName,
            timestampMillis = anchor.timestampMillis,
            raw = JsonObject(emptyMap()),
        )

    /** 手动向指定目标推送一条消息（供维护端"手动发言"使用）。 */
    suspend fun sendManual(
        kind: MessageKind,
        targetId: String,
        content: String,
    ) {
        runCatching {
            when (kind) {
                MessageKind.GROUP_AT, MessageKind.GROUP_MESSAGE, MessageKind.GUILD ->
                    api.sendGroupMessage(targetId, content)
                MessageKind.C2C, MessageKind.DIRECT -> api.sendUserMessage(targetId, content)
            }
            log(LogLevel.INFO, TAG, "手动发送成功 → $targetId")
        }.onFailure { t ->
            log(LogLevel.ERROR, TAG, "手动发送失败：${t.message}")
        }
    }

    private fun log(
        level: LogLevel,
        tag: String,
        text: String,
    ) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, text)
        when (level) {
            LogLevel.ERROR -> logger.e(tag, text)
            LogLevel.WARN -> logger.w(tag, text)
            LogLevel.INFO -> logger.i(tag, text)
            LogLevel.DEBUG -> logger.d(tag, text)
            LogLevel.VERBOSE -> logger.v(tag, text)
        }
        logSink?.append(entry)
    }

    /** 格式化时间为 HH:mm:ss，便于日志列表展示。 */
    fun formatTime(millis: Long): String = TIME_FORMAT.format(Date(millis))
}
