package app.timetable.ui

import app.timetable.greet.Holidays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 年历排版计算的回归测试。
 *
 * 这里钉死的都是"在真机上画错了才会发现、而且很难一眼看出错在哪"的地方：
 *  - 12 个月排成几列几行、一个月份块够不够装下 6 周格子（装不下就是下半年被裁掉）；
 *  - 2 个全角汉字塞不塞得进一格（塞不下就是相邻两天的名字连成一片）；
 *  - 名字截断规则与"同一天多个节日画哪个"；
 *  - 点空白格 / 点月份块缝隙时不能返回一个"看得见但不在那里"的日期。
 *
 * 全部是纯 JVM 断言。凡是需要 Paint / Canvas / MeasureSpec 才能算的东西都不在这里测 ——
 * 本仓库开着 `testOptions.unitTests.isReturnDefaultValues = true`，那些 API 在单测里
 * 只会返回 0/null，测了等于没测。
 */
class CalendarLayoutTest {

    // 真机上"设置页里的年历"实际能拿到多少宽度（见 activity_settings.xml 与 SettingsActivity）：
    //   屏宽 − 页面左右 padding 各 16dp − 卡片里给日历留的左右 margin 各 16dp = 屏宽 − 64dp
    /** 360dp 屏（当前最常见的窄屏） */
    private val phone360 = 296f
    /** 411dp 屏（Pixel 一类，用户报过"360dp 宽约 600dp 高"的那档） */
    private val phone411 = 347f
    /** 600dp 平板 */
    private val tablet600 = 536f
    /** 800dp 平板 */
    private val tablet800 = 736f

    private fun assertClose(expected: Float, actual: Float, delta: Float = 0.01f, msg: String = "") =
        assertEquals(msg, expected, actual, delta)

    // ------------------------------------------------------------------ 排成几列几行

    @Test
    fun narrowPhoneDropsToTwoColumns() {
        // 这次改动的核心：窄屏从 3 列改成 2 列，格子宽度从 ~13dp 翻到 ~20dp，
        // 否则一行节日名（2 个汉字）根本没有立足之地
        assertEquals(2, CalendarLayout.columnsFor(phone360))
        assertEquals(2, CalendarLayout.columnsFor(phone411))
        assertEquals(20.14f, CalendarLayout.cellWidthDp(phone360, 2), 0.02f)
        assertEquals(12.95f, CalendarLayout.cellWidthDp(phone360, 3), 0.02f)
    }

    @Test
    fun wideScreensGetMoreColumnsSoCellsStayBig() {
        // 平板不是"每格变大到夸张"，而是"月份排得更多、格子维持在一个舒服的区间"
        assertEquals(3, CalendarLayout.columnsFor(tablet600))
        assertEquals(4, CalendarLayout.columnsFor(tablet800))
        for (w in intArrayOf(296, 347, 536, 736, 1000)) {
            val m = CalendarLayout.layoutFor(w.toFloat(), 1f)
            assertTrue("宽 $w：格子 ${m.cellW} 宽于上限", m.cellW <= 28.001f)
            assertTrue("宽 $w：格子 ${m.cellW} 窄于下限", m.cellW >= 15f)
        }
    }

    @Test
    fun twelveMonthsAlwaysFillExactlyTheDeclaredGrid() {
        for (w in 100..1400 step 1) {
            val m = CalendarLayout.layoutFor(w.toFloat(), 1f)
            val idx = CalendarLayout.MONTHS
            assertEquals("宽 $w：列数要在 2..4", true, m.cols in 2..4)
            assertEquals("宽 $w：行数必须是 ⌈12/列数⌉", (12 + m.cols - 1) / m.cols, m.rows)
            assertTrue("宽 $w：网格装不下 12 个月", m.cols * m.rows >= idx)
        }
    }

    @Test
    fun columnsNeverShrinkAsTheScreenGetsWider() {
        var prev = 0
        var w = 100f
        while (w <= 1400f) {
            val cols = CalendarLayout.columnsFor(w)
            assertTrue("宽 $w：列数从 $prev 掉到了 $cols", cols >= prev)
            prev = cols
            w += 1f
        }
        assertEquals("再宽也该封顶在 4 列", 4, prev)
    }

