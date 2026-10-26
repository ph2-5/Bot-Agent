package com.qbot.core.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 场景契约：截断、空判定、往返、畸形参数容错、渲染回退。 */
class SceneContractTest {

    private val now = 1_000_000_000_000L

    private fun scene(contract: SceneContract?) =
        Scene(
            id = 1L,
            kind = SceneKind.CROSSTALK,
            groupId = "g1",
            participants =
                listOf(
                    SceneParticipant("a", "阿远", isBot = true, role = SceneRole.COMEDIAN),
                    SceneParticipant("b", "于谦", isBot = true, role = SceneRole.STRAIGHT),
                ),
            goal = "聊到借钱收场",
            status = SceneStatus.ACTIVE,
            turnIndex = 0,
            expectedSpeakerId = null,
            waitUntil = null,
            lastActivityAt = now,
            createdAt = now,
            contract = contract,
        )

    @Test
    fun `超长字段被截断到上限`() {
        val long = "长".repeat(300)
        val contract =
            SceneContract(
                languageStyle = long,
                roleRules = (1..6).associate { "角色$it" to long },
                taboos = (1..8).map { long },
                exitCriteria = long,
                props = (1..5).map { long },
            ).normalized()

        assertEquals(SceneContract.MAX_STYLE, contract.languageStyle.length)
        assertTrue(contract.languageStyle.endsWith("…"))
        assertEquals(SceneContract.MAX_ROLES, contract.roleRules.size)
        assertTrue(contract.roleRules.values.all { it.length == SceneContract.MAX_ROLE_RULE })
        assertTrue(contract.roleRules.keys.all { it.length <= SceneContract.MAX_ROLE_NAME })
        assertEquals(SceneContract.MAX_TABOOS, contract.taboos.size)
        assertTrue(contract.taboos.all { it.length == SceneContract.MAX_TABOO })
        assertEquals(SceneContract.MAX_EXIT, contract.exitCriteria.length)
        assertEquals(SceneContract.MAX_PROPS, contract.props.size)
        assertTrue(contract.props.all { it.length == SceneContract.MAX_PROP })
    }

    @Test
    fun `空白项被丢掉 条数按上限保留`() {
        val contract =
            SceneContract(
                roleRules = mapOf("阿远" to "  ", "  " to "规则", "于谦" to "捧"),
                taboos = listOf("  ", "不提政治"),
                props = emptyList(),
            ).normalized()

        // 名字空或规则空都丢；剩下一条
        assertEquals(mapOf("于谦" to "捧"), contract.roleRules)
        assertEquals(listOf("不提政治"), contract.taboos)
    }

    @Test
    fun `全空视为没写契约`() {
        assertTrue(SceneContract().isEmpty())
        assertTrue(SceneContract(languageStyle = "   ", taboos = listOf("  ")).isEmpty())
        assertFalse(SceneContract(languageStyle = "京津口语").isEmpty())
        assertFalse(SceneContract(roleRules = mapOf("你" to "抛包袱")).isEmpty())
    }

    @Test
    fun `序列化往返一致 含中文与换行`() {
        val contract =
            SceneContract(
                languageStyle = "京津口语，短句为主",
                roleRules = mapOf("你" to "抛话题\n每句留口子", "对方" to "顺着接"),
                taboos = listOf("不提政治", "不拿家人开玩笑"),
                exitCriteria = "借到钱就收\n别拖",
                props = listOf("三百块"),
            ).normalized()

        val text = Json.encodeToString(contract)
        val back = Json.decodeFromString<SceneContract>(text)
        assertEquals(contract, back)
    }

    @Test
    fun `畸形参数不打断 取不到就丢`() {
        val raw =
            Json.parseToJsonElement(
                """
                {
                  "languageStyle": "短句",
                  "roleRules": ["不是对象"],
                  "taboos": "不是数组",
                  "exitCriteria": {"不是字符串": 1},
                  "props": [1, "三百块", null]
                }
                """.trimIndent(),
            ) as kotlinx.serialization.json.JsonObject

        val contract = SceneContract.fromJson(raw)
        assertEquals("短句", contract.languageStyle)
        assertTrue(contract.roleRules.isEmpty())
        assertTrue(contract.taboos.isEmpty())
        assertTrue(contract.exitCriteria.isBlank())
        assertEquals(listOf("1", "三百块"), contract.props)
    }

