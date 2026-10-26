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
    /**
     * 「你刚说完、对方接上」的时间窗。
     *
     * 超过一分钟就不像在接你的话了——群里话题换得比这快得多。
     */
    private const val REPLY_WINDOW_MILLIS = 60_000L

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

        // 4.4) 你刚说完，对方接着追问 / 用「你」回应：在跟你说话。
        //
        // 下面的两人对话判定只要 recent 里有你自己的发言就直接失效，
        // 而"我刚说完、对方接上"恰恰是最常见的形态——双 bot 对谈尤其如此。
        // 结果是复盘里几乎每条都显示"对全群说"，模型也拿不到正确信号。
        //
        // 条件必须收紧：机器人说完一句，别人接着说"今天好累啊"是对全群感慨，
        // 不能算接话。所以要求「问句」或「出现第二人称」才算指向你。
        val last = recent.lastOrNull()
        if (last != null &&
            last.role == "assistant" &&
            !message.senderId.isNullOrBlank() &&
            message.senderId != selfId &&
            message.timestampMillis >= last.timestampMillis &&
            message.timestampMillis - last.timestampMillis <= REPLY_WINDOW_MILLIS
        ) {
            val trimmed = text.trimEnd()
            val looksLikeReply =
                trimmed.endsWith("?") ||
                    trimmed.endsWith("？") ||
                    text.contains("你") ||
                    text.contains("您")
            if (looksLikeReply) {
                return Addressee(AddresseeKind.BOT, null, "你刚说完，对方在接你的话")
            }
        }

        // 4.5) 最近几条在两个人之间来回：本条即便没 @ 谁，
        //      也是在跟另一个人说话，而不是对全群说。
        //      这是"两个人拌嘴，机器人硬插进去劝架/站队"的直接解药。
        val pair = twoPartyExchange(recent)
        if (pair != null && message.senderId != null && message.senderId in pair) {
            val other =
                recent.lastOrNull {
                    it.role != "assistant" && it.senderId != message.senderId
                }
            return Addressee(
                AddresseeKind.MEMBER,
                other?.senderName ?: "另一位群友",
                "最近几条是你们两人在对话",
            )
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
        // 群友很少叫全名：设定「过去的阿远」，群里只喊「阿远」。
        // 这里与 BotNameMatcher 用同一套后缀规则（最短 2 字），
        // 否则「阿远你个傻福」会被判成"对全群说"（审计：叫简称识别不到）。
        val candidates =
            buildList {
                add(name)
                for (len in name.length - 1 downTo BotNameMatcher.MIN_SUFFIX) {
                    add(name.takeLast(len))
                }
            }
        return candidates.any { candidate ->
            head.startsWith(candidate) ||
                head.startsWith("@$candidate") ||
                head.startsWith("$candidate，") ||
                head.startsWith("$candidate,") ||
                head.startsWith("$candidate：") ||
                head.startsWith("$candidate:")
        }
    }

    /**
     * 最近几条是否构成「两个人之间的对话」。
     *
     * 全部满足才认：窗口内没有机器人的发言（它已入场就不是第三方视角）、
     * 只出现两个发送者，且最后 3 条都在这两人之间。
     */
    private fun twoPartyExchange(recent: List<RoomMessage>): Set<String>? {
        if (recent.any { it.role == "assistant" }) return null
        val window = recent.takeLast(6)
        if (window.size < 3) return null
        val senders =
            window.mapNotNull { it.senderId?.takeIf { id -> id.isNotBlank() } }.distinct()
        if (senders.size != 2) return null
        val tail = window.takeLast(3)
        if (tail.any { it.senderId.isNullOrBlank() || it.senderId !in senders }) return null
        return senders.toSet()
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
