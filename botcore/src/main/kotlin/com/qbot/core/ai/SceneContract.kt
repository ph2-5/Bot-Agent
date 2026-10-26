package com.qbot.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一场演出的"契约"：由 AI 在开场时自己写，软件负责记录并执行。
 *
 * 固定模板只能覆盖"相声"这一种语境；真正让不同语境表现不同的做法，
 * 是让进入场景的模型自己写规则——它看得懂这次对话是什么调性，
 * 代码只需要保证规则能塞进上下文、且中途不许改。
 *
 * 所有长度上限都在 [normalized] 里硬性截断：模型不会老实遵守提示词里的字数，
 * 只能由代码兜底，否则一段散文就能把上下文吃掉。
 */
@Serializable
data class SceneContract(
    /** 语言风格：口音/语体/句长/称呼方式 */
    val languageStyle: String = "",
    /** 角色名 → 该角色的行为规则 */
    val roleRules: Map<String, String> = emptyMap(),
    /** 禁忌（最多 5 条） */
    val taboos: List<String> = emptyList(),
    /** 收尾条件 */
    val exitCriteria: String = "",
    /** 可用的素材/话题（最多 3 条） */
    val props: List<String> = emptyList(),
) {
    /** 截断到各字段上限，并丢掉空白项。 */
    fun normalized(): SceneContract =
        SceneContract(
            languageStyle = languageStyle.trim().truncate(MAX_STYLE),
            roleRules =
                roleRules.entries
                    .asSequence()
                    .map {
                        it.key.trim().take(MAX_ROLE_NAME) to it.value.trim().truncate(MAX_ROLE_RULE)
                    }
                    .filter { it.first.isNotBlank() && it.second.isNotBlank() }
                    .take(MAX_ROLES)
                    .toMap(),
            taboos = taboos.cleanList(MAX_TABOO, MAX_TABOOS),
            exitCriteria = exitCriteria.trim().truncate(MAX_EXIT),
            props = props.cleanList(MAX_PROP, MAX_PROPS),
        )

    /** 全空（含只有空白）时视为没写契约，调用方回退固定模板。 */
    fun isEmpty(): Boolean {
        val n = normalized()
        return n.languageStyle.isBlank() &&
            n.roleRules.isEmpty() &&
            n.taboos.isEmpty() &&
            n.exitCriteria.isBlank() &&
            n.props.isEmpty()
    }

    companion object {
        /** 语言风格上限 */
        const val MAX_STYLE = 120

        /** 角色名上限（名字本身不该长） */
        const val MAX_ROLE_NAME = 20

        /** 每个角色的规则上限 */
        const val MAX_ROLE_RULE = 80

        /** 角色数量上限 */
        const val MAX_ROLES = 4

        /** 每条禁忌上限 */
        const val MAX_TABOO = 40

        /** 禁忌条数上限 */
        const val MAX_TABOOS = 5

        /** 收尾条件上限 */
        const val MAX_EXIT = 80

        /** 每条素材上限 */
        const val MAX_PROP = 40

        /** 素材条数上限 */
        const val MAX_PROPS = 3

        /**
         * 从工具参数里解析契约。
         *
         * 模型给的 JSON 结构可能不规整（该是数组的写成字符串、该是对象的写成数组），
         * 一律按类型取、取不到就丢，绝不让一次畸形参数把开场整条打断。
         */
        fun fromJson(raw: JsonObject): SceneContract {
            val style = raw.string("languageStyle").orEmpty()
            val rules =
                (raw["roleRules"] as? JsonObject)
                    ?.entries
                    ?.associate { (name, value) -> name to ((value as? JsonPrimitive)?.contentOrNull.orEmpty()) }
                    .orEmpty()
            val taboos = (raw["taboos"] as? JsonArray).strings()
            val exit = raw.string("exitCriteria").orEmpty()
            val props = (raw["props"] as? JsonArray).strings()
            return SceneContract(style, rules, taboos, exit, props).normalized()
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonArray?.strings(): List<String> =
            this?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    }
}

/** 超长就截断并加省略号；上限包含省略号本身，保证最终长度绝不超。 */
private fun String.truncate(max: Int): String {
    if (length <= max) return this
    if (max <= 1) return take(max)
    return take(max - 1) + "…"
}

/** 逐条 trim、截断、丢空行，再限制条数。 */
private fun List<String>.cleanList(
    maxLength: Int,
    maxCount: Int,
): List<String> =
    asSequence()
        .map { it.trim().truncate(maxLength) }
        .filter { it.isNotBlank() }
        .take(maxCount)
        .toList()
