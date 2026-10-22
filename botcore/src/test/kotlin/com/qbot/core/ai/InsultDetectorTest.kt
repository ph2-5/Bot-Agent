package com.qbot.core.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 冒犯检测测试：被指名骂要认出来，@ 别人要让路。 */
class InsultDetectorTest {
    private val detector = InsultDetector()

    @Test
    fun `带侮辱词且叫名字算指名冒犯`() {
        assertTrue(
            detector.targetsBot("过去的阿远你个傻逼", listOf("过去的阿远")),
        )
    }

    @Test
    fun `只叫名字后缀也算`() {
        // 群里通常只叫后两个字
        assertTrue(detector.targetsBot("阿远真是个废物", listOf("过去的阿远")))
    }

    @Test
    fun `只提到名字不带冒犯不算`() {
        assertFalse(detector.targetsBot("阿远今天没来吗", listOf("过去的阿远")))
    }

    @Test
    fun `没有名字的脏话不算指名`() {
        assertFalse(detector.targetsBot("这游戏真垃圾", listOf("过去的阿远")))
    }

    @Test
    fun `谐音缩写也能识别`() {
        assertTrue(detector.targetsBot("阿远你就是个sb", listOf("过去的阿远")))
    }

    @Test
    fun `名字太短的后缀不匹配`() {
        // 单字后缀容易误伤普通词，刻意不启用
        assertFalse(detector.targetsBot("远远不够", listOf("阿远")))
    }

    @Test
    fun `识别是否在跟别人说话`() {
        assertTrue(detector.talksToOthers(listOf("OTHER openid"), "SELF"))
        assertFalse(detector.talksToOthers(listOf("SELF"), "SELF"))
        assertFalse(detector.talksToOthers(emptyList(), "SELF"))
    }
}
