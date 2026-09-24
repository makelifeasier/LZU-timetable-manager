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
 *  - 荣耀"下方一大片空白"：系统回报的高度可能是我们声明的 minHeight 本身（回显），
 *    此时那个数字不含信息，必须按兜底高度算
 *  - 但**回显校正只认 minHeight（150dp）**：90dp 是 minResizeHeight —— 用户真能把组件
 *    拖到的最小格子，属于真实高度。以前 89..91dp 也被抬到 150dp，于是按 2 行排版硬塞进
 *    90dp 的格子，底部切出半行（真机表现：「只剩标题和被切一半的第一行」）。
 *    这条链路（采用高度 → 收页脚 → 整行数）在下面用纯函数整条钉住。
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
        // 只有「恰好等于声明的 minHeight（150dp）那一档」才是回显。
        assertTrue(WidgetData.looksLikeDeclaredEcho(150))
        assertTrue(WidgetData.looksLikeDeclaredEcho(149))
        assertTrue(WidgetData.looksLikeDeclaredEcho(151))
    }

    @Test
    fun resizeMinimumIsNoLongerTreatedAsAnEcho() {
        // 90dp 是 minResizeHeight，也就是**用户真能把组件拖到的最小尺寸**：
        // 它是真实格子高度、含信息，必须原样采信（此前把它当回显，会平白抬到 150dp）。
        // 代价写在 WidgetData.effectiveHeightDp 的注释里：少数启动器报 90dp 时我们会按 1 行算，
        // "真 90dp" 与"回显 90dp"这两种情况本来就分不开，这一档选择了相信用户。
        assertFalse(WidgetData.looksLikeDeclaredEcho(90))
        assertFalse(WidgetData.looksLikeDeclaredEcho(89))
        assertFalse(WidgetData.looksLikeDeclaredEcho(91))
        assertEquals(90, WidgetData.effectiveHeightDp(90))
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
    fun effectiveHeightPassesRealHeightsThrough() {
        // 采用高度必须与上报高度逐值相等（差 1dp 都可能让"2 行"变成"1 行 + 半行"）
        for (h in listOf(90, 92, 110, 130, 187, 260, 400)) {
            assertEquals("$h dp 是真实格子高度，必须原样采信", h, WidgetData.effectiveHeightDp(h))
        }
        // 没回报时按兜底；回显声明值（150dp）时也落在同一个数上
        assertEquals(WidgetData.FALLBACK_WIDGET_DP, WidgetData.effectiveHeightDp(0))
        assertEquals(WidgetData.FALLBACK_WIDGET_DP, WidgetData.effectiveHeightDp(150))
    }

    // -------------------------------------------- 真实最小格子（90dp）的整条链路

    @Test
    fun minimumCellShowsExactlyOneWholeRow() {
        // 回归（本轮修的 bug）：90dp 是用户把组件拖到最小之后的**真实**高度。
        // 以前它被当成"回显"抬成 150dp → 按 (150-71)/38 = 2 行去排版，却只有 90dp 的格子，
        // 于是底部切出半行：真机上只剩标题和被切一半的第一行。
        assertTrue("90dp 放不下页脚，应当收起页脚", WidgetData.compactFor(90, 0))
        assertEquals("把组件拉到最小后应当正好显示 1 整行", 1, WidgetData.rowsFor(90, 0))
        // 收页脚后的头部 49dp + 1 整行 38dp = 87dp ≤ 90dp —— 放得进，且不留半行
        val used = WidgetData.CHROME_DP - WidgetData.FOOTER_DP + slot
        assertTrue("$used dp 必须放得进 90dp 的格子", used <= 90f)
    }

    @Test
    fun normalHeightIsNotAffectedByThatFix() {
        // 上面那条改动不能把正常尺寸的观感带坏：150dp 仍然是"留着页脚 + 2 整行"
        assertFalse(WidgetData.compactFor(WidgetData.FALLBACK_WIDGET_DP, 0))
        assertEquals(2, WidgetData.rowsFor(WidgetData.FALLBACK_WIDGET_DP, 0))
        assertEquals(2, WidgetData.rowsFor(WidgetData.DECLARED_MIN_HEIGHT_DP, 0))
    }

    @Test
    fun manualRowsStillWinInTheSmallestCell() {
        // 手动指定行数时不自作主张：页脚不收起、行数照用户说的给（放不下的部分交给系统裁）
        assertFalse(WidgetData.compactFor(90, 2))
        assertEquals(2, WidgetData.rowsFor(90, 2))
    }

    @Test
    fun resolveRowsArithmeticIsUnchanged() {
        // resolveRows 本身的算术（纯函数，与回显校正无关）：
        // 90-71=19dp 连一行都放不满 → 夹到 1；150-71=79dp → 2 行
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
