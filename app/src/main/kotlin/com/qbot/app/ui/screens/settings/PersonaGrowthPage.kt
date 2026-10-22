package com.qbot.app.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.qbot.app.BotApplication
import kotlin.math.roundToInt

/**
 * 人格成长设置。
 *
 * 只有"节奏"在这里调——性格本身在「AI · 人设」页看。
 * 这样分工的理由：用户在设置里想知道的是"它多久动一次、会不会失控"，
 * 而不是再读一遍它的人格。
 */
@Composable
fun PersonaGrowthPage(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as BotApplication
    val controller = remember { app.controller }
    val settings = remember { app.settings }
    val config by settings.personaGrowthConfig.collectAsState()
    val active by controller.observeActivePersona().collectAsState(initial = null)

    SettingsPageScaffold(
        title = "人格成长",
        subtitle = "让它自己决定什么时候长大一点",
        onBack = onBack,
    ) {
        SettingsCard {
            val persona = active
            if (persona == null) {
                HintText("还没有人格档案。去「AI → 人设」点右下角的 + 建一个。")
            } else {
                Text(persona.name, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append(
                            when (persona.stage) {
                                com.qbot.core.ai.PersonaStage.EMBRYO -> "破壳中"
                                com.qbot.core.ai.PersonaStage.GROWING -> "成长中"
                                com.qbot.core.ai.PersonaStage.MATURE -> "已成熟"
                                com.qbot.core.ai.PersonaStage.MANUAL -> "手动设定"
                            },
                        )
                        if (persona.frozen) append(" · 已冻结")
                        if (persona.growthCount > 0) {
                            append(" · 已成长 ").append(persona.growthCount).append(" 次")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (persona.stage == com.qbot.core.ai.PersonaStage.MANUAL) {
                    HintText(
                        "这份人格是你手写的，不会自动改。想让它自己长，" +
                            "在「AI → 人设」的卡片上点「交给它自己养」。",
                    )
                }
            }
        }

        SectionHeader("自动成长", "关掉之后只能手动点「立即成长」")
        SettingsCard {
            SwitchRow(
                title = "允许它自己长大",
                desc = "按消息量和间隔自动微调；每次改动不超过 15%，随时可回滚",
                checked = config.enabled,
                onCheckedChange = {
                    settings.savePersonaGrowthConfig(config.copy(enabled = it))
                },
            )
        }

        SectionHeader("节奏", "消息攒够、且距上次超过间隔，才会动一次")
        SettingsCard {
            var birth by remember(config.birthMessages) {
                mutableFloatStateOf(config.birthMessages.toFloat())
            }
            Text("破壳需要 ${birth.toInt()} 条消息", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = birth,
                onValueChange = { birth = (it / 10f).roundToInt() * 10f },
                valueRange = 20f..200f,
                steps = 17,
                enabled = config.enabled,
                onValueChangeFinished = {
                    settings.savePersonaGrowthConfig(
                        config.copy(birthMessages = birth.toInt().coerceIn(20, 200)),
                    )
                },
            )
            HintText("破壳前用的是性格种子骨架，不影响正常聊天，只是还没长出细节。")

            var grow by remember(config.growMessages) {
                mutableFloatStateOf(config.growMessages.toFloat())
            }
            Text("每次成长需要 ${grow.toInt()} 条新消息", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = grow,
                onValueChange = { grow = (it / 10f).roundToInt() * 10f },
                valueRange = 50f..500f,
                steps = 44,
                enabled = config.enabled,
                onValueChangeFinished = {
                    settings.savePersonaGrowthConfig(
                        config.copy(growMessages = grow.toInt().coerceIn(50, 500)),
                    )
                },
            )
            HintText("按新增消息数算，不按时间窗——三天没开机、一开涌进几百条，也只长一次。")

            var interval by remember(config.growIntervalHours) {
                mutableFloatStateOf(config.growIntervalHours.toFloat())
            }
            Text("两次成长至少间隔 ${interval.toInt()} 小时", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = interval,
                onValueChange = { interval = it.roundToInt().toFloat() },
                valueRange = 1f..72f,
                steps = 70,
                enabled = config.enabled,
                onValueChangeFinished = {
                    settings.savePersonaGrowthConfig(
                        config.copy(growIntervalHours = interval.toInt().coerceIn(1, 72)),
                    )
                },
            )
            HintText("间隔本身就是频率上限：12 小时大约一天最多两次；成熟后自动翻倍。")
        }

        SectionHeader("它在长成什么样", null)
        SettingsCard {
            HintText(
                "去「AI → 人设」看当前人格的五段内容、成长日记和每一版快照。" +
                    "觉得哪一版更好，可以直接回滚。",
            )
            if (active != null) {
                HintText("重抽种子、冻结、立即成长也在那张卡片上。")
            }
        }

        Text(
            "自动成长只在机器人运行、且 AI 已配置时进行。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
