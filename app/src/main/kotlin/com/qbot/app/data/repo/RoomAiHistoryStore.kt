package com.qbot.app.data.repo

import com.qbot.app.data.db.AiHistoryDao
import com.qbot.app.data.db.AiMessageEntity
import com.qbot.core.ai.AiHistoryStore
import com.qbot.core.ai.ChatMessage

/**
 * 基于 Room 的 AI 会话历史存储。
 *
 * 历史落库的意义：机器人重启、进程被杀后，
 * 群里的对话上下文不会丢失，体验上更像"同一个机器人"。
 */
class RoomAiHistoryStore(
    private val dao: AiHistoryDao,
) : AiHistoryStore {
    override suspend fun load(
        conversationKey: String,
        limit: Int,
    ): List<ChatMessage> =
        // 查询是倒序取的，转回正序才是真实的对话顺序
        dao
            .recent(conversationKey, limit)
            .asReversed()
            .map { ChatMessage(role = it.role, content = it.content) }

    override suspend fun append(
        conversationKey: String,
        message: ChatMessage,
    ) {
        dao.insert(
            AiMessageEntity(
                conversationKey = conversationKey,
                role = message.role,
                content = message.content,
                timestampMillis = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun clear(conversationKey: String) {
        dao.clear(conversationKey)
    }

    override suspend fun trim(
        conversationKey: String,
        keep: Int,
    ) {
        dao.trim(conversationKey, keep)
    }

    override suspend fun trimAll(keep: Int) {
        // 逐个会话清理：老版本 SQLite 没有窗口函数，拆成循环最稳妥
        dao.allKeys().forEach { key ->
            dao.trim(key, keep)
        }
    }
}
