package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义课程（[UserCourses]）的纯逻辑测试。
 *
 * 为什么这一层必须钉住：它是"错了也看不出来"的重灾区 ——
 *  - **节次行序**：用户填"第 5 节"，存成 5 的话课会被画到「中午1节」那一行，
 *    提醒时间也跟着早约两小时（兰大的第 5 节行序是 7，中间插着中午两行）；
 *  - **周次**：填反了或者单双周对不上，这门课一次都不会出现，
 *    用户只会以为"我加的课丢了"。
 * 这两类都是纯函数能拦下来的，所以没有理由不测。
 *
 * 注意：JSON 的读写**不在这里测** —— 单测里 org.json 是返回默认值的假实现
 * （见 app/build.gradle.kts 的 unitTests.isReturnDefaultValues），
 * 所以"删除/编辑"的规则被抽成 [UserCourses.upsert] / [UserCourses.without] 这样的纯函数，
 * JSON 只是它们的壳。
 */
class UserCoursesTest {

    /** 兰大课表页的真实行序：第 1-4 节是 1-4 行，中间两行是中午，第 5 节落在第 7 行 */
    private val lzu = listOf(
        Section(1, "第1节", "08:30", "09:15"),
        Section(2, "第2节", "09:25", "10:10"),
        Section(3, "第3节", "10:30", "11:15"),
        Section(4, "第4节", "11:25", "12:10"),
        Section(5, "中午1节", "12:30", "13:15"),
        Section(6, "中午2节", "13:25", "14:10"),
        Section(7, "第5节", "14:30", "15:15"),
        Section(8, "第6节", "15:25", "16:10"),
        Section(9, "第7节", "16:30", "17:15"),
        Section(10, "第8节", "17:25", "18:10"),
        Section(11, "第9节", "19:00", "19:45"),
        Section(12, "第10节", "19:55", "20:40"),
        Section(13, "第11节", "20:50", "21:35"),
        Section(14, "第12节", "21:45", "22:30")
    )

    private fun input(
        name: String = "体育选修",
        room: String = "",
        teacher: String = "",
        day: Int = 3,
        startSection: String = "3",
        endSection: String = "4",
        startWeek: String = "1",
        endWeek: String = "16",
        parity: Parity = Parity.NONE
    ) = UserCourses.Input(name, room, teacher, day, startSection, endSection, startWeek, endWeek, parity)

    private fun session(name: String, day: Int, start: Int, end: Int, category: String = "") =
        Session(name = name, category = category, day = day, startSection = start, endSection = end)

    // ============================================================ 节次：第几节 → 行序

    @Test
    fun sectionFiveKeepsItsRowNotItsNumber() {
        // 这个用例就是需求里点名的：第 5 节 → 行序 7（不是 5）
        assertEquals(7, UserCourses.rowOfSection(lzu, 5))
        assertEquals(8, UserCourses.rowOfSection(lzu, 6))
        // 前四节恰好重合，所以这个 bug 只会在第 5 节以后露出来 —— 越是这种越要钉住
        assertEquals(1, UserCourses.rowOfSection(lzu, 1))
        assertEquals(4, UserCourses.rowOfSection(lzu, 4))
    }

    @Test
    fun savedSessionCarriesTheRowOrder() {
        val check = UserCourses.check(input(startSection = "5", endSection = "6"), lzu)
        assertTrue("第 5-6 节应当能保存，实际 ${check.errors}", check.ok)
        val s = check.session!!
        assertEquals(7, s.startSection)
        assertEquals(8, s.endSection)
        // 存的是行序，显示时才翻译回「第5-6节」——两件事不能混
        assertEquals("第5-6节", ParseResult(sections = lzu).sectionRangeLabel(s.startSection, s.endSection))
    }

    @Test
    fun maxSectionNumberCountsRealSectionsNotTableRows() {
        // 表里有 14 行，但只到第 12 节：上界必须是 12，否则会放用户填"第 14 节"
        assertEquals(12, UserCourses.maxSectionNo(lzu))
        assertFalse(UserCourses.check(input(startSection = "13", endSection = "13"), lzu).ok)
    }

