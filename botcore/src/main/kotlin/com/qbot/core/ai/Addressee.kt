package com.qbot.core.ai

import com.qbot.core.model.IncomingMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 一句话大概率是对谁说的。 */
enum class AddresseeKind {
    /** 对机器人说的（@ 了它，或在回应它刚才的问题） */
    BOT,

    /** 对群里某个特定成员说的 */
    MEMBER,

    /** 说给全群听的（抛话题、感慨、没人可指） */
    GROUP,

    /** 看不出来 */
    UNKNOWN,
}

/**
 * 对话对象推断结果。
 *
 * 人说话是有对象的：两个人在互道晚安时插一句会很怪，
 * 而"有人吗"这种对全群的话不接才怪。模型需要这个信息，
 * 否则只能靠语气猜——这也是"缺乏语境判断"的主要来源之一。
 */
data class Addressee(
    val kind: AddresseeKind,
    /** 对某个成员说时的名字 */
    val name: String? = null,
    /** 判断依据（写进提示词与复盘，便于核对） */
    val evidence: String = "",
)

/**
 * 基于可见信号的对话对象推断。
 *
 * 只用确定性证据，不做猜测式建模：
 * 1. @ 提到谁（机器人/其他成员）；
 * 2. 引用了哪条消息（机器人/某人）；
 * 3. 开头叫了谁的名字；
 * 4. 上一条是提问且本条很短——大概率在回答那个人（或回答机器人）。
 *
 * 都对不上就认为是对全群说的，交给模型按语境判断。
 */
object AddresseeDetector {
    fun infer(
        message: IncomingMessage,
        recent: List<RoomMessage>,
        botNames: Collection<String>,
        selfId: String,
    ): Addressee {
        val text = message.content.trim()
        val mentionIds = mentionIdsOf(message)
        val nameOf = nameLookup(recent)
        val bots = botNames.mapNotNull { it.takeIf { n -> n.isNotBlank() } }

        // 1) 直接 @ 了机器人
        if (selfId.isNotBlank() && (text.contains("<@!$selfId>") || mentionIds.contains(selfId))) {
            return Addressee(AddresseeKind.BOT, null, "@ 了你")
        }

        // 2) @ 了其他成员
        if (mentionIds.isNotEmpty()) {
            val name = mentionIds.firstNotNullOfOrNull { nameOf[it] }
            return Addressee(AddresseeKind.MEMBER, name ?: "某位群友", "@ 了对方")
        }

        // 3) 引用了哪条消息
        message.quotedMessageId?.takeIf { it.isNotBlank() }?.let { id ->
            val quoted = recent.lastOrNull { it.externalId == id }
            if (quoted != null) {
                return if (quoted.role == "assistant") {
                    Addressee(AddresseeKind.BOT, null, "引用了你刚才的话")
                } else {
                    Addressee(
                        AddresseeKind.MEMBER,
                        quoted.senderName ?: "某位群友",
                        "引用了对方的消息",
                    )
                }
            }
            return Addressee(AddresseeKind.MEMBER, null, "引用了某条消息")
        }

        // 4) 开头叫了名字
        val head = text.take(12)
        val called =
            bots.firstOrNull { name -> startsWithName(head, name) }
                ?: nameOf.values.firstOrNull { name -> startsWithName(head, name) }
        if (called != null) {
            return if (bots.any { it == called }) {
                Addressee(AddresseeKind.BOT, null, "叫了你的名字")
            } else {
                Addressee(AddresseeKind.MEMBER, called, "开头叫了对方的名字")
            }
        }

        // 5) 上一条是提问、本条很短——在接那个人的话
        val prev = recent.lastOrNull()
        if (prev != null &&
            prev.senderId != message.senderId &&
            (prev.content.trimEnd().endsWith("?") || prev.content.trimEnd().endsWith("？")) &&
            text.length <= 15
        ) {
            return if (prev.role == "assistant") {
                Addressee(AddresseeKind.BOT, null, "在回应你刚才的问题")
            } else {
                Addressee(
                    AddresseeKind.MEMBER,
                    prev.senderName ?: "上一位",
                    "在接上一位的话",
                )
            }
        }

        // 6) 没有指向：抛给全群的感慨/提问
        return Addressee(AddresseeKind.GROUP, null, "没有明确指向，像是对全群说")
    }

    private fun startsWithName(head: String, name: String): Boolean {
        if (name.isBlank()) return false
        return head.startsWith(name) ||
            head.startsWith("@$name") ||
            head.startsWith("$name，") ||
            head.startsWith("$name,") ||
            head.startsWith("$name：") ||
            head.startsWith("$name:")
    }

    private fun nameLookup(recent: List<RoomMessage>): Map<String, String> =
        recent
            .mapNotNull { msg ->
                val id = msg.senderId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = msg.senderName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to name
            }.toMap()

    private fun mentionIdsOf(message: IncomingMessage): List<String> {
        val mentions = message.raw["mentions"] as? JsonArray ?: return emptyList()
        return mentions.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            listOf("id", "member_openid", "user_openid", "union_openid")
                .firstNotNullOfOrNull { key -> obj[key]?.jsonPrimitive?.contentOrNull }
        }
    }
}
