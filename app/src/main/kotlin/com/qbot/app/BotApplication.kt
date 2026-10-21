package com.qbot.app

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.qbot.app.data.db.AppDatabase
import com.qbot.app.data.repo.SettingsRepository
import com.qbot.app.runtime.BotController
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

    override fun onCreate() {
        super.onCreate()

        database =
            Room.databaseBuilder(applicationContext, AppDatabase::class.java, "qbot.db")
                // 破坏性迁移在自用 App 里是可接受的取舍：
                // 本地只存日志与规则，重建代价远低于维护迁移脚本。
                .fallbackToDestructiveMigration()
                .build()

        settings = SettingsRepository(applicationContext)
        controller = BotController(applicationContext, database, settings, AndroidLogger())
    }
}
