package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 当日调课（[DayOverrides]）的纯逻辑测试。
 *
 * 为什么单独测这一层：它改的是"我会看到什么课"，而错的方式往往很隐蔽 ——
 * 比如清空之后又被加课覆盖、或者排序错了导致两节课叠在同一格。
 * 这些都不需要 Android 环境就能验证，所以没有理由不测。
 */
class DayOverridesTest {

    private val monday = LocalDate.of(2026, 9, 21)

    private fun session(name: String, day: Int, start: Int, end: Int) =
        Session(name = name, day = day, startSection = start, endSection = end)

    private val base = listOf(
        session("高等数学", 3, 1, 2),
        session("大学英语", 3, 5, 6)
    )

    @Test
    fun noOverrideReturnsBase() {
        assertEquals(base, DayOverrides.apply(base, null, day = 3))
    }

    /**
     * REPLACE 在纯逻辑这一层是**原样返回**（真正的"换成哪一周"只有仓库做得到，
     * 它才拿得到周次基准）。
     *
     * 这个断言是防回归的：这里曾经返回空表，一旦有调用方复用这段纯逻辑，
     * 用户的"替换"就会被静默变成"全天停课"。
     */
    @Test
    fun replaceIsNotHandledHereAndMustNotLookLikeClear() {
        val out = DayOverrides.apply(
            base,
            DayOverrides.Override(DayOverrides.Mode.REPLACE, sourceWeek = 8),
            day = 3
        )
        assertEquals(base, out)
    }

    @Test
    fun clearRemovesEverything() {        val out = DayOverrides.apply(
            base,
            DayOverrides.Override(DayOverrides.Mode.CLEAR),
            day = 3
        )
        assertTrue("清空当天应当是空的，实际 ${out.size} 段", out.isEmpty())
    }

    @Test
    fun addKeepsBaseAndAppendsNew() {
        val extra = session("形势与政策", 0, 3, 4)   // day 故意填 0，验证会被归一化
        val out = DayOverrides.apply(
            base,
            DayOverrides.Override(DayOverrides.Mode.ADD, extra = listOf(extra)),
            day = 3
        )
        assertEquals(3, out.size)
        // 排序必须按起始节：1,3,5 —— 否则两节课会叠在同一格
        assertEquals(listOf(1, 3, 5), out.map { it.startSection })
        assertEquals("加进来的课必须被改成当天", listOf(3, 3, 3), out.map { it.day })
    }

    @Test
    fun addSameLessonTwiceIsDeduped() {
        val extra = session("形势与政策", 3, 3, 4)
        val once = DayOverrides.mergeSorted(base, listOf(extra), 3)
        val twice = DayOverrides.mergeSorted(base, listOf(extra, extra), 3)
        assertEquals("同一节课加两次应当只留一节", once.size, twice.size)
    }

    @Test
    fun addDoesNotSwallowRealLessonAtSameSlot() {
        // 加课和原有课同节次但课名不同 —— 两节都要在（真实场景：补课和原课撞了）
        val extra = session("补课：物理实验", 3, 1, 2)
        val out = DayOverrides.mergeSorted(base, listOf(extra), 3)
        assertEquals(3, out.size)
        assertTrue(out.any { it.name == "补课：物理实验" })
        assertTrue(out.any { it.name == "高等数学" })
    }

    @Test
    fun pruneKeepsRecentPastAndAllFuture() {
        val today = LocalDate.of(2026, 9, 24)
        val o = DayOverrides.Override(DayOverrides.Mode.CLEAR)
        val map = mapOf(
            today.minusDays(10) to o,   // 太旧，丢
            today.minusDays(2) to o,    // 刚过去，留（可能还在看这一周的课表）
            today to o,
            today.plusDays(30) to o     // 未来的调课绝对不能丢
        )
        val kept = DayOverrides.aliveKeys(map, today)
        assertEquals(setOf(today.minusDays(2), today, today.plusDays(30)), kept.keys)
    }

    // ------------------------------------------------------- 与小组件的衔接

    /**
     * `WeekCalc.agenda` 的 `sessionsOn` 注入点：小组件必须显示"调课之后"的课。
     *
     * 这是批 D 的关键接口 —— 如果哪天有人重新在这里直接用原始课表，
     * 就会出现"App 里清空了、小组件还在念原来的课"（最难解释的一类不一致）。
     */
    @Test
    fun agendaUsesInjectedSessionsWhenProvided() {
        val result = ParseResult(
            sections = listOf(
                Section(1, "第1节", "08:30", "09:15"),
                Section(2, "第2节", "09:25", "10:10")
            ),
            sessions = listOf(session("高等数学", 3, 1, 2))
        )
        val now = LocalDateTime.of(2026, 9, 23, 7, 0)   // 周三早上
        val week = WeekCalc.weekOf(now.toLocalDate(), monday)

        val plain = WeekCalc.agenda(result, monday, now, lookaheadDays = 0, limit = 5)
        assertEquals("没有覆盖时应当看到那节课", 1, plain.size)

        val cleared = WeekCalc.agenda(
            result, monday, now, lookaheadDays = 0, limit = 5,
            sessionsOn = { emptyList() }               // 模拟"清空当天"
        )
        assertTrue("注入空列表后应当没有课，实际 ${cleared.size} 段", cleared.isEmpty())

        val added = WeekCalc.agenda(
            result, monday, now, lookaheadDays = 0, limit = 5,
            sessionsOn = { listOf(session("晚上补课", 3, 2, 2)) }
        )
        assertEquals(1, added.size)
        assertEquals("晚上补课", added.first().session.name)
        assertEquals("开始时间要取自注入的那节课所在的节次",
            LocalTime.of(9, 25), added.first().start)
        assertEquals(week, WeekCalc.weekOf(now.toLocalDate(), monday))
    }
}
