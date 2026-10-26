package com.qbot.app.runtime

import com.qbot.app.data.db.AppDatabase
import com.qbot.app.data.db.RoomMessageEntity
import com.qbot.app.data.repo.RoomDriveStore
import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.AutonomousDrive
import com.qbot.core.ai.AutonomousGate
import com.qbot.core.ai.AutonomousRecord
import com.qbot.core.ai.AutonomyMode
import com.qbot.core.ai.AuxAiConfig
import com.qbot.core.ai.ConversationKeys
import com.qbot.core.ai.DriveConfig
import com.qbot.core.ai.DriveState
import com.qbot.core.ai.RoomMessage
import com.qbot.core.ai.SceneMode
import com.qbot.core.ai.TranscriptRender
import com.qbot.core.engine.BotRuntime
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 自主开口调度器。
 *
 * 结构照 [PersonaGrowthScheduler]：一个 10 分钟的节拍器 + 一个消息后的延迟评估。
 * 判定逻辑本身全在 botcore 的 [AutonomousDrive]（纯函数、可单测），
 * 这里只负责取样、调两道闸门、写决策表和真正开口。
 *
 * 几条刻意写死的产品约束：
 * - **非 ACTIVE 档直接返回**，连取样都不做——"跑了再丢弃"等于每来一条群消息白烧 CPU；
 * - **消息后延迟 20~40 秒**且同群只保留最后一次：立刻接话像条件反射，不像"想了想"；
 * - **chime 刚回过就跳过本轮**：chime 管"这条值不值得接"，自主管"我想不想说"，两者抢答最讨人厌；
 * - 失败静默：任何一步异常都只记日志，绝不影响正常回复。
 */
