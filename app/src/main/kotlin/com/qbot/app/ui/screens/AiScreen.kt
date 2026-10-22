package com.qbot.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.app.data.repo.GreetingConfig
import com.qbot.app.data.repo.GroupSummary
import com.qbot.app.data.repo.observeSummaries
import com.qbot.app.ui.components.ExpandableSection
import com.qbot.core.ai.ChitchatConfig
import com.qbot.core.ai.KnownPerson
import com.qbot.core.ai.MemoryConfig
import com.qbot.core.ai.NameSource
import com.qbot.core.ai.Persona
import com.qbot.core.ai.PersonaSnapshot
import com.qbot.core.ai.PersonaSnapshotKind
import com.qbot.core.ai.RoomMessage
import com.qbot.core.ai.ScoreWeights
import com.qbot.core.ai.UserFact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * AI 管理页：记忆系统 + 人设系统 + 群感知。
 *
 * 设计原则：
 * - 记忆页回答「机器人现在认识谁、记得什么」，每条记忆都可删——
 *   用户对机器人的记忆必须有最终处置权；
 * - 人设页回答「机器人现在是谁」，人设可绑定到
 *   具体某个人 / 某个群 / 全局，优先级 人 > 群 > 全局；
 * - 群组页回答「机器人进了哪些群、在每个群叫什么、见过多少人」——
 *   平台不提供成员名单，这些档案全靠事件拼出来，这里既能查看也能手动修正。
 */
@Composable
fun AiScreen() {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("记忆", "人设", "群组")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(title) },
                )
            }
        }
        when (tab) {
            0 -> MemoryTab()
            1 -> PersonaTab()
            else -> GroupTab()
        }
    }
}

// ---------- 记忆 ----------

/** 一个人的记忆条目（合并身份名录与事实库，两者可能只命中一边）。 */
private data class PersonMemory(
    val senderId: String,
    val displayName: String?,
    val factCount: Int,
)

@Composable
private fun MemoryTab() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as BotApplication
    val controller = app.controller
    val scope = rememberCoroutineScope()

    val memoryConfig by controller.memoryConfig.collectAsState()

    var people by remember { mutableStateOf<List<PersonMemory>>(emptyList()) }
    var totalFacts by remember { mutableIntStateOf(0) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var expandedFacts by remember { mutableStateOf<List<UserFact>>(emptyList()) }
    var renameTarget by remember { mutableStateOf<PersonMemory?>(null) }

    fun reload() {
        scope.launch {
            val dao = app.database
            val identities = dao.identityDao().recent(100)
            val factSenders = dao.factDao().allSenders()
            val merged =
                (identities.map { it.senderId to it.displayName } + factSenders.map { it to null })
                    .groupBy { it.first }
                    .map { (senderId, pairs) ->
                        PersonMemory(
                            senderId = senderId,
                            displayName = pairs.firstNotNullOfOrNull { it.second },
                            factCount = dao.factDao().countForSender(senderId),
                        )
                    }
            people = merged
            totalFacts = dao.factDao().count()
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MemoryConfigCard(
            config = memoryConfig,
            onChange = { app.settings.saveMemoryConfig(it) },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatChip("认识的人", people.size.toString(), Modifier.weight(1f))
            StatChip("记忆条数", totalFacts.toString(), Modifier.weight(1f))
            StatChip(
                "自动学习",
                if (memoryConfig.enabled) "开" else "关",
                Modifier.weight(1f),
            )
        }

        if (people.isEmpty()) {
            EmptyHint(
                "还没有任何记忆",
                "在群里 @ 机器人聊几句，或者发「叫我XXX」让它认识你；" +
                    "说「我喜欢XXX」它会默默记下。随时可以用「我的记忆」查、「忘掉X」删。",
            )
        }

        people.forEach { person ->
            PersonCard(
                person = person,
                expanded = expandedId == person.senderId,
                facts = if (expandedId == person.senderId) expandedFacts else emptyList(),
                onToggle = {
                    if (expandedId == person.senderId) {
                        expandedId = null
                    } else {
                        expandedId = person.senderId
                        scope.launch {
                            expandedFacts =
                                controller.factStore().facts(person.senderId)
                        }
                    }
                },
                onRename = { renameTarget = person },
                onDeleteFact = { fact ->
                    scope.launch {
                        controller.factStore().delete(fact.id)
                        expandedFacts = controller.factStore().facts(person.senderId)
                        reload()
                    }
                },
                onForgetAll = {
                    scope.launch {
                        controller.factStore().deleteAll(person.senderId)
                        controller.identityStore().forget(person.senderId)
                        expandedId = null
                        reload()
                    }
                },
            )
        }

        Spacer(Modifier.height(32.dp))
    }

    renameTarget?.let { target ->
        RenameDialog(
            current = target.displayName.orEmpty(),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                scope.launch {
                    controller
                        .identityStore()
                        .remember(target.senderId, name, NameSource.MANUAL, System.currentTimeMillis())
                    renameTarget = null
                    reload()
                }
            },
        )
    }
}

