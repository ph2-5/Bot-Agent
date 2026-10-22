package com.qbot.core.ai

import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.MessageKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 记忆管线测试：身份 → 事实 → 共享背景的编排逻辑。
 *
 * 全部用内存存储与固定时钟，不起网络、不碰 Android。
 */
class MemoryPipelineTest {
    private lateinit var identities: InMemoryIdentityStore
    private lateinit var facts: InMemoryMemoryStore
    private lateinit var room: InMemoryRoomContextStore
    private lateinit var clock: FakeMemoryClock

    /** 当前管线（换配置时整体重建，因为 MemoryPipeline 不是 data class）。 */
    private lateinit var pipeline: MemoryPipeline

    /** 可控时钟，让「最近更新」类的断言稳定。 */
    private class FakeMemoryClock(var now: Long = 1_700_000_000_000) {
        fun tick(millis: Long = 1000) {
            now += millis
        }
    }

    @Before
    fun setUp() {
        identities = InMemoryIdentityStore()
        facts = InMemoryMemoryStore()
        room = InMemoryRoomContextStore()
        clock = FakeMemoryClock()
        pipeline = newPipeline(MemoryConfig())
    }

    private fun newPipeline(config: MemoryConfig) =
        MemoryPipeline(
            identities = identities,
            facts = facts,
            roomContext = room,
            configProvider = { config },
            clock = { clock.now },
        )

    private fun message(
        content: String,
        senderId: String = "sender-1",
        targetId: String = "group-1",
        kind: MessageKind = MessageKind.GROUP_AT,
        senderName: String? = null,
    ) = IncomingMessage(
        kind = kind,
        eventType = "GROUP_AT_MESSAGE_CREATE",
        messageId = "msg-1",
        content = content,
        targetId = targetId,
        senderId = senderId,
        timestampMillis = clock.now,
        senderName = senderName,
        raw = buildJsonObject { },
    )

    // ---------- 身份 ----------

    @Test
    fun `自称名被学习并在后续消息里复用`() =
        runTest {
            val first = pipeline.buildContext(message("叫我小明"), "bot", "叫我小明")
            assertEquals("小明", first.senderLabel)
            assertNotNull(identities.find("sender-1"))

            clock.tick()
            val second = pipeline.buildContext(message("在吗"), "bot", "在吗")
            assertEquals("小明", second.senderLabel)
        }

    @Test
    fun `手动备注不会被自称覆盖`() =
        runTest {
            identities.remember("sender-1", "阿伟", NameSource.MANUAL, clock.now)

            val ctx = pipeline.buildContext(message("叫我小明"), "bot", "叫我小明")
            assertEquals("手动备注优先", "阿伟", ctx.senderLabel)
        }

    @Test
    fun `平台昵称在无自称时被采用`() =
        runTest {
            val ctx =
                pipeline.buildContext(
                    message("大家好", senderName = "频道小明"),
                    "bot",
                    "大家好",
                )
            assertEquals("频道小明", ctx.senderLabel)
            assertEquals(NameSource.PLATFORM, identities.find("sender-1")?.source)
        }

    @Test
    fun `查无此人时回退到群友标签`() =
        runTest {
            val ctx = pipeline.buildContext(message("在吗"), "bot", "在吗")
            assertEquals("群友nder-1", ctx.senderLabel)
        }

    @Test
    fun `单聊回退到好友标签`() =
        runTest {
            val ctx =
                pipeline.buildContext(
                    message("在吗", targetId = "user-openid", kind = MessageKind.C2C),
                    "bot",
                    "在吗",
                )
            assertEquals("好友nder-1", ctx.senderLabel)
        }

    @Test
    fun `关闭学习后不认识任何人`() =
        runTest {
            val p = newPipeline(MemoryConfig(learnNames = false))
            val ctx = p.buildContext(message("叫我小明"), "bot", "叫我小明")
            assertEquals("群友nder-1", ctx.senderLabel)
            assertNull(identities.find("sender-1"))
        }

    // ---------- 事实 ----------

