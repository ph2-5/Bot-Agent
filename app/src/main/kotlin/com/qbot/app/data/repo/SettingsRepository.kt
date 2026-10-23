package com.qbot.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.AiMode
import com.qbot.core.ai.ChimeLesson
import com.qbot.core.ai.ChitchatConfig
import com.qbot.core.ai.MemoryConfig
import com.qbot.core.ai.ScoreWeights
import com.qbot.core.ai.ThinkingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 机器人凭证。 */
data class BotCredentials(
    val appId: String,
    val clientSecret: String,
) {
    /** 凭证是否填写完整。空 appId 会让网关一直重试，UI 需要据此给出提示。 */
    val isConfigured: Boolean
        get() = appId.isNotBlank() && clientSecret.isNotBlank()
}

/** 加群问候语设置。 */
data class GreetingConfig(
    /** 总开关：关闭后被拉进群保持安静 */
    val enabled: Boolean = false,
    /** 问候语正文；空白等同于关闭 */
    val text: String = "大家好，我是本群机器人，@我就能聊天，说「叫你XXX」可以给我取个群昵称。",
)

/**
 * 设置仓库。
 *
 * 分两处存储，是因为安全性要求不同：
 *
 * - **凭证（AppID / ClientSecret / AI API Key）** 属于机密，存 [EncryptedSharedPreferences]，
 *   底层由 Android Keystore 加密，即使设备被 root 也不易直接读出明文。
 * - **运行偏好与 AI 非敏感参数**（模型名、系统提示词、触发模式等）无敏感性，
 *   存普通 DataStore 即可，读取走 Flow，界面可实时响应。
 */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val AUTO_START = booleanPreferencesKey("auto_start")
        val POWER_SAVING = booleanPreferencesKey("power_saving")
        val KEEP_SCREEN_OFF_ONLY = booleanPreferencesKey("keep_screen_off_only")
        val LOG_RETENTION_DAYS = intPreferencesKey("log_retention_days")
        val LAST_TARGET_KIND = stringPreferencesKey("last_target_kind")
        val LAST_TARGET_ID = stringPreferencesKey("last_target_id")

        // ---- AI ----
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_SYSTEM_PROMPT = stringPreferencesKey("ai_system_prompt")
        val AI_TEMPERATURE = floatPreferencesKey("ai_temperature")
        val AI_MAX_TOKENS = intPreferencesKey("ai_max_tokens")
        val AI_MODE = stringPreferencesKey("ai_mode")
        val AI_CONTEXT_TURNS = intPreferencesKey("ai_context_turns")
        val AI_REPLY_ON_ERROR = stringPreferencesKey("ai_reply_on_error")
        val AI_THINKING_MODE = stringPreferencesKey("ai_thinking_mode")
        val AI_CONTEXT_TOKEN_BUDGET = intPreferencesKey("ai_context_token_budget")
        val AI_VISION_MODEL = stringPreferencesKey("ai_vision_model")
        val AI_WEB_SEARCH = booleanPreferencesKey("ai_web_search")

        // ---- 记忆系统 ----
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val MEMORY_FACTS_PER_PERSON = intPreferencesKey("memory_facts_per_person")
        val MEMORY_ROOM_CONTEXT = booleanPreferencesKey("memory_room_context")
        val MEMORY_ROOM_SIZE = intPreferencesKey("memory_room_size")
        val MEMORY_LEARN_NAMES = booleanPreferencesKey("memory_learn_names")
        val MEMORY_LEARN_FACTS = booleanPreferencesKey("memory_learn_facts")
        val MEMORY_PERSONA_REWRITE = booleanPreferencesKey("memory_persona_rewrite")

        // ---- 群感知 ----
        val GREETING_ENABLED = booleanPreferencesKey("greeting_enabled")
        val GREETING_TEXT = stringPreferencesKey("greeting_text")

        // ---- 群聊参与（插话） ----
        val CHITCHAT_ENABLED = booleanPreferencesKey("chitchat_enabled")
        val CHITCHAT_AI_JUDGE = booleanPreferencesKey("chitchat_ai_judge")
        val CHITCHAT_COOLDOWN_SECONDS = intPreferencesKey("chitchat_cooldown_seconds")
        val CHITCHAT_MAX_PER_HOUR = intPreferencesKey("chitchat_max_per_hour")
        val CHITCHAT_MAX_PER_DAY = intPreferencesKey("chitchat_max_per_day")
        val CHITCHAT_PROBABILITY_PERCENT = intPreferencesKey("chitchat_probability_percent")
        val CHITCHAT_ANSWER_ON_NAME = booleanPreferencesKey("chitchat_answer_on_name")
        val CHITCHAT_MIN_SCORE = intPreferencesKey("chitchat_min_score")
        // 打分明细权重（各情形加/扣分）
        val CHITCHAT_W_LENGTH = intPreferencesKey("chitchat_w_length")
        val CHITCHAT_W_QUESTION = intPreferencesKey("chitchat_w_question")
        val CHITCHAT_W_SHORT = intPreferencesKey("chitchat_w_short")
        val CHITCHAT_W_MENTION = intPreferencesKey("chitchat_w_mention")
        val CHITCHAT_W_TOPIC = intPreferencesKey("chitchat_w_topic")
        val CHITCHAT_W_RESPOND_BOT = intPreferencesKey("chitchat_w_respond_bot")
        val CHITCHAT_W_QUIET = intPreferencesKey("chitchat_w_quiet")
        val CHITCHAT_W_SPAM = intPreferencesKey("chitchat_w_spam")
        // 自然语言列表（JSON 数组字符串）
        val CHITCHAT_CHIME_RULES = stringPreferencesKey("chitchat_chime_rules")
        val CHITCHAT_INSULT_WORDS = stringPreferencesKey("chitchat_insult_words")
        // 训练闭环：经验库容量与每次注入条数
        val CHITCHAT_MAX_LESSONS = intPreferencesKey("chitchat_max_lessons")
        val CHITCHAT_MAX_INJECTED = intPreferencesKey("chitchat_max_injected")
        val CHITCHAT_JUDGE_CONTEXT = intPreferencesKey("chitchat_judge_context")

        // ---- 人格（2.3.0） ----
        val PERSONA_LEGACY_IMPORTED = booleanPreferencesKey("persona_legacy_imported")
    }

    private companion object {
        const val SECRET_FILE = "qbot_secrets"
        const val KEY_APP_ID = "app_id"
        const val KEY_CLIENT_SECRET = "client_secret"
        const val KEY_AI_API_KEY = "ai_api_key"
    }

    private val encryptedPrefs by lazy {
        val masterKey =
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

        EncryptedSharedPreferences.create(
            context,
            SECRET_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------- 凭证 ----------

    /** 读取凭证。 */
    fun credentials(): BotCredentials =
        BotCredentials(
            appId = encryptedPrefs.getString(KEY_APP_ID, "").orEmpty(),
            clientSecret = encryptedPrefs.getString(KEY_CLIENT_SECRET, "").orEmpty(),
        )

    /** 保存凭证。 */
    fun saveCredentials(appId: String, clientSecret: String) {
        encryptedPrefs.edit()
            .putString(KEY_APP_ID, appId.trim())
            .putString(KEY_CLIENT_SECRET, clientSecret.trim())
            .apply()
    }

    // ---------- AI 配置 ----------

    /**
     * AI 配置。
     *
     * 混合了两处数据源：非敏感字段在 DataStore（Flow），
     * API Key 在加密偏好（一次性读）。这里合并成一个 StateFlow，
     * 机器人在每条消息处理时直接 `.value` 取最新配置，零延迟生效。
     */
    private val _aiConfig =
        MutableStateFlow(
            AiConfig(apiKey = encryptedPrefs.getString(KEY_AI_API_KEY, "").orEmpty()),
        )
    val aiConfig: StateFlow<AiConfig> = _aiConfig.asStateFlow()

    /**
     * 记忆系统配置。
     *
     * 与 [aiConfig] 同一套合并机制：DataStore 出非敏感字段，
     * 机器人在每条消息处理时 `.value` 取最新值，改设置无需重启。
     */
    private val _memoryConfig = MutableStateFlow(MemoryConfig())
    val memoryConfig: StateFlow<MemoryConfig> = _memoryConfig.asStateFlow()

    /**
     * 加群问候语配置。
     *
     * 与 [memoryConfig] 同一套机制：DataStore 出字段，
     * 机器人被拉进群时 `.value` 取最新值，改设置无需重启。
     * 问候语属于主动消息（消耗配额），默认关闭。
     */
    private val _greetingConfig = MutableStateFlow(GreetingConfig())
    val greetingConfig: StateFlow<GreetingConfig> = _greetingConfig.asStateFlow()

    /**
     * 群聊参与（插话）配置。
     *
     * 只有群主在群里给机器人打开「接收所有消息」后才会收到非 @ 消息；
     * 这个开关决定收到之后要不要接话。
     */
    private val _chitchatConfig = MutableStateFlow(ChitchatConfig())
    val chitchatConfig: StateFlow<ChitchatConfig> = _chitchatConfig.asStateFlow()

    init {
        // DataStore 的变更合并进 StateFlow；加密偏好里的 apiKey 每次重新读，
        // 保证用户在设置页改完 Key 后保存即可生效。
        scope.launch {
            context.dataStore.data.collect { prefs ->
                _aiConfig.value = readAiConfig(prefs)
                _memoryConfig.value = readMemoryConfig(prefs)
                _greetingConfig.value = readGreetingConfig(prefs)
                _chitchatConfig.value = readChitchatConfig(prefs)
            }
        }
    }

    private fun readMemoryConfig(prefs: Preferences): MemoryConfig {
        val defaults = MemoryConfig()
        return MemoryConfig(
            enabled = prefs[Keys.MEMORY_ENABLED] ?: defaults.enabled,
            factsPerPerson =
            prefs[Keys.MEMORY_FACTS_PER_PERSON] ?: defaults.factsPerPerson,
            roomContextEnabled =
            prefs[Keys.MEMORY_ROOM_CONTEXT] ?: defaults.roomContextEnabled,
            roomContextSize = prefs[Keys.MEMORY_ROOM_SIZE] ?: defaults.roomContextSize,
            learnNames = prefs[Keys.MEMORY_LEARN_NAMES] ?: defaults.learnNames,
            learnFacts = prefs[Keys.MEMORY_LEARN_FACTS] ?: defaults.learnFacts,
            personaRewrite =
                prefs[Keys.MEMORY_PERSONA_REWRITE] ?: defaults.personaRewrite,
        )
    }

    /** 保存记忆系统配置。 */
    fun saveMemoryConfig(config: MemoryConfig) {
        _memoryConfig.value = config
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[Keys.MEMORY_ENABLED] = config.enabled
                prefs[Keys.MEMORY_FACTS_PER_PERSON] = config.factsPerPerson
                prefs[Keys.MEMORY_ROOM_CONTEXT] = config.roomContextEnabled
                prefs[Keys.MEMORY_ROOM_SIZE] = config.roomContextSize
                prefs[Keys.MEMORY_LEARN_NAMES] = config.learnNames
                prefs[Keys.MEMORY_LEARN_FACTS] = config.learnFacts
                prefs[Keys.MEMORY_PERSONA_REWRITE] = config.personaRewrite
            }
        }
    }

    private fun readGreetingConfig(prefs: Preferences): GreetingConfig {
        val defaults = GreetingConfig()
        return GreetingConfig(
            enabled = prefs[Keys.GREETING_ENABLED] ?: defaults.enabled,
            text = prefs[Keys.GREETING_TEXT] ?: defaults.text,
        )
    }

    /** 保存加群问候语配置。 */
    fun saveGreetingConfig(config: GreetingConfig) {
        _greetingConfig.value = config
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[Keys.GREETING_ENABLED] = config.enabled
                prefs[Keys.GREETING_TEXT] = config.text
            }
        }
    }

    // ---------- 人格引导 ----------

    /**
     * 直接读首帧配置。
     *
     * [aiConfig] 是 StateFlow，进程刚起来时可能还没收到第一次发射（值是默认值），
     * 迁移判断不能依赖它，否则会把"出厂默认"误判成用户写的人格。
     */
    suspend fun loadAiConfigOnce(): AiConfig = readAiConfig(context.dataStore.data.first())

    suspend fun legacyPersonaImported(): Boolean =
        context.dataStore.data.first()[Keys.PERSONA_LEGACY_IMPORTED] ?: false

    suspend fun markLegacyPersonaImported() {
        context.dataStore.edit { it[Keys.PERSONA_LEGACY_IMPORTED] = true }
    }

    private fun readChitchatConfig(prefs: Preferences): ChitchatConfig {
        val defaults = ChitchatConfig()
        val weights = defaults.scoreWeights
        return ChitchatConfig(
            enabled = prefs[Keys.CHITCHAT_ENABLED] ?: defaults.enabled,
            aiJudge = prefs[Keys.CHITCHAT_AI_JUDGE] ?: defaults.aiJudge,
            baseCooldownMillis =
                (prefs[Keys.CHITCHAT_COOLDOWN_SECONDS] ?: (defaults.baseCooldownMillis / 1000).toInt())
                    .toLong() * 1000,
            maxPerHour = prefs[Keys.CHITCHAT_MAX_PER_HOUR] ?: defaults.maxPerHour,
            maxPerDay =
                (prefs[Keys.CHITCHAT_MAX_PER_DAY] ?: defaults.maxPerDay).coerceIn(0, 1000),
            probability =
                (prefs[Keys.CHITCHAT_PROBABILITY_PERCENT] ?: (defaults.probability * 100).toInt())
                    .coerceIn(1, 100) / 100.0,
            answerOnName = prefs[Keys.CHITCHAT_ANSWER_ON_NAME] ?: defaults.answerOnName,
            minScore =
                (prefs[Keys.CHITCHAT_MIN_SCORE] ?: defaults.minScore).coerceIn(0, 40),
            scoreWeights =
                ScoreWeights(
                    lengthScore =
                        (prefs[Keys.CHITCHAT_W_LENGTH] ?: weights.lengthScore).coerceIn(0, 20),
                    questionScore =
                        (prefs[Keys.CHITCHAT_W_QUESTION] ?: weights.questionScore).coerceIn(0, 40),
                    shortPenalty =
                        (prefs[Keys.CHITCHAT_W_SHORT] ?: weights.shortPenalty).coerceIn(-40, 0),
                    mentionPenalty =
                        (prefs[Keys.CHITCHAT_W_MENTION] ?: weights.mentionPenalty).coerceIn(-40, 0),
                    topicScore =
                        (prefs[Keys.CHITCHAT_W_TOPIC] ?: weights.topicScore).coerceIn(0, 30),
                    respondBotScore =
                        (prefs[Keys.CHITCHAT_W_RESPOND_BOT] ?: weights.respondBotScore)
                            .coerceIn(0, 30),
                    quietScore =
                        (prefs[Keys.CHITCHAT_W_QUIET] ?: weights.quietScore).coerceIn(0, 20),
                    spamPenalty =
                        (prefs[Keys.CHITCHAT_W_SPAM] ?: weights.spamPenalty).coerceIn(-30, 0),
                ),
            chimeRules = decodeStringList(prefs[Keys.CHITCHAT_CHIME_RULES]),
            insultExtraWords = decodeStringList(prefs[Keys.CHITCHAT_INSULT_WORDS]),
            maxLessons =
                (prefs[Keys.CHITCHAT_MAX_LESSONS] ?: defaults.maxLessons)
                    .coerceIn(ChimeLesson.MIN_LESSONS, ChimeLesson.MAX_LESSONS),
            maxInjectedLessons =
                (prefs[Keys.CHITCHAT_MAX_INJECTED] ?: defaults.maxInjectedLessons)
                    .coerceIn(ChimeLesson.MIN_INJECTED, ChimeLesson.MAX_INJECTED),
            judgeContextSize =
                (prefs[Keys.CHITCHAT_JUDGE_CONTEXT] ?: defaults.judgeContextSize)
                    .coerceIn(defaults.MIN_JUDGE_CONTEXT, defaults.MAX_JUDGE_CONTEXT),
        )
    }

    /** 保存群聊参与（插话）配置。 */
    fun saveChitchatConfig(config: ChitchatConfig) {
        _chitchatConfig.value = config
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[Keys.CHITCHAT_ENABLED] = config.enabled
                prefs[Keys.CHITCHAT_AI_JUDGE] = config.aiJudge
                prefs[Keys.CHITCHAT_COOLDOWN_SECONDS] = (config.baseCooldownMillis / 1000).toInt()
                prefs[Keys.CHITCHAT_MAX_PER_HOUR] = config.maxPerHour
                prefs[Keys.CHITCHAT_MAX_PER_DAY] = config.maxPerDay.coerceIn(0, 1000)
                prefs[Keys.CHITCHAT_PROBABILITY_PERCENT] =
                    (config.probability * 100).toInt().coerceIn(1, 100)
                prefs[Keys.CHITCHAT_ANSWER_ON_NAME] = config.answerOnName
                prefs[Keys.CHITCHAT_MIN_SCORE] = config.minScore.coerceIn(0, 40)
                val w = config.scoreWeights
                prefs[Keys.CHITCHAT_W_LENGTH] = w.lengthScore.coerceIn(0, 20)
                prefs[Keys.CHITCHAT_W_QUESTION] = w.questionScore.coerceIn(0, 40)
                prefs[Keys.CHITCHAT_W_SHORT] = w.shortPenalty.coerceIn(-40, 0)
                prefs[Keys.CHITCHAT_W_MENTION] = w.mentionPenalty.coerceIn(-40, 0)
                prefs[Keys.CHITCHAT_W_TOPIC] = w.topicScore.coerceIn(0, 30)
                prefs[Keys.CHITCHAT_W_RESPOND_BOT] = w.respondBotScore.coerceIn(0, 30)
                prefs[Keys.CHITCHAT_W_QUIET] = w.quietScore.coerceIn(0, 20)
                prefs[Keys.CHITCHAT_W_SPAM] = w.spamPenalty.coerceIn(-30, 0)
                prefs[Keys.CHITCHAT_CHIME_RULES] =
                    encodeStringList(config.chimeRules, maxItems = 10, maxLength = 100)
                prefs[Keys.CHITCHAT_INSULT_WORDS] =
                    encodeStringList(config.insultExtraWords, maxItems = 50, maxLength = 10)
                prefs[Keys.CHITCHAT_MAX_LESSONS] =
                    config.maxLessons.coerceIn(ChimeLesson.MIN_LESSONS, ChimeLesson.MAX_LESSONS)
                prefs[Keys.CHITCHAT_MAX_INJECTED] =
                    config.maxInjectedLessons.coerceIn(
                        ChimeLesson.MIN_INJECTED,
                        ChimeLesson.MAX_INJECTED,
                    )
                prefs[Keys.CHITCHAT_JUDGE_CONTEXT] =
                    config.judgeContextSize.coerceIn(
                        config.MIN_JUDGE_CONTEXT,
                        config.MAX_JUDGE_CONTEXT,
                    )
            }
        }
    }

    // ---------- 自然语言列表的存取（插话规则 / 自定义侮辱词） ----------
    //
    // DataStore 没有列表类型，用 JSON 数组字符串承载。
    // 读取失败（坏数据/旧格式）时退回空表——这些是增强项，
    // 不该让一条坏记录把整个设置仓库的 Flow 搞崩。

    private val listJson = Json { ignoreUnknownKeys = true }

    private fun decodeStringList(raw: String?): List<String> =
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { listJson.decodeFromString<List<String>>(raw) }
                .getOrDefault(emptyList())
                .filter { it.isNotBlank() }
        }

    private fun encodeStringList(
        items: List<String>,
        maxItems: Int,
        maxLength: Int,
    ): String =
        listJson.encodeToString(
            items
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(maxItems)
                .map { it.take(maxLength) },
        )

    private fun readAiConfig(prefs: Preferences): AiConfig {
        val defaults = AiConfig()
        return AiConfig(
            enabled = prefs[Keys.AI_ENABLED] ?: false,
            baseUrl = prefs[Keys.AI_BASE_URL] ?: defaults.baseUrl,
            apiKey = encryptedPrefs.getString(KEY_AI_API_KEY, "").orEmpty(),
            model = prefs[Keys.AI_MODEL] ?: defaults.model,
            systemPrompt = prefs[Keys.AI_SYSTEM_PROMPT] ?: defaults.systemPrompt,
            temperature = (prefs[Keys.AI_TEMPERATURE] ?: defaults.temperature.toFloat()).toDouble(),
            maxTokens = prefs[Keys.AI_MAX_TOKENS] ?: defaults.maxTokens,
            mode =
                runCatching { AiMode.valueOf(prefs[Keys.AI_MODE] ?: defaults.mode.name) }
                    .getOrDefault(defaults.mode),
            contextTurns = prefs[Keys.AI_CONTEXT_TURNS] ?: defaults.contextTurns,
            replyOnError = prefs[Keys.AI_REPLY_ON_ERROR] ?: defaults.replyOnError,
            thinkingMode =
                runCatching {
                    ThinkingMode.valueOf(prefs[Keys.AI_THINKING_MODE] ?: defaults.thinkingMode.name)
                }.getOrDefault(defaults.thinkingMode),
            contextTokenBudget =
                prefs[Keys.AI_CONTEXT_TOKEN_BUDGET] ?: defaults.contextTokenBudget,
            visionModel = prefs[Keys.AI_VISION_MODEL] ?: defaults.visionModel,
            webSearch = prefs[Keys.AI_WEB_SEARCH] ?: defaults.webSearch,
        )
    }

    /** 保存 AI 配置。apiKey 单独走加密存储。 */
    fun saveAiConfig(config: AiConfig) {
        encryptedPrefs.edit()
            .putString(KEY_AI_API_KEY, config.apiKey.trim())
            .apply()

        // 先更新 StateFlow，让 UI 与机器人立即看到新值，
        // DataStore 的落库由协程异步完成。
        _aiConfig.value = config.copy(apiKey = config.apiKey.trim())

        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[Keys.AI_ENABLED] = config.enabled
                prefs[Keys.AI_BASE_URL] = config.baseUrl.trim()
                prefs[Keys.AI_MODEL] = config.model.trim()
                prefs[Keys.AI_SYSTEM_PROMPT] = config.systemPrompt
                prefs[Keys.AI_TEMPERATURE] = config.temperature.toFloat()
                prefs[Keys.AI_MAX_TOKENS] = config.maxTokens
                prefs[Keys.AI_MODE] = config.mode.name
                prefs[Keys.AI_CONTEXT_TURNS] = config.contextTurns
                prefs[Keys.AI_REPLY_ON_ERROR] = config.replyOnError
                prefs[Keys.AI_THINKING_MODE] = config.thinkingMode.name
                prefs[Keys.AI_CONTEXT_TOKEN_BUDGET] = config.contextTokenBudget
                prefs[Keys.AI_VISION_MODEL] = config.visionModel.trim()
                prefs[Keys.AI_WEB_SEARCH] = config.webSearch
            }
        }
    }

    // ---------- 运行偏好 ----------

    val autoStart: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AUTO_START] ?: true }

    suspend fun setAutoStart(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_START] = enabled }
    }

    /** 省电模式：只订阅消息事件，减少唤醒次数。 */
    val powerSaving: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.POWER_SAVING] ?: false }

    suspend fun setPowerSaving(enabled: Boolean) {
        context.dataStore.edit { it[Keys.POWER_SAVING] = enabled }
    }

    val logRetentionDays: Flow<Int> =
        context.dataStore.data.map { it[Keys.LOG_RETENTION_DAYS] ?: 3 }

    suspend fun setLogRetentionDays(days: Int) {
        context.dataStore.edit { it[Keys.LOG_RETENTION_DAYS] = days }
    }

    /** 记住上次手动发送的目标，避免每次重填。 */
    suspend fun saveLastTarget(
        kind: String,
        targetId: String,
    ) {
        context.dataStore.edit {
            it[Keys.LAST_TARGET_KIND] = kind
            it[Keys.LAST_TARGET_ID] = targetId
        }
    }

    suspend fun lastTarget(): Pair<String, String> {
        val prefs = context.dataStore.data.first()
        return (prefs[Keys.LAST_TARGET_KIND] ?: "GROUP") to (prefs[Keys.LAST_TARGET_ID] ?: "")
    }
}
