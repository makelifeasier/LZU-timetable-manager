package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **每日一句搬进可滚动列表之后**的列表组成（纯逻辑）+ 它与布局的对应关系。
 *
 * 用户原话："把每日一句显示在可上下滑动的地方并且把原来的这句话（移到）课表上面。这样就刚刚好了。"
 * 于是它从"列表下方一块固定的 22dp"变成"列表的第一项"：
 *  - 跟着列表一起滚，想多看一眼往下滑；
 *  - 不与图片抢预算（[ExtrasPlanner] 里那套"谁让谁"整条删掉，图片框因此高了 22dp）；
 *  - 顺序是"一句话在上、课程在下"，与用户要的一致。
 *
 * 这一组守三件事：
 *  1. **索引映射**（[WidgetListPlan]）：错一格的后果是"滑一下发现某节课不见了"，
 *     真机上极难定位，而这里是几条断言就能钉死的东西；
 *  2. **行高必须与课程行完全一致**（复用 `widget_today_row.xml`）：列表高度取的是 38dp 的整数倍，
 *     只要句子那行不是 38dp，底部就会切出半行（真机反馈的"第三节课露半个"）；
 *  3. **"强制隐藏"档位照样管得住它**：那个开关的语义是"这些附加内容我不要"，
 *     句子虽然换了位置，但语义不能变。
 */
class WidgetListPlanTest {

    private fun repoFile(relative: String): File? = listOf(
        File(relative),
        File("app/$relative"),
        File("/$relative")
    ).firstOrNull { it.isFile }

    // --------------------------------------------------------------- 1. 索引映射

    @Test
    fun theQuoteIsTheVeryFirstItemWhenItIsOn() {
        // 开着句子：第 0 项是句子，课程从第 1 项开始 —— "一句话在课表上面"就是这一条
        assertTrue(WidgetListPlan.quoteRow(quoteEnabled = true, override = 0))
        assertTrue(WidgetListPlan.quoteRow(quoteEnabled = true, override = 1))
        assertEquals("列表第 0 项是句子（没有对应的课程行）", -1, WidgetListPlan.courseIndex(0, true))
        assertEquals("第 1 项 = 第 0 节课", 0, WidgetListPlan.courseIndex(1, true))
        assertEquals("第 2 项 = 第 1 节课", 1, WidgetListPlan.courseIndex(2, true))
        assertEquals(3, WidgetListPlan.courseIndex(4, true))
    }

    @Test
    fun withoutTheQuoteTheIndexIsTheCoursesOwnIndex() {
        // 关掉句子：列表就是课程本身，索引逐值相同（老行为）
        assertFalse(WidgetListPlan.quoteRow(quoteEnabled = false, override = 0))
        for (i in 0..5) {
            assertEquals(i, WidgetListPlan.courseIndex(i, false))
        }
    }

    @Test
    fun itemCountIsCoursesPlusTheQuoteSlot() {
        assertEquals("3 节课 + 一句话 = 4 项", 4, WidgetListPlan.itemCount(3, quoteRow = true))
        assertEquals("关掉句子就是 3 项", 3, WidgetListPlan.itemCount(3, quoteRow = false))
        assertEquals("一节课都没有时列表里只剩句子（不是空列表）", 1, WidgetListPlan.itemCount(0, true))
        assertEquals("一节课都没有 + 关掉句子 = 空列表（交给空状态文案）", 0, WidgetListPlan.itemCount(0, false))
        // 脏值（负数）不能算出负项数
        assertEquals(0, WidgetListPlan.itemCount(-5, quoteRow = false))
        assertEquals(1, WidgetListPlan.itemCount(-5, quoteRow = true))
    }

    @Test
    fun everyPositionMapsToAValidCourseRowOrTheQuote() {
        // 把 0..N 全扫一遍：除第 0 项（句子）之外，每个位置都必须映射到**存在的那节课**
        for (courses in 0..6) {
            val total = WidgetListPlan.itemCount(courses, quoteRow = true)
            assertEquals("项数", courses + 1, total)
            var seen = 0
            for (position in 0 until total) {
                val index = WidgetListPlan.courseIndex(position, quoteRow = true)
                if (index < 0) {
                    assertEquals("只有第 0 项是句子", 0, position)
                } else {
                    assertTrue("第 $position 项映射到不存在的第 $index 节课", index < courses)
                    seen++
                }
            }
            assertEquals("每节课都必须被映射到且只映射一次", courses, seen)
        }
    }

    // --------------------------------------------------------------- 2. "强制隐藏"档位

    @Test
    fun theForceHideOverrideAlsoHidesTheQuote() {
        // override = -1（设置里的「强制隐藏」）的语义是"这些附加内容我不要"：
        // 句子换了位置，语义不变 —— 否则用户明明关掉了它却还在（换了个地方出现）
        assertFalse(WidgetListPlan.quoteRow(quoteEnabled = true, override = -1))
        // 自动（0）与强制显示（1）都照用户的开关走
        assertTrue(WidgetListPlan.quoteRow(quoteEnabled = true, override = 0))
        assertTrue(WidgetListPlan.quoteRow(quoteEnabled = true, override = 1))
        assertFalse(WidgetListPlan.quoteRow(quoteEnabled = false, override = 1))
    }

