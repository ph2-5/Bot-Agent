package com.qbot.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性格种子测试。
 *
 * 最重要的一条是 [性格必须命中插话偏置]：骨架里少了关键词，
 * `PersonaBias.personaBiasFor` 会恒返回 NEUTRAL，"性格影响要不要说话"
 * 这个能力会静默失效，而这种失效在运行时几乎看不出来。
 */
class PersonaSeedTest {
    @Test
    fun `每个种子都能生成完整五段骨架`() {
        PersonaSeed.entries.forEach { seed ->
            val skeleton = PersonaGrowth.embryoSkeleton(seed.name)
            val sections = PersonaSections.parse(skeleton)
            assertTrue("${seed.name} 的骨架解析失败", sections != null)
            assertTrue("${seed.name} 的骨架有空白段落", sections!!.isComplete)
            assertEquals(
                "${seed.name} 的身份段应来自种子",
                seed.identity,
                sections.identity,
            )
        }
    }

    @Test
    fun `性格必须命中插话偏置`() {
        PersonaSeed.entries.forEach { seed ->
            val skeleton = PersonaGrowth.embryoSkeleton(seed.name)
            assertEquals(
                "${seed.name} 的关键词没命中 PersonaBias，会导致性格对插话完全无效",
                seed.bias,
                PersonaBias.personaBiasFor(skeleton),
            )
        }
    }

    @Test
    fun `种子的骨架长度在守卫允许范围内`() {
        PersonaSeed.entries.forEach { seed ->
            val length = PersonaGrowth.embryoSkeleton(seed.name).length
            assertTrue(
                "${seed.name} 骨架过短（$length），可能被守卫拒绝",
                length >= PersonaGuard.MIN_TOTAL_CHARS,
            )
            assertTrue("${seed.name} 骨架过长（$length）", length <= PersonaGuard.MAX_TOTAL_CHARS)
        }
    }

    @Test
    fun `同一 seedId 两次生成逐字节相同`() {
        // 前缀缓存的前提：破壳前每一轮的 system 必须完全一致
        assertEquals(
            PersonaGrowth.embryoSkeleton("NERD"),
            PersonaGrowth.embryoSkeleton("NERD"),
        )
    }

    @Test
    fun `未知 seedId 回落到默认种子`() {
        assertEquals(
            PersonaGrowth.embryoSkeleton("WARM_BROTHER"),
            PersonaGrowth.embryoSkeleton("NOT_EXIST"),
        )
        assertEquals(PersonaSeed.WARM_BROTHER, PersonaSeed.of(null))
    }

    @Test
    fun `不同种子的骨架不一样`() {
        assertNotEquals(
            PersonaGrowth.embryoSkeleton("SHARP_TONGUE"),
            PersonaGrowth.embryoSkeleton("QUIET_OBSERVER"),
        )
    }

    @Test
    fun `随机抽取永远落在合法范围`() {
        repeat(50) {
            val seed = PersonaSeed.random()
            assertTrue(seed in PersonaSeed.entries)
        }
        // 边界值：0.0 取第一个，接近 1.0 取最后一个，不能越界
        assertEquals(PersonaSeed.entries.first(), PersonaSeed.random { 0.0 })
        assertEquals(PersonaSeed.entries.last(), PersonaSeed.random { 0.999999 })
        assertEquals(PersonaSeed.entries.last(), PersonaSeed.random { 1.0 })
    }
}
