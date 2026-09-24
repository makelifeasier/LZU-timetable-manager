package app.timetable.greet

import app.timetable.data.WeekCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 节日表的判定测试。重点在三件容易出错、且出错很显眼的事上：
 *  - 浮动节日（母亲节/父亲节）算得对不对；
 *  - 农历节日的公历日期对不对；
 *  - 普通日子必须**干净**（返回空）——否则用户一年会被弹十几次莫名其妙的祝福。
 */
class HolidaysTest {

    private fun names(date: String): List<String> =
        Holidays.of(LocalDate.parse(date)).map { it.name }

    private fun kindOf(date: String, name: String): Holidays.Kind? =
        Holidays.of(LocalDate.parse(date)).firstOrNull { it.name == name }?.kind

    private fun datesOf(year: Int, name: String): List<LocalDate> =
        Holidays.all(year).filter { it.second.name == name }.map { it.first }

    // ------------------------------------------------------------------ 公历

    @Test
    fun newYearAndNationalDayAreSolar() {
        assertTrue("元旦", "元旦" in names("2026-01-01"))
        assertEquals(Holidays.Kind.SOLAR, kindOf("2026-01-01", "元旦"))
        assertTrue("国庆节", "国庆节" in names("2026-10-01"))
        assertEquals(Holidays.Kind.SOLAR, kindOf("2026-10-01", "国庆节"))
    }

    @Test
    fun schoolAnniversaryIsSeptember17() {
        // 兰大 1909-09-17 建校，校庆固定在这一天
        assertTrue("校庆", "校庆" in names("2026-09-17"))
        assertEquals(Holidays.Kind.SOLAR, kindOf("2026-09-17", "校庆"))
        assertTrue("9/16 不该有校庆", "校庆" !in names("2026-09-16"))
    }

    @Test
    fun mothersDayIsTheSecondSundayOfMay() {
        for (year in 2024..2030) {
            val hits = datesOf(year, "母亲节")
            assertEquals("$year 年应恰好命中一次母亲节", 1, hits.size)
            val d = hits[0]
            assertEquals("$d 应是周日", DayOfWeek.SUNDAY, d.dayOfWeek)
            assertEquals("$d 应在 5 月", 5, d.monthValue)
            assertTrue("$d 不是 5 月第 2 个周日", d.dayOfMonth in 8..14)
            assertTrue("第 1 个周日不该命中", "母亲节" !in names(d.minusDays(7).toString()))
            assertTrue("第 3 个周日不该命中", "母亲节" !in names(d.plusDays(7).toString()))
        }
    }

    @Test
    fun fathersDayIsTheThirdSundayOfJune() {
        for (year in 2024..2030) {
            val hits = datesOf(year, "父亲节")
            assertEquals("$year 年应恰好命中一次父亲节", 1, hits.size)
            val d = hits[0]
            assertEquals("$d 应是周日", DayOfWeek.SUNDAY, d.dayOfWeek)
            assertEquals("$d 应在 6 月", 6, d.monthValue)
            assertTrue("$d 不是 6 月第 3 个周日", d.dayOfMonth in 15..21)
            assertTrue("第 2 个周日不该命中", "父亲节" !in names(d.minusDays(7).toString()))
        }
    }

    // ------------------------------------------------------------------ 农历

    @Test
    fun springFestivalIsLunar() {
        assertEquals(Holidays.Kind.LUNAR, kindOf("2026-02-17", "春节"))
        assertTrue("正月初二不该命中", names("2026-02-18").isEmpty())
    }

    @Test
    fun dragonBoatAndMidAutumn2026() {
        assertTrue("端午", "端午" in names("2026-06-19"))
        assertTrue("中秋", "中秋" in names("2026-09-25"))
    }

    @Test
    fun newYearsEveTracksTheLastDayOfTheTwelfthMonth() {
        // 2025 春节是 1/29 → 1/28 是除夕，1/29 是春节而不该再算除夕
        assertEquals(Holidays.Kind.LUNAR, kindOf("2025-01-28", "除夕"))
        assertTrue("春节当天不该有除夕", "除夕" !in names("2025-01-29"))
        assertTrue("春节", "春节" in names("2025-01-29"))
    }

    @Test
    fun ordinaryDaysAreClean() {
        // 两个"什么都不是"的普通日子：3/5（正月十七）与 4/15（二月廿九）
        assertTrue("2026-03-05 不该有任何节日", Holidays.of(LocalDate.parse("2026-03-05")).isEmpty())
        assertTrue("2026-04-15 不该有任何节日", Holidays.of(LocalDate.parse("2026-04-15")).isEmpty())
    }

    // ------------------------------------------------------------------ 校历

    @Test
    fun schoolNodesFollowTheSameTermStartAsTheTimetable() {
        // 开学日用的是课表推算里的同一口径（9/1 后第一个周一、2/20 后第一个周一），
        // 否则会出现"课表显示第 1 周，祝福却说还没开学"
        val autumn = WeekCalc.firstMondayOnOrAfter(LocalDate.of(2026, 9, 1))
        val spring = WeekCalc.firstMondayOnOrAfter(LocalDate.of(2026, 2, 20))
        assertEquals(Holidays.Kind.SCHOOL, kindOf(autumn.toString(), "秋季开学"))
        assertEquals(Holidays.Kind.SCHOOL, kindOf(spring.toString(), "春季开学"))
        assertEquals(
            Holidays.Kind.SCHOOL,
            kindOf(autumn.plusWeeks(16).toString(), "期末周")
        )
    }

    @Test
    fun schoolGreetingsAdmitTheyAreNotOfficial() {
        for (year in 2026..2028) {
            for ((date, holiday) in Holidays.all(year)) {
                if (holiday.kind != Holidays.Kind.SCHOOL) continue
                assertTrue(
                    "$date 的校历祝福必须写明是参考节点：${holiday.greeting}",
                    holiday.greeting.contains("非官方校历")
                )
            }
        }
    }

    // ------------------------------------------------------------------ 全年清单

    @Test
    fun allYearIsSortedAndContainsKnownDates() {
        val all = Holidays.all(2026)
        assertTrue("全年节日数量太少，可能漏了一大片", all.size >= 25)

        var prev = LocalDate.MIN
        for ((date, _) in all) {
            assertTrue("全年清单必须按日期升序：$date 跟在 $prev 之后", !date.isBefore(prev))
            assertEquals("清单里不该出现别的年份", 2026, date.year)
            prev = date
        }

        val dates = all.map { it.first }
        assertTrue("春节", LocalDate.of(2026, 2, 17) in dates)
        assertTrue("中秋", LocalDate.of(2026, 9, 25) in dates)
        assertTrue("国庆", LocalDate.of(2026, 10, 1) in dates)
        assertTrue("校庆", LocalDate.of(2026, 9, 17) in dates)
    }

    @Test
    fun everyGreetingIsShortAndNonEmpty() {
        // 弹窗标题一行放不下 20 字以上；这里顺手当成"别写成一段话"的守门员
        for (year in 2026..2028) {
            for ((date, holiday) in Holidays.all(year)) {
                assertTrue("$date 的节日名不能为空", holiday.name.isNotBlank())
                assertTrue(
                    "$date ${holiday.name} 的祝福过长（${holiday.greeting.length} 字）：${holiday.greeting}",
                    holiday.greeting.length <= 18
                )
                assertTrue(
                    "$date ${holiday.name} 的祝福不能为空",
                    holiday.greeting.isNotBlank()
                )
            }
        }
    }
}
