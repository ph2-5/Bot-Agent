package com.qbot.app.runtime

import com.qbot.app.data.db.RoomMessageDao
import com.qbot.app.data.db.RoomMessageEntity
import com.qbot.app.data.repo.PersonaRepository
import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.GrowthAction
import com.qbot.core.ai.GrowthStats
import com.qbot.core.ai.LlmClient
import com.qbot.core.ai.Persona
import com.qbot.core.ai.PersonaGrowth
import com.qbot.core.ai.PersonaGrowthPolicy
import com.qbot.core.ai.PersonaGrowthState
import com.qbot.core.ai.PersonaKind
import com.qbot.core.ai.PersonaSeed
import com.qbot.core.ai.PersonaSnapshotKind
import com.qbot.core.ai.PersonaStage
import com.qbot.core.ai.RoomMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 人格自主成长的调度器。
 *
 * 每 [TICK_MILLIS] 醒一次，判断"该不该破壳 / 该不该长一截"。
 * 判定逻辑本身在 botcore 的 [PersonaGrowthPolicy]（纯函数、可单测），
 * 这里只负责取样、调模型、写库。
 *
 * 三层并发保护：
 * 1. [mutex] 的 `tryLock`：同一时刻只跑一次，拿不到就跳过本轮（不排队）；
 * 2. `commitBirth` 的 `AND stage='EMBRYO'`：并发破壳只有一个成功；
 * 3. `commitGrowth` 的 `updatedAt` CAS：用户手改时成长结果直接丢弃。
 */
