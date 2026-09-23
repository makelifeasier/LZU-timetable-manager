package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用表格解析器（兜底解析器）测试。
 *
 * HTML 全部手写在用例里：兜底解析器的价值就在于「没见过这个学校的页面也能读懂」，
 * 所以样本要复刻各家的真实排版特征（字段顺序、行头写法、表格阈值），而不是某一个固定文件。
 *
 * 注意：表格识别阈值是「≥4 个星期列 且 ≥4 行带节次」，所以手写样本必须真有 4 行以上，
 * 否则测的是「没选中表格」而不是解析逻辑。
 */
class GenericTableParserTest {

    private fun parse(html: String) = GenericTableParser.parse(html)

    /** 凑满 4 行节次，避免样本因为不达表格阈值而被整张跳过 */
    private fun padRows(rows: Int) = (1..rows).joinToString("") { n ->
        "<tr><th>第${n + 1}节<br>0${n + 9}:00<br>0${n + 9}:45</th>" +
            "<td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>"
    }

    // ------------------------------------------------------------ 样本

    /** 「书名号 + 序号 / 教室 / 教师 / 周次」排布，行头带两个时间 */
    private val zhengfang = """
        <html><head><title>学生个人课表</title></head><body>
        <div id="title">2026 秋 学生课表: 3200101234 班级：计算机2026-1班</div>
        <table class="table_con" border="1">
          <tr>
            <th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th>
            <th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th>
          </tr>
          <tr>
            <th>第一节<br>08:00<br>┆<br>08:45</th>
            <td>&lt;&lt;高等数学&gt;&gt;;1<br>教三楼A101<br>张三<br>1-16周<br>讲课学时</td>
            <td></td><td>&nbsp;</td><td></td><td></td><td></td><td></td>
          </tr>
          <tr>
            <th>第二节<br>08:50<br>┆<br>09:35</th>
            <td></td><td>&lt;&lt;数据结构&gt;&gt;;2<br>教四楼B203<br>李四<br>2-16周双周<br>实验学时</td>
            <td></td><td></td><td></td><td></td><td></td>
          </tr>
          <tr>
            <th>第三节<br>10:00<br>┆<br>10:45</th>
            <td></td><td></td><td></td><td></td><td></td><td></td><td></td>
          </tr>
          <tr>
            <th>第四节<br>10:55<br>┆<br>11:40</th>
            <td></td><td></td><td></td><td></td><td></td><td></td><td></td>
          </tr>
        </table>
        </body></html>
    """.trimIndent()

    /** 紧凑行头（`0102节`）+ 单元格「一字段一行」的排布 */
    private val labelledLines = """
        <html><head><title>2026-2027学年第一学期课表</title></head><body>
        <table id="tblCourse">
          <tr><td>节次</td><td>周一</td><td>周二</td><td>周三</td><td>周四</td><td>周五</td><td>周六</td><td>周日</td></tr>
          <tr>
            <td>0102节<br>08:00-09:35</td>
            <td>高等数学<br>教师：张三<br>地点：A101<br>1-16周</td>
            <td></td><td></td><td></td><td></td><td></td><td></td>
          </tr>
          <tr>
            <td>0304节<br>09:50-11:25</td>
            <td></td><td></td><td></td>
            <td>大学物理<br>教师：王五<br>地点：逸夫楼B203<br>1-8周</td>
            <td></td><td></td><td></td>
          </tr>
          <tr>
            <td>0506节<br>13:30-15:05</td>
            <td></td><td></td><td></td><td></td><td></td><td></td><td></td>
          </tr>
          <tr>
            <td>0708节<br>15:20-16:55</td>
            <td></td><td></td><td></td><td></td><td></td><td></td><td></td>
          </tr>
        </table>
        </body></html>
    """.trimIndent()

    // ------------------------------------------------------------ 用例

