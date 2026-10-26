package com.qbot.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 场景模型与四个原语工具的纯 JVM 测试。 */
class SceneTest {

    private val now = 1_000_000_000_000L

    private fun scene(
        status: SceneStatus = SceneStatus.ACTIVE,
        expected: String? = null,
        waitUntil: Long? = null,
        createdAt: Long = now,
        participants: List<SceneParticipant> = twoPlayers(),
    ) = Scene(
        id = 7L,
        kind = SceneKind.CROSSTALK,
        groupId = "g1",
        participants = participants,
        goal = "聊到借钱收场",
        status = status,
        turnIndex = 0,
        expectedSpeakerId = expected,
        waitUntil = waitUntil,
        lastActivityAt = now,
        createdAt = createdAt,
    )

    private fun twoPlayers(partnerId: String = "b") =
        listOf(
            SceneParticipant("a", "阿远", isBot = true, role = SceneRole.COMEDIAN),
            SceneParticipant(partnerId, "于谦", isBot = true, role = SceneRole.STRAIGHT),
        )

    private class FakeStore(var current: Scene? = null) : SceneStore {
        val records = mutableListOf<SceneDecisionRecord>()
        var endedId: Long? = null

        override suspend fun active(groupId: String): Scene? =
            current?.takeIf { it.groupId == groupId && it.status != SceneStatus.ENDED }

        override suspend fun activeScenes(): List<Scene> =
            listOfNotNull(current?.takeIf { it.status != SceneStatus.ENDED })

        override suspend fun save(scene: Scene): Scene {
            current = if (scene.id == 0L) scene.copy(id = 1L) else scene
            return current!!
        }

        override suspend fun end(sceneId: Long, nowMillis: Long) {
            endedId = sceneId
            current = current?.copy(status = SceneStatus.ENDED)
        }

        override suspend fun lastEndedAt(groupId: String): Long? = null

        override suspend fun record(decision: SceneDecisionRecord) {
            records += decision
        }

        override suspend fun speaksSince(groupId: String, sinceMillis: Long): Int = 0
    }

    @Test
    fun `角色与轮次判定`() {
        val s = scene(expected = "a")
        assertEquals(SceneRole.COMEDIAN, s.roleOf("a"))
        assertEquals(SceneRole.STRAIGHT, s.roleOf("b"))
        assertNull(s.roleOf("c"))
        assertTrue(s.isMyTurn("a"))
        assertFalse(s.isMyTurn("b"))
        // 不在参与者里，永远轮不到
        assertFalse(s.isMyTurn("c"))
    }

    @Test
    fun `expected 为空时所有参与者都可以接`() {
        val s = scene(expected = null)
        assertTrue(s.isMyTurn("a"))
        assertTrue(s.isMyTurn("b"))
        assertFalse(s.isMyTurn("c"))
    }

    @Test
    fun `已结束的场景不再有轮次`() {
        val s = scene(status = SceneStatus.ENDED, expected = "a")
        assertFalse(s.isMyTurn("a"))
        // 已结束的场景不再参与超时收尾
        assertFalse(s.isExpired(now + SceneLimits.MAX_SCENE_MILLIS * 2))
    }

    @Test
    fun `超过十分钟强制结束`() {
        val s = scene()
        assertFalse(s.isExpired(now + SceneLimits.MAX_SCENE_MILLIS))
        assertTrue(s.isExpired(now + SceneLimits.MAX_SCENE_MILLIS + 1))
    }

    @Test
    fun `等待未到时不唤醒 到点后才算过期`() {
        val s = scene(status = SceneStatus.WAITING, expected = "b", waitUntil = now + 25_000)
        assertTrue(s.isWaitingFor("b", now))
        assertFalse(s.isWaitingFor("b", now + 26_000))
        assertFalse(s.waitExpired(now))
        assertTrue(s.waitExpired(now + 25_000))
    }

