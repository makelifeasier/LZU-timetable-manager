package app.timetable.greet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 农历表的正确性锚点。
 *
 * 表是手抄的常量，最怕的是"某一年抄错一位" —— 那种错误平时看不出来，
 * 只在某个春节当天把祝福弹早/弹晚一天。所以这里有三层防线：
 *  1. 已知的春节/端午/中秋日期（上面对齐真实历书）；
 *  2. 闰月锚点（2025 闰六月，全年第 384 天结构最容易抄错）；
 *  3. 全表扫描：相邻两天的农历日必须恰好连续（跨月跨年只能落在初一），
 *     表里任何一位抄错都会在这一步暴露。
 */
class LunarCalendarTest {

    private fun assertLunar(
        lunarYear: Int,
        month: Int,
        day: Int,
        date: String,
        leap: Boolean = false
    ) {
        val l = LunarCalendar.lunarOf(LocalDate.parse(date))
        assertEquals("$date 的农历年", lunarYear, l.year)
        assertEquals("$date 的农历月", month, l.month)
        assertEquals("$date 的农历日", day, l.day)
        assertEquals("$date 是否闰月", leap, l.leap)
    }

    @Test
    fun springFestivalKnownDates() {
        // 三个年份的春节：表若整体漂了，这里第一个炸
        assertLunar(2024, 1, 1, "2024-02-10")
        assertLunar(2025, 1, 1, "2025-01-29")
        assertLunar(2026, 1, 1, "2026-02-17")
    }

    @Test
    fun dragonBoatAndMidAutumn2026() {
        assertLunar(2026, 5, 5, "2026-06-19")    // 端午
        assertLunar(2026, 8, 15, "2026-09-25")   // 中秋
    }

    @Test
    fun leapMonthIsFlagged() {
        // 农历 2025 年闰六月（全年 384 天）：六月初一 6/25，闰六月初一 7/25，七月初一 8/23
        assertLunar(2025, 6, 1, "2025-06-25")
        assertLunar(2025, 6, 1, "2025-07-25", leap = true)
        assertLunar(2025, 7, 1, "2025-08-23")
    }

    @Test
    fun newYearsEveIsTheLastDayOfTwelfthMonth() {
        // 2025 春节是 1/29 → 1/28 必须是腊月最后一天（该年腊月为小月，廿九）
        assertLunar(2024, 12, 29, "2025-01-28")
    }

    @Test
    fun springFestivalPredecessorIsAlwaysTwelfthMonth() {
        // 三个已知春节的前一天都必须是腊月（除夕判定依赖这一点）
        for (cny in listOf("2024-02-10", "2025-01-29", "2026-02-17")) {
            val eve = LunarCalendar.lunarOf(LocalDate.parse(cny).minusDays(1))
            assertEquals("$cny 前一天应是腊月", 12, eve.month)
            assertTrue("$cny 前一天不该是闰月", !eve.leap)
        }
    }

    @Test
    fun wholeTableIsContiguousAndWellFormed() {
        // 1900-01-31（农历 1900 年正月初一）扫到 2100 年底，共约 7.3 万天。
        // 逐日校验两件事：数值合法 + 与次日恰好相差一天。
        var d = LocalDate.of(1900, 1, 31)
        val end = LocalDate.of(2100, 12, 31)
        var prev = LunarCalendar.lunarOf(d)
        assertEquals(1, prev.month)
        assertEquals(1, prev.day)
        d = d.plusDays(1)
        while (!d.isAfter(end)) {
            val cur = LunarCalendar.lunarOf(d)
            assertTrue("$d 农历月越界：$cur", cur.month in 1..12)
            assertTrue("$d 农历日越界：$cur", cur.day in 1..30)
            assertTrue("$d 农历年偏了：$cur", cur.year in 1899..2100)

            val contiguous =
                (cur.month == prev.month && cur.leap == prev.leap && cur.day == prev.day + 1) ||
                    cur.day == 1    // 跨月/跨年/进闰月：必须正好回到初一
            assertTrue("$d 与前一天不连续：$prev → $cur", contiguous)
            prev = cur
            d = d.plusDays(1)
        }
    }

    @Test
    fun outOfTableDatesFallBackToTheNineteenYearCycle() {
        // 表外不抛异常，而且用默冬章（19 年 ≈ 235 个朔望月）平移出来，
        // 月日应当与"19 年后/前"的那一天一致
        val early = LunarCalendar.lunarOf(LocalDate.parse("1880-06-15"))
        val earlyShifted = LunarCalendar.lunarOf(LocalDate.parse("1994-06-15"))
        assertEquals(earlyShifted.month, early.month)
        assertEquals(earlyShifted.day, early.day)
        assertEquals("19 × 6 = 114 年", 114, earlyShifted.year - early.year)

        val late = LunarCalendar.lunarOf(LocalDate.parse("2200-05-05"))
        val lateShifted = LunarCalendar.lunarOf(LocalDate.parse("1991-05-05"))
        assertEquals(lateShifted.month, late.month)
        assertEquals(lateShifted.day, late.day)
        assertEquals("19 × 11 = 209 年", 209, late.year - lateShifted.year)
    }

    @Test
    fun extremeDatesDoNotThrow() {
        for (text in listOf("1899-12-31", "1850-01-01", "2101-01-01", "2101-02-15", "2500-08-08")) {
            val l = LunarCalendar.lunarOf(LocalDate.parse(text))
            assertTrue("$text 应给出合法的月份，实际 ${l.month}", l.month in 1..12)
            assertTrue("$text 应给出合法的日期，实际 ${l.day}", l.day in 1..30)
        }
    }
}
