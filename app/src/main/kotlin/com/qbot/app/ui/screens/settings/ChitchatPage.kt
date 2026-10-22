package com.qbot.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.app.data.repo.GreetingConfig
import com.qbot.core.ai.ChitchatConfig
import com.qbot.core.ai.ScoreWeights
import kotlin.math.roundToInt

/**
 * 插话与应答：机器人自己决定什么时候说话的参数都在这里。
 *
 * 三级漏斗（频率 → 打分 → AI 判断）的参数按同样顺序排列，
 * 调参时能顺着链路想；所有滑杆都在松手后才落库，避免拖动过程中反复写盘。
 */
@Composable
fun ChitchatPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val config by app.controller.chitchatConfig.collectAsState()
    val greeting by app.controller.greetingConfig.collectAsState()

    val update: (ChitchatConfig) -> Unit = { app.settings.saveChitchatConfig(it) }
    val updateGreeting: (GreetingConfig) -> Unit = { app.settings.saveGreetingConfig(it) }

    SettingsPageScaffold(
        title = "插话与应答",
        subtitle = "授权后机器人能看见群里所有消息，并在合适的时候自己接话",
        onBack = onBack,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("群主怎么授权", fontWeight = FontWeight.Medium)
                HintText(
                    "QQ 群设置 → 群机器人 → 本机器人资料页 → 把「机器人可获取的群聊消息范围」" +
                        "改成「获取群内全部消息」。每个群要单独开一次，" +
                        "关掉时机器人会收到通知并自动停止接话。",
                )
            }
        }

        SectionHeader("开关", "关掉后仍然只回复 @ 它的消息")
        SettingsCard {
            SwitchRow(
                title = "允许自己接话",
                desc = "关闭后仍会把群消息记进记忆，但绝不主动发言",
                checked = config.enabled,
                onCheckedChange = { update(config.copy(enabled = it)) },
            )
            SwitchRow(
                title = "让 AI 决定要不要接话（推荐）",
                desc = "每条消息都交给 AI 判断（规则只挡单字、@别人等明显噪音），最懂语境；"
                    + "每条多一次很小的模型调用",
                checked = config.aiJudge,
                onCheckedChange = { update(config.copy(aiJudge = it)) },
                enabled = config.enabled,
            )
        }

        SectionHeader("频率", "先挡住「说太多」，剩下才轮到内容判断")
        SettingsCard {
            var cooldown by remember(config.baseCooldownMillis) {
                mutableFloatStateOf((config.baseCooldownMillis / 1000).toFloat().coerceIn(1f, 300f))
            }
            Text(
                "冷却 ${cooldown.toInt()} 秒（群里聊得热闹时自动放慢，冷场时加快；最低 1 秒）",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = cooldown,
                onValueChange = { cooldown = it },
                valueRange = 1f..300f,
                enabled = config.enabled,
                onValueChangeFinished = {
                    update(config.copy(baseCooldownMillis = cooldown.toInt().coerceIn(1, 300) * 1000L))
                },
            )

            var hourly by remember(config.maxPerHour) {
                mutableFloatStateOf(config.maxPerHour.toFloat().coerceIn(0f, 200f))
            }
            Text(
                if (hourly.toInt() <= 0) "每小时接话：无上限" else "每小时最多接话 ${hourly.toInt()} 次",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = hourly,
                onValueChange = { hourly = it },
                valueRange = 0f..200f,
                enabled = config.enabled,
                onValueChangeFinished = {
                    update(config.copy(maxPerHour = hourly.toInt().coerceIn(0, 200)))
                },
            )

            var daily by remember(config.maxPerDay) {
                mutableFloatStateOf(config.maxPerDay.toFloat().coerceIn(0f, 1000f))
            }
            Text(
                if (daily.toInt() <= 0) "每天接话：无上限" else "每天最多接话 ${daily.toInt()} 次",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = daily,
                onValueChange = { daily = it },
                valueRange = 0f..1000f,
                steps = 99,
                enabled = config.enabled,
                onValueChangeFinished = {
                    update(config.copy(maxPerDay = daily.toInt().coerceIn(0, 1000)))
                },
            )
            HintText("调到 0 表示不限制（只靠冷却控制节奏）。")

            SwitchRow(
                title = "被叫名字必答",
                desc = "有人叫机器人的名字（含群名片、短昵称）时一定接话，不受冷却限制（仍有 5 秒最小间隔）",
                checked = config.answerOnName,
                onCheckedChange = { update(config.copy(answerOnName = it)) },
                enabled = config.enabled,
            )

            var probability by remember(config.probability) {
                mutableFloatStateOf((config.probability * 100).toFloat().coerceIn(5f, 100f))
            }
            Text(
                "候选消息参与概率 ${probability.toInt()}%（仅关闭 AI 判断时生效）",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = probability,
                onValueChange = { probability = it },
                valueRange = 5f..100f,
                enabled = config.enabled,
                onValueChangeFinished = {
                    update(config.copy(probability = probability.toInt().coerceIn(5, 100) / 100.0))
                },
            )
        }

        SectionHeader(
            "评分微调",
            "关闭 AI 判断时分数达门槛才接话；开启时只有负分消息会被拦（噪音过滤）",
        )
        SettingsCard {
            var minScore by remember(config.minScore) {
                mutableFloatStateOf(config.minScore.toFloat())
            }
            Text("过筛分数门槛：${minScore.toInt()}", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = minScore,
                onValueChange = { minScore = it },
                valueRange = 0f..40f,
                steps = 39,
                enabled = config.enabled,
                onValueChangeFinished = { update(config.copy(minScore = minScore.toInt())) },
            )

            val weights = config.scoreWeights
            ScoreSlider(
                label = "长度适中（2~60 字）",
                value = weights.lengthScore,
                range = 0..20,
                enabled = config.enabled,
            ) { update(config.copy(scoreWeights = weights.copy(lengthScore = it))) }
            ScoreSlider(
                label = "疑问句（吗/呢/怎么/为什么…）",
                value = weights.questionScore,
                range = 0..40,
                enabled = config.enabled,
            ) { update(config.copy(scoreWeights = weights.copy(questionScore = it))) }
            ScoreSlider(
                label = "单字或表情消息",
                value = weights.shortPenalty,
                range = -40..0,
                enabled = config.enabled,
            ) { update(config.copy(scoreWeights = weights.copy(shortPenalty = it))) }
            ScoreSlider(
                label = "@ 了别人（两人对话不该插嘴）",
                value = weights.mentionPenalty,
                range = -40..0,
                enabled = config.enabled,
            ) { update(config.copy(scoreWeights = weights.copy(mentionPenalty = it))) }
            ScoreSlider(
                label = "话题延续（与最近消息聊的一样）",
                value = weights.topicScore,
                range = 0..30,
                enabled = config.enabled,
            ) { update(config.copy(scoreWeights = weights.copy(topicScore = it))) }
            ScoreSlider(
                label = "有人在回应机器人（上一条是它说的）",
                value = weights.respondBotScore,
                range = 0..30,
                enabled = config.enabled,
            ) { update(config.copy(scoreWeights = weights.copy(respondBotScore = it))) }
            ScoreSlider(
                label = "冷场（群里半天没人说话）",
                value = weights.quietScore,
                range = 0..20,
                enabled = config.enabled,
            ) { update(config.copy(scoreWeights = weights.copy(quietScore = it))) }
            ScoreSlider(
                label = "同一人连续刷屏",
                value = weights.spamPenalty,
                range = -30..0,
                enabled = config.enabled,
            ) { update(config.copy(scoreWeights = weights.copy(spamPenalty = it))) }

            TextButton(
                onClick = {
                    update(
                        config.copy(minScore = ChitchatConfig().minScore, scoreWeights = ScoreWeights()),
                    )
                },
            ) {
                Text("恢复默认分值与门槛")
            }
            HintText(
                "拿不准就先只动「过筛分数门槛」：调高变高冷，调低变话痨。" +
                    "人设里的「话痨 / 高冷」关键词也会自动微调它（±10）。",
            )
        }

        SectionHeader("AI 接话规则", "用自己的话约定什么场合该接话，优先级最高")
        SettingsCard {
            TextListEditor(
                items = config.chimeRules,
                emptyHint = "还没有自定义规则，AI 按通用规则判断。",
                placeholder = "例如：群里讨论工作时不要插话",
                addLabel = "添加规则",
                maxItems = 10,
                maxLength = 100,
                enabled = config.enabled,
                onRemove = { rule -> update(config.copy(chimeRules = config.chimeRules - rule)) },
                onAdd = { rule -> update(config.copy(chimeRules = config.chimeRules + rule)) },
            )
            HintText(
                "规则会注入 AI 的接话判断提示词，例如「只有被叫到名字或有人求助才接话」" +
                    "「凌晨不要接话」。已添加 ${config.chimeRules.size}/10 条。",
            )
        }

        SectionHeader("冒犯词表", "被叫名字时带这些词 = 被冒犯，机器人会按人设反应")
        SettingsCard {
            TextListEditor(
                items = config.insultExtraWords,
                emptyHint = "只使用内置词表（傻逼/智障/废物等常见词）。",
                placeholder = "例如：菜狗（你们群里的黑话）",
                addLabel = "添加词语",
                maxItems = 50,
                maxLength = 10,
                enabled = config.enabled,
                onRemove = { word ->
                    update(config.copy(insultExtraWords = config.insultExtraWords - word))
                },
                onAdd = { word ->
                    update(config.copy(insultExtraWords = config.insultExtraWords + word))
                },
            )
            HintText(
                "语言的黑话与谐音层出不穷，内置表不可能穷尽。把群里实际出现的词加进来，" +
                    "「叫名字 + 这些词」才会触发反应。已添加 ${config.insultExtraWords.size}/50 个。",
            )
        }

        SectionHeader(
            "训练与经验",
            "你标错一次它就学一条；经验按场景归档，同类自动合并",
        )
        SettingsCard {
            var maxLessons by remember(config.maxLessons) {
                mutableFloatStateOf(config.maxLessons.toFloat())
            }
            Text("经验库容量 ${maxLessons.toInt()} 条", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = maxLessons,
                onValueChange = { maxLessons = (it / 10f).roundToInt() * 10f },
                valueRange = 20f..200f,
                steps = 17,
                onValueChangeFinished = {
                    update(config.copy(maxLessons = maxLessons.toInt().coerceIn(20, 200)))
                },
            )
            HintText("容量只决定能存多少条，不改变每次判断的提示词长度；超出时先淘汰支撑少、最久没用的。")

            var injected by remember(config.maxInjectedLessons) {
                mutableFloatStateOf(config.maxInjectedLessons.toFloat())
            }
            Text("每次判断参考 ${injected.toInt()} 条", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = injected,
                onValueChange = { injected = it },
                valueRange = 3f..12f,
                steps = 8,
                onValueChangeFinished = {
                    update(
                        config.copy(maxInjectedLessons = injected.toInt().coerceIn(3, 12)),
                    )
                },
            )
            HintText(
                "这一项才影响提示词成本与稳定性。同一类别最多占 " +
                    "${com.qbot.core.ai.ChimeLesson.MAX_PER_CATEGORY_INJECTED} 条，" +
                    "避免八条经验全是「别插话」。",
            )
        }

        SectionHeader("加群问候语", "被拉进新群时主动说一句")
        SettingsCard {
            SwitchRow(
                title = "被拉进群时打招呼",
                desc = "进群会主动发一句自我介绍（属于主动消息，消耗每日配额）",
                checked = greeting.enabled,
                onCheckedChange = { updateGreeting(greeting.copy(enabled = it)) },
            )
            OutlinedTextField(
                value = greeting.text,
                onValueChange = { updateGreeting(greeting.copy(text = it)) },
                label = { Text("问候语正文") },
                minLines = 2,
                enabled = greeting.enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            HintText(
                "留空等同于关闭。只在新群第一次进群时发；" +
                    "群管理员关闭主动消息后（GROUP_MSG_REJECT）不再发送。",
            )
        }
    }
}

/**
 * 带正负号显示的打分滑杆。
 *
 * 负分项（单字、@别人、刷屏）的范围是负数区间，显示时带符号，
 * 让「扣多少」和「加多少」在视觉上对称。松手才提交。
 */
@Composable
private fun ScoreSlider(
    label: String,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val current = local.toInt()
    val sign = if (current > 0) "+" else ""
    Text("$label：$sign$current 分", style = MaterialTheme.typography.labelMedium)
    Slider(
        value = local,
        onValueChange = { local = it },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
        enabled = enabled,
        onValueChangeFinished = { onCommit(local.toInt()) },
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
        HintText(emptyHint)
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
