package com.qbot.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 关系、心情与夸奖检测：数值要克制，态度不能被好感带偏。 */
class RelationTest {
    private val day = 24L * 3600 * 1000

    @Test
    fun `回一句只加熟识，不加好感`() {
        val r =
            RelationPolicy.apply(
                current = null,
                userId = "u1",
                displayName = "小明",
                event = RelationEvent.SPOKE,
                at = 1000,
            )
        assertEquals(2, r.familiarity)
        assertEquals(0, r.affinity)
        assertEquals(1, r.interactionCount)
        assertEquals("小明", r.displayName)
    }

    @Test
    fun `被夸加好感，被骂扣得更多`() {
        val praised =
            RelationPolicy.apply(null, "u1", null, RelationEvent.PRAISED, 1)
        val insulted =
            RelationPolicy.apply(praised, "u1", null, RelationEvent.INSULTED, 2)
        assertEquals(5, praised.affinity)
        assertEquals(-3, insulted.affinity)
    }

    @Test
    fun `熟识封顶，好感有上下限`() {
        var r = PersonRelation(userId = "u1", familiarity = 99, affinity = 98)
        r = RelationPolicy.apply(r, "u1", null, RelationEvent.MENTIONED, 1)
        assertEquals(100, r.familiarity)
        r = RelationPolicy.apply(r.copy(affinity = 98), "u1", null, RelationEvent.PRAISED, 1)
        assertEquals(100, r.affinity)
        r = RelationPolicy.apply(r.copy(affinity = -97), "u1", null, RelationEvent.INSULTED, 1)
        assertEquals(-100, r.affinity)
    }

    @Test
    fun `一周内不衰减，一周后向中性回归`() {
        val r = PersonRelation(userId = "u1", familiarity = 50, affinity = -40, lastInteractionAt = 0)
        assertEquals(-40, RelationPolicy.decay(r, 6 * day).affinity)
        // 两周：回归 4 点，负值向 0 靠
        assertEquals(-36, RelationPolicy.decay(r, 14 * day).affinity)
        // 熟识不衰减：认识就是认识
        assertEquals(50, RelationPolicy.decay(r, 14 * day).familiarity)
    }

    @Test
    fun `好感为负时明确禁止冷落与阴阳`() {
        val text =
            RelationPolicy.describe(
                PersonRelation(userId = "u1", displayName = "小明", familiarity = 60, affinity = -40),
            )
        assertTrue("必须点名不许冷落", text.contains("不许冷落"))
        assertTrue(text.contains("小明"))
        assertTrue(text.contains("不太想主动"))
    }

    @Test
    fun `刚认识时禁止自来熟`() {
        val text =
            RelationPolicy.describe(
                PersonRelation(userId = "u1", displayName = "小明", familiarity = 5, affinity = 0),
            )
        assertTrue(text.contains("别自来熟"))
    }

    @Test
    fun `心情只在被夸被骂时变，聊天不改变心情`() {
        val base = BotMood(value = 50, updatedAt = 1)
        assertEquals(62, MoodPolicy.apply(base, RelationEvent.PRAISED, 2).value)
        assertEquals(30, MoodPolicy.apply(base, RelationEvent.INSULTED, 2).value)
        assertEquals(base, MoodPolicy.apply(base, RelationEvent.SPOKE, 2))
        assertEquals(base, MoodPolicy.apply(base, RelationEvent.MENTIONED, 2))
    }

    @Test
    fun `心情随时间回到中性`() {
        val angry = BotMood(value = 10, updatedAt = 0)
        val after = MoodPolicy.decay(angry, 10 * 3600_000L)
        assertEquals(20, after.value)
        assertEquals(BotMood.NEUTRAL, MoodPolicy.decay(angry, 100 * 3600_000L).value)
    }

    @Test
    fun `中性心情不注入任何话`() {
        assertNull(MoodPolicy.describe(BotMood(value = 50)))
    }

    @Test
    fun `低心情允许语气冲，但禁止迁怒`() {
        val text = MoodPolicy.describe(BotMood(value = 5))!!
        assertTrue(text.contains("不许骂人"))
        assertTrue(text.contains("迁怒"))
    }

    @Test
    fun `冲着机器人说的夸奖才算`() {
        assertTrue(PraiseDetector.isPraise("你好厉害", isDirected = true))
        assertTrue(PraiseDetector.isPraise("666", isDirected = true))
        assertTrue(!PraiseDetector.isPraise("你好厉害", isDirected = false))
    }

    @Test
    fun `长句与反问式骂人不算夸奖`() {
        val long =
            "今天这个会开得特别久，我觉得大家都很辛苦，但是有些事情还是要说清楚的厉害"
        assertTrue(!PraiseDetector.isPraise(long, isDirected = true))
        assertTrue(!PraiseDetector.isPraise("你牛逼什么啊", isDirected = true))
    }
}
