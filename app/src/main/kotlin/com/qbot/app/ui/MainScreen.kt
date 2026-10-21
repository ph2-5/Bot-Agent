package com.qbot.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.qbot.app.ui.screens.DashboardScreen
import com.qbot.app.ui.screens.LogsScreen
import com.qbot.app.ui.screens.RulesScreen
import com.qbot.app.ui.screens.SettingsScreen

private enum class Tab(
    val label: String,
    val icon: ImageVector,
) {
    STATUS("状态", Icons.Default.Dashboard),
    LOGS("日志", Icons.AutoMirrored.Filled.List),
    RULES("规则", Icons.Default.Rule),
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
    var selected by remember { mutableIntStateOf(0) }

    Scaffold(
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
        androidx.compose.foundation.layout.Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            when (selected) {
                0 ->
                    DashboardScreen(
                        hasNotificationPermission = hasNotificationPermission,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onStartService = onStartService,
                        onStopService = onStopService,
                    )

                1 -> LogsScreen()
                2 -> RulesScreen()
                3 -> SettingsScreen(onRequestBatteryWhitelist = onRequestBatteryWhitelist)
            }
        }
    }
}
