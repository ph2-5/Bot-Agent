package com.qbot.core.ai

import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.MessageKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiResponderTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LlmClient
    private lateinit var history: InMemoryAiHistoryStore
    private lateinit var responder: AiResponder

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = LlmClient(http = OkHttpClient.Builder().build())
        history = InMemoryAiHistoryStore()
        responder = AiResponder(client = client, history = history)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config(
        mode: AiMode = AiMode.RULE_FIRST,
        enabled: Boolean = true,
        contextTokenBudget: Int = AiConfig().contextTokenBudget,
        thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
        baseUrl: String? = null,
        model: String = "deepseek-chat",
        maxTokens: Int = AiConfig().maxTokens,
    ) = AiConfig(
        enabled = enabled,
        baseUrl = baseUrl ?: server.url("/").toString(),
        apiKey = "sk-test",
        model = model,
        mode = mode,
        contextTokenBudget = contextTokenBudget,
        thinkingMode = thinkingMode,
        maxTokens = maxTokens,
    )

    private fun message(
        content: String,
        targetId: String = "group-1",
        kind: MessageKind = MessageKind.GROUP_AT,
        imageUrl: String? = null,
    ) = IncomingMessage(
        kind = kind,
        eventType = "GROUP_AT_MESSAGE_CREATE",
        messageId = "msg-1",
        content = content,
        targetId = targetId,
        senderId = "user-1",
        timestampMillis = 1_700_000_000_000,
        imageUrl = imageUrl,
        raw = buildJsonObject { },
    )

    private fun enqueueAnswer(answer: String = "这是答案") {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {"choices":[{"message":{"role":"assistant","content":"$answer"}}]}
                    """.trimIndent(),
                ),
        )
    }

    // ---------- shouldHandle ----------

    @Test
    fun `规则优先模式下规则未命中才交给 AI`() {
        assertTrue(responder.shouldHandle(message("你好"), config(AiMode.RULE_FIRST), ruleMatched = false))
        assertFalse(responder.shouldHandle(message("你好"), config(AiMode.RULE_FIRST), ruleMatched = true))
    }

    @Test
    fun `AI 优先模式下无视规则命中`() {
        assertTrue(responder.shouldHandle(message("你好"), config(AiMode.AI_ALWAYS), ruleMatched = true))
    }

    @Test
    fun `命令模式只认 ai 前缀`() {
        val cfg = config(AiMode.COMMAND_ONLY)
        assertTrue(responder.shouldHandle(message("/ai 你好"), cfg, ruleMatched = false))
        assertFalse(responder.shouldHandle(message("你好"), cfg, ruleMatched = false))
    }

    @Test
    fun `未启用或配置不全时一律不处理`() {
        assertFalse(responder.shouldHandle(message("你好"), config(enabled = false), ruleMatched = false))
        assertFalse(
            responder.shouldHandle(
                message("你好"),
                config().copy(apiKey = ""),
                ruleMatched = false,
            ),
        )
    }

    @Test
    fun `只发图没写字也要交给 AI`() {
        // 群里 @ 机器人甩一张图过来，是视觉模型最常见的开场
        assertTrue(
            responder.shouldHandle(
                message("", imageUrl = "https://example.com/a.jpg"),
                config(),
                ruleMatched = false,
            ),
        )
        // 但纯空消息仍然不处理
        assertFalse(responder.shouldHandle(message(""), config(), ruleMatched = false))
        // 命令模式下纯图也不认：没有 /ai 前缀，不该突然开始计费
        assertFalse(
            responder.shouldHandle(
                message("", imageUrl = "https://example.com/a.jpg"),
                config(mode = AiMode.COMMAND_ONLY),
                ruleMatched = false,
            ),
        )
    }

    // ---------- extractQuestion ----------

    @Test
    fun `剥离 ai 前缀得到真实问题`() {
        assertEquals("今天天气如何", responder.extractQuestion(message("/ai 今天天气如何")))
        assertEquals("今天天气如何", responder.extractQuestion(message("今天天气如何")))
    }

    // ---------- reply ----------

    @Test
    fun `回复成功后问答写入历史`() =
        runBlocking {
            enqueueAnswer("答案是 42")
            val reply = responder.reply(message("答案是什么"), config())

            assertEquals("答案是 42", reply)
            val stored = history.load("g:group-1:user-1", 10)
            assertEquals(2, stored.size)
            assertEquals("user", stored[0].role)
            assertEquals("答案是什么", stored[0].content)
            assertEquals("assistant", stored[1].role)
        }

    @Test
    fun `第二轮请求会带上历史上下文`() =
        runBlocking {
            enqueueAnswer("第一轮回答")
            responder.reply(message("问题一"), config())

            enqueueAnswer("第二轮回答")
            responder.reply(message("问题二"), config())

            server.takeRequest() // 先消费掉第一轮请求
            val second = server.takeRequest()
            val body = second.body.readUtf8()
            assertTrue("应包含第一轮提问", body.contains("问题一"))
            assertTrue("应包含第一轮回答", body.contains("第一轮回答"))
            assertTrue("应包含本轮提问", body.contains("问题二"))
        }

    @Test
    fun `请求失败时不写历史并抛出异常`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

            val error =
                runCatching { responder.reply(message("在吗"), config()) }.exceptionOrNull()

            assertTrue(error is LlmException)
            assertTrue(history.load("g:group-1:user-1", 10).isEmpty())
        }

    @Test
    fun `清空指令直接重置上下文且不调用模型`() =
        runBlocking {
            enqueueAnswer("随便")
            responder.reply(message("问题"), config())
            assertEquals(2, history.load("g:group-1:user-1", 10).size)

            val reply = responder.reply(message("/ai 清空"), config())
            assertTrue(reply.contains("已清空"))
            assertTrue(history.load("g:group-1:user-1", 10).isEmpty())
            // 只发起了第一次真实请求，清空指令没有打网络
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `不同会话的上下文互相隔离`() =
        runBlocking {
            enqueueAnswer("甲群回答")
            responder.reply(message("甲群问题"), config())

            enqueueAnswer("乙群回答")
            responder.reply(message("乙群问题", targetId = "group-2"), config())

            server.takeRequest() // 先消费掉甲群的请求
            val secondBody = server.takeRequest().body.readUtf8()
            assertFalse("乙群不应看到甲群历史", secondBody.contains("甲群问题"))
        }

    @Test
    fun `超长回复会被截断`() =
        runBlocking {
            val long = "长".repeat(3000)
            enqueueAnswer(long)

            val reply = responder.reply(message("讲个长的"), config())
            assertTrue(reply.length < long.length + 20)
            assertTrue(reply.endsWith("……（回复过长已截断）"))
        }

    @Test
    fun `空白问题直接反问不调用模型`() =
        runBlocking {
            val reply = responder.reply(message("/ai   "), config())
            assertEquals("请问你想问什么？", reply)
            assertEquals(0, server.requestCount)
        }

    // ---------- assembleMessages：缓存友好组装与预算 ----------

    @Test
    fun `组装顺序固定为 system 历史 本轮问题`() {
        val messages =
            responder.assembleMessages(
                systemPrompt = "你是助手",
                history =
                    listOf(
                        ChatMessage("user", "旧问题"),
                        ChatMessage("assistant", "旧回答"),
                    ),
                question = "新问题",
                tokenBudget = 10_000,
            )

        assertEquals(4, messages.size)
        assertEquals("system", messages[0].role)
        assertEquals("user", messages[1].role)
        assertEquals("assistant", messages[2].role)
        assertEquals("新问题", messages[3].content)
    }

    @Test
    fun `system 为空时不含 system 消息`() {
        val messages =
            responder.assembleMessages(
                systemPrompt = "  ",
                history = emptyList(),
                question = "问",
                tokenBudget = 10_000,
            )
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
    }

    @Test
    fun `超预算时从最早的历史成对丢弃`() {
        // 每条中文消息约等于其字符数个 token
        val history =
            listOf(
                ChatMessage("user", "一".repeat(400)),
                ChatMessage("assistant", "答".repeat(400)),
                ChatMessage("user", "二".repeat(400)),
                ChatMessage("assistant", "答".repeat(400)),
                ChatMessage("user", "三".repeat(400)),
                ChatMessage("assistant", "答".repeat(400)),
            )
        // 预算只够 system + 最后一对 + 本轮问题
        val messages =
            responder.assembleMessages(
                systemPrompt = "系统",
                history = history,
                question = "本轮",
                tokenBudget = 2 + 800 + 2 + 50,
            )

        assertTrue("system 必须保留", messages.first().role == "system")
        assertTrue("本轮问题必须保留", messages.last().content == "本轮")
        val contents = messages.map { it.content }
        assertTrue("最早的一对应被丢弃", !contents.contains("一".repeat(400)))
        assertTrue("最新的一对应保留", contents.contains("三".repeat(400)))
        // 成对丢弃：不应出现只剩提问没有回答的孤儿
        val userCount = messages.count { it.role == "user" && it.content != "本轮" }
        val assistantCount = messages.count { it.role == "assistant" }
        assertEquals("剩余历史应问答配对", userCount, assistantCount)
    }

    @Test
    fun `上下文超限时自动砍半历史重试并成功`() =
        runBlocking {
            enqueueAnswer("第一次")
            responder.reply(message("问题"), config(contextTokenBudget = 10_000))
            history.append("g:group-1:user-1", ChatMessage("user", "塞".repeat(5000)))

            // 第一次请求服务端报上下文超限，第二次（砍半后）成功
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":{"message":"maximum context length exceeded"}}"""),
            )
            enqueueAnswer("重试后的答案")

            val reply = responder.reply(message("再问"), config(contextTokenBudget = 10_000))
            assertEquals("重试后的答案", reply)
        }

    // ---------- thinkingParam ----------

    @Test
    fun `DeepSeek 端点默认关闭思考`() {
        val param = responder.thinkingParam(config())
        assertEquals("disabled", param)
    }

    @Test
    fun `非 DeepSeek 端点不发送 thinking 参数`() {
        val cfg = config(baseUrl = "https://api.openai.com", model = "gpt-4o")
        assertEquals(null, responder.thinkingParam(cfg))
    }

    @Test
    fun `AUTO 模式不发送 thinking 参数`() {
        val cfg = config().copy(thinkingMode = ThinkingMode.AUTO)
        assertEquals(null, responder.thinkingParam(cfg))
    }

    @Test
    fun `开启思考时参数为 enabled`() {
        val cfg = config().copy(thinkingMode = ThinkingMode.ENABLED)
        assertEquals("enabled", responder.thinkingParam(cfg))
    }

    @Test
    fun `DeepSeek 请求体默认带 thinking disabled`() =
        runBlocking {
            enqueueAnswer("ok")
            responder.reply(message("你好"), config())

            val body = server.takeRequest().body.readUtf8()
            assertTrue("应关闭思考模式，实际=$body", body.contains("\"thinking\""))
            assertTrue("应为 disabled，实际=$body", body.contains("disabled"))
        }

    // ---------- 指令结果复述（directive） ----------

    @Test
    fun `指令结果被包装进本轮问题交给模型`() =
        runBlocking {
            enqueueAnswer("你叫小明，我一直记着呢")
            val reply =
                responder.reply(
                    message("我叫什么"),
                    config(),
                    directive = "对方的展示名是「小明」（对方自己告诉你的）",
                )
            assertEquals("你叫小明，我一直记着呢", reply)

            val body = server.takeRequest().body.readUtf8()
            assertTrue("应带上指令结果，实际=$body", body.contains("【指令结果】"))
            assertTrue("应带上事实材料", body.contains("对方的展示名是「小明」"))
            assertTrue("应带上复述任务说明", body.contains("【任务】"))
            assertTrue("应带上用户原话", body.contains("我叫什么"))
        }

    @Test
    fun `指令复述的会话历史只记原始话术`() =
        runBlocking {
            enqueueAnswer("记住了")
            responder.reply(message("叫你小白"), config(), directive = "你刚刚把名字改成了「小白」")

            val stored = history.load("g:group-1:user-1", 10)
            assertEquals(2, stored.size)
            assertEquals("user", stored[0].role)
            assertEquals("用户原话应原文入历史", "叫你小白", stored[0].content)
            assertFalse(
                "包装文本不应泄漏进历史",
                stored[0].content.contains("【指令结果】"),
            )
        }

    @Test
    fun `clear 指令优先于指令复述且不调用模型`() =
        runBlocking {
            val reply = responder.reply(message("clear"), config(), directive = "任意结果")
            assertTrue(reply.contains("已清空"))
            assertEquals(0, server.requestCount)
        }

    // ---------- 插话判断的用户自定义规则 ----------

    @Test
    fun `插话判断注入用户自定义规则`() =
        runBlocking {
            enqueueAnswer("YES\n有人求助")
            val verdict =
                responder.wantsToChimeIn(
                    message("今天天气怎么样"),
                    config(),
                    recent = emptyList(),
                    botName = "小白",
                    personaPrompt = null,
                    behavior = BehaviorContext(userRules = listOf("只有有人求助时才接话")),
                )
            assertTrue(verdict.wants)

            val body = server.takeRequest().body.readUtf8()
            assertTrue("请求体应包含用户规则，实际=$body", body.contains("只有有人求助时才接话"))
            assertTrue("应有规则块标题", body.contains("用户补充规则"))
            assertTrue("应声明优先级最高", body.contains("优先级最高"))
        }

    @Test
    fun `没有用户规则时提示词不出现规则块`() =
        runBlocking {
            enqueueAnswer("NO")
            responder.wantsToChimeIn(message("随便聊聊"), config(), emptyList(), "小白")

            val body = server.takeRequest().body.readUtf8()
            assertFalse("无规则时不应注入规则块，实际=$body", body.contains("用户补充规则"))
        }

    @Test
    fun `超过十条用户规则只注入前十条`() =
        runBlocking {
            enqueueAnswer("YES")
            val rules = (1..15).map { "规则编号$it" }
            responder.wantsToChimeIn(
                message("在吗"),
                config(),
                emptyList(),
                "小白",
                behavior = BehaviorContext(userRules = rules),
            )

            val body = server.takeRequest().body.readUtf8()
            assertTrue("应注入第 10 条", body.contains("规则编号10"))
            assertFalse("第 11 条起应被截掉，实际=$body", body.contains("规则编号11"))
        }

    // ---------- 判断结果与理由 ----------

    @Test
    fun `判断结果带出模型给的理由`() =
        runBlocking {
            enqueueAnswer("YES\n话题冷场，适合搭一句")
            val verdict =
                responder.wantsToChimeIn(message("有人吗"), config(), emptyList(), "小白")
            assertTrue(verdict.wants)
            assertTrue("应带上理由，实际=${verdict.reason}", verdict.reason?.contains("冷场") == true)
        }

    @Test
    fun `理由写在同一行也能解析`() =
        runBlocking {
            enqueueAnswer("NO，两人正在私聊")
            val verdict =
                responder.wantsToChimeIn(message("你吃了吗"), config(), emptyList(), "小白")
            assertFalse(verdict.wants)
            assertTrue("应抽出同行理由，实际=${verdict.reason}", verdict.reason?.contains("私聊") == true)
        }

    @Test
    fun `无法解析的回复按不接处理`() =
        runBlocking {
            enqueueAnswer("这个我得想想")
            val verdict =
                responder.wantsToChimeIn(message("随便说说"), config(), emptyList(), "小白")
            assertFalse(verdict.wants)
        }

    @Test
    fun `经验注入提示词且最多十二条`() =
        runBlocking {
            enqueueAnswer("YES")
            val lessons = (1..15).map { "经验编号$it" }
            responder.wantsToChimeIn(
                message("在吗"),
                config(),
                emptyList(),
                "小白",
                behavior = BehaviorContext(lessons = lessons),
            )

            val body = server.takeRequest().body.readUtf8()
            assertTrue("应出现偏好块", body.contains("从主人反馈里学到的偏好"))
            assertTrue("应注入第 12 条", body.contains("经验编号12"))
            assertFalse("第 13 条起应被截掉，实际=$body", body.contains("经验编号13"))
        }

    @Test
    fun `没有经验时不出现经验块`() =
        runBlocking {
            enqueueAnswer("NO")
            responder.wantsToChimeIn(message("随便聊聊"), config(), emptyList(), "小白")

            val body = server.takeRequest().body.readUtf8()
            assertFalse("无经验时不应出现偏好块，实际=$body", body.contains("从主人反馈里学到的偏好"))
        }

    @Test
    fun `判断提示词带上历史消息与相对时间`() =
        runBlocking {
            enqueueAnswer("NO\n两人在私聊")
            val history =
                listOf(
                    RoomMessage(
                        senderId = "u1",
                        senderName = "小明",
                        role = "user",
                        content = "今晚开黑吗",
                        timestampMillis = 1_700_000_000_000L - 180_000L,
                    ),
                    RoomMessage(
                        senderId = null,
                        senderName = null,
                        role = "assistant",
                        content = "我不玩",
                        timestampMillis = 1_700_000_000_000L - 60_000L,
                    ),
                )
            responder.wantsToChimeIn(
                message("你们玩啥").copy(timestampMillis = 1_700_000_000_000L),
                config(),
                history,
                "小白",
                lastBotSpokeAgoMillis = 60_000L,
            )

            val body = server.takeRequest().body.readUtf8()
            assertTrue("应包含历史消息", body.contains("今晚开黑吗"))
            assertTrue("机器人消息应标为「你」", body.contains("[你 · 1 分钟前]"))
            assertTrue("用户消息应带相对时间", body.contains("[小明 · 3 分钟前]"))
            assertTrue("应告知上次发言时间", body.contains("你上次在这个群发言是1 分钟前"))
            assertTrue("应给出判断方法提示", body.contains("先读上面的对话"))
        }

    @Test
    fun `判断被输出额度截断时放宽额度重试`() =
        runBlocking {
            // 第一次：思考过程吃光 40 token，正文为空、finish=length
            server.enqueue(
                MockResponse().setBody(
                    """{"choices":[{"message":{"content":"","reasoning_content":"让我想想"},
                    "finish_reason":"length"}]}""".trimIndent(),
                ),
            )
            // 第二次：额度放宽后正常给出结论
            enqueueAnswer("YES\n有人提问")

            val verdict =
                responder.wantsToChimeIn(message("这个怎么弄"), config(), emptyList(), "小白")

            assertTrue("重试后应拿到结论", verdict.wants)
            assertEquals("应恰好调用两次模型", 2, server.requestCount)
        }

    @Test
    fun `判断的输出额度与设置里的单次生成上限对齐`() =
        runBlocking {
            enqueueAnswer("NO")
            responder.wantsToChimeIn(
                message("随便聊聊"),
                config(maxTokens = 500),
                emptyList(),
                "小白",
            )

            val body = server.takeRequest().body.readUtf8()
            assertTrue(
                "应直接使用设置里的额度，不在代码里另立数字，实际=$body",
                body.contains("\"max_tokens\":500"),
            )
        }

    @Test
    fun `判断提示词表达意愿并带上对话对象`() =
        runBlocking {
            enqueueAnswer("NO")
            responder.wantsToChimeIn(
                message("你吃了吗"),
                config(),
                emptyList(),
                "小白",
                addressee = Addressee(AddresseeKind.MEMBER, "阿远", "开头叫了对方的名字"),
            )

            val body = server.takeRequest().body.readUtf8()
            assertTrue("应有判断标记", body.contains(AiResponder.JUDGE_PROMPT_TAG))
            assertTrue("应表达想不想而不是该不该", body.contains("想不想"))
            assertTrue("应给出对话对象", body.contains("对「阿远」说的"))
            assertTrue("应说明插别人对话很怪", body.contains("插进去通常很怪"))
        }
}