    @Test
    fun absurdlyNarrowScreensFallBackToTwoColumnsInsteadOfCrashing() {
        // 窄到 2 列也放不下时，仍然返回 2 列（而不是 1 列或 0 列）——见 MIN_CELL_W_DP 的注释
        assertEquals(2, CalendarLayout.columnsFor(0f))
        assertEquals(2, CalendarLayout.columnsFor(60f))
        assertEquals(2, CalendarLayout.columnsFor(120f))
        assertTrue(CalendarLayout.layoutFor(60f, 1f).cellH > 0f)
    }

    @Test
    fun monthBlocksNeverOverflowTheReportedWidth() {
        for (w in intArrayOf(256, 296, 347, 411, 536, 736, 1000, 1280)) {
            val m = CalendarLayout.layoutFor(w.toFloat(), 1f)
            val lastRight = CalendarLayout.blockLeft(CalendarLayout.MONTHS - 1, m) + m.blockW
            assertTrue("宽 $w：最后一个月月份块的右边界 $lastRight 超出了 $w", lastRight <= w + 0.01f)
            assertTrue("宽 $w：首个月份块左边距为负", CalendarLayout.blockLeft(0, m) >= 0f)
        }
    }

    // ------------------------------------------------------------------ 高度

    @Test
    fun blockHeightCoversSixWeekRows() {
        // 一个月最多 6 行格子（1 号落在周日 + 31 天的大月）。块高少乘一次 6，
        // 表现就是"每个月最后一周被裁掉半行"，而且只有大月才看得出来 —— 必须钉死。
        for (w in intArrayOf(296, 347, 536, 736)) {
            val m = CalendarLayout.layoutFor(w.toFloat(), 1f)
            assertClose(
                m.titleH + m.weekH + m.cellH * 6f,
                m.blockH,
                0.01f,
                "宽 $w：块高没覆盖 6 行格子"
            )
        }
    }

    @Test
    fun everyMonthOfEveryYearFitsInsideItsBlock() {
        // 用真实日历跑一遍：任何一年任何一月的最后一个日期，落在的格子行必须 < 6，且底边在块内
        val m = CalendarLayout.layoutFor(phone360, 1f)
        for (year in 2024..2030) {
            for (month in 1..12) {
                val lead = CalendarLayout.leadOffset(year, month)
                val len = LocalDate.of(year, month, 1).lengthOfMonth()
                val row = (lead + len - 1) / 7
                assertTrue("$year-$month 落在第 ${row + 1} 行，超出 6 行", row <= 5)
                val bottom = CalendarLayout.cellTopInBlock(len, lead, m) + m.cellH
                assertTrue(
                    "$year-$month 最后一格底边 $bottom 超出月份块底 ${m.blockH}",
                    bottom <= m.blockH + 0.01f
                )
            }
        }
    }

    @Test
    fun totalHeightIsTheFullHeightNeverTheCompressedOne() {
        // 放进 ScrollView 用 wrap_content，必须上报完整高度；而且整块高度要与行数一致，
        // 不能"只报一屏"——那样下半年会被裁掉
        for (w in intArrayOf(296, 347, 536, 736, 1280)) {
            val m = CalendarLayout.layoutFor(w.toFloat(), 1f)
            val expected = m.originY * 2f + m.rows * m.blockH + m.gapY * (m.rows - 1)
            assertClose(expected, m.totalHeight, 0.01f, "宽 $w：上报高度与行数对不上")
            val lastBottom = CalendarLayout.blockTop(CalendarLayout.MONTHS - 1, m) + m.blockH
            assertTrue("宽 $w：第 12 个月的底边 $lastBottom 超出上报高度 ${m.totalHeight}", lastBottom <= m.totalHeight + 0.01f)
        }
    }

    @Test
    fun narrowPhoneCalendarIsAboutElevenHundredDpTall() {
        // 这是这次改动明确的代价：360dp 屏上从约 551dp 变成约 1133dp（6 行 × 6 个月块）。
        // 数字写进测试是为了让它"不可悄悄变胖"——真要再高，会在这里红掉。
        val m = CalendarLayout.layoutFor(phone360, 1f)
        assertClose(1133.26f, m.totalHeight, 1f, "360dp 屏上的年历高度变了")
        val w411 = CalendarLayout.layoutFor(phone411, 1f)
        assertClose(1165.6f, w411.totalHeight, 1f, "411dp 屏上的年历高度变了")
        assertTrue("411dp 屏比 360dp 屏还矮？", w411.totalHeight > m.totalHeight)
    }

    // ------------------------------------------------------------------ 格内字号

