package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 开学日默认值（第 1 周周一）。
 *
 * 这是用户实测反馈后改正的：兰大秋季学期 **8.31–9.6 就是第 1 周**，
 * 也就是说第 1 周周一是 **9 月 1 日所在那一周的周一**，而不是"9 月 1 日之后的第一个周一"。
 * 后者会在 9 月 1 日不是周一时整整错开一周，整个学期的周次全部偏移 —— 而且看起来很正常，
 * 极难发现。所以这里把几年的真实值都钉住。
 */
class WeekCalcTermStartTest {

    private fun week1(year: Int, season: String, today: LocalDate): LocalDate? =
        WeekCalc.guessWeek1Monday(year.toString(), season, today)

    @Test
    fun autumnStartsOnTheMondayOfTheWeekContainingSep1() {
        // 2026-09-01 是周二 → 第 1 周周一 = 2026-08-31（8.31–9.6 是第 1 周，用户实测）
        assertEquals(
            LocalDate.of(2026, 8, 31),
            week1(2026, "秋", LocalDate.of(2026, 9, 24))
        )
        // 2025-09-01 是周一 → 就是它自己
        assertEquals(LocalDate.of(2025, 9, 1), week1(2025, "秋", LocalDate.of(2025, 10, 1)))
        // 2024-09-01 是周日 → 所在那一周的周一是 2024-08-26
        assertEquals(LocalDate.of(2024, 8, 26), week1(2024, "秋", LocalDate.of(2024, 10, 1)))
    }

    @Test
    fun springKeepsTheConservativeRule() {
        // 春季没有实测依据，沿用"2/20 之后的第一个周一"：
        // 2026-02-20 是周五 → 2/23。这条测试是**刻意钉住"不擅自改春季"**的。
        assertEquals(LocalDate.of(2026, 2, 23), week1(2026, "春", LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun returnsNullBeforeTheTermStarts() {
        // 还没开学就不要瞎猜（新装的同学这时候看到的应该是"暂无课表"而不是错的周次）
        assertNull(week1(2026, "秋", LocalDate.of(2026, 8, 1)))
        assertNull(week1(2026, "春", LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun unknownSeasonReturnsNull() {
        assertNull(week1(2026, "暑期", LocalDate.of(2026, 8, 1)))
    }

    @Test
    fun resultIsAlwaysAMondayAndNotAfterToday() {
        for (year in 2024..2030) {
            for (season in listOf("秋", "春")) {
                val today = LocalDate.of(year, if (season == "秋") 10 else 4, 15)
                val d = week1(year, season, today) ?: continue
                assertEquals("$year$season 的第 1 周周必须是周一", DayOfWeek.MONDAY, d.dayOfWeek)
                assertEquals("$year$season 不能晚于今天", false, d.isAfter(today))
            }
        }
    }
}