@Composable
private fun MemoryConfigCard(
    config: MemoryConfig,
    onChange: (MemoryConfig) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("记忆系统", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)

            SwitchRow(
                title = "启用记忆",
                desc = "关闭后退化为纯对话机器人：不认人、不记事",
                checked = config.enabled,
                onCheckedChange = { onChange(config.copy(enabled = it)) },
            )

            SwitchRow(
                title = "学习自称名",
                desc = "用户说「叫我XXX」时记住，群里就能认人",
                checked = config.learnNames,
                onCheckedChange = { onChange(config.copy(learnNames = it)) },
                enabled = config.enabled,
            )

            SwitchRow(
                title = "提取事实",
                desc = "从「我喜欢/在备考/住在…」这类陈述里提取长期记忆",
                checked = config.learnFacts,
                onCheckedChange = { onChange(config.copy(learnFacts = it)) },
                enabled = config.enabled,
            )

            SwitchRow(
                title = "共享群聊背景",
                desc = "记住群里最近的对话，让「那他呢」这类承接能听懂",
                checked = config.roomContextEnabled,
                onCheckedChange = { onChange(config.copy(roomContextEnabled = it)) },
                enabled = config.enabled,
            )

            SwitchRow(
                title = "指令回复人设化",
                desc = "「我叫什么 / 我的记忆 / 叫你X / 群里都有谁」等指令的结果" +
                    "交给 AI 按人设语气说出，不再用固定句式；关闭后走固定文案，不消耗 API",
                checked = config.personaRewrite,
                onCheckedChange = { onChange(config.copy(personaRewrite = it)) },
                enabled = config.enabled,
            )

            Text(
                "每人最多保留 ${config.factsPerPerson} 条事实（超出自动淘汰最旧的）",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = config.factsPerPerson.toFloat(),
                onValueChange = { onChange(config.copy(factsPerPerson = it.toInt().coerceIn(5, 100))) },
                valueRange = 5f..100f,
                enabled = config.enabled,
            )

            Text(
                "群聊背景保留 ${config.roomContextSize} 条",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = config.roomContextSize.toFloat(),
                onValueChange = {
                    onChange(config.copy(roomContextSize = it.toInt().coerceIn(5, 50)))
                },
                valueRange = 5f..50f,
                enabled = config.enabled && config.roomContextEnabled,
            )
        }
    }
}

@Composable
private fun PersonCard(
    person: PersonMemory,
    expanded: Boolean,
    facts: List<UserFact>,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDeleteFact: (UserFact) -> Unit,
    onForgetAll: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).clickable { onToggle() }) {
                    Text(
                        text = person.displayName ?: "群友${person.senderId.takeLast(6)}",
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${person.factCount} 条记忆 · ${person.senderId.take(12)}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Default.Edit, contentDescription = "备注名")
                }
                IconButton(onClick = onForgetAll) {
                    Icon(Icons.Default.Delete, contentDescription = "忘掉此人全部记忆")
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (facts.isEmpty()) {
                    Text(
                        "还没有关于这个人的事实。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                facts.forEach { fact ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "· ${fact.text}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onDeleteFact(fact) }) { Text("删") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("备注名") },
        text = {
            Column {
                Text(
                    "手动备注的优先级高于用户自称，设置后ta再自我介绍也不会覆盖。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("展示名") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------- 人设 ----------

@Composable
private fun PersonaTab() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as BotApplication
    val controller = app.controller
    val repository = app.controller.personaRepository
    val scope = rememberCoroutineScope()

    val personas by repository.personas.collectAsState(initial = emptyList())
    val aiConfig by controller.aiConfig.collectAsState()
    var editing by remember { mutableStateOf<Persona?>(null) }
    var creating by remember { mutableStateOf(false) }
    var evolveTarget by remember { mutableStateOf<PersonaTarget?>(null) }
    var historyTarget by remember { mutableStateOf<PersonaTarget?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "人设优先级：对某个人 > 对某个群 > 全局",
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "同一级别有多条时，用最近更新的那条。全部删光则回退到「设置」页里的默认系统提示词。" +
                            "人设内容保持稳定不要频繁改动——前缀缓存命不上会变慢、变贵。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (personas.isEmpty()) {
                EmptyHint(
                    "还没有人设",
                    "点右下角 + 新建。可以给全局一个默认人设，再给某个群或某个人单独定制。",
                )
            }

            // 全局系统提示词：所有没单独人设的对话都用它。
            // 它不在这张 personas 表里，单独给一个入口，才能被 AI 优化与回滚。
            PersonaTargetCard(
                title = "全局系统提示词",
                subtitle = "所有没有单独人设的对话都用它",
                prompt = aiConfig.systemPrompt,
                onEvolve = {
                    evolveTarget =
                        PersonaTarget(id = null, name = "全局默认", prompt = aiConfig.systemPrompt)
                },
                onHistory = {
                    historyTarget =
                        PersonaTarget(id = null, name = "全局默认", prompt = aiConfig.systemPrompt)
                },
            )

            personas.forEach { persona ->
                PersonaCard(
                    persona = persona,
                    onEdit = { editing = persona },
                    onEvolve = {
                        evolveTarget =
                            PersonaTarget(persona.id, persona.name, persona.prompt)
                    },
                    onHistory = {
                        historyTarget =
                            PersonaTarget(persona.id, persona.name, persona.prompt)
                    },
                    onDelete = {
                        scope.launch {
                            repository.delete(persona.id)
                        }
                    },
                )
            }

            Spacer(Modifier.height(88.dp))
        }

        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "新建人设")
        }
    }

    if (creating || editing != null) {
        PersonaEditorDialog(
            initial = editing,
            recentPeople = { repository.recentPeople() },
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { persona ->
                scope.launch {
                    repository.save(persona)
                    creating = false
                    editing = null
                }
            },
        )
    }

    evolveTarget?.let { target ->
        PersonaEvolveDialog(
            target = target,
            onDismiss = { evolveTarget = null },
            onRun = { instruction -> controller.evolvePersona(target.id, instruction) },
        )
    }

    historyTarget?.let { target ->
        PersonaSnapshotDialog(
            target = target,
            onDismiss = { historyTarget = null },
            load = { controller.personaSnapshots(target.id) },
            onRestore = { snapshot ->
                scope.launch {
                    if (target.id != null) {
                        repository.all().firstOrNull { it.id == target.id }?.let {
                            repository.save(it.copy(prompt = snapshot.prompt))
                        }
                    } else {
                        app.settings.saveAiConfig(
                            controller.aiConfig.value.copy(systemPrompt = snapshot.prompt),
                        )
                    }
                    historyTarget = null
                }
            },
            onDelete = { id -> scope.launch { repository.deleteSnapshot(id) } },
        )
    }
}

