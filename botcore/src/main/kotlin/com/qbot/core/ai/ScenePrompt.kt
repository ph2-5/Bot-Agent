package com.qbot.core.ai

/**
 * 场景提示词块。
 *
 * 独立成一个 system 段，插在人格之后、记忆之前：人格决定"我是谁、怎么说话"，
 * 场景决定"现在在做什么、这一句该起什么作用"。两者分开，
 * 才不会让场景规则污染人格（也方便按场景替换）。
 *
 * 动作说明里必须写"不要为了用动作而用动作"——模型会为了完成"有工具可用"
 * 这个暗示而每轮都调 wait_for_reply，把对谈拖死（实测过的坑）。
 */
object ScenePrompt {

    private fun roleLabel(role: SceneRole?): String =
        when (role) {
            SceneRole.COMEDIAN -> "逗哏"
            SceneRole.STRAIGHT -> "捧哏"
            null -> "对方"
        }

    private fun roleHint(role: SceneRole?): String =
        when (role) {
            SceneRole.COMEDIAN -> "你抛，对方接。每抛一个包袱都要留出让他接话的口子，别自己把话说完。"
            SceneRole.STRAIGHT -> "你接，对方抛。顺着他的话往下垫，把包袱递回去，不要抢过来自己说。"
            null -> "一来一回，把话递过去。"
        }

    /**
     * 生成本轮的场景块；不在场景里时返回 null（调用方不注入即可）。
     *
     * @param interruptedBy 本轮之前有人插话时，把那个人的名字带进来——
     *   模型知道"刚才有人打岔"，就不会当没发生过继续说相声。
     */
    fun block(
        scene: Scene,
        selfId: String,
        interruptedBy: String? = null,
    ): String? {
        if (scene.status == SceneStatus.ENDED) return null
        val me = scene.participants.firstOrNull { it.senderId == selfId } ?: return null
        val partner = scene.partnerOf(selfId)
        val partnerName = partner?.displayName?.takeIf { it.isNotBlank() } ?: "对方"
        val role = roleLabel(me.role)
        val turn = scene.turnIndex + 1

        return buildString {
            append("【当前场景】\n")
            append("你正在和「").append(partnerName).append("」说相声")
            append("（第 ").append(turn).append(" 轮，你演").append(role).append("）。\n")
            if (partner?.role != null) {
                append("对方演").append(roleLabel(partner.role)).append("。")
            }
            append(roleHint(me.role)).append('\n')
            if (scene.goal.isNotBlank()) {
                append("这段的目标：").append(scene.goal.take(120)).append('\n')
            }
            append("说到「底」（最后一个包袱抖完）就收，不要拖。\n")
            if (!interruptedBy.isNullOrBlank()) {
                append("刚才「").append(interruptedBy.take(20)).append("」插了句话——")
                append("可以顺势应一句再回正题，也可以不接；不要装作没听见。\n")
            }
            append('\n')
            append("【可用动作】\n")
            append("你说完可以等他（wait_for_reply），他不接可以催（prompt_partner），")
            append("说到结尾就结束（end_scene）。\n")
            append("不要为了用动作而用动作：大多数轮次直接说话就行，不需要调用任何工具。")
        }
    }

    /** 开场时给 start_scene 的说明（工具描述里用）。 */
    const val START_HINT: String =
        "只有在明确要跟某个人演一段（比如对方提议说相声、你主动起一个段子）时才调用。" +
            "普通聊天不要用：一旦开场，接下来若干轮都会被这个场景接管。"
}
