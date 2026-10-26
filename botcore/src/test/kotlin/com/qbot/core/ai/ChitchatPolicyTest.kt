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
        minScore: Int = 15,
        aiJudge: Boolean = false,
        scoreWeights: ScoreWeights = ScoreWeights(),
    ) = ChitchatConfig(
        enabled = enabled,
        baseCooldownMillis = baseCooldownMillis,
        maxPerHour = maxPerHour,
        maxPerDay = maxPerDay,
        aiJudge = aiJudge,
        minScore = minScore,
        scoreWeights = scoreWeights,
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
            // 冷却不是拒绝：等剩下的 2 秒再答，而不是装死
            assertTrue("刚说过话时应等待而不是丢弃，实际=$gate", gate is ChitchatGate.Defer)
            assertEquals(2_000L, (gate as ChitchatGate.Defer).delayMillis)
        }

    @Test
    fun `对方划界时名字出现也不接`() =
        runTest {
            // 「关你阿远什么事」：名字被夹在中间，过去会被判成"叫了名字"强制接话
            val gate =
                policy.gate(
                    "g1",
                    "我和陈泽说话关你阿远什么事",
                    listOf("阿远"),
                    config(),
                )
            assertTrue("划界句不该触发接话，实际=$gate", gate is ChitchatGate.Skip)
        }

    @Test
    fun `否认身份时不触发强制接话`() =
        runTest {
            val gate = policy.gate("g1", "我不是阿远，认错人了", listOf("阿远"), config())
            assertTrue("否认不是叫人，实际=$gate", gate !is ChitchatGate.Forced)
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
    fun `冷却期内普通消息先等待而不是丢弃`() =
        runTest {
            policy.markSpoke("g1")
            now += 30_000
            val gate = policy.gate("g1", "今晚有人开黑吗", names, config(baseCooldownMillis = 90_000))
            assertTrue("冷却中应给出等待时长，实际=$gate", gate is ChitchatGate.Defer)
            // 基配 90 秒、冷场 ×0.6 → 54 秒；已过 30 秒，剩 24 秒。
            // 调用方会判定「太久」并转成跳过，但这里先如实报出剩余时间
            assertEquals(24_000L, (gate as ChitchatGate.Defer).delayMillis)
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
            // 话题正热（有最近消息，无冷场加分）时，纯感叹没有上下文信号，不过筛
            val recent =
                listOf(
                    roomMsg(senderId = "u1", content = "今晚吃什么"),
                    roomMsg(senderId = "u2", content = "随便，都行"),
                )
            val gate = policy.gate("g1", "今天好热啊", names, config(), recent = recent)
            assertTrue("无信号闲聊应跳过，实际=$gate", gate is ChitchatGate.Skip)
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
            assertTrue("刚说过话时骂人回应应等待，实际=$gate", gate is ChitchatGate.Defer)
        }

    @Test
    fun `划界句即使被当成冒犯也优先闭嘴`() =
        runTest {
            // 上游 detectors 可能同时给出 insulted=true（名字 + "你" 同在）。
            // 用户明确说「关你什么事」时，宁可闭嘴，也不要回嘴。
            val gate =
                policy.gate(
                    "g1",
                    "我和陈泽说话关你阿远什么事",
                    listOf("过去的阿远"),
                    config(),
                    insulted = true,
                )
            assertTrue("划界优先于冒犯回应，实际=$gate", gate is ChitchatGate.Skip)
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
            // "有人吗"得 25 分（疑问 25 + 长度 10 − 短句 20 + 冷场 10）
            val neutral = policy.gate("g1", "有人吗", names, config(minScore = 26))
            assertTrue("中性偏置下应被拦，实际=$neutral", neutral is ChitchatGate.Skip)

            val talkative =
                policy.gate(
                    "g2",
                    "有人吗",
                    names,
                    config(minScore = 26),
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
                    config(minScore = 16),
                    personaBias = PersonaBias.RESERVED,
                )
            assertTrue("高冷偏置下阈值 +10 应被拦，实际=$gate", gate is ChitchatGate.Skip)
        }

    // ---------- 上下文评分 ----------

    private fun roomMsg(
        senderId: String = "u1",
        role: String = "user",
        content: String,
    ) = RoomMessage(
        senderId = senderId,
        senderName = senderId,
        role = role,
        content = content,
        timestampMillis = 0,
    )

    @Test
    fun `话题延续时加分`() =
        runTest {
            val recent =
                listOf(
                    roomMsg(senderId = "u1", content = "新版本这游戏太肝了"),
                    roomMsg(senderId = "u2", content = "是啊每天任务做不完"),
                )
            val onTopic =
                policy.gate("g1", "这游戏确实难", names, config(), recent = recent)
            assertTrue("话题延续应过筛，实际=$onTopic", onTopic is ChitchatGate.Candidate)

            val offTopic =
                policy.gate(
                    "g1",
                    "今天天气不错",
                    names,
                    config(minScore = 16),
                    recent = recent,
                )
            assertTrue("跑题时阈值 16 应被拦，实际=$offTopic", offTopic is ChitchatGate.Skip)
        }

    @Test
    fun `有人在回应机器人时加分`() =
        runTest {
            val recent = listOf(roomMsg(role = "assistant", content = "我觉得还行吧"))
            val gate = policy.gate("g1", "哈哈确实", names, config(), recent = recent)
            assertTrue("有人接机器人的话应过筛，实际=$gate", gate is ChitchatGate.Candidate)
        }

    @Test
    fun `同一人连续刷屏时扣分`() =
        runTest {
            val same =
                listOf(
                    roomMsg(senderId = "u1", content = "这游戏太肝了"),
                    roomMsg(senderId = "u1", content = "真的肝到不行"),
                )
            val different =
                listOf(
                    roomMsg(senderId = "u1", content = "这游戏太肝了"),
                    roomMsg(senderId = "u2", content = "真的肝到不行"),
                )
            val spam =
                policy.gate("g1", "这游戏肝度如何", names, config(minScore = 16), recent = same)
            val normal =
                policy.gate("g2", "这游戏肝度如何", names, config(minScore = 16), recent = different)
            assertTrue("同一人刷屏应被拦，实际=$spam", spam is ChitchatGate.Skip)
            assertTrue("不同人聊天应过筛，实际=$normal", normal is ChitchatGate.Candidate)
        }

    @Test
    fun `冷场时加分`() =
        runTest {
            // 无最近消息：中等长度无问句 10 + 冷场 10 = 20 ≥ 15
            val gate = policy.gate("g1", "这游戏确实难", names, config())
            assertTrue("冷场应过筛，实际=$gate", gate is ChitchatGate.Candidate)
        }

    @Test
    fun `人设性格从提示词关键词推断`() {
        assertEquals(PersonaBias.TALKATIVE, PersonaBias.personaBiasFor("你是个话痨，看到话题就忍不住"))
        assertEquals(PersonaBias.RESERVED, PersonaBias.personaBiasFor("你很高冷，惜字如金"))
        assertEquals("两种特质都出现时不猜", PersonaBias.NEUTRAL, PersonaBias.personaBiasFor("话痨但高冷"))
        assertEquals("空人设保持中性", PersonaBias.NEUTRAL, PersonaBias.personaBiasFor(null))
    }

    @Test
    fun `AI 主导时普通陈述也进候选`() =
        runTest {
            // 话题正热的普通陈述：规则主导会被阈值拦下，AI 主导应交给模型
            val recent =
                listOf(
                    roomMsg(senderId = "u1", content = "今晚吃什么"),
                    roomMsg(senderId = "u2", content = "随便，都行"),
                )
            val ruleMode = policy.gate("g1", "今天好热啊", names, config(), recent = recent)
            assertTrue("规则主导下应被拦，实际=$ruleMode", ruleMode is ChitchatGate.Skip)

            val aiMode =
                policy.gate("g1", "今天好热啊", names, config(aiJudge = true), recent = recent)
            assertTrue("AI 主导下应交模型判断，实际=$aiMode", aiMode is ChitchatGate.Candidate)
        }

    @Test
    fun `AI 主导时仍挡明显噪音`() =
        runTest {
            val gate = policy.gate("g1", "哦", names, config(aiJudge = true))
            assertTrue("单字噪音不该浪费模型调用，实际=$gate", gate is ChitchatGate.Skip)
        }

    // ---------- 1.9.3：打分明细权重用户化 ----------

    @Test
    fun `调高长度分值后原本不过滤的消息进入候选`() =
        runTest {
            // 无上下文中性的短句：默认只有长度分 10 < 15，过不了筛
            val recent =
                listOf(
                    roomMsg(senderId = "u1", content = "今天天气不错"),
                    roomMsg(senderId = "u2", content = "晚上吃啥"),
                )
            val text = "咋回事啊"
            val dft = policy.gate("g1", text, names, config(), recent = recent)
            assertTrue("默认分值应被拦，实际=$dft", dft is ChitchatGate.Skip)

            val tuned =
                policy.gate(
                    "g1",
                    text,
                    names,
                    config(scoreWeights = ScoreWeights(lengthScore = 20)),
                    recent = recent,
                )
            assertTrue("长度分值调到 20 应过筛，实际=$tuned", tuned is ChitchatGate.Candidate)
        }

    @Test
    fun `调低各项分值后提问也被拦`() =
        runTest {
            // 默认：疑问 25 + 长度 10 + 冷场 10 = 45，稳过
            val dft = policy.gate("g1", "有人吗", names, config())
            assertTrue("默认分值应过筛，实际=$dft", dft is ChitchatGate.Candidate)

            val tuned =
                policy.gate(
                    "g1",
                    "有人吗",
                    names,
                    config(
                        scoreWeights =
                            ScoreWeights(
                                lengthScore = 0,
                                questionScore = 5,
                                quietScore = 0,
                            ),
                    ),
                )
            assertTrue("各分值调低后应被拦，实际=$tuned", tuned is ChitchatGate.Skip)
        }

    @Test
    fun `调高门槛后提问也被拦`() =
        runTest {
            val gate = policy.gate("g1", "有人吗", names, config(minScore = 50))
            assertTrue("门槛 50 应拦下 45 分的提问，实际=$gate", gate is ChitchatGate.Skip)
        }

    @Test
    fun `用户规则与自定义词表可存入配置`() {
        val config =
            config().copy(
                chimeRules = listOf("群里讨论工作时不要插话", "有人求助时优先接话"),
                insultExtraWords = listOf("菜狗", " farmed"),
            )
        assertEquals(2, config.chimeRules.size)
        assertEquals(2, config.insultExtraWords.size)
        // 空列表是默认值：不影响未配置的用户
        assertEquals(emptyList<String>(), config().chimeRules)
        assertEquals(emptyList<String>(), config().insultExtraWords)
    }
}
