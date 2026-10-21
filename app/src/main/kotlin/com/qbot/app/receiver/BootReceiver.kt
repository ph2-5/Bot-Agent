package com.qbot.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qbot.app.data.repo.SettingsRepository
import com.qbot.app.service.BotForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启接收器。
 *
 * 同时监听"应用被替换"（[Intent.ACTION_MY_PACKAGE_REPLACED]）——
 * 用户更新 App 后系统不会发开机广播，但期望机器人能自动恢复在线，
 * 否则每次升级都得手动打开一次 App。
 *
 * 广播进程有 10 秒时限，读取设置属于 IO 操作，
 * 因此用 [goAsync] 延长生命周期，避免在主线程被判定为超时。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_PACKAGE_REPLACED
        ) {
            return
        }

        // 应用升级场景下只关心自己，避免收到其他应用的广播时误启动
        if (action == Intent.ACTION_PACKAGE_REPLACED) {
            val replacedPackage = intent.data?.schemeSpecificPart
            if (replacedPackage != context.packageName) return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // DataStore 的 flow 会立刻发出当前值，first() 取到后自动取消订阅
                val enabled =
                    SettingsRepository(context)
                        .autoStart
                        .first()

                if (enabled) {
                    BotForegroundService.start(context)
                }
            } finally {
                // 无论成功与否都必须结束，否则系统会认为广播处理超时
                pendingResult.finish()
            }
        }
    }
}
