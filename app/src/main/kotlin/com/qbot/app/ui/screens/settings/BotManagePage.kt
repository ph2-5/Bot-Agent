package com.qbot.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.app.data.repo.BotAccount
import com.qbot.app.ui.theme.StatusIdle
import com.qbot.app.ui.theme.StatusOnline
import com.qbot.app.ui.theme.StatusWarning
import com.qbot.core.gateway.ConnectionState
import com.qbot.core.gateway.GatewayStatus

/**
 * 机器人管理：一个 App 内管理多个 QQ 开放平台机器人，并让它们同时在线。
 *
 * 每个机器人有独立的人格、记忆、群档案与凭证（独立的库与设置文件），
 * 互不串味；这里只负责账号层面的增删改与启停。
 *
 * 注意：删除机器人会连同它的库与凭证一起删掉，且至少保留一个。
 */
@Composable
fun BotManagePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val bots = app.bots
    val accounts by bots.accounts.collectAsState()
    val currentId by bots.currentBotId.collectAsState()
    val statuses by bots.statuses.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<BotAccount?>(null) }
    var deleting by remember { mutableStateOf<BotAccount?>(null) }

    SettingsPageScaffold(
        title = "机器人管理",
        subtitle = "多个 AppID 可以同时在线，各自独立人格与记忆",
        onBack = onBack,
    ) {
        SectionHeader("已添加 ${accounts.size} 个", "点击卡片切换为当前维护对象")

        accounts.forEach { account ->
            BotCard(
                account = account,
                appId = remember(account.id) { bots.appIdOf(account.id) },
                isCurrent = account.id == currentId,
                status = statuses[account.id],
                canDelete = accounts.size > 1,
                onSelect = { bots.select(account.id) },
                onEnabledChange = { bots.setBotEnabled(account.id, it) },
                onRename = { renaming = account },
                onDelete = { deleting = account },
            )
        }

        OutlinedButton(
            onClick = { showAdd = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("添加机器人")
        }

        HintText(
            "每个机器人使用各自的数据库与加密凭证。同一个 QQ 群里同时有多个机器人时，" +
                "它们的学习成果不会互相共享——这是刻意设计，避免人格串味。" +
                "凭证与开关都在各自卡片和「机器人凭证」页里维护。",
        )
    }

    if (showAdd) {
        BotEditorDialog(
            title = "添加机器人",
            confirmText = "添加",
            onDismiss = { showAdd = false },
            onConfirm = { name, appId, secret ->
                val account = bots.addBot(name, appId, secret)
                bots.select(account.id)
                showAdd = false
            },
        )
    }

    renaming?.let { account ->
        BotEditorDialog(
            title = "重命名机器人",
            confirmText = "保存",
            initialName = account.name,
            nameOnly = true,
            onDismiss = { renaming = null },
            onConfirm = { name, _, _ ->
                bots.renameBot(account.id, name)
                renaming = null
            },
        )
    }

    deleting?.let { account ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${account.name}」？") },
            text = {
                Text(
                    "这个机器人的人格、记忆、群档案、日志与凭证都会一并删除，且无法恢复。" +
                        "其他机器人不受影响。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        bots.removeBot(account.id)
                        deleting = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BotCard(
    account: BotAccount,
    appId: String,
    isCurrent: Boolean,
    status: GatewayStatus?,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val (color, label) = statusLabel(status)

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isCurrent) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            account.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (isCurrent) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text =
                                buildString {
                                    append(label)
                                    append(" · ")
                                    append(if (appId.isBlank()) "未填 AppID" else "AppID 尾号 ${appId.takeLast(4)}")
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = account.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isCurrent) {
                    TextButton(onClick = onSelect) { Text("设为当前") }
                }
                TextButton(onClick = onRename) { Text("重命名") }
                TextButton(
                    onClick = onDelete,
                    enabled = canDelete,
                ) {
                    Text(
                        "删除",
                        color =
                            if (canDelete) {
                                MaterialTheme.colorScheme.error
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
private fun statusLabel(status: GatewayStatus?): Pair<androidx.compose.ui.graphics.Color, String> =
    when (status?.state) {
        ConnectionState.READY -> StatusOnline to "在线"
        ConnectionState.CONNECTING,
        ConnectionState.IDENTIFYING,
        ConnectionState.RESUMING,
        ConnectionState.RECONNECTING,
        -> StatusWarning to "连接中"
        ConnectionState.FATAL -> MaterialTheme.colorScheme.error to "需处理"
        ConnectionState.IDLE, null -> StatusIdle to "未连接"
    }

/**
 * 新增 / 重命名共用的对话框。
 *
 * 密钥只在新增时需要；密码框不做 state 保存，避免旋屏后落进 Bundle。
 */
@Composable
private fun BotEditorDialog(
    title: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, appId: String, secret: String) -> Unit,
    initialName: String = "",
    nameOnly: Boolean = false,
    initialAppId: String = "",
) {
    var name by remember { mutableStateOf(initialName) }
    var appId by remember { mutableStateOf(initialAppId) }
    var secret by remember { mutableStateOf("") }

    val valid = name.isNotBlank() && (nameOnly || (appId.isNotBlank() && secret.isNotBlank()))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("给自己看的备注，例如「阿远」") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!nameOnly) {
                    OutlinedTextField(
                        value = appId,
                        onValueChange = { appId = it },
                        label = { Text("AppID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text("AppSecret") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HintText("凭证加密保存；添加后可在卡片里随时启停。")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim(), appId.trim(), secret.trim()) },
                enabled = valid,
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 首页入口摘要：当前机器人 + 总数。 */
@Composable
fun BotManageSubtitle(): String {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val accounts by app.bots.accounts.collectAsState()
    val currentId by app.bots.currentBotId.collectAsState()
    val current = accounts.firstOrNull { it.id == currentId }
    val enabled = accounts.count { it.enabled }
    return if (accounts.size <= 1) {
        current?.let { "当前：${it.name}" } ?: "还没有机器人"
    } else {
        "当前：${current?.name ?: "?"} · 共 ${accounts.size} 个（启用 $enabled）"
    }
}
