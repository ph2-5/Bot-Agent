package com.qbot.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词组装器测试：**顺序本身就是缓存策略**。
 *
 * 断言重点：
 * 1. 人设必须在最前面且永不丢弃；
 * 2. 事实块在人设之后、群聊背景之前；
 * 3. 群聊背景是动态内容，预算紧张时最先牺牲；
 * 4. 本轮问题固定最后，且带 [展示名] 前缀。
 */
class PromptAssemblerTest {
    private val assembler = PromptAssembler()

    private fun memory(
        label: String = "小明",
        facts: List<UserFact> = emptyList(),
        digest: List<RoomMessage> = emptyList(),
        group: GroupDigest? = null,
        quoted: QuotedMessage? = null,
        insult: InsultContext? = null,
        social: SocialContext? = null,
    ) = MemoryContext(
        senderLabel = label,
        facts = facts,
        roomDigest = digest,
        group = group,
        quoted = quoted,
        insult = insult,
        social = social,
    )

    private fun fact(text: String) = UserFact(senderId = "s", text = text)

    private fun roomMsg(who: String?, content: String) =
        RoomMessage(
            senderId = "s",
            senderName = who,
            role = "user",
            content = content,
            timestampMillis = 0,
        )

    @Test
    fun `组装顺序为人设 事实 背景 历史 本轮问题`() {
        val messages =
            assembler.assemble(
                personaPrompt = "你是助手",
                memory =
                    memory(
                        facts = listOf(fact("喜欢吃辣")),
                        digest = listOf(roomMsg("小红", "在吗")),
                    ),
                history =
                    listOf(
                        ChatMessage("user", "旧问题"),
                        ChatMessage("assistant", "旧回答"),
                    ),
                question = "新问题",
                tokenBudget = 10_000,
            )

        assertEquals(6, messages.size)
        assertEquals("system", messages[0].role)
        assertEquals("你是助手", messages[0].content)
        assertTrue(messages[1].content.contains("喜欢吃辣"))
        assertTrue(messages[2].content.contains("小红"))
        assertEquals("user", messages[3].role)
        assertEquals("assistant", messages[4].role)
        assertEquals("user", messages[5].role)
        // 本轮问题带展示名前缀，固定最后
        assertEquals("[小明] 新问题", messages[5].content)
    }

    @Test
    fun `没有人设时不注入 system 人设消息`() {
        val messages =
            assembler.assemble(
                personaPrompt = "   ",
                memory = null,
                history = emptyList(),
                question = "问",
                tokenBudget = 10_000,
            )
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("问", messages[0].content)
    }

    @Test
    fun `可以关闭展示名前缀`() {
        val messages =
            assembler.assemble(
                personaPrompt = "",
                memory = memory(label = "小明"),
                history = emptyList(),
                question = "问",
                tokenBudget = 10_000,
                senderPrefix = false,
            )
        assertEquals("问", messages.last().content)
    }

    @Test
    fun `无记忆时退化为纯对话组装`() {
        val messages =
            assembler.assemble(
                personaPrompt = "人设",
                memory = null,
                history = listOf(ChatMessage("user", "旧")),
                question = "新",
                tokenBudget = 10_000,
            )
        assertEquals(3, messages.size)
        assertEquals("新", messages.last().content) // 无展示名可加
    }

    @Test
    fun `预算紧张时先砍群聊背景`() {
        val messages =
            assembler.assemble(
                personaPrompt = "人设",
                memory =
                    memory(
                        facts = listOf(fact("喜欢吃辣")),
                        // 每条都顶到单条截断上限，连一条都塞不进背景配额
                        digest = List(20) { roomMsg("小红", "啊".repeat(80)) },
                    ),
                history = emptyList(),
                question = "问题",
                // 预算只够人设 + 事实 + 问题，背景必然被砍
                tokenBudget = 60,
            )

        assertTrue("人设必须保留", messages.any { it.content == "人设" })
        assertTrue("事实必须保留", messages.any { it.content.contains("喜欢吃辣") })
        assertFalse("背景应被砍掉", messages.any { it.content.contains("小红") })
        assertTrue("本轮问题必须保留", messages.last().content.contains("问题"))
    }

