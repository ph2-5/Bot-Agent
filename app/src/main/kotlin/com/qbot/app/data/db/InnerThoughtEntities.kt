package com.qbot.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 内心戏：模型没说出口的那段话。
 *
 * 单独建表而不是塞进 `room_messages`，有三个原因：
 * 1. 聊天记录会被 [com.qbot.core.ai.MemoryPipeline] 拿去拼上下文，
 *    内心混进去必然污染下一轮对话；
 * 2. 两边的保留策略不同——聊天窗口 200 条滚动，内心可以留更久；
 * 3. 它是未来"情绪 / 好感度"最直接的事件源，和消息不是一回事。
 */
@Entity(
    tableName = "inner_thoughts",
    indices = [Index("createdAt"), Index("groupId")],
)
data class InnerThoughtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 群 / 频道 openid；单聊时为对方 openid */
    val groupId: String? = null,
    val senderId: String? = null,
    val senderName: String? = null,
    /** 说出口的那句 */
    val spoken: String,
    /** 没说出口的那部分（已按 600 字截断） */
    val thought: String,
    val createdAt: Long,
)

@Dao
interface InnerThoughtDao {
    @Insert
    suspend fun insert(entity: InnerThoughtEntity): Long

    @Query("SELECT * FROM inner_thoughts ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<InnerThoughtEntity>>

    @Query("SELECT * FROM inner_thoughts ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recentOnce(limit: Int): List<InnerThoughtEntity>

    /** 对某个人最近一次"没说出口的话"：判断"我上次是不是忍了他"用。 */
    @Query(
        "SELECT * FROM inner_thoughts WHERE senderId = :senderId " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1",
    )
    suspend fun lastFor(senderId: String): InnerThoughtEntity?

    /** 只留最近 [keep] 条，避免长期挂机把库撑大。 */
    @Query(
        "DELETE FROM inner_thoughts WHERE id NOT IN (" +
            "SELECT id FROM inner_thoughts ORDER BY createdAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM inner_thoughts")
    suspend fun clear()
}
