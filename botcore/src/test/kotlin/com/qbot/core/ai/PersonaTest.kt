package com.qbot.core.ai

import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.MessageKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 人设解析测试：人 → 群 → 全局 三级优先级。 */
class PersonaTest {
    private fun message(
        senderId: String = "sender-1",
        targetId: String = "group-1",
        kind: MessageKind = MessageKind.GROUP_AT,
    ) = IncomingMessage(
        kind = kind,
        eventType = "GROUP_AT_MESSAGE_CREATE",
        messageId = "m",
        content = "hi",
        targetId = targetId,
        senderId = senderId,
        timestampMillis = 0,
        raw = buildJsonObject { },
    )

    @Test
    fun `人级人设压过群级与全局`() {
        val personas =
            listOf(
                Persona(id = 1, name = "全局", prompt = "全局人设", updatedAt = 100),
                Persona(id = 2, scopeId = "group-1", name = "群", prompt = "群人设", updatedAt = 100),
                Persona(id = 3, senderId = "sender-1", name = "人", prompt = "人的人设", updatedAt = 100),
            )
        assertEquals("人的人设", resolvePersona(message(), personas)?.prompt)
    }

    @Test
    fun `群级人设压过全局`() {
        val personas =
            listOf(
                Persona(id = 1, name = "全局", prompt = "全局人设", updatedAt = 100),
                Persona(id = 2, scopeId = "group-1", name = "群", prompt = "群人设", updatedAt = 50),
            )
        assertEquals("群人设", resolvePersona(message(), personas)?.prompt)
    }

    @Test
    fun `绑定别人的人设对本人无效`() {
        val personas =
            listOf(
                Persona(id = 1, senderId = "other", name = "别人的", prompt = "别人的"),
                Persona(id = 2, name = "全局", prompt = "全局人设"),
            )
        assertEquals("全局人设", resolvePersona(message(), personas)?.prompt)
    }

    @Test
    fun `绑定别的群的人设本群用不上`() {
        val personas =
            listOf(
                Persona(id = 1, scopeId = "group-2", name = "别群", prompt = "别群人设"),
            )
        assertNull(resolvePersona(message(), personas))
    }

    @Test
    fun `同粒度多条取最近更新的`() {
        val personas =
            listOf(
                Persona(id = 1, senderId = "sender-1", name = "旧", prompt = "旧的", updatedAt = 100),
                Persona(id = 2, senderId = "sender-1", name = "新", prompt = "新的", updatedAt = 200),
            )
        assertEquals("新的", resolvePersona(message(), personas)?.prompt)
    }

    @Test
    fun `空白提示词的人设被忽略`() {
        val personas = listOf(Persona(id = 1, name = "空白", prompt = "  "))
        assertNull(resolvePersona(message(), personas))
    }

    @Test
    fun `空列表返回 null`() {
        assertNull(resolvePersona(message(), emptyList()))
    }

    @Test
    fun `解析器按人设库优先 无则走兜底`() =
        runTest {
            val store = InMemoryPersonaStore()
            store.save(Persona(name = "群", prompt = "群人设", scopeId = "group-1"))

            val resolver = PersonaResolver(store, fallbackProvider = { "兜底人设" })
            assertEquals("群人设", resolver.promptFor(message()))
            assertEquals("兜底人设", resolver.promptFor(message(targetId = "group-9")))
        }

    @Test
    fun `解析器无人设也无兜底时返回种子骨架`() =
        runTest {
            val resolver = PersonaResolver(InMemoryPersonaStore())
            val resolved = resolver.resolve(message())
            assertEquals(PersonaSource.EMBRYO, resolved.source)
            assertEquals(PersonaStage.EMBRYO, resolved.stage)
            assertTrue(
                "骨架不能为空：空 system 会让模型退化成助手腔",
                resolved.prompt.contains("[身份]"),
            )
        }

    @Test
    fun `存储的增删查`() =
        runTest {
            val store = InMemoryPersonaStore()
            val id = store.save(Persona(name = "a", prompt = "p"))
            store.save(Persona(name = "b", prompt = "p2"))
            assertEquals(2, store.all().size)

            store.delete(id)
            assertEquals(1, store.all().size)
            assertEquals("b", store.all().first().name)
        }
}
