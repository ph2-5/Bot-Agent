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
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.app.data.repo.GroupSummary
import com.qbot.app.data.repo.observeSummaries
import com.qbot.core.ai.AutonomyMode
import com.qbot.core.ai.KnownPerson
import com.qbot.core.ai.NameSource
import com.qbot.core.ai.Persona
import com.qbot.core.ai.PersonaGrowth
import com.qbot.core.ai.PersonaKind
import com.qbot.core.ai.PersonaSeed
import com.qbot.core.ai.PersonaSections
import com.qbot.core.ai.PersonaStage
import com.qbot.core.ai.PersonaSnapshot
import com.qbot.core.ai.PersonaSnapshotKind
import com.qbot.core.ai.RoomMessage
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
    val tabs = listOf("记忆", "人设", "群组", "接话")

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
            2 -> GroupTab()
            else -> ChimeReviewScreen()
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

        Text(
            "记忆参数（开关、容量）在「设置 → 记忆系统」",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val controller = app.controller
    val scope = rememberCoroutineScope()

    val active by controller.observeActivePersona().collectAsState(initial = null)
    val profiles by controller.observePersonaProfiles().collectAsState(initial = emptyList())
    val allPersonas by controller.personaRepository.personas.collectAsState(initial = emptyList())
    val overrides = allPersonas.filter { it.kind == PersonaKind.OVERRIDE }

    var creating by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<Persona?>(null) }
    var editingOverride by remember { mutableStateOf<Persona?>(null) }
    var evolveTarget by remember { mutableStateOf<PersonaTarget?>(null) }
    var historyTarget by remember { mutableStateOf<PersonaTarget?>(null) }
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var coverageOpen by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val current = active
            if (current == null) {
                EmptyHint(
                    "还没有人格",
                    "点右下角 + 建一个。不用写任何提示词——它会先带着一个随机性格，再从群聊里慢慢长成自己的样子。",
                )
            } else {
                ActivePersonaCard(
                    persona = current,
                    busy = busy,
                    onEdit = { editingProfile = current },
                    onHistory = {
                        historyTarget = PersonaTarget(current.id, current.name, current.prompt)
                    },
                    onGrow = {
                        scope.launch {
                            busy = true
                            val ok = controller.growPersonaNow()
                            busy = false
                            hint =
                                if (ok) {
                                    "已经长了一版，去「成长日记」看它这次改了什么"
                                } else {
                                    "这次没跑起来：可能 AI 未配置、已冻结，或模型觉得没什么可改的"
                                }
                        }
                    },
                    onFreeze = { frozen ->
                        scope.launch { controller.setPersonaFrozen(current.id, frozen) }
                    },
                    onReseed = {
                        scope.launch {
                            controller.reseedPersona(current.id)
                            hint = "已换一个性格种子，正文清空，等群聊重新破壳"
                        }
                    },
                    onHandOver = {
                        scope.launch {
                            controller.handOverPersonaToGrowth(current.id)
                            hint = "已交给它自己养：以后它会按群聊自己微调"
                        }
                    },
                    onTakeOver = {
                        scope.launch {
                            controller.takeOverPersona(current.id)
                            hint = "已转为手动：以后只按你写的来，它不会自己改"
                        }
                    },
                )
            }

            if (current != null && overrides.isNotEmpty()) {
                // 覆盖层永远优先：不提示的话，用户会以为"切换全局档案没生效"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                ) {
                    Text(
                        "注意：有 ${overrides.size} 条按人/按群覆盖。在它们指定的范围内，" +
                            "会优先使用覆盖人格，上面这份「${current.name}」不会生效。" +
                            "可在下方「按人或按群单独设定」里停用或修改。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            val others = profiles.filter { !it.active }
            if (others.isNotEmpty()) {
                Text(
                    "其他人格档案（未生效）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                others.forEach { profile ->
                    InactiveProfileRow(
                        persona = profile,
                        onActivate = {
                            scope.launch {
                                controller.activatePersona(profile.id)
                                hint = "已切换到「${profile.name}」，下一句话就是它的声音"
                            }
                        },
                        onDuplicate = {
                            scope.launch {
                                controller.createPersonaProfile("${profile.name} 副本")
                            }
                        },
                        onDelete = { scope.launch { controller.deletePersona(profile.id) } },
                    )
                }
            }

            // 覆盖层默认折叠：它是"局部例外"，不是主路径
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { coverageOpen = !coverageOpen },
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("按人或按群单独设定", fontWeight = FontWeight.Medium)
                            Text(
                                "覆盖优先于人格档案：只影响指定的某个人或某个群",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(if (coverageOpen) "收起" else "展开", style = MaterialTheme.typography.labelMedium)
                    }
                    if (coverageOpen) {
                        Spacer(Modifier.height(8.dp))
                        overrides.forEach { override ->
                            OverrideRow(
                                persona = override,
                                onEdit = { editingOverride = override },
                                onDelete = {
                                    scope.launch { controller.deletePersona(override.id) }
                                },
                            )
                        }
                        TextButton(
                            onClick = {
                                editingOverride =
                                    Persona(
                                        name = "",
                                        prompt = "",
                                        scopeId = "",
                                        kind = PersonaKind.OVERRIDE,
                                    )
                            },
                        ) {
                            Text("+ 新增一条覆盖")
                        }
                    }
                }
            }

            Spacer(Modifier.height(88.dp))
        }

        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "新建人格")
        }
    }

    if (creating) {
        PersonaCreateDialog(
            onDismiss = { creating = false },
            onConfirm = { name, seed, manualPrompt ->
                scope.launch {
                    if (manualPrompt != null) {
                        controller.createManualPersona(name, manualPrompt)
                        hint = "已创建手写人格「$name」：按你写的来，AI 不会改"
                    } else {
                        controller.createPersonaProfile(name, seed)
                        hint = "已创建「$name」，性格种子是「${seed.label}」。攒够消息它就破壳了。"
                    }
                    creating = false
                }
            },
        )
    }

    editingProfile?.let { persona ->
        PersonaBodyDialog(
            persona = persona,
            onDismiss = { editingProfile = null },
            onSave = { name, prompt ->
                scope.launch {
                    controller.personaRepository.save(persona.copy(name = name, prompt = prompt))
                    editingProfile = null
                    hint =
                        if (PersonaSections.parse(prompt) == null) {
                            "已保存为自由文本人格：和你以前写系统提示词一样，AI 不会改它"
                        } else {
                            "已保存。这版会成为它以后自动成长的新基准"
                        }
                }
            },
        )
    }

    editingOverride?.let { persona ->
        PersonaEditorDialog(
            initial = if (persona.id == 0L) null else persona,
            recentPeople = { controller.personaRepository.recentPeople() },
            onDismiss = { editingOverride = null },
            onSave = { saved ->
                scope.launch {
                    controller.personaRepository.save(saved)
                    editingOverride = null
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
                    controller.personaRepository.all().firstOrNull { it.id == target.id }?.let {
                        controller.personaRepository.save(it.copy(prompt = snapshot.prompt))
                    }
                    historyTarget = null
                    hint = "已回滚到那一版"
                }
            },
            onDelete = { id -> scope.launch { controller.personaRepository.deleteSnapshot(id) } },
        )
    }
}