    @Test
    fun `预算再紧也保留最少条数的事实`() {
        val facts = (1..10).map { fact("事实$it") }
        val messages =
            assembler.assemble(
                personaPrompt = "",
                memory = memory(facts = facts),
                history = emptyList(),
                question = "问题",
                tokenBudget = 200,
            )

        val kept =
            messages
                .filter { it.role == "system" }
                .flatMap { it.content.lines() }
                .count { it.startsWith("- ") }
        assertTrue("至少保留 3 条事实，实际 $kept", kept >= 3)
    }

    @Test
    fun `超预算时历史成对丢弃`() {
        val history =
            listOf(
                ChatMessage("user", "一".repeat(300)),
                ChatMessage("assistant", "答".repeat(300)),
                ChatMessage("user", "二".repeat(300)),
                ChatMessage("assistant", "答".repeat(300)),
                ChatMessage("user", "三".repeat(300)),
                ChatMessage("assistant", "答".repeat(300)),
            )
        val messages =
            assembler.assemble(
                personaPrompt = "系统",
                memory = null,
                history = history,
                question = "本轮",
                tokenBudget = 2 + 600 + 2 + 50,
            )

        assertTrue(messages.first().role == "system")
        assertTrue(messages.last().content == "本轮")
        val contents = messages.map { it.content }
        assertTrue(!contents.contains("一".repeat(300)))
        assertTrue(contents.contains("三".repeat(300)))
        val userCount = messages.count { it.role == "user" && it.content != "本轮" }
        val assistantCount = messages.count { it.role == "assistant" }
        assertEquals(userCount, assistantCount)
    }

    @Test
    fun `群聊背景单条被截断`() {
        val long = "啊".repeat(500)
        val messages =
            assembler.assemble(
                personaPrompt = "",
                memory = memory(digest = listOf(roomMsg("小红", long))),
                history = emptyList(),
                question = "问题",
                tokenBudget = 10_000,
            )
        val digestMsg = messages.first { it.content.contains("小红") }
        assertTrue("单条应被截到 80 字符左右", digestMsg.content.length < long.length)
    }

    @Test
    fun `背景里匿名用户显示为某人`() {
        val messages =
            assembler.assemble(
                personaPrompt = "",
                memory = memory(digest = listOf(roomMsg(null, "在吗"))),
                history = emptyList(),
                question = "问题",
                tokenBudget = 10_000,
            )
        assertTrue(messages.any { it.content.contains("[某人]") })
    }

    @Test
    fun `群画像注入在事实之后 群背景之前`() {
        val messages =
            assembler.assemble(
                personaPrompt = "人设",
                memory =
                    memory(
                        facts = listOf(fact("喜欢吃辣")),
                        digest = listOf(roomMsg("小红", "在吗")),
                        group =
                            GroupDigest(
                                groupId = "g",
                                botName = "小助手",
                                seenCount = 12,
                                proactiveAllowed = true,
                            ),
                    ),
                history = emptyList(),
                question = "问题",
                tokenBudget = 10_000,
            )

        assertEquals(5, messages.size)
        assertEquals("人设", messages[0].content)
        assertTrue(messages[1].content.contains("喜欢吃辣"))
        assertTrue("群画像应包含本群昵称", messages[2].content.contains("小助手"))
        assertTrue("群画像应包含成员数", messages[2].content.contains("12"))
        assertTrue(messages[3].content.contains("小红"))
        assertEquals("user", messages[4].role)
    }

    @Test
    fun `群画像没有昵称时用官方名字兜底`() {
        val messages =
            assembler.assemble(
                personaPrompt = "",
                memory =
                    memory(
                        group =
                            GroupDigest(
                                groupId = "g",
                                botName = null,
                                seenCount = 3,
                                proactiveAllowed = true,
                            ),
                    ),
                history = emptyList(),
                question = "问题",
                tokenBudget = 10_000,
            )
        assertTrue(messages.any { it.content.contains("还没有昵称") })
    }