    // --------------------------------------------------------------- 3. 句子占的那一格

    /**
     * 句子搬进列表之后**它也占一格** —— 这一格必须在排行数时就扣掉。
     *
     * 最初只让它"挤掉课程的一格"（列表总格数不变），真机上（4×2 + 图片，行数=1）
     * 整块组件只剩一句"好好吃饭"、一节课都看不见。所以现在的规则是：先给句子**另加一格**，
     * 课程行数不变；只有"另加一格会把图片压到硬底线以下"时，才退回"占课程的一格"。
     */
    @Test
    fun theQuoteGetsItsOwnSlotSoNoCourseIsHidden() {
        // 4×2（187dp）+ 照片：照片预留 78dp（226dp 宽、3.14:1 → 72 + 6）
        val reserve = 78
        val withQuote = WidgetData.listSlots(
            heightDp = 187, manualRows = 0, slotDp = WidgetData.ROW_SLOT_DP,
            reserveDp = reserve, quoteOn = true
        )
        assertEquals("句子另占一格（课程照旧 1 行）", 1, withQuote.courseRows)
        assertEquals(1, withQuote.quoteSlots)
        assertEquals("列表一共 2 格 = 2×38dp", 2, withQuote.totalSlots)
        // 图片框还剩 187 − 71 − 76 − 6 = 34dp ≥ 硬底线 32dp，站得住 → 走"另加一格"
        val photoBox = 187 - WidgetData.CHROME_DP.toInt() - withQuote.totalSlots * 38 - WidgetData.AREA_GAP_DP
        assertEquals(34, photoBox)
        assertTrue("图片必须还在（≥ 硬底线）", photoBox >= WidgetData.PHOTO_HARD_MIN_DP)

        // 关掉句子：一格都不占，课程行数与"没有句子"时逐值相同（老行为）
        val withoutQuote = WidgetData.listSlots(
            heightDp = 187, manualRows = 0, slotDp = WidgetData.ROW_SLOT_DP,
            reserveDp = reserve, quoteOn = false
        )
        assertEquals(1, withoutQuote.courseRows)
        assertEquals(0, withoutQuote.quoteSlots)
        assertEquals(1, withoutQuote.totalSlots)
    }

    @Test
    fun theSlotRuleNeverHidesEveryCourseWhenTheWidgetIsTallEnough() {
        // 187..400dp 全扫一遍 + 开句子，逐档核对"这一格从哪儿来"：
        //  · 图片让得起 38dp（让完还 ≥ 硬底线）→ 课程行数**与关掉句子时逐值相同**（一格都不少）；
        //  · 让不起 → 句子占课程的一格（课程 −1），但图片保持原样，且**绝不会是负格数**。
        // 真机踩过的坑（句子吃掉唯一那格、组件只剩一句话）在这条里不可能再出现：
        // 那要求 rows == 0 且仍然占格，而 rows 的下限是 1、退让后最多减到 0。
        val reserve = 78
        var additive = 0
        var giveWay = 0
        for (h in 90..400) {
            val on = WidgetData.listSlots(h, 0, WidgetData.ROW_SLOT_DP, reserve, quoteOn = true)
            val off = WidgetData.listSlots(h, 0, WidgetData.ROW_SLOT_DP, reserve, quoteOn = false)
            assertEquals("${h}dp：句子最多占一格", 1, on.quoteSlots)
            assertTrue("${h}dp：课程格数不能是负的", on.courseRows >= 0)
            assertTrue("${h}dp：课程至少要在关掉句子时的基础上最多让一格", on.courseRows >= off.courseRows - 1)

            val chrome = WidgetData.CHROME_DP.toInt()
            val photoOff = h - chrome - off.totalSlots * 38 - WidgetData.AREA_GAP_DP
            val photoOn = h - chrome - on.totalSlots * 38 - WidgetData.AREA_GAP_DP
            if (photoOff - 38 >= WidgetData.PHOTO_HARD_MIN_DP) {
                // 让得起 → 另一格；课程一格不少，图片矮 38dp
                additive++
                assertEquals("${h}dp：让得起时课程行数必须一格不少", off.courseRows, on.courseRows)
                assertEquals("${h}dp：让得起时列表要多一格", off.totalSlots + 1, on.totalSlots)
                assertEquals("${h}dp：这一格是图片让出来的", 38, photoOff - photoOn)
                assertTrue("${h}dp：让完之后图片仍要 ≥ 硬底线", photoOn >= WidgetData.PHOTO_HARD_MIN_DP)
            } else {
                // 让不起 → 占课程的一格；图片与关掉句子时逐值相同
                giveWay++
                assertEquals("${h}dp：退让时课程 −1（不会更多）", off.courseRows - 1, on.courseRows)
                assertEquals("${h}dp：退让时列表格数不变", off.totalSlots, on.totalSlots)
                assertEquals("${h}dp：退让时图片高度不变", photoOff, photoOn)
            }
            // 列表 + 图片 + 头部不能超出格子。
            // 只对 ≥187dp 断言：90~186dp 这种极小格子本来就装不下"头部 + 一行课 + 图片"，
            // 溢出的几个 dp 由系统裁（[WidgetData.photoReserveDp] 的注释里写着这条老规矩），
            // 那属于"矮组件"档，不是这条用例要守的东西。
            val used = chrome + on.totalSlots * 38 + WidgetData.AREA_GAP_DP
            if (h >= 187) {
                assertTrue("${h}dp：列表+图片超出格子（用掉 ${used}dp）", used <= h)
            }
        }
        assertTrue("这一段里应当两档都出现过（否则这条用例只测了一档）", additive > 0 && giveWay > 0)
    }

