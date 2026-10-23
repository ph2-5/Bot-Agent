package com.qbot.core.engine

import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.AddresseeDetector
import com.qbot.core.ai.AiMode
import com.qbot.core.ai.AiResponder
import com.qbot.core.ai.BehaviorContext
import com.qbot.core.ai.ChimeDecisionRecord
import com.qbot.core.ai.ChimeTrainer
import com.qbot.core.ai.LessonCategories
import com.qbot.core.ai.LlmException
import com.qbot.core.ai.ChitchatConfig
import com.qbot.core.ai.ChitchatGate
import com.qbot.core.ai.ChitchatPolicy
import com.qbot.core.ai.CommandOutcome
import com.qbot.core.ai.InsultContext
import com.qbot.core.ai.InsultDetector
import com.qbot.core.ai.MemoryPipeline
import com.qbot.core.ai.PersonaBias
import com.qbot.core.ai.RoomMessage
import com.qbot.core.ai.describe
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
    /** 插话决策引擎；默认实例即可，测试可注入固定时钟 */
    private val chitchatPolicy: ChitchatPolicy = ChitchatPolicy(),
    /** 冒犯检测：被指名辱骂时要按人设反应，不能装没看见 */
    private val insultDetector: InsultDetector = InsultDetector(),
    /** 概率源，测试里可注入固定值 */
    private val random: () -> Double = { kotlin.random.Random.nextDouble() },
) : SimpleGatewayListener() {
    companion object {
        private const val TAG = "Runtime"
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
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
        // @ 消息优先记账：随后到达的同 id 全量事件会被去重挡掉
        if (message.kind == MessageKind.GROUP_AT) rememberMessage(message.messageId)

        // 整条处理链放进协程：身份解析与记忆装载都是挂起函数（要查库），
        // 网关回调线程必须尽快返回，否则会堵塞后续事件读取。
        scope.launch { processMessage(message, text) }
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

        if (aiResponder != null && aiConfig != null && aiEnabled &&
            aiResponder.shouldHandle(message, aiConfig, ruleMatched = false)
        ) {
            replyWithAi(message, aiConfig, text)
            return
        }

        // 规则未命中且 AI 未启用/不处理时静默丢弃，避免对每条消息都回一句
        log(LogLevel.DEBUG, TAG, "无匹配规则，忽略")
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
        if (text.isBlank()) return
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
        val names =
            if (profile != null) {
                listOfNotNull(profile.botName, botName.takeIf { it.isNotBlank() })
            } else {
                listOfNotNull(botName.takeIf { it.isNotBlank() })
            }
        // 被指名冒犯：名字（含后缀）+ 侮辱词同时成立才算。
        // 侮辱词 = 内置表 + 用户在设置里追加的自定义词（语言黑话层出不穷，
        // 用户把自己群里实际出现的词加进来，判定才准）。
        val extraInsultWords = chitchatConfig?.insultExtraWords ?: emptyList()
        val insulted =
            !addressed &&
                insultDetector.isInsult(text, extraInsultWords) &&
                insultDetector.targetsBot(text, names, extraInsultWords)
        val talkingToOthers = insultDetector.talksToOthers(mentionIds, selfId)

        // 这句话大概率是对谁说的：人说话是有对象的，
        // 「两人互道晚安」和「有人吗」该不该接完全不同。
        val addressee = AddresseeDetector.infer(message, recent, names, selfId)

        val gate =
            when {
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

        pipeline.recordChitchat(
            message = message,
            cleanText = text,
            isAt = addressed,
            decision = gate.describe(),
        )
        learnBotName(message, selfId)

        // @ 自己的消息交给 @ 链路应答，这里只记不说
        if (addressed) return

        // 2) 三级漏斗的后两级
        val config = chitchatConfig ?: return

        // 训练闭环：整个判断过程只在这里落一条记录，无论从哪个分支返回。
        // 用 finally 而不是在每个 return 前写库——既不会漏记，也是未来把
        // sink 换成事件总线（插件化）的前提。
        var aiVerdict: String? = null
        var aiReason: String? = null
        var action = ChimeDecisionRecord.ACTION_SILENT
        var finalReason = gate.describe()
        var replyText: String? = null
        val gatePath =
            when (gate) {
                is ChitchatGate.Forced -> ChimeDecisionRecord.GATE_FORCED
                is ChitchatGate.Candidate -> ChimeDecisionRecord.GATE_CANDIDATE
                is ChitchatGate.Skip -> ChimeDecisionRecord.GATE_SKIP
            }
        val ruleScore = (gate as? ChitchatGate.Candidate)?.score

        try {
            val why =
                when (val current = gate) {
                    is ChitchatGate.Skip -> {
                        log(LogLevel.VERBOSE, TAG, "群聊消息不接：${current.reason}")
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
                            // 经验注入：群级优先、全局兜底；失败不影响判断
                            val lessons =
                                runCatching {
                                    chimeTrainer?.lessonsForPrompt(
                                        groupId,
                                        config.maxInjectedLessons,
                                    ) ?: emptyList()
                                }.getOrDefault(emptyList())
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
                                        addressee = addressee,
                                        behavior =
                                            BehaviorContext(
                                                userRules = config.chimeRules,
                                                // 带上类别标签，模型能看出这几条各自管什么场景
                                                lessons =
                                                    lessons.map {
                                                        "【${LessonCategories.label(it.category)}】${it.text}"
                                                    },
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
                            // 被参考过的经验计一次命中（失败不影响判断）
                            if (lessons.isNotEmpty()) {
                                runCatching { chimeTrainer?.bumpHits(lessons.map { it.id }) }
                            }
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
                val memory = pipeline.buildContext(message, selfId, text, insult)
                val answer = responder.reply(message, aiConfig, memory)
                if (answer.isBlank()) {
                    finalReason = "不接：回复为空"
                    return@runCatching
                }
                sendReply(message, answer)
                chitchatPolicy.markSpoke(groupId)
                // 机器人这条已由 sendReply 统一写入群上下文
                pipeline.markChitchatDecision(message, "已接话（$why）")
                action = ChimeDecisionRecord.ACTION_SPOKE
                replyText = answer
                finalReason = "已接话（$why）"
                log(LogLevel.INFO, TAG, "群聊插话（$why，${answer.length} 字）")
            }.onFailure { t ->
                finalReason = "不接：回复失败"
                pipeline.markChitchatDecision(message, finalReason)
                logger.e(TAG, "群聊插话失败", t)
                log(LogLevel.WARN, TAG, "群聊插话失败：${t.message}")
            }
        } finally {
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
                            addresseeKind = addressee.kind.name,
                            addresseeName = addressee.name,
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
        recent
            .takeLast(8)
            .joinToString("\n") { msg ->
                val who =
                    msg.senderName?.takeIf { it.isNotBlank() }
                        ?: if (msg.role == "assistant") botName.ifBlank { "机器人" } else "某人"
                "[$who] ${msg.content.replace("\n", " ").take(60)}"
            }.take(400)

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
                val memory = pipeline.buildContext(message, botId, text)
                val answer =
                    responder.reply(
                        message,
                        aiConfig,
                        memory,
                        directive = outcome.directive,
                    )
                if (answer.isNotBlank()) {
                    reply = answer
                    viaAi = true
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
            val memory = memoryPipeline?.buildContext(message, botId, text, insult)
            val answer = aiResponder!!.reply(message, config, memory)
            sendReply(message, answer)
            if (isDirectChat(message)) memoryPipeline?.recordExchange(message, text, answer)
            // 用量日志：缓存命中不仅省钱，首 token 延迟也大幅降低，
            // 命中率长期为 0 说明 system 提示词不稳定，值得排查。
            val usage = aiResponder.lastUsage.value
            if (usage != null && usage.promptTokens > 0) {
                val hitPercent = (usage.cacheHitRatio ?: 0f) * 100
                log(
                    LogLevel.INFO,
                    TAG,
                    "AI 已回复（${answer.length} 字）｜prompt ${usage.promptTokens} tokens" +
                        "（缓存命中 ${usage.cacheHitTokens}，${hitPercent.toInt()}%）" +
                        "｜completion ${usage.completionTokens} tokens",
                )
            } else {
                log(LogLevel.INFO, TAG, "AI 已回复（${answer.length} 字）")
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

    private suspend fun sendReply(
        message: IncomingMessage,
        reply: String,
    ) {
        val target = message.targetId
        if (target.isNullOrBlank()) {
            log(LogLevel.WARN, TAG, "缺少回复目标，无法回复")
            return
        }

        when (message.kind) {
            MessageKind.GROUP_AT, MessageKind.GROUP_MESSAGE ->
                api.sendGroupMessage(target, reply, replyMessageId = message.messageId)
            MessageKind.C2C -> api.sendUserMessage(target, reply, replyMessageId = message.messageId)
            MessageKind.GUILD ->
                // 频道消息沿用频道 openid 发送，此处退化为按 group 通道处理
                api.sendGroupMessage(target, reply, replyMessageId = message.messageId)

            MessageKind.DIRECT -> api.sendUserMessage(target, reply, replyMessageId = message.messageId)
        }

        // 所有群内回复的统一记账点：@ 回复、规则回复、指令复述都走这里。
        // 之前只有旁听链路记，导致群上下文里只有"问"没有"答"，
        // 模型判断"我刚说过话没有"时其实是瞎的。
        when (message.kind) {
            MessageKind.GROUP_AT, MessageKind.GROUP_MESSAGE, MessageKind.GUILD ->
                memoryPipeline?.recordChitchatAnswer(message, reply)
            else -> Unit
        }
    }

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