/** 当前生效的人格档案。 */
@Composable
private fun ActivePersonaCard(
    persona: Persona,
    busy: Boolean,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onGrow: () -> Unit,
    onFreeze: (Boolean) -> Unit,
    onReseed: () -> Unit,
    onHandOver: () -> Unit,
    onTakeOver: () -> Unit,
) {
    // 三种形态：分段结构（自动成长）/ 自由文本（用户手写）/ 空（EMBRYO，显示骨架）
    val sections = PersonaSections.parse(persona.prompt)
    val freeform = persona.prompt.isNotBlank() && sections == null
    // 手写档案（MANUAL）不参与自动成长，也就不存在"种子"这回事。
    // 过去这里无条件显示「种子「XXX」」，用户会以为「我手写了怎么还有种子」——
    // 那是展示 bug，不是行为。只有 EMBRYO/GROWING/MATURE 才挂种子标签。
    val isManual = persona.stage == PersonaStage.MANUAL
    val skeletonPreview =
        if (persona.prompt.isBlank() && !isManual) {
            PersonaSections.parse(PersonaGrowth.embryoSkeleton(persona.seedId))
        } else {
            null
        }
    val seed = PersonaSeed.of(persona.seedId)

    Card(
        // 整张卡片可点：过去只有底部「编辑」按钮能点，用户点卡片没反应，
        // 会以为"手动设定"这个状态卡死了。
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(persona.name, fontWeight = FontWeight.Bold)
                    Text(
                        buildString {
                            append(stageLabel(persona.stage))
                            if (!isManual) {
                                append(" · 种子「").append(seed.label).append("」")
                            }
                            if (persona.growthCount > 0) {
                                append(" · 已成长 ").append(persona.growthCount).append(" 次")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (isManual && persona.prompt.isBlank()) {
                        Text(
                            "正文为空：当前使用中性占位提示词（不会套用种子性格）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = { onFreeze(!persona.frozen) }) {
                    Text(if (persona.frozen) "已冻结" else "冻结")
                }
            }

            if (persona.stage == PersonaStage.EMBRYO) {
                Text(
                    "还没破壳：现在用的是种子骨架，聊够消息它会自己写出第一版人格。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                sections != null -> PersonaSectionsView(sections)
                freeform -> {
                    // 用户手写的自由文本必须原样显示——过去解析失败会 fallback 到骨架，
                    // 等于把用户写的东西藏起来，看起来就像"人格是空的"。
                    Text(
                        "自由文本 · AI 不会自动改写",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        persona.prompt.take(900) +
                            if (persona.prompt.length > 900) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> skeletonPreview?.let { PersonaSectionsView(it) }
            }

            if (persona.lastNote.isNotBlank()) {
                Text(
                    "上次：${persona.lastNote}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onHistory) { Text("成长日记") }
                TextButton(onClick = onGrow, enabled = !busy) {
                    Text(if (busy) "成长中…" else "立即成长")
                }
                if (persona.stage == PersonaStage.EMBRYO) {
                    TextButton(onClick = onReseed, enabled = !busy) { Text("换种子") }
                }
                if (persona.stage == PersonaStage.MANUAL) {
                    TextButton(onClick = onHandOver, enabled = !busy) { Text("交给它自己养") }
                } else {
                    TextButton(onClick = onTakeOver, enabled = !busy) { Text("改为手动") }
                }
            }
        }
    }
}

/** 六段渲染：身份段加锁标，告诉用户这段改不了；老档案没有毛病段就不显示。 */
@Composable
private fun PersonaSectionsView(sections: PersonaSections) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLine("身份", sections.identity, locked = true)
        SectionLine("性格", sections.traits)
        if (sections.hasQuirks) SectionLine("毛病", sections.quirks)
        SectionLine("说话方式", sections.voice)
        SectionLine("兴趣", sections.interests)
        SectionLine("边界", sections.bounds)
    }
}

@Composable
private fun SectionLine(
    label: String,
    body: String,
    locked: Boolean = false,
) {
    Column {
        Text(
            if (locked) "$label 🔒" else label,
            style = MaterialTheme.typography.labelSmall,
            color = if (locked) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}

private fun stageLabel(stage: PersonaStage): String =
    when (stage) {
        PersonaStage.EMBRYO -> "破壳中"
        PersonaStage.GROWING -> "成长中"
        PersonaStage.MATURE -> "已成熟"
        PersonaStage.MANUAL -> "手动设定"
    }

/** 未生效的档案：可以切过去，也可以复制一份再改。 */
@Composable
private fun InactiveProfileRow(
    persona: Persona,
    onActivate: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(persona.name, fontWeight = FontWeight.Medium)
                    Text(
                        "${stageLabel(persona.stage)} · 未生效",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onActivate) { Text("切换") }
                TextButton(onClick = onDuplicate) { Text("复制") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

/** 覆盖层（按人/按群）的一行。 */
@Composable
private fun OverrideRow(
    persona: Persona,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(persona.name.ifBlank { "未命名" }, style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    !persona.senderId.isNullOrBlank() -> "仅对某人生效"
                    !persona.scopeId.isNullOrBlank() -> "仅对群 ${persona.scopeId} 生效"
                    else -> "未绑定"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onEdit) { Text("编辑") }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "删除")
        }
    }
}

/** 新建人格：名字 + 随机/指定性格种子。 */
@Composable
private fun PersonaCreateDialog(
    onDismiss: () -> Unit,
    /** 第三参数非空表示"自己写"：直接拿它当人格正文 */
    onConfirm: (String, PersonaSeed, String?) -> Unit,
) {
    var seed by remember { mutableStateOf(PersonaSeed.random()) }
    var name by remember { mutableStateOf(seed.label) }
    // false = 自动养成（选种子），true = 自己写（旧版系统提示词那种）
    var manual by remember { mutableStateOf(false) }
    var manualPrompt by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建人格") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !manual,
                        onClick = { manual = false },
                        label = { Text("让它自己长") },
                    )
                    FilterChip(
                        selected = manual,
                        onClick = { manual = true },
                        label = { Text("我自己写") },
                    )
                }
                Text(
                    if (manual) {
                        "你写的就是它的系统提示词，和旧版一样。AI 不会自动改，除非你点「交给它自己养」。"
                    } else {
                        "不用写提示词。先给它一个性格原点，剩下的从群聊里长。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (manual) {
                    OutlinedTextField(
                        value = manualPrompt,
                        onValueChange = { manualPrompt = it },
                        label = { Text("系统提示词") },
                        placeholder = { Text("你是一个说话简短、偶尔毒舌的群友……") },
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!manual) {
                    Text("性格种子", style = MaterialTheme.typography.labelMedium)
                    // 名字默认跟随种子；判断时必须拿"切换前"的 seed 比较，
                    // 否则先赋值再比较永远不成立，名字会和实际人格错位。
                    fun chooseSeed(option: PersonaSeed) {
                        if (name.isBlank() || name == seed.label) name = option.label
                        seed = option
                    }
                    PersonaSeed.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { chooseSeed(option) }
                                    .padding(vertical = 4.dp),
                        ) {
                            RadioButton(selected = seed == option, onClick = { chooseSeed(option) })
                            Text(option.label, modifier = Modifier.weight(1f))
                            Text(
                                option.traits.take(18) + "…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        name.trim().ifBlank { if (manual) "手写人格" else seed.label },
                        seed,
                        if (manual) manualPrompt.trim() else null,
                    )
                },
                enabled = !manual || manualPrompt.isNotBlank(),
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 编辑档案正文（不含绑定范围——档案永远是全局的）。 */
@Composable
private fun PersonaBodyDialog(
    persona: Persona,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(persona.name) }
    var prompt by remember { mutableStateOf(persona.prompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑人格") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("人格正文（等同旧版系统提示词）") },
                    supportingText = {
                        Text(
                            "想自己写就随便写，怎么顺怎么来——这版不会被 AI 改动。" +
                                "想让它自己接着长，再按下面提示的六段格式写，然后点「交给它自己养」。",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    minLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "六段格式：[身份] [性格] [毛病] [说话方式] [兴趣] [边界]。不按这个写也能用，" +
                        "只是自动成长需要它来比对改动幅度。" +
                        "「毛病」写它会被群友当场指出的具体行为（比如「被夸就顺杆爬」），" +
                        "别写「太认真」这种把优点说成缺点的。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim().ifBlank { persona.name }, prompt.trim()) },
                enabled = prompt.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 人设优化/历史的目标。id 一定是真实档案——全局提示词的概念已移除。 */
private data class PersonaTarget(
    val id: Long,
    val name: String,
    val prompt: String,
)

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
                                                PersonaSnapshotKind.BIRTH -> "自动破壳"
                                                PersonaSnapshotKind.GROWTH -> "自动成长"
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
    var scope by remember { mutableIntStateOf(0) } // 0 群 1 人（全局人格由档案承担）
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
                    scope = 1
                    senderId = initial.senderId.orEmpty()
                }

                !initial.scopeId.isNullOrBlank() -> {
                    scope = 0
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
                        label = { Text("按群") },
                    )
                    FilterChip(
                        selected = scope == 1,
                        onClick = { scope = 1 },
                        label = { Text("按人") },
                    )
                }
                Text(
                    "只想改整体性格？用「AI · 人设」页的人格档案，那里才是它自己。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                when (scope) {
                    0 ->
                        OutlinedTextField(
                            value = scopeId,
                            onValueChange = { scopeId = it },
                            label = { Text("群 openid") },
                            placeholder = { Text("日志里「收到[GROUP_AT]」所在群的 openid") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                    1 -> {
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
                            senderId = senderId.takeIf { scope == 1 }?.takeIf { it.isNotBlank() },
                            scopeId = scopeId.takeIf { scope == 0 }?.takeIf { it.isNotBlank() },
                            name = name.trim().ifBlank { "未命名人设" },
                            prompt = prompt.trim(),
                        ),
                    )
                },
                enabled =
                    name.isNotBlank() && prompt.isNotBlank() &&
                        (scopeId.isNotBlank() || senderId.isNotBlank()),
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

    val gatewayStatus by controller.status.collectAsState()
    val summaries by groupStore.observeSummaries().collectAsState(initial = emptyList())
    // 按群覆盖的自主档位：只存"单独设过"的群，没存过的回落全局
    val groupBehaviors by controller.observeGroupBehaviors().collectAsState(initial = emptyMap())
    val globalAutonomy by controller.autonomyConfig.collectAsState()

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
                // null = 跟随全局；其余为按群覆盖
                autonomyMode = groupBehaviors[group.groupId],
                globalAutonomyMode = globalAutonomy.mode,
                onAutonomyChange = { mode ->
                    scope.launch {
                        if (mode == null) {
                            controller.clearGroupAutonomy(group.groupId)
                        } else {
                            controller.setGroupAutonomy(group.groupId, mode.name)
                        }
                    }
                },
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
    autonomyMode: String? = null,
    globalAutonomyMode: AutonomyMode = AutonomyMode.PASSIVE,
    onAutonomyChange: (AutonomyMode?) -> Unit = {},
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

                // 此群行为：默认跟随全局，也可以单独压成静音或放开成自主
                val overrideMode = autonomyMode?.let { AutonomyMode.parse(it) }
                Text(
                    "此群开口档位（全局：${globalAutonomyMode.label()}）",
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = overrideMode == null,
                        onClick = { onAutonomyChange(null) },
                        label = { Text("跟随全局") },
                    )
                    AutonomyMode.entries.forEach { mode ->
                        FilterChip(
                            selected = overrideMode == mode,
                            onClick = { onAutonomyChange(mode) },
                            label = { Text(mode.label()) },
                        )
                    }
                }
                Text(
                    if (overrideMode == null) {
                        "没单独设过：改全局默认时这个群会跟着变。"
                    } else {
                        "已单独设为 ${overrideMode.label()}；其余群不受影响。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** 三档模式的中文名。 */
private fun AutonomyMode.label(): String =
    when (this) {
        AutonomyMode.OFF -> "纯响应"
        AutonomyMode.PASSIVE -> "被动参与"
        AutonomyMode.ACTIVE -> "自主"
    }