    @Test
    fun fallsBackToTheNthListedSectionWhenLabelsAreNotNumbered() {
        val weird = listOf(
            Section(1, "一", "08:00", "08:45"),
            Section(2, "二", "09:00", "09:45"),
            Section(3, "三", "10:00", "10:45")
        )
        assertEquals(3, UserCourses.maxSectionNo(weird))
        assertEquals(2, UserCourses.rowOfSection(weird, 2))
        assertEquals(2, UserCourses.sectionNoOfRow(weird, 2))
    }

    @Test
    fun editingShowsTheSectionNumberNotTheRow() {
        // 存的是行序 7；编辑表单必须显示回"第 5 节"。
        // 显示成 7 的话，用户不动节次直接保存 → 7 被当成第 7 节 → 存成行 9，
        // 这门课自己往下挪两行，而且全程没有任何提示。
        assertEquals(5, UserCourses.sectionNoOfRow(lzu, 7))
        assertEquals(6, UserCourses.sectionNoOfRow(lzu, 8))
        assertEquals(1, UserCourses.sectionNoOfRow(lzu, 1))
        assertEquals(4, UserCourses.sectionNoOfRow(lzu, 4))
    }

    @Test
    fun sectionRoundTripThroughTheFormNeverDrifts() {
        // 表单"第几节" → 存行序 → 再打开表单 → 还是那个"第几节"：来回多少次都不漂移
        for (n in 1..UserCourses.maxSectionNo(lzu)) {
            val row = UserCourses.rowOfSection(lzu, n)!!
            assertEquals("第 $n 节来回换算后不该变", n, UserCourses.sectionNoOfRow(lzu, row))
        }
    }

    @Test
    fun sectionNoOfRowIsNullWhenThatRowNoLongerExists() {
        // 课表重新导入、节次表变小：行序 99 不存在了 → 返回 null，让表单提示用户重选，
        // 而不是拿这个行序当节号糊上去
        assertNull(UserCourses.sectionNoOfRow(lzu, 99))
    }

    @Test
    fun reversedSectionInputIsSwappedNotRejected() {
        // 和「当日调课 → 加一节课」的宽容度一致：填反了就按小的在前算
        val check = UserCourses.check(input(startSection = "4", endSection = "2"), lzu)
        assertTrue(check.ok)
        assertEquals(2, check.session!!.startSection)
        assertEquals(4, check.session.endSection)
    }

    // ============================================================ 表单校验

    @Test
    fun blankNameIsRejected() {
        assertFalse(UserCourses.check(input(name = ""), lzu).ok)
        assertFalse("全是空格也不行", UserCourses.check(input(name = "   "), lzu).ok)
        assertTrue(UserCourses.check(input(name = "  重修·大学物理  "), lzu).session!!.name == "重修·大学物理")
    }

    @Test
    fun sectionOutOfRangeIsRejectedWithHumanText() {
        val zero = UserCourses.check(input(startSection = "0", endSection = "1"), lzu)
        assertFalse(zero.ok)
        val tooBig = UserCourses.check(input(startSection = "3", endSection = "13"), lzu)
        assertFalse(tooBig.ok)
        assertTrue("提示要说清楚上界，实际 ${tooBig.errors}", tooBig.errors.any { it.contains("最多到第 12 节") })
    }

    @Test
    fun nonNumericSectionIsRejected() {
        val check = UserCourses.check(input(startSection = "三", endSection = "四"), lzu)
        assertFalse(check.ok)
        assertTrue(check.errors.any { it.contains("节次要填数字") })
    }

    @Test
    fun withoutSectionTableWeRefuseInsteadOfGuessingARow() {
        // 节次表来自教务课表页面；没有它只能猜行序，而猜错的代价是课画错格 + 提醒早两小时
        val check = UserCourses.check(input(), emptyList())
        assertFalse(check.ok)
        assertTrue(check.errors.any { it.contains("节次表") })
    }

    @Test
    fun endSectionMayBeLeftEmptyAndMeansTheSameSection() {
        val check = UserCourses.check(input(startSection = "5", endSection = ""), lzu)
        assertTrue(check.ok)
        assertEquals(7, check.session!!.startSection)
        assertEquals(7, check.session.endSection)
    }

    // ============================================================ 周次校验

    @Test
    fun reversedWeeksAreRejected() {
        val check = UserCourses.check(input(startWeek = "16", endWeek = "3"), lzu)
        assertFalse(check.ok)
        assertTrue(check.errors.any { it.contains("颠倒") })
    }

