package com.qbot.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.app.data.db.RuleEntity
import com.qbot.core.engine.MatchType
import com.qbot.core.engine.RuleScope
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as BotApplication
    val dao = remember { app.database.ruleDao() }
    val scope = rememberCoroutineScope()

    val rules by dao.all().collectAsState(initial = emptyList())

    // null = 关闭对话框；非空 = 正在编辑的规则（id 为空表示新建）
    var editing by remember { mutableStateOf<RuleEntity?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = RuleEntity(
                    id = UUID.randomUUID().toString(),
                    name = "",
                    enabled = true,
                    matchType = MatchType.CONTAINS.name,
                    pattern = "",
                    reply = "",
                    scope = RuleScope.ALL.name,
                    priority = 0,
                )
            }) {
                Icon(Icons.Default.Add, contentDescription = "新增规则")
            }
        },
    ) { padding ->
        if (rules.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有任何规则", fontWeight = FontWeight.Bold)
                    Text(
                        "点击右下角新增，例如：包含「你好」→ 回复「你好呀」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rules, key = { it.id }) { rule ->
                    RuleCard(
                        rule = rule,
                        onClick = { editing = rule },
                        onToggle = { enabled ->
                            scope.launch { dao.upsert(rule.copy(enabled = enabled)) }
                        },
                        onDelete = { scope.launch { dao.delete(rule) } },
                    )
                }
            }
        }
    }

    editing?.let { target ->
        RuleEditDialog(
            initial = target,
            isNew = rules.none { it.id == target.id },
            onDismiss = { editing = null },
            onSave = { entity ->
                scope.launch { dao.upsert(entity) }
                editing = null
            },
            onDelete = {
                scope.launch { dao.delete(target) }
                editing = null
            },
        )
    }
}

@Composable
private fun RuleCard(
    rule: RuleEntity,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
            )

            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    text = rule.name.ifBlank { "未命名规则" },
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${rule.matchType}「${rule.pattern}」→ ${rule.reply}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Text(
                    text = "范围 ${rule.scope} · 优先级 ${rule.priority}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RuleEditDialog(
    initial: RuleEntity,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (RuleEntity) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var pattern by remember { mutableStateOf(initial.pattern) }
    var reply by remember { mutableStateOf(initial.reply) }
    var matchType by remember { mutableStateOf(initial.matchType) }
    var scope by remember { mutableStateOf(initial.scope) }
    var priorityText by remember { mutableStateOf(initial.priority.toString()) }
    var enabled by remember { mutableStateOf(initial.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增规则" else "编辑规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("规则名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("匹配方式", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MatchType.entries.forEach { type ->
                        FilterChip(
                            selected = matchType == type.name,
                            onClick = { matchType = type.name },
                            label = { Text(type.name) },
                        )
                    }
                }

                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("关键词 / 正则") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = reply,
                    onValueChange = { reply = it },
                    label = { Text("回复内容") },
                    supportingText = {
                        Text("可用变量：{content} {sender} {target} {time} {nl}")
                    },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("生效范围", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleScope.entries.forEach { s ->
                        FilterChip(
                            selected = scope == s.name,
                            onClick = { scope = s.name },
                            label = { Text(s.name) },
                        )
                    }
                }

                OutlinedTextField(
                    value = priorityText,
                    onValueChange = { priorityText = it.filter { ch -> ch.isDigit() || ch == '-' } },
                    label = { Text("优先级（越大越优先）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("启用该规则")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            pattern = pattern,
                            reply = reply,
                            matchType = matchType,
                            scope = scope,
                            priority = priorityText.toIntOrNull() ?: 0,
                            enabled = enabled,
                        ),
                    )
                },
                enabled = pattern.isNotBlank() && reply.isNotBlank(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