    @Test
    fun `群关闭主动消息时注入禁令`() {
        val messages =
            assembler.assemble(
                personaPrompt = "",
                memory =
                    memory(
                        group =
                            GroupDigest(
                                groupId = "g",
                                botName = null,
                                seenCount = 3,
                                proactiveAllowed = false,
                            ),
                    ),
                history = emptyList(),
                question = "问题",
                tokenBudget = 10_000,
            )
        assertTrue(messages.any { it.content.contains("关闭了主动消息") })
    }

    @Test
    fun `预算紧张时群画像保留 群背景被砍`() {
        val messages =
            assembler.assemble(
                personaPrompt = "",
                memory =
                    memory(
                        digest = List(20) { roomMsg("小红", "啊".repeat(80)) },
                        group =
                            GroupDigest(
                                groupId = "g",
                                botName = "小助手",
                                seenCount = 5,
                                proactiveAllowed = true,
                            ),
                    ),
                history = emptyList(),
                question = "问题",
                tokenBudget = 60,
            )
        assertTrue("群画像必须保留", messages.any { it.content.contains("小助手") })
        assertFalse("背景应被砍掉", messages.any { it.content.contains("小红") })
        assertTrue(messages.last().content.contains("问题"))
    }

    @Test
    fun `群成员名册与机器人自我称呼进入群画像`() {
        val messages =
            assembler.assemble(
                personaPrompt = "你是毒舌群友",
                memory =
                    memory(
                        label = "小明",
                        group =
                            GroupDigest(
                                groupId = "g",
                                botName = "小白",
                                seenCount = 6,
                                knownMembers = listOf("小明", "小红"),
                                unnamedCount = 4,
                            ),
                    ),
                history = emptyList(),
                question = "群里都有谁",
                tokenBudget = 10_000,
            )

        val groupBlock = messages.first { it.role == "system" && it.content.contains("QQ 群里聊天") }
        assertTrue("应声明自己的群名片", groupBlock.content.contains("小白"))
        assertTrue("应列出认识的人", groupBlock.content.contains("小明"))
        assertTrue("应列出认识的人", groupBlock.content.contains("小红"))
        assertTrue("应说明还有多少人没报名", groupBlock.content.contains("另有 4 位"))
        assertTrue("应禁止编造", groupBlock.content.contains("不要编造"))
        assertTrue("应声明当前说话人的名字", groupBlock.content.contains("正在跟你说话的这个人叫「小明」"))
    }

    @Test
    fun `事实块声明当前称呼并作废旧称呼`() {
        val messages =
            assembler.assemble(
                personaPrompt = "你是助手",
                memory = memory(label = "小红", facts = listOf(fact("喜欢吃辣"))),
                history = emptyList(),
                question = "我叫什么",
                tokenBudget = 10_000,
            )

        val factsBlock = messages.first { it.content.contains("喜欢吃辣") }
        assertTrue(
            "改名后必须声明以此为准",
            factsBlock.content.contains("对方现在的名字是「小红」"),
        )
        assertTrue("旧称呼应作废", factsBlock.content.contains("旧称呼一律作废"))
    }

    @Test
    fun `群聊常识声明第三方指代规则`() {
        val messages =
            assembler.assemble(
                personaPrompt = "你是一个脾气不好的群友",
                memory =
                    memory(
                        label = "潮水",
                        group =
                            GroupDigest(
                                groupId = "g",
                                botName = "潮水啊，我已归来",
                                seenCount = 10,
                            ),
                    ),
                history = emptyList(),
                question = "这个人是傻B",
                tokenBudget = 10_000,
            )

        val groupBlock = messages.first { it.content.contains("QQ 群里聊天") }
        assertTrue(
            "必须写明「你」才指自己",
            groupBlock.content.contains("「你」才指你"),
        )
        assertTrue(
            "必须要求指代不明时先问",
            groupBlock.content.contains("先问清楚"),
        )
        assertTrue(
            "必须声明看不到的部分不要编造",
            groupBlock.content.contains("不要编造"),
        )
    }

