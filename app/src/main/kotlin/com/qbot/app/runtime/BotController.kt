package com.qbot.app.runtime

import android.content.Context
import com.qbot.app.data.db.AppDatabase
import com.qbot.app.data.db.LogEntity
import com.qbot.app.data.repo.PersonaRepository
import com.qbot.app.data.repo.RoomAiHistoryStore
import com.qbot.app.data.repo.RoomFactStore
import com.qbot.app.data.repo.RoomGroupStore
import com.qbot.app.data.repo.RoomIdentityStore
import com.qbot.app.data.repo.RoomSharedContextStore
import com.qbot.app.data.repo.SettingsRepository
import com.qbot.core.ai.AiResponder
import com.qbot.core.ai.LlmClient
import com.qbot.core.ai.MemoryPipeline
import com.qbot.core.ai.PersonaResolver
import com.qbot.core.api.OpenApi
import com.qbot.core.auth.AccessTokenManager
import com.qbot.core.engine.BotRuntime
import com.qbot.core.engine.LogEntry as CoreLogEntry
import com.qbot.core.engine.LogLevel
import com.qbot.core.engine.LogSink
import com.qbot.core.engine.MatchType
import com.qbot.core.engine.Rule
import com.qbot.core.engine.RuleEngine
import com.qbot.core.engine.RuleScope
import com.qbot.core.gateway.ConnectionState
import com.qbot.core.gateway.GatewayClient
import com.qbot.core.gateway.GatewayStatus
import com.qbot.core.model.Intents
import com.qbot.core.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 机器人运行时控制器（进程内单例）。
 *
 * 职责：
 * 1. 按当前凭证组装 `凭证管理 → API → 网关 → 规则引擎 → 运行时` 这条依赖链；
 * 2. 把网关运行状态与运行日志以 StateFlow 形式暴露给 UI；
 * 3. 在凭证变更、订阅位调整时重建整条链路——
 *    这几项都是连接建立时就固定下来的参数，无法在运行中热更新。
 */
