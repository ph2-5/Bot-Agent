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
 * - 群档案（群名片 + 在群状态 + 主动发言权限；成员样本与活跃度不导）
 *
 * 不导出：日志、AI 会话历史、人设版本快照、判断决策日志——
 * 这些是短期运行日志（7 天自动过期），搬过去只会带去过期状态。
 *
 * 3.0.0 起长期记忆（事实、最近对话、内心戏）也进备份：
 * 它们虽然不是"配置"，但换机后从零开始等于把养出来的人格也丢了。
 */
@Serializable
data class ConfigBackup(
    val version: Int = CURRENT_VERSION,
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
    /** 2.3.0 新增：人格档案（不含快照历史与游标——运行数据不搬家） */
    @SerialName("persona_profiles") val personaProfiles: List<PersonaProfileBackup> = emptyList(),
    /** 2.4.0 新增：它是怎么养出来的——对每个人的印象不该因为换机清零 */
    val relations: List<RelationBackup> = emptyList(),
    /** 2.4.0 新增：群内说法 */
    val slang: List<SlangBackup> = emptyList(),
    /** 2.6.0 新增：表情包库（手动贴的链接 + 群里捡的图） */
    val memes: List<MemeBackup> = emptyList(),
    /** 2.8.0 新增：人设设定（梗 / 认识的人 / 关系） */
    val lore: List<LoreBackup> = emptyList(),
    /** 2.8.0 新增：说话风格约束（内置的启停覆盖 + 用户自定义） */
    @SerialName("style_rules") val styleRules: List<StyleRuleBackup> = emptyList(),
    /** 3.0.0 新增：长期事实记忆（换机后不用重新认识每个人） */
    val facts: List<FactBackup> = emptyList(),
    /** 3.0.0 新增：最近对话背景（够模型接上"上次聊到哪"） */
    @SerialName("room_messages") val roomMessages: List<RoomMessageBackup> = emptyList(),
    /** 3.0.0 新增：内心戏（"忍过这个人"的情绪记忆） */
    @SerialName("inner_thoughts") val innerThoughts: List<InnerThoughtBackup> = emptyList(),
) {
    companion object {
        /**
         * 当前备份格式版本。
         *
         * 导入时据此判断备份是否来自更新版本：字段能忽略，但语义不能猜，
         * 所以旧版 App 导入新版备份要明确提示"部分内容未导入"，而不是静默丢掉。
         */
        const val CURRENT_VERSION = 6

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
    // ---- 2.8.0 新增：按人记对话（旧备份按默认值导入） ----
    @SerialName("personal_thread_enabled") val personalThreadEnabled: Boolean = true,
    @SerialName("personal_thread_size") val personalThreadSize: Int = 12,
    // ---- 2.9.0 新增：按人设分开记忆（旧备份按默认值导入） ----
    @SerialName("persona_scoped") val personaScoped: Boolean = false,
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
    // ---- 2.1 新增：训练参数（旧备份按默认值导入） ----
    @SerialName("max_lessons") val maxLessons: Int = 80,
    @SerialName("max_injected_lessons") val maxInjectedLessons: Int = 8,
    @SerialName("judge_context_size") val judgeContextSize: Int = 12,
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

/**
 * 人格档案的备份形态。
 *
 * 刻意不导 `msgCursor` / `lastGrownAt` / `birthAt` / 快照历史：
 * 那些是"这台设备上长到哪儿了"，搬到新机器上只会让成长节奏错乱。
 */
@Serializable
data class PersonaProfileBackup(
    val name: String,
    val prompt: String,
    /** 同一次备份里最多认一条激活 */
    @SerialName("is_active") val isActive: Boolean = false,
    /** EMBRYO / GROWING / MATURE / MANUAL */
    val stage: String = "MANUAL",
    @SerialName("seed_id") val seedId: String? = null,
    val frozen: Boolean = false,
    @SerialName("growth_count") val growthCount: Int = 0,
)

@Serializable
data class DisplayNameBackup(
    @SerialName("sender_id") val senderId: String,
    @SerialName("display_name") val displayName: String,
    val source: String,
)

/**
 * 一条群档案。
 *
 * 6 起除了群名片，还带上「机器人是否还在群里」与「群是否允许主动发言」：
 * 这两项决定机器人会不会主动开口，换机后靠网关事件重新学习会有一段时间判断错误。
 *
 * [active] / [proactiveAllowed] 用可空表示「旧备份没提供」：
 * 旧版备份反序列化出来是 null，导入时沿用本机值，避免把被移出的群误标成在群。
 * 群成员样本不导——它是持续滚动更新的运行数据，重新积累即可。
 */
@Serializable
data class GroupBotNameBackup(
    @SerialName("group_id") val groupId: String,
    @SerialName("bot_name") val botName: String? = null,
    val active: Boolean? = null,
    @SerialName("proactive_allowed") val proactiveAllowed: Boolean? = null,
    /** 入群时间；0 = 旧备份未提供 */
    @SerialName("joined_at") val joinedAt: Long = 0,
    /** 最近活跃时间；0 = 旧备份未提供 */
    @SerialName("last_seen_at") val lastSeenAt: Long = 0,
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
    /** 2.1 新增：目录类别与支撑次数；旧备份缺字段按「其他 / 1」导入 */
    val category: String = "OTHER",
    @SerialName("support_count") val supportCount: Int = 1,
    /** 2.9.0 新增：经验归属的人格名（null = 共用/旧数据）；按名称而非 id 关联 */
    @SerialName("persona_name") val personaName: String? = null,
)

@Serializable
data class RelationBackup(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String? = null,
    val familiarity: Int = 0,
    val affinity: Int = 0,
    @SerialName("interaction_count") val interactionCount: Int = 0,
    @SerialName("last_interaction_at") val lastInteractionAt: Long = 0,
    @SerialName("last_note") val lastNote: String? = null,
    /** 2.9.0 新增：关系归属的人格名（null = 共用视角） */
    @SerialName("persona_name") val personaName: String? = null,
)

@Serializable
data class SlangBackup(
    val term: String,
    val meaning: String,
    val usage: String? = null,
    @SerialName("scope_group_id") val scopeGroupId: String? = null,
    val hits: Int = 0,
)

@Serializable
data class MemeBackup(
    val url: String,
    val source: String = "HARVEST",
    val hits: Int = 0,
)

/**
 * 一条人设设定。
 *
 * [personaName] 而不是 personaId：数据库自增 id 换机后对不上，
 * 导入时按人格名称重新绑定；人格不存在则落成全局设定。
 */
@Serializable
data class LoreBackup(
    @SerialName("persona_name") val personaName: String? = null,
    /** PERSON / SLANG / RELATION / NOTE */
    val kind: String,
    val name: String,
    val note: String = "",
    @SerialName("scope_group_id") val scopeGroupId: String? = null,
    val enabled: Boolean = true,
    /** AI / MANUAL */
    val source: String = "MANUAL",
)

/**
 * 一条说话风格约束。
 *
 * 内置规则只备份「用户改过的启停」，[source] = BUILTIN；
 * 用户自定义的是 [source] = USER，完整带着 id 以免重复导入。
 */
@Serializable
data class StyleRuleBackup(
    val id: String,
    val text: String,
    /** LENGTH / TONE / FORMAT / ECHO / BOUNDARY */
    val category: String = "TONE",
    val source: String = "USER",
    val enabled: Boolean = true,
)

/**
 * 一条长期事实记忆。
 *
 * 按 senderId 关联：事实本来就是"关于某个人"的，id 换了人还是同一个 openid。
 * 导入时按 (senderId, text) 去重，重复导入不会翻倍。
 */
@Serializable
data class FactBackup(
    @SerialName("sender_id") val senderId: String,
    val text: String,
    /** NAME / PREFERENCE / PROFILE / NOTE */
    val kind: String = "NOTE",
    /** EXTRACTED / MANUAL / REFLECTED */
    val source: String = "EXTRACTED",
    @SerialName("updated_at") val updatedAt: Long = 0,
)

/**
 * 一条最近对话。
 *
 * 只备份最近一段（有上限），不是全量聊天记录：
 * 目的只是让模型换机后能接上"上次聊到哪"，不是做聊天归档。
 */
@Serializable
data class RoomMessageBackup(
    @SerialName("scope_key") val scopeKey: String,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
    /** user / assistant */
    val role: String = "user",
    val content: String,
    @SerialName("timestamp_millis") val timestampMillis: Long = 0,
    @SerialName("is_at") val isAt: Boolean = false,
    @SerialName("external_id") val externalId: String? = null,
    val decision: String? = null,
    /** 机器人发言当时扮演的人格名（null = 共用/旧数据） */
    @SerialName("persona_name") val personaName: String? = null,
)

/** 一条内心戏。 */
@Serializable
data class InnerThoughtBackup(
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
    val spoken: String,
    val thought: String,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("persona_name") val personaName: String? = null,
)
