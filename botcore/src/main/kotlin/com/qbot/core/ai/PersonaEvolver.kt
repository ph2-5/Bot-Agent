package com.qbot.core.ai

/** 一条人设版本快照。 */
data class PersonaSnapshot(
    val id: Long = 0,
    /** 对应的人设 id；null 表示全局系统提示词 */
    val personaId: Long?,
    val name: String,
    val prompt: String,
    val kind: PersonaSnapshotKind,
    /** 说明：为什么改、改了什么 */
    val note: String,
    val createdAt: Long,
)

/** 快照类型。 */
enum class PersonaSnapshotKind {
    /** 初始版本：第一次进化前自动存档，不可删除 */
    INITIAL,

    /** 用户点「AI 优化」产生的大改 */
    EVOLVED,

    /** 手动保存 / 回滚前的存档 */
    MANUAL,

    /** 自动破壳产生的第一版人格 */
    BIRTH,

    /** 自动微调产生的一版 */
    GROWTH,
}

/** 一次人设进化的结果。 */
data class PersonaEvolution(
    /** 进化后的完整提示词 */
    val prompt: String,
    /** 本次改动摘要（由模型自己用一句话说明） */
    val note: String,
)

/**
 * 人设进化器。
 *
 * 让系统提示词随真实对话慢慢变好，而不是一次写死：
 * 模型拿到「当前人设 + 最近的群聊样本 + 用户额外要求」，
 * 在**不改变身份与价值观**的前提下补全表达习惯、口头禅与边界，
 * 删掉与样本冲突或过于笼统的句子。
 *
 * 安全边界（全部在提示词里约束 + 代码里校验）：
 * 1. 结果为空或异常短 → 拒绝，保留原文；
 * 2. 结果超过原文 1.6 倍或超过 1500 字 → 拒绝，防止越改越臃肿；
 * 3. 初始版本由调用方在第一次进化前存档，永远可回滚。
 */
class PersonaEvolver(
    private val client: LlmClient,
) {
    companion object {
        /** 进化结果最大长度 */
        private const val MAX_PROMPT_CHARS = 1500

        /** 结果相对原文的最大膨胀倍数 */
        private const val MAX_GROWTH = 1.6

        /** 低于这个长度视为无效输出 */
        private const val MIN_PROMPT_CHARS = 20
    }

    /**
     * 生成修订后的人设。
     *
     * @throws IllegalStateException 模型输出无效（空、过短、过度膨胀）
     */
    suspend fun evolve(
        personaName: String,
        currentPrompt: String,
        samples: List<RoomMessage>,
        instruction: String? = null,
        config: AiConfig,
    ): PersonaEvolution {
        val transcript =
            TranscriptRender.render(
                samples.takeLast(30).map { msg ->
                    val who = msg.senderName?.takeIf { it.isNotBlank() } ?: "群友"
                    TranscriptRender.Line(
                        who = if (msg.role == "assistant") "你" else who,
                        body = msg.content.take(120),
                    )
                },
            )
        val system =
            "你是人设编辑器，负责改进一条 QQ 机器人的人设提示词。\n" +
                "硬性要求：\n" +
                "1. 保留原人设的身份、性格、价值观与说话风格，不得更换角色或立场；\n" +
                "2. 补全缺失的表达习惯、口头禅、句子长度偏好、场景边界（什么情况不说什么）；\n" +
                "3. 删掉与样本明显冲突、或空泛到无法执行的句子；\n" +
                "4. 只输出改进后的完整提示词正文，不要任何解释、不要 markdown 标题或代码块；\n" +
                "5. 不要虚构具体人物、事件或群名。"
        val user =
            buildString {
                append("人设名称：").append(personaName).append("\n\n")
                append("当前提示词：\n---\n").append(currentPrompt.take(4000)).append("\n---\n\n")
                if (transcript.isNotBlank()) {
                    append("最近的群聊样本（供参考语气与场景，不要照抄内容）：\n")
                    append(TranscriptRender.FORMAT_HINT).append("\n")
                    append(transcript).append("\n\n")
                }
                val extra = instruction?.trim().orEmpty()
                if (extra.isNotEmpty()) {
                    append("用户额外要求（优先满足）：\n").append(extra).append("\n\n")
                }
                append("请输出改进后的完整提示词。")
            }
        val reply =
            client.chat(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                temperature = 0.4,
                maxTokens = 1200,
                thinkingType = null,
            )
        val prompt = sanitize(reply.content)
        require(prompt.length >= MIN_PROMPT_CHARS) { "模型输出过短，已放弃本次进化" }
        require(prompt.length <= MAX_PROMPT_CHARS) { "模型输出过长（${prompt.length} 字），已放弃" }
        require(prompt.length <= (currentPrompt.length * MAX_GROWTH).toInt() + 40) {
            "模型输出膨胀过多（${currentPrompt.length} → ${prompt.length} 字），已放弃"
        }
        return PersonaEvolution(prompt = prompt, note = summarize(prompt))
    }

    /** 去掉模型偶尔带上的代码块围栏与多余空白（与人格成长共用同一实现）。 */
    private fun sanitize(raw: String): String = PromptTextUtils.sanitize(raw)

    /** 用首行做一句话摘要，存进快照备注。 */
    private fun summarize(prompt: String): String {
        val firstLine = prompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return firstLine.take(60).ifBlank { "AI 优化" }
    }
}
