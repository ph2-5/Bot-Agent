package com.qbot.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qbot.app.data.repo.SettingsRepository
import com.qbot.app.service.BotForegroundService
import com.qbot.app.service.ResurrectorJobService
import com.qbot.app.service.SurvivalSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启接收器。
 *
 * 同时监听：
 * - "应用被替换"（[Intent.ACTION_MY_PACKAGE_REPLACED]）——用户更新 App 后
 *   系统不会发开机广播，但期望机器人能自动恢复在线；
 * - 厂商快启广播（QUICKBOOT_POWERON / HTC 系）——部分 ROM 的"开机"
 *   走的是自己的私有广播，标准 BOOT_COMPLETED 收不到；
 * - 任务重注册（[Intent.ACTION_BOOT_COMPLETED]）——[ResurrectorJobService]
 *   的持久化任务虽然声称跨重启，但显式重注册一次更稳。
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
        val recognized =
            action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                action == Intent.ACTION_PACKAGE_REPLACED ||
                action == "android.intent.action.QUICKBOOT_POWERON" || // HTC 系
                action == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (!recognized) {
            return
        }

        // 应用升级场景下只关心自己，避免收到其他应用的广播时误启动
        if (action == Intent.ACTION_PACKAGE_REPLACED) {
            val replacedPackage = intent.data?.schemeSpecificPart
            if (replacedPackage != context.packageName) return
        }

        // 无论设置里是否允许自启，都把复活任务注册上：
        // 用户之后手动开过一次机器人，任务就该开始兜底。
        ResurrectorJobService.schedule(context)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // DataStore 的 flow 会立刻发出当前值，first() 取到后自动取消订阅
                val enabled =
                    SettingsRepository(context)
                        .autoStart
                        .first()

                if (enabled) {
                    BotForegroundService.start(context, SurvivalSource.BOOT)
                }
            } finally {
                // 无论成功与否都必须结束，否则系统会认为广播处理超时
                pendingResult.finish()
            }
        }
    }
}
