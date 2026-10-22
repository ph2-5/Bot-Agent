package com.qbot.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 可折叠设置卡片。
 *
 * 设置页曾经是一列永远拉不到头的卡片（凭证 / AI 接入 / 记忆 / 策略 /
 * 手动发送 / 备份 / 说明七连），常用项被埋在最底下。改成可折叠后：
 * 常用项默认展开，长内容（AI 接入、使用说明）默认收起，
 * 点标题栏即可展开/收起——首屏只剩三张卡。
 *
 * @param initiallyExpanded 默认是否展开（跨配置变更与进程重建保持，
 *   用 rememberSaveable，旋屏不会把用户的展开状态弄丢）
 */
@Composable
fun ExpandableCard(
    title: String,
    subtitle: String? = null,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ExpandableHeader(
                title = title,
                subtitle = subtitle,
                expanded = expanded,
                onToggle = { expanded = !expanded },
            )
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * 轻量折叠分区（无 Card 外壳）。
 *
 * 给「一张卡内部还要再分层」的场景用：群聊参与卡片里的
 * 评分微调 / AI 接话规则 / 冒犯词表都是高级分区，
 * 平时收起，需要时点开——卡片本身不会因此变成第二个长滚动页。
 */
@Composable
fun ExpandableSection(
    title: String,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        ExpandableHeader(
            title = title,
            subtitle = subtitle,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ExpandableHeader(
    title: String,
    subtitle: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "expandArrow",
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "收起" else "展开",
            modifier =
                Modifier
                    .size(24.dp)
                    .rotate(rotation),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
