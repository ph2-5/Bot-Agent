package com.qbot.core.ai

import com.qbot.core.api.SearchResult
import com.qbot.core.model.IncomingMessage

/**
 * 聊天时遇到没见过的说法，主动去问一下。
 *
 * 与 [SlangHarvest] 的分工：
 * - 收获是**事后统计**：一个词在群里出现三次以上，才值得整理成条目；
 * - 这里是**当场遇到**：第一次见到就查，因为它可能只出现一次，但那次正好是在跟它说话。
 *
 * 为什么只做异步：查一次要一次模型往返，压在回复链路上会让它答得更慢。
 * 这一轮不懂就先按人设处理（"这是啥意思"本身也很像人），下一轮就懂了。
 */
fun interface SlangLearner {
    suspend fun maybeLearn(message: IncomingMessage, text: String)
}

/**
 * 从一条消息里挑出"可能不懂、又值得查"的说法。
 *
 * 只认三种形态：英文缩写、数字梗、叠字。不是偷懒——不认识的普通词太多
 * （"天气""地铁""会议"），每见一个就查会把额度烧光，而这三类误报最低，
 * 覆盖了绝大多数网络黑话。
 */
object UnknownTermDetector {
    private val ENGLISH = Regex("^[A-Za-z]{2,8}$")
    private val NUMBER = Regex("^\\d{3,6}$")

    fun pick(
        text: String,
        known: Set<String>,
        names: Collection<String> = emptyList(),
    ): String? {
        if (text.isBlank()) return null
        for (chunk in SlangHarvest.SEPARATORS.split(text)) {
            val piece = chunk.trim()
            if (piece.length !in 2..8) continue
            if (piece in known) continue
            if (piece in SlangHarvest.STOP) continue
            if (piece.contains("@") || piece.contains("<") || piece.contains("/")) continue
            if (names.any { it.length >= 2 && piece.contains(it) }) continue
            if (!looksLikeSlang(piece)) continue
            return piece
        }
        return null
    }

    internal fun looksLikeSlang(piece: String): Boolean {
        if (ENGLISH.matches(piece)) return true
        if (NUMBER.matches(piece)) return true
        // 前两字相同：绝绝子、萌萌哒、绷绷住
        if (piece.length >= 3 && piece[0] == piece[1]) return true
        // ABAB / AABB
        if (piece.length == 4 && piece[0] == piece[2] && piece[1] == piece[3]) return true
        return false
    }
}

/**
 * 问模型"这个词什么意思"。
 *
 * 诚实说明：这不是联网搜索，用的是模型自己的知识。对已经进入语料的梗够用；
 * 对这几天刚火的词，它可能答不上来——那就返回 null，不入库，
 * 等群里出现次数够了走 [SlangHarvest] 或者主人手动补。
 */
class SlangLookup(private val client: LlmClient) {
    suspend fun explain(
        term: String,
        context: String,
        config: AiConfig,
        evidence: List<SearchResult> = emptyList(),
    ): SlangTerm? {
        val system =
            "你在帮一个 QQ 群机器人理解群里的说法。\n" +
                "请给出「$term」在中文网络语境或群聊里最可能的意思。\n" +
                "如果它是人名、地名、公司名、普通日常用语，或者你完全没见过，只输出 UNKNOWN。\n" +
                "如果知道，严格按两行输出，不要多余内容：\n" +
                "意思：不超过 20 字\n" +
                "场合：不超过 10 字，什么情况下用"
        val user =
            buildString {
                append("说法：").append(term).append('\n')
                if (context.isNotBlank()) {
                    append("它出现的位置：「")
                        .append(context.replace("\n", " ").take(80))
                        .append("」\n")
                }
                if (evidence.isNotEmpty()) {
                    append("\n实时搜索到的资料（可能不完整，只作参考）：\n")
                    evidence.take(5).forEach { item ->
                        append("- ")
                        append(item.title.take(60))
                        if (item.brief().isNotBlank()) append("：").append(item.brief())
                        append('\n')
                    }
                    append("资料能说明意思就按资料答；互相矛盾时给最常见的说法；都说不清就输出 UNKNOWN。")
                }
            }
        val reply =
            client.chat(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                temperature = 0.1,
                maxTokens = 200,
                // 这是后台小任务，宁可查不到也不要拖住
                timeoutSeconds = 15,
                thinkingType = ThinkingParams.forConfig(config, forceDisable = true),
            )
        return parse(reply.content, term)
    }

    internal fun parse(raw: String, term: String): SlangTerm? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.uppercase().contains("UNKNOWN")) return null
        val lines = text.lineSequence().map { it.trim() }.toList()
        fun valueOf(prefix: String): String? =
            lines.firstOrNull { it.startsWith(prefix) }
                ?.let { line ->
                    val cut = line.substringAfter('：', "").ifBlank { line.substringAfter(':', "") }
                    cut.trim()
                }
        val meaning = valueOf("意思")?.takeIf { it.isNotBlank() } ?: return null
        val usage = valueOf("场合")?.takeIf { it.isNotBlank() }
        return SlangTerm(
            term = term,
            meaning = meaning.take(40),
            usage = usage?.take(30),
            source = SlangTerm.SOURCE_LEARNED,
        )
    }
}
