package com.qbot.app

import android.app.Application
import android.util.Log
import com.qbot.app.data.db.AppDatabase
import com.qbot.app.data.repo.SettingsRepository
import com.qbot.app.runtime.BotController
import com.qbot.app.runtime.BotManager
import com.qbot.app.service.ResurrectorJobService
import com.qbot.app.service.SurvivalStatsStore
import com.qbot.core.util.Logger

/**
 * 把 botcore 的日志桥接到 Logcat。
 *
 * 必须用大括号体：Log.v/d/i/w 返回 Int（写入的字节数），
 * 写成表达式体 `override fun v(...) = Log.v(...)` 会让返回类型变成 Int，
 * 与接口声明的 Unit 不匹配而编译失败。
 */
class AndroidLogger : Logger {
    override fun v(tag: String, msg: String) {
        Log.v(tag, msg)
    }

    override fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    override fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    override fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    override fun e(tag: String, msg: String, t: Throwable?) {
        if (t != null) {
            Log.e(tag, msg, t)
        } else {
            Log.e(tag, msg)
        }
    }
}

class BotApplication : Application() {
    /**
     * 多机器人管理器：账号目录、当前选中、每个机器人的库 / 设置 / 运行时。
     *
     * 下面三个兼容属性让维护端继续写 `app.controller` / `app.settings` / `app.database`，
     * 实际指向「当前选中的机器人」，切换机器人后由界面重建读取新实例。
     */
    lateinit var bots: BotManager
        private set

    val database: AppDatabase
        get() = bots.currentDatabase

    val settings: SettingsRepository
        get() = bots.currentSettings

    val controller: BotController
        get() = bots.currentController

    /** 存活统计：机器人一直在靠谁续命（手动/开机/定时复活/系统重建）。 */
    lateinit var survivalStats: SurvivalStatsStore
        private set

    override fun onCreate() {
        super.onCreate()

        bots = BotManager(applicationContext, AndroidLogger())
        survivalStats = SurvivalStatsStore(applicationContext)

        // 注册定时复活任务：进程被 ROM 整体杀掉后，到点由系统把 App 拉起来。
        // 重复注册是幂等的（同 id 的 JobInfo 会覆盖旧的）。
        ResurrectorJobService.schedule(applicationContext)
    }
}
