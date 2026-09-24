package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class WeekCalcTest {

    private val week1 = LocalDate.of(2026, 9, 7)   // 假定的第 1 周周一

    @Test
    fun mondayOfAlwaysReturnsMonday() {
        for (offset in 0..41) {
            val d = week1.plusDays(offset.toLong())
            val m = WeekCalc.mondayOf(d)
            assertEquals(java.time.DayOfWeek.MONDAY, m.dayOfWeek)
            assertTrue(!m.isAfter(d))
            assertTrue(m.plusDays(7).isAfter(d))
        }
    }

    @Test
    fun weekOfCountsFromWeekOneMonday() {
        assertEquals(1, WeekCalc.weekOf(week1, week1))
        assertEquals(1, WeekCalc.weekOf(week1.plusDays(6), week1))
        assertEquals(2, WeekCalc.weekOf(week1.plusDays(7), week1))
        assertEquals(19, WeekCalc.weekOf(week1.plusDays(18 * 7L), week1))
    }

    @Test
    fun weekOfHandlesDatesBeforeTermStart() {
        // 第 1 周周一之前 1 天属于「第 0 周」，再往前 7 天才到 -1 周
        assertEquals(0, WeekCalc.weekOf(week1.minusDays(1), week1))
        assertEquals(0, WeekCalc.weekOf(week1.minusDays(7), week1))
        assertEquals(-1, WeekCalc.weekOf(week1.minusDays(8), week1))
    }

    @Test
    fun week1MondayRoundTrips() {
        // 对任意「今天」与 1..20 周，反推第 1 周周一后再算回来必须一致
        val today = LocalDate.of(2026, 9, 21)
        for (n in 1..20) {
            val monday = WeekCalc.week1Monday(today, n)
            assertEquals(java.time.DayOfWeek.MONDAY, monday.dayOfWeek)
            assertEquals(n, WeekCalc.weekOf(today, monday))
        }
    }

    @Test
    fun parityFiltering() {
        val even = WeekSpan(4, 18, Parity.EVEN)
        assertTrue(even.contains(4))
        assertTrue(even.contains(6))
        assertTrue(!even.contains(5))
        assertTrue(!even.contains(19))
        assertTrue(!even.contains(3))

        val odd = WeekSpan(6, 15, Parity.ODD)
        assertTrue(odd.contains(7))
        assertTrue(!odd.contains(8))

        val all = WeekSpan(4, 19, Parity.NONE)
        assertTrue(all.contains(5))
        assertTrue(all.contains(4))
        assertTrue(!all.contains(20))
    }

    @Test
    fun mergesConsecutiveSectionsOfSameCourse() {
        val a = Session("数学分析（一）", "1", "天山堂A303", "丙老师", WeekSpan(4, 19), "讲课学时", 1, 1, 1)
        val b = a.copy(startSection = 2, endSection = 2)
        val merged = WeekCalc.merge(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals(1, merged[0].startSection)
        assertEquals(2, merged[0].endSection)
    }

    @Test
    fun doesNotMergeDifferentCoursesOrWeeks() {
        val a = Session("数学分析（一）", "1", "天山堂A303", "丙老师", WeekSpan(4, 19), "", 1, 1, 1)
        val b = Session("解析几何", "1", "天山堂A408", "乙老师", WeekSpan(4, 19), "", 1, 2, 2)
        assertEquals(2, WeekCalc.merge(listOf(a, b)).size)

        // 名字相同但周次不同（同一格子里的两段）不能合并
        val c = a.copy(weeks = WeekSpan(4, 8), startSection = 1, endSection = 1)
        val d = a.copy(weeks = WeekSpan(9, 19), startSection = 2, endSection = 2)
        assertEquals(2, WeekCalc.merge(listOf(c, d)).size)

        // 同门课但中间空一节也不合并
        val e = a.copy(startSection = 1, endSection = 1)
        val f = a.copy(startSection = 3, endSection = 3)
        assertEquals(2, WeekCalc.merge(listOf(e, f)).size)
    }

    @Test
    fun todaySessionsRespectsWeekAndOrder() {
        val result = TimetableParser.parse(
            javaClass.classLoader!!.getResourceAsStream("timetable-2026autumn.html")!!
                .readBytes().toString(Charsets.UTF_8)
        )
        // 用 mondayOf 求真实周一，不要假设某个日期就是周一
        val monday = WeekCalc.mondayOf(LocalDate.of(2026, 9, 28))

        // 第 4 周周一：高阶英语1(第1-2节) + 高等代数(第3-4节) + 数学分析 + 生物多样性
        val week4 = WeekCalc.todaySessions(result, monday, 4)
        val names = week4.map { it.name }
        assertTrue(names.contains("高阶英语1"))
        assertTrue(names.contains("高等代数（一）"))
        assertEquals(4, week4.size)
        // 已按节次升序
        assertEquals(week4.map { it.startSection }.sorted(), week4.map { it.startSection })

        // 第 5 周：高阶英语1 是 4-18 周双周课，不上
        val week5 = WeekCalc.todaySessions(result, monday.plusDays(7), 5)
        assertTrue(week5.none { it.name == "高阶英语1" })
        assertTrue(week5.any { it.name == "高等代数（一）" })
    }

    @Test
    fun todaySessionsMergesConsecutiveBlocks() {
        val result = TimetableParser.parse(
            javaClass.classLoader!!.getResourceAsStream("timetable-2026autumn.html")!!
                .readBytes().toString(Charsets.UTF_8)
        )
        val monday = WeekCalc.mondayOf(LocalDate.of(2026, 9, 28))
        val english = WeekCalc.todaySessions(result, monday, 4).first { it.name == "高阶英语1" }
        assertEquals(1, english.startSection)
        assertEquals(2, english.endSection)   // 第 1、2 节合并为一段
    }

    @Test
    fun nextAndCurrentSessionFromClocks() {
        val result = TimetableParser.parse(
            javaClass.classLoader!!.getResourceAsStream("timetable-2026autumn.html")!!
                .readBytes().toString(Charsets.UTF_8)
        )
        val day = WeekCalc.mondayOf(LocalDate.of(2026, 9, 28))   // 第 4 周周一
        val week = 4

        assertEquals("高阶英语1", WeekCalc.currentSession(result, day, week, LocalTime.of(8, 45))?.name)
        // 08:30-10:10 是高阶英语1，09:30 时下一节应为 10:30 的高等代数
        assertEquals("高等代数（一）", WeekCalc.nextSession(result, day, week, LocalTime.of(9, 30))?.name)
        assertNull(WeekCalc.currentSession(result, day, week, LocalTime.of(7, 0)))
        assertNull(WeekCalc.nextSession(result, day, week, LocalTime.of(23, 0)))
    }

    @Test
    fun weekGridCoversSevenDays() {
        val result = TimetableParser.parse(
            javaClass.classLoader!!.getResourceAsStream("timetable-2026autumn.html")!!
                .readBytes().toString(Charsets.UTF_8)
        )
        val grid = WeekCalc.weekGrid(result, 4)
        assertEquals((1..7).toList(), grid.keys.sorted())
        assertTrue(grid[1]!!.isNotEmpty())
        assertTrue(grid[7]!!.isEmpty())
    }

    // ---------------------------------------------------------- 首次使用时的周次估算

    @Test
    fun guessesAutumnWeek1FromSeptember() {
        // 2026 秋季：9/1 是周二 → 第 1 周周一 = **8/31**
        // （用户实测：兰大秋季学期 8.31–9.6 就是第 1 周；旧规则"9/1 之后第一个周一"会给 9/7，整整差一周）
        assertEquals(
            LocalDate.of(2026, 8, 31),
            WeekCalc.guessWeek1Monday("2026", "秋", LocalDate.of(2026, 10, 20))
        )
    }

    @Test
    fun guessesSpringWeek1FromLateFebruary() {
        // 2027 春季：2/20 是周六 → 第 1 周周一 = 2/22
        assertEquals(
            LocalDate.of(2027, 2, 22),
            WeekCalc.guessWeek1Monday("2027", "春", LocalDate.of(2027, 4, 1))
        )
    }

    @Test
    fun autumnGuessGivesPlausibleWeekNumber() {
        // 关键：第 8 周才装上 App 的同学，必须看到「第 8 周左右」而不是「第 1 周」
        // 第 1 周周一 = 8/31 → 10/27 是第 9 周（按旧规则 9/7 起算则是第 8 周）
        val w1 = WeekCalc.guessWeek1Monday("2026", "秋", LocalDate.of(2026, 10, 27))!!
        assertEquals(9, WeekCalc.weekOf(LocalDate.of(2026, 10, 27), w1))
    }

    @Test
    fun guessReturnsNullForUnknownSeasonOrBeforeTermStart() {
        assertNull(WeekCalc.guessWeek1Monday("2026", "夏", LocalDate.of(2026, 7, 1)))
        // 还没开学 → 不猜
        assertNull(WeekCalc.guessWeek1Monday("2026", "秋", LocalDate.of(2026, 8, 1)))
        assertNull(WeekCalc.guessWeek1Monday("", "", LocalDate.of(2026, 8, 1)))
    }

    @Test
    fun initialWeek1FallsBackWhenTermUnknown() {
        val today = LocalDate.of(2026, 8, 1)   // 暑假：秋/春都猜不出来
        assertEquals(
            WeekCalc.week1Monday(today, 1),
            WeekCalc.initialWeek1Monday(ParseResult(), today)
        )
    }

    @Test
    fun initialWeek1UsesParsedTermWhenAvailable() {
        val result = TimetableParser.parse(
            javaClass.classLoader!!.getResourceAsStream("timetable-2026autumn.html")!!
                .readBytes().toString(Charsets.UTF_8)
        )
        // fixture 是 2026 秋 → 应估出 8/31（新学期默认值，见 guessesAutumnWeek1FromSeptember），
        // 而不是「本周=第1周」
        assertEquals(
            LocalDate.of(2026, 8, 31),
            WeekCalc.initialWeek1Monday(result, LocalDate.of(2026, 10, 27))
        )
    }
}
