package com.qbot.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.qbot.app.ui.screens.settings.SETTINGS_HOME_ID
import com.qbot.app.ui.screens.settings.SettingsHome
import com.qbot.app.ui.screens.settings.settingsEntries

/**
 * 设置入口：首页列出分组入口，点进去是独立子页。
 *
 * 页面注册表见 [settingsEntries]：内置页与未来的插件页共用同一套结构，
 * 这里只负责「首页 ↔ 子页」的切换与系统返回键处理，不关心具体有哪些页。
 */
@Composable
fun SettingsScreen(onRequestBatteryWhitelist: () -> Unit) {
    val entries = settingsEntries(onRequestBatteryWhitelist)
    var pageId by rememberSaveable { mutableStateOf(SETTINGS_HOME_ID) }
    val current = entries.firstOrNull { it.id == pageId }

    // 子页时拦截系统返回键回首页；首页时交还给系统（退出应用）
    BackHandler(enabled = current != null) { pageId = SETTINGS_HOME_ID }

    Box(modifier = Modifier.fillMaxSize()) {
        if (current == null) {
            SettingsHome(entries = entries, onOpen = { pageId = it })
        } else {
            current.content { pageId = SETTINGS_HOME_ID }
        }
    }
}
