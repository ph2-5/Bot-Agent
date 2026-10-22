package com.qbot.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 五段解析、相似度与段落 diff。 */
class PersonaSectionsTest {
    private val sample =
        """
        [身份] 我是群里的老哥。
        [性格] 热心、话多，见人有难处就想帮。
        [说话方式] 短句，先给结论。
        [兴趣] 装机、球赛。
        [边界] 不冒充群友。
        """.trimIndent()

    @Test
    fun `解析标准五段`() {
        val sections = PersonaSections.parse(sample)!!
        assertEquals("我是群里的老哥。", sections.identity)
        assertEquals("热心、话多，见人有难处就想帮。", sections.traits)
        assertEquals("短句，先给结论。", sections.voice)
        assertEquals("装机、球赛。", sections.interests)
        assertEquals("不冒充群友。", sections.bounds)
        assertTrue(sections.isComplete)
    }

    @Test
    fun `缺任一标记返回 null`() {
        assertNull(PersonaSections.parse(sample.replace("[边界]", "")))
        assertNull(PersonaSections.parse("随便一段没有人格标记的话"))
        assertNull(PersonaSections.parse(""))
    }

    @Test
    fun `标记重复出现时取最后一次`() {
        val raw =
            """
            [身份] 草稿身份
            [性格] 草稿
            [说话方式] 草稿
            [兴趣] 草稿
            [边界] 草稿
            [身份] 正式身份
            [性格] 正式性格
            [说话方式] 正式说话
            [兴趣] 正式兴趣
            [边界] 正式边界
            """.trimIndent()

        val sections = PersonaSections.parse(raw)!!
        assertEquals("正式身份", sections.identity)
        assertEquals("正式边界", sections.bounds)
    }

    @Test
    fun `渲染后可再次解析且内容一致`() {
        val original = PersonaSections.parse(sample)!!
        val again = PersonaSections.parse(original.render())!!
        assertEquals(original, again)
    }

    @Test
    fun `完全相同相似度为 1`() {
        val a = PersonaSections.parse(sample)!!
        assertEquals(1.0, PersonaSections.similarity(a, a), 0.0001)
    }

    @Test
    fun `完全不同的正文相似度很低`() {
        val a = PersonaSections.parse(sample)!!
        val b =
            PersonaSections(
                identity = a.identity,
                traits = "冷淡、惜字如金。",
                voice = "只说一个字。",
                interests = "机械表。",
                bounds = "不理人。",
            )
        assertTrue(PersonaSections.similarity(a, b) < 0.2)
    }

    @Test
    fun `只改一段时 diff 只报那一段`() {
        val a = PersonaSections.parse(sample)!!
        val b = a.copy(interests = "装机、球赛、外卖优惠。")
        assertEquals(listOf("兴趣"), b.changedFrom(a))
    }

    @Test
    fun `身份段变化也会被发现`() {
        val a = PersonaSections.parse(sample)!!
        val b = a.copy(identity = "我是另一个人。")
        assertEquals(listOf("身份"), b.changedFrom(a))
    }

    @Test
    fun `相似度不受身份段影响`() {
        // 身份段本来就不许改，不该让它的措辞影响"改动幅度"判定
        val a = PersonaSections.parse(sample)!!
        val b = a.copy(identity = "完全不同的身份描述。")
        assertEquals(1.0, PersonaSections.similarity(a, b), 0.0001)
    }
}
