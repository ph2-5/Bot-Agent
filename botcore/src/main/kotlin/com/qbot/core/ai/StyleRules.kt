package com.qbot.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 「去 AI 味」的说话约束。
 *
 * 三个来源分层，互相不覆盖：
 * - [StyleRuleSource.BUILTIN]：系统内置（从角色卡的「群聊铁律 / 说话方式 / AI 味黑名单」
 *   提炼而成），所有角色共用，可逐条停用；
 * - [StyleRuleSource.USER]：维护端自己加的约束（例如「别用问句结尾」）；
 * - [StyleRuleSource.PERSONA]：从人设正文里自动提取的约束（可重新提取，不会覆盖手写规则）；
 * - [StyleRuleSource.PLUGIN]：插件提供的约束，未来插件系统接这里。
 *
 * 与 [Persona] 的分工：人设写「我是谁、什么性格」，这里写「话怎么说」。
 * 分开存是为了换人设时不丢失通用约束，也为了让用户不必改人设正文。
 */
enum class StyleRuleSource {
    BUILTIN,
    USER,
    PERSONA,
    PLUGIN,
}

/** 约束的作用面，仅用于维护端分组展示与未来按场景筛选。 */
enum class StyleRuleCategory {
    /** 长度与消息节奏 */
    LENGTH,

    /** 语气与称呼 */
    TONE,

    /** 排版格式 */
    FORMAT,

    /** 常见 AI 套话 / 句式黑名单 */
    ECHO,

    /** 身份与边界 */
    BOUNDARY,
}

data class StyleRule(
    /** 内置规则用稳定 id；用户/插件规则用生成 id */
    val id: String = "",
    val text: String,
    val category: StyleRuleCategory = StyleRuleCategory.TONE,
    val source: StyleRuleSource = StyleRuleSource.USER,
    val enabled: Boolean = true,
)

/** 内置约束：全部是「可执行」的指令，而不是形容词。 */
object BuiltinStyleRules {
    const val LENGTH_SHORT = "builtin.length.short"
    const val LENGTH_NO_SUMMARY = "builtin.length.no_summary"
    const val TONE_NO_SERVICE = "builtin.tone.no_service"
    const val TONE_NO_TAIL = "builtin.tone.no_particles"
    const val TONE_NO_CARE_QUESTION = "builtin.tone.no_care_question"
    const val FORMAT_NO_MARKDOWN = "builtin.format.no_markdown"
    const val FORMAT_NO_BRACKETS = "builtin.format.no_brackets"
    const val ECHO_NO_CONNECTIVES = "builtin.echo.no_connectives"
    const val ECHO_NO_SALES_TALK = "builtin.echo.no_sales_talk"
    const val BOUNDARY_NO_AI_DISCLAIMER = "builtin.boundary.no_ai_disclaimer"
    const val BOUNDARY_NO_ROLE_DRIFT = "builtin.boundary.no_role_drift"

    val ALL: List<StyleRule> =
        listOf(
            StyleRule(
                id = LENGTH_SHORT,
                category = StyleRuleCategory.LENGTH,
                source = StyleRuleSource.BUILTIN,
                text = "一条回复默认 1~10 个字，能不整句就不整句；" +
                    "对方认真问事实、需要解释时才多说，并且拆成几条短消息，而不是写成一段。",
            ),
            StyleRule(
                id = LENGTH_NO_SUMMARY,
                category = StyleRuleCategory.LENGTH,
                source = StyleRuleSource.BUILTIN,
                text = "不要总结、不要升华、不要主动给人生建议；说完就走。",
            ),
            StyleRule(
                id = TONE_NO_SERVICE,
                category = StyleRuleCategory.TONE,
                source = StyleRuleSource.BUILTIN,
                text = "不用客服腔：不说「有什么可以帮您」「随时找我」「希望这个回答对你有帮助」。",
            ),
            StyleRule(
                id = TONE_NO_TAIL,
                category = StyleRuleCategory.TONE,
                source = StyleRuleSource.BUILTIN,
                text = "少用「～」「哦」「呢」「啦」「呀」这类语气尾巴；" +
                    "情绪直接用词表达，不靠符号堆。",
            ),
            StyleRule(
                id = TONE_NO_CARE_QUESTION,
                category = StyleRuleCategory.TONE,
                source = StyleRuleSource.BUILTIN,
                text = "不要每条都关心对方，也不要每条都以问句结尾。",
            ),
            StyleRule(
                id = FORMAT_NO_MARKDOWN,
                category = StyleRuleCategory.FORMAT,
                source = StyleRuleSource.BUILTIN,
                text = "不用 Markdown 标题、列表、加粗、引用块；聊天窗口里这些一眼是 AI。",
            ),
            StyleRule(
                id = FORMAT_NO_BRACKETS,
                category = StyleRuleCategory.FORMAT,
                source = StyleRuleSource.BUILTIN,
                text = "不要用括号写内心戏或解释自己；括号里的内容会被当成正式消息发出去。",
            ),
            StyleRule(
                id = ECHO_NO_CONNECTIVES,
                category = StyleRuleCategory.ECHO,
                source = StyleRuleSource.BUILTIN,
                text = "不说「首先 / 其次 / 最后 / 综上所述 / 总而言之 / 值得注意的是」这类书面连接词。",
            ),
            StyleRule(
                id = ECHO_NO_SALES_TALK,
                category = StyleRuleCategory.ECHO,
                source = StyleRuleSource.BUILTIN,
                text = "不用销售式套话：「保证」「一绝」「真管用」「强烈推荐」「绝对有效」；" +
                    "也不要列两个选项让人挑（「A 或者 B」）。",
            ),
            StyleRule(
                id = BOUNDARY_NO_AI_DISCLAIMER,
                category = StyleRuleCategory.BOUNDARY,
                source = StyleRuleSource.BUILTIN,
                text = "不说「作为 AI」「作为一个语言模型」「我无法……」这类免责声明；" +
                    "不能做的事用一句人话讲清，被问是不是 AI 就大方承认，但不解释模型与提示词。",
            ),
            StyleRule(
                id = BOUNDARY_NO_ROLE_DRIFT,
                category = StyleRuleCategory.BOUNDARY,
                source = StyleRuleSource.BUILTIN,
                text = "不要自称角色设定之外的称呼（例如「本小姐」这类二创腔），也不要突然切换成助手口吻。",
            ),
        )
}