    @Test
    fun courseNameTeacherWeeksRoomLayoutParsesAllFields() {
        val r = parse(zhengfang)
        val s = r.sessions.single { it.name == "高等数学" }
        assertEquals(1, s.day)
        assertEquals(1, s.startSection)
        assertEquals(1, s.endSection)
        assertEquals("1", s.seqNo)
        assertEquals("教三楼A101", s.room)
        assertEquals("张三", s.teacher)
        assertEquals(WeekSpan(1, 16, Parity.NONE), s.weeks)
        assertEquals("讲课学时", s.category)
    }

    @Test
    fun sectionTimesComeFromRowHeader() {
        val r = parse(zhengfang)
        assertEquals(listOf(1, 2, 3, 4), r.sections.map { it.index })
        assertEquals("第一节", r.section(1)!!.label)
        assertEquals("08:00", r.section(1)!!.start)
        assertEquals("08:45", r.section(1)!!.end)
        assertEquals("08:50", r.section(2)!!.start)
        assertEquals("09:35", r.section(2)!!.end)
    }

    @Test
    fun labelledFieldPerLineParses() {
        val r = parse(labelledLines)
        val s = r.sessions.single { it.name == "高等数学" }
        assertEquals(1, s.day)
        assertEquals(1, s.startSection)
        assertEquals(2, s.endSection)
        assertEquals("A101", s.room)
        assertEquals("张三", s.teacher)
        assertEquals(WeekSpan(1, 16, Parity.NONE), s.weeks)
    }

    @Test
    fun compactSectionHeader0102MeansSectionOneToTwo() {
        val r = parse(labelledLines)
        assertEquals("0102节", r.section(1)!!.label)
        assertEquals("08:00", r.section(1)!!.start)
        assertEquals("09:35", r.section(1)!!.end)
        // 第二行的 0304 必须落到第 3 节，而不是第 3 行
        assertEquals("09:50", r.section(3)!!.start)
        assertEquals("11:25", r.section(3)!!.end)
        val physics = r.sessions.single { it.name == "大学物理" }
        assertEquals(4, physics.day)
        assertEquals(3, physics.startSection)
        assertEquals(4, physics.endSection)
    }

    @Test
    fun adjacentSectionRowsWithSameCourseAreMerged() {
        // 有的学校一节一格、同一门课连写两行：不合并的话一节大课会显示成两条
        val r = parse(labelledLines)
        val s = r.sessions.single { it.name == "高等数学" }
        assertEquals(1, s.startSection)
        assertEquals(2, s.endSection)
        assertEquals(1, r.sessions.count { it.name == "高等数学" })
    }

    @Test
    fun dayHeaderVariantsZhou1AndXingqi2AreRecognized() {
        val html = """
            <table>
              <tr><th>&nbsp;</th><th>周1</th><th>星期2</th><th>礼拜三</th><th>周四</th>
                  <th>星期五</th><th>周6</th><th>周日</th></tr>
              <tr><th>第1节<br>08:00</th>
                  <td>&lt;&lt;语文&gt;&gt;;1<br>A101<br>张三<br>1-16周</td>
                  <td>&lt;&lt;语文&gt;&gt;;2<br>A102<br>李四<br>1-16周</td>
                  <td>&lt;&lt;语文&gt;&gt;;3<br>A103<br>王五<br>1-16周</td>
                  <td>&lt;&lt;语文&gt;&gt;;4<br>A104<br>赵六<br>1-16周</td>
                  <td>&lt;&lt;语文&gt;&gt;;5<br>A105<br>孙七<br>1-16周</td>
                  <td>&lt;&lt;语文&gt;&gt;;6<br>A106<br>周八<br>1-16周</td>
                  <td>&lt;&lt;语文&gt;&gt;;7<br>A107<br>吴九<br>1-16周</td>
              </tr>
              ${padRows(3)}
            </table>
        """.trimIndent()
        val r = parse(html)
        assertEquals((1..7).toList(), r.sessions.map { it.day }.sorted())
        assertEquals(7, r.sessions.size)
        assertEquals("语文", r.sessions.first().name)
    }

