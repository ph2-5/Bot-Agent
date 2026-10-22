package com.qbot.app.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 服务启动来源。
 *
 * 存活统计的核心问题不是"在线了多久"，而是"上次是怎么没的"：
 * 每次拉起都记下来源，维护端就能看出机器人一直在靠谁续命
 * （用户手动 / 开机广播 / JobScheduler 复活 / 系统重投递）。
 */
object SurvivalSource {
    const val USER = "user"
    const val BOOT = "boot"
    const val JOB = "job"
    const val SYSTEM = "system"
}

/** 存活统计快照。 */
data class SurvivalStats(
    /** 用户手动（打开 App 点启动）拉起次数 */
    val userStarts: Int = 0,
    /** 开机 / 升级广播拉起次数 */
    val bootStarts: Int = 0,
    /** JobScheduler 周期性复活次数 */
    val jobResurrections: Int = 0,
    /** 系统重投递（START_STICKY 无 intent 重建）次数 */
    val systemRedeliveries: Int = 0,
    /** 最近一次拉起的来源 */
    val lastStartSource: String = "",
    /** 最近一次拉起的时间戳 */
    val lastStartAtMillis: Long = 0,
) {
    /** 全部拉起次数。 */
    val totalStarts: Int
        get() = userStarts + bootStarts + jobResurrections + systemRedeliveries

    /** 来源的中文描述，供 UI 展示。 */
    fun describeSource(source: String = lastStartSource): String =
        when (source) {
            SurvivalSource.USER -> "手动启动"
            SurvivalSource.BOOT -> "开机/升级自启"
            SurvivalSource.JOB -> "定时任务复活"
            SurvivalSource.SYSTEM -> "系统重建服务"
            else -> "未知"
        }
}

/**
 * 存活统计存储。
 *
 * 计数器必须跨进程存活才有意义（进程被杀死恰恰是最需要记录的事件），
 * 因此用 SharedPreferences 而不是内存变量；
 * 同时暴露 StateFlow 供维护端仪表盘实时观察。
 */
class SurvivalStatsStore(context: Context) {
    private val prefs = context.getSharedPreferences("survival_stats", Context.MODE_PRIVATE)

    private val _stats = MutableStateFlow(read())
    val stats: StateFlow<SurvivalStats> = _stats.asStateFlow()

    /** 记录一次服务拉起。 */
    fun recordStart(
        source: String,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        val current = _stats.value
        val updated =
            when (source) {
                SurvivalSource.USER -> current.copy(userStarts = current.userStarts + 1)
                SurvivalSource.BOOT -> current.copy(bootStarts = current.bootStarts + 1)
                SurvivalSource.JOB -> current.copy(jobResurrections = current.jobResurrections + 1)
                SurvivalSource.SYSTEM ->
                    current.copy(systemRedeliveries = current.systemRedeliveries + 1)

                else -> current
            }.copy(lastStartSource = source, lastStartAtMillis = atMillis)

        prefs
            .edit()
            .putInt(KEY_USER, updated.userStarts)
            .putInt(KEY_BOOT, updated.bootStarts)
            .putInt(KEY_JOB, updated.jobResurrections)
            .putInt(KEY_SYSTEM, updated.systemRedeliveries)
            .putString(KEY_LAST_SOURCE, updated.lastStartSource)
            .putLong(KEY_LAST_AT, updated.lastStartAtMillis)
            .apply()

        _stats.value = updated
    }

    /** 清空统计（维护端「重置」用）。 */
    fun reset() {
        prefs.edit().clear().apply()
        _stats.value = SurvivalStats()
    }

    private fun read(): SurvivalStats =
        SurvivalStats(
            userStarts = prefs.getInt(KEY_USER, 0),
            bootStarts = prefs.getInt(KEY_BOOT, 0),
            jobResurrections = prefs.getInt(KEY_JOB, 0),
            systemRedeliveries = prefs.getInt(KEY_SYSTEM, 0),
            lastStartSource = prefs.getString(KEY_LAST_SOURCE, "").orEmpty(),
            lastStartAtMillis = prefs.getLong(KEY_LAST_AT, 0),
        )

    private companion object {
        const val KEY_USER = "user_starts"
        const val KEY_BOOT = "boot_starts"
        const val KEY_JOB = "job_resurrections"
        const val KEY_SYSTEM = "system_redeliveries"
        const val KEY_LAST_SOURCE = "last_start_source"
        const val KEY_LAST_AT = "last_start_at"
    }
}
