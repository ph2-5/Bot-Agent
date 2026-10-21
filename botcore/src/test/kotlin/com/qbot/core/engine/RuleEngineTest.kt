package com.qbot.core.engine

import com.qbot.core.model.EventType
import com.qbot.core.model.MessageKind
import com.qbot.core.model.parseIncomingMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun groupMessage(content: String) =
        parseIncomingMessage(
            EventType.GROUP_AT_MESSAGE_CREATE,
            json.parseToJsonElement(
                """{"id":"1","group_openid":"g1","op_member_openid":"u1","content":${Json.encodeToString(content)},"timestamp":"1"}""",
            ),
        )!!

    private fun privateMessage(content: String) =
        parseIncomingMessage(
            EventType.C2C_MESSAGE_CREATE,
            json.parseToJsonElement(
                """{"id":"2","author":{"user_openid":"u2"},"content":${Json.encodeToString(content)},"timestamp":"1"}""",
            ),
        )!!

    private val basicRule =
        Rule(
            id = "r1",
            name = "打招呼",
            matchType = MatchType.CONTAINS,
            pattern = "你好",
            reply = "你好呀！",
        )

    @Test
    fun `包含匹配生效`() {
        assertTrue(RuleEngine(listOf(basicRule)).match(groupMessage("你好"), "100") != null)
    }

    @Test
    fun `未命中时返回 null`() {
        assertNull(RuleEngine(listOf(basicRule)).match(groupMessage("再见"), "100"))
    }

    @Test
    fun `精确匹配区分全等`() {
        val engine =
            RuleEngine(
                listOf(basicRule.copy(matchType = MatchType.EXACT, pattern = "你好")),
            )
        assertTrue(engine.match(groupMessage("你好"), "100") != null)
        assertNull(engine.match(groupMessage("你好呀"), "100"))
    }

    @Test
    fun `前缀匹配生效`() {
        val engine =
            RuleEngine(
                listOf(basicRule.copy(matchType = MatchType.PREFIX, pattern = "/ping")),
            )
        assertTrue(engine.match(groupMessage("/ping now"), "100") != null)
        assertNull(engine.match(groupMessage("say /ping"), "100"))
    }

    @Test
    fun `正则匹配生效`() {
        val engine =
            RuleEngine(
                listOf(basicRule.copy(matchType = MatchType.REGEX, pattern = """^查询\s*(\d+)$""")),
            )
        assertTrue(engine.match(groupMessage("查询 12345"), "100") != null)
        assertNull(engine.match(groupMessage("查询 abc"), "100"))
    }

    @Test
    fun `非法正则不会导致崩溃而是跳过`() {
        val engine =
            RuleEngine(
                listOf(basicRule.copy(matchType = MatchType.REGEX, pattern = """([unclosed""")),
            )
        assertNull(engine.match(groupMessage("你好"), "100"))
    }

    @Test
    fun `禁用的规则不参与匹配`() {
        val engine = RuleEngine(listOf(basicRule.copy(enabled = false)))
        assertNull(engine.match(groupMessage("你好"), "100"))
    }

    @Test
    fun `高优先级规则优先命中`() {
        val low = basicRule.copy(id = "low", pattern = "你好", reply = "低优先级", priority = 0)
        val high = basicRule.copy(id = "high", pattern = "你好", reply = "高优先级", priority = 10)
        // 故意把低优先级放在列表前面，验证结果由 priority 而非列表顺序决定
        val hit = RuleEngine(listOf(low, high)).match(groupMessage("你好"), "100")
        assertEquals("高优先级", hit!!.reply)
    }

    @Test
    fun `作用域过滤生效`() {
        val groupOnly = basicRule.copy(scope = RuleScope.GROUP_ONLY)
        val engine = RuleEngine(listOf(groupOnly))
        assertTrue(engine.match(groupMessage("你好"), "100") != null)
        assertNull(engine.match(privateMessage("你好"), "100"))

        val privateOnly = RuleEngine(listOf(basicRule.copy(scope = RuleScope.PRIVATE_ONLY)))
        assertTrue(privateOnly.match(privateMessage("你好"), "100") != null)
        assertNull(privateOnly.match(groupMessage("你好"), "100"))
    }

    @Test
    fun `空文本不匹配任何规则`() {
        assertNull(RuleEngine(listOf(basicRule)).match(groupMessage("   "), "100"))
    }

    @Test
    fun `回复模板占位符被正确渲染`() {
        val msg = groupMessage("查询余额")
        val rendered = RuleEngine.render("你说的是：{content}{nl}来自 {sender}", msg, "100")
        assertEquals("你说的是：查询余额\n来自 u1", rendered)
    }

    @Test
    fun `消息类型覆盖全部场景`() {
        assertEquals(4, MessageKind.entries.size)
    }
}
