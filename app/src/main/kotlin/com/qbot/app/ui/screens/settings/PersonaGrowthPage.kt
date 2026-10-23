package com.qbot.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.core.ai.PersonaStage
import kotlinx.coroutines.launch

/**
 * 人格成长。
 *
 * 这里**没有**"自动成长开关"——它和「冻结」是同一件事，只留一个。
 * 节奏也不需要用户调：有新消息就交给模型，它觉得没东西可学就回 NO_CHANGE。
 */
@Composable
fun PersonaGrowthPage(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as BotApplication
    val controller = remember { app.controller }
    val active by controller.observeActivePersona().collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    SettingsPageScaffold(
        title = "人格成长",
        subtitle = "它自己判断什么时候该长一点",
        onBack = onBack,
    ) {
        val persona = active
        SettingsCard {
            if (persona == null) {
                HintText("还没有人格档案。去「AI → 人设」点右下角的 + 建一个。")
            } else {
                Text(persona.name, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append(stageText(persona.stage))
                        if (persona.frozen) append(" · 已冻结")
                        if (persona.growthCount > 0) {
                            append(" · 已成长 ").append(persona.growthCount).append(" 次")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                when {
                    persona.frozen ->
                        HintText("已冻结：它不会自己改。想恢复就把下面的开关关掉。")

                    persona.stage == PersonaStage.MANUAL -> {
                        HintText(
                            "这份人格是你手写的（或从旧版系统提示词继承的），不会被自动改写。" +
                                "想让它自己长，点下面交给它——从这一刻起，它会按群聊自己微调。",
                        )
                        TextButton(
                            onClick = {
                                scope.launch { controller.handOverPersonaToGrowth(persona.id) }
                            },
                        ) {
                            Text("交给它自己养")
                        }
                    }

                    persona.stage == PersonaStage.EMBRYO ->
                        HintText("还没破壳：攒到 20 条群消息，它会写出第一版人格。")

                    else ->
                        HintText("正在自己长。每次改动都很小，随时可以在「AI → 人设」里回滚。")
                }
            }
        }

        if (persona != null) {
            SectionHeader("刹车", "唯一需要你手动拨的东西")
            SettingsCard {
                SwitchRow(
                    title = "冻结这份人格",
                    desc = "冻结后不再自动成长，正文原样保留；随时可以解冻接着长",
                    checked = persona.frozen,
                    onCheckedChange = { frozen ->
                        scope.launch { controller.setPersonaFrozen(persona.id, frozen) }
                    },
                )
                HintText(
                    "想彻底改由自己写：在「AI → 人设」里点「改为手动」，" +
                        "之后它会完全照你写的来。",
                )
            }
        }

        SectionHeader("它是怎么判断的", "没有阈值要你调")
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HintText("① 有新消息（成长期 ≥10 条）时，它会把这段时间的群聊读一遍。")
                HintText("② 自己判断「有没有我还不了解的东西」——全是闲聊复读就什么都不做。")
                HintText("③ 值得吸收才小步改：最多两段、幅度不超过 15%，身份段锁死。")
                HintText("所以冷清群不会永远不长，热闹群也不会一天改八次。")
            }
        }

        SectionHeader("在哪看它长成什么样", null)
        SettingsCard {
            HintText(
                "「AI → 人设」：五段正文、成长日记、每一版快照都能回滚；" +
                    "也能换种子、冻结、或点「立即成长」催它一次。",
            )
            Text(
                "只在机器人运行、且 AI 已配置时成长。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun stageText(stage: PersonaStage): String =
    when (stage) {
        PersonaStage.EMBRYO -> "破壳中"
        PersonaStage.GROWING -> "成长中"
        PersonaStage.MATURE -> "已成熟"
        PersonaStage.MANUAL -> "手动设定"
    }
