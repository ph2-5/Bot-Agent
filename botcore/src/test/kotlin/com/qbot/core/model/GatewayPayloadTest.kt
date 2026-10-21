package com.qbot.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayPayloadTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @Test
    fun `Identify 报文结构符合官方约定`() {
        val payload = buildIdentify("QQBot abc123", Intents.DEFAULT, listOf(0, 1))

        assertEquals(OpCode.IDENTIFY, payload.op)
        val d = payload.d!!.jsonObject
        assertEquals("QQBot abc123", d["token"]!!.jsonPrimitive.content)
        assertEquals(Intents.DEFAULT, d["intents"]!!.jsonPrimitive.content.toInt())

        // shard 必须是数组且长度为 2
        val shard = d["shard"] as JsonArray
        assertEquals(2, shard.size)
        assertEquals(0, shard[0].jsonPrimitive.content.toInt())
        assertEquals(1, shard[1].jsonPrimitive.content.toInt())
    }

    @Test
    fun `心跳报文在首次连接时携带 null 序号`() {
        val payload = buildHeartbeat(null)
        assertEquals(OpCode.HEARTBEAT, payload.op)
        assertTrue("首次心跳 d 应为 null", payload.d is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `心跳报文携带最新事件序号`() {
        val payload = buildHeartbeat(251)
        assertEquals(OpCode.HEARTBEAT, payload.op)
        assertEquals(251, payload.d!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `Resume 报文携带会话与序号`() {
        val payload = buildResume("QQBot t", "session-42", 1337)
        assertEquals(OpCode.RESUME, payload.op)
        val d = payload.d!!.jsonObject
        assertEquals("QQBot t", d["token"]!!.jsonPrimitive.content)
        assertEquals("session-42", d["session_id"]!!.jsonPrimitive.content)
        assertEquals(1337, d["seq"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `解析 Hello 心跳周期`() {
        val raw = """{"op":10,"d":{"heartbeat_interval":45000}}"""
        val payload = json.decodeFromString<GatewayPayload>(raw)
        assertEquals(45_000L, parseHeartbeatInterval(payload))
    }

    @Test
    fun `Hello 缺少心跳周期时回落到默认 45 秒`() {
        val payload = json.decodeFromString<GatewayPayload>("""{"op":10,"d":{}}""")
        assertEquals(45_000L, parseHeartbeatInterval(payload))
    }

    @Test
    fun `解析 READY 的 session_id`() {
        val raw =
            """
            {"op":0,"s":1,"t":"READY","d":{"version":1,"session_id":"082ee18c","user":{"id":"1","username":"bot","bot":true}}}
            """.trimIndent()
        val payload = json.decodeFromString<GatewayPayload>(raw)
        assertEquals("082ee18c", parseSessionId(payload))
        assertEquals(1, payload.s)
        assertEquals("READY", payload.t)

        val user = json.decodeFromJsonElement<ReadyUser>(payload.d!!.jsonObject["user"]!!)
        assertEquals("bot", user.username)
        assertTrue(user.bot)
    }

    @Test
    fun `未知 opcode 不会导致解析崩溃`() {
        val payload = json.decodeFromString<GatewayPayload>("""{"op":9999,"d":{"unknown_field":true}}""")
        assertEquals(9999, payload.op)
        assertNull(payload.t)
    }
}
