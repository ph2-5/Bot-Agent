package com.qbot.core.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 成长节奏的边界值测试。
 *
 * 这些数字直接决定"它多久自己改一次"——错一个，要么永远不长，
 * 要么一天改八次把人格改花。
 */
class PersonaGrowthPolicyTest {
    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun state(
        stage: PersonaStage,
        frozen: Boolean = false,
        growthCount: Int = 0,
        birthAt: Long = now - 10 * day,
        lastGrownAt: Long = now - 2 * day,
    ) = PersonaGrowthState(
        stage = stage,
        frozen = frozen,
        growthCount = growthCount,
        birthAt = birthAt,
        lastGrownAt = lastGrownAt,
    )

    @Test
    fun `胚胎未攒够消息不破壳`() {
        // birthAt 必须在 30 分钟内，否则会走「等够了时间」的快速通道
        val s = state(PersonaStage.EMBRYO, birthAt = now - 10 * 60_000L)
        assertEquals(GrowthAction.NONE, PersonaGrowthPolicy.next(s, 59, now))
        assertEquals(GrowthAction.BIRTH, PersonaGrowthPolicy.next(s, 60, now))
    }

    @Test
    fun `胚胎可以用时间换消息量`() {
        // 冷清群：消息不多，但等够 30 分钟也可以破壳
        val young = state(PersonaStage.EMBRYO, birthAt = now - 29 * 60_000L)
        assertEquals(GrowthAction.NONE, PersonaGrowthPolicy.next(young, 20, now))

        val old = state(PersonaStage.EMBRYO, birthAt = now - 31 * 60_000L)
        assertEquals(GrowthAction.BIRTH, PersonaGrowthPolicy.next(old, 20, now))
    }

    @Test
    fun `成长期按消息量与间隔双门槛`() {
        val fresh = state(PersonaStage.GROWING, lastGrownAt = now - 12 * hour)
        assertEquals(GrowthAction.GROW, PersonaGrowthPolicy.next(fresh, 150, now))
        assertEquals(GrowthAction.NONE, PersonaGrowthPolicy.next(fresh, 149, now))

        val tooSoon = state(PersonaStage.GROWING, lastGrownAt = now - 11 * hour)
        assertEquals(GrowthAction.NONE, PersonaGrowthPolicy.next(tooSoon, 500, now))
    }

    @Test
    fun `长时间没动静也会长一次`() {
        val idle = state(PersonaStage.GROWING, lastGrownAt = now - 7 * day)
        assertEquals(GrowthAction.GROW, PersonaGrowthPolicy.next(idle, 40, now))

        val notYet = state(PersonaStage.GROWING, lastGrownAt = now - 6 * day)
        assertEquals(GrowthAction.NONE, PersonaGrowthPolicy.next(notYet, 40, now))
    }

    @Test
    fun `成熟期间隔翻倍`() {
        val at12h = state(PersonaStage.MATURE, growthCount = 10, lastGrownAt = now - 12 * hour)
        assertEquals(
            "成熟后 12 小时不够，必须 24 小时",
            GrowthAction.NONE,
            PersonaGrowthPolicy.next(at12h, 500, now),
        )

        val at24h = state(PersonaStage.MATURE, growthCount = 10, lastGrownAt = now - 24 * hour)
        assertEquals(GrowthAction.GROW, PersonaGrowthPolicy.next(at24h, 500, now))
    }

    @Test
    fun `冻结或关开关时永不成长`() {
        val frozen = state(PersonaStage.GROWING, frozen = true, lastGrownAt = now - 30 * day)
        assertEquals(GrowthAction.NONE, PersonaGrowthPolicy.next(frozen, 5000, now))

        val disabled = PersonaGrowthConfig(enabled = false)
        val normal = state(PersonaStage.GROWING, lastGrownAt = now - 30 * day)
        assertEquals(GrowthAction.NONE, PersonaGrowthPolicy.next(normal, 5000, now, disabled))
    }

    @Test
    fun `手动人设不自动成长`() {
        val manual = state(PersonaStage.MANUAL, lastGrownAt = now - 30 * day)
        assertEquals(GrowthAction.NONE, PersonaGrowthPolicy.next(manual, 5000, now))
    }

    @Test
    fun `配置越界会被夹回合法范围`() {
        val normalized =
            PersonaGrowthConfig.normalize(
                enabled = true,
                birthMessages = 5,
                growMessages = 9999,
                growIntervalHours = 200,
            )
        assertEquals(PersonaGrowthConfig.MIN_BIRTH_MESSAGES, normalized.birthMessages)
        assertEquals(PersonaGrowthConfig.MAX_GROW_MESSAGES, normalized.growMessages)
        assertEquals(PersonaGrowthConfig.MAX_INTERVAL_HOURS, normalized.growIntervalHours)
    }
}
