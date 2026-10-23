package com.qbot.core.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 成长触发条件的边界测试。
 *
 * 2.3.1 起这里只判断"够不够触发一次询问"，不再判断"该不该改"——
 * 后者交给模型（NO_CHANGE）。所以门槛只剩消息量。
 */
class PersonaGrowthPolicyTest {
    private fun state(
        stage: PersonaStage,
        frozen: Boolean = false,
        growthCount: Int = 0,
    ) = PersonaGrowthState(
        stage = stage,
        frozen = frozen,
        growthCount = growthCount,
        birthAt = 1_700_000_000_000L,
        lastGrownAt = 1_700_000_000_000L,
    )

    @Test
    fun `胚胎攒够二十条才破壳`() {
        val s = state(PersonaStage.EMBRYO)
        assertEquals(GrowthAction.NONE, PersonaGrowthPolicy.next(s, 19))
        assertEquals(GrowthAction.BIRTH, PersonaGrowthPolicy.next(s, 20))
    }

    @Test
    fun `成长期十条新消息就够问一次`() {
        val s = state(PersonaStage.GROWING)
        assertEquals(GrowthAction.NONE, PersonaGrowthPolicy.next(s, 9))
        assertEquals(GrowthAction.GROW, PersonaGrowthPolicy.next(s, 10))
    }

    @Test
    fun `成熟期门槛翻倍`() {
        val s = state(PersonaStage.MATURE, growthCount = 10)
        assertEquals(GrowthAction.NONE, PersonaGrowthPolicy.next(s, 19))
        assertEquals(GrowthAction.GROW, PersonaGrowthPolicy.next(s, 20))
    }

    @Test
    fun `冻结时不成长`() {
        assertEquals(
            GrowthAction.NONE,
            PersonaGrowthPolicy.next(state(PersonaStage.GROWING, frozen = true), 5000),
        )
    }

    @Test
    fun `手动人设不自动成长`() {
        assertEquals(
            GrowthAction.NONE,
            PersonaGrowthPolicy.next(state(PersonaStage.MANUAL), 5000),
        )
    }

    @Test
    fun `没有新消息时什么都不做`() {
        assertEquals(
            GrowthAction.NONE,
            PersonaGrowthPolicy.next(state(PersonaStage.GROWING), 0),
        )
    }
}
