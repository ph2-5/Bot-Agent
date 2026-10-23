package com.qbot.app.ui.plugin

import com.qbot.app.ui.screens.settings.SettingsEntry

/**
 * 插件宿主（设置页部分）。
 *
 * 语气插件已经落地为「文件式插件」：应用外部私有目录 `plugins/` 下的 JSON 由
 * [com.qbot.app.data.plugin.FileStylePluginHost] 读取，规则直接进入
 * 「说话风格」页的插件分区与提示词渲染链路。
 *
 * 这里仍只负责设置页条目：未来插件需要独立设置页时，在 [entries] 返回
 * 各自的 `SettingsEntry`，设置中心会自动多出对应分组，无需改分发逻辑。
 *
 * 约定：
 * - 条目 id 用 `plugin.<pluginId>[.<page>]` 前缀，避免与内置页冲突；
 * - 插件自己的数据表统一命名 `plugin_<pluginId>_*`；
 * - 插件配置通过 `SettingsRepository` 的插件命名空间读写（待实现）。
 */
object PluginHost {
    fun entries(): List<SettingsEntry> = emptyList()
}
