package com.qbot.core.ai

import com.qbot.core.model.GroupNotice
import com.qbot.core.model.GroupNoticeKind
import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.MessageKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 群感知测试：群档案的建立、成员积累、上限淘汰，以及三个群指令。
 *
 * 平台不提供群成员列表，这些档案完全由事件拼出来——
 * 测试重点放在「事件 → 档案」的映射正确性与上限行为上。
 */
class GroupAwarenessTest {
    private lateinit var groups: InMemoryGroupStore
    private lateinit var identities: InMemoryIdentityStore
    private lateinit var facts: InMemoryMemoryStore
    private lateinit var room: InMemoryRoomContextStore
    private lateinit var pipeline: MemoryPipeline

    @Before
    fun setUp() {
        groups = InMemoryGroupStore()
        identities = InMemoryIdentityStore()
        facts = InMemoryMemoryStore()
        room = InMemoryRoomContextStore()
        pipeline =
            MemoryPipeline(
                identities = identities,
                facts = facts,
                roomContext = room,
                groupStore = groups,
                botNameProvider = { "官方机器人" },
            )
    }

    private fun groupMessage(
        content: String,
        senderId: String = "member-1",
        groupId: String = "group-1",
    ) = IncomingMessage(
        kind = MessageKind.GROUP_AT,
        eventType = "GROUP_AT_MESSAGE_CREATE",
        messageId = "msg-1",
        content = content,
        targetId = groupId,
        senderId = senderId,
        timestampMillis = 1_700_000_000_000,
        raw = buildJsonObject { },
    )

    private fun notice(
        kind: GroupNoticeKind,
        groupId: String? = "group-1",
        actorId: String? = "member-op",
    ) = GroupNotice(
        kind = kind,
        groupId = groupId,
        actorId = actorId,
        timestampMillis = 1_700_000_000_000,
    )

    // ---------- 群档案 ----------