    @Test
    fun nameIsSmallerThanTheDayNumberButNotTooSmall() {
        for (w in intArrayOf(296, 347, 536, 736, 1280)) {
            val m = CalendarLayout.layoutFor(w.toFloat(), 1f)
            assertTrue("宽 $w：名字字号没比日期小一号", m.nameTextPx < m.dayTextPx)
            assertTrue("宽 $w：名字字号 ${m.nameTextPx} 低于 6dp 下限", m.nameTextPx >= 6f - 0.01f)
            assertTrue("宽 $w：日期字号 ${m.dayTextPx} 不在 7~10dp 区间", m.dayTextPx in 7f..10f)
            assertTrue("宽 $w：名字字号 ${m.nameTextPx} 超过 8dp 上限", m.nameTextPx <= 8f + 0.01f)
        }
    }

    @Test
    fun twoHanCharsAlwaysFitInsideTheCellAtEveryFontScale() {
        // 汉字是全角：1 个字的渲染宽度 = 1 个字号。名字要放 2 个字，
        // 而字号会跟着系统字体缩放长，长过头就会横向顶出格子、和邻格的名字连成一片。
        // FONT_SCALE_CAP 就是为这条不变式设的。
        var width = 240f
        while (width <= 1200f) {
            for (scale in floatArrayOf(1f, 1.15f, 1.3f, 1.5f, 2f, 3f)) {
                val m = CalendarLayout.layoutFor(width, 1f, scale)
                val rendered = 2f * m.nameTextPx
                assertTrue(
                    "可用宽 $width / 字体缩放 $scale：2 个汉字要 ${rendered}dp，格子只有 ${m.cellW}dp",
                    rendered <= m.cellW + 0.01f
                )
            }
            width += 1f
        }
    }

    @Test
    fun dayNumberAndNameAndDotsDoNotOverlapVertically() {
        for (w in intArrayOf(296, 347, 536, 736)) {
            for (scale in floatArrayOf(1f, 1.3f, 2f)) {
                val m = CalendarLayout.layoutFor(w.toFloat(), 1f, scale)
                val tag = "宽 $w / 缩放 $scale"
                assertTrue("$tag：日期基线不在格子内", m.dateBaselineY in 0f..m.cellH)
                assertTrue("$tag：名字基线不在日期基线之下", m.nameBaselineY > m.dateBaselineY)
                assertTrue("$tag：名字基线 ${m.nameBaselineY} 超出格高 ${m.cellH}", m.nameBaselineY < m.cellH)
                assertTrue("$tag：圆点行 ${m.dotCenterY} 没在名字之下", m.dotCenterY > m.nameBaselineY)
                assertTrue("$tag：圆点行 ${m.dotCenterY} 超出格高 ${m.cellH}", m.dotCenterY < m.cellH)
                // "今天/选中"的圆只包住日期行：圆的最低点不能碰到名字
                val circleBottom = m.circleCy + m.circleRadius
                assertTrue("$tag：今天的实心圆 $circleBottom 盖到了名字基线 ${m.nameBaselineY}", circleBottom < m.nameBaselineY)
            }
        }
    }

    @Test
    fun gridTopLeavesRoomForTheWeekHeader() {
        val m = CalendarLayout.layoutFor(phone360, 1f)
        assertClose(m.titleH + m.weekH, m.gridTop, 0.001f)
        assertTrue("星期表头跑到标题上面去了", m.weekH > 0f && m.titleH > m.weekH)
    }

    // ------------------------------------------------------------------ 圆点排布

    @Test
    fun dotsAreSymmetricAroundTheDayNumber() {
        val r = 1.8f
        val gap = 1.2f
        for (count in 1..3) {
            val xs = (0 until count).map { CalendarLayout.dotCenterX(it, count, 100f, r, gap) }
            assertClose(100f, (xs.first() + xs.last()) / 2f, 0.001f, "count=$count 的圆点没有以格心对称")
            assertEquals(count, xs.toSet().size)
        }
    }

    @Test
    fun threeDotsStillFitInsideOneCell() {
        // 元旦撞腊八再撞校历节点时最多三个点，一排点不能顶出格子
        for (w in intArrayOf(256, 296, 347, 536, 736, 1000)) {
            val m = CalendarLayout.layoutFor(w.toFloat(), 1f)
            val total = 3 * m.dotRadius * 2f + 2 * m.dotGapPx
            assertTrue("宽 $w：三个圆点要 ${total}dp，格子只有 ${m.cellW}dp", total <= m.cellW)
        }
    }