    @Test
    fun `消息里声明的事实被落库并注入后续上下文`() =
        runTest {
            val first = pipeline.buildContext(message("我喜欢吃辣"), "bot", "我喜欢吃辣")
            assertTrue(first.facts.any { it.text == "喜欢吃辣" })

            clock.tick()
            val second = pipeline.buildContext(message("推荐道菜"), "bot", "推荐道菜")
            assertTrue(
                "后续对话应看到事实",
                second.facts.any { it.text == "喜欢吃辣" },
            )
        }

    @Test
    fun `同一条事实不会重复入库`() =
        runTest {
            pipeline.buildContext(message("我喜欢吃辣"), "bot", "我喜欢吃辣")
            clock.tick()
            pipeline.buildContext(message("我喜欢吃辣"), "bot", "我喜欢吃辣")
            assertEquals(1, facts.facts("sender-1").size)
        }

    @Test
    fun `关闭事实学习后不落库`() =
        runTest {
            val p = newPipeline(MemoryConfig(learnFacts = false))
            p.buildContext(message("我喜欢吃辣"), "bot", "我喜欢吃辣")
            assertTrue(facts.facts("sender-1").isEmpty())
        }

    @Test
    fun `每个人的事实互相隔离`() =
        runTest {
            pipeline.buildContext(message("我喜欢吃辣", senderId = "a"), "bot", "我喜欢吃辣")
            clock.tick()
            val bCtx =
                pipeline.buildContext(message("在吗", senderId = "b"), "bot", "在吗")
            assertTrue(bCtx.facts.isEmpty())
        }

    @Test
    fun `事实总量被收敛到上限`() =
        runTest {
            val p =
                newPipeline(MemoryConfig(factsPerPerson = 3))
            repeat(5) { i ->
                clock.tick()
                p.buildContext(message("我喜欢吃辣$i", senderId = "a"), "bot", "我喜欢吃辣$i")
            }
            assertEquals(3, facts.facts("a").size)
        }

    // ---------- 共享背景 ----------

    @Test
    fun `问答被写进共享背景且带展示名`() =
        runTest {
            pipeline.buildContext(message("叫我小明"), "bot", "叫我小明")
            pipeline.recordExchange(message("你好"), "你好", "你好呀")

            val digest = room.recent("g:group-1", 10)
            assertEquals(2, digest.size)
            assertEquals("user", digest[0].role)
            assertEquals("小明", digest[0].senderName)
            assertEquals("你好", digest[0].content)
            assertEquals("assistant", digest[1].role)
            assertEquals("你好呀", digest[1].content)
        }

    @Test
    fun `共享背景按范围隔离`() =
        runTest {
            pipeline.recordExchange(message("你好", targetId = "group-1"), "你好", "甲")
            pipeline.recordExchange(message("你好", targetId = "group-2"), "你好", "乙")

            assertEquals(2, room.recent("g:group-1", 10).size)
            assertEquals(2, room.recent("g:group-2", 10).size)
        }

    @Test
    fun `共享背景条数被收敛`() =
        runTest {
            repeat(30) { i ->
                pipeline.recordExchange(message("问$i"), "问$i", "答$i")
            }
            assertTrue(room.recent("g:group-1", 100).size <= 20)
        }

    @Test
    fun `关闭共享背景后不写不读`() =
        runTest {
            val p = newPipeline(MemoryConfig(roomContextEnabled = false))
            p.recordExchange(message("你好"), "你好", "你好呀")
            val ctx = p.buildContext(message("在吗"), "bot", "在吗")
            assertTrue(room.recent("g:group-1", 10).isEmpty())
            assertTrue(ctx.roomDigest.isEmpty())
        }

    // ---------- 记忆指令 ----------

    @Test
    fun `查看记忆指令回放事实`() =
        runBlocking {
            pipeline.buildContext(message("我喜欢吃辣"), "bot", "我喜欢吃辣")

            val outcome = pipeline.handleMemoryCommand(message("我的记忆"), "我的记忆")
            assertNotNull(outcome)
            assertTrue("事实材料应含事实", outcome!!.directive.contains("喜欢吃辣"))
            assertTrue("兜底文案应含事实", outcome.fallback.contains("喜欢吃辣"))
        }

    @Test
    fun `我叫什么指令返回已学名字`() =
        runTest {
            pipeline.buildContext(message("叫我小明"), "bot", "叫我小明")

            val outcome = pipeline.handleMemoryCommand(message("我叫什么"), "我叫什么")
            assertTrue("事实材料应含名字", outcome!!.directive.contains("小明"))
            assertTrue("兜底文案应含名字", outcome.fallback.contains("小明"))
        }

