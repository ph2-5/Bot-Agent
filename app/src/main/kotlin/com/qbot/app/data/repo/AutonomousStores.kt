package com.qbot.app.data.repo

import com.qbot.app.data.db.AutonomousDecisionDao
import com.qbot.app.data.db.AutonomousDecisionEntity
import com.qbot.app.data.db.GroupBehaviorDao
import com.qbot.app.data.db.GroupBehaviorEntity
import com.qbot.core.ai.AutonomousRecord
import com.qbot.core.ai.DriveStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 自主决策表的存储实现。
 *
 * botcore 只认 [DriveStore] 接口，Room 细节留在这里：
 * 同一个机器人共用一个库，多机器人天然隔离（各自一个 [com.qbot.app.data.db.AppDatabase]）。
 */
class RoomDriveStore(
    private val dao: AutonomousDecisionDao,
) : DriveStore {
    override suspend fun record(record: AutonomousRecord) {
        dao.insert(
            AutonomousDecisionEntity(
                groupId = record.groupId,
                personaId = record.personaId,
                score = record.score,
                outcome = record.outcome,
                reason = record.reason,
                fingerprint = record.fingerprint,
                createdAt = record.createdAt,
            ),
        )
    }

    /** DAO 按 id 倒序取，这里翻成从旧到新——调度器做"最近被无视"判断时依赖顺序。 */
    override suspend fun recent(
        groupId: String,
        limit: Int,
    ): List<AutonomousRecord> =
        dao.recent(groupId, limit).asReversed().map { it.toRecord() }

    override suspend fun countSpokenSince(
        groupId: String,
        sinceMillis: Long,
    ): Int = dao.countSpokenSince(groupId, sinceMillis)

    override suspend fun recentHeldFingerprints(
        groupId: String,
        sinceMillis: Long,
    ): List<Pair<String, Long>> =
        dao.heldSince(groupId, sinceMillis)
            .mapNotNull { entity ->
                entity.fingerprint?.takeIf { it.isNotBlank() }?.let { it to entity.createdAt }
            }

    /** 复盘页：最近的自主决策（SPOKEN / HELD 都显示）。 */
    fun observeRecent(limit: Int): Flow<List<AutonomousRecord>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toRecord() } }

    /** 只保留最近若干天的记录，防止长期挂机把库撑大。 */
    suspend fun trimBefore(beforeMillis: Long) {
        dao.trimBefore(beforeMillis)
    }

    private fun AutonomousDecisionEntity.toRecord(): AutonomousRecord =
        AutonomousRecord(
            groupId = groupId,
            personaId = personaId,
            score = score,
            outcome = outcome,
            reason = reason,
            fingerprint = fingerprint,
            createdAt = createdAt,
        )
}

/**
 * 按群覆盖全局档位。
 *
 * 表里只存"被单独设过"的群；没设过的群直接回落全局档，
 * 这样用户改全局默认时，之前只是打开过群详情、没动过下拉的群也跟着变。
 */
class RoomGroupBehaviorStore(
    private val dao: GroupBehaviorDao,
) {
    fun observeAll(): Flow<Map<String, String>> =
        dao.observeAll().map { rows -> rows.associate { it.groupId to it.autonomyMode } }

    suspend fun all(): Map<String, String> =
        dao.all().associate { it.groupId to it.autonomyMode }

    suspend fun modeFor(groupId: String): String? = dao.byGroup(groupId)?.autonomyMode

    suspend fun setMode(
        groupId: String,
        mode: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (groupId.isBlank()) return
        dao.upsert(
            GroupBehaviorEntity(
                groupId = groupId,
                autonomyMode = mode,
                updatedAt = nowMillis,
            ),
        )
    }

    /** 清掉覆盖：该群回到全局档。 */
    suspend fun clear(groupId: String) {
        dao.delete(groupId)
    }
}
