package com.qbot.app.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 一个厂商的保活配置项。 */
private data class RomVendor(
    val name: String,
    val steps: List<String>,
    /** 自启动/关联启动设置的组件，尽力而为，打不开就回退应用详情页 */
    val autoStartComponent: String? = null,
)

private val VENDORS =
    listOf(
        RomVendor(
            name = "小米 / Redmi（MIUI / HyperOS）",
            steps =
                listOf(
                    "设置 → 应用设置 → 应用管理 → 本应用 → 自启动，打开",
                    "设置 → 省电与电池 → 应用智能省电 → 本应用 → 无限制",
                    "近期任务里锁定本应用卡片（下拉卡片加锁）",
                    "安全中心 → 权限管理 → 后台弹出界面权限，允许",
                ),
            autoStartComponent = "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        RomVendor(
            name = "华为 / 荣耀（EMUI / MagicOS）",
            steps =
                listOf(
                    "设置 → 应用和服务 → 应用启动管理 → 本应用 → 手动管理，三项全开",
                    "设置 → 电池 → 更多电池设置 → 休眠时保持网络连接，打开",
                    "设置 → 应用和服务 → 本应用 → 电池 → 不受限制",
                ),
            autoStartComponent = "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        RomVendor(
            name = "OPPO / 一加（ColorOS）",
            steps =
                listOf(
                    "设置 → 电池 → 应用耗电管理 → 本应用 → 允许后台运行",
                    "设置 → 应用 → 应用管理 → 本应用 → 自启动，打开",
                    "长按近期任务卡片 → 锁定",
                ),
            autoStartComponent = "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
        ),
        RomVendor(
            name = "vivo / iQOO（OriginOS / Funtouch）",
            steps =
                listOf(
                    "设置 → 电池 → 后台高耗电 → 本应用，允许",
                    "i管家 → 应用管理 → 自启动 → 本应用，允许",
                    "设置 → 更多设置 → 权限管理 → 后台弹出界面，允许",
                ),
            autoStartComponent = "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        ),
        RomVendor(
            name = "三星（One UI）",
            steps =
                listOf(
                    "设置 → 应用程序 → 本应用 → 电池 → 不受限制",
                    "设置 → 电池和设备维护 → 处理实用程序 → 添加未监视的应用程序",
                    "三星的「睡眠模式」会定时杀后台，建议关闭或加入例外",
                ),
        ),
    )

/**
 * ROM 保活指南。
 *
 * 为什么单列一张卡：应用能做的（前台服务、定时复活任务、WifiLock）
 * 都已经做了，但国产 ROM 的系统级杀后台不在应用管辖范围。
 * 这里的文案是真正让机器人「活过夜」的最后一道工序——
 * 顺序按「不设置会怎样」的影响排序，第一条最重要。
 */
@Composable
fun RomSurvivalGuideCard(onRequestBatteryWhitelist: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "后台保活设置（重要）",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Android 系统与国产 ROM 都会在锁屏后清理后台。本应用已自带前台服务、" +
                    "定时复活任务与网络锁，但下面的系统级设置决定了它们能发挥几成——" +
                    "不设置的话，锁屏几十分钟后大概率掉线。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(onClick = onRequestBatteryWhitelist, modifier = Modifier.fillMaxWidth()) {
                Text("① 加入电池优化白名单（系统弹窗，最优先）")
            }

            VENDORS.forEach { vendor ->
                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = if (expanded == vendor.name) null else vendor.name }
                        .padding(vertical = 6.dp),
                ) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            text = vendor.name,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(if (expanded == vendor.name) "收起" else "展开")
                    }

                    if (expanded == vendor.name) {
                        Spacer(Modifier.height(4.dp))
                        vendor.steps.forEachIndexed { index, step ->
                            Text(
                                text = "${index + 1}. $step",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { context.openVendorAutoStart(vendor) },
                            ) { Text("尝试打开自启动设置") }
                            OutlinedButton(
                                onClick = { context.openAppDetails() },
                            ) { Text("打开应用详情") }
                        }
                    }
                }
            }

            Text(
                "提示：不同 ROM 的菜单名称随版本变化，找不到时直接在系统设置里搜索「自启动」与「电池」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 尽力打开厂商的自启动管理页，失败则退回应用详情页。 */
private fun Context.openVendorAutoStart(vendor: RomVendor) {
    val component = vendor.autoStartComponent
    if (component != null) {
        val (pkg, cls) = component.split("/")
        val intent =
            Intent().apply {
                setComponent(ComponentName(pkg, cls))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val resolved =
            runCatching { packageManager.resolveActivity(intent, 0) }.getOrNull()
        if (resolved != null) {
            runCatching { startActivity(intent) }.onSuccess { return }
        }
    }
    openAppDetails()
}

private fun Context.openAppDetails() {
    val intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    runCatching { startActivity(intent) }
}

/** 打开系统电池优化设置列表（Android 6+）。 */
fun Context.openBatteryOptimizationSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    runCatching { startActivity(intent) }.onFailure { openAppDetails() }
}