    @Test
    fun manualRowCountsAreNotChangedByTheQuote() {
        // 手动档是硬约束："用户说了几行课就是几行课"。句子挤在最上面由列表自己滚 ——
        // 不能因为句子的开关把用户指定行数改掉
        for (manual in 1..6) {
            val slots = WidgetData.listSlots(300, manual, WidgetData.ROW_SLOT_DP, 0, quoteOn = true)
            assertEquals("手动 $manual 行必须原样保留", manual, slots.courseRows)
            assertEquals(1, slots.quoteSlots)
            val off = WidgetData.listSlots(300, manual, WidgetData.ROW_SLOT_DP, 0, quoteOn = false)
            assertEquals(manual, off.courseRows)
            assertEquals(0, off.quoteSlots)
        }
    }

    // --------------------------------------------------------------- 4. 与布局/行高同源

    @Test
    fun theQuoteRowReusesTheCourseRowLayoutSoTheHeightIsIdentical() {
        // 列表高度 = 整行数 × 38dp（WidgetData.listHeightDp）。句子那一行只要不是 38dp，
        // 列表底部就会切出半行 —— 而自检里那条 `rowHeights=.. uniform=true` 也会立刻变假。
        // 所以它**必须**复用课程行的布局，只把用不到的部件藏起来。
        val src = repoFile("src/main/java/app/timetable/widget/TimetableWidgetService.kt")
            ?: error("找不到 TimetableWidgetService.kt（测试工作目录假设有变）")
        val code = src.readText().lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertTrue(
            "句子那一行必须复用课程行布局（行高才会与课程行逐值相同）",
            code.contains("buildQuoteRow") &&
                code.contains("RemoteViews(context.packageName, R.layout.widget_today_row)")
        )
        // 行高由布局写死 38dp —— 与 WidgetData.ROW_SLOT_DP 必须一致（两处同源）
        val rowXml = repoFile("src/main/res/layout/widget_today_row.xml")
            ?: error("找不到 widget_today_row.xml（测试工作目录假设有变）")
        val rootHeight = Regex(
            "android:id=\"@\\+id/row_root\"[\\s\\S]{0,200}?android:layout_height=\"(\\d+)dp\""
        ).find(rowXml.readText())?.groupValues?.get(1)?.toInt()
            ?: error("widget_today_row.xml 里读不到 row_root 的高度")
        assertEquals(
            "行布局的高度必须等于 WidgetData.ROW_SLOT_DP（列表高度是按它算的）",
            WidgetData.ROW_SLOT_DP.toInt(), rootHeight
        )
    }

    @Test
    fun theQuoteIsNoLongerAStandaloneRowInTheWidgetLayout() {
        // 回归：底部那块固定的句子控件必须**不在了**（它就是被搬走的那 22dp）。
        // 留着它的后果是两处都在显示句子（一块固定、一块滚动）。
        val layout = repoFile("src/main/res/layout/widget_today.xml")
            ?: error("找不到 widget_today.xml（测试工作目录假设有变）")
        val xml = layout.readText()
        assertFalse("widget_today.xml 里不该再有 widget_quote", xml.contains("@+id/widget_quote"))
        // 而图片区仍然在（底部只剩它）
        assertTrue(xml.contains("@+id/widget_photo_area"))
    }

    @Test
    fun whatIsNotCoveredHere() {
        // 覆盖不到的：
        //  1. RemoteViews 到底能不能被宿主 inflate（`setTextViewTextSize` / `setViewVisibility`
        //     都在白名单里，但真机宿主的差异只有日志与截图能看出来）；
        //  2. 句子的**观感**（弱化成副文本色 11sp 是否够"不像一节课"）：只能看真机截图；
        //  3. 滚动位置的记忆（ListView 自己的行为，不归我们管）。
        assertTrue("本用例只是一条书面说明", true)
    }
}
