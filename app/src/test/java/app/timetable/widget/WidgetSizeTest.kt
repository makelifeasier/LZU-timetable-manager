package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 小组件尺寸 → 显示几整行。
 *
 * 这一组规则全是**真机事故**换来的：
 *  - "第三行露半个"：列表高度必须恰好是行高的整数倍 → `resolveRows` 只返回整数
 *  - 荣耀"下方一大片空白"：系统回报的高度可能是我们声明的 minHeight/minResizeHeight
 *    本身（回显），此时那个数字不含信息，必须按兜底高度算
 *  - 最要命的一环是**声明值与代码常量漂移**：上个版本把 XML 里 minHeight 改成 90dp，
 *    代码里却还按 150dp 兜底，两边不一致 → 荣耀上只排出 1 行。
 *    所以这里直接读 XML 断言两边相等。
 */
class WidgetSizeTest {

    private val slot = WidgetData.ROW_SLOT_DP

    // ------------------------------------------------------------ 自动模式

    @Test
    fun fitsTwoRowsAtDefaultHeight() {
        // 150dp（兜底/默认）- 71dp（头部页脚） = 79dp → 79/38 = 2.07 → 2 行
        assertEquals(2, WidgetData.resolveRows(79f, slot, 0))
    }

    @Test
    fun neverReturnsHalfARow() {
        // 可用高度 149dp → 3.92 行 → 只能是 3（余下的 35dp 宁可空着也不露半行）
        assertEquals(3, WidgetData.resolveRows(149f, slot, 0))
    }

    @Test
    fun alwaysAtLeastOneRowEvenInTinyCell() {
        assertEquals(1, WidgetData.resolveRows(19f, slot, 0))
        assertEquals(1, WidgetData.resolveRows(0f, slot, 0))
        assertEquals(1, WidgetData.resolveRows(-50f, slot, 0))
    }

    @Test
    fun autoModeCapsAtFourRows() {
        assertEquals(WidgetData.AUTO_MAX_ROWS, WidgetData.resolveRows(1000f, slot, 0))
    }

    @Test
    fun guardsAgainstZeroSlot() {
        assertEquals(1, WidgetData.resolveRows(200f, 0f, 0))
    }

    // ------------------------------------------------------------ 手动模式

    @Test
    fun manualRowsWinOverComputedHeight() {
        // 荣耀/部分 ROM：回报 150dp，实际格子高得多 —— 用户说 4 行就得是 4 行
        assertEquals(4, WidgetData.resolveRows(79f, slot, 4))
        assertEquals(3, WidgetData.resolveRows(0f, slot, 3))
    }

    @Test
    fun manualRowsAreClampedToSaneRange() {
        assertEquals(WidgetData.MANUAL_MAX_ROWS, WidgetData.resolveRows(0f, slot, 99))
        assertEquals(1, WidgetData.resolveRows(0f, slot, -7))
    }

    // ------------------------------------------------------------ 回显识别

    @Test
    fun detectsEchoOfDeclaredMinimums() {
        assertTrue(WidgetData.looksLikeDeclaredEcho(90))
        assertTrue(WidgetData.looksLikeDeclaredEcho(89))
        assertTrue(WidgetData.looksLikeDeclaredEcho(150))
        assertTrue(WidgetData.looksLikeDeclaredEcho(149))
    }

    @Test
    fun realCellHeightsAreNotTreatedAsEcho() {
        // 小米/OPPO/Pixel 报的是真实格子高度，这些都得原样采信
        assertFalse(WidgetData.looksLikeDeclaredEcho(110))
        assertFalse(WidgetData.looksLikeDeclaredEcho(130))
        assertFalse(WidgetData.looksLikeDeclaredEcho(187))
        assertFalse(WidgetData.looksLikeDeclaredEcho(260))
        assertFalse(WidgetData.looksLikeDeclaredEcho(0))
    }

    @Test
    fun echoOfResizeMinimumIsRaisedToFallback() {
        // 75dp 用 38dp 的行放得下 0 行（会被夹到 1）；校正成 150dp 后是 2 行
        assertEquals(1, WidgetData.resolveRows(90f - WidgetData.CHROME_DP, slot, 0))
        assertEquals(2, WidgetData.resolveRows(150f - WidgetData.CHROME_DP, slot, 0))
    }

    // ------------------------------------------------------------ XML ↔ 代码常量

    @Test
    fun declaredSizesMatchCodeConstants() {
        val f = listOf(
            File("src/main/res/xml/widget_today_info.xml"),
            File("app/src/main/res/xml/widget_today_info.xml")
        ).firstOrNull { it.isFile } ?: error("找不到 widget_today_info.xml（测试工作目录假设有变）")
        val xml = f.readText()

        val minHeight = dpAttr(xml, "minHeight")
        val minResizeHeight = dpAttr(xml, "minResizeHeight")
        assertEquals(
            "XML 声明的 minHeight 必须与 DECLARED_MIN_HEIGHT_DP 一致：" +
                "两边不一致时，回显这个值的启动器（荣耀）会按错误的高度排行",
            WidgetData.DECLARED_MIN_HEIGHT_DP, minHeight
        )
        assertEquals(
            WidgetData.DECLARED_MIN_RESIZE_HEIGHT_DP, minResizeHeight
        )
        assertEquals(
            "取不到尺寸时的兜底高度应与声明的 minHeight 一致",
            WidgetData.DECLARED_MIN_HEIGHT_DP, WidgetData.FALLBACK_WIDGET_DP
        )
        assertTrue("声明的最小高度必须够放 2 整行", minHeight >= 2 * slot + WidgetData.CHROME_DP)
    }

    private fun dpAttr(xml: String, name: String): Int {
        val m = Regex("android:$name=\"(\\d+)dp\"").find(xml)
            ?: error("widget_today_info.xml 里没有 android:$name")
        return m.groupValues[1].toInt()
    }
}
