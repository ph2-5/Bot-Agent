package com.qbot.core.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「去 AI 味」约束测试。
 *
 * 三层来源的合并顺序、启停覆盖、注入上限都在这里定死——
 * 规则一旦重复注入，模型会开始「背规则」，比不注入更糟。
 */
class StyleRulesTest {
    @Test
    fun `内置规则都可执行且 id 唯一`() {
        val all = BuiltinStyleRules.ALL
        assertTrue(all.size >= 10)
        assertEquals(all.size, all.map { it.id }.distinct().size)
        assertTrue(all.all { it.text.isNotBlank() })
        assertTrue(all.all { it.source == StyleRuleSource.BUILTIN })
        assertTrue(all.all { it.enabled })
    }

    @Test
    fun `内置规则的启停覆盖不会改变顺序`() {
        val disabled = BuiltinStyleRules.LENGTH_SHORT
        val composer = StyleRuleComposer()
        val merged =
            composer.merge(
                listOf(
                    StyleRule(
                        id = disabled,
                        text = BuiltinStyleRules.ALL.first { it.id == disabled }.text,
                        category = StyleRuleCategory.LENGTH,
                        source = StyleRuleSource.BUILTIN,
                        enabled = false,
                    ),
                ),
            )
        assertEquals(BuiltinStyleRules.ALL.map { it.id }, merged.map { it.id })
        assertFalse(merged.first { it.id == disabled }.enabled)
    }

    @Test
    fun `用户规则排在内置之前且重复文本只留一条`() {
        val composer = StyleRuleComposer()
        val sameText = BuiltinStyleRules.ALL.first().text
        val merged =
            composer.merge(
                listOf(
                    StyleRule(id = "user.1", text = "不要用问句结尾", source = StyleRuleSource.USER),
                    StyleRule(id = "user.2", text = sameText, source = StyleRuleSource.USER),
                ),
            )
        assertEquals("不要用问句结尾", merged.first().text)
        assertEquals(merged.size, merged.map { it.text.trim() }.distinct().size)
    }

    @Test
    fun `activeRules 只保留启用的规则`() {
        val composer = StyleRuleComposer()
        val disabled = BuiltinStyleRules.TONE_NO_SERVICE
        val saved =
            listOf(
                StyleRule(
                    id = disabled,
                    text = BuiltinStyleRules.ALL.first { it.id == disabled }.text,
                    source = StyleRuleSource.BUILTIN,
                    enabled = false,
                ),
            )
        val active = composer.activeRules(saved)
        assertEquals(BuiltinStyleRules.ALL.size - 1, active.size)
        assertTrue(active.none { it.id == disabled })
    }

    @Test
    fun `插件提供的规则被标记为插件来源`() =
        runBlocking {
            val composer =
                StyleRuleComposer(
                    plugins =
                        listOf(
                            StyleRulePlugin {
                                listOf(
                                    StyleRule(
                                        id = "p.1",
                                        text = "插件规则",
                                        source = StyleRuleSource.USER,
                                    ),
                                )
                            },
                        ),
                )
            val merged = composer.rules()
            assertEquals(StyleRuleSource.PLUGIN, merged.first { it.id == "p.1" }.source)
        }

    @Test
    fun `注入块为空时返回 null`() {
        assertNull(StyleRender.block(emptyList()))
        assertNull(StyleRender.block(BuiltinStyleRules.ALL.map { it.copy(enabled = false) }))
    }

    @Test
    fun `注入块有条数上限且单条被截断`() {
        val rules =
            (1..30).map {
                StyleRule(id = "u.$it", text = "第 $it 条 " + "长".repeat(200))
            }
        val block = StyleRender.block(rules)!!
        assertEquals(StyleRender.MAX_INJECTED, block.lines().count { it.startsWith("- ") })
        assertTrue(block.lines().drop(1).all { it.length <= StyleRender.MAX_ONE_RULE + 2 })
    }

    @Test
    fun `内存存储可以停用与清空用户规则`() =
        runBlocking {
            val store = InMemoryStyleRuleStore()
            store.upsert(StyleRule(text = "用户规则", source = StyleRuleSource.USER))
            val saved = store.all().single()
            store.setEnabled(saved.id, false)
            assertFalse(store.all().single().enabled)

            store.clearUserRules()
            assertTrue(store.all().isEmpty())
        }
}
