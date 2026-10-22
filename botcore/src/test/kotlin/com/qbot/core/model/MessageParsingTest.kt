package com.qbot.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun element(raw: String) = json.parseToJsonElement(raw)

    @Test
    fun `解析群聊艾特消息`() {
        // 官方 botpy GroupMessage：author.member_openid 为群里每个人的稳定标识
        val raw =
            """
            {
              "id": "msg-001",
              "group_openid": "group-abc",
              "author": {"member_openid": "user-888"},
              "content": "<@!100200> 今天天气怎么样",
              "timestamp": "1700000000"
            }
            """.trimIndent()

        val msg = parseIncomingMessage(EventType.GROUP_AT_MESSAGE_CREATE, element(raw))

        assertEquals(MessageKind.GROUP_AT, msg!!.kind)
        assertEquals("msg-001", msg.messageId)
        assertEquals("group-abc", msg.targetId)
        assertEquals("user-888", msg.senderId)
    }

    @Test
    fun `群聊发送者回退到旧字段 op_member_openid`() {
        // 兼容极少数历史报文：顶层 op_member_openid
        val raw = """{"id":"1","group_openid":"g","op_member_openid":"user-legacy","content":"hi","timestamp":"1"}"""
        val msg = parseIncomingMessage(EventType.GROUP_AT_MESSAGE_CREATE, element(raw))!!
        assertEquals("user-legacy", msg.senderId)
    }

    @Test
    fun `群聊无 author 时发送者为 null 不崩溃`() {
        val msg =
            parseIncomingMessage(
                EventType.GROUP_AT_MESSAGE_CREATE,
                element("""{"id":"1","group_openid":"g","content":"hi","timestamp":"1"}"""),
            )!!
        assertNull(msg.senderId)
    }

    @Test
    fun `频道消息取 author_id 与 member_nick`() {
        val raw =
            """
            {
              "id": "g-1",
              "channel_id": "chan-1",
              "author": {"id": "author-42", "username": "someone"},
              "member": {"nick": "小明"},
              "content": "大家好",
              "timestamp": "1700000000"
            }
            """.trimIndent()
        val msg = parseIncomingMessage(EventType.MESSAGE_CREATE, element(raw))!!
        assertEquals(MessageKind.GUILD, msg.kind)
        assertEquals("author-42", msg.senderId)
        assertEquals("小明", msg.senderName)
    }

    @Test
    fun `艾特前缀被正确剥离`() {
        val raw = """{"id":"1","group_openid":"g","author":{"member_openid":"u"},"content":"<@!100200> 你好呀","timestamp":"1700000000"}"""
        val msg = parseIncomingMessage(EventType.GROUP_AT_MESSAGE_CREATE, element(raw))!!

        assertEquals("<@!100200> 你好呀", msg.content)
        assertEquals("你好呀", msg.cleanText("100200"))
    }

    @Test
    fun `多个艾特也会被剥离`() {
        val msg =
            parseIncomingMessage(
                EventType.GROUP_AT_MESSAGE_CREATE,
                element("""{"id":"1","group_openid":"g","content":"<@!1> <@!2> 开始抽奖","timestamp":"1"}"""),
            )!!
        assertEquals("开始抽奖", msg.cleanText())
    }

    @Test
    fun `解析单聊消息`() {
        val raw =
            """
            {
              "id": "c2c-001",
              "author": {"id": "author-id", "user_openid": "openid-777"},
              "content": "在吗",
              "timestamp": "1700000000"
            }
            """.trimIndent()

        val msg = parseIncomingMessage(EventType.C2C_MESSAGE_CREATE, element(raw))!!

        assertEquals(MessageKind.C2C, msg.kind)
        assertEquals("c2c-001", msg.messageId)
        assertEquals("openid-777", msg.targetId)
        // 官方报文：C2C 发送者取 author.user_openid（与 botpy 一致），
        // author.id 只是兜底
        assertEquals("openid-777", msg.senderId)
        assertEquals("在吗", msg.content)
    }

    @Test
    fun `非消息事件返回 null`() {
        assertNull(parseIncomingMessage("GUILD_CREATE", element("""{"id":"1"}""")))
        assertNull(parseIncomingMessage(EventType.READY, element("""{"session_id":"s"}""")))
    }

    @Test
    fun `原始报文被保留以便读取未建模字段`() {
        val msg =
            parseIncomingMessage(
                EventType.GROUP_AT_MESSAGE_CREATE,
                element("""{"id":"1","group_openid":"g","content":"hi","timestamp":"1","new_future_field":42}"""),
            )!!
        assertEquals(42, msg.raw["new_future_field"].toString().toInt())
    }

    @Test
    fun `秒级时间戳被转换为毫秒`() {
        val msg =
            parseIncomingMessage(
                EventType.GROUP_AT_MESSAGE_CREATE,
                element("""{"id":"1","group_openid":"g","content":"hi","timestamp":"1700000000"}"""),
            )!!
        assertEquals(1_700_000_000_000L, msg.timestampMillis)
    }

    @Test
    fun `毫秒级时间戳不会被二次放大`() {
        val msg =
            parseIncomingMessage(
                EventType.GROUP_AT_MESSAGE_CREATE,
                element("""{"id":"1","group_openid":"g","content":"hi","timestamp":1700000000000}"""),
            )!!
        assertEquals(1_700_000_000_000L, msg.timestampMillis)
    }

    @Test
    fun `空内容不会产生崩溃`() {
        val msg =
            parseIncomingMessage(
                EventType.GROUP_AT_MESSAGE_CREATE,
                element("""{"id":"1","group_openid":"g","timestamp":"1"}"""),
            )!!
        assertEquals("", msg.content)
        assertEquals("", msg.cleanText())
    }

    @Test
    fun `解析群内全量消息并取真实昵称`() {
        // 官方 GROUP_MESSAGE_CREATE（群主开启「接收所有消息」后推送）
        val raw =
            """
            {
              "id": "msg-full-1",
              "group_openid": "group-abc",
              "author": {
                "id": "u1",
                "member_openid": "user-888",
                "username": "小明",
                "member_role": "member"
              },
              "content": "今晚有人开黑吗",
              "timestamp": "1700000000",
              "mentions": []
            }
            """.trimIndent()

        val msg = parseIncomingMessage(EventType.GROUP_MESSAGE_CREATE, element(raw))

        assertEquals(MessageKind.GROUP_MESSAGE, msg!!.kind)
        assertEquals("group-abc", msg.targetId)
        assertEquals("user-888", msg.senderId)
        assertEquals("真实昵称应来自 author.username", "小明", msg.senderName)
        assertEquals("今晚有人开黑吗", msg.content)
    }

    @Test
    fun `全量消息缺少昵称时回退为 null`() {
        val msg =
            parseIncomingMessage(
                EventType.GROUP_MESSAGE_CREATE,
                element("""{"id":"1","group_openid":"g","author":{"member_openid":"u"},"content":"hi","timestamp":"1"}"""),
            )!!
        assertNull(msg.senderName)
    }

    @Test
    fun `全量与艾特消息共享同一群背景作用域`() {
        val at =
            parseIncomingMessage(
                EventType.GROUP_AT_MESSAGE_CREATE,
                element("""{"id":"1","group_openid":"g","author":{"member_openid":"u"},"content":"hi","timestamp":"1"}"""),
            )!!
        val full =
            parseIncomingMessage(
                EventType.GROUP_MESSAGE_CREATE,
                element("""{"id":"2","group_openid":"g","author":{"member_openid":"u"},"content":"yo","timestamp":"1"}"""),
            )!!
        assertEquals(
            "同一条群背景应同时容纳 @ 消息与普通消息",
            com.qbot.core.ai.ConversationKeys.room(at),
            com.qbot.core.ai.ConversationKeys.room(full),
        )
    }
}