class AutonomousScheduler(
    private val database: AppDatabase,
    private val runtimeProvider: () -> BotRuntime?,
    /** 当前激活人格 id；null 表示不分人设（读库是挂起操作） */
    private val personaIdProvider: suspend () -> Long?,
    /** 全局默认档（用户改设置后立即生效，无需重启） */
    private val globalConfig: () -> DriveConfig,
    /** 按群覆盖：返回 null 表示这个群没单独设过 */
    private val groupModeProvider: suspend (String) -> String?,
    /** 判定用的辅助模型配置（与记忆精排共用） */
    private val auxConfig: () -> AuxAiConfig,
    private val mainConfig: () -> AiConfig,
    private val gate: AutonomousGate,
    /** 人设正文：算"话题与人格的相关度"的唯一依据 */
    private val personaTextProvider: suspend () -> String?,
    private val log: (String) -> Unit,
    /** 本群是否有进行中的场景（C1：场景优先，自主让位） */
    private val sceneActiveProvider: suspend (String) -> Boolean = { false },
    /** 本群场景是否刚结束（窗口内也不开口，避免"相声刚完它又自己说"） */
    private val sceneRecentEndProvider: suspend (String) -> Boolean = { false },
    /** 场景档位（C2：只读观察期间照常评估、只记录不发送） */
    private val sceneModeProvider: () -> SceneMode = { SceneMode.OFF },
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    companion object {
        /** 兜底节拍：让"沉默一阵后突然想接一句"成为可能 */
        const val TICK_MILLIS = 10 * 60_000L

        /** 消息后延迟范围：太快像反射，太慢话题已经过去了 */
        private const val MIN_DELAY_MILLIS = 20_000L
        private const val MAX_DELAY_MILLIS = 40_000L

        /** 硬上限：同一群 30 分钟内最多自主开口 2 次（任务书要求，先于用户配置生效） */
        private const val HARD_SPEAK_WINDOW_MILLIS = 30 * 60_000L
        private const val HARD_SPEAK_LIMIT = 2

        /**
         * chime 刚回过的时间窗：这段时间内自主完全不评估。
         *
         * 原本是 5 分钟——但它的目的只是"别在 chime 刚说完后又自己接一句"，
         * 一两分钟足够。5 分钟会让活跃群里的自主开口几乎永远不触发，
         * 等于把整个功能关掉。
         */
        private const val CHIME_GUARD_MILLIS = 2 * 60_000L

        /** 便宜模型判定的每天上限：超过就只跑本地闸门 */
        private const val MAX_GATE_CALLS_PER_DAY = 120

        /** 转录取多少条喂给判定模型 */
        private const val JUDGE_CONTEXT = 8

        /** 决策表保留天数 */
        private const val DECISION_RETENTION_DAYS = 7L

        /** 一次 tick 最多评估几个群 */
        private const val MAX_GROUPS_PER_TICK = 8

        /** 群范围的活动判定窗口 */
        private const val ACTIVE_WINDOW_MILLIS = 30 * 60_000L

        /** 场景结束后自主的冷却：避免"相声刚收底，它又自己开口" */
        const val SCENE_COOLDOWN_MILLIS = 5 * 60_000L
    }

    private val mutex = Mutex()

    /** 消息后评估的待办：同群只保留最后一次，避免一条消息一个协程地堆起来 */
    private val pending = ConcurrentHashMap<String, Job>()

    /** 便宜模型调用计数（按天），只在内存里——重启清零可以接受 */
    private var gateDay: Long = -1
    private var gateCalls: Int = 0

    private var scope: CoroutineScope? = null
    private var tickJob: Job? = null

    /** 决策存储：公开给复盘页用 */
    val decisions: RoomDriveStore
        get() = RoomDriveStore(database.autonomousDecisionDao())

    fun start(scope: CoroutineScope): Job {
        this.scope = scope
        tickJob?.takeIf { it.isActive }?.let { return it }
        val job =
            scope.launch {
                while (isActive) {
                    delay(TICK_MILLIS)
                    runCatching { tick() }
                        .onFailure { log("自主节拍失败：${it.message ?: it::class.simpleName}") }
                }
            }
        tickJob = job
        return job
    }

    fun stop() {
        tickJob?.cancel()
        tickJob = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        scope = null
    }

    /**
     * 消息处理完的入口（[BotRuntime] 的收尾回调）。
     *
     * 只负责排一个延迟任务；真正的评估在 [evaluate] 里。
     */
    fun onMessageHandled(groupId: String) {
        if (groupId.isBlank()) return
        val host = scope ?: return
        pending.remove(groupId)?.cancel()
        val job =
            host.launch {
                delay(random.nextLong(MIN_DELAY_MILLIS, MAX_DELAY_MILLIS + 1))
                runCatching { evaluate(groupId) }
                    .onFailure {
                        log("自主评估失败（${groupId.take(12)}）：${it.message ?: "未知错误"}")
                    }
            }
        pending[groupId] = job
    }

    /** 定时兜底：评估最近活跃的群。 */
    suspend fun tick() {
        runCatching { decisions.trimBefore(clock() - DECISION_RETENTION_DAYS * 86_400_000L) }
        val scopes =
            runCatching {
                database.roomMessageDao()
                    .activeScopesSince(clock() - ACTIVE_WINDOW_MILLIS, MAX_GROUPS_PER_TICK * 4)
            }.getOrElse { return }
        scopes
            .asSequence()
            // 私人线程（g:群@p:人）不是群语境，排除
            .filter { it.startsWith("g:") && !it.contains("@p:") }
            .map { it.removePrefix("g:") }
            .distinct()
            .take(MAX_GROUPS_PER_TICK)
            .forEach { groupId -> runCatching { evaluate(groupId) } }
    }

    /**
     * 调试页手动触发一次。
     *
     * OFF 档仍然不跑——"完全关闭"不该被调试绕过；
     * PASSIVE 档允许按 ACTIVE 的阈值算一遍（否则分数恒为 0，调参页没意义），
     * 这一下**可能真的开口一次**，所以界面上的按钮文案要写清楚。
     */
    suspend fun force(groupId: String): Boolean {
        if (effectiveConfig(groupId).mode == AutonomyMode.OFF) return false
        return evaluate(groupId, forced = true)
    }

    /** 全局档 + 按群覆盖。 */
    suspend fun effectiveConfig(groupId: String): DriveConfig {
        val global = globalConfig()
        val override = runCatching { groupModeProvider(groupId) }.getOrNull()
        return if (override.isNullOrBlank()) {
            global
        } else {
            global.copy(mode = AutonomyMode.parse(override))
        }
    }

    /** 一次完整评估。返回这次是否真的开口。 */
    private suspend fun evaluate(
        groupId: String,
        forced: Boolean = false,
    ): Boolean {
        var config = effectiveConfig(groupId)
        if (config.mode == AutonomyMode.OFF) return false
        if (config.mode != AutonomyMode.ACTIVE) {
            if (!forced) return false
            config = config.copy(mode = AutonomyMode.ACTIVE)
        }
        if (!mutex.tryLock()) return false
        try {
            return evaluateLocked(groupId, config, forced)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun evaluateLocked(
        groupId: String,
        config: DriveConfig,
        forced: Boolean,
    ): Boolean {
        val runtime = runtimeProvider() ?: return false
        val ai = mainConfig()
        if (!ai.enabled || !ai.isReady) return false
        val now = clock()
        val personaId = personaIdProvider()
        val scopeKey = ConversationKeys.groupRoom(groupId)
        val dao = database.roomMessageDao()

        // C1：场景优先。本群演戏中、或刚收底不到 5 分钟，自主一律让位。
        // 只查本群——A 群演戏不该让 B 群也跟着闭嘴。
        val sceneReason = sceneSkipReason(groupId)
        if (sceneReason != null) {
            recordHeld(groupId, personaId, 0.0, sceneReason, "")
            return false
        }

        val recent =
            dao.recent(scopeKey, JUDGE_CONTEXT, personaId)
                .asReversed()
                .map { it.toRoomMessage() }
        if (recent.isEmpty()) return false

        // 已经在被 @ 的那条消息上等回复：让被动链路先说话，别抢
        if (recent.lastOrNull()?.isAt == true) return false

        val anchor =
            recent.lastOrNull { it.role != "assistant" && !it.externalId.isNullOrBlank() }
                ?: return false

        val moodRow = moodRow()
        val mood = moodRow?.value ?: 50
        val thoughts =
            runCatching {
                database.innerThoughtDao().recentOnce(6)
                    .asSequence()
                    .filter { it.groupId == null || it.groupId == groupId }
                    .map { it.thought }
                    .filter { it.isNotBlank() }
                    .take(3)
                    .toList()
            }.getOrElse { emptyList() }

        val fingerprint = AutonomousDrive.fingerprintOf(anchor.content)
        // 同一话题连续忍住 3 次：冷却 30 分钟，不再重复想
        val held =
            decisions.recentHeldFingerprints(groupId, now - AutonomousDrive.HELD_COOLDOWN_MILLIS)
        if (AutonomousDrive.isCooling(held, fingerprint, now)) return false

        // chime 刚回过：本轮直接跳过（也顺带避免刚说完又被判定为"想说"）
        val lastSpokeAt = runtime.lastSpokeAtMillis(groupId) ?: moodRow?.lastSpokeAt
        if (!forced && lastSpokeAt != null && now - lastSpokeAt < CHIME_GUARD_MILLIS) return false

        // 配额：先看硬上限（30 分钟 2 次），再看用户配置的每小时上限
        if (decisions.countSpokenSince(groupId, now - HARD_SPEAK_WINDOW_MILLIS) >= HARD_SPEAK_LIMIT) {
            return false
        }
        if (config.maxSpeaksPerHour > 0) {
            val spokenHour = decisions.countSpokenSince(groupId, now - 60 * 60_000L)
            if (spokenHour >= config.maxSpeaksPerHour) return false
        }

        val interest =
            AutonomousDrive.interestOf(
                anchor.content,
                listOfNotNull(
                    runCatching { personaTextProvider() }.getOrNull(),
                    thoughts.joinToString(" ").takeIf { it.isNotBlank() },
                ),
            )
        val state =
            DriveState(
                mood = mood,
                interest = interest,
                closeness = closenessOf(personaId, anchor.senderId),
                minutesSinceLastSpoke = lastSpokeAt?.let { ((now - it) / 60_000L).toInt() } ?: -1,
                recentMessageCount = dao.countSinceTime(scopeKey, now - 10 * 60_000L),
                ignoredCount = moodRow?.suppressedCount ?: 0,
                hourOfDay = hourOf(now),
                hasMaterial = recent.isNotEmpty() || thoughts.isNotEmpty(),
            )
        val decision = AutonomousDrive.evaluate(state, config, now)

        if (!decision.shouldSpeak) {
            recordHeld(groupId, personaId, decision.score, decision.reason, fingerprint)
            bumpSuppressed(+1)
            return false
        }

        // 第二道闸门：便宜模型只看上下文，不写正文
        if (!takeGateBudget()) {
            recordHeld(groupId, personaId, decision.score, "判定模型今日额度已用完", fingerprint)
            return false
        }
        val verdict =
            runCatching {
                gate.shouldSpeak(judgeContext(recent, mood, thoughts, interest), auxConfig())
            }.getOrNull()
        if (verdict == null || !verdict.speak) {
            recordHeld(
                groupId = groupId,
                personaId = personaId,
                score = decision.score,
                reason = "闸门二：" + (verdict?.reason?.takeIf { it.isNotBlank() } ?: "不判定，默认忍住"),
                fingerprint = fingerprint,
            )
            bumpSuppressed(+1)
            return false
        }

        // C2：场景只读观察期间，照常走完两道闸门（保留观察价值），
        // 但一条都不发——否则"观察 AI 怎么演"的数据里会混进自主发言。
        if (sceneModeProvider() == SceneMode.OBSERVE) {
            recordHeld(
                groupId = groupId,
                personaId = personaId,
                score = decision.score,
                reason = "本会开口（${verdict.reason.take(100)}），场景只读观察，未发送",
                fingerprint = fingerprint,
                suppressedBy = AutonomousRecord.SUPPRESSED_SCENE_OBSERVE,
            )
            bumpSuppressed(+1)
            return false
        }

        val spoken =
            runCatching {
                runtime.autonomousSpeak(
                    groupId = groupId,
                    anchor = anchor,
                    recent = recent,
                    thoughts = thoughts,
                    topic = verdict.topic,
                )
            }.getOrElse {
                log("自主开口失败（${groupId.take(12)}）：${it.message ?: it::class.simpleName}")
                false
            }

        if (!spoken) {
            // 生成失败 / 模型自己选择沉默：同样记账，避免下一轮又问一遍
            recordHeld(groupId, personaId, decision.score, "主模型选择沉默或生成失败", fingerprint)
            return false
        }

        runCatching {
            decisions.record(
                AutonomousRecord(
                    groupId = groupId,
                    personaId = personaId,
                    score = decision.score,
                    outcome = AutonomousRecord.OUTCOME_SPOKEN,
                    reason = decision.reason + "｜" + verdict.reason.take(120),
                    fingerprint = fingerprint.takeIf { it.isNotBlank() },
                    createdAt = clock(),
                ),
            )
        }
        bumpSuppressedToZero()
        return true
    }

    private suspend fun recordHeld(
        groupId: String,
        personaId: Long?,
        score: Double,
        reason: String,
        fingerprint: String,
        suppressedBy: String? = null,
    ) {
        runCatching {
            decisions.record(
                AutonomousRecord(
                    groupId = groupId,
                    personaId = personaId,
                    score = score,
                    outcome = AutonomousRecord.OUTCOME_HELD,
                    reason = reason.take(300),
                    fingerprint = fingerprint.takeIf { it.isNotBlank() },
                    suppressedBy = suppressedBy,
                    createdAt = clock(),
                ),
            )
        }
    }

    /** 场景让位的理由；不该让位时返回 null。 */
    private suspend fun sceneSkipReason(groupId: String): String? {
        val active = runCatching { sceneActiveProvider(groupId) }.getOrDefault(false)
        if (active) return "场景进行中，自主让位"
        val justEnded = runCatching { sceneRecentEndProvider(groupId) }.getOrDefault(false)
        if (justEnded) return "场景刚结束不到 ${SCENE_COOLDOWN_MILLIS / 60_000} 分钟，自主让位"
        return null
    }

    /** 喂给判定模型的上下文：只给最近几条 + 情绪/内心戏/相关度，不给全量记忆。 */
    private fun judgeContext(
        recent: List<RoomMessage>,
        mood: Int,
        thoughts: List<String>,
        interest: Double,
    ): String {
        val now = clock()
        val transcript =
            TranscriptRender.render(
                recent.map { message ->
                    TranscriptRender.Line(
                        who = if (message.role == "assistant") "你" else message.senderName ?: "某人",
                        body = message.content.take(120),
                        meta = ago(now - message.timestampMillis),
                    )
                },
            )
        return buildString {
            append("最近群里的对话：\n")
            append(TranscriptRender.FORMAT_HINT).append('\n')
            append(transcript).append("\n\n")
            append("你现在的状态：情绪 $mood/100，这个话题与你的相关度 ${(interest * 100).toInt()}%。\n")
            if (thoughts.isNotEmpty()) {
                append("你刚才没说出口的想法：\n")
                thoughts.forEach { append("- ").append(it.take(80)).append('\n') }
            }
        }
    }

    private suspend fun closenessOf(
        personaId: Long?,
        senderId: String?,
    ): Double {
        if (senderId.isNullOrBlank()) return 0.5
        val row =
            runCatching { database.affinityDao().find(personaId ?: 0, senderId) }.getOrNull()
                ?: return 0.5
        // -100..100 → 0..1
        return (row.affinity + 100) / 200.0
    }

    private suspend fun moodRow() =
        runCatching { database.botMoodDao().get() }.getOrNull()

    private suspend fun bumpSuppressed(delta: Int) {
        runCatching {
            val dao = database.botMoodDao()
            val current = dao.get() ?: return@runCatching
            dao.put(current.copy(suppressedCount = (current.suppressedCount + delta).coerceIn(0, 99)))
        }
    }

    private suspend fun bumpSuppressedToZero() {
        runCatching {
            val dao = database.botMoodDao()
            val current = dao.get() ?: return@runCatching
            dao.put(current.copy(suppressedCount = 0, lastSpokeAt = clock()))
        }
    }

    /** 每天最多 120 次便宜模型判定。跨天自动清零。 */
    private fun takeGateBudget(): Boolean {
        val day = clock() / 86_400_000L
        if (day != gateDay) {
            gateDay = day
            gateCalls = 0
        }
        if (gateCalls >= MAX_GATE_CALLS_PER_DAY) return false
        gateCalls++
        return true
    }

    /** 用设备本地时区判断"几点"——安静时段是用户的作息，不是 UTC。 */
    private fun hourOf(millis: Long): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = millis
        return calendar.get(Calendar.HOUR_OF_DAY)
    }

    private fun ago(deltaMillis: Long): String =
        when {
            deltaMillis < 60_000L -> "刚刚"
            deltaMillis < 3_600_000L -> "${deltaMillis / 60_000L} 分钟前"
            else -> "${deltaMillis / 3_600_000L} 小时前"
        }

    private fun RoomMessageEntity.toRoomMessage(): RoomMessage =
        RoomMessage(
            senderId = senderId,
            senderName = senderName,
            role = role,
            content = content,
            timestampMillis = timestampMillis,
            isAt = isAt,
            externalId = externalId,
            decision = decision,
            personaId = personaId,
        )
}
