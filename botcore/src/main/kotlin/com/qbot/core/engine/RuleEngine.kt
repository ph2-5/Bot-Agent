package com.qbot.core.engine

import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.MessageKind
import com.qbot.core.model.cleanText

/** 匹配方式。 */
enum class MatchType {
    /** 完全相等 */
    EXACT,

    /** 包含 */
    CONTAINS,

    /** 前缀 */
    PREFIX,

    /** 正则（Java 正则语法） */
    REGEX,
}

/** 规则生效范围。 */
enum class RuleScope {
    ALL,
    GROUP_ONLY,
    PRIVATE_ONLY,
}

/**
 * 自动回复规则。
 *
 * 这一层刻意保持为**纯数据 + 纯函数**，不碰网络与数据库，
 * 因此可以脱离 Android 直接做单元测试，也方便将来导出/导入规则配置。
 */
data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val matchType: MatchType = MatchType.CONTAINS,
    val pattern: String,
    val reply: String,
    val scope: RuleScope = RuleScope.ALL,
    /** 数值越大优先级越高 */
    val priority: Int = 0,
)

/**
 * 规则引擎。
 *
 * 规则列表可变：用户在维护端增删改规则后无需重启机器人，
 * 下一次消息进来即刻生效。列表用 `@Volatile` 引用整体替换，
 * 读取方要么读到旧快照要么读到新快照，不会出现半更新状态。
 */
class RuleEngine(
    rules: List<Rule> = emptyList(),
) {
    @Volatile
    private var rules: List<Rule> = rules
        private set

    /** 整体替换规则列表。 */
    fun updateRules(rules: List<Rule>) {
        this.rules = rules.toList()
    }

    /** 当前生效的规则快照。 */
    fun rulesSnapshot(): List<Rule> = rules

    /**
     * 找出匹配的第一条规则。
     *
     * 命中顺序：先按 [Rule.priority] 降序，再按列表原始顺序，
     * 保证配置可预期——用户调整优先级即可覆盖，不必依赖列表顺序。
     */
    fun match(
        message: IncomingMessage,
        botId: String = "",
    ): Rule? {
        val text = message.cleanText(botId)
        if (text.isEmpty()) return null

        return rules
            .asSequence()
            .filter { it.enabled }
            .filter { it.scopeMatches(message.kind) }
            .sortedWith(compareByDescending<Rule> { it.priority })
            .firstOrNull { it.matches(text) }
    }

    private fun Rule.scopeMatches(kind: MessageKind): Boolean =
        when (scope) {
            RuleScope.ALL -> true
            RuleScope.GROUP_ONLY -> kind == MessageKind.GROUP_AT || kind == MessageKind.GUILD
            RuleScope.PRIVATE_ONLY -> kind == MessageKind.C2C || kind == MessageKind.DIRECT
        }

    private fun Rule.matches(text: String): Boolean {
        if (pattern.isEmpty()) return false
        return when (matchType) {
            MatchType.EXACT -> text.equals(pattern, ignoreCase = true)
            MatchType.CONTAINS -> text.contains(pattern, ignoreCase = true)
            MatchType.PREFIX -> text.startsWith(pattern, ignoreCase = true)
            MatchType.REGEX ->
                runCatching { Regex(pattern).containsMatchIn(text) }
                    .getOrDefault(false)
        }
    }

    companion object {
        /**
         * 渲染回复模板。
         *
         * 支持占位符：
         * - `{content}` 用户原话（已剥离 @ 前缀）
         * - `{sender}`  发送者 openid
         * - `{target}`  群 openid 或 用户 openid
         * - `{time}`    当前时间 HH:mm
         * - `{nl}`      换行
         */
        fun render(
            template: String,
            message: IncomingMessage,
            botId: String = "",
        ): String {
            val timeText =
                java.text
                    .SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date(message.timestampMillis))

            return template
                .replace("{content}", message.cleanText(botId))
                .replace("{sender}", message.senderId.orEmpty())
                .replace("{target}", message.targetId.orEmpty())
                .replace("{time}", timeText)
                .replace("{nl}", "\n")
        }
    }
}