class PersonaGrowthScheduler(
    private val repo: PersonaRepository,
    private val messages: RoomMessageDao,
    private val aiConfig: () -> AiConfig,
    private val client: LlmClient,
    private val botName: () -> String?,
    private val log: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** 检查频率：不追求实时，十分钟一次足够 */
        const val TICK_MILLIS = 10 * 60_000L

        /** 破壳前的询问冷却：半小时 */
        private const val BIRTH_COOLDOWN_MILLIS = 30 * 60_000L

        /** 成长期的询问冷却：一小时。节奏交给模型，这里只防止短时间反复烧 token */
        private const val GROW_COOLDOWN_MILLIS = 60 * 60_000L

        /** 取样覆盖几个群 */
        private const val SAMPLE_SCOPES = 3

        /** 每个群取多少条 */
        private const val SAMPLE_PER_SCOPE = 16
    }

    private val mutex = Mutex()
    private val growth = PersonaGrowth(client)

    /**
     * 每个人格上次真正询问模型的时间。
     *
     * 按档案 id 隔离：给旧人格点过「立即成长」后，新人格不应跟着一起冷却——
     * 否则切换过去的人会觉得"新档案一直不生不长"。
     * 刻意放内存：重启后重新计时无所谓，但能保证"十次 tick 不会问十次"。
     */
    private val lastAskedAt = mutableMapOf<Long, Long>()

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                runCatching { tick() }.onFailure {
                    log("人格成长检查失败：${it.message ?: it::class.simpleName}")
                }
            }
        }

    /** 跑一次检查；外部（UI 的"立即成长"）也可以直接调。 */
    suspend fun tick() {
        if (!mutex.tryLock()) return
        try {
            runOnce()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * 手动催一次：忽略消息量与间隔门槛。
     *
     * 用户在界面上点「立即成长」时用。仍然受冻结、AI 未配置、并发互斥约束。
     */
    suspend fun force(): Boolean {
        if (!mutex.tryLock()) return false
        try {
            val ai = aiConfig()
            if (!ai.enabled || !ai.isReady) return false
            val profile = repo.active() ?: return false
            if (profile.kind != PersonaKind.PROFILE || profile.frozen) return false
            // 手动催过一次也要刷新这个人格自己的冷却，否则 10 分钟后的定时任务会再自动长一次
            lastAskedAt[profile.id] = clock()
            return if (profile.stage == PersonaStage.EMBRYO) {
                birth(profile, ai)
            } else {
                grow(profile, messages.countSince(profile.msgCursor), ai)
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runOnce() {
        val ai = aiConfig()
        if (!ai.enabled || !ai.isReady) return

        // 所有「允许成长」的人格都要跑，而不只是当前激活档案：
        // 覆盖层人设（某个群/某个人专用）如果被设为成长期，过去永远长不了（审计 M5）。
        // MANUAL 是用户手写的人设，PersonaGrowthPolicy 本身也会拒绝，这里先筛掉省一次查询。
        val candidates =
            repo.all().filter {
                !it.frozen && it.stage != PersonaStage.MANUAL
            }
        val now = clock()
        candidates.forEach { profile ->
            // 冷却：tick 每十分钟一次，但不能每十分钟都去问模型
            val cooldown =
                if (profile.stage == PersonaStage.EMBRYO) {
                    BIRTH_COOLDOWN_MILLIS
                } else {
                    GROW_COOLDOWN_MILLIS
                }
            // delta 为负说明系统时间被回拨过：视为"已过期"，否则会永久停摆
            val last = lastAskedAt[profile.id] ?: 0L
            val delta = now - last
            if (last > 0 && delta >= 0 && delta < cooldown) return@forEach

            val pending = messages.countSince(profile.msgCursor)
            val action =
                PersonaGrowthPolicy.next(
                    state =
                        PersonaGrowthState(
                            stage = profile.stage,
                            frozen = profile.frozen,
                            growthCount = profile.growthCount,
                            birthAt = profile.birthAt,
                            lastGrownAt = profile.lastGrownAt,
                        ),
                    pendingMessages = pending,
                )

            when (action) {
                GrowthAction.NONE -> Unit
                GrowthAction.BIRTH -> {
                    lastAskedAt[profile.id] = now
                    birth(profile, ai)
                }
                GrowthAction.GROW -> {
                    lastAskedAt[profile.id] = now
                    grow(profile, pending, ai)
                }
            }
        }
    }

    /** 破壳：种子 + 群聊 → 第一版人格。返回是否真的写成。 */
    private suspend fun birth(
        profile: Persona,
        ai: AiConfig,
    ): Boolean {
        val seed = PersonaSeed.of(profile.seedId)
        val samples = takeSamples(profile.msgCursor)
        // 游标取在样本之后、模型之前：模型调用那几十秒的新消息既不在样本里，
        // 也不该被游标越过——留给下一批。
        val cursorNow = messages.maxId() ?: 0

        // 破壳前的骨架永远留一版：用户以后觉得"还不如一开始"，可以回滚到这里
        if (!repo.hasInitialSnapshot(profile.id)) {
            repo.addSnapshot(
                personaId = profile.id,
                name = profile.name,
                prompt = PersonaGrowth.embryoSkeleton(profile.seedId),
                kind = PersonaSnapshotKind.INITIAL,
                note = "种子骨架（破壳前，不可删除）",
            )
        }

        val draft = growth.birth(seed, samples, botName(), ai)
        val cursor = cursorNow
        val ok =
            repo.commitBirth(
                id = profile.id,
                prompt = draft.prompt,
                seedId = seed.name,
                cursor = cursor,
                note = draft.note,
            )
        if (!ok) {
            // 期间已经被另一次破壳抢先，丢弃本次结果即可（没有写快照，无需清理）
            log("人格破壳被并发取消")
            return false
        }
        repo.addSnapshot(profile.id, profile.name, draft.prompt, PersonaSnapshotKind.BIRTH, draft.note)
        log("人格破壳完成（${seed.label}，参考 ${samples.size} 条群聊）")
        return true
    }

    /** 微调：只允许小步修正。返回是否真的写成。 */
    private suspend fun grow(
        profile: Persona,
        pending: Int,
        ai: AiConfig,
    ): Boolean {
        val samples = takeSamples(profile.msgCursor)
        val stats =
            GrowthStats(
                pendingMessages = pending,
                daysSinceLastGrowth =
                    if (profile.lastGrownAt <= 0) {
                        0
                    } else {
                        ((clock() - profile.lastGrownAt) / 86_400_000L).toInt()
                    },
                growthCount = profile.growthCount,
            )
        val cursorNow = messages.maxId() ?: 0
        val draft =
            growth.grow(profile.prompt, samples, stats, ai)
                ?: run {
                    // 模型认为这批对话没有新东西：推进游标，等下一批，不写快照
                    repo.advanceCursor(profile.id, cursorNow)
                    log("人格检查：这批消息没有值得吸收的，跳过")
                    return false
                }
        val cursor = cursorNow
        val nextStage =
            if (profile.growthCount + 1 >= PersonaGrowthPolicy.MATURE_GROWTHS) {
                PersonaStage.MATURE
            } else {
                PersonaStage.GROWING
            }

        val ok =
            repo.commitGrowth(
                id = profile.id,
                prompt = draft.prompt,
                stage = nextStage,
                cursor = cursor,
                note = draft.note,
                // CAS：用户在此期间手改过，就放弃这次成长——手改永远赢
                expectedUpdatedAt = profile.updatedAt,
            )
        if (!ok) {
            log("人格成长被手动编辑打断，本次放弃")
            return false
        }
        repo.addSnapshot(profile.id, profile.name, draft.prompt, PersonaSnapshotKind.GROWTH, draft.note)
        log("人格成长完成：${draft.note}")
        return true
    }

    /**
     * 跨群均衡取样。
     *
     * 只取最近一个群会让人格变成那个群的镜子——某个群连着刷三天，
     * 机器人就会开始用那个群的黑话和情绪。取最活跃的 3 个群各 16 条。
     */
    private suspend fun takeSamples(cursor: Long): List<RoomMessage> {
        val scopes = messages.topScopesSince(cursor, SAMPLE_SCOPES)
        if (scopes.isEmpty()) return emptyList()
        return scopes.flatMap { scope ->
            messages.recentSince(scope, cursor, SAMPLE_PER_SCOPE)
                .asReversed()
                .map { it.toRoomMessage() }
        }
    }

    private fun RoomMessageEntity.toRoomMessage(): RoomMessage =
        RoomMessage(
            senderId = senderId,
            senderName = senderName,
            role = role,
            content = content,
            timestampMillis = timestampMillis,
        )
}