    @Test
    fun `被引用的原文会注入并提示指代`() {
        val messages =
            assembler.assemble(
                personaPrompt = "你是群友",
                memory =
                    memory(
                        label = "潮水",
                        quoted = QuotedMessage(senderName = "阿远", content = "我刚刚说什么了"),
                        group =
                            GroupDigest(
                                groupId = "g",
                                botName = "潮水啊，我已归来",
                                seenCount = 10,
                            ),
                    ),
                history = emptyList(),
                question = "他刚才说了啥",
                tokenBudget = 10_000,
            )

        val quotedBlock = messages.first { it.content.contains("对方正在回复") }
        assertTrue("应带上被引用的人", quotedBlock.content.contains("「阿远」"))
        assertTrue("应带上被引用的原文", quotedBlock.content.contains("我刚刚说什么了"))
        assertTrue("应提示据此判断指代", quotedBlock.content.contains("优先看这条原文"))
    }

    @Test
    fun `冒犯块宣告被骂并给人设反应许可`() {
        val messages =
            assembler.assemble(
                personaPrompt = "你脾气很差",
                memory =
                    memory(
                        label = "阿远",
                        insult = InsultContext(byWhom = "阿远", quote = "潮水你个傻逼"),
                    ),
                history = emptyList(),
                question = "潮水你个傻逼",
                tokenBudget = 10_000,
            )

        val block = messages.first { it.content.contains("当众骂了你") }
        assertTrue("应指出是谁骂的", block.content.contains("「阿远」"))
        assertTrue("应带上原话", block.content.contains("潮水你个傻逼"))
        assertTrue("应授权按人设反应", block.content.contains("按你的人设做出反应"))
        assertTrue("应守住底线", block.content.contains("不连续对骂"))
    }

    @Test
    fun `冒犯块带上对骂者的了解以区分熟人与陌生人`() {
        val messages =
            assembler.assemble(
                personaPrompt = "你是群友",
                memory =
                    memory(
                        label = "阿远",
                        facts = listOf(fact("喜欢熬夜")),
                        insult = InsultContext(byWhom = "阿远", quote = "你是废物"),
                    ),
                history = emptyList(),
                question = "你是废物",
                tokenBudget = 10_000,
            )

        val block = messages.first { it.content.contains("当众骂了你") }
        assertTrue("应带上关于此人的事实", block.content.contains("喜欢熬夜"))
        assertTrue(
            "熟人玩笑与陌生人挑衅应有区别",
            block.content.contains("反应程度应当不一样"),
        )
    }

    @Test
    fun `有候选图时注入表情包块`() {
        val messages =
            assembler.assemble(
                personaPrompt = "你是群友",
                memory =
                    memory(
                        social = SocialContext(memes = listOf("https://a.com/1.jpg")),
                    ),
                history = emptyList(),
                question = "在吗",
                tokenBudget = 10_000,
            )

        val block = messages.first { it.content.contains("表情包") }
        assertTrue(block.content.contains("https://a.com/1.jpg"))
        assertTrue(block.content.contains("[表情:图片地址]"))
    }

    @Test
    fun `没有候选图时不提发图这件事`() {
        val messages =
            assembler.assemble(
                personaPrompt = "你是群友",
                memory = memory(social = SocialContext(memes = emptyList())),
                history = emptyList(),
                question = "在吗",
                tokenBudget = 10_000,
            )

        // 没有图可发，提示词里连"能发图"都不该出现——
        // 否则模型会开始编造图片地址
        assertFalse(messages.any { it.content.contains("表情包") })
        assertFalse(messages.any { it.content.contains("表情:") })
    }
}
