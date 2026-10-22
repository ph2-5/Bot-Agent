package com.qbot.app

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.qbot.app.data.db.AppDatabase
import com.qbot.app.data.repo.SettingsRepository
import com.qbot.app.runtime.BotController
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
    lateinit var database: AppDatabase
        private set

    lateinit var settings: SettingsRepository
        private set

    lateinit var controller: BotController
        private set

    /** 存活统计：机器人一直在靠谁续命（手动/开机/定时复活/系统重建）。 */
    lateinit var survivalStats: SurvivalStatsStore
        private set

    override fun onCreate() {
        super.onCreate()

        database =
            Room.databaseBuilder(applicationContext, AppDatabase::class.java, "qbot.db")
                // 记忆系统表（v3）走增量迁移，老用户的日志/规则/AI 历史全部保留；
                // 破坏性迁移只作为未知版本的兜底。
                .addMigrations(AppDatabase.MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()

        settings = SettingsRepository(applicationContext)
        controller = BotController(applicationContext, database, settings, AndroidLogger())
        survivalStats = SurvivalStatsStore(applicationContext)

        // 注册定时复活任务：进程被 ROM 整体杀掉后，到点由系统把 App 拉起来。
        // 重复注册是幂等的（同 id 的 JobInfo 会覆盖旧的）。
        ResurrectorJobService.schedule(applicationContext)
    }
}
