package com.qbot.app.data.repo

import com.qbot.app.data.db.IdentityDao
import com.qbot.app.data.db.PersonaDao
import com.qbot.app.data.db.PersonaEntity
import com.qbot.app.data.db.PersonaSnapshotDao
import com.qbot.app.data.db.PersonaSnapshotEntity
import com.qbot.core.ai.KnownPerson
import com.qbot.core.ai.Persona
import com.qbot.core.ai.PersonaKind
import com.qbot.core.ai.PersonaSeed
import com.qbot.core.ai.PersonaSections
import com.qbot.core.ai.PersonaSnapshot
import com.qbot.core.ai.PersonaSnapshotKind
import com.qbot.core.ai.PersonaStage
import com.qbot.core.ai.PersonaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 人设仓库。
 *
 * 2.3.0 起它管理两类行：
 * - **人格档案**（PROFILE）：可以有多份，同一时刻只有一份 active；
 * - **覆盖层**（OVERRIDE）：绑定到某个人 / 某个群，永远优先于档案。
 *
 * 另外管理「人设版本快照」：初始版本（INITIAL）不可删除，
 * 保证"人格被改坏还能回到最初"。
 */
class PersonaRepository(
    private val personaDao: PersonaDao,
    private val identityDao: IdentityDao,
    private val snapshotDao: PersonaSnapshotDao? = null,
) : PersonaStore {
    companion object {
        /** 每个目标保留的非初始版本数量 */
        private const val KEEP_SNAPSHOTS = 20
    }

    /** 全部人设（按更新时间倒序），供管理界面展示 */
    val personas: Flow<List<Persona>> =
        personaDao.all().map { list -> list.map { it.toPersona() } }

    /** 人格档案列表（激活的排最前） */
    val profiles: Flow<List<Persona>> =
        personaDao.profiles().map { list -> list.map { it.toPersona() } }

    /** 当前激活的人格档案 */
    fun observeActive(): Flow<Persona?> =
        personaDao.observeActive().map { it?.toPersona() }

    suspend fun active(): Persona? = personaDao.active()?.toPersona()

    suspend fun countProfiles(): Int = personaDao.countProfiles()

    /** 最近互动过的人，供人设绑定时快速选择 */
    suspend fun recentPeople(limit: Int = 50): List<KnownPerson> =
        identityDao.recent(limit).map { KnownPerson(it.senderId, it.displayName) }

    override suspend fun all(): List<Persona> = personaDao.allOnce().map { it.toPersona() }

    /**
     * 保存一条人设（UI 编辑 / 覆盖层用）。
     *
     * **元数据必须沿用库里已有的行**：界面传进来的 [Persona] 通常只带
     * name/prompt/绑定关系，如果直接用它的默认值，一次编辑就会把
     * PROFILE 降级成 OVERRIDE、把 activeSlot 清空——人格会"凭空消失"。
     */
    override suspend fun save(persona: Persona): Long {
        val now = System.currentTimeMillis()
        val existing = if (persona.id > 0) personaDao.findById(persona.id) else null

        // 自由文本（不是分段结构）不可能被"小步微调"——守卫拿不到可比对的段落，
        // 下一次成长只能整段重写，等于把用户写的东西冲掉。
        // 所以只要正文解析不出段落，就自动转成手动：用户写的永远优先。
        val freeform =
            persona.prompt.isNotBlank() && PersonaSections.parse(persona.prompt) == null
        val stage =
            if (freeform) {
                PersonaStage.MANUAL.name
            } else {
                existing?.stage ?: persona.stage.name
            }

        return personaDao.upsert(
            PersonaEntity(
                id = persona.id,
                senderId = persona.senderId?.takeIf { it.isNotBlank() },
                scopeId = persona.scopeId?.takeIf { it.isNotBlank() },
                name = persona.name,
                prompt = persona.prompt,
                updatedAt = now,
                kind = existing?.kind ?: persona.kind.name,
                stage = stage,
                seedId = existing?.seedId ?: persona.seedId,
                activeSlot = existing?.activeSlot,
                frozen = existing?.frozen ?: persona.frozen,
                birthAt = existing?.birthAt ?: 0,
                lastGrownAt = existing?.lastGrownAt ?: 0,
                growthCount = existing?.growthCount ?: 0,
                msgCursor = existing?.msgCursor ?: 0,
                lastNote = existing?.lastNote.orEmpty(),
                createdAt = existing?.createdAt ?: now,
            ),
        )
    }

    override suspend fun delete(id: Long) {
        deleteProfileOrOverride(id)
    }

    // ---------- 人格档案 ----------

    /**
     * 新建一份人格档案（默认 EMBRYO，等群聊攒够再破壳）。
     *
     * 一定先以 `activeSlot = null` 插入，再单独 [activate]——
     * 直接插 1 会撞上唯一索引。
     */
    suspend fun createProfile(
        name: String,
        prompt: String = "",
        stage: PersonaStage = PersonaStage.EMBRYO,
        seedId: String? = null,
    ): Long {
        val now = System.currentTimeMillis()
        val id =
            personaDao.upsert(
                PersonaEntity(
                    senderId = null,
                    scopeId = null,
                    name = name,
                    prompt = prompt,
                    updatedAt = now,
                    kind = PersonaKind.PROFILE.name,
                    stage = stage.name,
                    seedId = seedId,
                    activeSlot = null,
                    frozen = false,
                    birthAt = now,
                    createdAt = now,
                ),
            )
        // 新建但没写 prompt 的档案补一条空快照没有意义，等破壳时再存 INITIAL
        return id
    }

    /** 用随机性格种子新建一份档案（用户"零提示词起步"的入口）。 */
    suspend fun createSeededProfile(seed: PersonaSeed = PersonaSeed.random()): Long =
        createProfile(
            name = seed.label,
            prompt = "",
            stage = PersonaStage.EMBRYO,
            seedId = seed.name,
        )

    suspend fun activate(id: Long) {
        personaDao.switchActive(id, System.currentTimeMillis())
    }

    /**
     * 删除档案或覆盖层。
     *
     * 删的是激活档案时，先把激活位让给另一份档案；一份都不剩就允许删，
     * 解析链会自动回落到种子骨架（不会出现"没有激活却还有档案"的中空态）。
     */
    suspend fun deleteProfileOrOverride(id: Long): Boolean {
        val all = personaDao.allOnce()
        val target = all.firstOrNull { it.id == id } ?: return false
        if (target.activeSlot == 1) {
            val next = all.firstOrNull { it.id != id && it.kind == PersonaKind.PROFILE.name }
            if (next != null) {
                personaDao.switchActive(next.id, System.currentTimeMillis())
            }
        }
        personaDao.deleteById(id)
        return true
    }

    /**
     * 用户接手：保留当前正文，停止自动成长。
     *
     * 和「交给它自己养」互为反向操作。用户随时可以把某份人格抢回手里，
     * 之后只有他自己改。
     */
    suspend fun takeOver(id: Long) {
        val row = personaDao.findById(id) ?: return
        personaDao.updateMeta(
            id = id,
            stage = PersonaStage.MANUAL.name,
            seedId = row.seedId,
            frozen = row.frozen,
            at = System.currentTimeMillis(),
        )
    }

    /** 冻结 / 解冻自动成长。 */
    suspend fun setFrozen(
        id: Long,
        frozen: Boolean,
    ) {
        val row = personaDao.findById(id) ?: return
        personaDao.updateMeta(
            id = id,
            stage = row.stage,
            seedId = row.seedId,
            frozen = frozen,
            at = System.currentTimeMillis(),
        )
    }

    /** 重抽性格种子（只对 EMBRYO 有意义）并回到未破壳状态。 */
    suspend fun reseed(
        id: Long,
        seed: PersonaSeed = PersonaSeed.random(),
    ) {
        val row = personaDao.findById(id) ?: return
        val now = System.currentTimeMillis()
        // 旧种子的 INITIAL 必须清掉：换原点后它不再是"最初的样子"，
        // 否则回滚会还原成上一个种子，新种子则永远没有初始版本。
        snapshotDao?.deleteInitial(id)
        personaDao.updateMeta(
            id = id,
            stage = PersonaStage.EMBRYO.name,
            seedId = seed.name,
            frozen = row.frozen,
            at = now,
        )
        // 正文清空，让骨架重新接管；名字也换成新种子的标签
        personaDao.upsert(
            row.copy(
                name = seed.label,
                prompt = "",
                stage = PersonaStage.EMBRYO.name,
                seedId = seed.name,
                birthAt = now,
                lastGrownAt = 0,
                growthCount = 0,
                msgCursor = 0,
                lastNote = "",
                updatedAt = now,
            ),
        )
    }

    /** 交给它自己养：把手动人设转成 GROWING，并从这里开始累计消息。 */
    suspend fun handOverToGrowth(
        id: Long,
        cursor: Long,
    ) {
        val row = personaDao.findById(id) ?: return
        val now = System.currentTimeMillis()
        personaDao.upsert(
            row.copy(
                stage = PersonaStage.GROWING.name,
                frozen = false,
                birthAt = if (row.birthAt > 0) row.birthAt else now,
                lastGrownAt = now,
                msgCursor = cursor,
                updatedAt = now,
            ),
        )
    }

    /**
     * 导入备份时更新档案元数据。
     *
     * 不能用 [save]：它会刻意保留库里已有的 stage/seedId，
     * 否则一次界面编辑就会把「成长期」降级成「手动」。导入是少数
     * 需要显式覆盖元数据的场景，所以单独开一个口子。
     */
    suspend fun applyProfileMeta(
        id: Long,
        stage: PersonaStage,
        seedId: String?,
        frozen: Boolean,
    ) {
        personaDao.updateMeta(id, stage.name, seedId, frozen, System.currentTimeMillis())
    }

    /** 导入备份时恢复成长次数（成长节奏相关的游标/时间不恢复）。 */
    suspend fun restoreGrowthCount(
        id: Long,
        count: Int,
    ) {
        personaDao.setGrowthCount(id, count.coerceAtLeast(0))
    }

    /** 模型判定"没有值得吸收的"时，只把游标推过去，等下一批消息。 */
    suspend fun advanceCursor(
        id: Long,
        cursor: Long,
    ) {
        personaDao.advanceCursor(id, cursor)
    }

    /** 破壳写回；返回 false 表示期间已经破过壳（并发保护）。 */
    suspend fun commitBirth(
        id: Long,
        prompt: String,
        seedId: String?,
        cursor: Long,
        note: String,
    ): Boolean =
        personaDao.commitBirth(
            id = id,
            prompt = prompt,
            seedId = seedId,
            cursor = cursor,
            at = System.currentTimeMillis(),
            note = note,
        ) > 0

    /**
     * 成长写回；返回 false 表示期间被手动改过（CAS 失败）。
     *
     * 调用方拿到 false 时必须把刚写的快照删掉——否则会出现
     * "快照里有、实际没生效"的幽灵版本，回滚后人格直接倒退。
     */
    suspend fun commitGrowth(
        id: Long,
        prompt: String,
        stage: PersonaStage,
        cursor: Long,
        note: String,
        expectedUpdatedAt: Long,
    ): Boolean =
        personaDao.commitGrowth(
            id = id,
            prompt = prompt,
            stage = stage.name,
            cursor = cursor,
            at = System.currentTimeMillis(),
            note = note,
            expected = expectedUpdatedAt,
        ) > 0

    // ---------- 版本快照 ----------

    /** 某个目标的历史版本（按时间倒序） */
    suspend fun snapshots(personaId: Long?): List<PersonaSnapshot> =
        snapshotDao?.listFor(personaId)?.map { it.toSnapshot() }.orEmpty()

    /** 当前目标是否已有初始存档 */
    suspend fun hasInitialSnapshot(personaId: Long?): Boolean =
        snapshotDao?.countInitial(personaId)?.let { it > 0 } ?: false

    suspend fun addSnapshot(
        personaId: Long?,
        name: String,
        prompt: String,
        kind: PersonaSnapshotKind,
        note: String,
    ) {
        val dao = snapshotDao ?: return
        dao.insert(
            PersonaSnapshotEntity(
                personaId = personaId,
                name = name,
                prompt = prompt,
                kind = kind.name,
                note = note,
                createdAt = System.currentTimeMillis(),
            ),
        )
        // 初始版本永不淘汰
        dao.trim(personaId, KEEP_SNAPSHOTS)
    }

    /** 删除非初始版本；初始版本静默忽略 */
    suspend fun deleteSnapshot(id: Long) {
        snapshotDao?.deleteNonInitial(id)
    }

    private fun PersonaSnapshotEntity.toSnapshot(): PersonaSnapshot =
        PersonaSnapshot(
            id = id,
            personaId = personaId,
            name = name,
            prompt = prompt,
            kind =
                runCatching { PersonaSnapshotKind.valueOf(kind) }
                    .getOrDefault(PersonaSnapshotKind.MANUAL),
            note = note,
            createdAt = createdAt,
        )

    private fun PersonaEntity.toPersona(): Persona =
        Persona(
            id = id,
            senderId = senderId,
            scopeId = scopeId,
            name = name,
            prompt = prompt,
            updatedAt = updatedAt,
            kind =
                runCatching { PersonaKind.valueOf(kind) }
                    .getOrDefault(PersonaKind.OVERRIDE),
            stage =
                runCatching { PersonaStage.valueOf(stage) }
                    .getOrDefault(PersonaStage.MANUAL),
            seedId = seedId,
            active = activeSlot == 1,
            frozen = frozen,
            growthCount = growthCount,
            birthAt = birthAt,
            lastGrownAt = lastGrownAt,
            msgCursor = msgCursor,
            lastNote = lastNote,
        )
}
