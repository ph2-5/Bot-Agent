package com.qbot.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 场景的四个原语工具。
 *
 * 关键设计：**AI 决定做什么，代码决定最多能做什么**。
 * 工具只负责把 AI 的意图翻译成场景状态变更，所有上限都在这里兜底
 * （等待时长、连续催场次数、单场景时长由运行时检查，每小时条数由 store 计数）。
 *
 * OBSERVE 模式下工具照常被调用、照常落表，但 [SceneDecisionRecord.executed] = false，
 * 不改任何场景状态——第一期先靠这批数据判断 AI 会不会乱开场。
 */
class SceneToolbox(
    private val store: SceneStore,
    private val modeProvider: () -> SceneMode,
    private val selfIdProvider: () -> String,
    private val selfNameProvider: () -> String = { "" },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 同一场景相邻两轮不能连续 wait：模型会为了用工具而用工具，把对谈拖死。 */
    private val lastAction = java.util.concurrent.ConcurrentHashMap<Long, String>()

    val tools: List<BotTool> get() = listOf(startTool, waitTool, promptTool, endTool)

    private val startTool =
        object : BotTool {
            override val spec =
                ToolSpec(
                    name = "start_scene",
                    description =
                        "和某个具体的人开始一段双人对谈（相声式一来一回）。" +
                            ScenePrompt.START_HINT +
                            "开场时必须同时写好 contract：这是你为这次演出定的规则，要具体、可执行。" +
                            "不要写「要自然」「要幽默」这类空话；角色规则要写清「谁能做什么、不能做什么」，" +
                            "而不是形容词。角色名用你在对话里看到的叫法。",
                    parameters =
                        Json.parseToJsonElement(
                            """
                            {
                              "type": "object",
                              "properties": {
                                "role": {
                                  "type": "string",
                                  "enum": ["comedian", "straight"],
                                  "description": "你在这段里演逗哏(comedian)还是捧哏(straight)，按你的人格自己判断"
                                },
                                "partnerName": {
                                  "type": "string",
                                  "description": "对方在你眼里的名字，如「于谦」"
                                },
                                "goal": {
                                  "type": "string",
                                  "description": "这段要说到哪儿，一句话，如「聊到借钱收场」"
                                },
                                "contract": {
                                  "type": "object",
                                  "description": "你为这次演出写的规则。每项都要具体到能照着做，不要写形容词。",
                                  "properties": {
                                    "languageStyle": {
                                      "type": "string",
                                      "description": "口音/语体/句长/怎么称呼对方，最多 120 字。例：京津口语，短句为主，多用「您」"
                                    },
                                    "roleRules": {
                                      "type": "object",
                                      "description": "角色名 → 该角色能做什么、不能做什么。最多 4 个角色，每条 80 字。例：{\"你\":\"抛话题、铺包袱，每句留口子；不许自己接自己的包袱\"}",
                                      "additionalProperties": { "type": "string" }
                                    },
                                    "taboos": {
                                      "type": "array",
                                      "items": { "type": "string" },
                                      "description": "禁忌，最多 5 条，每条 40 字。例：[\"不提政治\",\"不拿家人做人身攻击\"]"
                                    },
                                    "exitCriteria": {
                                      "type": "string",
                                      "description": "什么情况算说完、怎么收尾，最多 80 字"
                                    },
                                    "props": {
                                      "type": "array",
                                      "items": { "type": "string" },
                                      "description": "这次可以用的素材或话题，可选，最多 3 条，每条 40 字"
                                    }
                                  }
                                }
                              },
                              "required": ["role", "partnerName", "goal", "contract"]
                            }
                            """.trimIndent(),
                        ) as JsonObject,
                )

            override suspend fun execute(arguments: JsonObject, turn: ToolTurn): ToolResult {
                val groupId = turn.groupId?.takeIf { it.isNotBlank() }
                    ?: return ToolResult("不在群里，无法开场。")
                val mode = modeProvider()
                if (mode == SceneMode.OFF) {
                    return ToolResult("场景功能未开启，不要调用场景工具。")
                }
                val existing = runCatching { store.active(groupId) }.getOrNull()
                if (existing != null) {
                    return ToolResult("本群已经有一段进行中的对谈，不要重复开始。")
                }
                val selfId = selfIdProvider()
                if (selfId.isBlank()) return ToolResult("机器人身份未知，稍后再试。")

                val partnerName =
                    arguments["partnerName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (partnerName.isBlank()) return ToolResult("缺少对方的名字，未开场。")
                val goal = arguments["goal"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val role = parseRole(arguments["role"]?.jsonPrimitive?.contentOrNull)
                // 契约可能是缺的或畸形的：解析失败不阻断开场，回退固定模板即可
                val contract =
                    (arguments["contract"] as? JsonObject)
                        ?.let { runCatching { SceneContract.fromJson(it) }.getOrNull() }
                        ?.takeIf { !it.isEmpty() }
                val now = clock()
                val executed = mode == SceneMode.ACTIVE

                val scene =
                    Scene(
                        id = 0L,
                        kind = SceneKind.CROSSTALK,
                        groupId = groupId,
                        participants =
                            listOf(
                                SceneParticipant(
                                    senderId = selfId,
                                    displayName = selfNameProvider().ifBlank { "我" },
                                    isBot = true,
                                    role = role,
                                ),
                                // 对方 openid 未知：先占位，等对方开口时由运行时认领
                                SceneParticipant(
                                    senderId = "",
                                    displayName = partnerName.take(20),
                                    isBot = true,
                                    role = opposite(role),
                                ),
                            ),
                        goal = goal.take(80),
                        status = SceneStatus.ACTIVE,
                        turnIndex = 0,
                        // 开场由我起头
                        expectedSpeakerId = selfId,
                        waitUntil = null,
                        lastActivityAt = now,
                        createdAt = now,
                        contract = contract,
                    )
                val saved = if (executed) store.save(scene) else scene
                runCatching {
                    store.record(
                        SceneDecisionRecord(
                            sceneId = saved.id.takeIf { it > 0L },
                            groupId = groupId,
                            action = SceneAction.START,
                            argsJson =
                                buildJsonObject {
                                    put("role", role.name)
                                    put("partnerName", partnerName.take(20))
                                    put("goal", goal.take(80))
                                    // OBSERVE 不建场景，契约只存在于这条记录里——
                                    // 复盘页靠它判断 AI 写的规则是不是空话
                                    contract?.let {
                                        put(
                                            "contract",
                                            Json.encodeToString(SceneContract.serializer(), it).take(800),
                                        )
                                    }
                                }.toString(),
                            executed = executed,
                            reason = "AI 判断要跟「${partnerName.take(20)}」演一段",
                            createdAt = now,
                        ),
                    )
                }
                return ToolResult(
                    if (executed) {
                        "已开始和「${partnerName.take(20)}」的对谈。下一句由你先说，记得给他留接话的口子。"
                    } else {
                        "（只读模式）已记录开场意图，未实际开场。按普通对话继续回复即可。"
                    },
                )
            }
        }

    private val waitTool =
        object : BotTool {
            override val spec =
                ToolSpec(
                    name = "wait_for_reply",
                    description =
                        "说完这句后把话头交给对方，等 ${SceneLimits.MIN_WAIT_SECONDS}-" +
                            "${SceneLimits.MAX_WAIT_SECONDS} 秒。只在对方需要想一想、或你想留出接口时用；" +
                            "连着两轮都等会把对谈拖死。",
                    parameters =
                        Json.parseToJsonElement(
                            """
                            {
                              "type": "object",
                              "properties": {
                                "seconds": {
                                  "type": "integer",
                                  "minimum": ${SceneLimits.MIN_WAIT_SECONDS},
                                  "maximum": ${SceneLimits.MAX_WAIT_SECONDS}
                                }
                              },
                              "required": ["seconds"]
                            }
                            """.trimIndent(),
                        ) as JsonObject,
                )

            override suspend fun execute(arguments: JsonObject, turn: ToolTurn): ToolResult {
                val mode = modeProvider()
                if (mode == SceneMode.OFF) {
                    return ToolResult("场景功能未开启，不要调用场景工具。")
                }
                val scene = turn.scene
                val seconds =
                    (arguments["seconds"]?.jsonPrimitive?.intOrNull ?: 25)
                        .coerceIn(SceneLimits.MIN_WAIT_SECONDS, SceneLimits.MAX_WAIT_SECONDS)
                val now = clock()
                val executed = mode == SceneMode.ACTIVE
                // OBSERVE 下没有真实场景：也落一条记录，才能统计"模型想等多久"
                if (scene == null) {
                    if (!executed) {
                        runCatching {
                            store.record(
                                SceneDecisionRecord(
                                    groupId = turn.groupId.orEmpty(),
                                    action = SceneAction.WAIT,
                                    argsJson = buildJsonObject { put("seconds", seconds) }.toString(),
                                    executed = false,
                                    reason = "只读：没有进行中的场景，仍想等待",
                                    createdAt = now,
                                ),
                            )
                        }
                        return ToolResult("（只读模式）已记录等待意图，未实际等待。")
                    }
                    return ToolResult("现在没有进行中的对谈，不要调用动作工具。")
                }

                if (executed && lastAction[scene.id] == SceneAction.WAIT) {
                    return ToolResult("上一轮已经等过了，这一轮直接说，不要再等。")
                }
                runCatching {
                    store.record(
                        SceneDecisionRecord(
                            sceneId = scene.id,
                            groupId = scene.groupId,
                            action = SceneAction.WAIT,
                            argsJson = buildJsonObject { put("seconds", seconds) }.toString(),
                            executed = executed,
                            reason = "AI 选择等对方接话",
                            createdAt = now,
                        ),
                    )
                }
                if (!executed) return ToolResult("（只读模式）已记录等待意图，未实际等待。")

                lastAction[scene.id] = SceneAction.WAIT
                val partnerId = scene.partnerOf(turn.senderId.orEmpty())?.senderId.orEmpty()
                store.save(
                    scene.copy(
                        status = SceneStatus.WAITING,
                        waitUntil = now + seconds * 1000L,
                        // 把话头明确交给对方：巡检据此判断"等的人没来"
                        expectedSpeakerId = partnerId,
                        lastActivityAt = now,
                    ),
                )
                return ToolResult("已把话头交给对方，最多等 $seconds 秒。")
            }
        }

    private val promptTool =
        object : BotTool {
            override val spec =
                ToolSpec(
                    name = "prompt_partner",
                    description =
                        "对方迟迟不接时催一句（「该您了」）。连续催三次仍没回应，这段就自动收尾。",
                    parameters =
                        Json.parseToJsonElement(
                            """
                            {
                              "type": "object",
                              "properties": {
                                "message": {
                                  "type": "string",
                                  "description": "催促的话，一句话，别超过 20 字"
                                }
                              },
                              "required": ["message"]
                            }
                            """.trimIndent(),
                        ) as JsonObject,
                )

            override suspend fun execute(arguments: JsonObject, turn: ToolTurn): ToolResult {
                val mode = modeProvider()
                if (mode == SceneMode.OFF) {
                    return ToolResult("场景功能未开启，不要调用场景工具。")
                }
                val scene = turn.scene
                val text = arguments["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val now = clock()
                val executed = mode == SceneMode.ACTIVE
                if (scene == null) {
                    if (!executed) {
                        runCatching {
                            store.record(
                                SceneDecisionRecord(
                                    groupId = turn.groupId.orEmpty(),
                                    action = SceneAction.PROMPT,
                                    argsJson = buildJsonObject { put("message", text.take(40)) }.toString(),
                                    executed = false,
                                    reason = "只读：没有进行中的场景，仍想催场",
                                    createdAt = now,
                                ),
                            )
                        }
                        return ToolResult("（只读模式）已记录催场意图，未实际催促。")
                    }
                    return ToolResult("现在没有进行中的对谈，不要调用动作工具。")
                }
                runCatching {
                    store.record(
                        SceneDecisionRecord(
                            sceneId = scene.id,
                            groupId = scene.groupId,
                            action = SceneAction.PROMPT,
                            argsJson = buildJsonObject { put("message", text.take(40)) }.toString(),
                            executed = executed,
                            reason = "AI 催对方接话",
                            createdAt = now,
                        ),
                    )
                }
                if (!executed) return ToolResult("（只读模式）已记录催场意图，未实际催促。")

                lastAction[scene.id] = SceneAction.PROMPT
                // waitUntil 设为现在：场景巡检会立刻把这句话发出去。
                // 连续催几次由运行时计数（工具不持有跨轮次的状态，避免两份计数打架）。
                store.save(
                    scene.copy(
                        status = SceneStatus.WAITING,
                        waitUntil = now,
                        expectedSpeakerId = scene.partnerOf(turn.senderId.orEmpty())?.senderId.orEmpty(),
                        lastActivityAt = now,
                    ),
                )
                return ToolResult("已安排催场，巡检会在下一次检查时发出。")
            }
        }

    private val endTool =
        object : BotTool {
            override val spec =
                ToolSpec(
                    name = "end_scene",
                    description = "这段说到收底了就结束，别硬拖。",
                    parameters =
                        Json.parseToJsonElement(
                            """
                            {
                              "type": "object",
                              "properties": {
                                "reason": {
                                  "type": "string",
                                  "description": "为什么收，一句话"
                                }
                              },
                              "required": ["reason"]
                            }
                            """.trimIndent(),
                        ) as JsonObject,
                )

            override suspend fun execute(arguments: JsonObject, turn: ToolTurn): ToolResult {
                val mode = modeProvider()
                if (mode == SceneMode.OFF) {
                    return ToolResult("场景功能未开启，不要调用场景工具。")
                }
                val scene = turn.scene
                val reason = arguments["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val now = clock()
                val executed = mode == SceneMode.ACTIVE
                if (scene == null) {
                    if (!executed) {
                        runCatching {
                            store.record(
                                SceneDecisionRecord(
                                    groupId = turn.groupId.orEmpty(),
                                    action = SceneAction.END,
                                    executed = false,
                                    reason = reason.take(60).ifBlank { "只读：没有进行中的场景，仍想收尾" },
                                    createdAt = now,
                                ),
                            )
                        }
                        return ToolResult("（只读模式）已记录收尾意图，未实际结束。")
                    }
                    return ToolResult("现在没有进行中的对谈，不要调用动作工具。")
                }
                runCatching {
                    store.record(
                        SceneDecisionRecord(
                            sceneId = scene.id,
                            groupId = scene.groupId,
                            action = SceneAction.END,
                            argsJson = null,
                            executed = executed,
                            reason = reason.take(60).ifBlank { null },
                            createdAt = now,
                        ),
                    )
                }
                if (!executed) return ToolResult("（只读模式）已记录收尾意图，未实际结束。")

                lastAction.remove(scene.id)
                store.end(scene.id, now)
                return ToolResult("已收底，这段结束。")
            }
        }

    /** 场景真正结束时清掉内存计数，避免 map 长期增长。 */
    fun forget(sceneId: Long) {
        lastAction.remove(sceneId)
    }

    /** 对方接上话了：相邻两轮的限制重置（下一轮可以再等）。 */
    fun onPartnerSpoke(sceneId: Long) {
        lastAction.remove(sceneId)
    }

    private fun parseRole(raw: String?): SceneRole =
        when (raw?.trim()?.lowercase()) {
            "comedian", "逗哏" -> SceneRole.COMEDIAN
            "straight", "捧哏" -> SceneRole.STRAIGHT
            else -> SceneRole.COMEDIAN
        }

    private fun opposite(role: SceneRole): SceneRole =
        when (role) {
            SceneRole.COMEDIAN -> SceneRole.STRAIGHT
            SceneRole.STRAIGHT -> SceneRole.COMEDIAN
        }
}
