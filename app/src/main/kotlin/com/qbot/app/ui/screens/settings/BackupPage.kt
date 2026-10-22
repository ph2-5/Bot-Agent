package com.qbot.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val controller = remember { app.controller }
    val scope = rememberCoroutineScope()
    var hint by remember { mutableStateOf<String?>(null) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                    val json = controller.exportBackup()
                    // 备份文件可能上百 KB，读写放 IO 线程，别卡 UI
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("无法写入所选位置")
                    }
                }.onSuccess {
                    hint = "已导出到所选位置（含密钥，请妥善保管）"
                }.onFailure { hint = "导出失败：${it.message}" }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                    val text =
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)
                                ?.bufferedReader()?.readText()
                                ?: error("无法读取所选文件")
                        }
                    controller.importBackup(text)
                }.onSuccess { hint = it }
                    .onFailure { hint = "导入失败：${it.message}" }
            }
        }

    SettingsPageScaffold(
        title = "数据与备份",
        subtitle = "换机或备份用：规则、人设、配置与接话经验",
        onBack = onBack,
    ) {
        SettingsCard {
            HintText(
                "导出内容包含机器人凭证与 AI Key（明文 JSON），备份文件请妥善保管。\n" +
                    "导入按「合并」进行：同 id 规则覆盖、同名同绑定的人设更新、其余新增；" +
                    "接话经验按文本去重后合并。",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        val name = "qbot-config-%tF.json".format(java.util.Date())
                        exportLauncher.launch(name)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("导出配置")
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("导入配置")
                }
            }

            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        SectionHeader("不备份的内容", "运行数据体积大、且换机后参考价值低")
        SettingsCard {
            HintText(
                "日志、群聊背景（最近 200 条）、AI 对话历史、记忆事实、人设版本快照不会导出；" +
                    "接话决策日志也不导出，但从中提炼的经验条目会随配置一起备份。",
            )
        }
    }
}
