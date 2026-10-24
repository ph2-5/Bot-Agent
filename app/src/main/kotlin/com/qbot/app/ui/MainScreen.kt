package com.qbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.app.data.repo.BotAccount
import com.qbot.app.ui.screens.AiScreen
import com.qbot.app.ui.screens.DashboardScreen
import com.qbot.app.ui.screens.LogsScreen
import com.qbot.app.ui.screens.RulesScreen
import com.qbot.app.ui.screens.SettingsScreen
import com.qbot.app.ui.theme.StatusError
import com.qbot.app.ui.theme.StatusIdle
import com.qbot.app.ui.theme.StatusOnline
import com.qbot.app.ui.theme.StatusWarning
import com.qbot.core.gateway.ConnectionState
import com.qbot.core.gateway.GatewayStatus

private enum class Tab(
    val label: String,
    val icon: ImageVector,
) {
    STATUS("状态", Icons.Default.Dashboard),
    LOGS("日志", Icons.AutoMirrored.Filled.List),
    RULES("规则", Icons.Default.Rule),
    AI("AI", Icons.Default.Psychology),
    SETTINGS("设置", Icons.Default.Settings),
}

@Composable
fun MainScreen(
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
) {
    // 旋屏 / 进程重建后停在原来的 Tab，而不是跳回状态页
    var selected by rememberSaveable { mutableIntStateOf(0) }

    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val accounts by app.bots.accounts.collectAsState()
    val currentId by app.bots.currentBotId.collectAsState()
    val statuses by app.bots.statuses.collectAsState()

    Scaffold(
        topBar = {
            // 只有一个机器人时不必占用这一行；多机器人时常驻，随时切换维护对象
            if (accounts.size > 1) {
                BotSwitcherBar(
                    accounts = accounts,
                    currentId = currentId,
                    statuses = statuses,
                    onSelect = { app.bots.select(it.id) },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // 切换机器人后整棵子树重建：所有页面都通过 app.settings / app.database /
            // app.controller 读「当前机器人」，必须强制丢弃上一个机器人留下的 remember 状态。
            key(currentId) {
                when (selected) {
                    0 ->
                        DashboardScreen(
                            hasNotificationPermission = hasNotificationPermission,
                            onRequestNotificationPermission = onRequestNotificationPermission,
                            onStartService = onStartService,
                            onStopService = onStopService,
                            onRequestBatteryWhitelist = onRequestBatteryWhitelist,
                        )

                    1 -> LogsScreen()
                    2 -> RulesScreen()
                    3 -> AiScreen()
                    4 -> SettingsScreen(onRequestBatteryWhitelist = onRequestBatteryWhitelist)
                }
            }
        }
    }
}

/** 顶部机器人切换条：当前机器人、在线概况、切换菜单。 */
@Composable
private fun BotSwitcherBar(
    accounts: List<BotAccount>,
    currentId: String,
    statuses: Map<String, GatewayStatus>,
    onSelect: (BotAccount) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = accounts.firstOrNull { it.id == currentId }
    val enabled = accounts.filter { it.enabled }
    val online = enabled.count { statuses[it.id]?.state == ConnectionState.READY }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor(statuses[currentId]?.state)),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current?.name ?: "默认机器人",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "$online/${enabled.size} 个在线 · 共 ${accounts.size} 个机器人",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box {
                TextButton(onClick = { expanded = true }) {
                    Text("切换")
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    accounts.forEach { account ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (account.enabled) {
                                        account.name
                                    } else {
                                        "${account.name}（已停用）"
                                    },
                                )
                            },
                            onClick = {
                                expanded = false
                                onSelect(account)
                            },
                            leadingIcon = {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(dotColor(statuses[account.id]?.state)),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun dotColor(state: ConnectionState?): androidx.compose.ui.graphics.Color =
    when (state) {
        ConnectionState.READY -> StatusOnline
        ConnectionState.CONNECTING,
        ConnectionState.IDENTIFYING,
        ConnectionState.RESUMING,
        ConnectionState.RECONNECTING,
        -> StatusWarning
        ConnectionState.FATAL -> StatusError
        ConnectionState.IDLE, null -> StatusIdle
    }
