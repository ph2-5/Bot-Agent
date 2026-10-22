package com.qbot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.qbot.app.BotApplication
import com.qbot.app.MainActivity
import com.qbot.app.R
import com.qbot.app.network.NetworkMonitor
import com.qbot.core.gateway.ConnectionState
import com.qbot.core.gateway.GatewayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 机器人常驻前台服务。
 *
 * 这是"手机 24 小时在线"能否成立的关键：
 * 普通后台服务在 Android 8+ 几分钟内就会被系统停止，
 * 只有前台服务配合常驻通知才能长期存活（用户随时可以在通知栏一键停止）。
 *
 * 四层保险：
 * 1. **前台服务本身**：[START_STICKY] 让进程被回收后系统会尝试重建；
 * 2. **定时复活任务**：进程被整体杀掉后，由 [ResurrectorJobService]
 *    （JobScheduler 托管）到点把服务重新拉起来；
 * 3. **网络监听 + 看门狗**：断网恢复 / 连接假死时立刻重连；
 * 4. **WifiLock + 重连 WakeLock**：防止 WiFi 休眠掐断长连接、
 *    防止重连窗口期 CPU 休眠把重试拖死。
 */
class BotForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var controller: com.qbot.app.runtime.BotController
    private lateinit var networkMonitor: NetworkMonitor

    /** 用户是否显式点了停止。用于区分"被系统回收"与"主动关闭"。 */
    @Volatile
    private var stoppedByUser = false

    /** 本实例是否已记录过启动统计（onStartCommand 可能被多次调用）。 */
    private var statsRecorded = false

    /** WiFi 锁：部分 ROM 的 WiFi 省电策略会静默断开长连接。 */
    private var wifiLock: WifiManager.WifiLock? = null

    /** 重连期间的短暂 WakeLock：给握手与鉴权争取 CPU 时间。 */
    private var reconnectWakeLock: PowerManager.WakeLock? = null

    /** 上一次观察到的连接状态，用于只在状态跃迁时点 WakeLock。 */
    private var lastObservedState: ConnectionState? = null

    override fun onCreate() {
        super.onCreate()

        isAlive = true

        controller = (application as BotApplication).controller
        createNotificationChannel()

        // 必须在 5 秒内调用 startForeground，否则系统会判定服务启动超时并杀掉它。
        // 第四个参数必须与 Manifest 中声明的 foregroundServiceType 一致（specialUse）。
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(controller.status.value),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        acquireWifiLock()

        controller.start()

        networkMonitor =
            NetworkMonitor(
                context = this,
                scope = scope,
                onAvailable = {
                    if (controller.isRunning()) controller.onNetworkRestored()
                },
            )
        networkMonitor.start()

        // 状态变化 → 更新常驻通知；跃迁到连接中时点一下 WakeLock
        scope.launch {
            controller.status.collect { status ->
                updateNotification(status)
                onStatusChanged(status)
            }
        }

        startWatchdog()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stoppedByUser = true
                controller.stop()
                // 用户显式停止：连定时复活任务一起取消，不然 15 分钟后又被拉起来
                ResurrectorJobService.cancel(applicationContext)
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                controller.restart()
            }
        }

        // 记录存活统计：同一个服务实例只记第一次，
        // 之后重复的 start（含定时任务的空唤醒）不再计数。
        if (!statsRecorded) {
            statsRecorded = true
            val source =
                intent?.getStringExtra(EXTRA_SOURCE)
                    ?: if (intent == null) SurvivalSource.SYSTEM else SurvivalSource.USER
            (application as BotApplication).survivalStats.recordStart(source)
        }

        // 进程被系统回收后，系统会用 null intent 重建服务：
        // 此时 onCreate 已经重新拉起了机器人，这里返回 STICKY 让系统继续保活。
        return START_STICKY
    }

    /**
     * 看门狗。
     *
     * 部分国产 ROM（MIUI / ColorOS / EMUI 等）会在内存紧张时
     * 静默杀死进程，且不一定回调 onDestroy。这里采用"心跳存活性"检查：
     * 若控制器已停止运行但用户并未主动停止，说明进程被异常清理，尝试重新拉起。
     */
    private fun startWatchdog() {
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MILLIS)
                if (stoppedByUser) break
                if (!controller.isRunning() && networkMonitor.isOnline()) {
                    controller.start()
                }
            }
        }
    }

    /**
     * 状态跃迁到连接类状态时点 WakeLock。
     *
     * 重连退避可能把尝试时间推到锁屏后的 Doze 窗口，
     * 没有 WakeLock 的话定时器与 TCP 握手都会被拖延，
     * 表现为"锁屏十几分钟后消息才恢复"。
     * 锁带 15 秒超时自动释放，不会忘释放。
     */
    private fun onStatusChanged(status: GatewayStatus) {
        val state = status.state
        if (state == lastObservedState) return
        lastObservedState = state

        val connecting =
            state == ConnectionState.CONNECTING ||
                state == ConnectionState.IDENTIFYING ||
                state == ConnectionState.RECONNECTING
        if (connecting) {
            pokeReconnectWakeLock()
        }
    }

    private fun acquireWifiLock() {
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java) ?: return
        // WIFI_MODE_FULL_HIGH_PERF 在 API 29 起被标记废弃，但国产 ROM 上
        // 仍然是对抗"WiFi 省电断流"最直接的手段，这里显式压制弃用告警。
        @Suppress("DEPRECATION")
        wifiLock =
            wifiManager
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
                .also { lock ->
                    lock.setReferenceCounted(false)
                    runCatching { lock.acquire() }
                }
    }

    private fun pokeReconnectWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        val lock =
            reconnectWakeLock
                ?: powerManager
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    .also { reconnectWakeLock = it }
        if (!lock.isHeld) {
            // 超时自动释放：即使回调丢了也不会把 WakeLock 漏到天荒地老
            lock.acquire(RECONNECT_WAKELOCK_TIMEOUT_MILLIS)
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        runCatching { reconnectWakeLock?.takeIf { it.isHeld }?.release() }
        reconnectWakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户从最近任务划掉卡片时，多数 ROM 会连带杀掉进程。
        // 这里发一个延迟的自启意图，提高被重新拉起的概率（需配合自启动权限）。
        val restartIntent =
            Intent(applicationContext, BotForegroundService::class.java).apply {
                action = ACTION_RESTART
            }
        val pendingIntent =
            PendingIntent.getService(
                applicationContext,
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or pendingIntentFlagImmutable(),
            )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
        alarmManager?.set(
            android.app.AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 1_000L,
            pendingIntent,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        isAlive = false
        networkMonitor.stop()
        releaseLocks()
        scope.cancel()
    }

    override fun onBind(intent: Intent?) = null

    // ---------- 通知 ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW, // 低重要性：不弹提示音、不震动
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(status: GatewayStatus) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: GatewayStatus): Notification {
        val text =
            when (status.state) {
                ConnectionState.READY -> {
                    val uptime = status.readySinceMillis?.let { formatUptime(System.currentTimeMillis() - it) }
                    if (uptime != null) {
                        getString(R.string.notification_text_online) + " · 已在线 $uptime"
                    } else {
                        getString(R.string.notification_text_online)
                    }
                }

                ConnectionState.CONNECTING, ConnectionState.IDENTIFYING ->
                    getString(R.string.notification_text_connecting)

                ConnectionState.RECONNECTING ->
                    getString(R.string.notification_text_reconnecting, status.reconnectCount)

                ConnectionState.FATAL ->
                    status.lastError ?: getString(R.string.notification_text_idle)

                else -> getString(R.string.notification_text_idle)
            }

        val openIntent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val contentPending =
            PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentFlagImmutable(),
            )

        val stopIntent = Intent(this, BotForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPending =
            PendingIntent.getService(
                this,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentFlagImmutable(),
            )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentPending)
            .setOngoing(true) // 常驻，不可手动划掉
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_stop),
                stopPending,
            )
            .build()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun formatUptime(millis: Long): String {
        val totalMinutes = millis / 60_000
        val days = totalMinutes / 1440
        val hours = (totalMinutes % 1440) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "${days}天${hours}小时"
            hours > 0 -> "${hours}小时${minutes}分"
            else -> "${minutes}分钟"
        }
    }

    private fun pendingIntentFlagImmutable(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        private const val TAG = "BotService"
        private const val CHANNEL_ID = "bot_status"
        private const val NOTIFICATION_ID = 1001
        private const val WATCHDOG_INTERVAL_MILLIS = 60_000L

        /** WakeLock 标签，`adb shell dumpsys power` 里按名字排查 */
        private const val WIFI_LOCK_TAG = "QBot:websocket"
        private const val WAKE_LOCK_TAG = "QBot:reconnect"

        /** 重连 WakeLock 的自动释放超时：够完成一次握手+鉴权，又不至于忘释放 */
        private const val RECONNECT_WAKELOCK_TIMEOUT_MILLIS = 15_000L

        private const val ACTION_STOP = "com.qbot.app.action.STOP"
        private const val ACTION_RESTART = "com.qbot.app.action.RESTART"

        /** 启动来源的 intent extra，供存活统计使用 */
        const val EXTRA_SOURCE = "com.qbot.app.extra.SOURCE"

        /**
         * 服务实例是否活着。
         *
         * 供 [ResurrectorJobService] 判断"要不要复活"：
         * 进程被杀死时这个静态标志随进程一起消失，天然回到 false。
         */
        @Volatile
        var isAlive: Boolean = false
            private set

        fun start(
            context: Context,
            source: String = SurvivalSource.USER,
        ) {
            val intent =
                Intent(context, BotForegroundService::class.java).apply {
                    putExtra(EXTRA_SOURCE, source)
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BotForegroundService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        /**
         * 是否应当申请忽略电池优化。
         *
         * 不申请也并非完全不能用，但国内 ROM 上大概率会在锁屏一段时间后断连，
         * 因此维护端会引导用户开启。
         */
        fun shouldRequestBatteryWhitelist(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
            val pm = context.getSystemService(PowerManager::class.java)
            return !pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    }
}
