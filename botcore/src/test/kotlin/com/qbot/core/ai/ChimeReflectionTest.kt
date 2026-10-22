package com.qbot.core.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反思工具与内存训练器。
 *
 * 这里固定住三件事：模型输出怎么洗、相似度怎么判、经验按什么顺序注入。
 * 它们决定了「可训练」的效果，改坏了很容易退化成"学了个寂寞"。
 */
class ChimeReflectionTest {
    private fun record(
        message: String = "今天好累啊",
        action: String = ChimeDecisionRecord.ACTION_SILENT,
        aiVerdict: String? = "NO",
        aiReason: String? = "像在自言自语",
        reply: String? = null,
        ruleScore: Int? = 25,
    ) = ChimeDecisionRecord(
        groupId = "g1",
        speakerId = "u1",
        speakerName = "阿远",
        messageText = message,
        contextSnippet = "[小明] 今天加班到十点",
        ruleScore = ruleScore,
        gatePath = ChimeDecisionRecord.GATE_CANDIDATE,
        aiVerdict = aiVerdict,
        aiReason = aiReason,
        action = action,
        reason = if (action == ChimeDecisionRecord.ACTION_SPOKE) "已接话" else "不接：AI 判断不需要接话",
        replyText = reply,
        addressed = false,
        insulted = false,
        externalId = "m1",
        createdAt = 1_000L,
    )

    private fun decision(corrected: String? = ChimeDecision.SHOULD_SPEAK, note: String? = null) =
        ChimeDecision(
            id = 1,
            record = record(),
            feedback = false,
            correctedAction = corrected,
            feedbackNote = note,
            feedbackAt = 2_000L,
        )

    // ---------- 输出清洗 ----------

    @Test
    fun `清洗编号与引号前缀`() {
        assertEquals("两人私聊时不要插话", ChimeReflection.parseLesson("- 两人私聊时不要插话"))
        assertEquals("两人私聊时不要插话", ChimeReflection.parseLesson("1. 两人私聊时不要插话"))
        assertEquals("两人私聊时不要插话", ChimeReflection.parseLesson("经验：两人私聊时不要插话"))
        assertEquals("两人私聊时不要插话", ChimeReflection.parseLesson("「两人私聊时不要插话」"))
    }

    @Test
    fun `超长经验截断到四十字`() {
        val long = "这是一条非常长的经验用来验证截断逻辑是否正确处理超长文本避免撑爆提示词预算导致每次判断都变慢"
        assertTrue("样例本身要超过上限，实际=${long.length}", long.length > ChimeLesson.MAX_TEXT_CHARS)
        val parsed = ChimeReflection.parseLesson(long)
        assertNotNull(parsed)
        assertEquals(ChimeLesson.MAX_TEXT_CHARS, parsed!!.length)
        assertTrue(long.startsWith(parsed))
    }

    @Test
    fun `空输出与过短文本返回 null`() {
        assertNull(ChimeReflection.parseLesson(""))
        assertNull(ChimeReflection.parseLesson("   "))
        assertNull(ChimeReflection.parseLesson("嗯"))
    }

    @Test
    fun `只取第一行`() {
        val raw = "凌晨两点后不要接话\n这条是解释，不该入库"
        assertEquals("凌晨两点后不要接话", ChimeReflection.parseLesson(raw))
    }

    // ---------- 相似去重 ----------

    @Test
    fun `近似表述视为同一条经验`() {
        val existing = ChimeLesson(id = 3, text = "两人私聊时不要插话")
        val hit = ChimeReflection.findSimilar("两人私聊时别插话", listOf(existing))
        assertNotNull(hit)
        assertEquals(3L, hit!!.id)
    }

    @Test
    fun `不同场景不会误判为重复`() {
        val lessons =
            listOf(
                ChimeLesson(id = 1, text = "凌晨两点后不要接话"),
                ChimeLesson(id = 2, text = "有人求助时要主动接话"),
            )
        assertNull(ChimeReflection.findSimilar("两人私聊时别插话", lessons))
    }

    // ---------- 提示词 ----------

    @Test
    fun `反思提示词带上纠正方向与备注`() {
        val prompt =
            ChimeReflection.buildUserPrompt(
                decision(note = "他们只是在互相道晚安"),
                listOf(ChimeLesson(id = 1, text = "凌晨不要接话")),
            )
        assertTrue(prompt.contains("【主人反馈】判断错了，这种情况应该接话"))
        assertTrue(prompt.contains("他们只是在互相道晚安"))
        assertTrue(prompt.contains("【你的判断】NO（理由：像在自言自语）"))
        assertTrue(prompt.contains("【规则打分】25"))
        assertTrue(prompt.contains("凌晨不要接话"))
        assertTrue(prompt.contains("今天加班到十点"))
    }

    @Test
    fun `反思提示词在没有反馈备注时不出现空段落`() {
        val prompt = ChimeReflection.buildUserPrompt(decision(note = null), emptyList())
        assertFalse(prompt.contains("【主人备注】"))
        assertFalse(prompt.contains("【已有经验】"))
    }

    @Test
    fun `反思系统提示词限定字数与格式`() {
        val system = ChimeReflection.buildSystemPrompt()
        assertTrue(system.contains("${ChimeLesson.MAX_TEXT_CHARS} 字"))
        assertTrue(system.contains("只输出经验文本"))
    }

    // ---------- 内存训练器 ----------

    @Test
    fun `经验注入优先本群再全局且排除其他群`() =
        runBlocking {
            val trainer = InMemoryChimeTrainer()
            trainer.addLesson("全局经验A", scopeGroupId = null, updatedAt = 10)
            trainer.addLesson("本群经验B", scopeGroupId = "g1", updatedAt = 5)
            trainer.addLesson("别的群经验C", scopeGroupId = "g2", updatedAt = 99)

            val lessons = trainer.lessonsForPrompt("g1")
            assertEquals(listOf("本群经验B", "全局经验A"), lessons.map { it.text })
        }

    @Test
    fun `关闭的经验不会被注入`() =
        runBlocking {
            val trainer = InMemoryChimeTrainer()
            trainer.addLesson("被关掉的经验", enabled = false, updatedAt = 1)
            assertTrue(trainer.lessonsForPrompt("g1").isEmpty())
        }

    @Test
    fun `注入次数按 id 累加`() =
        runBlocking {
            val trainer = InMemoryChimeTrainer()
            val lesson = trainer.addLesson("有人求助时要接话", updatedAt = 1)
            trainer.bumpHits(listOf(lesson.id))
            trainer.bumpHits(listOf(lesson.id))
            assertEquals(2, trainer.lessons.single().hits)
        }

    @Test
    fun `内存训练器记录每次决策`() =
        runBlocking {
            val trainer = InMemoryChimeTrainer()
            trainer.onDecision(record())
            trainer.onDecision(record(message = "在吗"))
            assertEquals(2, trainer.decisions.size)
            assertEquals("今天好累啊", trainer.decisions.first().messageText)
        }

    @Test
    fun `正确率在没有反馈时为 null`() {
        assertNull(ChimeStats(total = 10, reviewed = 0).accuracyPercent)
        assertEquals(50, ChimeStats(total = 10, reviewed = 4, correct = 2).accuracyPercent)
    }
}
