package com.qbot.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第一道闸门的打分与冷却。
 *
 * 全部纯函数，不碰网络与数据库；重点验证"什么时候一定要忍住"——
 * 自主式最容易翻车的不是不会说，是不会忍。
 */
class AutonomousDriveTest {
    private val active =
        DriveConfig(
            mode = AutonomyMode.ACTIVE,
            threshold = DriveSensitivity.MEDIUM.threshold,
            quietHours = "23:00-08:00",
        )

    /** 一个"什么条件都不错"的基线状态 */
    private fun state(
        mood: Int = 70,
        interest: Double = 0.8,
        closeness: Double = 0.7,
        minutesSinceLastSpoke: Int = 45,
        recentMessageCount: Int = 4,
        ignoredCount: Int = 0,
        hourOfDay: Int = 15,
        hasMaterial: Boolean = true,
    ) = DriveState(
        mood = mood,
        interest = interest,
        closeness = closeness,
        minutesSinceLastSpoke = minutesSinceLastSpoke,
        recentMessageCount = recentMessageCount,
        ignoredCount = ignoredCount,
        hourOfDay = hourOfDay,
        hasMaterial = hasMaterial,
    )

    @Test
    fun `没有素材一定 HOLD`() {
        val decision = AutonomousDrive.evaluate(state(hasMaterial = false), active, 0L)

        assertEquals(DriveOutcome.HOLD, decision.outcome)
        assertFalse(decision.shouldSpeak)
        assertTrue(decision.reason.contains("素材"))
    }

    @Test
    fun `安静时段一定 HOLD`() {
        val decision = AutonomousDrive.evaluate(state(hourOfDay = 2), active, 0L)

        assertEquals(DriveOutcome.HOLD, decision.outcome)
        assertTrue(decision.reason.contains("安静时段"))
    }

    @Test
    fun `刚说完一分钟，时机因子把开口压下去`() {
        assertEquals(0.3, AutonomousDrive.timingFactor(1), 0.0001)
        assertEquals(1.2, AutonomousDrive.timingFactor(45), 0.0001)

        val justSpoke =
            AutonomousDrive.evaluate(
                state(
                    mood = 95,
                    interest = 1.0,
                    closeness = 1.0,
                    minutesSinceLastSpoke = 1,
                ),
                active,
                0L,
            )

        assertEquals(DriveOutcome.HOLD, justSpoke.outcome)
    }

    @Test
    fun `情绪低又被无视，一定 HOLD`() {
        val decision =
            AutonomousDrive.evaluate(
                state(mood = 20, interest = 0.5, closeness = 0.3, ignoredCount = 4),
                active,
                0L,
            )

        assertEquals(DriveOutcome.HOLD, decision.outcome)
        assertTrue(decision.score < active.threshold)
    }

    @Test
    fun `情绪高关系近有素材，可以 SPEAK`() {
        val decision =
            AutonomousDrive.evaluate(
                state(mood = 90, interest = 0.9, closeness = 0.9),
                active,
                0L,
            )

        assertEquals(DriveOutcome.SPEAK, decision.outcome)
        assertTrue(decision.score >= active.threshold)
    }

    @Test
    fun `同一 fingerprint 连续忍住三次就冷却`() {
        val fingerprint = AutonomousDrive.fingerprintOf("今晚的排位赛真是折磨")
        assertTrue("指纹不该为空", fingerprint.isNotBlank())
        // 原文换个标点仍应得到同一指纹——否则冷却永远不触发
        assertEquals(fingerprint, AutonomousDrive.fingerprintOf("今晚的排位赛真是折磨！"))

        val now = 1_000_000L
        val twice = listOf(fingerprint to now - 20 * 60_000L, fingerprint to now - 10 * 60_000L)
        assertFalse(AutonomousDrive.isCooling(twice, fingerprint, now))

        val thrice = twice + (fingerprint to now - 5 * 60_000L)
        assertTrue(AutonomousDrive.isCooling(thrice, fingerprint, now))

        // 冷却 30 分钟后自动解除，不能永久封锁话题
        assertFalse(AutonomousDrive.isCooling(thrice, fingerprint, now + 40 * 60_000L))
    }

    @Test
    fun `OFF 与 PASSIVE 档不跑计算`() {
        val off = AutonomousDrive.evaluate(state(), DriveConfig(mode = AutonomyMode.OFF), 0L)
        val passive = AutonomousDrive.evaluate(state(), DriveConfig(mode = AutonomyMode.PASSIVE), 0L)

        assertEquals(DriveOutcome.HOLD, off.outcome)
        assertEquals(0.0, off.score, 0.0001)
        assertEquals(DriveOutcome.HOLD, passive.outcome)
        assertEquals(0.0, passive.score, 0.0001)
    }

    @Test
    fun `安静时段支持跨零点与非法配置`() {
        assertTrue(AutonomousDrive.inQuietHours(23, "23:00-08:00"))
        assertTrue(AutonomousDrive.inQuietHours(7, "23:00-08:00"))
        assertFalse(AutonomousDrive.inQuietHours(12, "23:00-08:00"))
        // 配置写错时不要永久静默
        assertFalse(AutonomousDrive.inQuietHours(3, "写得不对"))
    }
}
