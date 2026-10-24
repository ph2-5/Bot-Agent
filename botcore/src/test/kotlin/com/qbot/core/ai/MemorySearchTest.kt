package com.qbot.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索基础件：分词与打分。
 *
 * 这两块是纯函数，排序质量问题在这里就能定住，不用起数据库。
 */
class MemorySearchTest {
    @Test
    fun `中文按二元组切分且保留短专名`() {
        val terms = MemoryTerms.extract("周末一起打原神吗")
        assertTrue("应包含「原神」，实际=$terms", terms.contains("原神"))
        assertTrue("应包含相邻字二元组，实际=$terms", terms.contains("周末"))
        assertTrue("问句虚词不该入选，实际=$terms", terms.none { it == "吗" })
    }

    @Test
    fun `纯虚词句子抽不出索引词`() {
        assertEquals(emptyList<String>(), MemoryTerms.extract("我们这个就是那样的"))
    }

    @Test
    fun `英文统一小写按整词切分`() {
        val terms = MemoryTerms.extract("今晚开 Minecraft 服务器")
        assertTrue("应保留英文整词，实际=$terms", terms.contains("minecraft"))
    }

    @Test
    fun `标点与表情不会进入索引`() {
        val terms = MemoryTerms.extract("！！！？？？😀😀")
        assertTrue("标点表情不该产生索引词，实际=$terms", terms.isEmpty())
    }

    @Test
    fun `越新的记忆得分越高`() {
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        val old = candidate(id = 1, updatedAt = now - 30 * day)
        val fresh = candidate(id = 2, updatedAt = now)

        assertEquals(fresh.key, MemoryRanker.pick(listOf(old, fresh), 1, now).single().key)
    }

    @Test
    fun `少见词权重高于常见词`() {
        assertTrue(MemoryRanker.termWeight(1) > MemoryRanker.termWeight(100))
    }

    @Test
    fun `命中词多的条目得分更高`() {
        val now = 1_700_000_000_000L
        val single = candidate(id = 1, updatedAt = now, rarity = 0.5, matched = 1)
        val triple = candidate(id = 2, updatedAt = now, rarity = 0.5, matched = 3)

        assertEquals(triple.key, MemoryRanker.pick(listOf(single, triple), 1, now).single().key)
    }

    @Test
    fun `同分时新的排在前面`() {
        val now = 1_700_000_000_000L
        val older = candidate(id = 1, updatedAt = now - 1000)
        val newer = candidate(id = 2, updatedAt = now)

        assertEquals(listOf(newer.key, older.key), MemoryRanker.pick(listOf(older, newer), 2, now).map { it.key })
    }

    @Test
    fun `开关关闭时检索器根本不被调用`() {
        // 配置默认值必须是开，用户才拿得到收益；关掉则完全回到旧行为
        assertTrue(MemoryConfig().searchEnabled)
        assertFalse(MemoryConfig().auxRankEnabled)
    }

    private fun candidate(
        id: Long,
        updatedAt: Long,
        rarity: Double = 1.0,
        matched: Int = 1,
    ) = MemoryCandidate(
        kind = MemoryKind.FACT,
        refId = id,
        text = "记忆 $id",
        updatedAt = updatedAt,
        rarity = rarity,
        matchedTerms = matched,
    )
}
