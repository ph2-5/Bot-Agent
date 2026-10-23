package com.qbot.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 人设设定里的「已知信息」。
 *
 * 角色卡里经常夹着两类东西：**它认识的人**（「老爹」「主人」以及该怎么对待）
 * 和**带特殊含义的说法**（外号、雷点、口癖）。这些东西写在人设正文里，
 * 模型只能靠通读全文去猜；抽出来单独存之后：
 * - 不会被人设成长改写冲掉；
 * - 可以在维护端逐条查看、停用、删除；
 * - 也能作为「梗」补进群内说法库，说话时自然用上。
 *
 * 注意与 [StyleRule] 的分工：这里存「设定里的事实」，不存「怎么说话」的规则。
 */
enum class LoreKind {
    /** 人设里明确提到的人 / 称呼对象 */
    PERSON,

    /** 人设里赋予特殊含义的说法、外号、雷点 */
    SLANG,

    /** 人物之间的关系（「A 是 B 的爹」这类） */
    RELATION,

    /** 其他设定备注 */
    NOTE,
}

enum class LoreSource {
    /** 由模型从人设正文里抽取 */
    AI,

    /** 维护端手动添加 */
    MANUAL,
}

data class LoreEntry(
    val id: Long = 0,
    /** 归属的人格档案；null 表示全局 */
    val personaId: Long? = null,
    val kind: LoreKind,
    /** 人名 / 说法本身 */
    val name: String,
    /** 一句话说明：这是什么人、这个说法什么意思 */
    val note: String,
    /** 只在某个群成立的设定；null 表示所有群 */
    val scopeGroupId: String? = null,
    val enabled: Boolean = true,
    val source: LoreSource = LoreSource.AI,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

interface LoreStore {
    /** 某个人格 + 某个群可用的设定（全局设定始终包含） */
    suspend fun forPersona(personaId: Long?, groupId: String?): List<LoreEntry>

    suspend fun all(): List<LoreEntry>

    /** 同名同类型同人格视为同一条，覆盖更新而不是堆重复行 */
    suspend fun save(entry: LoreEntry): Long

    suspend fun delete(id: Long)

    suspend fun setEnabled(id: Long, enabled: Boolean)

    suspend fun clearPersona(personaId: Long?)
}

/** 把设定渲染成提示词块。 */
object LoreRender {
    /** 注入条数上限：设定是背景板，不是台词本。 */
    const val MAX_INJECTED = 10

    fun block(entries: List<LoreEntry>): String? {
        val usable =
            entries.filter { it.enabled && it.name.isNotBlank() && it.note.isNotBlank() }
                .take(MAX_INJECTED)
        if (usable.isEmpty()) return null
        return buildString {
            append("人设里已经定下、你应当当作已知的事实（别反问，也别和这些设定冲突）：")
            usable.forEach { entry ->
                when (entry.kind) {
                    LoreKind.PERSON ->
                        append("\n- 认识的人「").append(entry.name).append("」：").append(entry.note)
                    LoreKind.SLANG ->
                        append("\n- 说法「").append(entry.name).append("」：").append(entry.note)
                    LoreKind.RELATION ->
                        append("\n- 关系「").append(entry.name).append("」：").append(entry.note)
                    LoreKind.NOTE ->
                        append("\n- 设定「").append(entry.name).append("」：").append(entry.note)
                }
            }
            append("\n这些是设定背景，不要主动向群里复述；相关话题出现时自然用上即可。")
        }
    }
}

/** 内存实现（测试用）。 */
class InMemoryLoreStore : LoreStore {
    private val rows = mutableListOf<LoreEntry>()
    private var nextId = 1L

    override suspend fun forPersona(personaId: Long?, groupId: String?): List<LoreEntry> =
        rows.filter { row ->
            row.enabled &&
                (row.personaId == null || row.personaId == personaId) &&
                (row.scopeGroupId == null || row.scopeGroupId == groupId)
        }

    override suspend fun all(): List<LoreEntry> = rows.toList()

    override suspend fun save(entry: LoreEntry): Long {
        val index =
            rows.indexOfFirst {
                it.personaId == entry.personaId &&
                    it.kind == entry.kind &&
                    it.name == entry.name &&
                    it.scopeGroupId == entry.scopeGroupId
            }
        if (index >= 0) {
            val existing = rows[index]
            rows[index] = entry.copy(id = existing.id, createdAt = existing.createdAt)
            return existing.id
        }
        val id = nextId++
        rows += entry.copy(id = id)
        return id
    }

    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(enabled = enabled)
    }

    override suspend fun clearPersona(personaId: Long?) {
        rows.removeAll { it.personaId == personaId }
    }
}

