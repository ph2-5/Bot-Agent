package com.qbot.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qbot.app.BotApplication
import com.qbot.core.engine.LogEntry
import com.qbot.core.engine.LogLevel
import com.qbot.app.ui.theme.StatusError
import com.qbot.app.ui.theme.StatusIdle
import com.qbot.app.ui.theme.StatusOnline
import com.qbot.app.ui.theme.StatusWarning

@Composable
fun LogsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = (context.applicationContext as BotApplication).controller
    val logs by controller.logs.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "共 ${logs.size} 条（仅保留最近 500 条在内存，全部记录已存本地）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无日志。启动机器人后这里会实时显示连接与消息记录。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(logs, key = { it.timestampMillis.toString() + it.tag + it.text.hashCode() }) { entry ->
                    LogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val (color, shortTag) =
        when (entry.level) {
            LogLevel.ERROR -> StatusError to "E"
            LogLevel.WARN -> StatusWarning to "W"
            LogLevel.INFO -> StatusOnline to "I"
            LogLevel.DEBUG -> StatusIdle to "D"
            LogLevel.VERBOSE -> StatusIdle to "V"
        }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = shortTag,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = entry.text,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "${formatClock(entry.timestampMillis)} · ${entry.tag}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatClock(millis: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
    return sdf.format(java.util.Date(millis))
}