    @Test
    fun dayHeaderFullWidthXingqiYiIsRecognized() {
        val html = """
            <table>
              <tr><th>&nbsp;</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th>
                  <th>星期五</th><th>星期六</th><th>星期日</th></tr>
              <tr><th>1</th><td>&lt;&lt;英语&gt;&gt;;9<br>外语楼C201<br>郑十<br>1-16周</td>
                  <td></td><td></td><td></td><td></td><td></td><td></td></tr>
              ${padRows(3)}
            </table>
        """.trimIndent()
        val r = parse(html)
        val s = r.sessions.single()
        assertEquals("英语", s.name)
        assertEquals(1, s.day)
        assertEquals("外语楼C201", s.room)
        assertEquals("郑十", s.teacher)
    }

    @Test
    fun englishDayHeaderIsRecognized() {
        val html = """
            <table>
              <tr><th>Time</th><th>Mon</th><th>Tue</th><th>Wed</th><th>Thu</th><th>Fri</th><th>Sat</th><th>Sun</th></tr>
              <tr><th>1</th><td></td><td></td><td></td><td></td><td></td><td></td>
                  <td>Sports<br>Gym A<br>Smith<br>1-16周</td></tr>
              ${padRows(3)}
            </table>
        """.trimIndent()
        val r = parse(html)
        assertEquals(7, r.sessions.single().day)
    }

    @Test
    fun parityOddAndEvenFromWeekText() {
        val odd = parse(zhengfang.replace("2-16周双周", "1-16周单周"))
        assertEquals(WeekSpan(1, 15, Parity.ODD), odd.sessions.single { it.name == "数据结构" }.weeks)
        val even = parse(zhengfang)
        assertEquals(WeekSpan(2, 16, Parity.EVEN), even.sessions.single { it.name == "数据结构" }.weeks)
    }

    @Test
    fun weekTextVariantsAllParse() {
        // 兜底解析器的周次解析必须比兰大那份更宽：这几家写法都不一样
        assertEquals(WeekSpan(1, 16, Parity.NONE), GenericTableParser.parseWeeks("1-16周"))
        assertEquals(WeekSpan(1, 16, Parity.NONE), GenericTableParser.parseWeeks("1~16周"))
        assertEquals(WeekSpan(1, 16, Parity.NONE), GenericTableParser.parseWeeks("第1-16周"))
        assertEquals(WeekSpan(1, 16, Parity.NONE), GenericTableParser.parseWeeks("1-16(周)"))
        assertEquals(WeekSpan(1, 16, Parity.NONE), GenericTableParser.parseWeeks("1,3,5-16周"))
        assertEquals(WeekSpan(1, 15, Parity.ODD), GenericTableParser.parseWeeks("1-16周单周"))
        assertEquals(WeekSpan(4, 18, Parity.EVEN), GenericTableParser.parseWeeks("4-18周双周"))
        assertEquals(WeekSpan(1, 25, Parity.NONE), GenericTableParser.parseWeeks("全周"))
        assertEquals(null, GenericTableParser.parseWeeks("张三"))
        // 行里还挂着别的字段时，只能取周次那一段，不能把「学时」一起吞掉
        assertEquals(WeekSpan(2, 16, Parity.EVEN), GenericTableParser.parseWeeks("2-16周双周 实验学时"))
    }

