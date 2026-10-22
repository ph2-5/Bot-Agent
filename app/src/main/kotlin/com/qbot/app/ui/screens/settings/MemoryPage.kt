package com.qbot.app.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.qbot.app.BotApplication
import com.qbot.core.ai.MemoryConfig

@Composable
fun MemoryPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val config by app.controller.memoryConfig.collectAsState()

    var peopleCount by remember { mutableStateOf(0) }
    var factCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        runCatching {
            peopleCount = app.database.identityDao().count()
            factCount = app.database.factDao().count()
        }
    }

    val update: (MemoryConfig) -> Unit = { app.settings.saveMemoryConfig(it) }

    SettingsPageScaffold(
        title = "记忆系统",
        subtitle = "机器人会记住每个人是谁、说过什么，并按人隔离会话",
        onBack = onBack,
    ) {
        SettingsCard {
            SwitchRow(
                title = "启用记忆",
                desc = "关闭后退化为纯对话机器人：不认人、不记事，已存数据保留",
                checked = config.enabled,
                onCheckedChange = { update(config.copy(enabled = it)) },
            )
            SwitchRow(
                title = "学习自称名",
                desc = "用户说「叫我XXX」时记住，群里就能认人",
                checked = config.learnNames,
                onCheckedChange = { update(config.copy(learnNames = it)) },
                enabled = config.enabled,
            )
            SwitchRow(
                title = "提取事实",
                desc = "从「我喜欢 / 在备考 / 住在…」这类陈述里提取长期记忆",
                checked = config.learnFacts,
                onCheckedChange = { update(config.copy(learnFacts = it)) },
                enabled = config.enabled,
            )
            SwitchRow(
                title = "共享群聊背景",
                desc = "记住群里最近的对话，让「那他呢」这类承接能听懂",
                checked = config.roomContextEnabled,
                onCheckedChange = { update(config.copy(roomContextEnabled = it)) },
                enabled = config.enabled,
            )
            SwitchRow(
                title = "指令回复人设化",
                desc = "「我叫什么 / 我的记忆 / 叫你X / 群里都有谁」的结果交给 AI 按人设语气说出；" +
                    "关闭后走固定文案，不消耗 API",
                checked = config.personaRewrite,
                onCheckedChange = { update(config.copy(personaRewrite = it)) },
                enabled = config.enabled,
            )
        }

        SectionHeader("容量", "记太多会稀释相关性，记太少又不够用")
        SettingsCard {
            var factsPerPerson by remember(config.factsPerPerson) {
                mutableFloatStateOf(config.factsPerPerson.toFloat())
            }
            Text(
                "每人最多保留 ${factsPerPerson.toInt()} 条事实（超出自动淘汰最旧的）",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = factsPerPerson,
                onValueChange = { factsPerPerson = it },
                valueRange = 5f..100f,
                enabled = config.enabled,
                onValueChangeFinished = {
                    update(config.copy(factsPerPerson = factsPerPerson.toInt().coerceIn(5, 100)))
                },
            )

            var roomContextSize by remember(config.roomContextSize) {
                mutableFloatStateOf(config.roomContextSize.toFloat())
            }
            Text(
                "群聊背景保留 ${roomContextSize.toInt()} 条（跨人指代的上下文窗口）",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = roomContextSize,
                onValueChange = { roomContextSize = it },
                valueRange = 5f..50f,
                enabled = config.enabled && config.roomContextEnabled,
                onValueChangeFinished = {
                    update(
                        config.copy(roomContextSize = roomContextSize.toInt().coerceIn(5, 50)),
                    )
                },
            )
        }

        SectionHeader("存量", "查看与删除具体记忆在「AI → 记忆」")
        SettingsCard {
            Text(
                "已认识 $peopleCount 人 · 记住 $factCount 条事实",
                style = MaterialTheme.typography.bodyMedium,
            )
            HintText(
                "群里对机器人说「叫我 XX」即可改名；说「我的记忆」查看、「忘掉 XX」删除。" +
                    "每人的完整事实列表在 AI Tab 里可以逐条处理。",
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}
