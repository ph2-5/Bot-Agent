package com.qbot.app.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.qbot.app.BotApplication
import com.qbot.app.ui.plugin.PluginHost

/** 设置首页的页面 id（不与任何子页冲突）。 */
const val SETTINGS_HOME_ID = "home"

/**
 * 一个设置页面。
 *
 * 用字符串 id 而不是枚举：插件将来注册自己的页面时无需修改任何分发逻辑，
 * 只要往 [PluginHost.entries] 里返回条目即可（id 约定 `plugin.<pluginId>`）。
 *
 * @param subtitle 首页入口的实时摘要，例如「已认识 12 人 · 86 条事实」
 * @param content 子页内容，参数为返回首页的回调
 */
data class SettingsEntry(
    val id: String,
    val group: String,
    val title: String,
    val icon: ImageVector,
    val subtitle: @Composable () -> String,
    val content: @Composable (onBack: () -> Unit) -> Unit,
)

/**
 * 全部设置页 = 内置页 + 插件页。
 *
 * 插件系统尚未实现，[PluginHost.entries] 现在恒为空；未来它返回条目后，
 * 设置首页会自动多出「插件」分组，两侧代码都不用改。
 */
@Composable
fun settingsEntries(onRequestBatteryWhitelist: () -> Unit): List<SettingsEntry> =
    builtinEntries(onRequestBatteryWhitelist) + PluginHost.entries()

@Composable
private fun builtinEntries(onRequestBatteryWhitelist: () -> Unit): List<SettingsEntry> =
    listOf(
        SettingsEntry(
            id = "credentials",
            group = "接入",
            title = "机器人凭证",
            icon = Icons.Filled.VpnKey,
            subtitle = { CredentialsSubtitle() },
        ) { onBack -> CredentialsPage(onBack = onBack) },
        SettingsEntry(
            id = "runtime",
            group = "接入",
            title = "运行与保活",
            icon = Icons.Filled.Bolt,
            subtitle = { RuntimeSubtitle() },
        ) { onBack ->
            RuntimePage(onBack = onBack, onRequestBatteryWhitelist = onRequestBatteryWhitelist)
        },
        SettingsEntry(
            id = "ai_model",
            group = "智能",
            title = "AI 模型",
            icon = Icons.Filled.Psychology,
            subtitle = { AiSubtitle() },
        ) { onBack -> AiModelPage(onBack = onBack) },
        SettingsEntry(
            id = "memory",
            group = "智能",
            title = "记忆系统",
            icon = Icons.Filled.Memory,
            subtitle = { MemorySubtitle() },
        ) { onBack -> MemoryPage(onBack = onBack) },
        SettingsEntry(
            id = "chitchat",
            group = "智能",
            title = "插话与应答",
            icon = Icons.Filled.Forum,
            subtitle = { ChitchatSubtitle() },
        ) { onBack -> ChitchatPage(onBack = onBack) },
        SettingsEntry(
            id = "persona_growth",
            group = "智能",
            title = "人格成长",
            icon = Icons.Filled.Psychology,
            subtitle = { PersonaGrowthSubtitle() },
        ) { onBack -> PersonaGrowthPage(onBack = onBack) },
        SettingsEntry(
            id = "manual_send",
            group = "工具",
            title = "手动发送",
            icon = Icons.Filled.Send,
            subtitle = { "主动发一条消息（占用主动消息配额）" },
        ) { onBack -> ManualSendPage(onBack = onBack) },
        SettingsEntry(
            id = "backup",
            group = "工具",
            title = "数据与备份",
            icon = Icons.Filled.Backup,
            subtitle = { "导出 / 导入配置与经验" },
        ) { onBack -> BackupPage(onBack = onBack) },
        SettingsEntry(
            id = "about",
            group = "工具",
            title = "使用说明",
            icon = Icons.Filled.Info,
            subtitle = { "第一次用先看这里" },
        ) { onBack -> AboutPage(onBack = onBack) },
    )

// ---------- 首页摘要 ----------

@Composable
private fun CredentialsSubtitle(): String {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val creds = remember { app.settings.credentials() }
    return if (creds.appId.isBlank()) "未配置" else "AppID 尾号 ${creds.appId.takeLast(4)}"
}

@Composable
private fun RuntimeSubtitle(): String {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val autoStart by app.settings.autoStart.collectAsState(initial = true)
    val logDays by app.settings.logRetentionDays.collectAsState(initial = 3)
    return "自启${if (autoStart) "开" else "关"} · 日志 $logDays 天"
}

@Composable
private fun AiSubtitle(): String {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val config by app.controller.aiConfig.collectAsState()
    return when {
        !config.enabled -> "未启用"
        config.model.isBlank() -> "已启用，未填模型"
        else -> config.model
    }
}

@Composable
private fun PersonaGrowthSubtitle(): String {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val persona by app.controller.observeActivePersona().collectAsState(initial = null)
    val current = persona ?: return "还没有人格"
    return buildString {
        append(
            when (current.stage) {
                com.qbot.core.ai.PersonaStage.EMBRYO -> "破壳中"
                com.qbot.core.ai.PersonaStage.GROWING -> "成长中"
                com.qbot.core.ai.PersonaStage.MATURE -> "已成熟"
                com.qbot.core.ai.PersonaStage.MANUAL -> "手动设定"
            },
        )
        if (current.frozen) append(" · 已冻结")
        if (current.growthCount > 0) append(" · 已成长 ${current.growthCount} 次")
    }
}

@Composable
private fun MemorySubtitle(): String {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    var counts by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(Unit) {
        counts =
            runCatching {
                app.database.identityDao().count() to app.database.factDao().count()
            }.getOrNull()
    }
    val pair = counts
    return if (pair == null) "…" else "已认识 ${pair.first} 人 · ${pair.second} 条事实"
}

@Composable
private fun ChitchatSubtitle(): String {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val config by app.controller.chitchatConfig.collectAsState()
    return when {
        !config.enabled -> "未开启（只回复 @）"
        config.chimeRules.isEmpty() -> "已开启 · 通用规则"
        else -> "已开启 · 规则 ${config.chimeRules.size} 条"
    }
}
