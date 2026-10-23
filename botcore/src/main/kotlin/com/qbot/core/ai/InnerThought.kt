package com.qbot.core.ai

/**
 * 一条「没说出口的话」。
 *
 * 思考模式打开时，模型会先在 `reasoning_content` 里想一遍再说。这段内容
 * 过去只当调试信息，用完就扔了——但它恰恰是拟人的核心资产：
 * 心里嫌烦、嘴上还是回了；心里想骂、说出口是"好好好"。人格就在这两者的差里。
 *
 * 明确三条边界：
 * 1. **绝不进对话上下文**。模型不该看见自己上一轮的内心，否则会越说越拧巴；
 * 2. 不发给群友，只落本地库，供复盘与后续的情绪 / 好感度建模；
 * 3. 思考模式关闭时为空，不产生记录——这也让"开思考"第一次有了产品理由，
 *    而不只是多烧 token。
 */
data class InnerThought(
    /** 落库后的行 id；未入库时为 0 */
    val id: Long = 0,
    /** 群 / 频道 openid；单聊时为对方的 openid */
    val groupId: String?,
    val senderId: String?,
    val senderName: String?,
    /** 说出口的那句 */
    val spoken: String,
    /** 没说出口的那部分 */
    val thought: String,
    val createdAt: Long,
)

/**
 * 内心戏落库出口。
 *
 * botcore 不认识 Room，由 Android 侧实现后注入；与 [ChimeTrainer] 同一套模式。
 */
interface InnerThoughtSink {
    suspend fun record(thought: InnerThought)
}

/** 一次 AI 回复：说出口的正文 + 没说出口的思考。 */
data class AiReply(
    val content: String,
    /** 思考模式关闭或模型没给思考时为 null */
    val reasoning: String? = null,
    /**
     * 这条回复想配的表情包地址（模型在末尾写了 [表情:URL] 标记）。
     *
     * 标记已从 [content] 里剥掉——正文是纯文字，图由调用方另行发送。
     * 上传失败时调用方静默放弃即可，不影响文字回复。
     */
    val memeUrl: String? = null,
)