    @Test
    fun `被拉进群时建档并记住拉它进来的人`() =
        runTest {
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED))

            val profile = groups.find("group-1")!!
            assertTrue("建档后应为活跃", profile.active)
            assertEquals(setOf("member-op"), profile.seenMembers)
        }

    @Test
    fun `新成员进群记入见过的人`() =
        runTest {
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED))
            pipeline.handleGroupNotice(notice(GroupNoticeKind.MEMBER_ADDED, actorId = "member-new"))
            pipeline.handleGroupNotice(notice(GroupNoticeKind.MEMBER_ADDED, actorId = "member-new2"))

            assertEquals(3, groups.find("group-1")!!.seenCount)
        }

    @Test
    fun `群内消息也会积累见过的人`() =
        runTest {
            pipeline.noteGroupActivity(groupMessage("大家好", senderId = "member-a"))
            pipeline.noteGroupActivity(groupMessage("在吗", senderId = "member-b"))
            pipeline.noteGroupActivity(groupMessage("还在吗", senderId = "member-a"))

            val profile = groups.find("group-1")!!
            assertEquals(2, profile.seenCount)
        }

    @Test
    fun `见过的人有上限，超出淘汰最旧的`() =
        runTest {
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED, actorId = "m0"))
            // 逐个加入 m1..m9， cap=3 时应只保留最近的三个
            for (i in 1..9) {
                pipeline.handleGroupNotice(
                    notice(GroupNoticeKind.MEMBER_ADDED, actorId = "m$i"),
                )
            }
            // touch 的 seenCap 默认 500，这里直接用 store 的 cap 参数测淘汰
            groups.touch("group-1", "m10", 1_700_000_001_000, seenCap = 3)
            val profile = groups.find("group-1")!!
            assertEquals(3, profile.seenCount)
            assertTrue("应保留最近出现的", profile.seenMembers.contains("m10"))
            assertFalse("最旧的应被淘汰", profile.seenMembers.contains("m0"))
        }

    @Test
    fun `被移出群后标记为不活跃但保留档案`() =
        runTest {
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED))
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_REMOVED))

            val profile = groups.find("group-1")!!
            assertFalse("被移出后应为不活跃", profile.active)
            assertTrue("档案仍应保留（再加回来要用）", profile.seenMembers.isNotEmpty())
        }

    @Test
    fun `再加回旧群时保留群名片`() =
        runTest {
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED))
            pipeline.handleMemoryCommand(groupMessage("叫你小白"), "叫你小白")
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_REMOVED))
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED))

            assertEquals("小白", groups.find("group-1")!!.botName)
        }

    @Test
    fun `主动消息开关被记录`() =
        runTest {
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED))
            pipeline.handleGroupNotice(notice(GroupNoticeKind.PROACTIVE_REJECTED))
            assertFalse(groups.find("group-1")!!.proactiveAllowed)

            pipeline.handleGroupNotice(notice(GroupNoticeKind.PROACTIVE_ALLOWED))
            assertTrue(groups.find("group-1")!!.proactiveAllowed)
        }

    @Test
    fun `好友事件不建群档`() =
        runTest {
            pipeline.handleGroupNotice(notice(GroupNoticeKind.FRIEND_ADDED, groupId = null))
            assertEquals(0, groups.count())
        }

    @Test
    fun `未配置群存储时群通知安全直通`() =
        runTest {
            val bare =
                MemoryPipeline(
                    identities = InMemoryIdentityStore(),
                    facts = InMemoryMemoryStore(),
                    roomContext = InMemoryRoomContextStore(),
                )
            // 不应抛异常
            bare.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED))
            assertNull(bare.groupProfile("group-1"))
        }

    // ---------- 群指令 ----------

    @Test
    fun `叫你X 设置本群名片`() =
        runTest {
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED))

            val outcome = pipeline.handleMemoryCommand(groupMessage("叫你小白"), "叫你小白")
            assertEquals("好，以后在这个群里我就叫「小白」了。", outcome!!.fallback)
            assertTrue("事实材料应说明新名字", outcome.directive.contains("改成了「小白」"))
            assertEquals("小白", groups.find("group-1")!!.botName)
        }

    @Test
    fun `叫你X 的多种说法都能识别`() =
        runTest {
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED))
            listOf("以后叫你阿白", "我今后就叫你大白", "叫你Alpha_1").forEach { text ->
                assertTrue(
                    "「$text」应被识别为改名指令",
                    pipeline.handleMemoryCommand(groupMessage(text), text) != null,
                )
            }
        }

    @Test
    fun `单聊里叫你X 会被礼貌拒绝`() =
        runTest {
            val dm =
                IncomingMessage(
                    kind = MessageKind.C2C,
                    eventType = "C2C_MESSAGE_CREATE",
                    messageId = "m",
                    content = "叫你小白",
                    targetId = "user-1",
                    senderId = "user-1",
                    timestampMillis = 1_700_000_000_000,
                    raw = buildJsonObject { },
                )
            val outcome = pipeline.handleMemoryCommand(dm, "叫你小白")
            assertTrue(
                "应提示只在群里有效，实际=${outcome?.fallback}",
                outcome!!.fallback.contains("只在群里有效"),
            )
        }

    @Test
    fun `你叫什么优先回答本群名片`() =
        runTest {
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED))
            pipeline.handleMemoryCommand(groupMessage("叫你小白"), "叫你小白")

            val outcome = pipeline.handleMemoryCommand(groupMessage("你叫什么"), "你叫什么")
            assertTrue("事实材料应含群名片", outcome!!.directive.contains("小白"))
            assertTrue("兜底文案应含群名片", outcome.fallback.contains("小白"))
        }

    @Test
    fun `没设名片时用官方名字兜底`() =
        runTest {
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED))

            val outcome = pipeline.handleMemoryCommand(groupMessage("你是谁"), "你是谁")
            assertTrue("事实材料应含官方名字", outcome!!.directive.contains("官方机器人"))
            assertTrue("兜底文案应含官方名字", outcome.fallback.contains("官方机器人"))
        }

    @Test
    fun `群里都有谁列出叫过名字的人`() =
        runTest {
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED))
            // 两个人在群里自我介绍（真实链路里 BotRuntime 先记账、resolveLabel 学名，再交指令）
            pipeline.noteGroupActivity(groupMessage("叫我小明", senderId = "m1"))
            pipeline.resolveLabel(groupMessage("叫我小明", senderId = "m1"), "叫我小明")
            pipeline.noteGroupActivity(groupMessage("叫我小红", senderId = "m2"))
            pipeline.resolveLabel(groupMessage("叫我小红", senderId = "m2"), "叫我小红")
            // 第三个只说话没报名
            pipeline.handleGroupNotice(notice(GroupNoticeKind.MEMBER_ADDED, actorId = "m3"))

            val outcome = pipeline.handleMemoryCommand(groupMessage("群里都有谁"), "群里都有谁")
            // 拉机器人进来的 member-op + 小明 + 小红 + m3
            assertTrue("兜底文案应报 4 人，实际=${outcome?.fallback}", outcome!!.fallback.contains("4 人"))
            assertTrue("事实材料应报 4 人", outcome.directive.contains("共 4 人"))
            assertTrue("事实材料应列出名字", outcome.directive.contains("小明"))
            assertTrue("事实材料应列出名字", outcome.directive.contains("小红"))
            assertTrue(
                "事实材料应说明有 2 位没报名字",
                outcome.directive.contains("还有 2 位没有告诉你名字"),
            )
            assertTrue("member-op 与 m3 没报过名字", outcome.fallback.contains("还有 2 位没告诉我名字"))
        }

    @Test
    fun `空群回答还不熟悉`() =
        runTest {
            // 加群事件没带操作人（平台可能不下发 op_member_openid）：见过的人为空
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED, actorId = null))
            val outcome = pipeline.handleMemoryCommand(groupMessage("群里都有谁"), "群里都有谁")
            assertTrue("事实材料应说明不熟悉", outcome!!.directive.contains("还不熟悉"))
            assertTrue("兜底文案应说明不熟悉", outcome.fallback.contains("还不熟悉"))
        }

    @Test
    fun `指令复述开关默认开启`() =
        runTest {
            assertTrue(pipeline.currentConfig().personaRewrite)
        }

    // ---------- 上下文注入 ----------

    @Test
    fun `群画像进入记忆上下文`() =
        runTest {
            pipeline.handleGroupNotice(notice(GroupNoticeKind.ROBOT_ADDED))
            pipeline.handleMemoryCommand(groupMessage("叫你小白"), "叫你小白")
            pipeline.noteGroupActivity(groupMessage("在吗", senderId = "member-a"))

            val ctx = pipeline.buildContext(groupMessage("你好"), botId = "bot-1", cleanText = "你好")
            assertEquals("小白", ctx.group?.botName)
            assertEquals(2, ctx.group?.seenCount) // 拉它进来的人 + member-a
        }

    @Test
    fun `没有群档时不注入群画像`() =
        runTest {
            val ctx = pipeline.buildContext(groupMessage("你好"), botId = "bot-1", cleanText = "你好")
            assertNull(ctx.group)
        }
}
