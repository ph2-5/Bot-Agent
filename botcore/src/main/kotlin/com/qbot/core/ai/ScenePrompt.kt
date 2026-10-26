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
        val interrupted =
            interruptedBy?.takeIf { it.isNotBlank() }?.let {
                "刚才「${it.take(20)}」插了句话——可以顺势应一句再回正题，也可以不接；不要装作没听见。\n"
            }.orEmpty()

        // 有契约就按契约演；否则逐字回退 2.13.0 的固定模板。
        val contract = scene.contract?.takeIf { !it.isEmpty() }
        if (contract != null) {
            return contractBlock(scene, me, partner, contract, interrupted)
        }
        return legacyBlock(scene, me, partner, interrupted)
    }

    /**
     * 契约版场景块。
     *
     * 契约在前、动作说明在后：规则决定"怎么说"，动作只是"允许怎么调度"。
     * 末句写死"契约不变"——不给模型中途即兴改设定的机会（第一期不做 update_contract）。
     */
    private fun contractBlock(
        scene: Scene,
        me: SceneParticipant,
        partner: SceneParticipant?,
        contract: SceneContract,
        interrupted: String,
    ): String {
        val selfName = me.displayName.takeIf { it.isNotBlank() } ?: "你"
        val partnerName = partner?.displayName?.takeIf { it.isNotBlank() } ?: "对方"
        // 角色规则里可能用了别的叫法（比如人格里的本名），命中不上就整体列出，不丢信息
        val rules = contract.roleRules
        val selfRule = rules.entries.firstOrNull { namesMatch(it.key, selfName) }?.value
        val partnerRule = rules.entries.firstOrNull { namesMatch(it.key, partnerName) }?.value
        val others =
            rules.filterNot { (name, _) ->
                (selfRule != null && namesMatch(name, selfName)) ||
                    (partnerRule != null && namesMatch(name, partnerName))
            }

        return buildString {
            append("【当前场景】\n")
            append("你正在和「").append(partnerName).append("」说相声")
            append("（第 ").append(scene.turnIndex + 1).append(" 轮，你演")
            append(roleLabel(me.role)).append("）。\n")
            append("[场景契约]\n")
            if (contract.languageStyle.isNotBlank()) {
                append("语言风格：").append(contract.languageStyle).append('\n')
            }
            if (selfRule != null) {
                append("你的角色（").append(selfName).append("）：").append(selfRule).append('\n')
            }
            if (partnerRule != null) {
                append("对方（").append(partnerName).append("）：").append(partnerRule).append('\n')
            }
            others.forEach { (name, rule) ->
                append("「").append(name).append("」：").append(rule).append('\n')
            }
            if (contract.taboos.isNotEmpty()) {
                append("禁忌：").append(contract.taboos.joinToString("；")).append('\n')
            }
            if (contract.exitCriteria.isNotBlank()) {
                append("收尾：").append(contract.exitCriteria).append('\n')
            }
            if (contract.props.isNotEmpty()) {
                append("可用素材：").append(contract.props.joinToString("；")).append('\n')
            }
            append("（契约在这个场景内保持不变，不要中途改写规则）\n")
            if (interrupted.isNotBlank()) append(interrupted)
            append('\n')
            append(actionsBlock())
        }
    }

    /** 2.13.0 的固定模板，一字不改——老场景（contract 为 null）必须行为一致。 */
    private fun legacyBlock(
        scene: Scene,
        me: SceneParticipant,
        partner: SceneParticipant?,
        interrupted: String,
    ): String {
        val partnerName = partner?.displayName?.takeIf { it.isNotBlank() } ?: "对方"
        return buildString {
            append("【当前场景】\n")
            append("你正在和「").append(partnerName).append("」说相声")
            append("（第 ").append(scene.turnIndex + 1).append(" 轮，你演")
            append(roleLabel(me.role)).append("）。\n")
            if (partner?.role != null) {
                append("对方演").append(roleLabel(partner.role)).append("。")
            }
            append(roleHint(me.role)).append('\n')
            if (scene.goal.isNotBlank()) {
                append("这段的目标：").append(scene.goal.take(120)).append('\n')
            }
            append("说到「底」（最后一个包袱抖完）就收，不要拖。\n")
            if (interrupted.isNotBlank()) append(interrupted)
            append('\n')
            append(actionsBlock())
        }
    }

    private fun actionsBlock(): String =
        buildString {
            append("【可用动作】\n")
            append("你说完可以等他（wait_for_reply），他不接可以催（prompt_partner），")
            append("说到结尾就结束（end_scene）。\n")
            append("不要为了用动作而用动作：大多数轮次直接说话就行，不需要调用任何工具。")
        }

    /** 契约里的角色名与参与者显示名是否指同一人：允许一方包含另一方。 */
    private fun namesMatch(
        raw: String,
        display: String,
    ): Boolean {
        val a = raw.trim()
        val b = display.trim()
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    /** 开场时给 start_scene 的说明（工具描述里用）。 */
    const val START_HINT: String =
        "只有在明确要跟某个人演一段（比如对方提议说相声、你主动起一个段子）时才调用。" +
            "普通聊天不要用：一旦开场，接下来若干轮都会被这个场景接管。"
}