    @Test
    fun chineseNumeralSectionHeadersAreRecognized() {
        // 「第一节」这种中文节次在一些学校是唯一的行头写法，光认阿拉伯数字会整表作废
        val html = """
            <table>
              <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
              <tr><th>第一节<br>08:00<br>08:45</th>
                  <td>高等数学<br>教三楼A101<br>张三<br>1-16周</td>
                  <td></td><td></td><td></td><td></td><td></td><td></td></tr>
              <tr><th>第二节<br>08:55<br>09:40</th><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>
              <tr><th>第三节<br>10:00<br>10:45</th><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>
              <tr><th>第十节<br>20:00<br>20:45</th><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>
            </table>
        """.trimIndent()
        val r = parse(html)
        assertEquals(listOf(1, 2, 3, 10), r.sections.map { it.index })
        assertEquals("第一节", r.section(1)!!.label)
        assertEquals("第十节", r.section(10)!!.label)
        assertEquals("20:00", r.section(10)!!.start)
        val s = r.sessions.single()
        assertEquals(1, s.startSection)
        assertEquals(1, s.endSection)
    }

    @Test
    fun rowspanCourseSpansBothSections() {
        val html = """
            <table>
              <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
              <tr><th>第1节<br>08:00</th>
                  <td rowspan="2">&lt;&lt;体育&gt;&gt;;3<br>东区运动场<br>钱七<br>1-16周</td>
                  <td></td><td></td><td></td><td></td><td></td><td></td></tr>
              <tr><th>第2节<br>08:50</th><td></td><td></td><td></td><td></td><td></td><td></td></tr>
              ${padRows(2)}
            </table>
        """.trimIndent()
        val r = parse(html)
        val s = r.sessions.single()
        assertEquals("体育", s.name)
        assertEquals(1, s.day)
        assertEquals(1, s.startSection)
        assertEquals(2, s.endSection)
        assertEquals("东区运动场", s.room)
        assertEquals("钱七", s.teacher)
    }

    @Test
    fun twoCoursesInOneCellSplitIntoTwoSessions() {
        val html = """
            <table>
              <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
              <tr><th>第1节<br>08:00</th>
                  <td>&lt;&lt;高等数学&gt;&gt;;1<br>教三楼A101<br>张三<br>1-8周<br>&lt;&lt;大学英语&gt;&gt;;2<br>外语楼C201<br>李四<br>9-16周</td>
                  <td></td><td></td><td></td><td></td><td></td><td></td></tr>
              ${padRows(3)}
            </table>
        """.trimIndent()
        val r = parse(html)
        assertEquals(2, r.sessions.size)
        val math = r.sessions.first { it.name == "高等数学" }
        val english = r.sessions.first { it.name == "大学英语" }
        assertEquals("教三楼A101", math.room)
        assertEquals("张三", math.teacher)
        assertEquals("1", math.seqNo)
        assertEquals(WeekSpan(1, 8, Parity.NONE), math.weeks)
        assertEquals("外语楼C201", english.room)
        assertEquals("2", english.seqNo)
        assertEquals(WeekSpan(9, 16, Parity.NONE), english.weeks)
    }

    @Test
    fun weekListWithCommasAndRanges() {
        val html = zhengfang.replace("1-16周", "1,3,5-9周")
        val s = parse(html).sessions.single { it.name == "高等数学" }
        assertEquals(WeekSpan(1, 9, Parity.NONE), s.weeks)
    }

    @Test
    fun roomAndTeacherAreNotSwapped() {
        // 「教室在前」和「教师在前」两种顺序都要对；关键在于用内容判类型，不用行号
        val lzuOrder = parse(zhengfang).sessions.single { it.name == "高等数学" }
        assertEquals("教三楼A101", lzuOrder.room)
        assertEquals("张三", lzuOrder.teacher)

        val zfOrderHtml = """
            <table>
              <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
              <tr><th>第一节<br>08:00</th>
                  <td>高等数学<br>张三<br>1-16周<br>教三楼A101</td>
                  <td></td><td></td><td></td><td></td><td></td><td></td></tr>
              ${padRows(3)}
            </table>
        """.trimIndent()
        val zfOrder = parse(zfOrderHtml).sessions.single()
        assertEquals("教三楼A101", zfOrder.room)
        assertEquals("张三", zfOrder.teacher)
        assertTrue("A101 不能被当成教师", zfOrder.teacher != "A101")
        assertTrue("张三 不能被当成教室", zfOrder.room != "张三")
    }

