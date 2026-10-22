package com.qbot.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.app.runtime.BotController
import com.qbot.app.service.BotForegroundService
import com.qbot.core.ai.AiMode
import com.qbot.core.ai.LlmClient
import com.qbot.core.model.MessageKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onRequestBatteryWhitelist: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as BotApplication
    val settings = remember { app.settings }
    val controller = remember { app.controller }
    val scope = rememberCoroutineScope()

    val credentials = remember { settings.credentials() }
    var appId by remember { mutableStateOf(credentials.appId) }
    var clientSecret by remember { mutableStateOf(credentials.clientSecret) }
    var savedHint by remember { mutableStateOf<String?>(null) }

    var autoStart by remember { mutableStateOf(true) }
    var powerSaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        autoStart = settings.autoStart.first()
        powerSaving = settings.powerSaving.first()
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------- 凭证 ----------
        SettingsCard(title = "机器人凭证", subtitle = "在 QQ 开放平台创建机器人后获得") {
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

            Text(
                "凭证使用 Android Keystore 加密存储，不会明文写入磁盘。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        settings.saveCredentials(appId, clientSecret)
                        controller.restart()
                        savedHint = "已保存，机器人正在重新连接"
                    },
                    enabled = appId.isNotBlank() && clientSecret.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存并重启")
                }

                OutlinedButton(
                    onClick = {
                        if (BotForegroundService.shouldRequestBatteryWhitelist(context)) {
                            onRequestBatteryWhitelist()
                        } else {
                            savedHint = "已在电池优化白名单中"
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("电池优化")
                }
            }

            savedHint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // ---------- AI 接入 ----------
        AiSettingsSection()

        // ---------- 记忆系统快捷开关 ----------
        MemoryQuickSection()

        // ---------- 运行策略 ----------
        SettingsCard(title = "运行策略") {
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

        // ---------- 手动发送 ----------
        ManualSendSection(
            onSend = { kind, targetId, content ->
                scope.launch { controller.sendManual(kind, targetId, content) }
            },
        )

        // ---------- 备份与恢复 ----------
        BackupSection(controller = controller)

        // ---------- 说明 ----------
        SettingsCard(title = "使用说明") {
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
    }
}

/**
 * 备份与恢复：导出/导入全部用户配置（换机或备份用）。
 *
 * 走系统文件选择器（SAF）：导出用 CreateDocument 生成 JSON，
 * 导入用 OpenDocument 读取后交给 [BotController.importBackup] 合并。
 */
@Composable
private fun BackupSection(controller: BotController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var hint by remember { mutableStateOf<String?>(null) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                    val json = controller.exportBackup()
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选位置")
                }.onSuccess {
                    hint = "已导出到所选位置（含密钥，请妥善保管）"
                }.onFailure { hint = "导出失败：${it.message}" }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                    val text =
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.readText()
                            ?: error("无法读取所选文件")
                    controller.importBackup(text)
                }.onSuccess { hint = it }
                    .onFailure { hint = "导入失败：${it.message}" }
            }
        }

    SettingsCard(
        title = "备份与恢复",
        subtitle = "换机或备份用：规则、人设、AI/记忆/参与配置、备注名、群名片",
    ) {
        Text(
            "导出内容包含机器人凭证与 AI Key（明文 JSON），备份文件请妥善保管。" +
                "导入按「合并」进行：同 id 规则覆盖、同名同绑定的人设更新、其余新增。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    val name = "qbot-config-%tF.json".format(java.util.Date())
                    exportLauncher.launch(name)
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("导出配置")
            }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                modifier = Modifier.weight(1f),
            ) {
                Text("导入配置")
            }
        }

        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String? = null,
    // Column 是 @Composable 函数而非类，不能写成 Column.() 作为 receiver 类型；
    // Column 的 content lambda 接收者是 ColumnScope。
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 记忆系统快捷开关。
 *
 * 完整的记忆管理（查看/改名/删除每个人的事实、人设绑定）在「AI」Tab，
 * 这里只放一个总开关和存量概览，方便从设置页直接发现该能力。
 */