    // ------------------------------------------------------------------ 名字截断规则

    @Test
    fun twoCharNamesAreKeptAsIs() {
        for (name in listOf("元旦", "劳动", "国庆", "春节", "端午", "中秋", "重阳", "腊八", "校庆", "除夕", "元宵")) {
            assertEquals(name, CalendarLayout.fitName(name))
        }
    }

    @Test
    fun longerNamesAreCutToTheFirstTwoCharsWithoutEllipsis() {
        // 规则：取前 2 个字，不加省略号（"…"要额外占半个字宽，窄屏会顶出格子）
        assertEquals("劳动", CalendarLayout.fitName("劳动节"))
        assertEquals("国庆", CalendarLayout.fitName("国庆节"))
        assertEquals("教师", CalendarLayout.fitName("教师节"))
        assertEquals("母亲", CalendarLayout.fitName("母亲节"))
        assertEquals("平安", CalendarLayout.fitName("平安夜"))
        assertEquals("春季", CalendarLayout.fitName("春季开学"))
        assertEquals("暑假", CalendarLayout.fitName("暑假开始"))
        assertEquals("龙抬", CalendarLayout.fitName("龙抬头"))
        for (s in listOf("劳动节", "国庆节", "春季开学")) {
            assertTrue("$s 截断后不该带省略号", "…" !in CalendarLayout.fitName(s))
        }
    }

    @Test
    fun truncationIsIdempotentAndNeverLengthensTheName() {
        for (name in listOf("元旦", "劳动节", "寒假开始", "国庆节", "", "x")) {
            val once = CalendarLayout.fitName(name)
            assertEquals("截断两次结果应当一样", once, CalendarLayout.fitName(once))
            assertTrue("截断不该让名字变长", once.length <= name.length)
        }
    }

    @Test
    fun degenerateNamesAreSafe() {
        assertEquals("", CalendarLayout.fitName(""))
        assertEquals("", CalendarLayout.fitName("元旦", 0))
        assertEquals("", CalendarLayout.fitName("元旦", -1))
        assertEquals("元", CalendarLayout.fitName("元旦", 1))
        assertEquals("国庆节", CalendarLayout.fitName("国庆节", 9))
    }

    @Test
    fun truncationDoesNotSplitASurrogatePair() {
        // "𠮷" 是 BMP 之外的字（两个 UTF-16 单元）。按码点截才不会画出半个乱码方块
        val name = "\uD842\uDFB7野家"
        assertEquals("\uD842\uDFB7野", CalendarLayout.fitName(name, 2))
    }

    @Test
    fun everyRealHolidayNameSurvivesTruncation() {
        // 用真实节日表跑一遍：任何一个节日的名字都必须截得出东西，
        // 否则日历上会出现"有点但没名字"的格子
        for (year in 2026..2028) {
            for ((date, h) in Holidays.all(year)) {
                val label = CalendarLayout.fitName(h.name)
                assertTrue("$date ${h.name} 截断后是空的", label.isNotEmpty())
                assertEquals(
                    "$date ${h.name} 截断长度不对",
                    minOf(h.name.length, CalendarLayout.NAME_MAX_CHARS),
                    label.length
                )
            }
        }
    }

    // ------------------------------------------------------------------ 多节日时的优先级

    private fun h(name: String, kind: Holidays.Kind) = Holidays.Holiday(name, "测试", kind)

    @Test
    fun solarWinsOverLunarAndSchool() {
        val hits = listOf(
            h("校庆", Holidays.Kind.SCHOOL),
            h("腊八", Holidays.Kind.LUNAR),
            h("元旦", Holidays.Kind.SOLAR)
        )
        assertEquals("元旦", CalendarLayout.primaryHoliday(hits)?.name)
    }

    @Test
    fun lunarWinsOverSchool() {
        val hits = listOf(h("寒假开始", Holidays.Kind.SCHOOL), h("春节", Holidays.Kind.LUNAR))
        assertEquals("春节", CalendarLayout.primaryHoliday(hits)?.name)
    }

    @Test
    fun schoolIsUsedWhenItIsTheOnlyCategory() {
        val hits = listOf(h("期中周", Holidays.Kind.SCHOOL))
        assertEquals("期中周", CalendarLayout.primaryHoliday(hits)?.name)
    }

