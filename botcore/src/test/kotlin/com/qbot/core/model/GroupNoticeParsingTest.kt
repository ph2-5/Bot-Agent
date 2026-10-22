package com.qbot.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 群/好友生命周期通知的解析测试。
 *
 * 字段名以官方 wiki 为准（botpy 未收录这些事件）：
 * - GROUP_ADD_ROBOT / GROUP_DEL_ROBOT：{timestamp, group_openid, op_member_openid}
 * - GROUP_MEMBER_ADD：{timestamp, group_openid, member_openid, user_openid}
 * - GROUP_MSG_RECEIVE / GROUP_MSG_REJECT：{timestamp, group_openid}
 * - FRIEND_ADD / FRIEND_DEL：{timestamp, openid}
 */
class GroupNoticeParsingTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `机器人加入群聊`() {
        val notice =
            parseGroupNotice(
                "GROUP_ADD_ROBOT",
                obj("""{"timestamp":1784570534,"group_openid":"G1","op_member_openid":"M1"}"""),
            )
        assertEquals(GroupNoticeKind.ROBOT_ADDED, notice!!.kind)
        assertEquals("G1", notice.groupId)
        assertEquals("M1", notice.actorId)
        assertEquals(1_784_570_534_000L, notice.timestampMillis)
    }

    @Test
    fun `机器人被移出群聊`() {
        val notice =
            parseGroupNotice(
                "GROUP_DEL_ROBOT",
                obj("""{"timestamp":1784570534,"group_openid":"G1","op_member_openid":"M1"}"""),
            )
        assertEquals(GroupNoticeKind.ROBOT_REMOVED, notice!!.kind)
        assertEquals("G1", notice.groupId)
    }

    @Test
    fun `新成员加入群聊`() {
        val notice =
            parseGroupNotice(
                "GROUP_MEMBER_ADD",
                obj(
                    """{"timestamp":1784276757,"group_openid":"G2","member_openid":"M9","user_openid":"U9"}""",
                ),
            )
        assertEquals(GroupNoticeKind.MEMBER_ADDED, notice!!.kind)
        assertEquals("G2", notice.groupId)
        assertEquals("M9", notice.actorId)
    }

    @Test
    fun `好友添加与删除`() {
        val add =
            parseGroupNotice("FRIEND_ADD", obj("""{"timestamp":1784570534,"openid":"U1"}"""))
        assertEquals(GroupNoticeKind.FRIEND_ADDED, add!!.kind)
        assertNull("好友事件不涉及群", add.groupId)
        assertEquals("U1", add.actorId)

        val del =
            parseGroupNotice("FRIEND_DEL", obj("""{"timestamp":1784570534,"openid":"U1"}"""))
        assertEquals(GroupNoticeKind.FRIEND_REMOVED, del!!.kind)
    }

    @Test
    fun `主动消息开关`() {
        val receive =
            parseGroupNotice(
                "GROUP_MSG_RECEIVE",
                obj("""{"timestamp":1784570534,"group_openid":"G3"}"""),
            )
        assertEquals(GroupNoticeKind.PROACTIVE_ALLOWED, receive!!.kind)

        val reject =
            parseGroupNotice(
                "GROUP_MSG_REJECT",
                obj("""{"timestamp":1784570534,"group_openid":"G3"}"""),
            )
        assertEquals(GroupNoticeKind.PROACTIVE_REJECTED, reject!!.kind)
    }

    @Test
    fun `非群通知事件返回 null`() {
        assertNull(parseGroupNotice("GROUP_AT_MESSAGE_CREATE", obj("""{"id":"x"}""")))
        assertNull(parseGroupNotice("READY", obj("""{"session_id":"s"}""")))
        assertNull(parseGroupNotice("SOMETHING_NEW", obj("""{"a":1}""")))
    }

    @Test
    fun `毫秒级时间戳不重复换算`() {
        val notice =
            parseGroupNotice(
                "GROUP_ADD_ROBOT",
                obj("""{"timestamp":1784570534123,"group_openid":"G1"}"""),
            )
        // 已经是毫秒（超出秒级合理范围）时原样保留
        assertEquals(1_784_570_534_123L, notice!!.timestampMillis)
    }
}