    @Test
    fun weeksOutsideOneToThirtyAreRejected() {
        assertTrue(UserCourses.check(input(startWeek = "0", endWeek = "16"), lzu).errors.any { it.contains("1–30") })
        assertTrue(UserCourses.check(input(startWeek = "1", endWeek = "31"), lzu).errors.any { it.contains("1–30") })
        assertTrue(UserCourses.check(input(startWeek = "1", endWeek = "30"), lzu).ok)
    }

    @Test
    fun blankOrNonNumericWeeksAreRejected() {
        assertTrue(UserCourses.check(input(startWeek = "", endWeek = "16"), lzu).errors.any { it.contains("周次没填全") })
        assertTrue(UserCourses.check(input(startWeek = "一", endWeek = "16"), lzu).errors.any { it.contains("周次要填数字") })
    }

    @Test
    fun parityThatNeverMatchesAWeekIsRejected() {
        // 单周 + 只填第 4 周：这门课一次都不会出现，用户自己看不出来，必须当场拦住
        val check = UserCourses.check(input(startWeek = "4", endWeek = "4", parity = Parity.ODD), lzu)
        assertFalse(check.ok)
        assertTrue(check.errors.any { it.contains("单周") })

        // 双周 3-3 同理
        assertFalse(UserCourses.check(input(startWeek = "3", endWeek = "3", parity = Parity.EVEN), lzu).ok)
        // 但"单周 3-3"是合法的
        assertTrue(UserCourses.check(input(startWeek = "3", endWeek = "3", parity = Parity.ODD), lzu).ok)
    }

    @Test
    fun paritySurvivesIntoTheWeekSpan() {
        val s = UserCourses.check(input(startWeek = "3", endWeek = "9", parity = Parity.ODD), lzu).session!!
        assertEquals(WeekSpan(3, 9, Parity.ODD), s.weeks)
        assertTrue(s.weeks.contains(5))
        assertFalse(s.weeks.contains(6))
    }

    @Test
    fun checkFillsTheFieldsTheRestOfTheAppReliesOn() {
        val s = UserCourses.check(
            input(name = " 辅导班 ", room = " 天山堂 301 ", teacher = "王老师", day = 7),
            lzu
        ).session!!
        assertEquals("辅导班", s.name)
        assertEquals("天山堂 301", s.room)
        assertEquals("王老师", s.teacher)
        assertEquals(7, s.day)
        // category 是"来源标记"：合并、渲染、将来的统计都靠它区分自定义与教务
        assertEquals(UserCourses.CATEGORY, s.category)
    }

    // ============================================================ 合并（与教务课撞节）

    private val base = ParseResult(
        sections = lzu,
        sessions = listOf(
            session("大学英语", 3, 5, 6),
            session("高等数学", 3, 1, 2)
        )
    )

    @Test
    fun sameSlotConflictKeepsBothLessons() {
        // 用户加的课和教务课同一天同一节：**都保留**（与"给这天加一节课"的既有行为一致）。
        // 静默覆盖等于替用户丢数据，而他根本不知道丢了什么。
        val mine = listOf(session("体育选修", 3, 1, 2, UserCourses.CATEGORY))
        val merged = UserCourses.mergeInto(base, mine)
        assertEquals(3, merged.sessions.size)
        assertTrue(merged.sessions.any { it.name == "高等数学" })
        assertTrue(merged.sessions.any { it.name == "体育选修" })
    }

    @Test
    fun conflictSurvivesTheDisplayPath() {
        // 撞节的两段走一遍"显示路径"（按天取 + merge）之后仍然都在 ——
        // 这里最容易出的错是 category 相同导致被 WeekCalc.merge 合并成一段
        val mine = listOf(session("体育选修", 3, 1, 2, UserCourses.CATEGORY))
        val merged = UserCourses.mergeInto(base, mine)
        val shown = WeekCalc.merge(WeekCalc.sessionsFor(merged, 3, 4))
        // 周三一共三段：教务的 1-2 与 5-6，加用户自己的 1-2
        assertEquals(3, shown.size)
        assertEquals(
            "同一格上的两段都要在（谁也没被合并掉）",
            listOf("体育选修", "高等数学"),
            shown.filter { it.startSection == 1 }.map { it.name }.sorted()
        )
    }

