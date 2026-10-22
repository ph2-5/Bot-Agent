package com.qbot.app.data.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 配置导入导出（换机 / 备份）。
 *
 * 覆盖全部「用户配置」类数据：
 * - 机器人凭证（AppID / AppSecret，明文存于备份文件，导出时会提醒）
 * - AI 接入（地址、Key、模型、提示词、参数）
 * - 记忆系统 / 加群问候语 / 群聊参与 配置
 * - 自动回复规则（按 id 合并，同 id 覆盖）
 * - 人设（按 名称+绑定对象 合并，命中则更新提示词，否则新增）
 * - 成员备注名（身份名录）
 * - 群名片（只导 botName，成员/活跃度等运行数据不导）
 *
 * 不导出：日志、AI 会话历史、群背景消息、事实记忆、人设版本快照——
 * 这些是"运行数据"而非"配置"，搬过去反而会把旧设备的上下文带歪。
 */
@Serializable
data class ConfigBackup(
    val version: Int = 1,
    @SerialName("exported_at") val exportedAt: Long,
    val credentials: CredentialsBackup? = null,
    val ai: AiBackup? = null,
    val memory: MemoryBackup? = null,
    val greeting: GreetingBackup? = null,
    val chitchat: ChitchatBackup? = null,
    val rules: List<RuleBackup> = emptyList(),
    val personas: List<PersonaBackup> = emptyList(),
    @SerialName("display_names") val displayNames: List<DisplayNameBackup> = emptyList(),
    @SerialName("group_bot_names") val groupBotNames: List<GroupBotNameBackup> = emptyList(),
    /** 2.0.0 新增：从反馈学到的接话经验（用户的训练成果，换机不该丢） */
    @SerialName("chime_lessons") val chimeLessons: List<LessonBackup> = emptyList(),
) {
    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        fun decode(text: String): ConfigBackup = json.decodeFromString(text)

        fun encode(backup: ConfigBackup): String = json.encodeToString(backup)
    }
}

@Serializable
data class CredentialsBackup(
    @SerialName("app_id") val appId: String,
    @SerialName("client_secret") val clientSecret: String,
)

@Serializable
data class AiBackup(
    val enabled: Boolean,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("api_key") val apiKey: String,
    val model: String,
    @SerialName("system_prompt") val systemPrompt: String,
    val temperature: Double,
    @SerialName("max_tokens") val maxTokens: Int,
    val mode: String,
    @SerialName("context_turns") val contextTurns: Int,
    @SerialName("reply_on_error") val replyOnError: String,
    @SerialName("thinking_mode") val thinkingMode: String,
    @SerialName("context_token_budget") val contextTokenBudget: Int,
)

@Serializable
data class MemoryBackup(
    val enabled: Boolean,
    @SerialName("facts_per_person") val factsPerPerson: Int,
    @SerialName("room_context_enabled") val roomContextEnabled: Boolean,
    @SerialName("room_context_size") val roomContextSize: Int,
    @SerialName("learn_names") val learnNames: Boolean,
    @SerialName("learn_facts") val learnFacts: Boolean,
    @SerialName("persona_rewrite") val personaRewrite: Boolean,
)

@Serializable
data class GreetingBackup(
    val enabled: Boolean,
    val text: String,
)

@Serializable
data class ChitchatBackup(
    val enabled: Boolean,
    @SerialName("base_cooldown_seconds") val baseCooldownSeconds: Int,
    @SerialName("max_per_hour") val maxPerHour: Int,
    val aiJudge: Boolean,
    @SerialName("probability_percent") val probabilityPercent: Int,
    // ---- 1.9.3 新增：全部带默认值，旧备份缺失这些字段时按出厂值导入 ----
    @SerialName("max_per_day") val maxPerDay: Int = 400,
    @SerialName("answer_on_name") val answerOnName: Boolean = true,
    @SerialName("min_score") val minScore: Int = 15,
    @SerialName("w_length") val lengthScore: Int = 10,
    @SerialName("w_question") val questionScore: Int = 25,
    @SerialName("w_short") val shortPenalty: Int = -20,
    @SerialName("w_mention") val mentionPenalty: Int = -20,
    @SerialName("w_topic") val topicScore: Int = 15,
    @SerialName("w_respond_bot") val respondBotScore: Int = 15,
    @SerialName("w_quiet") val quietScore: Int = 10,
    @SerialName("w_spam") val spamPenalty: Int = -10,
    @SerialName("chime_rules") val chimeRules: List<String> = emptyList(),
    @SerialName("insult_extra_words") val insultExtraWords: List<String> = emptyList(),
)

@Serializable
data class RuleBackup(
    val id: String,
    val name: String,
    val enabled: Boolean,
    @SerialName("match_type") val matchType: String,
    val pattern: String,
    val reply: String,
    val scope: String,
    val priority: Int,
)

@Serializable
data class PersonaBackup(
    val name: String,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("scope_id") val scopeId: String? = null,
    val prompt: String,
)

@Serializable
data class DisplayNameBackup(
    @SerialName("sender_id") val senderId: String,
    @SerialName("display_name") val displayName: String,
    val source: String,
)

@Serializable
data class GroupBotNameBackup(
    @SerialName("group_id") val groupId: String,
    @SerialName("bot_name") val botName: String,
)

/**
 * 一条接话经验。
 *
 * 只备份经验本身与作用域；`hits` 一起带上，换机后能保留"哪几条真的常用"。
 * 决策日志属于运行数据，不备份。
 */
@Serializable
data class LessonBackup(
    val text: String,
    val source: String = "REFLECTION",
    @SerialName("group_id") val groupId: String? = null,
    val hits: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
)
