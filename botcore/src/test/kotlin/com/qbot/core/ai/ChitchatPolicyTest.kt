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
    ) = ChitchatConfig(
        enabled = enabled,
        baseCooldownMillis = baseCooldownMillis,
        maxPerHour = maxPerHour,
        maxPerDay = maxPerDay,
        aiJudge = false,
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
}
