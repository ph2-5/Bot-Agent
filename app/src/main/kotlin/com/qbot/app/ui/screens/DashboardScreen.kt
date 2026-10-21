package com.qbot.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qbot.app.BotApplication
import com.qbot.app.ui.theme.StatusError
import com.qbot.app.ui.theme.StatusIdle
import com.qbot.app.ui.theme.StatusOnline
import com.qbot.app.ui.theme.StatusWarning
import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.AiMode
import com.qbot.core.ai.LlmUsage
import com.qbot.core.gateway.ConnectionState
import com.qbot.core.gateway.GatewayStatus
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = (context.applicationContext as BotApplication).controller

    val status by controller.status.collectAsState()
    val aiConfig by controller.aiConfig.collectAsState()
    val aiUsage by controller.aiUsage.collectAsState()

    // 在线时长需要持续跳动，每秒刷新一次时间基准
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!hasNotificationPermission) {
            PermissionBanner(onRequestNotificationPermission)
        }

        StatusCard(status = status, now = now)

        StatsRow(status = status)

        AiStatusCard(config = aiConfig, usage = aiUsage)

        ControlButtons(
            isRunning = status.state != ConnectionState.IDLE,
            onStart = onStartService,
            onStop = onStopService,
        )

        status.lastError?.let { error ->
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "最近一次异常",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: GatewayStatus,
    now: Long,
) {
    val (color, label) =
        when (status.state) {
            ConnectionState.READY -> StatusOnline to "运行中"
            ConnectionState.CONNECTING -> StatusWarning to "连接中"
            ConnectionState.IDENTIFYING -> StatusWarning to "鉴权中"
            ConnectionState.RESUMING -> StatusWarning to "恢复会话中"
            ConnectionState.RECONNECTING -> StatusWarning to "重连中"
            ConnectionState.FATAL -> StatusError to "已停止（需人工处理）"
            ConnectionState.IDLE -> StatusIdle to "未启动"
        }

    val pulse by animateFloatAsState(
        targetValue = if (status.state == ConnectionState.READY) 1f else 0.45f,
        label = "pulse",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.15f + pulse * 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(color),
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = label,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = color,
            )

            Spacer(Modifier.height(8.dp))

            status.readySinceMillis?.let { since ->
                Text(
                    text = "已在线 ${formatDuration(now - since)}",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (status.state == ConnectionState.RECONNECTING) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "第 ${status.reconnectCount} 次重试",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusWarning,
                )
            }

            status.sessionId?.let { sid ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "会话 ${sid.take(8)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** AI 接入状态一览：让维护端一眼看出 AI 是否在干活。 */
@Composable
private fun AiStatusCard(
    config: AiConfig,
    usage: LlmUsage?,
) {
    val (color, label) =
        when {
            !config.enabled -> StatusIdle to "AI 未启用"
            !config.isReady -> StatusWarning to "AI 配置不完整"
            else -> StatusOnline to "AI 已启用"
        }

    val modeLabel =
        when (config.mode) {
            AiMode.RULE_FIRST -> "规则优先"
            AiMode.AI_ALWAYS -> "AI 优先"
            AiMode.COMMAND_ONLY -> "仅 /ai 命令"
        }

    val thinkingLabel =
        when (config.thinkingMode) {
            com.qbot.core.ai.ThinkingMode.DISABLED -> "思考关"
            com.qbot.core.ai.ThinkingMode.ENABLED -> "思考开"
            com.qbot.core.ai.ThinkingMode.AUTO -> "跟随默认"
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(color),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("AI 接入", fontWeight = FontWeight.Bold)
                Text(
                    text =
                        if (config.enabled && config.isReady) {
                            "$label · $modeLabel · $thinkingLabel · ${config.model}"
                        } else {
                            label
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 缓存命中率：长期为 0 说明系统提示词不稳定（被动态内容污染），
                // 既费钱又拖慢首字延迟，值得点开设置页检查。
                if (config.enabled && usage != null && usage.promptTokens > 0) {
                    val ratio = (usage.cacheHitRatio ?: 0f) * 100
                    Text(
                        text =
                            "上次请求 ${usage.promptTokens} tokens（缓存命中 " +
                                "${ratio.toInt()}%）｜回复 ${usage.completionTokens} tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (ratio >= 50f) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsRow(status: GatewayStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard("收到事件", status.totalEvents.toString(), Modifier.weight(1f))
        StatCard("消息", status.totalMessages.toString(), Modifier.weight(1f))
        StatCard("重连", status.reconnectCount.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ControlButtons(
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onStart,
            enabled = !isRunning,
            modifier = Modifier.weight(1f).height(52.dp),
        ) {
            Text("启动机器人", fontSize = 16.sp)
        }

        OutlinedButton(
            onClick = onStop,
            enabled = isRunning,
            modifier = Modifier.weight(1f).height(52.dp),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    contentColor = StatusError,
                ),
        ) {
            Text("停止", fontSize = 16.sp)
        }
    }
}

@Composable
private fun PermissionBanner(onRequest: () -> Unit) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = StatusWarning.copy(alpha = 0.15f),
            ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "需要通知权限",
                fontWeight = FontWeight.Bold,
                color = StatusWarning,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Android 13 起前台服务必须显示通知，未授权将无法在后台保持在线。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRequest) {
                Text("立即授权")
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        days > 0 -> "${days}天 ${hours}小时 ${minutes}分"
        hours > 0 -> "${hours}小时 ${minutes}分 ${secs}秒"
        minutes > 0 -> "${minutes}分 ${secs}秒"
        else -> "${secs}秒"
    }
}
