package com.qbot.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.qbot.app.data.repo.observeSummaries
import com.qbot.core.ai.BotMood
import com.qbot.core.ai.MoodPolicy
import com.qbot.core.ai.PersonRelation
import com.qbot.core.ai.SlangTerm
import kotlinx.coroutines.launch

/**
 * 关系与梗。
 *
 * 这一页回答三个问题：它现在什么心情、它对每个人什么印象、它听懂了哪些群里的说法。
 * 三样都不直接给用户改性格——只展示与微调，性格仍然归人设页。
 */
@Composable
fun RelationPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val controller = app.controller
    val scope = rememberCoroutineScope()

    val relations by controller.observeRelations(50).collectAsState(initial = emptyList())
    val slang by controller.observeSlang().collectAsState(initial = emptyList())
    val groups by controller.groupStore().observeSummaries().collectAsState(initial = emptyList())

    var mood by remember { mutableStateOf(BotMood()) }
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        mood = runCatching { controller.currentMood() }.getOrDefault(BotMood())
    }

    val groupNames =
        remember(groups) {
            groups.associate {
                it.groupId to (it.botName?.takeIf { name -> name.isNotBlank() }
                    ?: "群 ${it.groupId.takeLast(6)}")
            }
        }

    SettingsPageScaffold(
        title = "关系与梗",
        subtitle = "它对每个人的印象、自己的心情，以及从群里学会的说法",
        onBack = onBack,
    ) {
        SectionHeader("它现在的心情", "心情只影响语气松紧，不影响礼数")
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "心情 ${mood.value}/100",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    MoodPolicy.describe(mood)?.let { "有情绪" } ?: "平常",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { mood.value / 100f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
            HintText(
                "被夸会高一点，被骂会低很多；每小时自己往 50 回落一点，" +
                    "睡一觉基本就过去了。" +
                    (mood.reason?.let { "最近一次：$it。" } ?: ""),
            )
        }

        SectionHeader("它认识的人", "共 ${relations.size} 人，按好感排序")
        if (relations.isEmpty()) {
            HintText("还没有记录。它跟人聊过、被 @ 过、被夸过或骂过之后，这里会长出第一条。")
        }
        relations.take(30).forEach { relation ->
            RelationRow(
                relation = relation,
                onForget = {
                    scope.launch {
                        controller.forgetRelation(relation.userId)
                        hint = "已忘掉对「${relation.displayName ?: "这个人"}」的印象"
                    }
                },
            )
        }

        SectionHeader("群里的说法", "共 ${slang.size} 条，只会用在对应的群")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    hint = null
                    scope.launch {
                        val added = runCatching { controller.harvestSlang() }.getOrDefault(0)
                        hint =
                            if (added == 0) {
                                "这次没发现新梗。群里要先聊够一些重复的说法。"
                            } else {
                                "已收获 $added 条"
                            }
                        busy = false
                    }
                },
            ) { Text(if (busy) "正在翻群聊…" else "立即收获") }
            TextButton(onClick = { adding = true }) { Text("手动添加") }
        }
        HintText(
            "只收这个群自己反复在说的词，不追网络热搜——" +
                "流行语有半衰期，用错代际比不用更尴尬。",
        )
        if (slang.isEmpty()) {
            HintText("还没有。点「立即收获」，它会翻最近的群聊，挑出反复出现的说法让模型判断。")
        }
        slang.forEach { term ->
            SlangRow(
                term = term,
                groupName = term.scopeGroupId?.let { groupNames[it] } ?: "所有群",
                onToggle = { enabled ->
                    scope.launch { controller.setSlangEnabled(term.id, enabled) }
                },
                onDelete = {
                    scope.launch {
                        controller.deleteSlang(term.id)
                        hint = "已删除「${term.term}」"
                    }
                },
            )
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
        AddSlangDialog(
            onDismiss = { adding = false },
            onConfirm = { term, meaning, usage ->
                adding = false
                scope.launch {
                    controller.addSlang(term, meaning, usage)
                    hint = "已添加「$term」"
                }
            },
        )
    }
}

@Composable
private fun RelationRow(relation: PersonRelation, onForget: () -> Unit) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    relation.displayName ?: "群友 ${relation.userId.takeLast(6)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "熟识 ${relation.familiarity} · 聊过 ${relation.interactionCount} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                when {
                    relation.affinity > 0 -> "好感 +${relation.affinity}"
                    relation.affinity < 0 -> "好感 ${relation.affinity}"
                    else -> "好感 0"
                },
                style = MaterialTheme.typography.labelMedium,
                color =
                    when {
                        relation.affinity > 0 -> MaterialTheme.colorScheme.primary
                        relation.affinity < 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        LinearProgressIndicator(
            progress = { relation.familiarity / 100f },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        relation.lastNote?.takeIf { it.isNotBlank() }?.let {
            Text(
                "最近：$it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onForget) { Text("忘掉这个人") }
    }
}

@Composable
private fun SlangRow(
    term: SlangTerm,
    groupName: String,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "「${term.term}」${term.meaning}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    buildString {
                        append("出现 ${term.hits} 次 · ").append(groupName)
                        when (term.source) {
                            SlangTerm.SOURCE_MANUAL -> append(" · 手动")
                            SlangTerm.SOURCE_AI -> append(" · 人设提取")
                            else -> Unit
                        }
                        term.usage?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onToggle(!term.enabled) }) {
                Text(if (term.enabled) "已启用" else "已停用")
            }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@Composable
private fun AddSlangDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String?) -> Unit,
) {
    var term by remember { mutableStateOf("") }
    var meaning by remember { mutableStateOf("") }
    var usage by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加一条群里的说法") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = term,
                    onValueChange = { if (it.length <= 12) term = it },
                    label = { Text("说法") },
                    placeholder = { Text("例如：绷不住了") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = meaning,
                    onValueChange = { if (it.length <= 40) meaning = it },
                    label = { Text("意思") },
                    placeholder = { Text("例如：忍不住笑了") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = usage,
                    onValueChange = { if (it.length <= 30) usage = it },
                    label = { Text("什么场合用（可不填）") },
                    placeholder = { Text("例如：看到离谱的事") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = term.isNotBlank() && meaning.isNotBlank(),
                onClick = { onConfirm(term.trim(), meaning.trim(), usage.trim().ifBlank { null }) },
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
