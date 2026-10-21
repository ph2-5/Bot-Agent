package com.qbot.core.model

/**
 * 事件订阅 Intents。
 *
 * 按位或组合后作为 Identify 的 intents 字段。
 * 只订阅真正需要的事件可以显著降低流量与电量开销——
 * 对 24 小时挂机的手机端来说这点尤其重要。
 *
 * 位定义以官方 Python SDK（botpy）的 Intents 为准：
 * https://github.com/tencent-connect/botpy/blob/master/botpy/flags.py
 *
 * ⚠️ 曾用错误的位定义（如把 GROUP_AT_MESSAGE_CREATE 写成 1<<16、
 * DIRECT_MESSAGE 写成 1<<4），导致 Identify 携带未定义位被服务端
 * 以 op9 Invalid Session 拒绝，表现为「连接中/鉴权中」死循环。
 * 修改本文件前务必对照官方定义核对每一位。
 */
object Intents {
    /** (1 << 0) 频道事件 */
    const val GUILDS = 1 shl 0

    /** (1 << 1) 频道成员事件 */
    const val GUILD_MEMBERS = 1 shl 1

    /** (1 << 9) 消息事件（频道内全部消息，仅私域机器人可订阅） */
    const val GUILD_MESSAGES = 1 shl 9

    /** (1 << 10) 消息表情表态事件 */
    const val GUILD_MESSAGE_REACTIONS = 1 shl 10

    /** (1 << 12) 私信消息事件（频道内私信） */
    const val DIRECT_MESSAGE = 1 shl 12

    /** (1 << 18) 开放论坛事件 */
    const val OPEN_FORUMS_EVENT = 1 shl 18

    /** (1 << 19) 音频或直播频道成员进出事件 */
    const val AUDIO_OR_LIVE_CHANNEL_MEMBER = 1 shl 19

    /** (1 << 25) 公域群/C2C 消息事件（群 @机器人 + QQ 消息列表单聊 + 加好友/退群等） */
    const val PUBLIC_MESSAGES = 1 shl 25

    /** (1 << 26) 互动事件（按钮/机器人"设置"等） */
    const val INTERACTION = 1 shl 26

    /** (1 << 27) 消息审核事件 */
    const val MESSAGE_AUDIT = 1 shl 27

    /** (1 << 28) 论坛事件（仅私域机器人可订阅） */
    const val FORUM_EVENT = 1 shl 28

    /** (1 << 29) 音频事件 */
    const val AUDIO_ACTION = 1 shl 29

    /** (1 << 30) 公域消息事件（只接收 @机器人 的频道消息） */
    const val PUBLIC_GUILD_MESSAGES = 1 shl 30

    /**
     * 默认订阅组合：公域机器人的全量事件（不含私域专属的
     * GUILD_MESSAGES 与 FORUM_EVENT）。
     *
     * 覆盖「群聊 @机器人」「单聊私聊」「按钮交互」「频道 @消息」，
     * 即官方 SDK 中 Intents.default() 的等价组合。
     *
     * 注意：所有事件订阅都需要平台申请；未申请时 Identify 会被服务端
     * 拒绝（op9 Invalid Session 或关闭码 4014），客户端会逐级降级。
     */
    const val DEFAULT =
        GUILDS or
            GUILD_MEMBERS or
            GUILD_MESSAGE_REACTIONS or
            DIRECT_MESSAGE or
            OPEN_FORUMS_EVENT or
            AUDIO_OR_LIVE_CHANNEL_MEMBER or
            PUBLIC_MESSAGES or
            INTERACTION or
            MESSAGE_AUDIT or
            AUDIO_ACTION or
            PUBLIC_GUILD_MESSAGES

    /** 只收消息：群 @、C2C 单聊、频道私信、公域 @消息，用于省电模式。 */
    const val MESSAGES_ONLY = PUBLIC_MESSAGES or DIRECT_MESSAGE or PUBLIC_GUILD_MESSAGES

    /**
     * 基础事件集合：官方默认授予、无需申请的四类事件。
     *
     * 参考官方文档：除 GUILDS / GUILD_MEMBERS / DIRECT_MESSAGE /
     * PUBLIC_GUILD_MESSAGES 之外的事件都需要经过申请才能订阅。
     * 当完整订阅被服务端拒绝时，客户端自动降级到此集合重连，
     * 保证机器人至少能上线，而不是卡死在重连循环里。
     */
    const val BASIC = GUILDS or GUILD_MEMBERS or DIRECT_MESSAGE or PUBLIC_GUILD_MESSAGES
}
