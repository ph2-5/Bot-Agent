package com.qbot.app.runtime

import android.content.Context
import com.qbot.app.data.backup.ConfigBackup
import com.qbot.app.data.backup.AiBackup
import com.qbot.app.data.backup.ChitchatBackup
import com.qbot.app.data.backup.CredentialsBackup
import com.qbot.app.data.backup.DisplayNameBackup
import com.qbot.app.data.backup.FactBackup
import com.qbot.app.data.backup.GreetingBackup
import com.qbot.app.data.backup.GroupBotNameBackup
import com.qbot.app.data.backup.InnerThoughtBackup
import com.qbot.app.data.backup.LessonBackup
import com.qbot.app.data.backup.LoreBackup
import com.qbot.app.data.backup.MemeBackup
import com.qbot.app.data.backup.MemoryBackup
import com.qbot.app.data.backup.PersonaBackup
import com.qbot.app.data.backup.RelationBackup
import com.qbot.app.data.backup.RoomMessageBackup
import com.qbot.app.data.backup.SlangBackup
import com.qbot.app.data.backup.PersonaProfileBackup
import com.qbot.app.data.backup.RuleBackup
import com.qbot.app.data.backup.StyleRuleBackup
import com.qbot.app.data.db.AppDatabase
import com.qbot.app.data.db.FactEntity
import com.qbot.app.data.db.InnerThoughtEntity
import com.qbot.app.data.db.LogEntity
import com.qbot.app.data.db.LoreEntity
import com.qbot.app.data.db.RoomMessageEntity
import com.qbot.app.data.db.RuleEntity
import com.qbot.app.data.db.StyleRuleEntity
import com.qbot.app.data.plugin.FileStylePluginHost
import com.qbot.app.data.repo.PersonaRepository
import com.qbot.app.data.repo.RoomChimeDecisionStore
import com.qbot.app.data.repo.RoomChimeLessonStore
import com.qbot.app.data.repo.RoomChimeTrainer
import com.qbot.app.data.repo.RoomAiHistoryStore
import com.qbot.app.data.repo.RoomFactStore
import com.qbot.app.data.repo.RoomGroupStore
import com.qbot.app.data.repo.RoomIdentityStore
import com.qbot.app.data.repo.RoomInnerThoughtSink
import com.qbot.app.data.repo.RoomLoreStore
import com.qbot.app.data.repo.RoomSocialStore
import com.qbot.app.data.repo.RoomStyleRuleStore
import com.qbot.core.api.DeepSeekSearch
import com.qbot.app.data.repo.RoomSharedContextStore
import com.qbot.app.data.repo.SettingsRepository
import com.qbot.app.data.repo.GreetingConfig
import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.AiMode
import com.qbot.core.ai.AiResponder
import com.qbot.core.ai.ChatMessage
import com.qbot.core.ai.ChimeDecision
import com.qbot.core.ai.BotMood
import com.qbot.core.ai.InnerThought
import com.qbot.core.ai.PersonRelation
import com.qbot.core.ai.SlangHarvest
import com.qbot.core.ai.SlangJudge
import com.qbot.core.ai.SlangTerm
import com.qbot.core.ai.ChimeDecisionRecord
import com.qbot.core.ai.ChimeLesson
import com.qbot.core.ai.ChimeReflection
import com.qbot.core.ai.ChimeStats
import com.qbot.core.ai.FactKind
import com.qbot.core.ai.FactSource
import com.qbot.core.ai.EmptyContentException
import com.qbot.core.ai.GenerationLimits
import com.qbot.core.ai.LessonCategories
import com.qbot.core.ai.LoreEntry
import com.qbot.core.ai.LoreKind
import com.qbot.core.ai.LoreSource
import com.qbot.core.ai.ChitchatConfig
import com.qbot.core.ai.GroupProfile
import com.qbot.core.ai.LlmClient
import com.qbot.core.ai.MemeHarvester
import com.qbot.core.ai.MemeImage
import com.qbot.core.ai.MemoryConfig
import com.qbot.core.ai.MemoryPipeline
import com.qbot.core.ai.Persona
import com.qbot.core.ai.PersonaGrowth
import com.qbot.core.ai.PersonaKind
import com.qbot.core.ai.PersonaSeed
import com.qbot.core.ai.PersonaStage
import com.qbot.core.ai.NameSource
import com.qbot.core.ai.PersonaResolver
import com.qbot.core.ai.PersonaLoreExtractor
import com.qbot.core.ai.PersonaStyleExtractor
import com.qbot.core.ai.RemoteImageLoader
import com.qbot.core.ai.ScoreWeights
import com.qbot.core.ai.SendMemeTool
import com.qbot.core.ai.StyleRule
import com.qbot.core.ai.StyleRuleCategory
import com.qbot.core.ai.StyleRuleComposer
import com.qbot.core.ai.StyleRuleSource
import com.qbot.core.ai.StyleRender
import com.qbot.core.ai.ThinkingMode
import com.qbot.core.ai.ThinkingParams
import com.qbot.core.ai.ToolRegistry
import com.qbot.core.ai.WebSearchTool
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        /** 决策日志保留天数（复盘用，过期即删） */
        private const val CHIME_RETENTION_DAYS = 7L

        /** 决策日志总量上限，防止高频群把库撑大 */
        private const val CHIME_MAX_DECISIONS = 2000

        /** 备份里最多带多少条最近对话：够接上"上次聊到哪"即可 */
        private const val BACKUP_ROOM_MESSAGES = 2000

        /** 备份里最多带多少条内心戏 */
        private const val BACKUP_INNER_THOUGHTS = 500
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
            snapshotDao = database.personaSnapshotDao(),
        )

    /**
     * 逐消息解析当前生效的人格（覆盖层 > 激活档案 > 种子）。
     *
     * 提示词、会话线程、人设设定、关系与经验都从这里取同一个 id，
     * 避免「说的是 A 人设，记忆却记到 B 人设」的错位。
     */
    private val personaResolver = PersonaResolver(store = personaRepository)

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

    /** 关系、心情、梗库、人设设定：既是提示词的数据源，也是互动事件的落库点。 */
    private val loreStore = RoomLoreStore(database.loreDao())

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
            // 会话/记忆按「本条消息实际扮演的人格」隔离，而不是全局激活档案；
            // 是否真的隔离由 MemoryConfig.personaScoped 决定（管线内部判断）。
            personaIdProvider = { message -> personaResolver.resolve(message).personaId },
            groupStore = groupStore,
            botNameProvider = { runtime?.botName?.takeIf { it.isNotBlank() } },
            // 「忘掉 X」同时清掉这个人设下与 X 相关的设定，避免下一轮又被注入
            loreStore = loreStore,
        )

    /**
     * 接话训练闭环：决策日志 + 经验库。
     *
     * 决策由 [RoomChimeTrainer] 在 BotRuntime 的单一出口写入；
     * 经验在判断前读取、注入提示词，并累计命中次数。
     */
    private val chimeDecisionStore = RoomChimeDecisionStore(database.chimeDecisionDao())
    private val chimeLessonStore =
        RoomChimeLessonStore(
            dao = database.chimeLessonDao(),
            // 未开隔离时返回 null：经验仍是共用的一份，行为与旧版本一致
            personaIdProvider = {
                if (settings.memoryConfig.value.personaScoped) personaRepository.active()?.id else null
            },
        )
    private val chimeTrainer = RoomChimeTrainer(chimeDecisionStore, chimeLessonStore)

    /** 内心戏出口：思考模式打开时，模型没说出口的那段话落这里。 */
    private val innerThoughtSink = RoomInnerThoughtSink(database.innerThoughtDao())

    private val styleRuleStore = RoomStyleRuleStore(database.styleRuleDao())

    /** 文件式语气插件：`Android/data/<包名>/files/plugins` 下的 JSON，改文件即生效 */
    private val stylePluginHost =
        FileStylePluginHost(
            // 优先外部私有目录：插上电脑或用文件管理器能直接放 JSON
            java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "plugins"),
        )

    /** 插件目录路径（设置页展示用） */
    val stylePluginDirectory: String get() = stylePluginHost.directoryPath

    /** 三路约束的合并器：内置（可逐条停用）+ 用户自定义 + 插件 */
    private val styleRuleComposer =
        StyleRuleComposer(store = styleRuleStore, plugins = listOf(stylePluginHost))

    private val socialStore =
        RoomSocialStore(
            affinityDao = database.affinityDao(),
            moodDao = database.botMoodDao(),
            slangDao = database.slangDao(),
            thoughtDao = database.innerThoughtDao(),
            memeDao = database.memeDao(),
            loreDao = database.loreDao(),
            // 设定必须跟着本条消息实际扮演的人格走：某群挂了覆盖层人设时，
            // 过去取 active() 会把设定接到另一个人格上（审计 A1）。
            personaIdProvider = { message ->
                if (message == null) {
                    personaRepository.active()?.id
                } else {
                    personaResolver.resolve(message).personaId
                }
            },
            // 关系/内心戏是否按人设分开，由维护端开关决定（默认共用）
            isolateMemories = { settings.memoryConfig.value.personaScoped },
        )

    /** 反思串行化：一次只处理一条反馈，避免并发写入把经验库弄乱 */
    private val reflectionMutex = Mutex()

    /** 反思与正式回复共用连接池 */
    private val reflectionClient by lazy { LlmClient(http = http, logger = logger) }

    /** 聊天时学梗与主对话的 web_search 工具共用同一个搜索实现。 */
    private val deepSeekSearch by lazy { DeepSeekSearch(http) }

    /** 聊天时学梗：遇到可疑词去搜一下，查到了就入库。 */
    private val slangLearner =
        SlangLearnerImpl(
            store = socialStore,
            client = reflectionClient,
            search = deepSeekSearch,
            config = { settings.aiConfig.value },
        )

    /** 收表情包：群友发的图顺手捡进库，之后回复时可以配一张。 */
    private val memeHarvester = MemeHarvester { url -> socialStore.harvestMeme(url) }


    /**
     * 人格自主成长调度器。
     *
     * lazy 构造：只有真正需要它的路径（init / 手动成长）才会建出来，
     * 测试里注入空依赖时不会因为多一个 OkHttp 客户端而变慢。
     */
    private val personaGrowthScheduler by lazy {
        PersonaGrowthScheduler(
            repo = personaRepository,
            messages = database.roomMessageDao(),
            aiConfig = { settings.aiConfig.value },
            client = LlmClient(http = http, logger = logger),
            botName = { runtime?.botName },
            log = { message -> appendLog(LogLevel.INFO, TAG, message) },
        )
    }

    /** 引导只跑一次：init 与 start 可能同时触发 */
    private val bootstrapMutex = Mutex()

    /**
     * 人格成长轮询的 Job。
     *
     * 过去 start() 的返回值被直接丢弃，于是点「停止机器人」后它仍在后台
     * 读库、调模型、写快照——用户以为已经关了。
     */
    private var growthJob: kotlinx.coroutines.Job? = null

    /**
     * 启动代次：每调用一次 start() 递增，stop() 也递增。
     *
     * start() 是挂起链（引导 → 读设置 → build），期间用户可能已经点了停止。
     * 启动协程在每个挂起点后比对代次，发现自己已过期就直接退出，
     * 避免「停了又自己活过来」的幽灵运行时。
     */
    @Volatile
    private var startGeneration = 0

    /** 身份存储（公开给维护端 UI 做备注名 / 忘掉某人）。 */
    fun identityStore(): RoomIdentityStore = identityStore

    /** 事实存储（公开给维护端 UI 做删除某条记忆）。 */
    fun factStore(): RoomFactStore = factStore

    /** 群档案存储（公开给维护端 UI 做群管理与群名片编辑）。 */
    fun groupStore(): RoomGroupStore = groupStore

    /** 复盘页：最近的接话判断，可按群过滤。 */
    fun observeChimeDecisions(
        groupId: String? = null,
        limit: Int = 100,
    ): Flow<List<ChimeDecision>> = chimeDecisionStore.observe(groupId, limit)

    /** 经验库。 */
    fun observeChimeLessons(): Flow<List<ChimeLesson>> = chimeLessonStore.observeAll()

    /**
     * 最近说过但没发出去的话。
     *
     * 只有开了思考模式才会有记录；关掉时列表恒为空，不是故障。
     */
    fun observeInnerThoughts(limit: Int = 100): Flow<List<InnerThought>> =
        innerThoughtSink.observeRecent(limit)

    suspend fun clearInnerThoughts() = innerThoughtSink.clear()

    /** 对每个人的关系（好感降序）。 */
    fun observeRelations(limit: Int = 50): Flow<List<PersonRelation>> =
        socialStore.observeRelations(limit)

    suspend fun currentMood(): BotMood = socialStore.currentMood()

    suspend fun forgetRelation(userId: String) = socialStore.forgetRelation(userId)

    /** 群内梗库。 */
    fun observeSlang(): Flow<List<SlangTerm>> = socialStore.observeSlang()

    suspend fun addSlang(term: String, meaning: String, usage: String? = null, groupId: String? = null) {
        if (term.isBlank() || meaning.isBlank()) return
        socialStore.upsertSlang(
            SlangTerm(
                term = term.trim(),
                meaning = meaning.trim(),
                usage = usage?.trim()?.takeIf { it.isNotBlank() },
                scopeGroupId = groupId,
                hits = 1,
                source = SlangTerm.SOURCE_MANUAL,
            ),
        )
    }

    suspend fun setSlangEnabled(id: Long, enabled: Boolean) = socialStore.setSlangEnabled(id, enabled)

    suspend fun deleteSlang(id: Long) = socialStore.deleteSlang(id)

    /** 表情包库（群里捡的 + 手动贴的）。 */
    fun observeMemes(): Flow<List<MemeImage>> = socialStore.observeMemes()

    /** 维护端手动贴一张图；返回 null 表示链接为空。 */
    suspend fun addMeme(url: String): Boolean {
        if (url.isBlank()) return false
        return socialStore.addMeme(url) != null
    }

    suspend fun deleteMeme(id: Long) = socialStore.deleteMeme(id)

    suspend fun clearMemes() = socialStore.clearMemes()

    /**
     * 从最近活跃的群里收获梗。
     *
     * 本地统计缩小范围 → 模型判断是不是梗 → 入库。返回新增/合并的条数。
     * 任何一步失败都只是这一轮没收获，不影响别的功能。
     */
    suspend fun harvestSlang(): Int {
        val config = settings.aiConfig.value
        if (!config.enabled || !config.isReady) return 0
        val groups =
            runCatching { groupStore.allActive() }.getOrDefault(emptyList())
                .sortedByDescending { it.lastSeenAt }
                .take(3)
        if (groups.isEmpty()) return 0
        val judge = SlangJudge(reflectionClient)
        var touched = 0
        groups.forEach { group ->
            val messages =
                runCatching { memoryPipeline.recentGroupMessages(group.groupId, 300) }
                    .getOrDefault(emptyList())
            if (messages.size < 20) return@forEach
            val names = messages.mapNotNull { it.senderName }.distinct()
            val candidates = SlangHarvest.candidates(messages, names, minHits = 3, limit = 18)
            if (candidates.isEmpty()) return@forEach
            val judged =
                runCatching { judge.filter(candidates, messages, config) }
                    .getOrDefault(emptyList())
            judged.forEach { term ->
                socialStore.upsertSlang(term.copy(scopeGroupId = group.groupId, hits = 1))
                touched++
            }
        }
        socialStore.trimSlang(200)
        return touched
    }

    // ---- 说话风格（去 AI 味约束） ----

    /**
     * 合并后的约束列表（内置 + 用户自定义）。
     *
     * 内置规则始终返回，enabled 反映用户是否停用过——
     * 数据库里没有行就是默认启用，这样升级新增的内置规则会自动出现。
     */
    fun observeStyleRules(): Flow<List<StyleRule>> =
        styleRuleStore.observeRows().map { saved ->
            styleRuleComposer.merge(saved, stylePluginHost.rules())
        }

    /** 新增一条自定义约束；文本为空返回 false。 */
    suspend fun addStyleRule(
        text: String,
        category: StyleRuleCategory = StyleRuleCategory.TONE,
    ): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        styleRuleStore.upsert(
            StyleRule(
                text = clean.take(StyleRender.MAX_ONE_RULE),
                category = category,
                source = StyleRuleSource.USER,
            ),
        )
        return true
    }

    suspend fun setStyleRuleEnabled(
        rule: StyleRule,
        enabled: Boolean,
    ) = styleRuleStore.setEnabled(rule.id, enabled)

    /** 删除用户规则；对内置规则而言等于「恢复默认」。 */
    suspend fun deleteStyleRule(rule: StyleRule) = styleRuleStore.delete(rule.id)

    suspend fun clearUserStyleRules() = styleRuleStore.clearUserRules()

    // ---- 人设设定（认识的人 / 说法） ----

    fun observeLore(): Flow<List<LoreEntry>> = loreStore.observeAll()

    /** 手动加一条设定；挂在当前激活的人格下。 */
    suspend fun addLore(
        kind: LoreKind,
        name: String,
        note: String,
    ): Boolean {
        val cleanName = name.trim()
        val cleanNote = note.trim()
        if (cleanName.isEmpty() || cleanNote.isEmpty()) return false
        loreStore.save(
            LoreEntry(
                personaId = personaRepository.active()?.id,
                kind = kind,
                name = cleanName.take(12),
                note = cleanNote.take(40),
                source = LoreSource.MANUAL,
            ),
        )
        return true
    }

    suspend fun setLoreEnabled(
        id: Long,
        enabled: Boolean,
    ) = loreStore.setEnabled(id, enabled)

    suspend fun deleteLore(id: Long) = loreStore.delete(id)

    /**
     * 让模型从当前人设正文里抽取「认识的人」与「特殊说法」。
     *
     * 只在用户点按钮时跑：抽取是一次性成本，产物落库后不再需要模型。
     * 重抽会替换上一轮 AI 抽的条目，手动加的保留。
     *
     * @return 本次写入的条数；0 表示没抽到或 AI 不可用
     */
    suspend fun extractPersonaLore(): Int {
        val persona = runCatching { personaRepository.active() }.getOrNull() ?: return 0
        val prompt = persona.prompt.trim()
        if (prompt.isBlank()) return 0
        val config = settings.aiConfig.value
        if (!config.enabled || !config.isReady) return 0
        val entries =
            runCatching {
                PersonaLoreExtractor(LlmClient(http = http, logger = logger))
                    .extract(prompt, persona.id, config)
            }.getOrDefault(emptyList())
        if (entries.isEmpty()) return 0

        // 重抽即替换：只清上一轮 AI 的产物，手动补充的设定不动
        runCatching {
            loreStore.all()
                .filter { it.personaId == persona.id && it.source == LoreSource.AI }
                .forEach { loreStore.delete(it.id) }
        }
        var saved = 0
        entries.forEach { entry ->
            runCatching { loreStore.save(entry) }.onSuccess { saved++ }
        }
        // 设定里的「特殊说法」已由 lore 注入提示词（LoreRender），
        // 不再同时写进梗库——那会导致同一条说法在提示词里出现两遍（审计 M2）。
        return saved
    }

    /**
     * 从当前人设正文里抽取「怎么说话」的约束，写入 [StyleRuleSource.PERSONA]。
     *
     * 过去用户在人设里写的「别用感叹号 / 别自称 AI / 少用表情」只作为人设正文
     * 的一部分被模型忽略，设置页的「语言约束」与它各管各的（审计 M1）。
     * 抽取后这些约束进入统一的风格渲染链路，可逐条启停、可被用户规则覆盖。
     *
     * 重抽会先清掉上一批 PERSONA 规则，避免人设改短后旧约束残留。
     *
     * @return 本次写入的条数；0 表示没抽到或 AI 不可用
     */
    suspend fun extractPersonaStyle(): Int {
        val persona = runCatching { personaRepository.active() }.getOrNull() ?: return 0
        val prompt = persona.prompt.trim()
        if (prompt.isBlank()) return 0
        val config = settings.aiConfig.value
        if (!config.enabled || !config.isReady) return 0
        val rules =
            runCatching {
                PersonaStyleExtractor(LlmClient(http = http, logger = logger))
                    .extract(prompt, config)
            }.getOrDefault(emptyList())
        // 即使没抽到也清掉上一批：人设已经删掉的约束不该继续生效
        runCatching { styleRuleStore.clearSource(StyleRuleSource.PERSONA) }
        if (rules.isEmpty()) return 0
        var saved = 0
        rules.forEach { rule ->
            runCatching { styleRuleStore.upsert(rule) }.onSuccess { saved++ }
        }
        return saved
    }

    /** 近 N 天的判断质量统计。 */
    suspend fun chimeStats(days: Int = 7): ChimeStats =
        chimeDecisionStore.stats(System.currentTimeMillis() - days.toLong() * 24 * 3600 * 1000)

    /**
     * 提交一条反馈。
     *
     * 标「正确」只落库；标「错误」会立刻调一次模型，把这个案例总结成经验。
     * 返回值直接作为提示文案展示给用户（成功与失败都是人话）。
     *
     * 反思是串行的：一次只跑一条，避免连点把多条相似经验同时写进去。
     */
    suspend fun submitChimeFeedback(
        id: Long,
        correct: Boolean,
        note: String? = null,
    ): String =
        reflectionMutex.withLock {
            val decision = chimeDecisionStore.find(id) ?: return@withLock "这条记录已经不在了"
            val corrected =
                when {
                    correct -> null
                    decision.record.action == ChimeDecisionRecord.ACTION_SPOKE ->
                        ChimeDecision.SHOULD_SILENT
                    else -> ChimeDecision.SHOULD_SPEAK
                }
            val now = System.currentTimeMillis()
            chimeDecisionStore.markFeedback(
                id = id,
                correct = correct,
                correctedAction = corrected,
                note = note?.trim()?.take(100)?.takeIf { it.isNotEmpty() },
                at = now,
            )
            if (correct) return@withLock "已记录：这次判断是对的"

            val cfg = settings.aiConfig.value
            if (!cfg.enabled || !cfg.isReady) {
                return@withLock "已记录，但 AI 未启用，暂时无法生成经验"
            }

            val scope = decision.record.groupId
            // 这次判断实际扮演的人格；开启隔离后，经验只归它、也只参考它的旧经验
            val personaId = decision.record.personaId
            val updated =
                decision.copy(
                    feedback = false,
                    correctedAction = corrected,
                    feedbackNote = note?.trim()?.take(100),
                )
            val reply =
                runCatching {
                    chatWithRetry(
                        cfg = cfg,
                        system = ChimeReflection.buildSystemPrompt(),
                        user = ChimeReflection.buildUserPrompt(updated, lessonsForReflection(personaId)),
                        maxTokens = GenerationLimits.forConfig(cfg),
                    )
                }.getOrElse {
                    return@withLock "已记录，但经验生成失败：${it.message ?: it::class.simpleName ?: "未知错误"}"
                }

            val parsed =
                ChimeReflection.parse(reply)
                    ?: return@withLock "已记录，但模型没给出可用的经验"

            // 归档到固定类别后，只在该类别里查重：
            // 同一件事的另一种说法会被改写并累加支撑数，而不是新增一条。
            val sameCategory = chimeLessonStore.sameCategory(scope, parsed.category, personaId)
            val similar = ChimeReflection.findSimilar(parsed.text, sameCategory)
            val outcome =
                if (similar != null) {
                    chimeLessonStore.mergeInto(similar.id, parsed.text, 1, now)
                    "已记录，并入「${LessonCategories.label(parsed.category)}」的经验：${parsed.text}"
                } else {
                    chimeLessonStore.insert(
                        ChimeLesson(
                            text = parsed.text,
                            source = ChimeLesson.SOURCE_REFLECTION,
                            scopeGroupId = scope,
                            fromDecisionId = id,
                            createdAt = now,
                            updatedAt = now,
                            category = parsed.category,
                            supportCount = 1,
                            personaId = personaId,
                        ),
                    )
                    "已记录，学到一条「${LessonCategories.label(parsed.category)}」经验：${parsed.text}"
                }

            // 同类过长就归并（偶发、成本可控），顺便按容量淘汰
            val merged =
                runCatching { mergeCategoryIfNeeded(scope, parsed.category, cfg, personaId) }.getOrNull()
            if (merged.isNullOrBlank()) {
                chimeLessonStore.trim(settings.chitchatConfig.value.maxLessons)
                outcome
            } else {
                "$outcome\n$merged"
            }
        }

    /**
     * 结构化短任务的模型调用：空正文时放宽额度重试一次。
     *
     * 有些代理不认 `thinking=disabled`，或长上下文下把额度耗在解释上。
     * 一次重试比把失败直接抛给用户体面；仍失败才报错（文案由调用方给）。
     */
    private suspend fun chatWithRetry(
        cfg: AiConfig,
        system: String,
        user: String,
        maxTokens: Int,
    ): String {
        suspend fun call(tokens: Int): String =
            reflectionClient
                .chat(
                    baseUrl = cfg.baseUrl,
                    apiKey = cfg.apiKey,
                    model = cfg.model,
                    messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                    temperature = 0.2,
                    maxTokens = tokens,
                    thinkingType = ThinkingParams.forConfig(cfg, forceDisable = true),
                ).content

        return try {
            call(maxTokens)
        } catch (e: EmptyContentException) {
            logger.w(TAG, "结构化调用空正文（finish=${e.finishReason}），放宽额度重试一次")
            call(GenerationLimits.retryFor(maxTokens))
        }
    }

    /**
     * 反思上下文里只看同视角的经验。
     *
     * 开启隔离后，别人格攒下的偏好不该影响这次的总结；
     * personaId 为 null（未开隔离 / 旧记录）时保持全量。
     * personaId 为空的经验是旧数据或公共经验，仍然保留。
     */
    private suspend fun lessonsForReflection(personaId: Long?): List<ChimeLesson> {
        val all = chimeLessonStore.all()
        return if (personaId == null) all else all.filter { it.personaId == null || it.personaId == personaId }
    }

    /**
     * 同类归并：某类别条数超过上限时，让模型把近义条目压成 ≤3 条。
     *
     * 只在「超限」时触发，成本可控；失败保持原样，下次再试。
     * 合并后支撑数按比例摊到新条目上，保留"这条经验有多可靠"的信息。
     */
    private suspend fun mergeCategoryIfNeeded(
        scopeGroupId: String?,
        category: String,
        cfg: AiConfig,
        personaId: Long? = null,
    ): String? {
        val maxLessons = settings.chitchatConfig.value.maxLessons
        val lessons = chimeLessonStore.sameCategory(scopeGroupId, category, personaId)
        if (lessons.size <= ChimeLesson.MAX_PER_CATEGORY) {
            chimeLessonStore.trim(maxLessons)
            return null
        }

        val (system, user) = ChimeReflection.buildMergePrompt(category, lessons)
        val reply =
            runCatching {
                chatWithRetry(cfg, system, user, maxTokens = GenerationLimits.forConfig(cfg))
            }.getOrNull() ?: return null

        val merged = ChimeReflection.parseMerged(reply)
        if (merged.isEmpty()) return null

        val now = System.currentTimeMillis()
        val support = lessons.sumOf { it.supportCount }
        val hits = lessons.sumOf { it.hits }
        chimeLessonStore.deleteMany(lessons.map { it.id })
        merged.forEach { text ->
            chimeLessonStore.insert(
                ChimeLesson(
                    text = text,
                    source = ChimeLesson.SOURCE_REFLECTION,
                    scopeGroupId = scopeGroupId,
                    createdAt = now,
                    updatedAt = now,
                    category = category,
                    supportCount = (support / merged.size).coerceAtLeast(1),
                    hits = (hits / merged.size).coerceAtLeast(0),
                    personaId = personaId,
                ),
            )
        }
        chimeLessonStore.trim(maxLessons)
        return "并把「${LessonCategories.label(category)}」的 ${lessons.size} 条经验归并成 ${merged.size} 条"
    }

    suspend fun setChimeLessonEnabled(id: Long, enabled: Boolean) {
        chimeLessonStore.setEnabled(id, enabled)
    }

    suspend fun updateChimeLessonText(id: Long, text: String) {
        val clean = text.trim().take(ChimeLesson.MAX_TEXT_CHARS)
        if (clean.isBlank()) return
        chimeLessonStore.updateText(id, clean, System.currentTimeMillis())
    }

    suspend fun deleteChimeLesson(id: Long) {
        chimeLessonStore.delete(id)
    }

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

        // 启动时收敛训练数据：决策日志 7 天 / 2000 条，经验库 40 条
        scope.launch {
            val before = System.currentTimeMillis() - CHIME_RETENTION_DAYS * 24 * 3600 * 1000
            runCatching { chimeDecisionStore.trim(before, CHIME_MAX_DECISIONS) }
            runCatching {
                chimeLessonStore.trim(settings.chitchatConfig.value.maxLessons)
            }
        }

        // 人格引导：旧设置里的系统提示词 → 第一条人格档案；零提示词用户则建种子档案
        scope.launch {
            runCatching { ensurePersonaBootstrap() }.onFailure {
                appendLog(LogLevel.WARN, TAG, "人格引导失败：${it.message ?: it::class.simpleName}")
            }
        }

    }

    /** 保证成长轮询在跑；重复调用是幂等的。 */
    private fun ensureGrowthRunning() {
        if (growthJob?.isActive == true) return
        growthJob =
            runCatching { personaGrowthScheduler.start(scope) }
                .onFailure { appendLog(LogLevel.WARN, TAG, "人格成长调度启动失败：${it.message}") }
                .getOrNull()
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

        val generation = ++startGeneration
        scope.launch {
            // 保证第一条消息就有人格：引导必须在 build 之前完成
            runCatching { ensurePersonaBootstrap() }
            if (generation != startGeneration) return@launch
            // 用 first() 直接取当前值：stateIn().value 在还没有订阅者时
            // 只会返回初始默认值，会导致省电模式设置被忽略。
            val powerSaving = settings.powerSaving.first()
            if (generation != startGeneration) return@launch
            val intents = if (powerSaving) Intents.MESSAGES_ONLY else Intents.DEFAULT
            build(credentials.appId, credentials.clientSecret, intents)
            // build 之后再次校验：构建期间用户可能已经点了停止
            if (generation != startGeneration) {
                runtime?.stop()
                gateway?.stop()
                runtime = null
                gateway = null
                _status.value = GatewayStatus(state = ConnectionState.IDLE)
                return@launch
            }
            runtime?.start()
            ensureGrowthRunning()
        }
    }

    /** 停止机器人。 */
    fun stop() {
        // 让尚未完成的启动协程作废
        startGeneration++
        growthJob?.cancel()
        growthJob = null
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

    /**
     * 机器人被从列表移除时释放资源：停连接并取消所有协程。
     *
     * 数据库由 [BotManager] 负责关闭——它才是库的持有者。
     */
    fun close() {
        stop()
        scope.cancel()
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

    /**
     * 用 AI 优化一条人设。
     *
     * 进化前先把当前版本存为 INITIAL（只存一次），因此第一次优化永远可回滚；
     * 之后每版都留快照，最多保留 [PersonaRepository] 的 20 条。
     *
     * @param personaId 人设 id；null 表示「全局系统提示词」
     * @param instruction 用户的额外要求，如「更毒舌一点，但别骂人」
     * @return 新的提示词
     */
    /**
     * 人格引导（幂等）。
     *
     * 三种情况：
     * 1. 库里已有档案 → 什么都不做；
     * 2. 旧设置里有用户写的系统提示词 → 导成一条 MANUAL 档案并激活
     *    （标 MANUAL 是刻意的：手写的东西不能被后台自动改写）；
     * 3. 什么都没有（新用户 / 出厂默认）→ 随机种子建一份 EMBRYO 档案。
     *
     * 标记最后才写：任何一步失败都不能置位，下次启动会重试。
     */
    private suspend fun ensurePersonaBootstrap() =
        bootstrapMutex.withLock {
            val imported = settings.legacyPersonaImported()
            if (imported && personaRepository.countProfiles() > 0) return@withLock

            if (personaRepository.countProfiles() == 0) {
                val legacy = settings.loadAiConfigOnce().systemPrompt.trim()
                val usable =
                    legacy.isNotEmpty() && legacy != AiConfig.LEGACY_DEFAULT_SYSTEM_PROMPT
                if (usable) {
                    val id =
                        personaRepository.createProfile(
                            name = "从旧设置继承的人格",
                            prompt = legacy,
                            stage = PersonaStage.MANUAL,
                        )
                    personaRepository.addSnapshot(
                        personaId = id,
                        name = "从旧设置继承的人格",
                        prompt = legacy,
                        kind = com.qbot.core.ai.PersonaSnapshotKind.INITIAL,
                        note = "从旧的系统提示词导入（自动存档，不可删除）",
                    )
                    personaRepository.activate(id)
                    appendLog(LogLevel.INFO, TAG, "人格引导：旧系统提示词已导入为一份人格档案")
                }
            }

            if (personaRepository.active() == null) {
                val seed = PersonaSeed.random()
                val id = personaRepository.createSeededProfile(seed)
                personaRepository.activate(id)
                appendLog(LogLevel.INFO, TAG, "人格引导：已用「${seed.label}」种子创建第一份人格")
            }
            settings.markLegacyPersonaImported()
        }

    /** 当前激活的人格档案。 */
    fun observeActivePersona(): Flow<Persona?> = personaRepository.observeActive()

    /** 全部人格档案。 */
    fun observePersonaProfiles(): Flow<List<Persona>> = personaRepository.profiles

    /** 新建一份人格档案并立刻切换过去。 */
    suspend fun createPersonaProfile(
        name: String,
        seed: PersonaSeed = PersonaSeed.random(),
    ): Long {
        val id = personaRepository.createProfile(name = name, seedId = seed.name)
        personaRepository.activate(id)
        appendLog(LogLevel.INFO, TAG, "新建人格「$name」（种子：${seed.label}）")
        return id
    }

    /**
     * 新建一份「手写人格」：正文是用户自己的系统提示词，完全自由格式。
     *
     * 这是和「自动养成」并存的另一条路：AI 不会碰它，除非用户显式交给它。
     */
    suspend fun createManualPersona(
        name: String,
        prompt: String,
    ): Long {
        val id =
            personaRepository.createProfile(
                name = name,
                prompt = prompt,
                stage = PersonaStage.MANUAL,
            )
        personaRepository.activate(id)
        appendLog(LogLevel.INFO, TAG, "新建手写人格「$name」")
        return id
    }

    /** 把人格抢回手里：停止自动成长，正文保持不变。 */
    suspend fun takeOverPersona(id: Long) {
        personaRepository.takeOver(id)
        appendLog(LogLevel.INFO, TAG, "人格已转为手动：以后只按你写的来")
    }

    suspend fun activatePersona(id: Long) {
        personaRepository.activate(id)
        appendLog(LogLevel.INFO, TAG, "已切换人格档案 #$id")
    }

    suspend fun deletePersona(id: Long) {
        personaRepository.deleteProfileOrOverride(id)
    }

    suspend fun setPersonaFrozen(
        id: Long,
        frozen: Boolean,
    ) {
        personaRepository.setFrozen(id, frozen)
    }

    /** 重抽性格种子：正文清空，回到未破壳状态。 */
    suspend fun reseedPersona(id: Long) {
        personaRepository.reseed(id)
    }

    /** 把用户手写的人设交给自动成长，从当前消息位置开始累计。 */
    suspend fun handOverPersonaToGrowth(id: Long) {
        personaRepository.handOverToGrowth(id, database.roomMessageDao().maxId() ?: 0)
        appendLog(LogLevel.INFO, TAG, "人格已交给自动成长")
    }

    /**
     * 手动催一次成长（忽略消息量与间隔门槛）。
     *
     * 返回 false 表示没跑起来：没配 AI、正在跑、已冻结，或模型被守卫拦下。
     */
    suspend fun growPersonaNow(): Boolean =
        runCatching { personaGrowthScheduler.force() }
            .onFailure { appendLog(LogLevel.WARN, TAG, "手动成长失败：${it.message}") }
            .getOrDefault(false)

    suspend fun evolvePersona(
        personaId: Long?,
        instruction: String? = null,
    ): Result<String> =
        runCatching {
            val aiConfig = settings.aiConfig.value
            require(aiConfig.enabled && aiConfig.isReady) { "先在「AI 接入」里配置并启用模型" }

            val persona = personaId?.let { id -> personaRepository.all().firstOrNull { it.id == id } }
            val name = persona?.name ?: "全局默认"
            val current = (persona?.prompt ?: aiConfig.systemPrompt).trim()

            // 还没破壳（或人设为空）：手动按钮从"优化"变成"按种子破壳"。
            // 这条路径本来被 require(current.isNotEmpty()) 挡死了，
            // 现在由 PersonaGrowth.birth 接管。
            if (current.isEmpty()) {
                require(persona != null) {
                    "还没有人格档案，先去「AI · 人设」里新建一个"
                }
                val seed = PersonaSeed.of(persona.seedId)
                val samples = recentSamplesForEvolution()
                val draft =
                    PersonaGrowth(LlmClient(http = http, logger = logger))
                        .birth(seed, samples, runtime?.botName, aiConfig)
                if (!personaRepository.hasInitialSnapshot(persona.id)) {
                    personaRepository.addSnapshot(
                        personaId = persona.id,
                        name = persona.name,
                        prompt = PersonaGrowth.embryoSkeleton(persona.seedId),
                        kind = com.qbot.core.ai.PersonaSnapshotKind.INITIAL,
                        note = "种子骨架（破壳前，不可删除）",
                    )
                }
                val committed =
                    personaRepository.commitBirth(
                        id = persona.id,
                        prompt = draft.prompt,
                        seedId = seed.name,
                        cursor = database.roomMessageDao().maxId() ?: 0,
                        note = draft.note,
                    )
                // 并发时可能已经被定时破壳抢先：没写成就不要留快照，
                // 否则会出现"快照里有、实际没生效"的幽灵版本。
                if (committed) {
                    personaRepository.addSnapshot(
                        personaId = persona.id,
                        name = persona.name,
                        prompt = draft.prompt,
                        kind = com.qbot.core.ai.PersonaSnapshotKind.BIRTH,
                        note = draft.note,
                    )
                    appendLog(LogLevel.INFO, TAG, "手动破壳完成（${seed.label}）")
                } else {
                    appendLog(LogLevel.INFO, TAG, "手动破壳被并发取消（可能已在别处完成）")
                }
                return@runCatching draft.prompt
            }

            if (!personaRepository.hasInitialSnapshot(personaId)) {
                personaRepository.addSnapshot(
                    personaId = personaId,
                    name = name,
                    prompt = current,
                    kind = com.qbot.core.ai.PersonaSnapshotKind.INITIAL,
                    note = "初始版本（自动存档，不可删除）",
                )
            }

            val evolution =
                com.qbot.core.ai.PersonaEvolver(LlmClient(http = http, logger = logger))
                    .evolve(
                        personaName = name,
                        currentPrompt = current,
                        samples = recentSamplesForEvolution(),
                        instruction = instruction,
                        config = aiConfig,
                    )

            if (persona != null) {
                personaRepository.save(persona.copy(prompt = evolution.prompt))
            } else {
                // 全局人设就是「AI 接入」里的系统提示词
                settings.saveAiConfig(aiConfig.copy(systemPrompt = evolution.prompt))
            }
            personaRepository.addSnapshot(
                personaId = personaId,
                name = name,
                prompt = evolution.prompt,
                kind = com.qbot.core.ai.PersonaSnapshotKind.EVOLVED,
                note = instruction?.takeIf { it.isNotBlank() } ?: "AI 优化",
            )
            evolution.prompt
        }

    /** 取最近活跃群的一段群聊作为进化样本；没有群就直接用空样本。 */
    private suspend fun recentSamplesForEvolution(): List<com.qbot.core.ai.RoomMessage> {
        val active = runCatching { groupStore.allActive() }.getOrDefault(emptyList())
        val groupId = active.maxByOrNull { it.lastSeenAt }?.groupId ?: return emptyList()
        return runCatching { memoryPipeline.recentGroupMessages(groupId, 40) }
            .getOrDefault(emptyList())
    }

    /** 某个目标的版本历史（UI 用）。 */
    suspend fun personaSnapshots(personaId: Long?) =
        personaRepository.snapshots(personaId)

    /** 实时观察某个群收到的消息（维护端「群消息」查看器）。 */
    fun observeGroupMessages(
        groupId: String,
        limit: Int = 200,
    ) = memoryPipeline.observeGroupMessages(groupId, limit)

    // ---------- 配置导入导出（换机 / 备份） ----------

    /**
     * 导出全部用户配置为 JSON 文本。
     *
     * 包含凭证与 API Key（明文）——备份文件请妥善保管。
     * 长期记忆（事实 / 最近对话 / 内心戏）一起导出；日志、AI 会话历史、
     * 版本快照与决策日志不导出。
     */
    suspend fun exportBackup(): String {
        val ai = settings.aiConfig.value
        val memory = settings.memoryConfig.value
        val greeting = settings.greetingConfig.value
        val chitchat = settings.chitchatConfig.value
        val creds = settings.credentials()

        // 备份里用名称而不是 id 关联人格：自增 id 换机后对不上
        val personaNames =
            runCatching { personaRepository.all() }.getOrDefault(emptyList()).associate { it.id to it.name }

        val backup =
            ConfigBackup(
                exportedAt = System.currentTimeMillis(),
                credentials =
                    CredentialsBackup(
                        appId = creds.appId,
                        clientSecret = creds.clientSecret,
                    ).takeIf { it.appId.isNotBlank() },
                ai =
                    AiBackup(
                        enabled = ai.enabled,
                        baseUrl = ai.baseUrl,
                        apiKey = ai.apiKey,
                        model = ai.model,
                        systemPrompt = ai.systemPrompt,
                        temperature = ai.temperature,
                        maxTokens = ai.maxTokens,
                        mode = ai.mode.name,
                        contextTurns = ai.contextTurns,
                        replyOnError = ai.replyOnError,
                        thinkingMode = ai.thinkingMode.name,
                        contextTokenBudget = ai.contextTokenBudget,
                    ),
                memory =
                    MemoryBackup(
                        enabled = memory.enabled,
                        factsPerPerson = memory.factsPerPerson,
                        roomContextEnabled = memory.roomContextEnabled,
                        roomContextSize = memory.roomContextSize,
                        learnNames = memory.learnNames,
                        learnFacts = memory.learnFacts,
                        personaRewrite = memory.personaRewrite,
                        personalThreadEnabled = memory.personalThreadEnabled,
                        personalThreadSize = memory.personalThreadSize,
                        personaScoped = memory.personaScoped,
                    ),
                greeting = GreetingBackup(enabled = greeting.enabled, text = greeting.text),
                chitchat =
                    ChitchatBackup(
                        enabled = chitchat.enabled,
                        baseCooldownSeconds = (chitchat.baseCooldownMillis / 1000).toInt(),
                        maxPerHour = chitchat.maxPerHour,
                        aiJudge = chitchat.aiJudge,
                        probabilityPercent = (chitchat.probability * 100).toInt().coerceIn(1, 100),
                        maxPerDay = chitchat.maxPerDay,
                        answerOnName = chitchat.answerOnName,
                        minScore = chitchat.minScore,
                        lengthScore = chitchat.scoreWeights.lengthScore,
                        questionScore = chitchat.scoreWeights.questionScore,
                        shortPenalty = chitchat.scoreWeights.shortPenalty,
                        mentionPenalty = chitchat.scoreWeights.mentionPenalty,
                        topicScore = chitchat.scoreWeights.topicScore,
                        respondBotScore = chitchat.scoreWeights.respondBotScore,
                        quietScore = chitchat.scoreWeights.quietScore,
                        spamPenalty = chitchat.scoreWeights.spamPenalty,
                        chimeRules = chitchat.chimeRules,
                        insultExtraWords = chitchat.insultExtraWords,
                        maxLessons = chitchat.maxLessons,
                        maxInjectedLessons = chitchat.maxInjectedLessons,
                        judgeContextSize = chitchat.judgeContextSize,
                    ),
                rules =
                    database.ruleDao().all().first().map { rule ->
                        RuleBackup(
                            id = rule.id,
                            name = rule.name,
                            enabled = rule.enabled,
                            matchType = rule.matchType,
                            pattern = rule.pattern,
                            reply = rule.reply,
                            scope = rule.scope,
                            priority = rule.priority,
                        )
                    },
                // 覆盖层与人格档案分开导：前者是"局部例外"，后者是"我是谁"
                personas =
                    personaRepository.all()
                        .filter { it.kind == PersonaKind.OVERRIDE }
                        .map { persona ->
                            PersonaBackup(
                                name = persona.name,
                                senderId = persona.senderId,
                                scopeId = persona.scopeId,
                                prompt = persona.prompt,
                            )
                        },
                personaProfiles =
                    personaRepository.all()
                        .filter { it.kind == PersonaKind.PROFILE }
                        .map { persona ->
                            PersonaProfileBackup(
                                name = persona.name,
                                prompt = persona.prompt,
                                isActive = persona.active,
                                stage = persona.stage.name,
                                seedId = persona.seedId,
                                frozen = persona.frozen,
                                growthCount = persona.growthCount,
                            )
                        },
                displayNames =
                    database.identityDao().recent(1000).map { entry ->
                        DisplayNameBackup(
                            senderId = entry.senderId,
                            displayName = entry.displayName,
                            source = entry.source,
                        )
                    },
                // 群档案：在群状态与主动发言权限一并带上，否则换机后可能误判能不能主动开口
                groupBotNames =
                    groupStore.all().map { profile ->
                        GroupBotNameBackup(
                            groupId = profile.groupId,
                            botName = profile.botName,
                            active = profile.active,
                            proactiveAllowed = profile.proactiveAllowed,
                            joinedAt = profile.joinedAt,
                            lastSeenAt = profile.lastSeenAt,
                        )
                    },
                // 关系与梗：都是"养"出来的，换机不该从零开始
                relations =
                    runCatching { socialStore.allRelations() }.getOrDefault(emptyList())
                        .map { (personaId, relation) ->
                        RelationBackup(
                            userId = relation.userId,
                            displayName = relation.displayName,
                            familiarity = relation.familiarity,
                            affinity = relation.affinity,
                            interactionCount = relation.interactionCount,
                            lastInteractionAt = relation.lastInteractionAt,
                            lastNote = relation.lastNote,
                            // 0 是共用视角，不写名字；按名字而不是 id 关联人格
                            personaName = personaNames[personaId],
                        )
                    },
                slang =
                    runCatching { socialStore.allSlang() }.getOrDefault(emptyList()).map { term ->
                            SlangBackup(
                                term = term.term,
                                meaning = term.meaning,
                                usage = term.usage,
                                scopeGroupId = term.scopeGroupId,
                                hits = term.hits,
                            )
                        },
                // 表情包库：链接轻量，值得跟着配置走
                memes =
                    runCatching { socialStore.allMemes() }.getOrDefault(emptyList()).map { meme ->
                        MemeBackup(
                            url = meme.url,
                            source = meme.source,
                            hits = meme.hits,
                        )
                    },
                // 人设设定与说话约束：用户手工调过的东西，换机不该丢
                lore =
                    runCatching { database.loreDao().all() }.getOrDefault(emptyList()).map { entry ->
                        LoreBackup(
                            personaName = entry.personaId?.let { personaNames[it] },
                            kind = entry.kind,
                            name = entry.name,
                            note = entry.note,
                            scopeGroupId = entry.scopeGroupId,
                            enabled = entry.enabled,
                            source = entry.source,
                        )
                    },
                styleRules =
                    runCatching { database.styleRuleDao().all() }.getOrDefault(emptyList()).map { row ->
                        StyleRuleBackup(
                            id = row.id,
                            text = row.text,
                            category = row.category,
                            source = row.source,
                            enabled = row.enabled,
                        )
                    },
                // 长期记忆与最近对话：换机后不该从零开始
                facts =
                    runCatching { database.factDao().all() }.getOrDefault(emptyList()).map { fact ->
                        FactBackup(
                            senderId = fact.senderId,
                            text = fact.text,
                            kind = fact.kind,
                            source = fact.source,
                            updatedAt = fact.updatedAt,
                        )
                    },
                roomMessages =
                    runCatching { database.roomMessageDao().exportRecent(BACKUP_ROOM_MESSAGES) }
                        .getOrDefault(emptyList())
                        // DAO 按 id 倒序取最近 N 条，导出时恢复成时间正序
                        .asReversed()
                        .map { row ->
                            RoomMessageBackup(
                                scopeKey = row.scopeKey,
                                senderId = row.senderId,
                                senderName = row.senderName,
                                role = row.role,
                                content = row.content,
                                timestampMillis = row.timestampMillis,
                                isAt = row.isAt,
                                externalId = row.externalId,
                                decision = row.decision,
                                personaName = row.personaId?.let { personaNames[it] },
                            )
                        },
                innerThoughts =
                    runCatching { database.innerThoughtDao().recentOnce(BACKUP_INNER_THOUGHTS) }
                        .getOrDefault(emptyList())
                        .asReversed()
                        .map { thought ->
                            InnerThoughtBackup(
                                groupId = thought.groupId,
                                senderId = thought.senderId,
                                senderName = thought.senderName,
                                spoken = thought.spoken,
                                thought = thought.thought,
                                createdAt = thought.createdAt,
                                personaName = thought.personaId?.let { personaNames[it] },
                            )
                        },
                // 训练成果：经验库跟着配置走，换机后不必重新教一遍
                chimeLessons =
                    chimeLessonStore.all().map { lesson ->
                        LessonBackup(
                            text = lesson.text,
                            source = lesson.source,
                            groupId = lesson.scopeGroupId,
                            hits = lesson.hits,
                            createdAt = lesson.createdAt,
                            updatedAt = lesson.updatedAt,
                            category = lesson.category,
                            supportCount = lesson.supportCount,
                            personaName = personaNames[lesson.personaId],
                        )
                    },
            )
        return ConfigBackup.encode(backup)
    }

    /**
     * 导入配置备份。
     *
     * 合并策略：规则与群名片按 id/群 upsert（同 id 覆盖）；
     * 人设按「名称+绑定对象」匹配，命中更新提示词、未命中新增；
     * 备注名受优先级守护（不会覆盖手动备注为自称）；
     * 各类配置整组覆盖。返回可读的导入摘要。
     */
    suspend fun importBackup(jsonText: String): String {
        val backup =
            runCatching { ConfigBackup.decode(jsonText) }.getOrElse { e ->
                return "导入失败：文件不是有效的配置备份（${e.message}）"
            }

        var applied = 0

        // 备份来自更新版本时字段会被 ignoreUnknownKeys 静默忽略，
        // 但语义猜不了（比如新版本把某字段换成了另一种含义），必须明确提示。
        val newerVersion = backup.version > ConfigBackup.CURRENT_VERSION

        backup.credentials?.takeIf { it.appId.isNotBlank() }?.let {
            settings.saveCredentials(it.appId, it.clientSecret)
            applied++
        }

        backup.ai?.let { dto ->
            val current = settings.aiConfig.value
            val mode =
                runCatching { AiMode.valueOf(dto.mode) }.getOrDefault(current.mode)
            val thinking =
                runCatching { ThinkingMode.valueOf(dto.thinkingMode) }
                    .getOrDefault(current.thinkingMode)
            settings.saveAiConfig(
                current.copy(
                    enabled = dto.enabled,
                    baseUrl = dto.baseUrl,
                    apiKey = dto.apiKey,
                    model = dto.model,
                    systemPrompt = dto.systemPrompt,
                    temperature = dto.temperature,
                    maxTokens = dto.maxTokens,
                    mode = mode,
                    contextTurns = dto.contextTurns,
                    replyOnError = dto.replyOnError,
                    thinkingMode = thinking,
                    contextTokenBudget = dto.contextTokenBudget,
                ),
            )
            applied++
        }

        backup.memory?.let {
            settings.saveMemoryConfig(
                MemoryConfig(
                    enabled = it.enabled,
                    factsPerPerson = it.factsPerPerson,
                    roomContextEnabled = it.roomContextEnabled,
                    roomContextSize = it.roomContextSize,
                    learnNames = it.learnNames,
                    learnFacts = it.learnFacts,
                    personaRewrite = it.personaRewrite,
                    personalThreadEnabled = it.personalThreadEnabled,
                    personalThreadSize = it.personalThreadSize,
                    personaScoped = it.personaScoped,
                ),
            )
            applied++
        }

        backup.greeting?.let {
            settings.saveGreetingConfig(GreetingConfig(enabled = it.enabled, text = it.text))
            applied++
        }

        backup.chitchat?.let {
            settings.saveChitchatConfig(
                ChitchatConfig(
                    enabled = it.enabled,
                    baseCooldownMillis = it.baseCooldownSeconds.coerceAtLeast(1).toLong() * 1000,
                    maxPerHour = it.maxPerHour,
                    maxPerDay = it.maxPerDay.coerceIn(0, 1000),
                    aiJudge = it.aiJudge,
                    probability = it.probabilityPercent.coerceIn(1, 100) / 100.0,
                    answerOnName = it.answerOnName,
                    minScore = it.minScore.coerceIn(0, 40),
                    scoreWeights =
                        ScoreWeights(
                            lengthScore = it.lengthScore.coerceIn(0, 20),
                            questionScore = it.questionScore.coerceIn(0, 40),
                            shortPenalty = it.shortPenalty.coerceIn(-40, 0),
                            mentionPenalty = it.mentionPenalty.coerceIn(-40, 0),
                            topicScore = it.topicScore.coerceIn(0, 30),
                            respondBotScore = it.respondBotScore.coerceIn(0, 30),
                            quietScore = it.quietScore.coerceIn(0, 20),
                            spamPenalty = it.spamPenalty.coerceIn(-30, 0),
                        ),
                    chimeRules = it.chimeRules.filter { r -> r.isNotBlank() }.take(10),
                    insultExtraWords = it.insultExtraWords.filter { w -> w.isNotBlank() }.take(50),
                    maxLessons = it.maxLessons.coerceIn(20, 200),
                    maxInjectedLessons = it.maxInjectedLessons.coerceIn(3, 12),
                    judgeContextSize = it.judgeContextSize.coerceIn(6, 24),
                ),
            )
            applied++
        }

        // 接话经验与关系：必须等人格档案导入完成后才能按「人格名 → id」重建归属，
        // 因此挪到本方法后半段统一处理（原先在这里，会把经验全落到共用视角）。

        var ruleCount = 0
        backup.rules.forEach { dto ->
            val matchType = runCatching { MatchType.valueOf(dto.matchType) }.getOrNull() ?: return@forEach
            val scope = runCatching { RuleScope.valueOf(dto.scope) }.getOrNull() ?: return@forEach
            database.ruleDao().upsert(
                RuleEntity(
                    id = dto.id,
                    name = dto.name,
                    enabled = dto.enabled,
                    matchType = matchType.name,
                    pattern = dto.pattern,
                    reply = dto.reply,
                    scope = scope.name,
                    priority = dto.priority,
                ),
            )
            ruleCount++
        }

        // ---- 人格档案：必须在覆盖层之前导入 ----
        var profileNew = 0
        var profileUpdated = 0
        val profilesBefore = personaRepository.all().filter { it.kind == PersonaKind.PROFILE }

        suspend fun upsertProfile(
            name: String,
            prompt: String,
            stageName: String,
            seedId: String?,
            frozen: Boolean,
            isActive: Boolean,
            growthCount: Int = 0,
        ): Long {
            val stage =
                runCatching { PersonaStage.valueOf(stageName) }
                    .getOrDefault(PersonaStage.MANUAL)
            val hit = profilesBefore.firstOrNull { it.name == name }
            val id =
                if (hit != null) {
                    personaRepository.save(hit.copy(prompt = prompt))
                    personaRepository.applyProfileMeta(hit.id, stage, seedId, frozen)
                    profileUpdated++
                    hit.id
                } else {
                    val created =
                        personaRepository.createProfile(
                            name = name,
                            prompt = prompt,
                            stage = stage,
                            seedId = seedId,
                        )
                    personaRepository.applyProfileMeta(created, stage, seedId, frozen)
                    profileNew++
                    created
                }
            if (growthCount > 0) {
                runCatching { personaRepository.restoreGrowthCount(id, growthCount) }
            }
            if (isActive) personaRepository.activate(id)
            return id
        }

        // 关系随人格一起在文末导入（需要先建好人格档案）

        if (backup.slang.isNotEmpty()) {
            runCatching {
                socialStore.clearSlang()
                backup.slang.forEach { dto ->
                    socialStore.putSlang(
                        SlangTerm(
                            term = dto.term,
                            meaning = dto.meaning,
                            usage = dto.usage,
                            scopeGroupId = dto.scopeGroupId,
                            hits = dto.hits,
                            source = SlangTerm.SOURCE_HARVEST,
                        ),
                    )
                }
            }
            applied++
        }

        // 旧备份（version < 4）没有 memes 字段，反序列化出空列表，自然跳过
        if (backup.memes.isNotEmpty()) {
            runCatching {
                socialStore.clearMemes()
                backup.memes.forEach { dto ->
                    socialStore.putMeme(
                        MemeImage(
                            url = dto.url,
                            source = dto.source,
                            hits = dto.hits,
                        ),
                    )
                }
            }
            applied++
        }

        if (backup.personaProfiles.isNotEmpty()) {
            backup.personaProfiles.forEach { dto ->
                upsertProfile(
                    name = dto.name,
                    prompt = dto.prompt,
                    stageName = dto.stage,
                    seedId = dto.seedId,
                    frozen = dto.frozen,
                    isActive = dto.isActive,
                    growthCount = dto.growthCount,
                )
            }
            applied++
        } else {
            // 旧备份没有档案概念：优先用"全局人设行"，其次用 ai.systemPrompt
            val legacyGlobal =
                backup.personas.firstOrNull {
                    it.senderId.isNullOrBlank() && it.scopeId.isNullOrBlank() && it.prompt.isNotBlank()
                }
            val legacyPrompt =
                legacyGlobal?.prompt
                    ?: backup.ai?.systemPrompt
                        ?.takeIf {
                            it.isNotBlank() && it != AiConfig.LEGACY_DEFAULT_SYSTEM_PROMPT
                        }
            if (legacyPrompt != null && profilesBefore.isEmpty()) {
                upsertProfile(
                    name = legacyGlobal?.name ?: "从旧备份继承的人格",
                    prompt = legacyPrompt,
                    stageName = PersonaStage.MANUAL.name,
                    seedId = null,
                    frozen = false,
                    isActive = true,
                )
            }
            // 两者都没有 → 保留本机现有档案：不能因为导入了旧配置就清空人格
        }

        var personaNew = 0
        var personaUpdated = 0
        val existing = personaRepository.all()
        // 旧备份里的"全局人设行"已经在上面变成档案了，这里跳过，避免它变成覆盖层抢人格
        backup.personas
            .filterNot { it.senderId.isNullOrBlank() && it.scopeId.isNullOrBlank() }
            .forEach { dto ->
            val hit =
                existing.find {
                    it.name == dto.name && it.senderId == dto.senderId && it.scopeId == dto.scopeId
                }
            personaRepository.save(
                Persona(
                    id = hit?.id ?: 0,
                    senderId = dto.senderId,
                    scopeId = dto.scopeId,
                    name = dto.name,
                    prompt = dto.prompt,
                ),
            )
            if (hit == null) personaNew++ else personaUpdated++
        }

        // 人设设定与说话约束：放在人格导入之后，才能按名称重新绑定人格
        var loreCount = 0
        if (backup.lore.isNotEmpty()) {
            runCatching {
                val names = personaRepository.all().associateBy { it.name }
                val dao = database.loreDao()
                // 备份里是完整设定集：整表替换，避免恢复后还残留旧人格的梗/旧认识的人
                dao.clearAll()
                backup.lore.forEach { dto ->
                    val personaId = dto.personaName?.let { names[it]?.id }
                    dao.upsert(
                        LoreEntity(
                            id = 0,
                            personaId = personaId,
                            kind = dto.kind,
                            name = dto.name,
                            note = dto.note,
                            scopeGroupId = dto.scopeGroupId,
                            enabled = dto.enabled,
                            source = dto.source,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    loreCount++
                }
            }
            applied++
        }

        // ---- 长期记忆：事实 / 最近对话 / 内心戏 ----
        // 人格归属已在上面建好，这里按名称重新绑定。
        val personaIdsForMemory = personaRepository.all().associate { it.name to it.id }

        var factCount = 0
        if (backup.facts.isNotEmpty()) {
            runCatching {
                val dao = database.factDao()
                // 按 (senderId, text) 去重：重复导入同一份备份不会翻倍
                val existing = dao.all().map { it.senderId to it.text }.toMutableSet()
                backup.facts.forEach { dto ->
                    val sender = dto.senderId.trim()
                    val text = dto.text.trim()
                    if (sender.isEmpty() || text.isEmpty()) return@forEach
                    if (!existing.add(sender to text)) return@forEach
                    dao.insert(
                        FactEntity(
                            senderId = sender,
                            text = text,
                            kind =
                                runCatching { FactKind.valueOf(dto.kind) }
                                    .getOrDefault(FactKind.NOTE)
                                    .name,
                            source =
                                runCatching { FactSource.valueOf(dto.source) }
                                    .getOrDefault(FactSource.EXTRACTED)
                                    .name,
                            updatedAt = dto.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        ),
                    )
                    factCount++
                }
                factStore.trimAll(settings.memoryConfig.value.factsPerPerson)
            }
            if (factCount > 0) applied++
        }

        var messageCount = 0
        if (backup.roomMessages.isNotEmpty()) {
            runCatching {
                val dao = database.roomMessageDao()
                backup.roomMessages.forEach { dto ->
                    val scopeKey = dto.scopeKey.trim()
                    val content = dto.content
                    if (scopeKey.isEmpty() || content.isBlank()) return@forEach
                    // 平台消息 id 去重；没有 id 的（机器人自己的发言）靠内容+时间兜底
                    val externalId = dto.externalId?.takeIf { it.isNotBlank() }
                    if (externalId != null && dao.countByExternalId(scopeKey, externalId) > 0) {
                        return@forEach
                    }
                    val timestamp = dto.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
                    val role = dto.role.takeIf { it == "assistant" } ?: "user"
                    if (externalId == null && dao.countSame(scopeKey, role, content, timestamp) > 0) {
                        return@forEach
                    }
                    dao.insert(
                        RoomMessageEntity(
                            scopeKey = scopeKey,
                            senderId = dto.senderId,
                            senderName = dto.senderName,
                            role = role,
                            content = content,
                            timestampMillis = timestamp,
                            isAt = dto.isAt,
                            externalId = externalId,
                            decision = dto.decision,
                            personaId = dto.personaName?.let { personaIdsForMemory[it] },
                        ),
                    )
                    messageCount++
                }
            }
            if (messageCount > 0) applied++
        }

        var thoughtCount = 0
        if (backup.innerThoughts.isNotEmpty()) {
            runCatching {
                val dao = database.innerThoughtDao()
                backup.innerThoughts.forEach { dto ->
                    if (dto.spoken.isBlank() && dto.thought.isBlank()) return@forEach
                    val createdAt = dto.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                    // 内心戏没有平台 id，按会话+人+时间+原话去重，重复导入不翻倍
                    if (dao.countSame(dto.groupId, dto.senderId, dto.spoken, createdAt) > 0) {
                        return@forEach
                    }
                    dao.insert(
                        InnerThoughtEntity(
                            groupId = dto.groupId,
                            senderId = dto.senderId,
                            senderName = dto.senderName,
                            spoken = dto.spoken,
                            thought = dto.thought.take(600),
                            createdAt = createdAt,
                            personaId = dto.personaName?.let { personaIdsForMemory[it] },
                        ),
                    )
                    thoughtCount++
                }
                dao.trim(BACKUP_INNER_THOUGHTS)
            }
            if (thoughtCount > 0) applied++
        }

        // ---- 接话经验：按人格名重建归属，同作用域 + 文本相似去重 ----
        var lessonCount = 0
        if (backup.chimeLessons.isNotEmpty()) {
            val personaIds = personaRepository.all().associate { it.name to it.id }
            val merged = chimeLessonStore.all().toMutableList()
            val now = System.currentTimeMillis()
            backup.chimeLessons.forEach { dto ->
                val text = dto.text.trim().take(ChimeLesson.MAX_TEXT_CHARS)
                if (text.length < 4) return@forEach
                // 找回这个人格：备份里没有名字（共用经验）或人格已不存在时落在 null
                val personaId = dto.personaName?.let { personaIds[it] }
                // 去重只在同一视角、同一作用域内做，避免把两个人格的经验并成一条
                val sameScope =
                    merged.filter { it.scopeGroupId == dto.groupId && it.personaId == personaId }
                if (ChimeReflection.findSimilar(text, sameScope) != null) return@forEach
                val created = dto.createdAt.takeIf { it > 0 } ?: now
                val updated = dto.updatedAt.takeIf { it > 0 } ?: created
                val id =
                    chimeLessonStore.insert(
                        ChimeLesson(
                            text = text,
                            source = ChimeLesson.SOURCE_REFLECTION,
                            scopeGroupId = dto.groupId,
                            hits = dto.hits.coerceAtLeast(0),
                            createdAt = created,
                            updatedAt = updated,
                            category = LessonCategories.normalize(dto.category),
                            supportCount = dto.supportCount.coerceAtLeast(1),
                            personaId = personaId,
                        ),
                    )
                merged +=
                    ChimeLesson(
                        id = id,
                        text = text,
                        source = ChimeLesson.SOURCE_REFLECTION,
                        scopeGroupId = dto.groupId,
                        hits = dto.hits,
                        createdAt = created,
                        updatedAt = updated,
                        category = LessonCategories.normalize(dto.category),
                        supportCount = dto.supportCount.coerceAtLeast(1),
                        personaId = personaId,
                    )
                lessonCount++
            }
            if (lessonCount > 0) {
                chimeLessonStore.trim(settings.chitchatConfig.value.maxLessons)
            }
            applied++
        }

        // ---- 关系：按人格名回到各自视角；找不到人格时落在共用视角 0 ----
        if (backup.relations.isNotEmpty()) {
            val personaIds = personaRepository.all().associate { it.name to it.id }
            backup.relations.forEach { dto ->
                runCatching {
                    socialStore.putRelation(
                        PersonRelation(
                            userId = dto.userId,
                            displayName = dto.displayName,
                            familiarity = dto.familiarity,
                            affinity = dto.affinity,
                            interactionCount = dto.interactionCount,
                            lastInteractionAt = dto.lastInteractionAt,
                            lastNote = dto.lastNote,
                        ),
                        personaId = dto.personaName?.let { personaIds[it] } ?: 0L,
                    )
                }
            }
            applied++
        }

        var styleCount = 0
        if (backup.styleRules.isNotEmpty()) {
            runCatching {
                val dao = database.styleRuleDao()
                backup.styleRules.forEach { dto ->
                    dao.upsert(
                        StyleRuleEntity(
                            id = dto.id,
                            text = dto.text,
                            category = dto.category,
                            source = dto.source,
                            enabled = dto.enabled,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    styleCount++
                }
            }
            applied++
        }

        var nameCount = 0
        backup.displayNames.forEach { dto ->
            if (dto.senderId.isBlank() || dto.displayName.isBlank()) return@forEach
            val source =
                runCatching { NameSource.valueOf(dto.source) }
                    .getOrDefault(NameSource.MANUAL)
            runCatching {
                identityStore.remember(dto.senderId, dto.displayName, source, System.currentTimeMillis())
                nameCount++
            }
        }

        var groupNameCount = 0
        backup.groupBotNames.forEach { dto ->
            runCatching {
                val current = groupStore.find(dto.groupId)
                val now = System.currentTimeMillis()
                val merged =
                    if (current != null) {
                        current.copy(
                            // 备份没写群名片时保留本机值，别把已设的称呼清掉
                            botName = dto.botName ?: current.botName,
                            // null = 旧备份没提供，沿用本机判断
                            active = dto.active ?: current.active,
                            proactiveAllowed = dto.proactiveAllowed ?: current.proactiveAllowed,
                            joinedAt = dto.joinedAt.takeIf { it > 0 } ?: current.joinedAt,
                            // 活跃时间取较新的：同机恢复不该让群列表倒退回旧排序
                            lastSeenAt = maxOf(dto.lastSeenAt, current.lastSeenAt),
                        )
                    } else {
                        GroupProfile(
                            groupId = dto.groupId,
                            botName = dto.botName,
                            joinedAt = dto.joinedAt.takeIf { it > 0 } ?: now,
                            lastSeenAt = dto.lastSeenAt.takeIf { it > 0 } ?: now,
                            active = dto.active ?: true,
                            proactiveAllowed = dto.proactiveAllowed ?: true,
                        )
                    }
                groupStore.upsert(merged)
                groupNameCount++
            }
        }

        appendLog(
            LogLevel.INFO,
            TAG,
            "配置导入完成：规则 $ruleCount 条、人格档案 +$profileNew/更新$profileUpdated、" +
                "人设覆盖 +$personaNew/更新$personaUpdated、" +
                "备注 $nameCount、群名片 $groupNameCount、经验 $lessonCount 条、" +
                "人设设定 $loreCount 条、说话约束 $styleCount 条、" +
                "记忆 $factCount 条、最近对话 $messageCount 条、内心戏 $thoughtCount 条、" +
                "配置项 $applied",
        )
        return (if (newerVersion) "提示：备份格式版本 ${backup.version} 高于本机支持的 " +
            "${ConfigBackup.CURRENT_VERSION}，部分新内容可能未导入。\n" else "") +
            "导入完成：规则 $ruleCount 条，人格档案新增 $profileNew、更新 $profileUpdated，" +
            "人设覆盖新增 $personaNew、更新 $personaUpdated，" +
            "备注名 $nameCount，群名片 $groupNameCount，经验 $lessonCount 条，" +
            "人设设定 $loreCount 条，说话约束 $styleCount 条，" +
            "记忆 $factCount 条，最近对话 $messageCount 条，内心戏 $thoughtCount 条，" +
            "配置组 $applied 项" +
            (if (backup.credentials != null) "（含凭证，需重新启动机器人生效）" else "")
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
        // 人格解析：覆盖层 → 激活档案 → 种子骨架，永不返回空——
        // 空 system 会让 PromptAssembler 整条不注入，模型立刻退化成客服腔。
        // 用类级实例，保证提示词、记忆、设定与经验看到的是同一个人格。
        val aiResponder =
            AiResponder(
                client = LlmClient(http = http, logger = logger),
                history = aiHistoryStore,
                personaResolver = { message -> personaResolver.promptFor(message) },
                // 表情包：模型在回复里自主决定要不要发、发哪张。
                // 图必须来自本地库（SendMemeTool 会校验），不能让它编 URL。
                tools =
                    ToolRegistry(
                        listOf(
                            SendMemeTool(socialStore),
                            // 联网搜索：只有需要时效信息时模型才调用；
                            // 开关关闭或非 DeepSeek 端点会直接拒绝，不产生费用。
                            WebSearchTool(
                                config = { settings.aiConfig.value },
                                search = { query, cfg ->
                                    deepSeekSearch.search(query, cfg.apiKey, cfg.baseUrl, 5)
                                },
                            ),
                        ),
                    ),
                // 历史按当前生效的人设隔离：切换档案后自动新开一条线程，
                // 新人格不会接着旧人格的语气和话题说话。
                conversationScopeProvider = { message ->
                    personaResolver.resolve(message).personaId?.toString()
                },
                // QQ 图片链接带签名、会过期，先本地下载转 base64 再给视觉模型；
                // 下载失败时回退原地址，行为与接入前一致。
                imageInliner = RemoteImageLoader(http),
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
                // 人设同时喂给插话漏斗（性格偏置）与 AI 插话判断（按性格决定接不接），
                // 让"要不要说话"也由人设说了算。
                personaPromptProvider = { message -> personaResolver.promptFor(message) },
                // 训练闭环：每条判断结束后落一条结构化记录
                chimeTrainer = chimeTrainer,
                // 内心戏：只落库，不进上下文也不发给群友
                innerThoughtSink = innerThoughtSink,
                // 关系 / 心情 / 梗 / 人设设定：读注入提示词，写来自互动事件
                socialProvider = socialStore,
                relationRecorder = socialStore,
                slangLearner = slangLearner,
                // 「去 AI 味」约束：内置 + 用户自定义（+ 未来插件），每条消息现取
                styleRuleProvider = { styleRuleComposer.rules() },
                // 表情包：群友发的图捡库，回复时模型可以从库里挑一张配着发
                memeHarvester = memeHarvester,
                // 明确被要求发图时本地直接挑一张；工具选中的图也在这里计数
                memeLibrary = socialStore,
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
