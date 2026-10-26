package com.qbot.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.qbot.app.BotApplication
import com.qbot.core.ai.AutonomyMode
import com.qbot.core.ai.DriveSensitivity
import com.qbot.core.ai.EmbeddingConfig
import com.qbot.core.ai.MemoryConfig
import kotlinx.coroutines.launch

@Composable
fun MemoryPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val config by app.controller.memoryConfig.collectAsState()
    val autonomy by app.controller.autonomyConfig.collectAsState()

    var peopleCount by remember { mutableStateOf(0) }
    var factCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        runCatching {
            peopleCount = app.database.identityDao().count()
            factCount = app.database.factDao().count()
        }
    }

    val update: (MemoryConfig) -> Unit = { app.settings.saveMemoryConfig(it) }

    SettingsPageScaffold(
        title = "记忆系统",
        subtitle = "机器人会记住每个人是谁、说过什么，并按人隔离会话",
        onBack = onBack,
    ) {
        SettingsCard {
            SwitchRow(
                title = "启用记忆",
                desc = "关闭后退化为纯对话机器人：不认人、不记事，已存数据保留",
                checked = config.enabled,
                onCheckedChange = { update(config.copy(enabled = it)) },
            )
            SwitchRow(
                title = "学习自称名",
                desc = "用户说「叫我XXX」时记住，群里就能认人",
                checked = config.learnNames,
                onCheckedChange = { update(config.copy(learnNames = it)) },
                enabled = config.enabled,
            )
            SwitchRow(
                title = "提取事实",
                desc = "从「我喜欢 / 在备考 / 住在…」这类陈述里提取长期记忆",
                checked = config.learnFacts,
                onCheckedChange = { update(config.copy(learnFacts = it)) },
                enabled = config.enabled,
            )
            SwitchRow(
                title = "按人记对话",
                desc = "每个人都有一条独立往返记录（含机器人自己的回复），回复时只看当前这个人的，" +
                    "不会把甲说过的话算到乙头上",
                checked = config.personalThreadEnabled,
                onCheckedChange = { update(config.copy(personalThreadEnabled = it)) },
                enabled = config.enabled,
            )
            SwitchRow(
                title = "共享群聊背景",
                desc = "记住群里最近的对话，让「那他呢」这类承接能听懂",
                checked = config.roomContextEnabled,
                onCheckedChange = { update(config.copy(roomContextEnabled = it)) },
                enabled = config.enabled,
            )
            SwitchRow(
                title = "指令回复人设化",
                desc = "「我叫什么 / 我的记忆 / 叫你X / 群里都有谁」的结果交给 AI 按人设语气说出；" +
                    "关闭后走固定文案，不消耗 API",
                checked = config.personaRewrite,
                onCheckedChange = { update(config.copy(personaRewrite = it)) },
                enabled = config.enabled,
            )
            SwitchRow(
                title = "按人设分开记忆",
                desc = "开启后每个档案各自积累对话、接话经验、关系与内心戏；" +
                    "关闭则换人设只换语气，记忆仍是同一份",
                checked = config.personaScoped,
                onCheckedChange = { update(config.copy(personaScoped = it)) },
                enabled = config.enabled,
            )
            SwitchRow(
                title = "记忆检索",
                desc = "按当前这句话去索引里找相关的旧记忆，而不是只按时间取最近几条；" +
                    "关闭后不再建索引（省 IO），行为回到旧版",
                checked = config.searchEnabled,
                onCheckedChange = { update(config.copy(searchEnabled = it)) },
                enabled = config.enabled,
            )
            SwitchRow(
                title = "高级参数",
                desc = "调整检索条数、时间衰减，以及是否让第二个模型参与精排",
                checked = config.advancedVisible,
                onCheckedChange = { update(config.copy(advancedVisible = it)) },
            )
            if (config.advancedVisible) {
                var searchTopK by remember(config.searchTopK) {
                    mutableFloatStateOf(config.searchTopK.toFloat())
                }
                Text(
                    "每次最多注入 ${searchTopK.toInt()} 条命中记忆",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = searchTopK,
                    onValueChange = { searchTopK = it },
                    valueRange = 2f..20f,
                    steps = 17,
                    enabled = config.enabled && config.searchEnabled,
                    onValueChangeFinished = {
                        update(config.copy(searchTopK = searchTopK.toInt().coerceIn(2, 20)))
                    },
                )

                var halfLife by remember(config.searchHalfLifeDays) {
                    mutableFloatStateOf(config.searchHalfLifeDays.toFloat())
                }
                Text(
                    "时间衰减半衰期 ${halfLife.toInt()} 天（越旧得分越低）",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = halfLife,
                    onValueChange = { halfLife = it },
                    valueRange = 3f..60f,
                    steps = 56,
                    enabled = config.enabled && config.searchEnabled,
                    onValueChangeFinished = {
                        update(
                            config.copy(
                                searchHalfLifeDays = halfLife.toInt().coerceIn(3, 60),
                            ),
                        )
                    },
                )

                SwitchRow(
                    title = "辅助模型精排",
                    desc = "把候选记忆交给第二个模型挑相关度；地址留空则沿用主模型",
                    checked = config.auxRankEnabled,
                    onCheckedChange = { update(config.copy(auxRankEnabled = it)) },
                    enabled = config.enabled && config.searchEnabled,
                )

                var rankCandidates by remember(config.auxRankCandidates) {
                    mutableFloatStateOf(config.auxRankCandidates.toFloat())
                }
                Text(
                    "精排前候选 ${rankCandidates.toInt()} 条（候选少于 4 条不调用）",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = rankCandidates,
                    onValueChange = { rankCandidates = it },
                    valueRange = 6f..60f,
                    steps = 53,
                    enabled = config.enabled && config.searchEnabled && config.auxRankEnabled,
                    onValueChangeFinished = {
                        update(
                            config.copy(
                                auxRankCandidates = rankCandidates.toInt().coerceIn(6, 60),
                            ),
                        )
                    },
                )
                HintText(
                    "辅助模型的地址 / 模型 / Key 在「AI → 模型」页设置；不填则用主模型，限流共享。",
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }

        SectionHeader("容量", "记太多会稀释相关性，记太少又不够用")
        SettingsCard {
            var factsPerPerson by remember(config.factsPerPerson) {
                mutableFloatStateOf(config.factsPerPerson.toFloat())
            }
            Text(
                "每人最多保留 ${factsPerPerson.toInt()} 条事实（超出自动淘汰最旧的）",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = factsPerPerson,
                onValueChange = { factsPerPerson = it },
                valueRange = 5f..100f,
                enabled = config.enabled,
                onValueChangeFinished = {
                    update(config.copy(factsPerPerson = factsPerPerson.toInt().coerceIn(5, 100)))
                },
            )

            var roomContextSize by remember(config.roomContextSize) {
                mutableFloatStateOf(config.roomContextSize.toFloat())
            }
            Text(
                "群聊背景保留 ${roomContextSize.toInt()} 条（跨人指代的上下文窗口）",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = roomContextSize,
                onValueChange = { roomContextSize = it },
                valueRange = 5f..50f,
                enabled = config.enabled && config.roomContextEnabled,
                onValueChangeFinished = {
                    update(
                        config.copy(roomContextSize = roomContextSize.toInt().coerceIn(5, 50)),
                    )
                },
            )

            var personalThreadSize by remember(config.personalThreadSize) {
                mutableFloatStateOf(config.personalThreadSize.toFloat())
            }
            Text(
                "每人的私人对话注入 ${personalThreadSize.toInt()} 条（机器人自己的回复也算在内）",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = personalThreadSize,
                onValueChange = { personalThreadSize = it },
                valueRange = 4f..30f,
                enabled = config.enabled && config.personalThreadEnabled,
                onValueChangeFinished = {
                    update(
                        config.copy(
                            personalThreadSize = personalThreadSize.toInt().coerceIn(4, 30),
                        ),
                    )
                },
            )
        }

        SectionHeader("自主开口", "由情绪、关系和话题决定它要不要插一句，而不只看有没有人叫它")
        SettingsCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutonomyMode.entries.forEach { mode ->
                    FilterChip(
                        selected = autonomy.mode == mode,
                        onClick = { app.settings.saveAutonomyConfig(autonomy.copy(mode = mode)) },
                        label = { Text(mode.label()) },
                    )
                }
            }
            HintText(
                when (autonomy.mode) {
                    AutonomyMode.OFF ->
                        "只在被 @ / 叫名字 / 明确对话时回复，连轻度插话也停止。"
                    AutonomyMode.PASSIVE ->
                        "被叫必回 + 群里有明确话题时轻度插话。这是默认档，与升级前的行为一致。"
                    AutonomyMode.ACTIVE ->
                        "在被动参与之上，会自己在群里偶尔接一句；仍走引用回复，不消耗主动消息额度。"
                },
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
            // 两个开关分属不同页、又都是 per-bot：不提示的话很容易只配一边，
            // 结果「场景演着、自主还在插话」或者「只设了一个 bot，双人演出起不来」。
            HintText(
                "自主开口与场景档位都按机器人独立设置：场景进行中、以及刚结束 5 分钟内，" +
                    "自主会主动让位。场景档位在「接话复盘」页设置，双人演出需要两个机器人分别设为 ACTIVE。",
                modifier = Modifier.fillMaxWidth(),
            )
            if (autonomy.mode == AutonomyMode.ACTIVE) {
                var quiet by remember(autonomy.quietHours) { mutableStateOf(autonomy.quietHours) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = quiet,
                        onValueChange = { quiet = it },
                        label = { Text("安静时段（HH:mm-HH:mm，跨零点）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).padding(top = 8.dp),
                    )
                    TextButton(
                        onClick = {
                            app.settings.saveAutonomyConfig(autonomy.copy(quietHours = quiet.trim()))
                        },
                        enabled = quiet.trim() != autonomy.quietHours,
                    ) { Text("保存") }
                }
                HintText(
                    "此区间内只忍不说；留空表示不限。",
                    modifier = Modifier.fillMaxWidth(),
                )

                var perHour by remember(autonomy.maxSpeaksPerHour) {
                    mutableFloatStateOf(autonomy.maxSpeaksPerHour.toFloat())
                }
                Text(
                    "每群每小时最多开口 ${perHour.toInt()} 次（0 表示不限制）",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = perHour,
                    onValueChange = { perHour = it },
                    valueRange = 0f..6f,
                    steps = 5,
                    onValueChangeFinished = {
                        app.settings.saveAutonomyConfig(
                            autonomy.copy(maxSpeaksPerHour = perHour.toInt().coerceIn(0, 6)),
                        )
                    },
                )

                Text("开口阈值（越高越挑剔）", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DriveSensitivity.entries.forEach { sensitivity ->
                        FilterChip(
                            selected =
                                DriveSensitivity.fromThreshold(autonomy.threshold) == sensitivity,
                            onClick = {
                                app.settings.saveAutonomyConfig(
                                    autonomy.copy(threshold = sensitivity.threshold),
                                )
                            },
                            label = { Text(sensitivity.label()) },
                        )
                    }
                }
                HintText(
                    "阈值低更容易被话题勾住。每条自主决策的分数与理由在「AI → 接话」里可查，" +
                        "觉得太吵就调高、太闷就调低。",
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }

        SectionHeader("向量记忆", "可选：用 embedding 做语义召回；没配就退回关键词倒排，默认行为不变")
        VectorMemoryCard(app)

        SectionHeader("存量", "查看与删除具体记忆在「AI → 记忆」")
        SettingsCard {
            Text(
                "已认识 $peopleCount 人 · 记住 $factCount 条事实",
                style = MaterialTheme.typography.bodyMedium,
            )
            HintText(
                "群里对机器人说「叫我 XX」即可改名；说「我的记忆」查看、「忘掉 XX」删除。" +
                    "每人的完整事实列表在 AI Tab 里可以逐条处理。",
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

/**
 * 向量记忆（可选层）。
 *
 * 未开启且用户没有主动展开时整块折叠，只留一行说明——
 * 绝大多数用户不需要配 embedding，不能让它看起来像必填项。
 */
@Composable
private fun VectorMemoryCard(app: BotApplication) {
    val config by app.controller.embeddingConfig.collectAsState()
    val backfill by app.controller.vectorBackfill.state.collectAsState()
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf(false) }
    var provider by remember(config.provider) { mutableStateOf(config.provider) }
    var baseUrl by remember(config.baseUrl) {
        mutableStateOf(config.baseUrl.ifBlank { EmbeddingConfig.PRESETS[config.provider]?.baseUrl.orEmpty() })
    }
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var model by remember(config.model) {
        mutableStateOf(config.model.ifBlank { EmbeddingConfig.PRESETS[config.provider]?.model.orEmpty() })
    }
    var dim by remember(config.dim) { mutableStateOf(config.dim) }
    var busy by remember { mutableStateOf(false) }
    var tip by remember { mutableStateOf<String?>(null) }

    // 只在真正开着向量记忆时才刷进度：折叠状态下不做任何数据库开销
    LaunchedEffect(config.enabled) {
        if (config.enabled) runCatching { app.controller.vectorBackfill.refresh() }
    }

    fun draft() =
        config.copy(
            provider = provider,
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            model = model.trim(),
            dim = dim,
        )

    SettingsCard {
        SwitchRow(
            title = "启用向量记忆",
            desc = "配好服务商与 Key 并「测试连接」探测维度后才能开启；" +
                "关闭时检索完全走关键词倒排，不产生任何 embedding 调用",
            checked = config.enabled,
            onCheckedChange = { on ->
                if (on && dim <= 0) {
                    tip = "还没探测到维度：请先点「测试连接」，成功后才能开启"
                    editing = true
                } else {
                    app.settings.saveEmbeddingConfig(draft().copy(enabled = on))
                    tip = null
                }
            },
        )

        if (!config.enabled && !editing) {
            HintText("未开启。需要语义召回（「上次聊的那个游戏」这类模糊指代）时再配置即可。")
            TextButton(onClick = { editing = true }) { Text("配置向量服务") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EmbeddingConfig.PRESETS.forEach { (key, preset) ->
                    FilterChip(
                        selected = provider == key,
                        onClick = {
                            provider = key
                            if (key != "custom") {
                                baseUrl = preset.baseUrl
                                model = preset.model
                            }
                            // 换服务商后旧维度必然不可比，必须重新探测
                            dim = 0
                            tip = null
                        },
                        label = { Text(preset.label) },
                    )
                }
            }

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("接口地址（例如 https://api.siliconflow.cn/v1）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型（如 BAAI/bge-m3）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (dim > 0) "维度 $dim（由测试连接探测）" else "维度：未探测，点「测试连接」自动探测",
                style = MaterialTheme.typography.labelMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { app.settings.saveEmbeddingConfig(draft()) },
                    enabled = !busy,
                ) { Text("保存配置") }
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            tip = null
                            app.settings.saveEmbeddingConfig(draft())
                            app.controller.probeEmbedding(draft()).fold(
                                onSuccess = { detected ->
                                    dim = detected
                                    app.settings.saveEmbeddingConfig(draft().copy(dim = detected))
                                    tip = "连接成功：维度 $detected，可以开启向量记忆了"
                                },
                                onFailure = {
                                    dim = 0
                                    tip = "连接失败：${it.message ?: it::class.simpleName}"
                                },
                            )
                            busy = false
                        }
                    },
                    enabled = !busy && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank(),
                ) { Text(if (busy) "测试中…" else "测试连接") }
            }

            tip?.let { HintText(it, modifier = Modifier.fillMaxWidth()) }

            Text(
                "已向量化 ${backfill.done} / ${backfill.total} 条 · 占用 ${formatBytes(backfill.bytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        app.settings.saveEmbeddingConfig(draft())
                        app.controller.vectorBackfill.start(scope)
                    },
                    enabled = !backfill.running && config.enabled,
                ) { Text("开始向量化") }
                TextButton(
                    onClick = { app.controller.vectorBackfill.stop() },
                    enabled = backfill.running,
                ) { Text("停止") }
            }
            backfill.message?.let { HintText(it, modifier = Modifier.fillMaxWidth()) }
            HintText(
                "回填不自动跑：每批 20 条、间隔 1 秒，退出页面即停，下次从断点继续。" +
                    "向量只是加分项——接口失败会静默退回倒排，连续失败 3 次暂停 10 分钟。",
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes <= 0 -> "0MB"
        bytes < 1024 * 1024 -> "${"%.0f".format(bytes / 1024.0)}KB"
        else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)}MB"
    }

/** 档位在人话里的名字；与 `AiScreen` 里的按群覆盖保持同一套措辞。 */
private fun AutonomyMode.label(): String =
    when (this) {
        AutonomyMode.OFF -> "纯响应"
        AutonomyMode.PASSIVE -> "被动参与"
        AutonomyMode.ACTIVE -> "自主"
    }

private fun DriveSensitivity.label(): String =
    when (this) {
        DriveSensitivity.LOW -> "挑剔"
        DriveSensitivity.MEDIUM -> "标准"
        DriveSensitivity.HIGH -> "活跃"
    }
