package com.qbot.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.core.ai.SceneDecisionRecord
import com.qbot.core.ai.SceneLimits
import com.qbot.core.ai.SceneMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 场景与对谈：跨消息的「正在演一段」状态。
 *
 * 入口从「AI → 接话」提到设置首页：它是开关，藏在别的页面的长列表里
 * 等于没有——被用户连问两次「这功能在哪」之后加的。
 */
@Composable
fun ScenePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val mode by app.controller.sceneMode.collectAsState()
    val decisions by app.controller.observeSceneDecisions(20).collectAsState(initial = emptyList())
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    SettingsPageScaffold(
        title = "场景与对谈",
        subtitle = "让机器人记住「我们正在演一段」，而不是每句话重新判断要不要接",
        onBack = onBack,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("档位（按机器人独立设置）", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SceneMode.entries.forEach { item ->
                        FilterChip(
                            selected = mode == item,
                            onClick = { scope.launch { app.controller.saveSceneMode(item) } },
                            label = { Text(modeLabel(item)) },
                        )
                    }
                }
                Text(
                    when (mode) {
                        SceneMode.OFF ->
                            "关闭：工具不发给模型，判定链不查场景——行为和没有这个功能时完全一致。"
                        SceneMode.OBSERVE ->
                            "只读：AI 照常判断「该开场了吗 / 该我接了吗」，但只写记录、不说话。" +
                                "先跑几天看它的判断准不准，再决定要不要放开。"
                        SceneMode.ACTIVE ->
                            "执行：真实接话。单场最长 ${SceneLimits.MAX_SCENE_MILLIS / 60000} 分钟，" +
                                "单群每小时最多 ${SceneLimits.MAX_SPEAKS_PER_HOUR} 条，" +
                                "对方连续 ${SceneLimits.MAX_PROMPTS} 次不接就自动收尾。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("双人演出需要三个前提", fontWeight = FontWeight.Medium)
                Text(
                    "1. 两个机器人都在同一个群；\n" +
                        "2. 群主在 QQ 的机器人设置里，给两个机器人都打开「接收全部群消息」" +
                        "（默认只收 @ 消息，不开就听不到对方说什么）；\n" +
                        "3. 两个机器人的这个档位都设成「执行」——只设一个是演不起来的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text("最近决策", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (decisions.isEmpty()) {
            Text(
                "还没有记录。切到「只读」或「执行」后，AI 每次判断都会记在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            decisions.forEach { record -> DecisionCard(record) }
        }
    }
}

@Composable
private fun DecisionCard(record: SceneDecisionRecord) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    actionLabel(record.action),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (record.executed) "已执行" else "仅记录",
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (record.executed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Text(
                    formatTime(record.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val reason = record.reason
            if (!reason.isNullOrBlank()) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun modeLabel(mode: SceneMode): String =
    when (mode) {
        SceneMode.OFF -> "关闭"
        SceneMode.OBSERVE -> "只读"
        SceneMode.ACTIVE -> "执行"
    }

private fun actionLabel(action: String): String =
    when (action.uppercase()) {
        "START" -> "开场"
        "WAIT" -> "等待"
        "PROMPT" -> "催场"
        "END" -> "收尾"
        else -> action
    }

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
