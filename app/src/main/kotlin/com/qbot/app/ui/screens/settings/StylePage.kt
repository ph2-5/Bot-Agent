package com.qbot.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.core.ai.StyleRuleCategory
import com.qbot.core.ai.StyleRuleSource
import kotlinx.coroutines.launch

/**
 * 说话风格：把角色卡里的「去 AI 味」铁律做成可开关的功能。
 *
 * 三层来源对应三种使用方式：
 * - 内置：开箱即用，可逐条停用（停用只影响这一个机器人）；
 * - 自定义：补一条自己想要的约束；
 * - 插件：未来插件系统接入后自动出现在这里。
 */
@Composable
fun StylePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val controller = app.controller
    val scope = rememberCoroutineScope()

    val rules by controller.observeStyleRules().collectAsState(initial = emptyList())
    val builtin = rules.filter { it.source == StyleRuleSource.BUILTIN }
    val userRules = rules.filter { it.source == StyleRuleSource.USER }
    val pluginRules = rules.filter { it.source == StyleRuleSource.PLUGIN }

    var adding by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    SettingsPageScaffold(
        title = "说话风格",
        subtitle = "角色卡里的「去 AI 味」铁律，按条生效",
        onBack = onBack,
    ) {
        SectionHeader(
            "内置约束 ${builtin.count { it.enabled }}/${builtin.size} 条启用",
            "从群聊铁律、说话方式、AI 味黑名单提炼而成；与人设里的修辞冲突时，以这里为准",
        )

        StyleRuleCategory.entries.forEach { category ->
            val group = builtin.filter { it.category == category }
            if (group.isEmpty()) return@forEach
            SettingsCard {
                Text(
                    categoryTitle(category),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                group.forEach { rule ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            rule.text,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { enabled ->
                                scope.launch { controller.setStyleRuleEnabled(rule, enabled) }
                            },
                        )
                    }
                }
            }
        }

        SectionHeader("我加的约束", "只对这个机器人生效，换人设也保留")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { adding = true }) { Text("添加一条") }
            if (userRules.isNotEmpty()) {
                TextButton(
                    onClick = {
                        scope.launch {
                            controller.clearUserStyleRules()
                            hint = "已清空自定义约束"
                        }
                    },
                ) { Text("清空") }
            }
        }
        if (userRules.isEmpty()) {
            HintText(
                "还没有。例如「不要用问句结尾」「被怼就一句话顶回去，别解释」——" +
                    "写成一句能直接执行的指令，比写形容词有用。",
            )
        }
        userRules.forEach { rule ->
            SettingsCard {
                Text(rule.text, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { enabled ->
                            scope.launch { controller.setStyleRuleEnabled(rule, enabled) }
                        },
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                controller.deleteStyleRule(rule)
                                hint = "已删除这条约束"
                            }
                        },
                    ) { Text("删除") }
                }
            }
        }

        SectionHeader("插件约束", "插件系统接入后，插件带来的约束会自动出现在这里")
        if (pluginRules.isEmpty()) {
            HintText("当前没有插件提供约束。")
        } else {
            pluginRules.forEach { rule ->
                SettingsCard { Text(rule.text, style = MaterialTheme.typography.bodySmall) }
            }
        }

        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    if (adding) {
        AddStyleRuleDialog(
            onDismiss = { adding = false },
            onConfirm = { text ->
                adding = false
                scope.launch {
                    val ok = controller.addStyleRule(text)
                    hint = if (ok) "已添加" else "内容为空，没有添加"
                }
            },
        )
    }
}

private fun categoryTitle(category: StyleRuleCategory): String =
    when (category) {
        StyleRuleCategory.LENGTH -> "长度与节奏"
        StyleRuleCategory.TONE -> "语气与称呼"
        StyleRuleCategory.FORMAT -> "排版格式"
        StyleRuleCategory.ECHO -> "AI 套话"
        StyleRuleCategory.BOUNDARY -> "身份与边界"
    }

@Composable
private fun AddStyleRuleDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加一条说话约束") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 120) text = it },
                    label = { Text("约束") },
                    placeholder = { Text("例如：不要用问句结尾") },
                    modifier = Modifier.fillMaxWidth(),
                )
                HintText("写成一句能直接执行的指令，别写「幽默一点」这种形容词。")
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 首页摘要：内置启用几条、自定义几条。 */
@Composable
fun StyleSubtitle(): String {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val rules by app.controller.observeStyleRules().collectAsState(initial = emptyList())
    if (rules.isEmpty()) return "…"
    val on = rules.count { it.enabled }
    val mine = rules.count { it.source == StyleRuleSource.USER }
    return "启用 $on 条 · 自定义 $mine 条"
}
