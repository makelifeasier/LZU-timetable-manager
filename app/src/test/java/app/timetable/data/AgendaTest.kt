package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 小组件「正在上 / 接下来」的排序逻辑。
 * 用一份可控的小课表（周一 08:00 数学 + 09:00 物理；周二 08:00 英语），
 * 比用真实大样本更容易构造边界场景。
 */
class AgendaTest {

    /** 2026-09-07 是周一 */
    private val week1 = LocalDate.of(2026, 9, 7)
    private val monday = LocalDate.of(2026, 9, 7)
    private val tuesday = LocalDate.of(2026, 9, 8)

    private val result: ParseResult = TimetableParser.parse(
        """
        <table id="timetable"><tbody>
        <tr><th>&nbsp;</th><th>周一</th><th>周二</th></tr>
        <tr><th>第1节<br>08:00<br>┆<br>08:45</th>
          <td id="1-1" class="center">&lt;&lt;数学&gt;&gt;;1<br>A101<br>张三<br>1-16周全周<br>讲课学时</td>
          <td id="2-1" class="center">&lt;&lt;英语&gt;&gt;;1<br>B202<br>李四<br>1-16周全周<br>讲课学时</td></tr>
        <tr><th>第2节<br>09:00<br>┆<br>09:45</th>
          <td id="1-2" class="center">&lt;&lt;物理&gt;&gt;;1<br>C303<br>王五<br>1-16周全周<br>讲课学时</td>
          <td id="2-2" class="center">&nbsp;</td></tr>
        </tbody></table>
        """.trimIndent()
    )

    private fun agenda(now: String, limit: Int = 6, days: Int = 8): List<Agenda> =
        WeekCalc.agenda(result, week1, LocalDateTime.parse(now), lookaheadDays = days, limit = limit)

    @Test
    fun sampleIsParsedAsExpected() {
        assertEquals(2, result.sections.size)
        assertEquals("08:00", result.sectionStart(1))
        assertEquals("09:45", result.sectionEnd(2))
        assertEquals(3, result.sessions.size)   // 周一两节 + 周二一节
    }

    @Test
    fun firstEntryIsTheOngoingClass() {
        // days=0 只看今天，避免把下周一循环回来的课也算进来
        val list = agenda("2026-09-07T08:10:00", days = 0)
        assertEquals(2, list.size)
        assertTrue(list[0].ongoing)
        assertEquals("数学", list[0].session.name)
        assertEquals(monday, list[0].date)
        assertFalse(list[1].ongoing)
        assertEquals("物理", list[1].session.name)
    }

    @Test
    fun lookaheadRollsIntoNextWeekAndRespectsLimit() {
        // 8 天窗口会包含下周一循环回来的课，但受 limit 约束
        val list = agenda("2026-09-07T08:10:00", limit = 6, days = 8)
        assertEquals(6, list.size)
        assertEquals("数学", list[0].session.name)
        assertEquals("物理", list[1].session.name)
        assertEquals("英语", list[2].session.name)
        assertEquals(LocalDate.of(2026, 9, 14), list[3].date)   // 下周一
    }

    @Test
    fun betweenClassesShowsWhatIsNext() {
        // 08:45 下课、09:00 上课之间
        val list = agenda("2026-09-07T08:50:00")
        assertEquals("物理", list[0].session.name)
        assertFalse(list[0].ongoing)
    }

    @Test
    fun rollsOverToNextDayWhenTodayIsOver() {
        val list = agenda("2026-09-07T10:30:00")
        assertEquals("英语", list[0].session.name)
        assertEquals(tuesday, list[0].date)
    }

    @Test
    fun rollsOverToNextWeekWhenTheWeekIsOver() {
        // 周二也上完了 → 下一次是下周一（在 8 天窗口内）
        val list = agenda("2026-09-08T10:30:00")
        assertEquals("数学", list[0].session.name)
        assertEquals(LocalDate.of(2026, 9, 14), list[0].date)
    }

    @Test
    fun skipsAlreadyFinishedClasses() {
        // 08:45 整：数学已结束，不应出现（只看今天，否则下周一的数学会混进来）
        val names = agenda("2026-09-07T08:45:00", days = 0).map { it.session.name }
        assertEquals(listOf("物理"), names)
    }

    @Test
    fun honoursLimit() {
        assertEquals(1, agenda("2026-09-07T07:00:00", limit = 1).size)
        assertEquals(2, agenda("2026-09-07T07:00:00", limit = 2).size)
    }

    @Test
    fun emptyWhenNoDataOrNoWeek() {
        assertTrue(WeekCalc.agenda(ParseResult(), week1, LocalDateTime.parse("2026-09-07T08:00:00")).isEmpty())
        assertTrue(agenda("2026-09-07T08:00:00", limit = 0).isEmpty())
    }

    @Test
    fun ongoingLabelCountsDownToEnd() {
        val list = agenda("2026-09-07T08:10:00")
        assertEquals("还剩 35 分", WeekCalc.relativeLabel(list[0], LocalDateTime.parse("2026-09-07T08:10:00")))
    }

    @Test
    fun upcomingLabelsAreHumanReadable() {
        // 08:50 时数学已下课，[0] 才是「即将开始」的物理
        assertEquals(
            "还有 10 分",
            WeekCalc.relativeLabel(
                agenda("2026-09-07T08:50:00", days = 0)[0],
                LocalDateTime.parse("2026-09-07T08:50:00")
            )
        )
        assertEquals(
            "明天",
            WeekCalc.relativeLabel(agenda("2026-09-07T10:30:00")[0], LocalDateTime.parse("2026-09-07T10:30:00"))
        )
        // 下周一：距今 6 天，显示星期几
        val nextWeek = agenda("2026-09-08T10:30:00")[0]
        assertEquals("周一", WeekCalc.relativeLabel(nextWeek, LocalDateTime.parse("2026-09-08T10:30:00")))
    }

    @Test
    fun afterBreakFastLabelSaysStartingSoon() {
        val list = agenda("2026-09-07T07:59:30")
        assertEquals("马上开始", WeekCalc.relativeLabel(list[0], LocalDateTime.parse("2026-09-07T07:59:30")))
    }

    @Test
    fun farAwaySameDaySwitchesToHours() {
        // 从 08:00 看今天 09:00 的课：1 小时，不该显示「还有 60 分」
        val item = agenda("2026-09-07T08:00:00", days = 0)[1]
        assertEquals("物理", item.session.name)
        assertEquals("还有 1 小时", WeekCalc.relativeLabel(item, LocalDateTime.parse("2026-09-07T08:00:00")))
    }
}
