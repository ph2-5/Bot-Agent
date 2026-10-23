package com.qbot.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qbot.app.BotApplication
import com.qbot.core.ai.MemeImage
import kotlinx.coroutines.launch

/**
 * 表情包库。
 *
 * 两个来源：群友发的图它自己会捡（HARVEST），这里可以手动贴链接（MANUAL）。
 * 模型回复时能从库里挑一张配着发——一次最多一张，且大多数时候只用文字。
 *
 * 刻意不做「联网搜图」：搜索接口返回的是网页而不是直图，把网页链接
 * 丢给上传接口只会失败；这群人自己的图才是用起来最稳的。
 */
@Composable
fun MemePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BotApplication
    val controller = app.controller
    val scope = rememberCoroutineScope()

    val memes by controller.observeMemes().collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    SettingsPageScaffold(
        title = "表情包库",
        subtitle = "它回复时可以从这些图里挑一张配着发",
        onBack = onBack,
    ) {
        SectionHeader("怎么用", "图是配菜，不是主食")
        SettingsCard {
            HintText(
                "群友发表情包时，它会顺手把图收进来——" +
                    "这群人刚发过的款，回一张同款的，气氛才对。",
            )
            HintText(
                "提示词里只会给最常用的 8 张，并且明确告诉它：" +
                    "大多数回复只用文字，气氛到了才发，一次最多一张。" +
                    "所以入库的图多不等于发得多，别担心它变成刷图机器。",
            )
            HintText(
                "想手动加就点下面的「贴一张图」。" +
                    "链接必须是直图（.jpg / .png / .gif 结尾），" +
                    "网页链接上传会失败——失败时它只少发一张图，文字回复不受影响。",
            )
        }

        SectionHeader("库里的图", "共 ${memes.size} 张，按被发的次数排序")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { adding = true }) { Text("贴一张图") }
            if (memes.isNotEmpty()) {
                TextButton(
                    onClick = {
                        scope.launch {
                            controller.clearMemes()
                            hint = "已清空表情包库"
                        }
                    },
                ) { Text("全部清空") }
            }
        }
        if (memes.isEmpty()) {
            HintText("还是空的。等群里有人发表情包就会自动开始捡，或点「贴一张图」手动加。")
        }
        memes.forEach { meme ->
            MemeRow(
                meme = meme,
                onDelete = {
                    scope.launch {
                        controller.deleteMeme(meme.id)
                        hint = "已删除"
                    }
                },
            )
        }

        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    if (adding) {
        AddMemeDialog(
            onDismiss = { adding = false },
            onConfirm = { url ->
                adding = false
                scope.launch {
                    val ok = controller.addMeme(url)
                    hint =
                        if (ok) {
                            "已加入表情包库"
                        } else {
                            "链接是空的，没有加入"
                        }
                }
            },
        )
    }
}

@Composable
private fun MemeRow(meme: MemeImage, onDelete: () -> Unit) {
    SettingsCard {
        Text(
            meme.url,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            buildString {
                append(if (meme.source == MemeImage.SOURCE_MANUAL) "手动添加" else "群里捡的")
                append(" · 见过 ").append(meme.hits).append(" 次")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

@Composable
private fun AddMemeDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("贴一张图") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("图片链接") },
                    placeholder = { Text("https://...jpg") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                HintText(
                    "要直图链接。可以先在群里发出来，再回到这里看它有没有被自动捡到——" +
                        "那样最省事。",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url) },
                enabled = url.isNotBlank(),
            ) { Text("加入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
