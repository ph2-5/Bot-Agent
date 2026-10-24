package com.qbot.app.runtime

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.qbot.app.data.db.AppDatabase
import com.qbot.app.data.repo.BotAccount
import com.qbot.app.data.repo.BotRegistry
import com.qbot.app.data.repo.SettingsRepository
import com.qbot.core.gateway.GatewayStatus
import com.qbot.core.util.Logger
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一个机器人实例：独立的设置、数据库与运行时。
 *
 * 三者必须成组存在——同一个 [BotController] 构造时就绑定了库与设置，
 * 拆开使用会把 A 机器人的人格写到 B 机器人的库里。
 */
data class BotInstance(
    val account: BotAccount,
    val settings: SettingsRepository,
    val database: AppDatabase,
    val controller: BotController,
)

/**
 * 多机器人管理器。
 *
 * 设计取舍：**每个机器人一个数据库文件**，而不是给 19 张表加 `botId`。
 * 好处是人格、记忆、群档案、日志天然隔离，老数据库一个字节都不用动；
 * 代价是同一个群里的两个机器人不会共享任何学习成果——这本来也是期望行为。
 *
 * 默认机器人沿用历史文件名，升级后老用户的数据与设置原样保留。
 */
class BotManager(
    private val context: Context,
    private val logger: Logger,
) {
    private val appContext = context.applicationContext
    val registry = BotRegistry(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private val instances = mutableMapOf<String, BotInstance>()
    private val forwards = mutableMapOf<String, Job>()

    /**
     * 设置仓库按机器人缓存。
     *
     * [SettingsRepository] 构造要打开 EncryptedSharedPreferences（取主密钥 + 解压文件），
     * 列表页每个机器人读一次 AppID 就构造一次会明显拖慢首屏，
     * 因此与实例一样按 id 复用。
     */
    private val settingsCache = mutableMapOf<String, SettingsRepository>()

    /**
     * 前台服务是否正在承载这些机器人。
     *
     * 用来区分「用户点了启用」和「服务没开时不该偷偷连」：
     * 服务未启动时改动开关只落配置，等下次启动统一生效。
     */
    @Volatile
    private var running = false

    private val _accounts = MutableStateFlow(registry.accounts())
    val accounts: StateFlow<List<BotAccount>> = _accounts.asStateFlow()

    private val _currentBotId = MutableStateFlow(registry.currentId())
    val currentBotId: StateFlow<String> = _currentBotId

    /** 每个机器人最近一次连接状态，服务用它拼一条聚合通知。 */
    private val _statuses = MutableStateFlow<Map<String, GatewayStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, GatewayStatus>> = _statuses.asStateFlow()

    init {
        // 老用户升级：目录为空时把历史单机器人收编成「默认机器人」。
        // 不必把旧凭证搬来搬去——默认 id 直接复用旧的 settings / qbot_secrets 文件。
        val legacy = registry.migrateLegacyIfEmpty()
        if (legacy != null) {
            logger.i(TAG, "检测到历史单机器人配置，已转为默认机器人")
            _accounts.value = registry.accounts()
            _currentBotId.value = registry.currentId()
        }
    }

    // ---------- 当前机器人 ----------

    val currentAccount: BotAccount
        get() = registry.account(_currentBotId.value) ?: fallbackAccount()

    val currentInstance: BotInstance
        get() = instanceFor(_currentBotId.value)

    val currentSettings: SettingsRepository
        get() = currentInstance.settings

    val currentDatabase: AppDatabase
        get() = currentInstance.database

    val currentController: BotController
        get() = currentInstance.controller

    fun select(id: String) {
        if (registry.account(id) == null) return
        registry.setCurrentId(id)
        _currentBotId.value = id
        // 选中即准备实例，界面立刻能读到它的人格与日志
        runCatching { instanceFor(id) }
    }

    // ---------- 实例生命周期 ----------

    fun instanceFor(id: String): BotInstance =
        synchronized(lock) {
            instances.getOrPut(id) { create(id) }
        }

    private fun create(id: String): BotInstance {
        val account = registry.account(id) ?: fallbackAccount()
        val settings = settingsFor(id)
        val database = AppDatabase.create(appContext, databaseName(id))
        val controller = BotController(appContext, database, settings, logger)
        forwards[id] =
            scope.launch {
                controller.status.collect { status ->
                    _statuses.update { it + (id to status) }
                }
            }
        logger.i(TAG, "已加载机器人「${account.name}」")
        return BotInstance(account, settings, database, controller)
    }

    /** 默认机器人沿用历史库文件名，其他机器人各自一个文件。 */
    private fun databaseName(id: String): String =
        if (id == BotRegistry.DEFAULT_ID) "qbot.db" else "qbot-$id.db"

    private fun settingsFileName(id: String): String =
        if (id == BotRegistry.DEFAULT_ID) "settings" else "settings_$id"

    private fun secretFileName(id: String): String =
        if (id == BotRegistry.DEFAULT_ID) "qbot_secrets" else "qbot_secrets_$id"

    /** 取（或创建）某个机器人的设置仓库。 */
    fun settingsFor(id: String): SettingsRepository =
        synchronized(lock) {
            settingsCache.getOrPut(id) {
                SettingsRepository(appContext, id.takeUnless { it == BotRegistry.DEFAULT_ID })
            }
        }

    // ---------- 账号增删改 ----------

    fun addBot(
        name: String,
        appId: String,
        clientSecret: String,
    ): BotAccount {
        val account =
            BotAccount(
                id = UUID.randomUUID().toString(),
                name = name.trim().ifBlank { "未命名机器人" },
                enabled = true,
                createdAt = System.currentTimeMillis(),
            )
        registry.upsert(account)
        // 新机器人有自己的加密设置文件；凭证在实例创建前写入，启动时直接可用
        settingsFor(account.id).saveCredentials(appId, clientSecret)
        refreshAccounts()
        if (running) runCatching { instanceFor(account.id).controller.start() }
        return account
    }

    fun renameBot(
        id: String,
        name: String,
    ) {
        val account = registry.account(id) ?: return
        registry.upsert(account.copy(name = name.trim().ifBlank { account.name }))
        refreshAccounts()
    }

    fun setBotEnabled(
        id: String,
        enabled: Boolean,
    ) {
        val account = registry.account(id) ?: return
        registry.upsert(account.copy(enabled = enabled))
        val instance = synchronized(lock) { instances[id] }
        if (!enabled) {
            instance?.controller?.stop()
        } else if (running) {
            instanceFor(id).controller.start()
        }
        refreshAccounts()
    }

    /**
     * 移除机器人：停连接、关库、删掉它自己的数据文件。
     *
     * 只删这个机器人自己的文件（库 + 设置 + 加密凭证），不碰其他机器人。
     * 默认机器人也会被一并清掉——这是用户明确要求的删除。
     */
    fun removeBot(id: String) {
        // 至少留一个：删光后界面没有「当前机器人」可用，人格与记忆也无处归属
        if (registry.accounts().size <= 1) return
        val instance = synchronized(lock) { instances.remove(id) }
        forwards.remove(id)?.cancel()
        _statuses.update { it - id }

        instance?.controller?.close()
        runCatching { instance?.database?.close() }

        runCatching {
            val db = appContext.getDatabasePath(databaseName(id))
            listOf("", "-wal", "-shm").forEach { suffix -> File(db.path + suffix).delete() }
        }
        // 设置缓存、设置文件与加密凭证全部清掉；删除是不可逆操作
        synchronized(lock) { settingsCache.remove(id) }
        SettingsRepository.forget(id.takeUnless { it == BotRegistry.DEFAULT_ID })
        runCatching { appContext.preferencesDataStoreFile(settingsFileName(id)).delete() }
        runCatching {
            listOf(secretFileName(id), "${secretFileName(id)}_keys").forEach { name ->
                appContext.deleteSharedPreferences(name)
            }
        }

        registry.remove(id)
        val next = registry.currentId()
        _currentBotId.value = next
        refreshAccounts()
        logger.i(TAG, "已移除机器人 $id")
    }

    /** AppID 从该机器人自己的加密设置里读，避免目录与设置两处不同步。 */
    fun appIdOf(id: String): String =
        runCatching { settingsFor(id).credentials().appId }.getOrDefault("")

    // ---------- 运行控制 ----------

    /** 启动所有启用的机器人；未启用的保持待机。 */
    fun startAll() {
        running = true
        registry.accounts().filter { it.enabled }.forEach { account ->
            runCatching { instanceFor(account.id).controller.start() }
                .onFailure { logger.w(TAG, "启动「${account.name}」失败：${it.message}") }
        }
    }

    fun stopAll() {
        running = false
        synchronized(lock) { instances.toList() }.forEach { (_, instance) ->
            runCatching { instance.controller.stop() }
        }
    }

    fun restartAll() {
        stopAll()
        startAll()
    }

    /**
     * 改完某个机器人的凭证后重连它。
     *
     * 服务没在跑或该机器人被停用时只落配置，不偷偷把它拉起来。
     */
    fun restartBot(id: String) {
        if (!running) return
        if (registry.account(id)?.enabled != true) return
        runCatching { instanceFor(id).controller.restart() }
            .onFailure { logger.w(TAG, "重连机器人 $id 失败：${it.message}") }
    }

    fun onNetworkRestored() {
        synchronized(lock) { instances.toList() }.forEach { (_, instance) ->
            if (instance.controller.isRunning()) instance.controller.onNetworkRestored()
        }
    }

    /** 看门狗：把异常掉线但本该运行的机器人拉起来。 */
    fun reviveOffline() {
        if (!running) return
        registry.accounts().filter { it.enabled }.forEach { account ->
            val instance = synchronized(lock) { instances[account.id] } ?: instanceFor(account.id)
            if (!instance.controller.isRunning()) {
                runCatching { instance.controller.start() }
            }
        }
    }

    /** 开机自启判断：至少有一个启用的机器人打开了自启。 */
    suspend fun anyAutoStart(): Boolean =
        registry.accounts()
            .filter { it.enabled }
            .any { account ->
                runCatching { settingsFor(account.id).autoStart.first() }.getOrDefault(true)
            }

    private fun refreshAccounts() {
        _accounts.value = registry.accounts()
        if (registry.account(_currentBotId.value) == null) {
            _currentBotId.value = registry.currentId()
        }
    }

    private fun fallbackAccount(): BotAccount {
        val id = registry.currentId()
        return registry.account(id)
            ?: BotAccount(id = BotRegistry.DEFAULT_ID, name = "默认机器人")
    }

    private companion object {
        const val TAG = "BotManager"
    }
}