/**
 * 从人设正文里抽取「认识的人」与「特殊说法」。
 *
 * 一次性调用，产物落库后就不再需要模型。抽取范围刻意收窄：
 * 不抽写作规则（那是 [StyleRule] 的事）、不抽性格形容词、不编造。
 */
class PersonaLoreExtractor(private val client: LlmClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun extract(
        personaPrompt: String,
        personaId: Long?,
        config: AiConfig,
        now: Long = System.currentTimeMillis(),
    ): List<LoreEntry> {
        val prompt = personaPrompt.trim()
        if (prompt.isEmpty()) return emptyList()
        val system =
            "你在整理一张角色卡（人设）里的长期设定。只抽取两类东西：\n" +
                "1. people：角色卡里明确提到、且角色需要区别对待的人或称呼对象" +
                "（例如「老爹」「主人」「群友」），连同「该怎么对待」；\n" +
                "2. slang：角色卡赋予特殊含义的说法、外号、雷点、口癖" +
                "（例如某个外号是雷点、被叫了会炸毛）。\n" +
                "不要抽取写作规则（短句、别用 Markdown、去 AI 味这些另有系统管理），" +
                "不要抽取性格形容词，不要编造角色卡里没有的东西。\n" +
                "只输出 JSON，不要解释、不要代码块：\n" +
                "{\"people\":[{\"name\":\"称呼\",\"note\":\"一句话说明怎么对待\"}]," +
                "\"slang\":[{\"term\":\"说法\",\"meaning\":\"什么意思\",\"usage\":\"什么场合用，可空\"}]}\n" +
                "限制：people 最多 8 条，slang 最多 8 条；" +
                "name / term 不超过 12 字；note / meaning 不超过 40 字；没有就输出 []。"
        val user = "角色卡正文：\n" + prompt.take(6000)
        val reply =
            client.chat(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                temperature = 0.1,
                maxTokens = GenerationLimits.forConfig(config),
                thinkingType = ThinkingParams.forConfig(config, forceDisable = true),
            )
        return parse(reply.content, personaId, now)
    }

    internal fun parse(
        raw: String,
        personaId: Long?,
        now: Long,
    ): List<LoreEntry> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val body = raw.substring(start, end + 1)
        val payload =
            runCatching { json.decodeFromString<RawLore>(body) }.getOrNull()
                ?: return emptyList()
        val now2 = now
        val people =
            payload.people
                .filter { it.name.isNotBlank() && it.note.isNotBlank() }
                .distinctBy { it.name.trim() }
                .take(8)
                .map {
                    LoreEntry(
                        personaId = personaId,
                        kind = LoreKind.PERSON,
                        name = it.name.trim().take(12),
                        note = it.note.trim().take(40),
                        source = LoreSource.AI,
                        createdAt = now2,
                        updatedAt = now2,
                    )
                }
        val slang =
            payload.slang
                .filter { it.term.isNotBlank() && it.meaning.isNotBlank() }
                .distinctBy { it.term.trim() }
                .take(8)
                .map {
                    LoreEntry(
                        personaId = personaId,
                        kind = LoreKind.SLANG,
                        name = it.term.trim().take(12),
                        note = buildString {
                            append(it.meaning.trim().take(40))
                            it.usage?.trim()?.takeIf { usage -> usage.isNotEmpty() }?.let { usage ->
                                append("（").append(usage.take(20)).append("）")
                            }
                        },
                        source = LoreSource.AI,
                        createdAt = now2,
                        updatedAt = now2,
                    )
                }
        return people + slang
    }

    @Serializable
    private data class RawLore(
        val people: List<RawPerson> = emptyList(),
        val slang: List<RawSlang> = emptyList(),
    )

    @Serializable
    private data class RawPerson(
        val name: String = "",
        val note: String = "",
    )

    @Serializable
    private data class RawSlang(
        val term: String = "",
        val meaning: String = "",
        val usage: String? = null,
    )
}
