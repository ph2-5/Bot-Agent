package com.qbot.core.ai

/** 关系事件：好感与熟识只有这几种来源，避免"随手加一点"导致数值失控。 */
enum class RelationEvent {
    /** 机器人回了这个人一句 */
    SPOKE,

    /** 这个人 @ 了机器人 */
    MENTIONED,

    /** 这个人夸了机器人 */
    PRAISED,

    /** 这个人骂了机器人 */
    INSULTED,
}

/**
 * 机器人对某个人的关系。
 *
 * 两个维度刻意分开，因为它们的语义完全不同：
 * - [familiarity] 是**慢变量**，只涨不跌：来过多少次、聊过多少句。它决定
 *   "能不能开玩笑""要不要主动搭话"——跟一个刚认识的人抖机灵是灾难；
 * - [affinity] 是**快变量**，可升可降，且会随时间回归中性。它决定
 *   "愿不愿意主动开口"，不决定说话的态度。
 *
 * 这条边界必须在提示词里写死：**好感低只让它少主动，绝不冷落、阴阳、攻击**。
 * 一旦让好感影响态度，机器人就会变成一个记仇的东西，那是最难看的拟人。
 */
data class PersonRelation(
    val userId: String,
    val displayName: String? = null,
    /** 0..100 */
    val familiarity: Int = 0,
    /** -100..100 */
    val affinity: Int = 0,
    val interactionCount: Int = 0,
    val lastInteractionAt: Long = 0,
    /** 最近一次变化的说法，如「他夸了你一句」 */
    val lastNote: String? = null,
)

object RelationPolicy {
    const val MAX_FAMILIARITY = 100
    const val MAX_AFFINITY = 100
    const val MIN_AFFINITY = -100

    private const val DAY_MILLIS = 24L * 3600 * 1000

    /**
     * 一次互动后的关系。
     *
     * 数值刻意给得小：聊 50 句才到"挺熟"，夸一句只加 5 点好感。
     * 给大了会变成"人人都是生死之交"，比没有还假。
     */
    fun apply(
        current: PersonRelation?,
        userId: String,
        displayName: String? = null,
        event: RelationEvent,
        at: Long,
        note: String? = null,
    ): PersonRelation {
        val base = current ?: PersonRelation(userId = userId)
        val (deltaFamiliarity, deltaAffinity) =
            when (event) {
                // 回一句只加熟识：聊天是常态，不该无限刷好感
                RelationEvent.SPOKE -> 2 to 0
                RelationEvent.MENTIONED -> 3 to 0
                RelationEvent.PRAISED -> 1 to 5
                RelationEvent.INSULTED -> 1 to -8
            }
        return base.copy(
            displayName = displayName?.takeIf { it.isNotBlank() } ?: base.displayName,
            familiarity =
                (base.familiarity + deltaFamiliarity).coerceIn(0, MAX_FAMILIARITY),
            affinity =
                (base.affinity + deltaAffinity).coerceIn(MIN_AFFINITY, MAX_AFFINITY),
            interactionCount = base.interactionCount + 1,
            lastInteractionAt = at,
            lastNote = note ?: base.lastNote,
        )
    }

    /**
     * 长期不互动，好感向中性回归。
     *
     * 没有这一步，一次吵架会被记一辈子——现实里人也是会淡的。
     * 熟识不衰减：认识就是认识。
     */
    fun decay(current: PersonRelation, now: Long): PersonRelation {
        val idle = now - current.lastInteractionAt
        if (idle < 7 * DAY_MILLIS || current.affinity == 0) return current
        val weeks = (idle / (7 * DAY_MILLIS)).coerceAtMost(4).toInt()
        val step = weeks * 2
        val next =
            if (current.affinity > 0) {
                (current.affinity - step).coerceAtLeast(0)
            } else {
                (current.affinity + step).coerceAtMost(0)
            }
        return current.copy(affinity = next)
    }

    /** 注入提示词的一段话。措辞刻意保守，避免模型把"低好感"演成敌意。 */
    fun describe(relation: PersonRelation): String {
        val familiarity =
            when {
                relation.familiarity >= 80 -> "很熟，聊过很多次"
                relation.familiarity >= 50 -> "挺熟"
                relation.familiarity >= 20 -> "见过几次"
                else -> "刚认识不久"
            }
        val attitude =
            when {
                relation.affinity >= 50 -> "挺愿意跟这个人说话"
                relation.affinity >= 15 -> "印象不错"
                relation.affinity > -15 -> "没什么特别的感觉"
                relation.affinity > -50 -> "不太想主动找这个人搭话"
                else -> "被这个人伤过，会留个心眼"
            }
        return buildString {
            append("你和「").append(relation.displayName ?: "这个人").append("」")
            append(familiarity).append("，").append(attitude).append("。")
            relation.lastNote?.takeIf { it.isNotBlank() }?.let {
                append("最近一次：").append(it).append("。")
            }
            if (relation.familiarity < 20) {
                append("你们还不熟，别自来熟地开玩笑，也别假装很了解对方。")
            }
            if (relation.affinity < -15) {
                // 这条最要紧：好感只影响主动程度，不影响礼数
                append("好感低只意味着你不太会主动找他；他说正事时照常回应，不许冷落、阴阳或翻旧账。")
            }
        }
    }
}

/**
 * 机器人自己的短期心情。
 *
 * 与好感的分工：好感是"对某个人"的长期账，心情是"这会儿"的状态。
 * 被骂了心情会差，但几小时后自己会缓过来——这也是人。
 */