    @Test
    fun `不认识时我问什么给出引导`() =
        runTest {
            val outcome = pipeline.handleMemoryCommand(message("我是谁"), "我是谁")
            assertTrue("事实材料应说明不认识", outcome!!.directive.contains("还不认识"))
            assertTrue("兜底文案应给出引导", outcome.fallback.contains("还不认识"))
        }

    @Test
    fun `忘掉关键词删除对应事实`() =
        runTest {
            pipeline.buildContext(message("我喜欢吃辣"), "bot", "我喜欢吃辣")
            clock.tick()
            pipeline.buildContext(message("我讨厌胡萝卜"), "bot", "我讨厌胡萝卜")

            val outcome = pipeline.handleMemoryCommand(message("忘掉胡萝卜"), "忘掉胡萝卜")
            assertTrue("兜底文案应确认删除", outcome!!.fallback.contains("已忘掉"))
            assertTrue(
                "事实材料应说明删了什么",
                outcome.directive.contains("忘掉了关于「胡萝卜」的 1 条记忆"),
            )
            assertTrue(facts.facts("sender-1").none { it.text.contains("胡萝卜") })
            assertTrue(facts.facts("sender-1").any { it.text.contains("辣") })
        }

    @Test
    fun `忘掉未命中的关键词不删任何东西`() =
        runTest {
            pipeline.buildContext(message("我喜欢吃辣"), "bot", "我喜欢吃辣")

            val outcome = pipeline.handleMemoryCommand(message("忘掉菠菜"), "忘掉菠菜")
            assertTrue(
                "事实材料应说明没找到",
                outcome!!.directive.contains("没有找到相关记录"),
            )
            assertEquals(1, facts.facts("sender-1").size)
        }

    @Test
    fun `忘掉我清空全部记忆与身份`() =
        runTest {
            pipeline.buildContext(message("叫我小明"), "bot", "叫我小明")
            pipeline.buildContext(message("我喜欢吃辣"), "bot", "我喜欢吃辣")

            val outcome = pipeline.handleMemoryCommand(message("忘掉我"), "忘掉我")
            assertTrue("兜底文案应确认清空", outcome!!.fallback.contains("已清空"))
            assertTrue("事实材料应说明清空后果", outcome.directive.contains("不记得关于对方的任何事情"))
            assertTrue(facts.facts("sender-1").isEmpty())
            assertNull(identities.find("sender-1"))
        }

    @Test
    fun `空记忆的查看指令给引导`() =
        runTest {
            val outcome = pipeline.handleMemoryCommand(message("我的记忆"), "我的记忆")
            assertTrue(
                "事实材料应说明没有记忆",
                outcome!!.directive.contains("还没有关于对方的任何记忆"),
            )
            assertTrue("兜底文案应给引导", outcome.fallback.contains("多聊几句"))
        }

    @Test
    fun `普通消息不是记忆指令`() =
        runTest {
            assertNull(pipeline.handleMemoryCommand(message("今天天气如何"), "今天天气如何"))
            assertNull(pipeline.handleMemoryCommand(message("我的记忆是怎么回事呢"), "我的记忆是怎么回事呢"))
        }

    @Test
    fun `记忆总开关关闭时指令不拦截`() =
        runTest {
            val p = newPipeline(MemoryConfig(enabled = false))
            assertNull(p.handleMemoryCommand(message("我的记忆"), "我的记忆"))
        }

    // ---------- 启动收敛 ----------

    @Test
    fun `trimAll 收敛每个人的事实`() =
        runTest {
            val p =
                newPipeline(MemoryConfig(factsPerPerson = 2))
            repeat(4) { i ->
                clock.tick()
                p.buildContext(message("我喜欢吃辣$i", senderId = "a"), "bot", "我喜欢吃辣$i")
                clock.tick()
                p.buildContext(message("我喜欢吃苦$i", senderId = "b"), "bot", "我喜欢吃苦$i")
            }
            p.trimAll()
            assertEquals(2, facts.facts("a").size)
            assertEquals(2, facts.facts("b").size)
        }
}
