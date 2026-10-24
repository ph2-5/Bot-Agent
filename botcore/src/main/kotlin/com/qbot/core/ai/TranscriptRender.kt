package com.qbot.core.ai

/**
 * 群聊转录渲染。
 *
 * 之前各处各自拼 `[名字] 内容`：说话人和正文挤在同一行，
 * 正文本身又以人名、方括号或冒号开头时，模型很容易把说话人当成消息的一部分——
 * 于是把 A 的话算到 B 头上，或者把别人的话记成自己说的。
 *
 * 现在统一成两行式：
 *
 * ```
 * [陈泽 · 18:41 · @你]
 * 我爸在绥棱种地呢。
 * ```
 *
 * 第一行只承载元信息，第二行起才是正文；块首再声明一次格式，
 * 让"谁在说"与"说了什么"在结构上不可能混淆。
 */
object TranscriptRender {
    /** 块首格式声明：一句话消除说话人与正文的歧义。 */
    const val FORMAT_HINT =
        "下面每条记录的第一行 [说话人 · 时间/标记] 是元信息，不是消息内容；从第二行起才是他说的话。"

    /**
     * 一条转录记录。
     *
     * @param who 说话人；机器人自己一律传「你」，避免模型把它当成第三方
     * @param body 消息正文
     * @param meta 附加在说话人一行的元信息，如相对时间、「@你」
     */
    data class Line(
        val who: String,
        val body: String,
        val meta: String? = null,
    ) {
        fun render(): String {
            val tag =
                buildString {
                    append('[').append(who)
                    meta?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    append(']')
                }
            return tag + "\n" + body.replace("\n", " ").trim()
        }
    }

    /** 渲染一组记录；空列表返回空串。 */
    fun render(lines: List<Line>): String = lines.joinToString("\n") { it.render() }
}