data class BotMood(
    /** 0..100，50 为平常 */
    val value: Int = NEUTRAL,
    val updatedAt: Long = 0,
    val reason: String? = null,
) {
    companion object {
        const val NEUTRAL = 50
    }
}

object MoodPolicy {
    private const val HOUR_MILLIS = 3600_000L

    fun apply(current: BotMood, event: RelationEvent, at: Long): BotMood {
        val delta =
            when (event) {
                RelationEvent.PRAISED -> 12
                RelationEvent.INSULTED -> -20
                // 聊天不改心情。若每条回复都刷新 updatedAt，
                // 时间回归就永远不会触发——说得越勤，气越消不掉，正好反了。
                else -> 0
            }
        if (delta == 0) return current
        return current.copy(
            value = (current.value + delta).coerceIn(0, 100),
            updatedAt = at,
            reason =
                when (event) {
                    RelationEvent.PRAISED -> "有人夸了你"
                    RelationEvent.INSULTED -> "刚被人骂了"
                    else -> current.reason
                },
        )
    }

    /** 每小时向中性回归 1 点——睡一觉就过去了。 */
    fun decay(current: BotMood, now: Long): BotMood {
        val hours = (now - current.updatedAt) / HOUR_MILLIS
        if (hours <= 0) return current
        // 不封顶：封了 24 点的话，隔三天再说话只能回到 34，然后卡住不动了。
        val step = hours.coerceAtMost(200).toInt()
        val next =
            when {
                current.value > BotMood.NEUTRAL ->
                    (current.value - step).coerceAtLeast(BotMood.NEUTRAL)
                current.value < BotMood.NEUTRAL ->
                    (current.value + step).coerceAtMost(BotMood.NEUTRAL)
                else -> current.value
            }
        return current.copy(value = next, updatedAt = now)
    }

    fun describe(mood: BotMood): String? =
        when {
            mood.value >= 75 -> "你现在心情不错，说话会松快一点，别得意忘形。"
            mood.value >= 58 -> "你现在心情还行。"
            mood.value in 43..57 -> null
            mood.value >= 25 ->
                "你现在有点烦。可以话少一点、敷衍一点，但别把火撒在无辜的人身上。"
            else ->
                "你现在很烦。允许你语气冲一点、回得短一点，但不许骂人、不许迁怒。"
        }
}

/** 一条消息在回复时能看到的社交上下文：对谁、我什么心情、群里最近怎么说。 */
data class SocialContext(
    val relation: PersonRelation? = null,
    val mood: BotMood? = null,
    val slang: List<SlangTerm> = emptyList(),
    /**
     * 可以发表情包时给出的候选图地址（来自群内收获 + 手动库）。
     *
     * 空列表 = 没有图可用，提示词里连"你可以发图"都不说——
     * 告诉模型一件它做不到的事，它就会开始编造图片地址。
     */
    val memes: List<String> = emptyList(),
    /**
     * 上一次回这个人时，没说出口的那句话。
     *
     * 这是内心戏与关系的接点：心里烦但嘴上回了，这件事要留下痕迹，
     * 否则每次都要重新忍一遍。只截一小段，且明确"别翻旧账"。
     */
    val lastThought: String? = null,
    /**
     * 人设设定里抽出来的「认识的人 / 特殊说法」。
     *
     * 与 [slang] 的分工：slang 是群里自然长出来的说法，
     * lore 是角色卡里写死的设定（老爹、雷点、彩蛋称呼）。
     */
    val lore: List<LoreEntry> = emptyList(),
)

/** 社交上下文的注入出口，由 BotRuntime 在组装记忆后填入。 */
fun interface SocialContextProvider {
    suspend fun provide(message: com.qbot.core.model.IncomingMessage): SocialContext?
}

/** 关系事件的写入口：由 BotRuntime 在互动发生时调用，Android 侧落库。 */
fun interface RelationRecorder {
    suspend fun record(
        message: com.qbot.core.model.IncomingMessage,
        event: RelationEvent,
        note: String?,
    )
}

/**
 * 夸奖检测。
 *
 * 规则很简单，误判率不算低——但它只加 5 点好感，误判的代价很小；
 * 反过来，漏掉一次真诚的夸奖才是损失。所以宁可宽，不要严。
 */
object PraiseDetector {
    private val WORDS =
        listOf(
            "厉害", "牛", "牛逼", "强", "好强", "棒", "太棒", "靠谱", "聪明", "有道理",
            "说得好", "说得对", "太对了", "谢谢", "感谢", "喜欢", "爱你", "笑死", "绝了",
            "神了", "6", "666", "yyds", "顶", "服了", "可以啊", "不错",
        )

    /**
     * @param isDirected 这条消息是否冲着机器人（@ 或私聊）。
     *   没冲着它说的"厉害"，夸的是别人，不算。
     */
    fun isPraise(text: String, isDirected: Boolean, botNames: Collection<String> = emptyList()): Boolean {
        if (!isDirected) return false
        val trimmed = text.trim()
        // 群里夸人通常很短；超过 30 字基本是在说事，不是夸
        if (trimmed.length > 30) return false
        // 骂人的句子里往往也带"牛逼"（"你牛逼什么"），先让冒犯检测否掉
        if (trimmed.contains("什么") && trimmed.endsWith("啊")) return false
        return WORDS.any { trimmed.contains(it) }
    }
}
