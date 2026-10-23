package com.qbot.core.ai

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人设设定（梗 / 认识的人）测试。
 *
 * 抽取器只做一次 JSON 解析，重点验证：不会把写作规则混进来、
 * 上限有效、垃圾输出返回空，以及同名条目不会堆重复行。
 */
class LoreTest {
    /** 解析阶段不联网，客户端只是构造所需。 */
    private val extractor = PersonaLoreExtractor(LlmClient(http = OkHttpClient()))

    @Test
    fun `解析出认识的人与特殊说法`() {
        val raw =
            """
            {"people":[{"name":"老爹","note":"只能对他温顺，别队群友这样"}],
             "slang":[{"term":"大肥鱼","meaning":"雷点，叫了会炸毛","usage":"被挑衅时"}]}
            """.trimIndent()
        val entries = extractor.parse(raw, personaId = 7, now = 1000)
        assertEquals(2, entries.size)
        assertEquals(LoreKind.PERSON, entries[0].kind)
        assertEquals("老爹", entries[0].name)
        assertEquals(7L, entries[0].personaId ?: 0L)
        assertEquals(LoreKind.SLANG, entries[1].kind)
        assertTrue(entries[1].note.contains("雷点"))
        assertTrue(entries[1].note.contains("被挑衅时"))
        assertEquals(LoreSource.AI, entries[1].source)
    }

    @Test
    fun `代码块包裹的 JSON 也能解析`() {
        val raw = "```json\n{\"people\":[{\"name\":\"主人\",\"note\":\"听话\"}]}\n```"
        val entries = extractor.parse(raw, personaId = null, now = 1)
        assertEquals(1, entries.size)
        assertEquals("主人", entries.single().name)
    }

    @Test
    fun `垃圾输出返回空`() {
        assertTrue(extractor.parse("我觉得这个角色卡很有意思", null, 1).isEmpty())
        assertTrue(extractor.parse("{}", null, 1).isEmpty())
    }

    @Test
    fun `空名或空说明被丢弃且有条数上限`() {
        val raw =
            buildString {
                append("{\"people\":[")
                append("{\"name\":\"\",\"note\":\"空名字\"},")
                (1..20).forEach { append("{\"name\":\"人$it\",\"note\":\"说明\"},") }
                append("{\"name\":\"末\",\"note\":\"\"}]}")
            }
        val entries = extractor.parse(raw, null, 1)
        assertEquals(8, entries.size)
        assertTrue(entries.all { it.kind == LoreKind.PERSON })
    }

    @Test
    fun `渲染块为空时返回 null`() {
        assertNull(LoreRender.block(emptyList()))
        assertNull(LoreRender.block(listOf(LoreEntry(kind = LoreKind.PERSON, name = "", note = "x"))))
    }

    @Test
    fun `渲染块包含人与说法且有条数上限`() {
        val entries =
            (1..15).map {
                LoreEntry(id = it.toLong(), kind = LoreKind.PERSON, name = "人$it", note = "说明$it")
            }
        val block = LoreRender.block(entries)!!
        assertEquals(LoreRender.MAX_INJECTED, block.lines().count { it.startsWith("- ") })
        assertTrue(block.contains("人1"))
    }

    @Test
    fun `同名同类型同人格只保留一条`() =
        runBlocking {
            val store = InMemoryLoreStore()
            val first =
                store.save(LoreEntry(personaId = 1, kind = LoreKind.PERSON, name = "老爹", note = "温顺"))
            val second =
                store.save(LoreEntry(personaId = 1, kind = LoreKind.PERSON, name = "老爹", note = "更温顺"))
            assertEquals(first, second)
            assertEquals(1, store.all().size)
            assertEquals("更温顺", store.all().single().note)

            // 换个人格是另一条，不会互相覆盖
            store.save(LoreEntry(personaId = 2, kind = LoreKind.PERSON, name = "老爹", note = "不熟"))
            assertEquals(2, store.all().size)
        }

    @Test
    fun `按人格与群过滤可用设定`() =
        runBlocking {
            val store = InMemoryLoreStore()
            store.save(LoreEntry(personaId = null, kind = LoreKind.NOTE, name = "全局", note = "都适用"))
            store.save(LoreEntry(personaId = 1, kind = LoreKind.PERSON, name = "老爹", note = "人格1"))
            store.save(LoreEntry(personaId = 2, kind = LoreKind.PERSON, name = "主人", note = "人格2"))
            store.save(
                LoreEntry(
                    personaId = 1,
                    kind = LoreKind.SLANG,
                    name = "群专属",
                    note = "只在群里",
                    scopeGroupId = "g1",
                ),
            )

            val inGroup1 = store.forPersona(1, "g1")
            assertEquals(3, inGroup1.size)
            val inGroup2 = store.forPersona(1, "g2")
            assertEquals(2, inGroup2.size)
            assertTrue(inGroup2.none { it.name == "群专属" })
        }
}
