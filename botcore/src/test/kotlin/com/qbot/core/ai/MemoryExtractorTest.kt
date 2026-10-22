package com.qbot.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆提取器测试。
 *
 * 重点覆盖「宁缺毋滥」的边界：误报比漏报更伤体验，
 * 因此问句、代词、身份词、超长内容都必须被拒绝。
 */
class MemoryExtractorTest {
    private val extractor = MemoryExtractor()

    // ---------- extractName ----------

    @Test
    fun `叫我句式提取自称名`() {
        assertEquals("小明", extractor.extractName("叫我小明"))
        assertEquals("阿伟", extractor.extractName("大家好，叫我阿伟"))
    }

    @Test
    fun `昵称句式提取自称名`() {
        assertEquals("测试_user-1", extractor.extractName("我的昵称是测试_user-1"))
        assertEquals("小美", extractor.extractName("我的名字是小美"))
        assertEquals("阿珍", extractor.extractName("昵称阿珍"))
    }

    @Test
    fun `自称名剥掉结尾语气词`() {
        assertEquals("小明", extractor.extractName("叫我小明呀"))
        assertEquals("阿伟", extractor.extractName("叫我阿伟啦"))
    }

    @Test
    fun `自称名截到标点`() {
        assertEquals("小明", extractor.extractName("叫我小明，你好"))
        assertEquals("小明", extractor.extractName("叫我小明。"))
    }

    @Test
    fun `身份词不会被误当成名字`() {
        assertNull(extractor.extractName("我是学生"))
        assertNull(extractor.extractName("我是管理员"))
        assertNull(extractor.extractName("我是大佬"))
        assertNull(extractor.extractName("我是谁"))
    }

    @Test
    fun `过短或非法字符的自称名被拒绝`() {
        assertNull(extractor.extractName("叫我a")) // 单字符
        assertNull(extractor.extractName("叫我@小明")) // 非法字符
        assertNull(extractor.extractName("叫我十三四十五六七八九十一十二")) // 超长
    }

    @Test
    fun `超长消息不提取自称名`() {
        assertNull(extractor.extractName("叫我" + "啊".repeat(30)))
    }

    @Test
    fun `普通对话不产生自称名`() {
        assertNull(extractor.extractName("今天天气真好"))
        assertNull(extractor.extractName(""))
    }

    // ---------- extractFacts ----------

    @Test
    fun `喜欢与讨厌被提取为偏好`() {
        val facts = extractor.extractFacts("我喜欢打篮球")
        assertEquals(1, facts.size)
        assertEquals("喜欢打篮球", facts[0].text)
        assertEquals(FactKind.PREFERENCE, facts[0].kind)
    }

    @Test
    fun `否定喜欢优先于肯定喜欢`() {
        val facts = extractor.extractFacts("我不喜欢香菜")
        assertEquals(1, facts.size)
        assertEquals("不喜欢香菜", facts[0].text)
    }

    @Test
    fun `程度副词被归一化`() {
        val facts = extractor.extractFacts("我特别喜欢猫")
        assertEquals("喜欢猫", facts[0].text)
    }

    @Test
    fun `愿望被提取为偏好`() {
        val facts = extractor.extractFacts("我想学吉他")
        assertEquals("想学吉他", facts[0].text)
    }

    @Test
    fun `进行中的状态被提取为资料`() {
        val facts = extractor.extractFacts("我在备考研究生")
        assertEquals(1, facts.size)
        assertEquals("在备考研究生", facts[0].text)
        assertEquals(FactKind.PROFILE, facts[0].kind)
    }

    @Test
    fun `居住地与宠物被提取`() {
        assertEquals("住在北京", extractor.extractFacts("我住在北京")[0].text)
        assertEquals("养了一只猫", extractor.extractFacts("我养了一只猫")[0].text)
    }

    @Test
    fun `我的X是句式被提取`() {
        val facts = extractor.extractFacts("我的生日是5月1日")
        assertEquals("生日是5月1日", facts[0].text)
    }

    @Test
    fun `问句一律不提取`() {
        assertTrue(extractor.extractFacts("你喜欢猫吗").isEmpty())
        assertTrue(extractor.extractFacts("你喜欢什么呢").isEmpty())
        assertTrue(extractor.extractFacts("你喜欢猫？").isEmpty())
        assertTrue(extractor.extractFacts("我想问什么").isEmpty().not()) // 陈述句照常提取
    }

    @Test
    fun `含代词的捕获被丢弃`() {
        assertTrue(extractor.extractFacts("我喜欢你").isEmpty())
    }

    @Test
    fun `单条消息最多两条事实`() {
        val facts =
            extractor.extractFacts("我喜欢篮球而且我想学吉他，还养了只狗")
        assertTrue("最多两条，实际 ${facts.size}", facts.size <= 2)
    }

    @Test
    fun `我是程序员这类角色陈述被提取`() {
        val facts = extractor.extractFacts("我是程序员")
        assertEquals(1, facts.size)
        assertEquals("是程序员", facts[0].text)
        assertEquals(FactKind.PROFILE, facts[0].kind)
    }

    @Test
    fun `普通闲聊不产生事实`() {
        assertTrue(extractor.extractFacts("今天天气真好").isEmpty())
        assertTrue(extractor.extractFacts("哈哈哈哈").isEmpty())
    }

    @Test
    fun `超长消息不提取事实`() {
        assertTrue(extractor.extractFacts("我喜欢" + "球".repeat(100)).isEmpty())
    }
}
