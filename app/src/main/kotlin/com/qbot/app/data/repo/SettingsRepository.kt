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

    init {
        // DataStore 的变更合并进 StateFlow；加密偏好里的 apiKey 每次重新读，
        // 保证用户在设置页改完 Key 后保存即可生效。
        scope.launch {
            context.dataStore.data.collect { prefs ->
                _aiConfig.value = readAiConfig(prefs)
            }
        }
    }

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