/** 人设优化/历史的目标：id 为 null 表示全局系统提示词。 */
private data class PersonaTarget(
    val id: Long?,
    val name: String,
    val prompt: String,
)

/** 全局系统提示词卡片：只有优化与历史两个动作（正文在「AI 接入」里编辑）。 */
@Composable
private fun PersonaTargetCard(
    title: String,
    subtitle: String,
    prompt: String,
    onEvolve: () -> Unit,
    onHistory: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                prompt.take(120) + if (prompt.length > 120) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEvolve) { Text("AI 优化") }
                TextButton(onClick = onHistory) { Text("版本历史") }
            }
        }
    }
}

/**
 * AI 优化人设对话框。
 *
 * 初始版本由 [BotController.evolvePersona] 在第一次优化前自动存档，
 * 所以这里不重复做"保存"动作，只负责收集额外要求并展示结果。
 */
@Composable
private fun PersonaEvolveDialog(
    target: PersonaTarget,
    onDismiss: () -> Unit,
    onRun: suspend (String?) -> Result<String>,
) {
    var instruction by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("AI 优化人设") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("目标：${target.name}", style = MaterialTheme.typography.labelMedium)
                Text(
                    "AI 会参考最近的群聊，在不改变身份设定的前提下补全表达习惯与边界。" +
                        "初始版本会自动存档，随时可以回滚。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text("额外要求（可选）") },
                    placeholder = { Text("例如：更毒舌一点，但别骂人") },
                    minLines = 2,
                    enabled = !running,
                )
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running,
                onClick = {
                    scope.launch {
                        running = true
                        message = null
                        onRun(instruction.takeIf { it.isNotBlank() })
                            .onSuccess {
                                failed = false
                                message = "已更新，新版本已存档"
                            }
                            .onFailure {
                                failed = true
                                message = it.message ?: "优化失败"
                            }
                        running = false
                    }
                },
            ) {
                Text(if (running) "优化中…" else "开始优化")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) { Text("关闭") }
        },
    )
}

