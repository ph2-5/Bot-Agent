package com.qbot.core.ai

import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.MessageKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索接入记忆管线后的行为：
 * 相关记忆提前、近因保底、失败回退、精排开关。
 */
class MemoryPipelineSearchTest {
    private var now = 1_700_000_000_000L
    private val identities = InMemoryIdentityStore()
    private val facts = InMemoryMemoryStore()
    private val room = InMemoryRoomContextStore()

    private class FakeSearcher(
        var candidates: List<MemoryCandidate> = emptyList(),
        var fail: Boolean = false,
    ) : MemorySearcher {
        var calls = 0

        override suspend fun recall(
            query: String,
            scopes: List<String>,
            kinds: Set<MemoryKind>,
            limit: Int,
        ): List<MemoryCandidate> {
            calls++
            if (fail) error("索引损坏")
            return candidates.filter { it.kind in kinds }
        }
    }

    private class FakeReranker(
        var order: (List<MemoryCandidate>) -> List<MemoryCandidate> = { it },
    ) : MemoryReranker {
        var calls = 0

        override suspend fun rerank(
            query: String,
            candidates: List<MemoryCandidate>,
            limit: Int,
        ): List<MemoryCandidate>? {
            calls++
            return order(candidates).take(limit)
        }
    }

    private fun pipeline(
        config: MemoryConfig,
        searcher: MemorySearcher? = null,
        reranker: MemoryReranker? = null,
    ) = MemoryPipeline(
        identities = identities,
        facts = facts,
        roomContext = room,
        configProvider = { config },
        clock = { now },
        searcher = searcher,
        reranker = reranker,
    )

    private fun message(
        content: String,
        senderId: String = "sender-1",
        targetId: String = "group-1",
    ) = IncomingMessage(
        kind = MessageKind.GROUP_AT,
        eventType = "GROUP_AT_MESSAGE_CREATE",
        messageId = "msg-1",
        content = content,
        targetId = targetId,
        senderId = senderId,
        timestampMillis = now,
        raw = buildJsonObject { },
    )

    private suspend fun addFact(
        text: String,
        ageDays: Int = 0,
    ): Long =
        facts.add(
            UserFact(
                senderId = "sender-1",
                text = text,
                updatedAt = now - ageDays * 86_400_000L,
            ),
        )

    @Test
    fun `相关但更旧的事实被提到最前面`() =
        runTest {
            val oldId = addFact("喜欢打原神", ageDays = 30)
            addFact("最近在备考", ageDays = 0)

            val searcher =
                FakeSearcher(
                    candidates =
                        listOf(
                            MemoryCandidate(
                                kind = MemoryKind.FACT,
                                refId = oldId,
                                text = "喜欢打原神",
                                updatedAt = now - 30 * 86_400_000L,
                                rarity = 1.0,
                                matchedTerms = 2,
                            ),
                        ),
                )

            val ctx =
                pipeline(MemoryConfig(learnFacts = false), searcher)
                    .buildContext(message("原神怎么配队"), "bot", "原神怎么配队")

            assertEquals("喜欢打原神", ctx.facts.first().text)
            assertEquals(2, ctx.facts.size)
        }

    @Test
    fun `关闭检索时完全不查索引`() =
        runTest {
            addFact("喜欢打原神")
            val searcher = FakeSearcher()

            pipeline(MemoryConfig(learnFacts = false, searchEnabled = false), searcher)
                .buildContext(message("原神怎么配队"), "bot", "原神怎么配队")

            assertEquals(0, searcher.calls)
        }

    @Test
    fun `索引抛异常时退回按时间取且不影响回复`() =
        runTest {
            addFact("喜欢打原神", ageDays = 30)
            addFact("最近在备考", ageDays = 0)
            val searcher = FakeSearcher(fail = true)

            val ctx =
                pipeline(MemoryConfig(learnFacts = false), searcher)
                    .buildContext(message("原神怎么配队"), "bot", "原神怎么配队")

            // 回退后保持原顺序：新的在前
            assertEquals("最近在备考", ctx.facts.first().text)
            assertEquals(2, ctx.facts.size)
            assertEquals(1, searcher.calls)
        }

    @Test
    fun `群背景保留最近几条同时插入命中的旧消息`() =
        runTest {
            repeat(8) { index ->
                room.append(
                    ConversationKeys.groupRoom("group-1"),
                    RoomMessage(
                        senderId = "u$index",
                        senderName = "群友$index",
                        role = "user",
                        content = "第 $index 条",
                        timestampMillis = now - (8 - index) * 1_000L,
                        id = (index + 1).toLong(),
                    ),
                )
            }

            val searcher =
                FakeSearcher(
                    candidates =
                        listOf(
                            MemoryCandidate(
                                kind = MemoryKind.ROOM,
                                refId = 2,
                                text = "上周说的那个游戏",
                                updatedAt = now - 60 * 60_000L,
                                senderId = "u1",
                                senderName = "群友1",
                                role = "user",
                                rarity = 1.2,
                            ),
                        ),
                )

            val ctx =
                pipeline(MemoryConfig(learnFacts = false, roomContextSize = 10), searcher)
                    .buildContext(message("那个游戏叫什么"), "bot", "那个游戏叫什么")

            assertTrue("命中的旧消息应进背景", ctx.roomDigest.any { it.content == "上周说的那个游戏" })
            assertTrue("最近 6 条必须保底", ctx.roomDigest.map { it.content }.containsAll((2..7).map { "第 $it 条" }))
            assertEquals("按时间正序", ctx.roomDigest.sortedBy { it.timestampMillis }, ctx.roomDigest)
        }

    @Test
    fun `开启精排时按辅助模型的顺序注入`() =
        runTest {
            val ids = (1..6).map { addFact("记忆 $it", ageDays = 6 - it) }
            val candidates =
                ids.mapIndexed { index, id ->
                    MemoryCandidate(
                        kind = MemoryKind.FACT,
                        refId = id,
                        text = "记忆 ${index + 1}",
                        updatedAt = now - (6 - index) * 86_400_000L,
                        rarity = 1.0,
                        matchedTerms = 1,
                    )
                }
            val searcher = FakeSearcher(candidates)
            // 精排故意把“最新”的排到后面，确认注入顺序真的听了辅助模型
            val reranker = FakeReranker(order = { it.sortedByDescending { c -> c.refId } })

            val ctx =
                pipeline(
                    MemoryConfig(
                        learnFacts = false,
                        auxRankEnabled = true,
                        searchTopK = 2,
                    ),
                    searcher,
                    reranker,
                ).buildContext(message("随便说点什么"), "bot", "随便说点什么")

            assertEquals(1, reranker.calls)
            assertEquals("记忆 6", ctx.facts.first().text)
            assertEquals("记忆 5", ctx.facts[1].text)
        }
}