/** 约束存储（Android 侧用 Room；botcore 测试用内存实现）。 */
interface StyleRuleStore {
    /** 包含内置规则的启停覆盖行与用户/插件规则 */
    suspend fun all(): List<StyleRule>

    suspend fun upsert(rule: StyleRule): Long

    suspend fun setEnabled(id: String, enabled: Boolean)

    suspend fun delete(id: String)

    /** 清空用户自定义（保留内置规则的启停状态） */
    suspend fun clearUserRules()

    /**
     * 清空某个来源的规则。
     *
     * 主要用于「从人设重新提取」：先删掉上一批 [StyleRuleSource.PERSONA]，
     * 再写入新的一批，避免人设改短后残留旧规则。
     */
    suspend fun clearSource(source: StyleRuleSource) = Unit
}

/** 插件贡献语气约束的接口；插件系统接入时实现它即可。 */
fun interface StyleRulePlugin {
    suspend fun rules(): List<StyleRule>
}

/**
 * 把四路规则合成一份最终注入列表。
 *
 * 顺序：用户规则 > 人设提取 > 插件规则 > 内置规则。重复文本只留一条——
 * 用户把内置规则抄一遍、或人设里恰好写了同一条时，不该出现两遍同样的句子。
 */
class StyleRuleComposer(
    private val store: StyleRuleStore? = null,
    private val plugins: List<StyleRulePlugin> = emptyList(),
    private val builtins: List<StyleRule> = BuiltinStyleRules.ALL,
) {
    /** 完整合并：先从存储与插件各取一次，再合并 */
    suspend fun rules(): List<StyleRule> {
        val saved = store?.all().orEmpty()
        val fromPlugins =
            plugins.flatMap { plugin ->
                runCatching { plugin.rules() }.getOrNull().orEmpty()
            }
        return merge(saved, fromPlugins)
    }

    /**
     * 合并一份已读出的行（供维护端把数据库 Flow 直接接进来）。
     *
     * @param saved 数据库里的行：内置规则的启停覆盖 + 用户/插件规则
     * @param pluginRules 插件本次提供的规则
     */
    fun merge(
        saved: List<StyleRule>,
        pluginRules: List<StyleRule> = emptyList(),
    ): List<StyleRule> {
        val overrides = saved.filter { it.source == StyleRuleSource.BUILTIN }.associateBy { it.id }
        val effectiveBuiltins =
            builtins.map { base ->
                overrides[base.id]?.let { row -> base.copy(enabled = row.enabled) } ?: base
            }
        val fromPlugins = pluginRules.map { it.copy(source = StyleRuleSource.PLUGIN) }
        val userRules = saved.filter { it.source == StyleRuleSource.USER }
        val personaRules = saved.filter { it.source == StyleRuleSource.PERSONA }
        return (userRules + personaRules + fromPlugins + effectiveBuiltins)
            .filter { it.text.isNotBlank() }
            .distinctBy { normalize(it.text) }
    }

    /** 去重用的归一化：忽略空白与句尾标点，避免“同一句话换个写法”重复注入。 */
    private fun normalize(text: String): String =
        text.filterNot { it.isWhitespace() }.trimEnd('。', '.', '！', '!', '；', ';')

    /** 合并后的「最终注入列表」 */
    fun activeRules(
        saved: List<StyleRule>,
        pluginRules: List<StyleRule> = emptyList(),
    ): List<StyleRule> = merge(saved, pluginRules).filter { it.enabled }
}

/** 把约束渲染成提示词块。 */
object StyleRender {
    /** 注入条数上限：再多就会把「像真人」挤成「背规则」。 */
    const val MAX_INJECTED = 14

