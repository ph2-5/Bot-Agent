package com.qbot.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.app.data.repo.observeSummaries
import com.qbot.core.ai.ChimeDecision
import com.qbot.core.ai.ChimeDecisionRecord
import com.qbot.core.ai.ChimeLesson
import com.qbot.core.ai.ChimeStats
import com.qbot.core.ai.InnerThought
import com.qbot.core.ai.LessonCategories
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 筛选：全部 / 待反馈 / 已反馈。 */
private enum class ReviewFilter(val label: String) {
    ALL("全部"),
    PENDING("待反馈"),
    REVIEWED("已反馈"),
}

/**
 * 接话复盘：训练闭环的「人」这一侧。
 *
 * 机器人每次判断都留了结构化记录（含 AI 给的理由）。这里逐条展示，
 * 主人标对错——标错会立刻让模型把这次教训总结成一条经验，
 * 之后判断时优先参考。经验库可以编辑、停用、删除，始终是可控的。
 */
@Composable
fun ChimeReviewScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val controller = app.controller
    val scope = rememberCoroutineScope()

    var filter by rememberSaveable { mutableStateOf(ReviewFilter.PENDING) }
    var groupFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf(ChimeStats()) }
    var hint by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<Long?>(null) }
    /** 待确认的「标错」记录：先让用户补一句备注，再生成经验 */
    var wrongTarget by remember { mutableStateOf<ChimeDecision?>(null) }
    var note by remember { mutableStateOf("") }
    var editingLesson by remember { mutableStateOf<ChimeLesson?>(null) }
    var editingText by remember { mutableStateOf("") }

    val summaries by controller.groupStore().observeSummaries().collectAsState(initial = emptyList())
    val decisions by controller.observeChimeDecisions(groupFilter, 100)
        .collectAsState(initial = emptyList())
    val lessons by controller.observeChimeLessons().collectAsState(initial = emptyList())
    val chitchat by controller.chitchatConfig.collectAsState()
    val thoughts by controller.observeInnerThoughts(30).collectAsState(initial = emptyList())
    var showThoughts by rememberSaveable { mutableStateOf(false) }

    // 反馈落库后 Flow 会重发，统计随之刷新
    LaunchedEffect(decisions, lessons) {
        stats = runCatching { controller.chimeStats() }.getOrDefault(ChimeStats())
    }

    val groupNames =
        remember(summaries) {
            summaries.associate {
                it.groupId to (it.botName?.takeIf { name -> name.isNotBlank() }
                    ?: "群 ${it.groupId.takeLast(6)}")
            }
        }

    val visible =
        decisions.filter {
            when (filter) {
                ReviewFilter.ALL -> true
                ReviewFilter.PENDING -> it.feedback == null
                ReviewFilter.REVIEWED -> it.feedback != null
            }
        }

    fun submit(id: Long, correct: Boolean, noteText: String?) {
        if (busyId != null) return
        busyId = id
        scope.launch {
            hint = runCatching { controller.submitChimeFeedback(id, correct, noteText) }
                .getOrElse { "提交失败：${it.message ?: "未知错误"}" }
            busyId = null
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "stats") { StatsCard(stats = stats, lessonCount = lessons.size) }

        item(key = "thoughts") {
            ThoughtsHeader(
                count = thoughts.size,
                expanded = showThoughts,
                onToggle = { showThoughts = !showThoughts },
            )
        }

        if (showThoughts) {
            if (thoughts.isEmpty()) {
                item(key = "thoughts-empty") {
                    Text(
                        "还没有记录。要它先想再说，得在「设置 → AI 与模型」里打开思考模式；" +
                            "关着的时候这条链路是空的。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(thoughts.take(20), key = { "thought-${it.id}" }) { thought ->
                    ThoughtCard(
                        thought = thought,
                        groupName = thought.groupId?.let { groupNames[it] } ?: "单聊",
                    )
                }
            }
        }

        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewFilter.entries.forEach { item ->
                        FilterChip(
                            selected = filter == item,
                            onClick = { filter = item },
                            label = { Text(item.label) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = groupFilter == null,
                        onClick = { groupFilter = null },
                        label = { Text("全部群") },
                    )
                    summaries.forEach { summary ->
                        FilterChip(
                            selected = groupFilter == summary.groupId,
                            onClick = {
                                groupFilter = if (groupFilter == summary.groupId) null else summary.groupId
                            },
                            label = { Text(groupNames[summary.groupId] ?: "群") },
                        )
                    }
                }
            }
        }

        hint?.let { text ->
            item(key = "hint") {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (visible.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (filter == ReviewFilter.PENDING) {
                        "没有待反馈的判断。机器人接话后，这里会列出它当时的判断与理由，" +
                            "方便你核对对错。"
                    } else {
                        "还没有记录。开启「插话与应答」并让群主放开消息范围后，" +
                            "每次判断都会记在这里（最近 7 天）。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(visible, key = { it.id }) { decision ->
            DecisionCard(
                decision = decision,
                groupName = groupNames[decision.record.groupId] ?: "群",
                busy = busyId == decision.id,
                onCorrect = { submit(decision.id, true, null) },
                onWrong = {
                    note = ""
                    wrongTarget = decision
                },
                onRetry = { submit(decision.id, false, decision.feedbackNote) },
            )
        }

        item(key = "lessons-header") {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    "经验库（${lessons.size}/${chitchat.maxLessons}）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "按场景归档，同类会合并。每次判断参考最多 " +
                        "${chitchat.maxInjectedLessons} 条（本群优先，同一类不超过 " +
                        "${ChimeLesson.MAX_PER_CATEGORY_INJECTED} 条）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (lessons.isEmpty()) {
            item(key = "lessons-empty") {
                Text(
                    "还没有经验。把某条判断标成「不该说 / 该说」，它就会总结一条。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 目录式展示：固定类别顺序，每类显示条数与总支撑次数
        val groupedLessons = lessons.groupBy { it.category }
        LessonCategories.ALL.forEach { (category, label) ->
            val catLessons = groupedLessons[category] ?: return@forEach
            item(key = "cat-$category") {
                Text(
                    "$label · ${catLessons.size} 条 · 支撑 ${catLessons.sumOf { it.supportCount }} 次",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(catLessons, key = { "lesson-${it.id}" }) { lesson ->
                LessonRow(
                    lesson = lesson,
                    groupName = lesson.scopeGroupId?.let { groupNames[it] },
                    onToggle = { enabled ->
                        scope.launch { controller.setChimeLessonEnabled(lesson.id, enabled) }
                    },
                    onEdit = {
                        editingText = lesson.text
                        editingLesson = lesson
                    },
                    onDelete = {
                        scope.launch {
                            controller.deleteChimeLesson(lesson.id)
                            hint = "已删除这条经验"
                        }
                    },
                )
            }
        }
    }

    wrongTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (busyId == null) wrongTarget = null },
            title = { Text("标为判断错误") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "记下它为什么错了，能帮它总结得更准（可不填）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { if (it.length <= 100) note = it },
                        placeholder = { Text("例如：他们只是在互相道晚安") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "提交后会调用一次模型生成经验，费用很小。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = busyId == null,
                    onClick = {
                        val id = target.id
                        wrongTarget = null
                        submit(id, false, note.ifBlank { null })
                    },
                ) {
                    Text(if (busyId == null) "提交并让它记住" else "生成中…")
                }
            },
            dismissButton = {
                TextButton(onClick = { wrongTarget = null }, enabled = busyId == null) {
                    Text("取消")
                }
            },
        )
    }

    editingLesson?.let { lesson ->
        AlertDialog(
            onDismissRequest = { editingLesson = null },
            title = { Text("修改经验") },
            text = {
                OutlinedTextField(
                    value = editingText,
                    onValueChange = { if (it.length <= ChimeLesson.MAX_TEXT_CHARS) editingText = it },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = editingText.isNotBlank(),
                    onClick = {
                        scope.launch {
                            controller.updateChimeLessonText(lesson.id, editingText)
                            editingLesson = null
                            hint = "经验已更新"
                        }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingLesson = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StatsCard(stats: ChimeStats, lessonCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("近 7 天", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val accuracy = stats.accuracyPercent
            Text(
                if (accuracy == null) {
                    "判断 ${stats.total} 次 · 还没有反馈"
                } else {
                    "判断 ${stats.total} 次 · 已反馈 ${stats.reviewed} 次 · 正确率 $accuracy%"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "误接（说了但不该说）${stats.wrongSpoke} · 漏接（该说没说）${stats.wrongSilent}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "经验库 $lessonCount 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 内心戏入口：默认收起，避免把复盘页顶得太长。 */
@Composable
private fun ThoughtsHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "没说出口的话",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "$count 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "它开口前会先想一遍。这段只给你看：群友看不到，也不会进下一轮对话。" +
                    "需要开启「思考模式」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一条内心戏：心里想的 vs 嘴上说的。 */
@Composable
private fun ThoughtCard(thought: InnerThought, groupName: String) {
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "$groupName · ${thought.senderName ?: "群友"} · " +
                    timeFormat.format(Date(thought.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "心里：${thought.thought}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "嘴上：${thought.spoken}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DecisionCard(
    decision: ChimeDecision,
    groupName: String,
    busy: Boolean,
    onCorrect: () -> Unit,
    onWrong: () -> Unit,
    onRetry: () -> Unit,
) {
    val record = decision.record
    val spoke = record.action == ChimeDecisionRecord.ACTION_SPOKE
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (spoke) "说了话" else "没说话",
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (spoke) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "$groupName · ${record.speakerName ?: "群友"} · " +
                        timeFormat.format(Date(record.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(record.messageText, style = MaterialTheme.typography.bodyMedium)

            record.addresseeKind?.let { kind ->
                val label =
                    when (kind) {
                        "BOT" -> "对你说"
                        "MEMBER" -> "对「${record.addresseeName ?: "某位群友"}」说"
                        "GROUP" -> "对全群说"
                        else -> "对象不明"
                    }
                Text(
                    "它以为：这句话是$label",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 让它"当时看到了什么"可见——否则用户只能猜它是不是没看上下文
            if (record.contextSnippet.isNotBlank()) {
                Text(
                    "它当时看到的：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    record.contextSnippet.lines().takeLast(4).joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val meta =
                buildString {
                    record.ruleScore?.let { append("规则分 $it") }
                    record.aiVerdict?.let {
                        if (isNotEmpty()) append(" · ")
                        append("AI $it")
                        record.aiReason?.let { reason -> append("（$reason）") }
                    }
                }
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            record.replyText?.let {
                Text(
                    "它说：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (decision.feedback == null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    TextButton(onClick = onCorrect, enabled = !busy) {
                        Text(if (spoke) "该说 ✓" else "不该说 ✓")
                    }
                    TextButton(onClick = onWrong, enabled = !busy) {
                        Text(if (spoke) "不该说 ✗" else "该说 ✗")
                    }
                    if (busy) {
                        Text(
                            "生成经验中…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
            } else {
                val result =
                    if (decision.feedback == true) {
                        "已标记：判断正确"
                    } else {
                        "已标记：判断错误" +
                            (decision.feedbackNote?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
                    }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        result,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (decision.feedback == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                    )
                    // 已标错但经验生成失败（网络/额度）时，允许重新生成一次
                    if (decision.feedback == false) {
                        TextButton(onClick = onRetry, enabled = !busy) {
                            Text(if (busy) "生成中…" else "重新总结")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LessonRow(
    lesson: ChimeLesson,
    groupName: String?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                lesson.text,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (lesson.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append(groupName ?: "全局")
                        append(" · 支撑 ${lesson.supportCount} 次")
                        append(" · 命中 ${lesson.hits} 次")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "修改", modifier = Modifier.height(20.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.height(20.dp))
                }
                Switch(checked = lesson.enabled, onCheckedChange = onToggle)
            }
        }
    }
}
