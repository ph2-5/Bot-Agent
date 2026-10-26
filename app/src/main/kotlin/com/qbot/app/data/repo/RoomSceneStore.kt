package com.qbot.app.data.repo

import com.qbot.app.data.db.SceneDao
import com.qbot.app.data.db.SceneDecisionDao
import com.qbot.app.data.db.SceneDecisionEntity
import com.qbot.app.data.db.SceneEntity
import com.qbot.core.ai.Scene
import com.qbot.core.ai.SceneDecisionRecord
import com.qbot.core.ai.SceneKind
import com.qbot.core.ai.SceneParticipant
import com.qbot.core.ai.SceneRole
import com.qbot.core.ai.SceneStatus
import com.qbot.core.ai.SceneStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 场景存储的 Room 实现。
 *
 * botcore 只认 [SceneStore] 接口；participants 用 JSON 存一列
 * （双人场景最多两个参与者，拆表得不偿失）。
 */
class RoomSceneStore(
    private val sceneDao: SceneDao,
    private val decisionDao: SceneDecisionDao,
) : SceneStore {

    override suspend fun active(groupId: String): Scene? =
        sceneDao.active(groupId)?.toModel()

    override suspend fun activeScenes(): List<Scene> =
        sceneDao.activeScenes().map { it.toModel() }

    override suspend fun save(scene: Scene): Scene {
        val participantsJson = json.encodeToString(scene.participants.map { it.toDto() })
        return if (scene.id == 0L) {
            val id =
                sceneDao.insert(
                    SceneEntity(
                        kind = scene.kind.name,
                        groupId = scene.groupId,
                        participantsJson = participantsJson,
                        goal = scene.goal,
                        status = scene.status.name,
                        turnIndex = scene.turnIndex,
                        expectedSpeakerId = scene.expectedSpeakerId,
                        waitUntil = scene.waitUntil,
                        lastActivityAt = scene.lastActivityAt,
                        createdAt = scene.createdAt,
                    ),
                )
            scene.copy(id = id)
        } else {
            sceneDao.update(
                id = scene.id,
                kind = scene.kind.name,
                participantsJson = participantsJson,
                goal = scene.goal,
                status = scene.status.name,
                turnIndex = scene.turnIndex,
                expectedSpeakerId = scene.expectedSpeakerId,
                waitUntil = scene.waitUntil,
                lastActivityAt = scene.lastActivityAt,
            )
            scene
        }
    }

    override suspend fun end(sceneId: Long, nowMillis: Long) {
        sceneDao.end(sceneId)
    }

    override suspend fun lastEndedAt(groupId: String): Long? = sceneDao.lastEndedAt(groupId)

    override suspend fun record(decision: SceneDecisionRecord) {
        decisionDao.insert(
            SceneDecisionEntity(
                sceneId = decision.sceneId,
                groupId = decision.groupId,
                action = decision.action,
                argsJson = decision.argsJson,
                executed = decision.executed,
                reason = decision.reason,
                createdAt = decision.createdAt,
            ),
        )
    }

    override suspend fun speaksSince(groupId: String, sinceMillis: Long): Int =
        decisionDao.speaksSince(groupId, sinceMillis)

    /** 调试页：最近 20 条场景决策（全局，不按群过滤）。 */
    fun observeDecisions(limit: Int): Flow<List<SceneDecisionRecord>> =
        decisionDao.observeRecent(limit).map { rows -> rows.map { it.toModel() } }

    /** 调试页：某个群最近的场景（含已结束的，便于回看）。 */
    fun observeScenes(groupId: String, limit: Int = 10): Flow<List<Scene>> =
        sceneDao.observeRecent(groupId, limit).map { rows -> rows.map { it.toModel() } }

    /** 定期清掉过期的已结束场景与决策记录，避免长期挂机把库撑大。 */
    suspend fun trim(nowMillis: Long, keepMillis: Long = 7L * 24 * 3600 * 1000) {
        sceneDao.trimEndedBefore(nowMillis - keepMillis)
        decisionDao.trimBefore(nowMillis - keepMillis)
    }

    private fun SceneEntity.toModel(): Scene =
        Scene(
            id = id,
            kind = runCatching { SceneKind.valueOf(kind) }.getOrDefault(SceneKind.CROSSTALK),
            groupId = groupId,
            participants = decodeParticipants(participantsJson),
            goal = goal,
            status = runCatching { SceneStatus.valueOf(status) }.getOrDefault(SceneStatus.ENDED),
            turnIndex = turnIndex,
            expectedSpeakerId = expectedSpeakerId,
            waitUntil = waitUntil,
            lastActivityAt = lastActivityAt,
            createdAt = createdAt,
        )

    private fun SceneParticipant.toDto(): ParticipantDto =
        ParticipantDto(senderId, displayName, isBot, role?.name)

    private fun decodeParticipants(raw: String): List<SceneParticipant> =
        runCatching {
            json.decodeFromString<List<ParticipantDto>>(raw).map { it.toModel() }
        }.getOrDefault(emptyList())

    private fun ParticipantDto.toModel(): SceneParticipant =
        SceneParticipant(
            senderId = senderId,
            displayName = displayName,
            isBot = isBot,
            role = role?.let { runCatching { SceneRole.valueOf(it) }.getOrNull() },
        )

    private fun SceneDecisionEntity.toModel(): SceneDecisionRecord =
        SceneDecisionRecord(
            id = id,
            sceneId = sceneId,
            groupId = groupId,
            action = action,
            argsJson = argsJson,
            executed = executed,
            reason = reason,
            createdAt = createdAt,
        )

    @Serializable
    private data class ParticipantDto(
        val senderId: String,
        val displayName: String,
        val isBot: Boolean,
        val role: String?,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
