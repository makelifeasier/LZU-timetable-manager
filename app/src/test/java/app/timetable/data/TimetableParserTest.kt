package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器回归测试。主样本是用户从教务系统真实保存的「学生课表」页面
 * （含浏览器注入的 <wbr> 与沉浸式翻译注入的 DOM）。
 */
class TimetableParserTest {

    private fun sample(): String =
        javaClass.classLoader!!.getResourceAsStream("timetable-2026autumn.html")!!
            .readBytes().toString(Charsets.UTF_8)

    private fun parsed(): ParseResult = TimetableParser.parse(sample())

    @Test
    fun parsesTermHeader() {
        val t = parsed().term
        assertEquals("2026", t.year)
        assertEquals("秋", t.term)
        assertEquals("999999999999", t.studentNo)
        assertEquals("2026示例班", t.className)
    }

    @Test
    fun parsesAllFourteenSectionsWithTimes() {
        val r = parsed()
        assertEquals(14, r.sections.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14), r.sections.map { it.index })

        val first = r.section(1)!!
        assertEquals("第1节", first.label)
        assertEquals("08:30", first.start)
        assertEquals("09:15", first.end)

        // 第 5、6 行是「中午1节」「中午2节」，行序 5、6
        assertEquals("中午1节", r.section(5)!!.label)
        assertEquals("12:20", r.section(5)!!.start)
        assertEquals("中午2节", r.section(6)!!.label)
        assertEquals("13:25", r.section(6)!!.start)

