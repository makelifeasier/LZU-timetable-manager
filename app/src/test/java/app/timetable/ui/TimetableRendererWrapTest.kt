package app.timetable.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 断行断点选择。
 *
 * `wrap` 本身依赖 Android 的 Paint（JVM 单测里量不出文字宽度），
 * 但**断点选择**是纯逻辑，也正是出过问题的地方 ——
 * 「天山堂A101」被硬切成「天山」/「堂A10…」，教室里那行等于白写。
 */
class TimetableRendererWrapTest {

    // ------------------------------------------------------------ 汉字 → 字母/数字

    @Test
    fun breaksBetweenChineseAndRoomNumber() {
        // 一行能塞下 4 个字符（"天山堂A"），断点应落在 3 —— 「天山堂」|「A101」
        assertEquals(3, TimetableRenderer.semanticCut("天山堂A101", 0, 4))
    }

    @Test
    fun breaksAfterBuildingNameBeforeLetter() {
        // "信息楼" 是 3 个字，断点在下标 3
        assertEquals(3, TimetableRenderer.semanticCut("信息楼E505", 0, 5))
    }

    @Test
    fun picksTheLastSemanticBoundaryInTheLine() {
        // "天山堂A1" 里有两个可断点吗？只有汉字→字母那一处
        assertEquals(3, TimetableRenderer.semanticCut("天山堂A101", 0, 5))
    }

    // ------------------------------------------------------------ 空格

    @Test
    fun breaksAfterSpace() {
        // "Blockly 创意…" 一行塞下 8 个字符，断点应在空格之后
        assertEquals(8, TimetableRenderer.semanticCut("Blockly 创意趣味编程", 0, 9))
    }

    // ------------------------------------------------------------ 没有断点

    @Test
    fun pureChineseWithNoBoundaryFallsBackToHardCut() {
        // 纯汉字没有语义断点 → 按能塞下的长度硬切
        assertEquals(3, TimetableRenderer.semanticCut("高等数学分析", 0, 3))
    }

    @Test
    fun cutsWhenBoundaryIsTooEarlyToBeWorthIt() {
        // "天山" + 长英文：可断点在下标 2，只占一行的一半 → 不值得，硬切
        assertEquals(6, TimetableRenderer.semanticCut("天山ABCXYZ", 0, 6))
    }

    @Test
    fun degenerateInputsAreSafe() {
        // 区间长度为 0 或 1：直接返回 end，不越界
        assertEquals(3, TimetableRenderer.semanticCut("天山堂", 3, 3))
        assertEquals(4, TimetableRenderer.semanticCut("天山堂A", 3, 4))
    }

    // ------------------------------------------------------------ 从中间开始

    @Test
    fun respectsStartOffset() {
        // 第二行从下标 3 开始："A101"（无断点）→ 硬切
        assertEquals(7, TimetableRenderer.semanticCut("天山堂A101", 3, 7))
    }

    @Test
    fun realRoomStringsFitInTwoLines() {
        // 回归：这些真机上的教室名，必须能在 2 行内按语义断成整齐的两截
        val rooms = listOf("天山堂A101", "天山堂B202", "昆仑堂C303", "信息楼E505", "天山堂D404")
        for (room in rooms) {
            val cut = TimetableRenderer.semanticCut(room, 0, 4)
            val line1 = room.substring(0, cut)
            val line2 = room.substring(cut)
            assertEquals("应当断在汉字与房号之间：$room", 3, cut)
            assertEquals("第一行是楼名：$room", 3, line1.length)
            assertEquals("第二行是房号：$room", 4, line2.length)
        }
    }
}