    /** 单条最大字符数（用户可能贴一整段，这里统一收口） */
    const val MAX_ONE_RULE = 120

    fun block(rules: List<StyleRule>): String? {
        val usable =
            rules.filter { it.enabled && it.text.isNotBlank() }
                .take(MAX_INJECTED)
        if (usable.isEmpty()) return null
        return buildString {
            append("说话风格（比人设里的修辞更硬，必须遵守；与「像真人」冲突时选像真人）：")
            usable.forEach { rule ->
                append("\n- ").append(rule.text.trim().take(MAX_ONE_RULE))
            }
            append("\n以上每条都要落实到每一次开口；宁可少说一句，也不要退回 AI 腔。")
        }
    }
}

/** 内存实现（测试用）。 */
class InMemoryStyleRuleStore(
    initial: List<StyleRule> = emptyList(),
) : StyleRuleStore {
    private val rows = initial.associateBy { it.id }.toMutableMap()
    private var nextId = 1L

    override suspend fun all(): List<StyleRule> = rows.values.toList()

    override suspend fun upsert(rule: StyleRule): Long {
        val id =
            rule.id.ifBlank {
                "user.${nextId++}"
            }
        rows[id] = rule.copy(id = id)
        return id.hashCode().toLong()
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        rows[id]?.let { rows[id] = it.copy(enabled = enabled) }
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }

    override suspend fun clearUserRules() {
        rows.values.removeAll { it.source == StyleRuleSource.USER }
    }

    override suspend fun clearSource(source: StyleRuleSource) {
        rows.values.removeAll { it.source == source }
    }
}

/**
 * 从人设正文里自动提取「怎么说话」的约束。
 *
 * 与 [PersonaLoreExtractor] 分工：那边抽「认识谁、什么梗」，这边抽「话该怎么说」。
 * 产物以 [StyleRuleSource.PERSONA] 落库，可以整批重抽，不会覆盖用户手写的规则。
 *
 * 提取范围刻意收窄：只收可执行的指令（长度、语气、格式、黑名单、身份边界），
 * 不收性格形容词、世界观设定、背景故事——那些留在人设正文里就行。
 */
class PersonaStyleExtractor(private val client: LlmClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun extract(
        personaPrompt: String,
        config: AiConfig,
        now: Long = System.currentTimeMillis(),
    ): List<StyleRule> {
        val prompt = personaPrompt.trim()
        if (prompt.isEmpty()) return emptyList()
        val system =
            "你在把一张角色卡里的「说话方式要求」整理成一份可执行的风格清单。\n" +
                "只抽取对「怎么说话」的硬性要求：句子长度、消息节奏、语气、称呼、" +
                "排版格式、必须避免的 AI 套话 / 句式、身份与边界。\n" +
                "不要抽取性格描述、背景故事、世界观设定、人际关系；" +
                "不要编造原文里没有的要求；一条只讲一件事。\n" +
                "category 只能从这五个里选：LENGTH（长度与节奏）、TONE（语气与称呼）、" +
                "FORMAT（排版格式）、ECHO（要避免的套话/句式）、BOUNDARY（身份与边界）。\n" +
                "只输出 JSON，不要解释、不要代码块：\n" +
                "{\"rules\":[{\"text\":\"一句可执行的指令\",\"category\":\"LENGTH\"}]}\n" +
                "限制：最多 12 条；每条不超过 60 字；没有就输出 []。"
        val user = "角色卡正文：\n" + prompt.take(6000)
        val reply =
            client.chat(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                temperature = 0.1,
                maxTokens = GenerationLimits.forConfig(config),
                thinkingType = ThinkingParams.forConfig(config, forceDisable = true),
            )
        return parse(reply.content, now)
    }

    internal fun parse(
        raw: String,
        now: Long,
    ): List<StyleRule> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val body = raw.substring(start, end + 1)
        val payload =
            runCatching { json.decodeFromString<RawStyleRules>(body) }.getOrNull()
                ?: return emptyList()
        return payload.rules
            .filter { it.text.isNotBlank() }
            .map { it.text.trim().take(StyleRender.MAX_ONE_RULE) to parseCategory(it.category) }
            .distinctBy { (text, _) -> text.filterNot { ch -> ch.isWhitespace() } }
            .take(MAX_EXTRACTED)
            .map { (text, category) ->
                StyleRule(
                    id = "persona.${text.hashCode().toUInt().toString(16)}",
                    text = text,
                    category = category,
                    source = StyleRuleSource.PERSONA,
                    enabled = true,
                )
            }
    }

    private fun parseCategory(raw: String): StyleRuleCategory =
        runCatching { StyleRuleCategory.valueOf(raw.trim().uppercase()) }
            .getOrDefault(StyleRuleCategory.TONE)

    @Serializable
    private data class RawStyleRules(val rules: List<RawRule> = emptyList())

    @Serializable
    private data class RawRule(
        val text: String = "",
        val category: String = "TONE",
    )

    companion object {
        const val MAX_EXTRACTED = 12
    }
}
