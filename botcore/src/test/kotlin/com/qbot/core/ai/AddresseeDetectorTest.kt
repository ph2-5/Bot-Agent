package com.qbot.core.ai

import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.MessageKind
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话对象推断。
 *
 * 它决定了"这句话该不该由我接"——两个人互聊和对全群抛话题，
 * 是完全不同的两件事。所有判断都必须有可核对的证据。
 */
class AddresseeDetectorTest {
    private val recent =
        listOf(
            RoomMessage(
                senderId = "u-ayuan",
                senderName = "阿远",
                role = "user",
                content = "今晚开黑吗？",
                timestampMillis = 1000L,
            ),
            RoomMessage(
                senderId = null,
                senderName = null,
                role = "assistant",
                content = "我不玩",
                timestampMillis = 2000L,
                externalId = "bot-msg-1",
            ),
        )

    private fun message(
        content: String,
        senderId: String = "u-ming",
        senderName: String = "小明",
        mentions: List<String> = emptyList(),
        quoted: String? = null,
    ) = IncomingMessage(
        kind = MessageKind.GROUP_MESSAGE,
        eventType = "GROUP_MESSAGE_CREATE",
        messageId = "m-1",
        content = content,
        targetId = "g-1",
        senderId = senderId,
        senderName = senderName,
        timestampMillis = 3000L,
        quotedMessageId = quoted,
        raw =
            buildJsonObject {
                if (mentions.isNotEmpty()) {
                    putJsonArray("mentions") {
                        mentions.forEach { id -> addJsonObject { put("id", id) } }
                    }
                }
            },
    )

    @Test
    fun `艾特机器人时认为是对机器人说的`() {
        val addressee =
            AddresseeDetector.infer(
                message("@小白 在吗", mentions = listOf("bot-1")),
                recent,
                botNames = listOf("小白"),
                selfId = "bot-1",
            )
        assertEquals(AddresseeKind.BOT, addressee.kind)
    }

    @Test
    fun `艾特别人时带上对方名字`() {
        val addressee =
            AddresseeDetector.infer(
                message("@阿远 你看这个", mentions = listOf("u-ayuan")),
                recent,
                botNames = listOf("小白"),
                selfId = "bot-1",
            )
        assertEquals(AddresseeKind.MEMBER, addressee.kind)
        assertEquals("阿远", addressee.name)
    }

    @Test
    fun `引用机器人消息时认为是在对机器人说`() {
        val addressee =
            AddresseeDetector.infer(
                message("那你玩什么", quoted = "bot-msg-1"),
                recent,
                botNames = listOf("小白"),
                selfId = "bot-1",
            )
        assertEquals(AddresseeKind.BOT, addressee.kind)
        assertTrue(addressee.evidence.contains("引用"))
    }

    @Test
    fun `开头叫了名字时认为是对那个人说的`() {
        val addressee =
            AddresseeDetector.infer(
                message("阿远，你今晚来吗"),
                recent,
                botNames = listOf("小白"),
                selfId = "bot-1",
            )
        assertEquals(AddresseeKind.MEMBER, addressee.kind)
        assertEquals("阿远", addressee.name)
    }

    @Test
    fun `开头喊机器人简称时认为是对机器人说的`() {
        // 群里没有人叫这个全名，群友只喊后两个字；过去会被判成"对全群说"
        val addressee =
            AddresseeDetector.infer(
                message("阿远你个傻福"),
                recent,
                botNames = listOf("过去的阿远"),
                selfId = "bot-1",
            )
        assertEquals(AddresseeKind.BOT, addressee.kind)
    }

    @Test
    fun `名字后缀不会把普通词误判成叫机器人`() {
        val addressee =
            AddresseeDetector.infer(
                message("远远不够啊"),
                recent,
                botNames = listOf("过去的阿远"),
                selfId = "bot-1",
            )
        assertEquals(AddresseeKind.GROUP, addressee.kind)
    }

    @Test
    fun `上一条不是提问时不做对话对象猜测`() {
        val addressee =
            AddresseeDetector.infer(
                message("来啊"),
                recent,
                botNames = listOf("小白"),
                selfId = "bot-1",
            )
        // 上一条是机器人在说「我不玩」（不带问号），因此这里应回到全群
        assertEquals(AddresseeKind.GROUP, addressee.kind)
    }

    @Test
    fun `没有指向时认为是对全群说的`() {
        val addressee =
            AddresseeDetector.infer(
                message("今天好累啊"),
                recent,
                botNames = listOf("小白"),
                selfId = "bot-1",
            )
        assertEquals(AddresseeKind.GROUP, addressee.kind)
        assertTrue(addressee.evidence.isNotBlank())
    }

    @Test
    fun `回答机器人刚提的问题时认为是对机器人说的`() {
        val history =
            listOf(
                RoomMessage(
                    senderId = null,
                    senderName = null,
                    role = "assistant",
                    content = "你们周末去哪？",
                    timestampMillis = 1000L,
                ),
            )
        val addressee =
            AddresseeDetector.infer(
                message("还没定"),
                history,
                botNames = listOf("小白"),
                selfId = "bot-1",
            )
        assertEquals(AddresseeKind.BOT, addressee.kind)
    }
}
