package com.qbot.core.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插话决策引擎测试：三级漏斗的前两级（规则与频率）。
 *
 * 时钟完全可控，因此冷却/配额这类"等一会儿"的逻辑不用真等。
 */
class ChitchatPolicyTest {
    private var now = 1_700_000_000_000L

    private val policy = ChitchatPolicy(clock = { now })

    private fun config(
        enabled: Boolean = true,
        baseCooldownMillis: Long = 90_000L,
        maxPerHour: Int = 20,
        maxPerDay: Int = 200,
        minScore: Int = 25,
    ) = ChitchatConfig(
        enabled = enabled,
        baseCooldownMillis = baseCooldownMillis,
        maxPerHour = maxPerHour,
        maxPerDay = maxPerDay,
        aiJudge = false,
        minScore = minScore,
    )

    private val names = listOf("小白")

    @Test
    fun `关闭时永远不接话`() =
        runTest {
            val gate = policy.gate("g1", "有人吗", names, config(enabled = false))
            assertTrue(gate is ChitchatGate.Skip)
        }

    @Test
    fun `叫到名字时强制接话`() =
        runTest {
            val gate = policy.gate("g1", "小白你过来一下", names, config())
            assertTrue("应识别为强制接话，实际=$gate", gate is ChitchatGate.Forced)
        }

    @Test
    fun `叫名字也有最小间隔防止刷屏`() =
        runTest {
            policy.markSpoke("g1")
            now += 3_000
            val gate = policy.gate("g1", "小白，小白！", names, config())
            assertTrue("刚说过话时名字也不该连续触发，实际=$gate", gate is ChitchatGate.Skip)
        }

    @Test
    fun `冷却下限为 1 秒`() =
        runTest {
            // 配置 1 秒时，冷场 ×0.6 不应算出 0 或负数
            assertEquals(1_000L, policy.cooldownFor("g1", config(baseCooldownMillis = 1_000)))
        }

    @Test
    fun `每小时上限为零时不限制`() =
        runTest {
            repeat(50) {
                policy.markSpoke("g1")
                now += 1_000
            }
            // 越过冷却再看配额：此时只应有"无上限"生效
            now += 60_000
            val gate = policy.gate("g1", "今晚有人开黑吗", names, config(maxPerHour = 0))
            assertTrue("0 表示无上限，应进入候选，实际=$gate", gate is ChitchatGate.Candidate)
        }

    @Test
    fun `冷却期内普通消息被跳过`() =
        runTest {
            policy.markSpoke("g1")
            now += 30_000
            val gate = policy.gate("g1", "今晚有人开黑吗", names, config(baseCooldownMillis = 90_000))
            assertTrue("冷却中应跳过，实际=$gate", gate is ChitchatGate.Skip)
        }

    @Test
    fun `冷却结束后普通提问进入候选`() =
        runTest {
            policy.markSpoke("g1")
            now += 91_000
            val gate = policy.gate("g1", "今晚有人开黑吗", names, config())
            assertTrue("应进入候选，实际=$gate", gate is ChitchatGate.Candidate)
        }

    @Test
    fun `陈述句分数不足时不进入候选`() =
        runTest {
            val gate = policy.gate("g1", "今天好热啊", names, config())
            assertTrue("无提问信号的闲聊应跳过，实际=$gate", gate is ChitchatGate.Skip)
        }

    @Test
    fun `对别人说话时降分`() =
        runTest {
            val gate = policy.gate("g1", "<@!12345> 你觉得今晚吃啥", names, config())
            assertTrue("在跟别人对话时不该插嘴，实际=$gate", gate is ChitchatGate.Skip)
        }

    @Test
    fun `热群冷却自动放大`() =
        runTest {
            repeat(15) { policy.observe("hot") }
            assertEquals(180_000L, policy.cooldownFor("hot", config(baseCooldownMillis = 90_000)))
        }

    @Test
    fun `冷场冷却自动缩短`() =
        runTest {
            assertEquals(54_000L, policy.cooldownFor("cold", config(baseCooldownMillis = 90_000)))
        }

