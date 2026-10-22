package com.qbot.app.ui.plugin

import com.qbot.app.ui.screens.settings.SettingsEntry

/**
 * 插件宿主（预留）。
 *
 * 2.0.0 只留接缝，不实现调度：未来插件系统定型后，由插件运行时在这里
 * 返回各自的设置页（`SettingsEntry`），设置中心会自动多出对应分组，
 * 无需改动 `SettingsScreen` 的分发逻辑。
 *
 * 约定：
 * - 条目 id 用 `plugin.<pluginId>[.<page>]` 前缀，避免与内置页冲突；
 * - 插件自己的数据表统一命名 `plugin_<pluginId>_*`；
 * - 插件配置通过 `SettingsRepository` 的插件命名空间读写（待实现）。
 */
object PluginHost {
    fun entries(): List<SettingsEntry> = emptyList()
}