class BotController(
    private val context: Context,
    private val database: AppDatabase,
    private val settings: SettingsRepository,
    private val logger: Logger,
) {
    companion object {
        private const val TAG = "BotController"

        /** 内存里保留的最新日志条数，用于 UI 实时展示（数据库负责长期留存） */
        private const val MEMORY_LOG_LIMIT = 500
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http: OkHttpClient =
        OkHttpClient.Builder()
            // 移动网络下握手可能很慢，给足连接超时；
            // 读超时设长一些，因为网关会长时间静默（只靠心跳维持）。
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(70, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    private val _status = MutableStateFlow(GatewayStatus())
    val status: StateFlow<GatewayStatus> = _status

    private val _logs = MutableStateFlow<List<CoreLogEntry>>(emptyList())
    val logs: StateFlow<List<CoreLogEntry>> = _logs

    /** 当前生效的规则（从数据库读取后转成引擎可用的形式）。 */
    val activeRules =
        database.ruleDao().enabledRules()
            .map { list -> list.map { it.toRule() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** AI 会话历史（落库，跨重启保留）。 */
    private val aiHistoryStore = RoomAiHistoryStore(database.aiHistoryDao())

    /** 人设仓库：维护端管理 + AiResponder 的人设解析。 */
    val personaRepository =
        PersonaRepository(
            personaDao = database.personaDao(),
            identityDao = database.identityDao(),
        )

    /** 身份存储，供维护端「备注名 / 忘掉某人」与管线共用。 */
    private val identityStore = RoomIdentityStore(database.identityDao())

    /** 事实存储，供维护端「删除某条记忆」与管线共用。 */
    private val factStore = RoomFactStore(database.factDao())

    /** 共享背景存储，与管线共用。 */
    private val roomContextStore = RoomSharedContextStore(database.roomMessageDao())

    /**
     * 群档案存储（机器人进过哪些群、群里见过谁、群名片、主动消息权限）。
     *
     * 平台不提供群成员名单接口，档案完全靠网关事件拼出来，
     * 落库后跨重启保留，维护端也能直接查看与管理。
     */
    private val groupStore =
        RoomGroupStore(
            groupDao = database.groupDao(),
            memberDao = database.groupMemberDao(),
        )

    /**
     * 记忆管线：身份 → 事实 → 共享群聊背景 → 群画像。
     *
     * 存储全部走 Room，跨重启保留；配置由 [SettingsRepository.memoryConfig]
     * 在每条消息处理时现取，改设置无需重启。
     *
     * 群名片的全局兜底名（READY 下发的 username）在连接建立后才有，
     * 因此用 lambda 延迟读取运行时，而不是在构造时固定。
     */
    private val memoryPipeline =
        MemoryPipeline(
            identities = identityStore,
            facts = factStore,
            roomContext = roomContextStore,
            configProvider = { settings.memoryConfig.value },
            groupStore = groupStore,
            botNameProvider = { runtime?.botName?.takeIf { it.isNotBlank() } },
        )

    /** 身份存储（公开给维护端 UI 做备注名 / 忘掉某人）。 */
    fun identityStore(): RoomIdentityStore = identityStore

    /** 事实存储（公开给维护端 UI 做删除某条记忆）。 */
    fun factStore(): RoomFactStore = factStore

    /** 群档案存储（公开给维护端 UI 做群管理与群名片编辑）。 */
    fun groupStore(): RoomGroupStore = groupStore

    /** AI 配置快照，供 UI 展示机器人是否具备 AI 能力。 */
    val aiConfig = settings.aiConfig

    /** 记忆系统配置快照，供 UI 展示与编辑。 */
    val memoryConfig = settings.memoryConfig

    /** 加群问候语配置快照，供 UI 展示与编辑。 */
    val greetingConfig = settings.greetingConfig

    /** 群聊参与（插话）配置快照。 */
    val chitchatConfig = settings.chitchatConfig

    /**
     * 最近一次 AI 调用的 token 用量（含缓存命中拆分）。
     *
     * AiResponder 在 build() 里创建，这里包一层空值兜底：
     * 机器人没启动时展示为 null，UI 显示「暂无数据」。
     */
    private val _aiUsage = MutableStateFlow<com.qbot.core.ai.LlmUsage?>(null)
    val aiUsage: StateFlow<com.qbot.core.ai.LlmUsage?> = _aiUsage.asStateFlow()

    @Volatile
    private var runtime: BotRuntime? = null

    @Volatile
    private var gateway: GatewayClient? = null

    @Volatile
    private var statusForwardJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var usageForwardJob: kotlinx.coroutines.Job? = null

    init {
        // 定期清理过期日志，避免长期挂机把磁盘撑满
        scope.launch {
            settings.logRetentionDays.collect { days ->
                val before = System.currentTimeMillis() - days.toLong() * 24 * 3600 * 1000
                runCatching { database.logDao().trimBefore(before) }
            }
        }

        // 启动时把 AI 会话历史收敛到配置的保留量，防止无限增长
        scope.launch {
            val keep = (settings.aiConfig.value.contextTurns * 2).coerceIn(2, 40)
            runCatching { aiHistoryStore.trimAll(keep) }
        }

        // 启动时收敛记忆系统：每个人事实总量与各范围共享背景条数
        scope.launch {
            runCatching { memoryPipeline.trimAll() }
        }
    }

    /** 启动机器人（幂等）。 */
    fun start() {
        if (runtime != null) {
            logger.d(TAG, "已在运行，忽略重复启动")
            return
        }

        val credentials = settings.credentials()
        if (!credentials.isConfigured) {
            appendLog(LogLevel.WARN, TAG, "尚未填写 AppID / ClientSecret，无法启动")
            _status.value = GatewayStatus(state = ConnectionState.IDLE, lastError = "未配置凭证")
            return
        }

        scope.launch {
            // 用 first() 直接取当前值：stateIn().value 在还没有订阅者时
            // 只会返回初始默认值，会导致省电模式设置被忽略。
            val powerSaving = settings.powerSaving.first()
            val intents = if (powerSaving) Intents.MESSAGES_ONLY else Intents.DEFAULT
            build(credentials.appId, credentials.clientSecret, intents)
            runtime?.start()
        }
    }

    /** 停止机器人。 */
    fun stop() {
        runtime?.stop()
        gateway?.stop()
        statusForwardJob?.cancel()
        statusForwardJob = null
        usageForwardJob?.cancel()
        usageForwardJob = null
        runtime = null
        gateway = null
        _status.value = GatewayStatus(state = ConnectionState.IDLE)
        appendLog(LogLevel.INFO, TAG, "已停止")
    }

    /** 重启（凭证或订阅位变更后调用）。 */
    fun restart() {
        logger.i(TAG, "重启机器人")
        stop()
        start()
    }

    /** 网络恢复时由广播调用，跳过退避等待立即重连。 */
    fun onNetworkRestored() {
        gateway?.reconnectNow("网络已恢复")
    }

    /**
     * 手动发送一条消息。
     *
     * 维护端"手动发言"用。注意这属于主动消息，会占用主动消息配额，
     * 与规则自动回复的被动回复不同。
     */
    suspend fun sendManual(
        kind: com.qbot.core.model.MessageKind,
        targetId: String,
        content: String,
    ) {
        runtime?.sendManual(kind, targetId, content)
    }

    fun isRunning(): Boolean = runtime != null

    /**
     * 测试 AI 配置是否可用。
     *
     * 维护端填完 AI 三项后可以先点"测试连接"验证，
     * 不必等到群里真的来一条消息才发现 Key 写错了。
     *
     * @return 模型的实际回复文本；失败时抛出带中文说明的异常。
     */
    suspend fun testAi(config: com.qbot.core.ai.AiConfig): String {
        val reply =
            LlmClient(http = http, logger = logger)
                .chat(
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                    model = config.model,
                    messages =
                        listOf(
                            com.qbot.core.ai.ChatMessage(
                                role = "user",
                                content = "请只回复四个字：连接成功",
                            ),
                        ),
                    maxTokens = 32,
                    timeoutSeconds = 20L,
                    thinkingType = com.qbot.core.ai.ThinkingMode.DISABLED.takeIf {
                        config.baseUrl.contains("deepseek", ignoreCase = true) ||
                            config.model.contains("deepseek", ignoreCase = true)
                    }?.let { "disabled" },
                )
                .content
                .trim()

        if (reply.isEmpty()) {
            throw com.qbot.core.ai.LlmException("模型返回了空内容，请检查模型名与服务地址是否匹配")
        }
        return reply
    }

    private fun build(
        appId: String,
        clientSecret: String,
        intents: Int,
    ) {
        val auth =
            AccessTokenManager(
                appId = appId,
                clientSecret = clientSecret,
                http = http,
                logger = logger,
            )

        val api =
            OpenApi(
                auth = auth,
                http = http,
                logger = logger,
            )

        val engine = RuleEngine(activeRules.value)

        // AI 链路：OpenAI 兼容客户端 + 落库历史 + 应答器。
        // AiConfig 通过 provider 在每条消息处理时现取，改设置无需重启。
        // 人设解析按 人→群→全局 查库，查不到回退到设置里的系统提示词。
        val personaResolver =
            PersonaResolver(
                store = personaRepository,
                fallbackProvider = { settings.aiConfig.value.systemPrompt },
            )
        val aiResponder =
            AiResponder(
                client = LlmClient(http = http, logger = logger),
                history = aiHistoryStore,
                personaResolver = { message -> personaResolver.promptFor(message) },
            )

        // 把应答器的最近用量转发到控制器流上，重建机器人时 UI 无需重新订阅
        usageForwardJob?.cancel()
        usageForwardJob =
            scope.launch {
                aiResponder.lastUsage.collect { _aiUsage.value = it }
            }

        val client =
            GatewayClient(
                api = api,
                auth = auth,
                scope = scope,
                http = http,
                intents = intents,
                logger = logger,
            )

        val botRuntime =
            BotRuntime(
                api = api,
                gateway = client,
                ruleEngine = engine,
                scope = scope,
                logSink = createLogSink(),
                logger = logger,
                aiResponder = aiResponder,
                aiConfigProvider = { settings.aiConfig.value },
                memoryPipeline = memoryPipeline,
                // 被拉进群时按设置发一句自我介绍（主动消息，默认关闭）。
                // 空白文本等同于关闭，与「未开启」走同一条安静路径。
                greetingTextProvider = {
                    settings.greetingConfig.value.let { greeting ->
                        if (greeting.enabled) greeting.text.trim().takeIf { it.isNotEmpty() } else null
                    }
                },
                // 群聊参与：只有群主开了「接收所有消息」才会收到非 @ 消息，
                // 收到之后要不要接话由这里的配置与 ChitchatPolicy 决定。
                chitchatConfigProvider = { settings.chitchatConfig.value },
            )

        // 把网关状态转发到控制器自己的流上，
        // 这样重建网关时 UI 不需要重新订阅新的对象。
        statusForwardJob?.cancel()
        statusForwardJob =
            scope.launch {
                client.status.collect { _status.value = it }
            }

        // 接上消息处理器：BotRuntime 实现了 GatewayListener，
        // 负责"收消息 → 匹配规则 → 调 API 回复"。
        // 必须在网关启动前挂上，否则先到的事件会被丢弃。
        client.setListener(botRuntime)

        // 规则热更新：用户在维护端改规则后无需重启机器人
        scope.launch {
            activeRules.collect { updated ->
                engine.updateRules(updated)
            }
        }

        this.gateway = client
        this.runtime = botRuntime
        appendLog(LogLevel.INFO, TAG, "已构建运行时（intents=$intents，规则 ${engine.rulesSnapshot().size} 条）")
    }

    private fun createLogSink() =
        object : LogSink {
            override fun append(entry: CoreLogEntry) {
                appendLog(entry.level, entry.tag, entry.text)
            }
        }

    private fun appendLog(
        level: LogLevel,
        tag: String,
        text: String,
    ) {
        val entry = CoreLogEntry(System.currentTimeMillis(), level, tag, text)

        // 内存列表：新日志插入头部，只保留最近若干条
        _logs.value = (listOf(entry) + _logs.value).take(MEMORY_LOG_LIMIT)

        // 落库：供历史查询，失败不应影响主流程
        scope.launch(Dispatchers.IO) {
            runCatching {
                database.logDao().insert(
                    LogEntity(
                        timestampMillis = entry.timestampMillis,
                        level = entry.level.name,
                        tag = tag,
                        text = text,
                    ),
                )
            }
        }
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }
}

/** 数据库实体 → 引擎规则。 */
fun com.qbot.app.data.db.RuleEntity.toRule(): Rule =
    Rule(
        id = id,
        name = name,
        enabled = enabled,
        matchType = runCatching { MatchType.valueOf(matchType) }.getOrDefault(MatchType.CONTAINS),
        pattern = pattern,
        reply = reply,
        scope = runCatching { RuleScope.valueOf(scope) }.getOrDefault(RuleScope.ALL),
        priority = priority,
    )

/** 引擎规则 → 数据库实体。 */
fun Rule.toEntity(): com.qbot.app.data.db.RuleEntity =
    com.qbot.app.data.db.RuleEntity(
        id = id,
        name = name,
        enabled = enabled,
        matchType = matchType.name,
        pattern = pattern,
        reply = reply,
        scope = scope.name,
        priority = priority,
    )
