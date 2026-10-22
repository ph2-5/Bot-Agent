package com.qbot.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.core.model.MessageKind
import kotlinx.coroutines.launch

@Composable
fun ManualSendPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val scope = rememberCoroutineScope()

    var kind by rememberSaveable { mutableStateOf(MessageKind.GROUP_AT) }
    var targetId by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }

    // 恢复上次发送的目标，减少重复输入
    LaunchedEffect(Unit) {
        val (savedKind, savedId) = app.settings.lastTarget()
        runCatching { kind = MessageKind.valueOf(savedKind) }
        if (targetId.isBlank()) targetId = savedId
    }

    SettingsPageScaffold(
        title = "手动发送",
        subtitle = "这里发出的是主动消息，会占用主动消息配额",
        onBack = onBack,
    ) {
        SettingsCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == MessageKind.GROUP_AT,
                    onClick = { kind = MessageKind.GROUP_AT },
                    label = { Text("群聊") },
                )
                FilterChip(
                    selected = kind == MessageKind.C2C,
                    onClick = { kind = MessageKind.C2C },
                    label = { Text("单聊") },
                )
            }

            OutlinedTextField(
                value = targetId,
                onValueChange = { targetId = it },
                label = { Text(if (kind == MessageKind.GROUP_AT) "群 openid" else "用户 openid") },
                placeholder = { Text("可在日志或群档案中看到 openid") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("消息内容") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    scope.launch {
                        app.settings.saveLastTarget(kind.name, targetId)
                        app.controller.sendManual(kind, targetId.trim(), content)
                        hint = "已提交发送，请在日志页确认结果"
                    }
                },
                enabled = targetId.isNotBlank() && content.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Text("  发送", modifier = Modifier.padding(start = 6.dp))
            }

            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        HintText("群聊主动消息有每日配额，且部分群可能被管理员关闭；发送失败原因见日志页。")
    }
}
