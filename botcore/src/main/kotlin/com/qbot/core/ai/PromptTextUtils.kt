package com.qbot.core.ai

/**
 * 模型输出的通用清理。
 *
 * 之所以独立成一处：人格进化（[PersonaEvolver]）与人格成长（[PersonaGrowth]）
 * 都要处理"模型偶尔给代码块围栏"这件事，两份实现早晚会不一致。
 */
internal object PromptTextUtils {
    /** 去掉 ``` 围栏（含 ```markdown 这类语言标记）与多余空白。 */
    fun sanitize(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```")
            val firstNewline = text.indexOf('\n')
            if (firstNewline >= 0 && text.substring(0, firstNewline).isNotBlank()) {
                // ```markdown 这类语言标记
                text = text.substring(firstNewline + 1)
            }
            text = text.removeSuffix("```")
        }
        return text.trim()
    }
}