@Composable
private fun MemoryQuickSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as BotApplication
    val settings = remember { app.settings }
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(settings.memoryConfig.value.enabled) }
    var peopleCount by remember { mutableStateOf(0) }
    var factCount by remember { mutableStateOf(0) }

    // 每次进入页面刷新一次存量统计（记忆是运行时持续写入的，够用即可）
    LaunchedEffect(Unit) {
        peopleCount = app.database.identityDao().count()
        factCount = app.database.factDao().count()
    }

    SettingsCard(
        title = "记忆系统",
        subtitle = "机器人会记住每个人是谁、说过什么，并按人隔离会话",
    ) {
        SwitchRow(
            title = "启用记忆",
            desc = "关闭后退化为纯对话机器人，已存数据保留不会删除",
            checked = enabled,
            onCheckedChange = { value ->
                enabled = value
                scope.launch {
                    settings.saveMemoryConfig(settings.memoryConfig.value.copy(enabled = value))
                }
            },
        )

        Text(
            "已认识 $peopleCount 人 · 记住 $factCount 条事实",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            "群聊里对机器人说「叫我 XX」即可改名；说「我的记忆」「忘掉 XX」可查看/删除。" +
                "到「AI」Tab 可以管理每个人的记忆、给不同群和人配置专属人设。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ManualSendSection(onSend: (MessageKind, String, String) -> Unit) {
    var kind by remember { mutableStateOf(MessageKind.GROUP_AT) }
    var targetId by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as BotApplication

    // 恢复上次发送的目标，减少重复输入
    LaunchedEffect(Unit) {
        val (savedKind, savedId) = app.settings.lastTarget()
        runCatching { kind = MessageKind.valueOf(savedKind) }
        targetId = savedId
    }

    SettingsCard(
        title = "手动发送",
        subtitle = "这里发出的是主动消息，会占用主动消息配额",
    ) {
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
            placeholder = { Text("可在日志中看到最近消息的 openid") },
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
                    onSend(kind, targetId.trim(), content)
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
}

/**
 * AI 接入配置。
 *
 * 走 OpenAI 兼容协议，因此 DeepSeek / 通义千问 / Kimi / 智谱 / OpenAI
 * 都能用同一套字段接入——用户只需要填对三项：服务地址、API Key、模型名。
 */
@Composable
private fun AiSettingsSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as BotApplication
    val settings = remember { app.settings }
    val controller = remember { app.controller }
    val scope = rememberCoroutineScope()

    val initial = remember { settings.aiConfig.value }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var systemPrompt by remember { mutableStateOf(initial.systemPrompt) }
    var temperature by remember { mutableFloatStateOf(initial.temperature.toFloat()) }
    var contextTurns by remember { mutableStateOf(initial.contextTurns.toString()) }
    var replyOnError by remember { mutableStateOf(initial.replyOnError) }
    var mode by remember { mutableStateOf(initial.mode) }
    var thinkingMode by remember { mutableStateOf(initial.thinkingMode) }
    var tokenBudget by remember { mutableFloatStateOf(initial.contextTokenBudget.toFloat()) }
    var hint by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    SettingsCard(title = "AI 接入", subtitle = "OpenAI 兼容协议，DeepSeek / 通义 / Kimi / 智谱 / OpenAI 通用") {
        SwitchRow(
            title = "启用 AI 应答",
            desc = "开启后按下方触发方式决定哪些消息交给 AI",
            checked = enabled,
            onCheckedChange = { enabled = it },
        )

        Text(
            "常用服务地址（也可填本地 Ollama，如 http://192.168.1.5:11434）：",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            LlmClient.PRESETS.joinToString("\n") { (url, name) -> "· $name：$url" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("服务地址 baseUrl") },
            placeholder = { Text("https://api.deepseek.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            placeholder = { Text("sk-...") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型名") },
            placeholder = { Text("deepseek-chat") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            label = { Text("系统提示词") },
            placeholder = { Text("定义 AI 的角色与语气") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        // 提示词越长，每轮请求的固定成本越高、前缀缓存越难稳定命中。
        // 实时显示估算 token 数，让「太长」从玄学变成可见的数字。
        val promptTokens = com.qbot.core.ai.TokenEstimator.estimate(systemPrompt)
        Text(
            "约 $promptTokens tokens（每轮请求都会携带）",
            style = MaterialTheme.typography.labelSmall,
            color =
                if (promptTokens > 1500) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )

        Text("思考模式", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = thinkingMode == com.qbot.core.ai.ThinkingMode.DISABLED,
                onClick = { thinkingMode = com.qbot.core.ai.ThinkingMode.DISABLED },
                label = { Text("关闭") },
            )
            FilterChip(
                selected = thinkingMode == com.qbot.core.ai.ThinkingMode.ENABLED,
                onClick = { thinkingMode = com.qbot.core.ai.ThinkingMode.ENABLED },
                label = { Text("开启") },
            )
            FilterChip(
                selected = thinkingMode == com.qbot.core.ai.ThinkingMode.AUTO,
                onClick = { thinkingMode = com.qbot.core.ai.ThinkingMode.AUTO },
                label = { Text("自动") },
            )
        }
        Text(
            when (thinkingMode) {
                com.qbot.core.ai.ThinkingMode.DISABLED ->
                    "关闭思考：模型直接给答案，响应最快（推荐日常使用）。DeepSeek 默认开启思考，这就是感觉慢的原因。"
                com.qbot.core.ai.ThinkingMode.ENABLED ->
                    "开启思考：先输出思维链再作答，更严谨但明显更慢，且 temperature 等参数会失效。"
                com.qbot.core.ai.ThinkingMode.AUTO ->
                    "自动：不发送该参数，按服务商默认行为（DeepSeek 默认开启思考）。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("触发方式", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == AiMode.RULE_FIRST,
                onClick = { mode = AiMode.RULE_FIRST },
                label = { Text("规则优先") },
            )
            FilterChip(
                selected = mode == AiMode.AI_ALWAYS,
                onClick = { mode = AiMode.AI_ALWAYS },
                label = { Text("AI 优先") },
            )
            FilterChip(
                selected = mode == AiMode.COMMAND_ONLY,
                onClick = { mode = AiMode.COMMAND_ONLY },
                label = { Text("仅 /ai") },
            )
        }
        Text(
            when (mode) {
                AiMode.RULE_FIRST -> "本地规则未命中时才问 AI，适合「关键词 + AI」混用。"
                AiMode.AI_ALWAYS -> "所有消息都交给 AI，本地规则暂时失效。"
                AiMode.COMMAND_ONLY -> "只有 /ai 开头的消息才触发 AI，其余按规则处理。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            if (thinkingMode == com.qbot.core.ai.ThinkingMode.ENABLED) {
                "随机性 temperature（思考模式下不生效）"
            } else {
                "随机性 temperature：${"%.1f".format(temperature)}"
            },
            fontWeight = FontWeight.Medium,
            color =
                if (thinkingMode == com.qbot.core.ai.ThinkingMode.ENABLED) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        Slider(
            value = temperature,
            onValueChange = { temperature = it },
            valueRange = 0f..1.5f,
            enabled = thinkingMode != com.qbot.core.ai.ThinkingMode.ENABLED,
        )

        Text(
            "上下文 token 预算：${tokenBudget.toInt()}",
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = tokenBudget,
            onValueChange = { tokenBudget = it },
            valueRange = 1000f..8000f,
            steps = 13,
        )
        Text(
            "系统提示词 + 历史对话 + 本轮问题的总量上限。超出时自动从最早的对话开始丢弃。" +
                "提示词越短越稳定，模型的前缀缓存越容易命中——命中部分价格仅 1/10，首字延迟也大幅降低。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = contextTurns,
            onValueChange = { contextTurns = it.filter { c -> c.isDigit() } },
            label = { Text("上下文轮数") },
            placeholder = { Text("每个会话记住最近几轮对话") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = replyOnError,
            onValueChange = { replyOnError = it },
            label = { Text("失败兜底话术") },
            placeholder = { Text("AI 出错时回复什么，留空则不回复") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val config =
                        com.qbot.core.ai.AiConfig(
                            enabled = enabled,
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                            systemPrompt = systemPrompt,
                            temperature = temperature.toDouble(),
                            maxTokens = initial.maxTokens,
                            mode = mode,
                            contextTurns = contextTurns.toIntOrNull()?.coerceIn(1, 20) ?: 6,
                            replyOnError = replyOnError,
                            thinkingMode = thinkingMode,
                            contextTokenBudget = tokenBudget.toInt(),
                        )
                    settings.saveAiConfig(config)
                    hint = "已保存，新设置对下一条消息立即生效"
                },
                enabled = !enabled || (apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()),
                modifier = Modifier.weight(1f),
            ) {
                Text("保存 AI 设置")
            }

            OutlinedButton(
                onClick = {
                    val config =
                        com.qbot.core.ai.AiConfig(
                            enabled = enabled,
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                            systemPrompt = systemPrompt,
                            temperature = temperature.toDouble(),
                            maxTokens = initial.maxTokens,
                            mode = mode,
                            contextTurns = contextTurns.toIntOrNull()?.coerceIn(1, 20) ?: 6,
                            replyOnError = replyOnError,
                            thinkingMode = thinkingMode,
                            contextTokenBudget = tokenBudget.toInt(),
                        )
                    testing = true
                    hint = null
                    scope.launch {
                        val result =
                            runCatching { controller.testAi(config) }
                        testing = false
                        hint =
                            result.fold(
                                onSuccess = { "连接成功，模型回复：${it.take(50)}" },
                                onFailure = {
                                    // 兜底 never null：异常 message 为空时给出类型名，
                                    // 避免界面上出现裸的 "null"
                                    "连接失败：${it.message ?: it::class.simpleName ?: "未知错误"}"
                                },
                            )
                    }
                },
                enabled = !testing && apiKey.isNotBlank() && model.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (testing) "测试中…" else "测试连接")
            }
        }

        Text(
            "API Key 经 Android Keystore 加密存储。对话上下文按群/人隔离，" +
                "发送 /ai 清空 可重置当前会话。保存后无需重启机器人。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