    @Test
    fun mergeIsNoOpWhenThereIsNothingCustom() {
        assertSame("没有自定义课程时不该复制整份课表", base, UserCourses.mergeInto(base, emptyList()))
    }

    @Test
    fun mergeKeepsSystemOrderAndAppendsCustomSorted() {
        val mine = listOf(
            session("周五的课", 5, 9, 10, UserCourses.CATEGORY),
            session("周三下午", 3, 7, 8, UserCourses.CATEGORY),
            session("周三早上", 3, 1, 2, UserCourses.CATEGORY)
        )
        val merged = UserCourses.mergeInto(base, mine)
        // 教务课程的原始顺序原样不动（诊断页导出、maxWeek 都看过它，重排没有收益）
        assertEquals(listOf("大学英语", "高等数学"), merged.sessions.take(2).map { it.name })
        // 追加部分按 (星期, 起始节) 排好
        assertEquals(
            listOf(3 to 1, 3 to 7, 5 to 9),
            merged.sessions.drop(2).map { it.day to it.startSection }
        )
    }

    @Test
    fun mergedCoursesAreVisibleOnTheRightWeeks() {
        val mine = listOf(
            Session(
                name = "辅导班", category = UserCourses.CATEGORY,
                day = 3, startSection = 7, endSection = 8, weeks = WeekSpan(3, 9, Parity.ODD)
            )
        )
        val merged = UserCourses.mergeInto(base, mine)
        assertEquals(listOf("辅导班"), WeekCalc.sessionsFor(merged, 3, 5).filter { it.category == UserCourses.CATEGORY }.map { it.name })
        assertTrue("双周不该出现", WeekCalc.sessionsFor(merged, 3, 6).none { it.name == "辅导班" })
        assertTrue("第 11 周已超出周次范围", WeekCalc.sessionsFor(merged, 3, 11).none { it.name == "辅导班" })
    }

    // ============================================================ 列表的增删改（JSON 之外的部分）

    @Test
    fun upsertAddsNewAndReplacesTheSameIdInPlace() {
        val a = UserCourses.Course("id-a", session("体育选修", 3, 3, 4, UserCourses.CATEGORY))
        val b = UserCourses.Course("id-b", session("辅导班", 5, 7, 8, UserCourses.CATEGORY))

        val one = UserCourses.upsert(emptyList(), a)
        assertEquals(1, one.size)
        val two = UserCourses.upsert(one, b)
        assertEquals(2, two.size)

        // 编辑：同 id 覆盖，位置不变、条数不变（不能变成"又多一条"）
        val edited = UserCourses.upsert(two, a.copy(session = a.session.copy(room = "新教室")))
        assertEquals(2, edited.size)
        assertEquals("id-a", edited[0].id)
        assertEquals("新教室", edited[0].session.room)
    }

    @Test
    fun withoutRemovesOnlyThatIdAndIgnoresUnknownIds() {
        val a = UserCourses.Course("id-a", session("体育选修", 3, 3, 4, UserCourses.CATEGORY))
        val b = UserCourses.Course("id-b", session("辅导班", 5, 7, 8, UserCourses.CATEGORY))
        val list = listOf(a, b)

        val gone = UserCourses.without(list, "id-a")
        assertEquals(listOf("id-b"), gone.map { it.id })
        assertEquals("删不存在的 id 不该动列表", 2, UserCourses.without(list, "id-x").size)
    }

    @Test
    fun sortedForDisplayOrdersByDayThenSection() {
        val list = listOf(
            UserCourses.Course("3", session("周五", 5, 9, 10, UserCourses.CATEGORY)),
            UserCourses.Course("1", session("周三下午", 3, 7, 8, UserCourses.CATEGORY)),
            UserCourses.Course("2", session("周三早上", 3, 1, 2, UserCourses.CATEGORY))
        )
        assertEquals(listOf("2", "1", "3"), UserCourses.sortedForDisplay(list).map { it.id })
    }

    @Test
    fun newIdsAreDistinct() {
        // 两条课拿到同一个 id 的话，"编辑一条"会顺手把另一条覆盖掉
        assertTrue(UserCourses.newId() != UserCourses.newId())
    }
}