    @Test
    fun `活跃度窗口外的旧消息不再计入`() =
        runTest {
            repeat(15) { policy.observe("g1") }
            now += 6 * 60_000L
            assertEquals(
                "5 分钟前的消息不算活跃",
                54_000L,
                policy.cooldownFor("g1", config(baseCooldownMillis = 90_000)),
            )
        }

    @Test
    fun `每小时配额耗尽后跳过`() =
        runTest {
            repeat(3) {
                policy.markSpoke("g1")
                now += 60_000
            }
            val gate = policy.gate("g1", "有人吗", names, config(maxPerHour = 3))
            assertTrue("超出每小时上限应跳过，实际=$gate", gate is ChitchatGate.Skip)
        }

    @Test
    fun `配额按群独立计算`() =
        runTest {
            repeat(3) {
                policy.markSpoke("g1")
                now += 60_000
            }
            val other = policy.gate("g2", "今晚有人开黑吗", names, config(maxPerHour = 3))
            assertTrue("别的群不受影响，实际=$other", other is ChitchatGate.Candidate)
        }

    @Test
    fun `被指名冒犯时强制接话`() =
        runTest {
            val gate =
                policy.gate(
                    "g1",
                    "阿远你个傻逼",
                    listOf("过去的阿远"),
                    config(),
                    insulted = true,
                )
            assertTrue("被指名骂必须按人设反应，实际=$gate", gate is ChitchatGate.Forced)
        }

    @Test
    fun `冒犯回应同样受最小间隔约束`() =
        runTest {
            policy.markSpoke("g1")
            now += 3_000
            val gate =
                policy.gate("g1", "阿远你个傻逼", listOf("过去的阿远"), config(), insulted = true)
            assertTrue("刚说过话时骂人回应也不该刷屏，实际=$gate", gate is ChitchatGate.Skip)
        }

    @Test
    fun `艾特了其他群友时不插嘴`() =
        runTest {
            val gate =
                policy.gate(
                    "g1",
                    "你觉得呢",
                    names,
                    config(),
                    talkingToOthers = true,
                )
            assertTrue("那是两个人的对话，不该接，实际=$gate", gate is ChitchatGate.Skip)
        }

    @Test
    fun `短昵称也能触发必答`() =
        runTest {
            // 设定「过去的阿远」，群里只喊「阿远」
            val gate = policy.gate("g1", "阿远，开黑不", listOf("过去的阿远"), config())
            assertTrue("后缀昵称应命中必答，实际=$gate", gate is ChitchatGate.Forced)
        }

    @Test
    fun `话痨人设降低打分阈值`() =
        runTest {
            // "有人吗"得 20 分：中性阈值 21 会被拦，话痨 −10 后过筛
            val neutral = policy.gate("g1", "有人吗", names, config(minScore = 21))
            assertTrue("中性偏置下应被拦，实际=$neutral", neutral is ChitchatGate.Skip)

            val talkative =
                policy.gate(
                    "g2",
                    "有人吗",
                    names,
                    config(minScore = 21),
                    personaBias = PersonaBias.TALKATIVE,
                )
            assertTrue("话痨偏置下应过筛，实际=$talkative", talkative is ChitchatGate.Candidate)
        }

    @Test
    fun `高冷人设抬高打分阈值`() =
        runTest {
            val gate =
                policy.gate(
                    "g1",
                    "有人吗",
                    names,
                    config(minScore = 11),
                    personaBias = PersonaBias.RESERVED,
                )
            assertTrue("高冷偏置下阈值 +10 应被拦，实际=$gate", gate is ChitchatGate.Skip)
        }

    @Test
    fun `人设性格从提示词关键词推断`() {
        assertEquals(PersonaBias.TALKATIVE, PersonaBias.personaBiasFor("你是个话痨，看到话题就忍不住"))
        assertEquals(PersonaBias.RESERVED, PersonaBias.personaBiasFor("你很高冷，惜字如金"))
        assertEquals("两种特质都出现时不猜", PersonaBias.NEUTRAL, PersonaBias.personaBiasFor("话痨但高冷"))
        assertEquals("空人设保持中性", PersonaBias.NEUTRAL, PersonaBias.personaBiasFor(null))
    }
}
