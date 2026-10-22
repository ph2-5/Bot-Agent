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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
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
import com.qbot.core.ai.KnownPerson
import com.qbot.core.ai.MemoryConfig
import com.qbot.core.ai.NameSource
import com.qbot.core.ai.Persona
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
    val repository = app.controller.personaRepository
    val scope = rememberCoroutineScope()

    val personas by repository.personas.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Persona?>(null) }
    var creating by remember { mutableStateOf(false) }

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

            personas.forEach { persona ->
                PersonaCard(
                    persona = persona,
                    onEdit = { editing = persona },
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
}

@Composable
private fun PersonaCard(
    persona: Persona,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
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
    val summaries by groupStore.observeSummaries().collectAsState(initial = emptyList())

    var expandedGroup by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<MemberSample>>(emptyList()) }
    var renameTarget by remember { mutableStateOf<GroupSummary?>(null) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
            )
        }

        Spacer(Modifier.height(32.dp))
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

@Composable
private fun GroupCard(
    group: GroupSummary,
    expanded: Boolean,
    members: List<MemberSample>,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
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
