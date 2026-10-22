package com.qbot.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.AiMode
import com.qbot.core.ai.LlmClient
import com.qbot.core.ai.ThinkingMode
import com.qbot.core.ai.TokenEstimator
import kotlinx.coroutines.launch

/**
 * AI 接入配置（OpenAI 兼容协议）。
 *
 * DeepSeek / 通义千问 / Kimi / 智谱 / OpenAI 共用同一套字段：
 * 服务地址、API Key、模型名。保存后对下一条消息立即生效。
 */
@Composable
fun AiModelPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val settings = remember { app.settings }
    val controller = remember { app.controller }
    val scope = rememberCoroutineScope()

    val initial = remember { settings.aiConfig.value }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var baseUrl by rememberSaveable { mutableStateOf(initial.baseUrl) }
    // Key 不进 savedInstanceState，旋屏后从加密存储重读
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var systemPrompt by rememberSaveable { mutableStateOf(initial.systemPrompt) }
    var temperature by rememberSaveable { mutableFloatStateOf(initial.temperature.toFloat()) }
    var contextTurns by rememberSaveable { mutableStateOf(initial.contextTurns.toString()) }
    var replyOnError by rememberSaveable { mutableStateOf(initial.replyOnError) }
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var thinkingMode by rememberSaveable { mutableStateOf(initial.thinkingMode) }
    var tokenBudget by rememberSaveable { mutableFloatStateOf(initial.contextTokenBudget.toFloat()) }
    var maxTokens by rememberSaveable { mutableStateOf(initial.maxTokens) }
    var hint by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun buildConfig() =
        AiConfig(
            enabled = enabled,
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            model = model.trim(),
            systemPrompt = systemPrompt,
            temperature = temperature.toDouble(),
            maxTokens = maxTokens,
            mode = mode,
            contextTurns = contextTurns.toIntOrNull()?.coerceIn(1, 20) ?: 6,
            replyOnError = replyOnError,
            thinkingMode = thinkingMode,
            contextTokenBudget = tokenBudget.toInt(),
        )

    SettingsPageScaffold(
        title = "AI 模型",
        subtitle = "OpenAI 兼容协议，DeepSeek / 通义 / Kimi / 智谱 / OpenAI 通用",
        onBack = onBack,
    ) {
        SettingsCard {
            SwitchRow(
                title = "启用 AI 应答",
                desc = "开启后按下方触发方式决定哪些消息交给 AI",
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
        }

        SectionHeader("服务商", "也可以填本地 Ollama，如 http://192.168.1.5:11434")
        SettingsCard {
            HintText(
                LlmClient.PRESETS.joinToString("\n") { (url, name) -> "· $name：$url" },
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
        }

        SectionHeader("系统提示词", "定义 AI 的角色与语气，每轮请求都会携带")
        SettingsCard {
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("系统提示词") },
                placeholder = { Text("例如：你是一个说话简短、偶尔毒舌的群友") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            // 提示词越长，每轮固定成本越高、前缀缓存越难稳定命中
            val promptTokens = TokenEstimator.estimate(systemPrompt)
            Text(
                "约 $promptTokens tokens",
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (promptTokens > 1500) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }

        SectionHeader("触发方式", "决定哪些消息交给 AI")
        SettingsCard {
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
            HintText(
                when (mode) {
                    AiMode.RULE_FIRST -> "本地规则未命中时才问 AI，适合「关键词 + AI」混用。"
                    AiMode.AI_ALWAYS -> "所有消息都交给 AI，本地规则暂时失效。"
                    AiMode.COMMAND_ONLY -> "只有 /ai 开头的消息才触发 AI，其余按规则处理。"
                },
            )
        }

        SectionHeader("生成参数", "思考模式、随机性、长度与上下文预算")
        SettingsCard {
            Text("思考模式", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = thinkingMode == ThinkingMode.DISABLED,
                    onClick = { thinkingMode = ThinkingMode.DISABLED },
                    label = { Text("关闭") },
                )
                FilterChip(
                    selected = thinkingMode == ThinkingMode.ENABLED,
                    onClick = { thinkingMode = ThinkingMode.ENABLED },
                    label = { Text("开启") },
                )
                FilterChip(
                    selected = thinkingMode == ThinkingMode.AUTO,
                    onClick = { thinkingMode = ThinkingMode.AUTO },
                    label = { Text("自动") },
                )
            }
            HintText(
                when (thinkingMode) {
                    ThinkingMode.DISABLED ->
                        "关闭思考：直接给答案，响应最快（推荐日常使用）。" +
                            "DeepSeek 默认开启思考，这就是感觉慢的原因。"
                    ThinkingMode.ENABLED ->
                        "开启思考：先输出思维链再作答，更严谨但明显更慢，且 temperature 会失效。"
                    ThinkingMode.AUTO ->
                        "自动：不发送该参数，按服务商默认行为（DeepSeek 默认开启思考）。"
                },
            )

            Text(
                if (thinkingMode == ThinkingMode.ENABLED) {
                    "随机性 temperature（思考模式下不生效）"
                } else {
                    "随机性 temperature：${"%.1f".format(temperature)}"
                },
                fontWeight = FontWeight.Medium,
                color =
                    if (thinkingMode == ThinkingMode.ENABLED) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            Slider(
                value = temperature,
                onValueChange = { temperature = it },
                valueRange = 0f..1.5f,
                enabled = thinkingMode != ThinkingMode.ENABLED,
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
            HintText(
                "提示词 + 历史 + 本轮问题的总量上限，超出时从最早的对话开始丢弃。" +
                    "提示词越短，前缀缓存越容易命中——命中部分价格仅 1/10，首字延迟也大幅降低。",
            )

            Text(
                "单次生成上限 $maxTokens tokens（含思考过程）",
                fontWeight = FontWeight.Medium,
            )
            Slider(
                value = maxTokens.toFloat(),
                onValueChange = { maxTokens = it.toInt() },
                valueRange = 200f..4000f,
                steps = 37,
            )
            HintText(
                "统计的是「思考 + 正文」的总量：开启思考时，思维链会先消耗额度，" +
                    "正文可能被挤掉（表现为只回一句空话或干脆没回）。" +
                    "关闭思考时，它就是正文长度上限。",
            )
            if (thinkingMode != ThinkingMode.DISABLED && maxTokens < 800) {
                Text(
                    "当前额度可能不够「思考 + 正文」。建议调到 800 以上，" +
                        "或把思考模式改为「关闭」——接话判断这类短任务尤其明显。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HintText(
                "所有模型调用共用这个上限：回复、接话判断、经验反思都受它约束。" +
                    "额度只是上限、不预扣费，实际按生成量计费。群聊建议 300~800。",
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
        }

        SettingsCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        settings.saveAiConfig(buildConfig())
                        hint = "已保存，新设置对下一条消息立即生效"
                    },
                    enabled =
                        !enabled ||
                            (apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存 AI 设置")
                }

                OutlinedButton(
                    onClick = {
                        testing = true
                        hint = null
                        scope.launch {
                            val result = runCatching { controller.testAi(buildConfig()) }
                            testing = false
                            hint =
                                result.fold(
                                    onSuccess = { "连接成功，模型回复：${it.take(50)}" },
                                    onFailure = {
                                        "连接失败：" +
                                            (it.message ?: it::class.simpleName ?: "未知错误")
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

            HintText(
                "API Key 经 Android Keystore 加密存储。对话上下文按群/人隔离，" +
                    "发送 /ai 清空 可重置当前会话。保存后无需重启机器人。",
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
}
