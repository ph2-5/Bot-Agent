package com.qbot.app.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.qbot.app.BotApplication

@Composable
fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val versionName =
        remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "1.9.3"
        }

    SettingsPageScaffold(
        title = "使用说明",
        subtitle = "版本 $versionName",
        onBack = onBack,
    ) {
        SettingsCard {
            Text(
                """
                · 本应用通过 QQ 官方机器人开放平台接入，走 WebSocket 长连接，无需 root。
                · 机器人只能在其已被添加的群里工作，单聊需用户主动发起。
                · 未认证的机器人仅管理员可用，可添加到你作为群主的群内测试。
                · 若长时间掉线，请检查：电池优化白名单、自启动权限、通知权限。
                · 手机锁屏后系统可能限制网络，建议将本应用加入系统「后台保护」列表。
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SectionHeader("接话是怎么判断的", "三级漏斗，任何一级不通过都不会发言")
        SettingsCard {
            Text(
                """
                1. 频率：冷却时间、每小时/每天上限——先挡住「说太多」；
                2. 内容：规则打分（疑问句加分、单字扣分、@ 别人扣分…），负分直接当噪音；
                3. 判断：先弄清这句话是对谁说的（@ 你 / 某人 / 全群），
                   再让模型按性格判断"此刻想不想接"——不是应不应该，是有没有说话的冲动。
                   判断会附带一句理由，你可以在「AI → 接话」里复核对错，
                   标错之后它会把这个案例总结成偏好，下次判断时优先参考。
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SectionHeader("关于数据", "所有数据都在本机")
        SettingsCard {
            HintText(
                "配置存在应用私有目录的 DataStore，凭证与 API Key 经 Android Keystore 加密；" +
                    "记忆、日志、决策记录存在本机 SQLite。卸载应用会一并删除，重要配置请先导出。",
            )
        }
    }
}