    @Test
    fun priorityDoesNotDependOnTheInputOrder() {
        val solar = h("国庆节", Holidays.Kind.SOLAR)
        val lunar = h("中秋", Holidays.Kind.LUNAR)
        val school = h("秋季开学", Holidays.Kind.SCHOOL)
        for (order in listOf(
            listOf(solar, lunar, school),
            listOf(school, lunar, solar),
            listOf(lunar, school, solar),
            listOf(school, solar, lunar)
        )) {
            assertEquals(order.toString(), "国庆节", CalendarLayout.primaryHoliday(order)?.name)
        }
    }

    @Test
    fun noHolidayMeansNoName() {
        assertNull(CalendarLayout.primaryHoliday(emptyList()))
        assertTrue(CalendarLayout.kindsOf(emptyList()).isEmpty())
    }

    @Test
    fun oneLabelPerDayIsPickedFromTheRealTable() {
        // 真实数据上的端到端契约：任何一天，画出来的名字都属于"当天优先级最高的那一类"
        for (year in 2026..2028) {
            for (day in 1..365) {
                val date = LocalDate.of(year, 1, 1).plusDays(day.toLong() - 1)
                val hits = Holidays.of(date)
                if (hits.isEmpty()) {
                    assertNull(CalendarLayout.primaryHoliday(hits))
                    continue
                }
                val picked = CalendarLayout.primaryHoliday(hits)!!
                val bestRank = hits.minOf { rank(it.kind) }
                assertEquals("$date 选中的 ${picked.name} 不是最高优先级的类别", bestRank, rank(picked.kind))
            }
        }
    }

    private fun rank(kind: Holidays.Kind): Int = when (kind) {
        Holidays.Kind.SOLAR -> 0
        Holidays.Kind.LUNAR -> 1
        Holidays.Kind.SCHOOL -> 2
    }

    @Test
    fun kindsAreDeduplicatedAndInSettingOrder() {
        val hits = listOf(
            h("校庆", Holidays.Kind.SOLAR),
            h("腊八", Holidays.Kind.LUNAR),
            h("期中周", Holidays.Kind.SCHOOL),
            h("又一个公历", Holidays.Kind.SOLAR)
        )
        assertEquals(
            listOf(Holidays.Kind.SOLAR, Holidays.Kind.LUNAR, Holidays.Kind.SCHOOL),
            CalendarLayout.kindsOf(hits)
        )
        assertEquals(
            listOf(Holidays.Kind.LUNAR),
            CalendarLayout.kindsOf(listOf(h("腊八", Holidays.Kind.LUNAR), h("小年", Holidays.Kind.LUNAR)))
        )
    }

    // ------------------------------------------------------------------ 命中测试

    @Test
    fun clickingTheMiddleOfADayReturnsThatDay() {
        val m = CalendarLayout.layoutFor(phone360, 1f)
        // 2026-01-01 是周四 → 前置空格 3 个
        assertEquals(3, CalendarLayout.leadOffset(2026, 1))
        val left = CalendarLayout.blockLeft(0, m) + CalendarLayout.cellLeftInBlock(1, 3, m)
        val top = CalendarLayout.blockTop(0, m) + CalendarLayout.cellTopInBlock(1, 3, m)
        val hit = CalendarLayout.dateAt(left + m.cellW / 2f, top + m.cellH / 2f, 2026, m)
        assertEquals(LocalDate.of(2026, 1, 1), hit)
    }

    @Test
    fun clickingTheBlankCellsBeforeTheFirstIsNotADate() {
        val m = CalendarLayout.layoutFor(phone360, 1f)
        val lead = CalendarLayout.leadOffset(2026, 1)
        // 1 号左边那几格是上个月的空白，不能回报成"0 号"或上个月的最后一天
        for (col in 0 until lead) {
            val x = CalendarLayout.blockLeft(0, m) + col * m.cellW + m.cellW / 2f
            val y = CalendarLayout.blockTop(0, m) + m.gridTop + m.cellH / 2f
            assertNull("第 $col 列是 1 号之前的空格，不该命中日期", CalendarLayout.dateAt(x, y, 2026, m))
        }
    }