    @Test
    fun `老场景没有契约时逐字回退固定模板`() {
        val block = ScenePrompt.block(scene(null), "a")!!
        assertTrue(block.contains("说到「底」"))
        assertTrue(block.contains("这段的目标：聊到借钱收场"))
        assertFalse(block.contains("[场景契约]"))
    }

    @Test
    fun `有契约时按契约渲染 角色规则落到双方`() {
        val contract =
            SceneContract(
                languageStyle = "京津口语，短句为主",
                roleRules = mapOf("阿远" to "抛话题、铺包袱", "于谦" to "顺着接、把包袱递回来"),
                taboos = listOf("不提政治"),
                exitCriteria = "借到钱就收",
                props = listOf("三百块"),
            )
        val block = ScenePrompt.block(scene(contract), "a")!!

        assertTrue(block.contains("[场景契约]"))
        assertTrue(block.contains("语言风格：京津口语，短句为主"))
        assertTrue(block.contains("你的角色（阿远）：抛话题、铺包袱"))
        assertTrue(block.contains("对方（于谦）：顺着接、把包袱递回来"))
        assertTrue(block.contains("禁忌：不提政治"))
        assertTrue(block.contains("收尾：借到钱就收"))
        assertTrue(block.contains("可用素材：三百块"))
        assertTrue(block.contains("契约在这个场景内保持不变"))
        assertFalse(block.contains("说到「底」"))
    }

    @Test
    fun `契约全空时仍回退固定模板`() {
        val block = ScenePrompt.block(scene(SceneContract(languageStyle = "  ")), "a")!!
        assertFalse(block.contains("[场景契约]"))
        assertTrue(block.contains("说到「底」"))
    }

    @Test
    fun `契约随场景落库 只读模式只进决策记录`() {
        val store = FakeStore()
        val box = SceneToolbox(store, { SceneMode.ACTIVE }, { "a" }, { "阿远" }, { now })
        val start = box.tools.first { it.spec.name == "start_scene" }
        runBlockingExecute(
            start,
            """
            {
              "role": "comedian",
              "partnerName": "于谦",
              "goal": "借钱",
              "contract": {"languageStyle": "短句", "roleRules": {"你": "抛包袱"}}
            }
            """.trimIndent(),
        )
        val created = store.current!!
        assertEquals("短句", created.contract?.languageStyle)
        assertEquals(mapOf("你" to "抛包袱"), created.contract?.roleRules)

        // OBSERVE：不建场景，但契约要进 argsJson（复盘页据此判断写得好不好）
        val observeStore = FakeStore()
        val observeBox = SceneToolbox(observeStore, { SceneMode.OBSERVE }, { "a" }, { "阿远" }, { now })
        val observeStart = observeBox.tools.first { it.spec.name == "start_scene" }
        runBlockingExecute(
            observeStart,
            """{"role":"comedian","partnerName":"于谦","goal":"借钱","contract":{"languageStyle":"短句"}}""",
        )
        assertNull(observeStore.current)
        val args = observeStore.records.single().argsJson.orEmpty()
        assertTrue(args.contains("\"contract\""))
        assertTrue(args.contains("短句"))
    }

    private class FakeStore(var current: Scene? = null) : SceneStore {
        val records = mutableListOf<SceneDecisionRecord>()

        override suspend fun active(groupId: String): Scene? = current

        override suspend fun activeScenes(): List<Scene> = listOfNotNull(current)

        override suspend fun save(scene: Scene): Scene {
            current = if (scene.id == 0L) scene.copy(id = 1L) else scene
            return current!!
        }

        override suspend fun end(sceneId: Long, nowMillis: Long) {
            current = current?.copy(status = SceneStatus.ENDED)
        }

        override suspend fun lastEndedAt(groupId: String): Long? = null

        override suspend fun record(decision: SceneDecisionRecord) {
            records += decision
        }

        override suspend fun speaksSince(groupId: String, sinceMillis: Long): Int = 0
    }

    private fun runBlockingExecute(
        tool: BotTool,
        args: String,
        turn: ToolTurn = ToolTurn(groupId = "g1", senderId = "a"),
    ): ToolResult {
        var result: ToolResult? = null
        kotlinx.coroutines.runBlocking {
            result = tool.execute(Json.parseToJsonElement(args) as kotlinx.serialization.json.JsonObject, turn)
        }
        return result!!
    }
}
