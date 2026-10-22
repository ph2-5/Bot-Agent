package com.qbot.app.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.app.service.BotForegroundService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun RuntimePage(onBack: () -> Unit, onRequestBatteryWhitelist: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val settings = remember { app.settings }
    val controller = remember { app.controller }
    val scope = rememberCoroutineScope()

    var autoStart by remember { mutableStateOf(true) }
    var powerSaving by remember { mutableStateOf(false) }
    var logDays by remember { mutableStateOf(3) }
    var hint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        autoStart = settings.autoStart.first()
        powerSaving = settings.powerSaving.first()
        logDays = settings.logRetentionDays.first()
    }

    SettingsPageScaffold(
        title = "运行与保活",
        subtitle = "开机自启、省电策略与日志留存",
        onBack = onBack,
    ) {
        SettingsCard {
            SwitchRow(
                title = "开机自动启动",
                desc = "设备重启后自动拉起机器人（需授权自启动权限）",
                checked = autoStart,
                onCheckedChange = { value ->
                    autoStart = value
                    scope.launch { settings.setAutoStart(value) }
                },
            )

            SwitchRow(
                title = "省电模式",
                desc = "只订阅消息事件，减少网络唤醒。会关闭互动事件等订阅",
                checked = powerSaving,
                onCheckedChange = { value ->
                    powerSaving = value
                    scope.launch {
                        settings.setPowerSaving(value)
                        controller.restart()
                    }
                },
            )
        }

        SectionHeader("日志", "长期挂机时日志表是唯一会无限增长的数据")
        SettingsCard {
            Text(
                "保留 $logDays 天（过期自动清理，仅影响日志页的历史记录）",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = logDays.toFloat(),
                onValueChange = { logDays = it.toInt() },
                valueRange = 1f..30f,
                steps = 28,
                onValueChangeFinished = {
                    scope.launch { settings.setLogRetentionDays(logDays) }
                },
            )
        }

        SectionHeader("保活", "掉线大多不是代码问题，而是系统把后台网络掐了")
        SettingsCard {
            HintText(
                "检查顺序：电池优化白名单 → 自启动权限 → 通知权限 → 系统「后台保护」。\n" +
                    "国产 ROM 还可能需要单独允许「后台弹出界面」与「锁屏后保持网络」。",
            )
            OutlinedButton(
                onClick = {
                    if (BotForegroundService.shouldRequestBatteryWhitelist(context)) {
                        onRequestBatteryWhitelist()
                    } else {
                        hint = "已在电池优化白名单中"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("申请电池优化白名单")
            }
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
