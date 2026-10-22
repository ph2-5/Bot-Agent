package com.qbot.app.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * 定时复活服务。
 *
 * 为什么需要它：进程内的看门狗只能救"服务还在、连接断了"，
 * 救不了"整个进程被 ROM 杀掉"。进程一旦死了，内存里的看门狗、
 * NetworkCallback、START_STICKY 的希望全部落空——
 * 而国产 ROM 杀进程恰恰是保活场景里最常见的事。
 *
 * JobScheduler 的周期性任务由系统托管：到点后如果 App 进程已死，
 * 系统会重新拉起进程来执行任务，任务里再把前台服务拉起来。
 * [JobInfo.Builder.setPersisted] 让任务本身也跨重启存活。
 *
 * 权限细节：Android 12+ 禁止后台启动前台服务，但"正在执行
 * JobScheduler 任务"期间 App 会被系统临时加入电源白名单，
 * 允许启动前台服务；个别机型仍可能拒绝，失败时只记日志不崩溃。
 */
class ResurrectorJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if (BotForegroundService.isAlive) {
            // 服务还活着，无需复活——周期性任务大多数时候会走这里
            return false
        }

        Log.i(TAG, "进程重建：定时任务尝试复活前台服务")
        runCatching {
            BotForegroundService.start(this, SurvivalSource.JOB)
        }.onFailure { t ->
            // 后台启动前台服务被系统拒绝（Android 12+ 少数场景）。
            // 不致命：下一个周期（15 分钟）或开机广播还会再试。
            Log.w(TAG, "复活失败：${t.message}")
        }
        return false // 没有需要异步执行的工作
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val TAG = "ResurrectorJob"
        private const val JOB_ID = 2001

        /** 15 分钟是系统允许的最小周期；任务由系统托管，低功耗且不占内存。 */
        private const val PERIOD_MILLIS = 15 * 60 * 1000L

        /** 注册（或更新）周期性复活任务。重复调用是幂等的。 */
        fun schedule(context: Context) {
            val scheduler =
                context.getSystemService(JobScheduler::class.java) ?: return
            val info =
                JobInfo
                    .Builder(JOB_ID, ComponentName(context, ResurrectorJobService::class.java))
                    .setPersisted(true) // 跨重启存活（需要 RECEIVE_BOOT_COMPLETED 权限）
                    .setPeriodic(PERIOD_MILLIS)
                    .build()

            val result = scheduler.schedule(info)
            if (result == JobScheduler.RESULT_FAILURE) {
                Log.w(TAG, "任务注册失败（可能已达系统上限）")
            }
        }

        /** 用户显式停止机器人时取消，避免停止后又被拉起来。 */
        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
