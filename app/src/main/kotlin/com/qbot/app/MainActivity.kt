package com.qbot.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qbot.app.service.BotForegroundService
import com.qbot.app.ui.MainScreen
import com.qbot.app.ui.theme.QBotMobileTheme

class MainActivity : ComponentActivity() {
    private var permissionGranted by mutableStateOf(false)

    /**
     * Android 13（API 33）起，通知需要运行时授权。
     * 拿不到通知权限就无法启动前台服务，因此进入界面时先申请。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        setContent {
            QBotMobileTheme {
                MainScreen(
                    hasNotificationPermission = permissionGranted,
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onStartService = { BotForegroundService.start(this) },
                    onStopService = { BotForegroundService.stop(this) },
                    onRequestBatteryWhitelist = { requestBatteryWhitelist() },
                )
            }
        }
    }

    /**
     * 跳转电池优化白名单设置页。
     *
     * 这一步无法静默完成——Android 不允许应用自行豁免电池优化，
     * 必须由用户在系统页面里手动确认。首次进入时由设置页引导。
     */
    private fun requestBatteryWhitelist() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        }.onFailure {
            // 部分 ROM 未实现该页面，退回到电池优化列表页
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
