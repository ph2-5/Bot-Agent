package com.qbot.core.ai

import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.MessageKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 人格解析优先级：覆盖层 → 激活档案 → 兜底 → 种子骨架。 */
class PersonaResolverTest {
    private fun message(
        senderId: String = "sender-1",
        targetId: String = "group-1",
    ) = IncomingMessage(
        kind = MessageKind.GROUP_AT,
        eventType = "GROUP_AT_MESSAGE_CREATE",
        messageId = "m",
        content = "hi",
        targetId = targetId,
        senderId = senderId,
        timestampMillis = 0,
        raw = buildJsonObject { },
    )

    private suspend fun InMemoryPersonaStore.addActive(
        name: String,
        prompt: String,
        seedId: String? = "SHARP_TONGUE",
    ): Long {
        val id =
            save(
                Persona(
                    name = name,
                    prompt = prompt,
                    kind = PersonaKind.PROFILE,
                    stage = PersonaStage.GROWING,
                    seedId = seedId,
                    active = true,
                ),
            )
        return id
    }

    @Test
    fun `覆盖层优先于激活档案`() =
        runTest {
            val store = InMemoryPersonaStore()
            store.addActive("毒舌损友", "档案正文：我是毒舌。")
            store.save(
                Persona(
                    name = "本群专用",
                    prompt = "覆盖正文：这个群我温柔。",
                    scopeId = "group-1",
                    kind = PersonaKind.OVERRIDE,
                ),
            )

            val resolved = PersonaResolver(store).resolve(message())

            assertEquals(PersonaSource.OVERRIDE, resolved.source)
            assertEquals("覆盖正文：这个群我温柔。", resolved.prompt)
        }

    @Test
    fun `未激活的档案不参与解析`() =
        runTest {
            val store = InMemoryPersonaStore()
            store.addActive("在用", "在用的正文")
            store.save(
                Persona(
                    name = "没启用",
                    prompt = "没启用的正文",
                    kind = PersonaKind.PROFILE,
                    stage = PersonaStage.MANUAL,
                    active = false,
                    // 更新时间更晚，若按"最近更新"会被误选
                    updatedAt = Long.MAX_VALUE,
                ),
            )

            val resolved = PersonaResolver(store).resolve(message())

            assertEquals(PersonaSource.PROFILE, resolved.source)
            assertEquals("在用的正文", resolved.prompt)
        }

    @Test
    fun `激活档案正文为空时返回种子骨架`() =
        runTest {
            val store = InMemoryPersonaStore()
            store.addActive("还没破壳", "", seedId = "QUIET_OBSERVER")

            val resolved = PersonaResolver(store).resolve(message())

            assertEquals(PersonaSource.PROFILE, resolved.source)
            assertEquals(PersonaStage.GROWING, resolved.stage)
            assertTrue("骨架必须含身份段标记", resolved.prompt.contains("[身份]"))
            assertTrue("应使用档案自己的种子", resolved.prompt.contains("话最少"))
        }

    @Test
    fun `有激活档案时不调用兜底`() =
        runTest {
            val store = InMemoryPersonaStore()
            store.addActive("在用", "在用的正文")
            val resolver =
                PersonaResolver(store, fallbackProvider = { error("不该走到兜底") })

            assertEquals("在用的正文", resolver.promptFor(message()))
        }

    @Test
    fun `无档案时旧兜底仍然可用`() =
        runTest {
            val resolver = PersonaResolver(InMemoryPersonaStore(), fallbackProvider = { "旧兜底" })
            val resolved = resolver.resolve(message())

            assertEquals(PersonaSource.LEGACY_FALLBACK, resolved.source)
            assertEquals("旧兜底", resolved.prompt)
        }

    @Test
    fun `完全没有档案时骨架永不为空`() =
        runTest {
            val resolved = PersonaResolver(InMemoryPersonaStore()).resolve(message())

            assertEquals(PersonaSource.EMBRYO, resolved.source)
            assertFalse("空 system 会让模型退化成客服腔", resolved.prompt.isBlank())
            assertTrue(
                "骨架必须能被解析成完整人格",
                PersonaSections.parse(resolved.prompt)?.isComplete == true,
            )
        }
}
