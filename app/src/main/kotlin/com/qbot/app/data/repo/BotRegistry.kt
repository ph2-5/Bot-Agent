package com.qbot.app.data.repo

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一个机器人账号（对应 QQ 开放平台的一个 AppID）。
 *
 * 这里只存「身份」，不存 secret：
 * 凭证仍由各自的 [SettingsRepository] 写进加密偏好，
 * 列表页要展示 AppID 时再向对应仓库读取。
 */
@Serializable
data class BotAccount(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
)

/**
 * 机器人账号目录。
 *
 * 存在普通 SharedPreferences 里：名称与开关不算机密，
 * 真正敏感的 AppID / ClientSecret / API Key 在各自的加密文件中。
 *
 * 关键约束：历史版本只有一个机器人，升级后必须原样变成「默认机器人」，
 * 继续使用 `qbot.db` / `settings` / `qbot_secrets` 这三个旧文件，
 * 否则老用户的人格与记忆会像丢了一样。
 */
class BotRegistry(context: Context) {
    companion object {
        /** 历史单机器人时代的固定 id：沿用旧库、旧设置文件。 */
        const val DEFAULT_ID = "default"

        private const val PREFS_FILE = "qbot_bots"
        private const val KEY_LIST = "accounts"
        private const val KEY_CURRENT = "current_id"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun accounts(): List<BotAccount> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<BotAccount>>(raw) }
            .getOrDefault(emptyList())
            .filter { it.id.isNotBlank() }
    }

    @Synchronized
    fun account(id: String): BotAccount? = accounts().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(account: BotAccount) {
        val list = accounts().toMutableList()
        val index = list.indexOfFirst { it.id == account.id }
        if (index >= 0) list[index] = account else list.add(account)
        write(list)
    }

    @Synchronized
    fun remove(id: String) {
        write(accounts().filterNot { it.id == id })
        if (currentId() == id) {
            prefs.edit().remove(KEY_CURRENT).apply()
        }
    }

    /**
     * 老用户升级：把历史单机器人转成默认账号。
     *
     * 只在目录为空时执行；用户主动删光机器人后不会又被凭空加回来。
     */
    @Synchronized
    fun migrateLegacyIfEmpty(name: String = "默认机器人"): BotAccount? {
        if (accounts().isNotEmpty()) return null
        val account =
            BotAccount(
                id = DEFAULT_ID,
                name = name,
                enabled = true,
                createdAt = System.currentTimeMillis(),
            )
        write(listOf(account))
        prefs.edit().putString(KEY_CURRENT, DEFAULT_ID).apply()
        return account
    }

    @Synchronized
    fun currentId(): String {
        val saved = prefs.getString(KEY_CURRENT, null)
        val list = accounts()
        return saved?.takeIf { id -> list.any { it.id == id } }
            ?: list.firstOrNull()?.id
            ?: DEFAULT_ID
    }

    @Synchronized
    fun setCurrentId(id: String) {
        prefs.edit().putString(KEY_CURRENT, id).apply()
    }

    private fun write(list: List<BotAccount>) {
        prefs.edit().putString(KEY_LIST, json.encodeToString(list)).apply()
    }
}
