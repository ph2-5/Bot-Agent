package com.qbot.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import com.qbot.core.ai.LoreEntry
import com.qbot.core.ai.LoreKind
import com.qbot.core.ai.LoreSource
import kotlinx.coroutines.launch

/**
 * 人设设定：从角色卡里抽出来的「认识的人」和「特殊说法」。
 *
 * 为什么单独存：角色卡是长文，模型每次都要通读去猜；抽出来之后
 * 能逐条查看/停用，而且改人设正文时不会把这些长期设定一起改没。
 */
@Composable
fun LorePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val controller = app.controller
    val scope = rememberCoroutineScope()

    val lore by controller.observeLore().collectAsState(initial = emptyList())
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    SettingsPageScaffold(
        title = "人设设定",
        subtitle = "角色卡里认识的人、雷点与特殊说法，抽出来单独存",
        onBack = onBack,
    ) {
        SectionHeader("从人设提取", "让模型读一遍当前人设，只抽设定，不改人设正文")
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        hint = null
                        scope.launch {
                            val added = runCatching { controller.extractPersonaLore() }.getOrDefault(0)
                            hint =
                                if (added == 0) {
                                    "没有抽到内容。检查 AI 配置，或人设里本来就没有明确的称呼与说法。"
                                } else {
                                    "已提取 $added 条设定"
                                }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "正在读人设…" else "从人设提取") }
                TextButton(onClick = { adding = true }) { Text("手动添加") }
            }
            HintText(
                "只抽取「认识谁、该怎么对待」和「哪些说法有特殊含义」，" +
                    "写作规则（短句、去 AI 味）在「说话风格」页，这里不重复。",
            )
        }

        if (lore.isEmpty()) {
            HintText(
                "还没有设定。点「从人设提取」，它会从当前人设里找出「老爹」「大肥鱼是雷点」" +
                    "这类信息，存下来后每次回复都会带上。",
            )
        }

        LoreKind.entries.forEach { kind ->
            val group = lore.filter { it.kind == kind }
            if (group.isEmpty()) return@forEach
            SectionHeader("${kindTitle(kind)} · ${group.size} 条")
            group.forEach { entry ->
                LoreRow(
                    entry = entry,
                    onToggle = { enabled ->
                        scope.launch { controller.setLoreEnabled(entry.id, enabled) }
                    },
                    onDelete = {
                        scope.launch {
                            controller.deleteLore(entry.id)
                            hint = "已删除「${entry.name}」"
                        }
                    },
                )
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
        AddLoreDialog(
            onDismiss = { adding = false },
            onConfirm = { kind, name, note ->
                adding = false
                scope.launch {
                    val ok = controller.addLore(kind, name, note)
                    hint = if (ok) "已添加「${name.trim()}」" else "名字和说明都不能为空"
                }
            },
        )
    }
}

@Composable
private fun LoreRow(
    entry: LoreEntry,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "「${entry.name}」",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(entry.note, style = MaterialTheme.typography.bodySmall)
                Text(
                    buildString {
                        append(if (entry.source == LoreSource.AI) "AI 提取" else "手动添加")
                        entry.scopeGroupId?.let { append(" · 仅群 ").append(it.takeLast(6)) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = entry.enabled, onCheckedChange = onToggle)
        }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

@Composable
private fun AddLoreDialog(
    onDismiss: () -> Unit,
    onConfirm: (LoreKind, String, String) -> Unit,
) {
    var kind by remember { mutableStateOf(LoreKind.PERSON) }
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加一条人设设定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LoreKind.entries.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(kindTitle(option)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 12) name = it },
                    label = { Text("名字 / 说法") },
                    placeholder = { Text("例如：老爹") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 40) note = it },
                    label = { Text("怎么对待 / 什么意思") },
                    placeholder = { Text("例如：他只对老爹温顺，别对群友用这一套") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && note.isNotBlank(),
                onClick = { onConfirm(kind, name.trim(), note.trim()) },
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun kindTitle(kind: LoreKind): String =
    when (kind) {
        LoreKind.PERSON -> "认识的人"
        LoreKind.SLANG -> "特殊说法"
        LoreKind.RELATION -> "关系"
        LoreKind.NOTE -> "其他设定"
    }

/** 首页摘要：抽过多少条设定。 */
@Composable
fun LoreSubtitle(): String {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val lore by app.controller.observeLore().collectAsState(initial = emptyList())
    if (lore.isEmpty()) return "还没抽过设定"
    return "${lore.size} 条设定"
}
