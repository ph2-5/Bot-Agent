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
        stage: PersonaStage = PersonaStage.GROWING,
    ): Long {
        val id =
            save(
                Persona(
                    name = name,
                    prompt = prompt,
                    kind = PersonaKind.PROFILE,
                    stage = stage,
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
            assertTrue("档案名应进入提示词", resolved.prompt.contains("「在用」"))
            assertTrue(resolved.prompt.contains("在用的正文"))
        }

    @Test
    fun `EMBRYO 正文为空时返回种子骨架`() =
        runTest {
            val store = InMemoryPersonaStore()
            store.addActive("还没破壳", "", seedId = "QUIET_OBSERVER", stage = PersonaStage.EMBRYO)

            val resolved = PersonaResolver(store).resolve(message())

            assertEquals(PersonaSource.PROFILE, resolved.source)
            assertEquals(PersonaStage.EMBRYO, resolved.stage)
            assertTrue("骨架必须含身份段标记", resolved.prompt.contains("[身份]"))
            assertTrue("应使用档案自己的种子", resolved.prompt.contains("话最少"))
            assertEquals("正常兜底不该标记异常", null, resolved.fallbackReason)
        }

    @Test
    fun `MANUAL 正文为空时用中性提示词，绝不掺种子`() =
        runTest {
            val store = InMemoryPersonaStore()
            store.addActive(
                "手写但忘了写正文",
                "",
                seedId = "SHARP_TONGUE",
                stage = PersonaStage.MANUAL,
            )

            val resolved = PersonaResolver(store).resolve(message())

            assertEquals(PersonaSource.PROFILE, resolved.source)
            assertEquals(PersonaStage.MANUAL, resolved.stage)
            assertEquals("MANUAL_EMPTY", resolved.fallbackReason)
            assertTrue(
                "应等于中性提示词",
                resolved.prompt.contains(PersonaGrowth.NEUTRAL_FALLBACK),
            )
            // 回归防线：手写档案绝不能被种子骨架污染
            assertFalse("不得出现骨架段标记", resolved.prompt.contains("[身份]"))
            assertFalse("不得出现种子特征", resolved.prompt.contains("毒舌"))
            assertFalse("不得出现种子特征", resolved.prompt.contains("热心"))
        }

    @Test
    fun `GROWING 正文为空按异常态处理，也不用种子`() =
        runTest {
            val store = InMemoryPersonaStore()
            store.addActive("长了一半丢了正文", "", stage = PersonaStage.GROWING)

            val resolved = PersonaResolver(store).resolve(message())

            assertEquals("EMPTY", resolved.fallbackReason)
            assertTrue(resolved.prompt.contains(PersonaGrowth.NEUTRAL_FALLBACK))
            assertFalse("不得回退成种子骨架", resolved.prompt.contains("[身份]"))
        }

    @Test
    fun `有激活档案时不调用兜底`() =
        runTest {
            val store = InMemoryPersonaStore()
            store.addActive("在用", "在用的正文")
            val resolver =
                PersonaResolver(store, fallbackProvider = { error("不该走到兜底") })

            assertTrue(resolver.promptFor(message()).contains("在用的正文"))
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