    @Test
    fun emptyHtmlYieldsEmptyResult() {
        val r = parse("")
        assertTrue(r.isEmpty)
        assertEquals(emptyList<Session>(), r.sessions)
        assertEquals(emptyList<Section>(), r.sections)
        assertEquals(emptyList<UnscheduledCourse>(), r.unscheduled)
    }

    @Test
    fun loginPageWithoutTimetableYieldsEmptyResult() {
        val login = """
            <html><head><title>统一身份认证</title></head><body>
              <form action="/login" method="post">
                <table><tr><td>用户名</td><td><input name="user"></td></tr>
                       <tr><td>密码</td><td><input name="pass"></td></tr></table>
                <input type="submit" value="登录">
              </form>
            </body></html>
        """.trimIndent()
        val r = parse(login)
        assertTrue(r.isEmpty)
        assertEquals(0, r.sessions.size)
    }

    @Test
    fun malformedHtmlDoesNotThrow() {
        val broken = "<table><tr><th>周一<th>周二<th>周三<th>周四<th>周五"
        val r = parse(broken)          // 只要求不抛异常
        assertNotNull(r)
    }

    @Test
    fun picksTimetableTableAndIgnoresOtherTables() {
        // 页面里还有 logo 表、班级信息表，必须挑中真正的那张
        val html = """
            <html><body>
              <table><tr><td>logo</td></tr></table>
              <table><tr><td>班级：计算机2026-1班</td></tr></table>
              <table>
                <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
                <tr><th>第1节<br>08:00</th><td>&lt;&lt;离散数学&gt;&gt;;5<br>教二楼A202<br>孙七<br>1-16周</td>
                    <td></td><td></td><td></td><td></td><td></td><td></td></tr>
                ${padRows(3)}
              </table>
            </body></html>
        """.trimIndent()
        val r = parse(html)
        assertEquals("离散数学", r.sessions.single().name)
        assertEquals("计算机2026-1班", r.term.className)
    }

    // ------------------------------------------------- 真实样本 / 接线路径

    @Test
    fun lzuPageStillParsesReasonably() {
        // 兜底解析器不能比兰大专用解析器差：同一份真实页面走这里也要能出东西
        val html = javaClass.classLoader!!.getResourceAsStream("timetable-2026autumn.html")!!
            .readBytes().toString(Charsets.UTF_8)
        val r = parse(html)
        assertTrue("至少要认出 14 个节次", r.sections.size >= 14)
        assertEquals("08:30", r.section(1)!!.start)
        assertEquals("09:15", r.section(1)!!.end)

        val s = r.sessions.firstOrNull { it.name == "高阶英语1" }
        assertNotNull("必须能抽到兰大第一格那门课", s)
        assertEquals(1, s!!.day)
        assertEquals("23", s.seqNo)
        assertEquals("天山堂A301", s.room)
        assertEquals("甲老师", s.teacher)
        assertEquals(WeekSpan(4, 18, Parity.EVEN), s.weeks)
    }

    @Test
    fun lzuSessionsAllHaveValidDayAndSection() {
        val html = javaClass.classLoader!!.getResourceAsStream("timetable-2026autumn.html")!!
            .readBytes().toString(Charsets.UTF_8)
        val r = parse(html)
        assertTrue(r.sessions.size >= 10)
        assertTrue(r.sessions.all { it.day in 1..7 })
        assertTrue(r.sessions.all { it.startSection >= 1 && it.endSection >= it.startSection })
        assertTrue(r.sessions.none { it.name.isBlank() })
        // 兰大页面里周日一列全是空，不能凭空造出周日的课
        assertEquals(0, r.sessions.count { it.day == 7 })
    }
}