        // 行序 7 的 label 是「第5节」——显示必须用 label，不能用行序
        assertEquals("第5节", r.section(7)!!.label)
        assertEquals("14:30", r.section(7)!!.start)
        assertEquals("第12节", r.section(14)!!.label)
        assertEquals("22:30", r.section(14)!!.end)
    }

    @Test
    fun parsesSingleSessionFromRealCell() {
        val r = parsed()
        val s = r.sessions.first { it.day == 1 && it.startSection == 1 }
        assertEquals("高阶英语1", s.name)
        assertEquals("23", s.seqNo)
        assertEquals("天山堂A301", s.room)
        assertEquals("甲老师", s.teacher)
        assertEquals(WeekSpan(4, 18, Parity.EVEN), s.weeks)
        assertEquals("讲课学时", s.category)
    }

    @Test
    fun parsesRoomTeacherOrderCorrectly() {
        val r = parsed()
        // 周二第1节：解析几何 / 天山堂A408 / 乙老师
        val s = r.sessions.first { it.day == 2 && it.startSection == 1 }
        assertEquals("解析几何", s.name)
        assertEquals("天山堂A408", s.room)
        assertEquals("乙老师", s.teacher)
        assertEquals(WeekSpan(4, 19, Parity.NONE), s.weeks)
    }

    @Test
    fun splitsMultiCourseCell() {
        val r = parsed()
        // 行序 7（第5节）周二有两段：4-8周 秦岭堂A312 / 9-19周 秦岭堂B106
        val list = r.sessions.filter { it.day == 2 && it.startSection == 7 }
        assertEquals(2, list.size)
        val a = list.first { it.weeks.start == 4 }
        val b = list.first { it.weeks.start == 9 }
        assertEquals("计算机基础与C语言", a.name)
        assertEquals("秦岭堂A312", a.room)
        assertEquals(WeekSpan(4, 8, Parity.NONE), a.weeks)
        assertEquals("秦岭堂B106", b.room)
        assertEquals(WeekSpan(9, 19, Parity.NONE), b.weeks)
    }

    @Test
    fun parsesOddWeekParity() {
        val r = parsed()
        // 周四第3节（行序 3）：大学生心理健康（网络共享课） 6-15周单周
        val s = r.sessions.first { it.day == 4 && it.startSection == 3 }
        assertEquals("大学生心理健康（网络共享课）", s.name)
        assertEquals("天山堂B503", s.room)
        assertEquals("寅老师", s.teacher)
        assertEquals(WeekSpan(6, 15, Parity.ODD), s.weeks)
    }

    @Test
    fun ignoresEmptyCells() {
        val r = parsed()
        assertEquals(0, r.sessions.count { it.day == 1 && it.startSection == 5 })
        assertEquals(0, r.sessions.count { it.day == 7 })
    }

    @Test
    fun courseCountMatchesRealPage() {
        val r = parsed()
        // 对真实页面独立核对过的权威数字：45 段课，分布 9/10/10/4/8/4/0
        assertEquals(45, r.sessions.size)
        val perDay = (1..7).map { d -> r.sessions.count { it.day == d } }
        assertEquals(listOf(9, 10, 10, 4, 8, 4, 0), perDay)
        assertTrue(r.sessions.all { it.day in 1..7 })
        assertTrue(r.sessions.all { it.startSection in 1..14 })
        // 只有周二第5、6节（行序 7、8）是「一格两段」
        val multi = r.sessions.groupBy { it.day to it.startSection }.filter { it.value.size == 2 }
        assertEquals(setOf(2 to 7, 2 to 8), multi.keys)

        // 12 门不同的课，分段数合计 45
        val byName = r.sessions.groupBy { it.name }
        assertEquals(12, byName.size)
        assertEquals(45, byName.values.sumOf { it.size })
        assertEquals(4, byName["高阶英语1"]!!.size)
        assertEquals(6, byName["数学分析（一）"]!!.size)
        assertEquals(6, byName["计算机基础与C语言"]!!.size)
        assertEquals(3, byName["生物多样性与保护(通识)"]!!.size)
        assertEquals(4, byName["Blockly 创意趣味编程（网络共享课）"]!!.size)
    }

    @Test
    fun parsesNoArrangementTable() {
        val list = parsed().unscheduled
        assertEquals(1, list.size)
        val c = list[0]
        assertEquals("配位化学（网络共享课）", c.name)
        assertEquals("1087169", c.code)
        assertEquals("1", c.seqNo)
        assertEquals("刘伟生", c.teacher)
        assertEquals(WeekSpan(1, 18, Parity.NONE), c.weeks)
    }

    // ------------------------------------------------------------ 契约兜底

    @Test
    fun parsesWeeksVariants() {
        assertEquals(WeekSpan(4, 19, Parity.NONE), TimetableParser.parseWeeks("4-19周全周"))
        assertEquals(WeekSpan(4, 18, Parity.EVEN), TimetableParser.parseWeeks("4-18周双周"))
        assertEquals(WeekSpan(6, 15, Parity.ODD), TimetableParser.parseWeeks("6-15周单周"))
        assertEquals(WeekSpan(9, 13, Parity.NONE), TimetableParser.parseWeeks("9-13周全周"))
        assertEquals(WeekSpan(5, 5, Parity.NONE), TimetableParser.parseWeeks("5周"))
        assertEquals(WeekSpan(2, 10, Parity.ODD), TimetableParser.parseWeeks("2-10周单"))
        assertEquals(null, TimetableParser.parseWeeks("天山堂A301"))
    }

    @Test
    fun handlesRowspanByFillingCoveredSections() {
        val html = """
            <table id="timetable"><tbody>
            <tr><th>&nbsp;</th><th>周一</th><th>周二</th></tr>
            <tr class="infolist_hr_common"><th>第1节<br>08:30<br>┆<br>09:15</th>
              <td id="1-1" class="center" rowspan="2">&lt;&lt;大学物理&gt;&gt;;1<br>天山堂A101<br>张三<br>1-16周全周<br>讲课学时</td>
              <td id="2-1" class="center">&nbsp;</td></tr>
            <tr class="infolist_hr_common"><th>第2节<br>09:25<br>┆<br>10:10</th>
              <td id="2-2" class="center">&lt;&lt;英语&gt;&gt;;2<br>天山堂B202<br>李四<br>1-8周全周<br>讲课学时</td></tr>
            </tbody></table>
        """.trimIndent()
        val r = TimetableParser.parse(html)
        val physics = r.sessions.filter { it.name == "大学物理" }
        assertEquals(1, physics.size)
        assertEquals(1, physics[0].startSection)
        assertEquals(2, physics[0].endSection)
        assertTrue(r.sessions.any { it.name == "英语" && it.day == 2 && it.startSection == 2 })
    }

    @Test
    fun tolerantOfMissingTimetableTable() {
        val r = TimetableParser.parse("<html><body>登录页</body></html>")
        assertTrue(r.isEmpty)
        assertEquals(0, r.sessions.size)
    }

    @Test
    fun tolerantOfEmptyCellAndMissingMeta() {
        val html = """
            <table id="timetable"><tbody>
            <tr><th>&nbsp;</th><th>周一</th></tr>
            <tr><th>第1节<br>08:30<br>┆<br>09:15</th>
              <td id="1-1" class="center">&lt;&lt;网络课&gt;&gt;;9<br>1-18周全周<br>讲课学时</td></tr>
            </tbody></table>
        """.trimIndent()
        val r = TimetableParser.parse(html)
        assertEquals(1, r.sessions.size)
        val s = r.sessions[0]
        assertEquals("网络课", s.name)
        assertEquals("", s.room)
        assertEquals("", s.teacher)
        assertEquals(WeekSpan(1, 18, Parity.NONE), s.weeks)
    }
}
