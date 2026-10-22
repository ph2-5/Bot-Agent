package com.qbot.core.ai

/**
 * 冒犯检测。
 *
 * 群聊参与本来遵循"没 @ 你就少插嘴"，但有两种情况必须破例：
 * 1. 有人**指名**（叫机器人的名字/群名片）并且带侮辱词——装没看见比回错更伤人设；
 * 2. 有人 @ 了**另一个群友**——那是在跟别人说话，再热闹也不该接。
 *
 * 规则刻意保守：必须"名字 + 侮辱词"同时成立才算指名辱骂，
 * 避免群里随便一句脏话就把机器人炸出来。
 */
class InsultDetector {
    companion object {
        /** 侮辱词表：覆盖常见谐音与缩写，宁可漏判也不要滥用 */
        private val INSULT_WORDS =
            listOf(
                "傻逼", "傻b", "傻B", "沙比", "煞笔", "sb", "SB",
                "智障", "弱智", "脑残", "废物", "垃圾", "狗东西", "蠢货", "笨蛋",
                "滚蛋", "滚出", "闭嘴", "去死", "你妈", "他妈的", "妈的", "草泥马", "艹",
            )

        /** 第二人称：出现它才更像在说"你" */
        private val SECOND_PERSON = listOf("你", "您")

        /** 名字后缀最短长度：太短会误伤普通词 */
        private const val MIN_NAME_SUFFIX = 2
    }

    /** 文本里是否带侮辱词。 */
    fun isInsult(text: String): Boolean = INSULT_WORDS.any { text.contains(it, ignoreCase = true) }

    /**
     * 这句话是不是冲着机器人来的。
     *
     * 判定顺序：
     * 1. 文本里出现机器人的完整名字，或名字的后缀（"过去的阿远" → "阿远"）；
     * 2. 且句子里有第二人称（"你/您"）或侮辱词紧跟在名字后。
     *
     * 只看名字不够——"阿远今天没来"不会触发；只看"你"也不够——"你妈的"可能骂别人。
     */
    fun targetsBot(
        text: String,
        names: Collection<String>,
    ): Boolean {
        val hitName =
            names.filter { it.isNotBlank() }.any { name ->
                if (text.contains(name)) {
                    true
                } else {
                    // 官方名常常带前缀（"过去的阿远"），群里只叫后两个字
                    (name.length downTo MIN_NAME_SUFFIX).any { len ->
                        len < name.length && text.contains(name.takeLast(len))
                    }
                }
            }
        if (!hitName) return false
        return SECOND_PERSON.any { text.contains(it) } || isInsult(text)
    }

    /** 是否在跟别人说话（消息里 @ 了非机器人的其他人）。 */
    fun talksToOthers(
        mentionIds: Collection<String>,
        selfId: String,
    ): Boolean = mentionIds.any { it.isNotBlank() && it != selfId }
}
