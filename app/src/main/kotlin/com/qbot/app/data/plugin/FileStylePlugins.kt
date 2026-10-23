package com.qbot.app.data.plugin

import com.qbot.core.ai.StyleRule
import com.qbot.core.ai.StyleRuleCategory
import com.qbot.core.ai.StyleRulePlugin
import com.qbot.core.ai.StyleRuleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 文件式语气插件：把 JSON 放进插件目录（默认在应用外部私有目录的
 * `plugins/` 下，设置页会显示完整路径）即生效。
 *
 * 这是插件系统的第一个真实接缝——先只支持「贡献语气约束」这一件事，
 * 不引入安装、权限、生命周期这些重机制。格式：
 *
 * ```json
 * {
 *   "id": "my-plugin",
 *   "name": "我的插件",
 *   "enabled": true,
 *   "style_rules": [
 *     { "text": "不要用问句结尾", "enabled": true, "category": "TONE" }
 *   ]
 * }
 * ```
 *
 * 每次取规则都重新扫目录：删文件、改文件不需要重启机器人。
 * 单个文件解析失败只跳过它，不影响其他插件。
 */
class FileStylePluginHost(
    private val dir: File,
) : StyleRulePlugin {
    private val json = Json { ignoreUnknownKeys = true }

    /** 插件目录，供设置页提示用户放文件的位置。 */
    val directoryPath: String get() = dir.absolutePath

    override suspend fun rules(): List<StyleRule> =
        withContext(Dispatchers.IO) {
            manifests().flatMap { (manifest, index) ->
                manifest.styleRules.mapIndexedNotNull { ruleIndex, rule ->
                    val text = rule.text.trim()
                    if (!rule.enabled || text.isBlank()) {
                        null
                    } else {
                        StyleRule(
                            // 不落库，id 只用于日志与去重展示
                            id = "plugin.${manifest.id}.$index.$ruleIndex",
                            text = text,
                            category =
                                runCatching { StyleRuleCategory.valueOf(rule.category.uppercase()) }
                                    .getOrDefault(StyleRuleCategory.TONE),
                            source = StyleRuleSource.PLUGIN,
                            enabled = true,
                        )
                    }
                }
            }
        }

    private fun manifests(): List<Pair<PluginManifest, Int>> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }
            .mapIndexedNotNull { index, file ->
                runCatching { json.decodeFromString<PluginManifest>(file.readText()) }
                    .getOrNull()
                    ?.takeIf { it.enabled && it.id.isNotBlank() }
                    ?.let { it to index }
            }
    }
}

@Serializable
private data class PluginManifest(
    val id: String = "",
    @Suppress("unused") val name: String = "",
    val enabled: Boolean = true,
    @SerialName("style_rules") val styleRules: List<PluginRule> = emptyList(),
)

@Serializable
private data class PluginRule(
    val text: String = "",
    val enabled: Boolean = true,
    val category: String = "TONE",
)
