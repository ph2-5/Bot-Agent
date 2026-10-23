package com.qbot.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 模型可调用的一次工具。
 *
 * 注意这里刻意没有引入「Agent」概念：群聊场景需要的是**一次回复里选一个确定的动作**，
 * 而不是让模型自主规划多步。工具由本地校验参数、执行并返回结果，
 * 模型只负责选择调用哪一个。
 */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema（OpenAI tools 的 function.parameters 原样透传） */
    val parameters: JsonObject,
)

/** 模型请求调用工具。arguments 保留原始 JSON 文本，便于日志与调试。 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * 工具执行结果。
 *
 * [memeUrl] 是目前唯一的附件出口：工具只负责选定图片，真正上传/发送仍由
 * BotRuntime 完成——失败时可以只丢图、不影响文字回复。
 */
data class ToolResult(
    val content: String,
    val memeUrl: String? = null,
)

/** 一个可执行工具。实现方负责参数解析、校验与副作用。 */
interface BotTool {
    val spec: ToolSpec

    suspend fun execute(arguments: JsonObject): ToolResult
}

/** 工具注册表：模型看到 schema，执行时按名字查找。 */
class ToolRegistry(tools: List<BotTool> = emptyList()) {
    private val ordered = tools.toList()
    private val byName = ordered.associateBy { it.spec.name }

    val specs: List<ToolSpec> get() = ordered.map { it.spec }

    /**
     * 只挑本轮真正能用的工具。
     *
     * 例如联网开关关着、表情库为空时，对应的 schema 不该发给模型，
     * 否则它会调用一个注定返回「不可用」的函数，白白消耗一轮工具循环。
     */
    fun specsExcept(excluded: Set<String>): List<ToolSpec> =
        ordered.filter { it.spec.name !in excluded }.map { it.spec }

    val isEmpty: Boolean get() = ordered.isEmpty()

    suspend fun execute(call: ToolCall): ToolResult {
        val tool =
            byName[call.name]
                ?: return ToolResult("未知工具「${call.name}」，本次未执行。")
        val parsed =
            runCatching { Json.parseToJsonElement(call.arguments) }.getOrElse {
                return ToolResult("工具「${call.name}」参数不是合法 JSON：${it.message}")
            }
        val arguments =
            parsed as? JsonObject
                ?: return ToolResult("工具「${call.name}」参数必须是 JSON 对象。")
        return runCatching { tool.execute(arguments) }.getOrElse {
            ToolResult("工具「${call.name}」执行失败：${it.message}")
        }
    }
}

/**
 * 表情包库的最小读写接口。
 *
 * botcore 不依赖 Room，Android 侧的 RoomSocialStore 实现它；
 * 测试用内存实现即可验证「只发库里有的图」这条约束。
 */
interface MemeLibrary {
    /** 这张图是否真的在候选库里（防止模型编造 URL）。 */
    suspend fun contains(url: String): Boolean

    /** 成功发出后累加采用次数。 */
    suspend fun recordUse(url: String)

    /** 用户明确要表情包时，由本地直接挑一张。 */
    suspend fun pick(): String?
}

/**
 * 发一张表情包。
 *
 * 只接受候选库里的原始 URL：模型可以自由选哪张，但不能凭空造地址。
 * 工具本身不发送，返回 [ToolResult.memeUrl] 交给 BotRuntime 走上传通道。
 */
class SendMemeTool(private val library: MemeLibrary) : BotTool {
    override val spec =
        ToolSpec(
            name = "send_meme",
            description =
                "从当前候选表情库中挑一张发给这个群。" +
                    "用户明确要表情包、或文字不足以表达情绪时调用；一次最多一张。",
            parameters =
                Json.parseToJsonElement(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "url": {
                          "type": "string",
                          "description": "候选列表中的原始图片 URL，必须原样复制，不要编造"
                        }
                      },
                      "required": ["url"]
                    }
                    """.trimIndent(),
                ) as JsonObject,
        )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val url = arguments["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (url.isBlank()) return ToolResult("缺少表情图片地址，未发送。")
        if (!library.contains(url)) return ToolResult("这张图不在候选库里，未发送。")
        return ToolResult("已选定一张表情包，稍后随回复发出。", memeUrl = url)
    }
}