    @Test
    fun `认领占位对方`() {
        val s =
            scene(
                participants =
                    listOf(
                        SceneParticipant("a", "阿远", isBot = true, role = SceneRole.COMEDIAN),
                        SceneParticipant("", "于谦", isBot = true, role = SceneRole.STRAIGHT),
                    ),
            )
        val claimed = s.claimPartner("b", "于谦本人", isBot = true)
        assertEquals("b", claimed.partnerOf("a")?.senderId)
        assertEquals("于谦本人", claimed.partnerOf("a")?.displayName)
        // 第三方不能被认成对方
        val withRealPartner = claimed.claimPartner("c", "路人")
        assertEquals("b", withRealPartner.partnerOf("a")?.senderId)
    }

    @Test
    fun `场景块只在参与者内生成`() {
        assertNotNull(ScenePrompt.block(scene(expected = "a"), "a"))
        assertNull(ScenePrompt.block(scene(expected = "a"), "c"))
        assertNull(ScenePrompt.block(scene(status = SceneStatus.ENDED), "a"))
    }

    @Test
    fun `开关关闭时开场工具直接拒绝`() {
        val store = FakeStore()
        val box = SceneToolbox(store, { SceneMode.OFF }, { "a" }, { "阿远" }, { now })
        val start = box.tools.first { it.spec.name == "start_scene" }
        val result =
            runBlockingExecute(
                start,
                """{"role":"comedian","partnerName":"于谦","goal":"借钱"}""",
            )
        assertTrue(result.content.contains("未开启"))
        assertNull(store.current)
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `只读模式只落表 不创建场景`() {
        val store = FakeStore()
        val box = SceneToolbox(store, { SceneMode.OBSERVE }, { "a" }, { "阿远" }, { now })
        val start = box.tools.first { it.spec.name == "start_scene" }
        val result =
            runBlockingExecute(
                start,
                """{"role":"comedian","partnerName":"于谦","goal":"借钱"}""",
            )
        assertTrue(result.content.contains("只读"))
        assertNull(store.current)
        assertEquals(1, store.records.size)
        assertEquals(SceneAction.START, store.records.single().action)
        assertFalse(store.records.single().executed)
    }

    @Test
    fun `真正执行时开场并进入等待`() {
        val store = FakeStore()
        val box = SceneToolbox(store, { SceneMode.ACTIVE }, { "a" }, { "阿远" }, { now })
        val start = box.tools.first { it.spec.name == "start_scene" }
        runBlockingExecute(start, """{"role":"comedian","partnerName":"于谦","goal":"借钱"}""")
        val created = store.current
        assertNotNull(created)
        assertEquals("a", created!!.participants.first().senderId)
        assertEquals(SceneStatus.ACTIVE, created.status)

        val wait = box.tools.first { it.spec.name == "wait_for_reply" }
        val turn = ToolTurn(groupId = "g1", senderId = "a", scene = created)
        runBlockingExecute(wait, """{"seconds":25}""", turn)
        val waiting = store.current!!
        assertEquals(SceneStatus.WAITING, waiting.status)
        assertEquals(now + 25_000, waiting.waitUntil)
        assertEquals("", waiting.expectedSpeakerId)

        // 相邻两轮不能连续等待
        val again = runBlockingExecute(wait, """{"seconds":25}""", turn)
        assertTrue(again.content.contains("已经等过"))
    }

    @Test
    fun `结束动作把场景置为 ENDED`() {
        val store = FakeStore(scene(expected = "a"))
        val box = SceneToolbox(store, { SceneMode.ACTIVE }, { "a" }, { "阿远" }, { now })
        val end = box.tools.first { it.spec.name == "end_scene" }
        val turn = ToolTurn(groupId = "g1", senderId = "a", scene = store.current)
        runBlockingExecute(end, """{"reason":"收底"}""", turn)
        assertEquals(7L, store.endedId)
        assertEquals(SceneStatus.ENDED, store.current!!.status)
    }

    private fun runBlockingExecute(
        tool: BotTool,
        args: String,
        turn: ToolTurn = ToolTurn(groupId = "g1", senderId = "a"),
    ): ToolResult {
        var result: ToolResult? = null
        kotlinx.coroutines.runBlocking {
            result = tool.execute(Json.parseToJsonElement(args) as JsonObject, turn)
        }
        return result!!
    }
}
