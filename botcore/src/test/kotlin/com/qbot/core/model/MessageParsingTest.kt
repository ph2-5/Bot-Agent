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
        val raw =
            """
            {
              "id": "msg-001",
              "group_openid": "group-abc",
              "op_member_openid": "user-888",
              "content": "<@!100200> 今天天气怎么样",
              "timestamp": "1700000000"
            }
            """.trimIndent()

        val msg = parseIncomingMessage(EventType.GROUP_AT_MESSAGE_CREATE, element(raw))

        assertEquals(MessageKind.GROUP_AT, msg!!.kind)
        assertEquals("msg-001", msg.messageId)
        assertEquals("group-abc", msg.targetId)
        assertEquals("user-888", msg.senderId)
        assertEquals("user-888", msg.senderId)
    }

    @Test
    fun `艾特前缀被正确剥离`() {
        val raw = """{"id":"1","group_openid":"g","op_member_openid":"u","content":"<@!100200> 你好呀","timestamp":"1700000000"}"""
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
        assertEquals("author-id", msg.senderId)
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
}