/** 版本历史：查看、回滚、删除非初始版本。 */
@Composable
private fun PersonaSnapshotDialog(
    target: PersonaTarget,
    onDismiss: () -> Unit,
    load: suspend () -> List<PersonaSnapshot>,
    onRestore: (PersonaSnapshot) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var items by remember { mutableStateOf<List<PersonaSnapshot>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(target.id) {
        items = load()
        loading = false
    }
    val format = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("版本历史 · ${target.name}") },
        text = {
            when {
                loading -> Text("读取中…")
                items.isEmpty() ->
                    Text(
                        "还没有版本记录。第一次用 AI 优化时会自动保存初始版本。",
                        style = MaterialTheme.typography.bodySmall,
                    )

                else ->
                    Column(
                        modifier =
                            Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items.forEach { snap ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            ) {
                                Column(
                                    Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            when (snap.kind) {
                                                PersonaSnapshotKind.INITIAL -> "初始版本"
                                                PersonaSnapshotKind.EVOLVED -> "AI 优化"
                                                PersonaSnapshotKind.MANUAL -> "手动保存"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            format.format(Date(snap.createdAt)),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    Text(snap.note, style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        snap.prompt.take(80) +
                                            if (snap.prompt.length > 80) "…" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row {
                                        TextButton(onClick = { onRestore(snap) }) {
                                            Text("回滚到此版")
                                        }
                                        if (snap.kind != PersonaSnapshotKind.INITIAL) {
                                            TextButton(
                                                onClick = {
                                                    onDelete(snap.id)
                                                    items = items.filterNot { it.id == snap.id }
                                                },
                                            ) {
                                                Text("删除")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun PersonaCard(
    persona: Persona,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onEvolve: () -> Unit = {},
    onHistory: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(persona.name, fontWeight = FontWeight.Medium)
                    Text(
                        text =
                        when {
                            !persona.senderId.isNullOrBlank() -> "仅对某人生效"
                            !persona.scopeId.isNullOrBlank() -> "仅对本群生效"
                            else -> "全局默认"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = persona.prompt.take(120) + if (persona.prompt.length > 120) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEvolve) { Text("AI 优化") }
                TextButton(onClick = onHistory) { Text("版本历史") }
            }
        }
    }
}

/** 人设编辑器：名称 + 提示词 + 生效范围（全局 / 群 / 人）。 */
@Composable
private fun PersonaEditorDialog(
    initial: Persona?,
    recentPeople: suspend () -> List<KnownPerson>,
    onDismiss: () -> Unit,
    onSave: (Persona) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(initial?.prompt.orEmpty()) }
    var scope by remember { mutableIntStateOf(0) } // 0 全局 1 群 2 人
    var scopeId by remember { mutableStateOf(initial?.scopeId.orEmpty()) }
    var senderId by remember { mutableStateOf(initial?.senderId.orEmpty()) }
    var people by remember { mutableStateOf<List<KnownPerson>>(emptyList()) }

    LaunchedEffect(Unit) {
        people = recentPeople()
    }

    // 依据初始值的绑定关系推断作用域选项
    LaunchedEffect(initial) {
        if (initial != null) {
            when {
                !initial.senderId.isNullOrBlank() -> {
                    scope = 2
                    senderId = initial.senderId.orEmpty()
                }

                !initial.scopeId.isNullOrBlank() -> {
                    scope = 1
                    scopeId = initial.scopeId.orEmpty()
                }

                else -> scope = 0
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建人设" else "编辑人设") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("如：默认助手 / 傲娇猫娘") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("人设提示词") },
                    placeholder = { Text("你是一个……，说话风格……，禁止……") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("生效范围", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = scope == 0,
                        onClick = { scope = 0 },
                        label = { Text("全局") },
                    )
                    FilterChip(
                        selected = scope == 1,
                        onClick = { scope = 1 },
                        label = { Text("按群") },
                    )
                    FilterChip(
                        selected = scope == 2,
                        onClick = { scope = 2 },
                        label = { Text("按人") },
                    )
                }

                when (scope) {
                    1 ->
                        OutlinedTextField(
                            value = scopeId,
                            onValueChange = { scopeId = it },
                            label = { Text("群 openid") },
                            placeholder = { Text("日志里「收到[GROUP_AT]」所在群的 openid") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                    2 -> {
                        if (people.isEmpty()) {
                            Text(
                                "还没有互动过的人。先让机器人在群里聊几句，这里就会出现可绑定的人。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        people.take(12).forEach { person ->
                            Row(
                                modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        senderId = person.senderId
                                        if (name.isBlank()) name = person.displayName
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = person.displayName,
                                    modifier = Modifier.weight(1f),
                                )
                                if (senderId == person.senderId) {
                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        Persona(
                            id = initial?.id ?: 0,
                            senderId = senderId.takeIf { scope == 2 }?.takeIf { it.isNotBlank() },
                            scopeId = scopeId.takeIf { scope == 1 }?.takeIf { it.isNotBlank() },
                            name = name.trim().ifBlank { "未命名人设" },
                            prompt = prompt.trim(),
                        ),
                    )
                },
                enabled = name.isNotBlank() && prompt.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------- 群感知 ----------

/** 群成员样本（openid + 解析出的展示名，可能为 null）。 */
private data class MemberSample(
    val memberId: String,
    val displayName: String?,
)

@Composable
private fun GroupTab() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as BotApplication
    val controller = app.controller
    val groupStore = controller.groupStore()
    val scope = rememberCoroutineScope()

    val greeting by controller.greetingConfig.collectAsState()
    val chitchat by controller.chitchatConfig.collectAsState()
    val gatewayStatus by controller.status.collectAsState()
    val summaries by groupStore.observeSummaries().collectAsState(initial = emptyList())

    var expandedGroup by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<MemberSample>>(emptyList()) }
    var renameTarget by remember { mutableStateOf<GroupSummary?>(null) }
    var messagesGroup by remember { mutableStateOf<GroupSummary?>(null) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GatewayEventsCard(
            state = gatewayStatus.state.name,
            counts = gatewayStatus.eventCounts,
            intents = gatewayStatus.intents,
        )

        ChitchatCard(
            config = chitchat,
            onChange = { app.settings.saveChitchatConfig(it) },
        )

        GreetingCard(
            config = greeting,
            onChange = { app.settings.saveGreetingConfig(it) },
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("群档案从哪来", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "QQ 官方不提供群成员名单接口，机器人进过哪些群、群里见过谁，" +
                        "全靠网关事件（加群/退群/新成员/@消息）一点点拼出来。" +
                        "在群里说「叫你XXX」可以改机器人的群名片，" +
                        "说「群里都有谁」它会凭这些档案回答。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (summaries.isEmpty()) {
            EmptyHint(
                "还没有群档案",
                "把机器人拉进任何一个群，它就会在这里建档；" +
                    "之后群里每@它一次、每进一个新成员，档案都会更新。",
            )
        }

        summaries.forEach { group ->
            GroupCard(
                group = group,
                expanded = expandedGroup == group.groupId,
                members = if (expandedGroup == group.groupId) members else emptyList(),
                onToggle = {
                    if (expandedGroup == group.groupId) {
                        expandedGroup = null
                    } else {
                        expandedGroup = group.groupId
                        scope.launch {
                            val ids = groupStore.members(group.groupId)
                            members =
                                ids.map { id ->
                                    MemberSample(
                                        memberId = id,
                                        displayName =
                                            runCatching {
                                                controller.identityStore().find(id)?.displayName
                                            }.getOrNull(),
                                    )
                                }
                        }
                    }
                },
                onRename = { renameTarget = group },
                onDelete = {
                    scope.launch { groupStore.delete(group.groupId) }
                },
                onShowMessages = { messagesGroup = group },
            )
        }

        Spacer(Modifier.height(32.dp))
    }

    messagesGroup?.let { target ->
        val messages by
            controller
                .observeGroupMessages(target.groupId, limit = 200)
                .collectAsState(initial = emptyList())
        GroupMessagesDialog(
            groupName = target.botName ?: "群 ${target.groupId.take(10)}…",
            messages = messages,
            onDismiss = { messagesGroup = null },
        )
    }

    renameTarget?.let { target ->
        GroupRenameDialog(
            current = target.botName.orEmpty(),
            groupId = target.groupId,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                scope.launch {
                    groupStore.rename(target.groupId, name.ifBlank { null })
                    renameTarget = null
                }
            },
        )
    }
}

/**
 * 接收诊断。
 *
 * 排障顺序：先看这里，再看「消息」列表。
 * @ 消息有、普通消息为 0，问题一定在平台侧的订阅/授权，跟接话策略无关。
 */
@Composable
private fun GatewayEventsCard(
    state: String,
    counts: Map<String, Long>,
    intents: Int,
) {
    val atCount = counts["GROUP_AT_MESSAGE_CREATE"] ?: 0L
    val fullCount = counts["GROUP_MESSAGE_CREATE"] ?: 0L

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("接收诊断", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "网关：$state · 订阅位：0x${Integer.toHexString(intents).uppercase()}",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "@我消息：$atCount 条 · 普通群消息：$fullCount 条",
                style = MaterialTheme.typography.labelMedium,
            )

            when {
                atCount > 0 && fullCount == 0L ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("只收到 @ 消息", fontWeight = FontWeight.Medium)
                            Text(
                                "说明「群消息」事件没有推过来——三处都要对上，缺一个都收不到：\n" +
                                    "1. 开放平台 → 开发设置 → 事件订阅与回调 → " +
                                    "接入方式必须选「WebSocket」（不是 Webhook；本 App 没有公网回调地址）；\n" +
                                    "2. 同一页的接收事件配置选「全部接收」；\n" +
                                    "3. QQ 群设置 → 群机器人 → 本机器人资料页 → " +
                                    "「机器人可获取的群聊消息范围」改成「获取群内全部消息」。\n" +
                                    "改完后停止再启动机器人，再让群里发几条不带 @ 的消息。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                atCount == 0L && fullCount == 0L ->
                    Text(
                        "还没收到任何群消息。先确认开放平台「事件订阅与回调」的接入方式是 " +
                            "WebSocket（选了 Webhook 且没填地址时，会导致什么都收不到），" +
                            "再确认机器人已进群并在群里 @ 它一次。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                else ->
                    Text(
                        "两类消息都在收到。普通消息会进「群聊参与」的三级漏斗。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }

            if (counts.isNotEmpty()) {
                Text(
                    "事件统计：" +
                        counts.entries
                            .sortedByDescending { it.value }
                            .take(4)
                            .joinToString("、") { "${it.key}×${it.value}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 群聊参与卡片。
 *
 * 门槛在群主那边：只有群主把机器人的「消息范围」改成获取群内全部消息，
 * 非 @ 消息才会推过来。所以卡片第一块是授权指引，而不是参数。
 *
 * 结构分两层：**基础区**（频率类参数，常开）+ **高级分区**（折叠）——
 * 打分明细、AI 接话规则、冒犯词表都属于「调过一次就不再碰」的参数，
 * 默认收起，避免这张卡本身变成第二个长滚动页。
 */
@Composable
private fun ChitchatCard(
    config: ChitchatConfig,
    onChange: (ChitchatConfig) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("群聊参与", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "授权后机器人能看见群里所有消息，并在合适的时候自己接话；" +
                    "没授权时它仍然只回复 @ 它的消息。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("群主怎么授权", fontWeight = FontWeight.Medium)
                    Text(
                        "QQ 群设置 → 群机器人 → 本机器人资料页 → 把「机器人可获取的群聊消息范围」" +
                            "改成「获取群内全部消息」。每个群要单独开一次，" +
                            "关掉时机器人会收到通知并自动停止接话。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SwitchRow(
                title = "允许自己接话",
                desc = "关闭后仍会把群消息记进记忆，但绝不主动发言",
                checked = config.enabled,
                onCheckedChange = { onChange(config.copy(enabled = it)) },
            )

            SwitchRow(
                title = "让 AI 决定要不要接话（推荐）",
                desc = "开启后每条消息都交给 AI 判断（规则只挡单字、@别人等明显噪音），" +
                    "最懂语境；每条多一次很小的模型调用（maxTokens=8）",
                checked = config.aiJudge,
                onCheckedChange = { onChange(config.copy(aiJudge = it)) },
                enabled = config.enabled,
            )

            // ---------- 基础区：频率与身份 ----------
            Text(
                "冷却 ${config.baseCooldownMillis / 1000} 秒（群里聊得热闹时自动放慢，冷场时加快；最低 1 秒）",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = (config.baseCooldownMillis / 1000).toFloat().coerceIn(1f, 300f),
                onValueChange = {
                    onChange(config.copy(baseCooldownMillis = it.toInt().coerceIn(1, 300) * 1000L))
                },
                valueRange = 1f..300f,
                enabled = config.enabled,
            )

            Text(
                if (config.maxPerHour <= 0) {
                    "每小时接话：无上限"
                } else {
                    "每小时最多接话 ${config.maxPerHour} 次"
                },
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = config.maxPerHour.toFloat().coerceIn(0f, 200f),
                onValueChange = { onChange(config.copy(maxPerHour = it.toInt().coerceIn(0, 200))) },
                valueRange = 0f..200f,
                enabled = config.enabled,
            )
            Text(
                "调到 0 表示不限制（只靠冷却控制节奏）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                if (config.maxPerDay <= 0) {
                    "每天接话：无上限"
                } else {
                    "每天最多接话 ${config.maxPerDay} 次"
                },
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = config.maxPerDay.toFloat().coerceIn(0f, 1000f),
                onValueChange = { onChange(config.copy(maxPerDay = it.toInt().coerceIn(0, 1000))) },
                valueRange = 0f..1000f,
                steps = 99,
                enabled = config.enabled,
            )

            SwitchRow(
                title = "被叫名字必答",
                desc = "有人叫机器人的名字（含群名片、短昵称）时一定接话，" +
                    "不受冷却限制（仍有 5 秒最小间隔防刷屏）",
                checked = config.answerOnName,
                onCheckedChange = { onChange(config.copy(answerOnName = it)) },
                enabled = config.enabled,
            )

            Text(
                "候选消息参与概率 ${(config.probability * 100).toInt()}%（仅关闭 AI 判断时生效）",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = (config.probability * 100).toFloat(),
                onValueChange = {
                    onChange(config.copy(probability = it.toInt().coerceIn(5, 100) / 100.0))
                },
                valueRange = 5f..100f,
                enabled = config.enabled,
            )

            // ---------- 高级分区 1：评分微调 ----------
            ExpandableSection(
                title = "评分微调",
                subtitle = "各情形加/扣分与过筛门槛。关闭 AI 判断时分数达门槛才接话；" +
                    "开启时只有负分消息会被拦（噪音过滤）",
            ) {
                val weights = config.scoreWeights
                Text(
                    "过筛分数门槛：${config.minScore}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = config.minScore.toFloat(),
                    onValueChange = { onChange(config.copy(minScore = it.toInt())) },
                    valueRange = 0f..40f,
                    steps = 39,
                    enabled = config.enabled,
                )
                ScoreSlider(
                    label = "长度适中（2~60 字）",
                    value = weights.lengthScore,
                    range = 0..20,
                    enabled = config.enabled,
                ) { onChange(config.copy(scoreWeights = weights.copy(lengthScore = it))) }
                ScoreSlider(
                    label = "疑问句（吗/呢/怎么/为什么…）",
                    value = weights.questionScore,
                    range = 0..40,
                    enabled = config.enabled,
                ) { onChange(config.copy(scoreWeights = weights.copy(questionScore = it))) }
                ScoreSlider(
                    label = "单字或表情消息",
                    value = weights.shortPenalty,
                    range = -40..0,
                    enabled = config.enabled,
                ) { onChange(config.copy(scoreWeights = weights.copy(shortPenalty = it))) }
                ScoreSlider(
                    label = "@ 了别人（两人对话不该插嘴）",
                    value = weights.mentionPenalty,
                    range = -40..0,
                    enabled = config.enabled,
                ) { onChange(config.copy(scoreWeights = weights.copy(mentionPenalty = it))) }
                ScoreSlider(
                    label = "话题延续（与最近消息聊的一样）",
                    value = weights.topicScore,
                    range = 0..30,
                    enabled = config.enabled,
                ) { onChange(config.copy(scoreWeights = weights.copy(topicScore = it))) }
                ScoreSlider(
                    label = "有人在回应机器人（上一条是它说的）",
                    value = weights.respondBotScore,
                    range = 0..30,
                    enabled = config.enabled,
                ) { onChange(config.copy(scoreWeights = weights.copy(respondBotScore = it))) }
                ScoreSlider(
                    label = "冷场（群里半天没人说话）",
                    value = weights.quietScore,
                    range = 0..20,
                    enabled = config.enabled,
                ) { onChange(config.copy(scoreWeights = weights.copy(quietScore = it))) }
                ScoreSlider(
                    label = "同一人连续刷屏",
                    value = weights.spamPenalty,
                    range = -30..0,
                    enabled = config.enabled,
                ) { onChange(config.copy(scoreWeights = weights.copy(spamPenalty = it))) }

                TextButton(
                    onClick = {
                        onChange(
                            config.copy(
                                minScore = ChitchatConfig().minScore,
                                scoreWeights = ScoreWeights(),
                            ),
                        )
                    },
                ) {
                    Text("恢复默认分值与门槛")
                }
                Text(
                    "拿不准就先只动「过筛分数门槛」：调高变高冷，调低变话痨。" +
                        "人设里的「话痨/高冷」关键词也会自动微调它（±10）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------- 高级分区 2：AI 接话规则 ----------
            ExpandableSection(
                title = "AI 接话规则",
                subtitle = "用自己的话约定什么场合该接话，优先级高于通用规则",
            ) {
                TextListEditor(
                    items = config.chimeRules,
                    emptyHint = "还没有自定义规则，AI 按通用规则判断。",
                    placeholder = "例如：群里讨论工作时不要插话",
                    addLabel = "添加规则",
                    maxItems = 10,
                    maxLength = 100,
                    enabled = config.enabled,
                    onRemove = { rule ->
                        onChange(config.copy(chimeRules = config.chimeRules - rule))
                    },
                    onAdd = { rule ->
                        onChange(config.copy(chimeRules = config.chimeRules + rule))
                    },
                )
                Text(
                    "规则会注入 AI 的接话判断提示词，例如：" +
                        "「只有被叫到名字或有人求助才接话」「凌晨不要接话」。" +
                        "已添加 ${config.chimeRules.size}/10 条。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------- 高级分区 3：冒犯词表 ----------
            ExpandableSection(
                title = "冒犯词表",
                subtitle = "被叫名字时带这些词 = 被冒犯，机器人会按人设反应",
            ) {
                TextListEditor(
                    items = config.insultExtraWords,
                    emptyHint = "只使用内置词表（傻逼/智障/废物等常见词）。",
                    placeholder = "例如：菜狗（你们群里的黑话）",
                    addLabel = "添加词语",
                    maxItems = 50,
                    maxLength = 10,
                    enabled = config.enabled,
                    onRemove = { word ->
                        onChange(config.copy(insultExtraWords = config.insultExtraWords - word))
                    },
                    onAdd = { word ->
                        onChange(config.copy(insultExtraWords = config.insultExtraWords + word))
                    },
                )
                Text(
                    "语言的黑话与谐音层出不穷，内置表不可能穷尽。" +
                        "把你们群里实际出现的词加进来，「叫名字 + 这些词」才会触发按人设的反应。" +
                        "已添加 ${config.insultExtraWords.size}/50 个。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 带正负号显示的打分滑杆。
 *
 * 负分项（单字、@别人、刷屏）的范围是负数区间，显示时带符号，
 * 让「扣多少」和「加多少」在视觉上对称。
 */
@Composable
private fun ScoreSlider(
    label: String,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    val sign = if (value > 0) "+" else ""
    Text("$label：$sign$value 分", style = MaterialTheme.typography.labelMedium)
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
        enabled = enabled,
    )
}

/**
 * 自然语言列表编辑器（AI 接话规则 / 冒犯词表共用）。
 *
 * 配置驱动而不是两份复制粘贴的代码：上限、字数、按钮文案都是参数。
 */
@Composable
private fun TextListEditor(
    items: List<String>,
    emptyHint: String,
    placeholder: String,
    addLabel: String,
    maxItems: Int,
    maxLength: Int,
    enabled: Boolean,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }

    if (items.isEmpty()) {
        Text(
            emptyHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    items.forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("· $item", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { onRemove(item) }) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }

    if (items.size < maxItems) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { if (it.length <= maxLength) input = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        onAdd(text.take(maxLength))
                        input = ""
                    }
                },
                enabled = enabled && input.isNotBlank(),
            ) {
                Text(addLabel)
            }
        }
    } else {
        Text(
            "已达上限 $maxItems 条，先删掉一些才能添加。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun GreetingCard(
    config: GreetingConfig,
    onChange: (GreetingConfig) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("加群问候语", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)

            SwitchRow(
                title = "被拉进群时打招呼",
                desc = "开启后进群会主动发一句自我介绍（属于主动消息，消耗每日配额）",
                checked = config.enabled,
                onCheckedChange = { onChange(config.copy(enabled = it)) },
            )

            OutlinedTextField(
                value = config.text,
                onValueChange = { onChange(config.copy(text = it)) },
                label = { Text("问候语正文") },
                minLines = 2,
                enabled = config.enabled,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "留空等同于关闭。问候语只在新群第一次进群时发；" +
                    "群管理员关闭主动消息后（GROUP_MSG_REJECT）不再发送。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 群消息查看器。
 *
 * 展示的就是机器人"听到"的内容（同一条群背景数据），
 * 用来确认「接收所有消息」是否真的生效，以及它到底看到了哪些话。
 */
@Composable
private fun GroupMessagesDialog(
    groupName: String,
    messages: List<RoomMessage>,
    onDismiss: () -> Unit,
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.CHINA) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("群消息 · $groupName") },
        text = {
            if (messages.isEmpty()) {
                Text(
                    "还没有收到这个群的消息。\n\n" +
                        "1. 确认群主在 QQ 群设置 → 群机器人 → 本机器人资料页，" +
                        "把「机器人可获取的群聊消息范围」改成「获取群内全部消息」；\n" +
                        "2. 让群里发几条不带 @ 的消息；\n" +
                        "3. 回到这里下拉刷新（或重开本页）。\n\n" +
                        "没有授权时，平台只推送 @ 机器人的消息，这里就只会出现那些。\n" +
                        "另外「记忆 → 共享群聊背景」需要保持开启，否则消息不会入库。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Column(
                    modifier =
                        Modifier
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "已记录 ${messages.size} 条（@我 ${messages.count { it.role == "user" && it.isAt }} 条 · " +
                            "普通 ${messages.count { it.role == "user" && !it.isAt }} 条，最新的在最后）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    messages.forEach { message ->
                        val fromBot = message.role == "assistant"
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!fromBot) {
                                    Text(
                                        text = if (message.isAt) "＠我" else "普通",
                                        style = MaterialTheme.typography.labelSmall,
                                        color =
                                            if (message.isAt) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    text =
                                        if (fromBot) {
                                            "机器人"
                                        } else {
                                            message.senderName?.takeIf { it.isNotBlank() }
                                                ?: "群友${message.senderId?.takeLast(4).orEmpty()}"
                                        },
                                    style = MaterialTheme.typography.labelSmall,
                                    color =
                                        if (fromBot) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    timeFormat.format(Date(message.timestampMillis)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                message.content,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            message.decision?.takeIf { it.isNotBlank() }?.let { decision ->
                                Text(
                                    "→ $decision",
                                    style = MaterialTheme.typography.labelSmall,
                                    color =
                                        if (decision.startsWith("已接话")) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun GroupCard(
    group: GroupSummary,
    expanded: Boolean,
    members: List<MemberSample>,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShowMessages: () -> Unit = {},
) {
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).clickable { onToggle() }) {
                    Text(
                        text = group.botName ?: "群 ${group.groupId.take(10)}…",
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "见过 ${group.seenCount} 人 · 最近活跃 ${timeFormat.format(Date(group.lastSeenAt))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = buildString {
                            append(if (group.active) "在群中" else "已被移出")
                            append(" · 主动消息")
                            append(if (group.proactiveAllowed) "允许" else "已关闭")
                            append(" · ")
                            append(group.groupId.take(16))
                            append("…")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (group.active) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                    )
                }
                TextButton(onClick = onShowMessages) { Text("消息") }
                IconButton(onClick = onRename) {
                    Icon(Icons.Default.Edit, contentDescription = "改群名片")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除此群档案")
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (members.isEmpty()) {
                    Text(
                        "这个群还没有互动记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                members.forEach { member ->
                    Text(
                        text =
                            "· " +
                                (member.displayName ?: "群友${member.memberId.takeLast(6)}") +
                                "  ${member.memberId.take(10)}…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "成员按最近出现排序，每群最多保留 500 个样本（超出淘汰最久的）。" +
                        "没报过名字的人只显示 openid 尾段。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GroupRenameDialog(
    current: String,
    groupId: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("群名片") },
        text = {
            Column {
                Text(
                    "设置后，机器人在这个群里被问到名字时用这个名字回答，" +
                        "AI 也会被告知大家怎么称呼它。留空则用官方名字。" +
                        "在群里直接说「叫你XXX」也能改。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("群名片（最多 12 字）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "群 openid：${groupId.take(20)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------- 共用小组件 ----------

@Composable
private fun StatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyHint(
    title: String,
    desc: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    enabled: Boolean = true,
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
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
