package com.qbot.app.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.qbot.app.BotApplication

@Composable
fun CredentialsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val bots = app.bots
    val currentId by bots.currentBotId.collectAsState()
    val account = remember(currentId) { bots.currentAccount }
    val settings = remember(currentId) { bots.currentSettings }
    val initial = remember(currentId) { settings.credentials() }

    var appId by rememberSaveable(currentId) { mutableStateOf(initial.appId) }
    // 密钥不进 savedInstanceState，旋屏后从加密存储重读（未保存的改动会丢，属预期）
    var clientSecret by remember(currentId) { mutableStateOf(initial.clientSecret) }
    var hint by remember { mutableStateOf<String?>(null) }

    SettingsPageScaffold(
        title = "机器人凭证",
        subtitle = "当前：${account.name} · 在 QQ 开放平台创建机器人后获得",
        onBack = onBack,
    ) {
        SettingsCard {
            OutlinedTextField(
                value = appId,
                onValueChange = { appId = it },
                label = { Text("AppID") },
                placeholder = { Text("机器人 ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = clientSecret,
                onValueChange = { clientSecret = it },
                label = { Text("AppSecret") },
                placeholder = { Text("机器人密钥，用于请求签名") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            HintText("凭证使用 Android Keystore 加密存储，不会明文写入磁盘。")

            Button(
                onClick = {
                    settings.saveCredentials(appId.trim(), clientSecret.trim())
                    bots.restartBot(account.id)
                    hint = "已保存。该机器人在运行中会自动重连，其他机器人不受影响。"
                },
                enabled = appId.isNotBlank() && clientSecret.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存并重连")
            }

            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        SectionHeader("在哪找", "QQ 开放平台 → 开发者后台 → 你的机器人 → 开发设置")
        SettingsCard {
            HintText(
                "AppID 与 AppSecret 是机器人接入的唯一凭证，泄露等于别人可以冒充你的机器人。" +
                    "更换凭证后需要重新保存并重启服务。",
            )
        }
    }
}