    @Test
    fun clickingAfterTheLastDayOfTheMonthIsNotADate() {
        val m = CalendarLayout.layoutFor(phone360, 1f)
        // 2026-05：1 号是周五（前置 4），共 31 天 → 31 号落在第 5 行第 7 列（index 34），
        // 它下面第 6 行第 1 列已经是 6 月 1 号的空位
        val lead = CalendarLayout.leadOffset(2026, 5)
        assertEquals(4, lead)
        val monthIndex = 4
        val x31 = CalendarLayout.blockLeft(monthIndex, m) + 6 * m.cellW + m.cellW / 2f
        val yRow4 = CalendarLayout.blockTop(monthIndex, m) + m.gridTop + 4 * m.cellH + m.cellH / 2f
        assertEquals(LocalDate.of(2026, 5, 31), CalendarLayout.dateAt(x31, yRow4, 2026, m))
        val x0 = CalendarLayout.blockLeft(monthIndex, m) + m.cellW / 2f
        val yRow5 = CalendarLayout.blockTop(monthIndex, m) + m.gridTop + 5 * m.cellH + m.cellH / 2f
        assertNull(CalendarLayout.dateAt(x0, yRow5, 2026, m))
    }

    @Test
    fun clickingTheHeaderOrTheGapIsNotADate() {
        val m = CalendarLayout.layoutFor(phone360, 1f)
        val x = CalendarLayout.blockLeft(0, m) + m.cellW / 2f
        // 月份标题 + 星期表头那一带
        assertNull(CalendarLayout.dateAt(x, m.originY + 1f, 2026, m))
        assertNull(CalendarLayout.dateAt(x, CalendarLayout.blockTop(0, m) + m.gridTop - 1f, 2026, m))
        // 月份块之间的横向缝隙（第一列块右边界到第二列块左边界之间）
        val gapX = CalendarLayout.blockLeft(0, m) + m.blockW + 1f
        val yInside = CalendarLayout.blockTop(0, m) + m.gridTop + m.cellH / 2f
        assertNull(CalendarLayout.dateAt(gapX, yInside, 2026, m))
        // 上下两块之间的纵向缝隙
        val yGap = CalendarLayout.blockTop(0, m) + m.blockH + 1f
        assertNull(CalendarLayout.dateAt(x, yGap, 2026, m))
    }

    @Test
    fun clickingOutsideTheViewIsNotADate() {
        val m = CalendarLayout.layoutFor(phone360, 1f)
        assertNull(CalendarLayout.dateAt(-1f, 100f, 2026, m))
        assertNull(CalendarLayout.dateAt(10f, -1f, 2026, m))
        assertNull(CalendarLayout.dateAt(phone360 + 20f, 100f, 2026, m))
        assertNull(CalendarLayout.dateAt(10f, m.totalHeight + 20f, 2026, m))
    }

    @Test
    fun everyRealDayCanBeHitBackFromItsOwnCell() {
        // 绘制与命中测试共用同一份换算的端到端校验：1~12 月每一天，用"画它的那个格子的中心"
        // 去反推，必须原样返回同一天
        for (w in floatArrayOf(296f, 347f, 536f, 736f)) {
            val m = CalendarLayout.layoutFor(w, 1f)
            for (month in 1..12) {
                val lead = CalendarLayout.leadOffset(2026, month)
                val len = LocalDate.of(2026, month, 1).lengthOfMonth()
                val monthIndex = month - 1
                for (day in 1..len) {
                    val x = CalendarLayout.blockLeft(monthIndex, m) +
                        CalendarLayout.cellLeftInBlock(day, lead, m) + m.cellW / 2f
                    val y = CalendarLayout.blockTop(monthIndex, m) +
                        CalendarLayout.cellTopInBlock(day, lead, m) + m.cellH / 2f
                    assertEquals(
                        "宽 $w 的 $month/$day 反推错了",
                        LocalDate.of(2026, month, day),
                        CalendarLayout.dateAt(x, y, 2026, m)
                    )
                }
            }
        }
    }

    @Test
    fun monthBlocksAreLaidOutInReadingOrder() {
        val m = CalendarLayout.layoutFor(phone360, 1f)
        assertEquals(2, m.cols)
        assertEquals(6, m.rows)
        // 1 月和 2 月同一行，左边那个更靠左；3 月在下一行，y 更大
        assertEquals(CalendarLayout.blockTop(0, m), CalendarLayout.blockTop(1, m), 0.001f)
        assertTrue(CalendarLayout.blockLeft(0, m) < CalendarLayout.blockLeft(1, m))
        assertTrue(CalendarLayout.blockTop(2, m) > CalendarLayout.blockTop(0, m))
        // 12 个月都在视图内
        for (i in 0 until 12) {
            assertTrue("第 ${i + 1} 个月的块顶超出了视图", CalendarLayout.blockTop(i, m) >= 0f)
        }
    }
}
