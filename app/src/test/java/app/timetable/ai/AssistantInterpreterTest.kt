package app.timetable.ai

import app.timetable.data.DayOverrides
import app.timetable.data.ParseResult
import app.timetable.data.Parity
import app.timetable.data.Section
import app.timetable.data.Session
import app.timetable.data.UserCourses
import app.timetable.data.WeekSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 把助手的操作翻译成"要落盘的东西"。
 *
 * 这些用例钉的是**决策**（写入哪种 Override、什么时候拒绝、拒绝时说什么话），
 * 而不是重新算一遍课表 —— 所以 [FakeEnv] 直接把"当前状态"摆好，
 * 让被测代码在真实分支上跑。
 *
 * 最要紧的一条：**节次 ≠ 行序**。兰大课表里"中午1节/中午2节"各占一行，
 * 第 5 节的行序是 7；写错这一格，课就画到别的格子里、提醒也跟着错。
 */
class AssistantInterpreterTest {

    // 2026-10-05 是周一；第 1 周周一 = 2026-08-31
    private val today: LocalDate = LocalDate.of(2026, 10, 5)
    private val week1: LocalDate = LocalDate.of(2026, 8, 31)

    private val thu: LocalDate = LocalDate.of(2026, 10, 15)   // 周四，第 7 周
    private val tue: LocalDate = LocalDate.of(2026, 10, 20)   // 周二，第 8 周

    /** 故意让行序与节次错开：中午1节/中午2节各占一行，所以"第5节"在行 7 */
    private val sections = listOf(
        Section(1, "第1节", "08:00", "08:45"),
        Section(2, "第2节", "08:55", "09:40"),
        Section(3, "第3节", "10:00", "10:45"),
        Section(4, "第4节", "10:55", "11:40"),
        Section(5, "中午1节", "12:00", "12:40"),
        Section(6, "中午2节", "12:50", "13:30"),
        Section(7, "第5节", "14:00", "14:45"),
        Section(8, "第6节", "14:55", "15:40")
    )

    private fun session(name: String, day: Int, start: Int, end: Int, room: String = "") =
        Session(
            name = name, room = room, day = day,
            startSection = start, endSection = end,
            weeks = WeekSpan(1, 16), category = "教务"
        )

    private val english = session("英语", 4, 3, 4)
    private val math = session("数学", 4, 7, 8)
    private val physics = session("物理", 3, 1, 2)

    private val result = ParseResult(
        sections = sections,
        sessions = listOf(english, math, physics)
    )

    private class FakeEnv(
        private val sectionsValue: List<Section>,
        private val resultValue: ParseResult,
        private val todayValue: LocalDate,
        private val week1Value: LocalDate?,
        private val dayLists: MutableMap<LocalDate, List<Session>>,
        private val overrides: MutableMap<LocalDate, DayOverrides.Override>,
        private val mine: List<UserCourses.Course>
    ) : AssistantInterpreter.Env {
        override val today: LocalDate get() = todayValue
        override val week1Monday: LocalDate? get() = week1Value
        override val sections: List<Section> get() = sectionsValue
        override fun result(): ParseResult = resultValue
        override fun dayList(date: LocalDate): List<Session> = dayLists[date].orEmpty()
        override fun existingOverride(date: LocalDate): DayOverrides.Override? = overrides[date]
        override fun courses(): List<UserCourses.Course> = mine
    }

    private fun env(
        days: Map<LocalDate, List<Session>> = mapOf(thu to listOf(english, math)),
        overrides: Map<LocalDate, DayOverrides.Override> = emptyMap(),
        mine: List<UserCourses.Course> = emptyList(),
        sectionsOverride: List<Section>? = null,
        week1Override: LocalDate? = week1
    ): AssistantInterpreter.Env = FakeEnv(
        sectionsValue = sectionsOverride ?: sections,
        resultValue = if (sectionsOverride != null) {
            result.copy(sections = sectionsOverride)
        } else {
            result
        },
        todayValue = today,
        week1Value = week1Override,
        dayLists = LinkedHashMap(days),
        overrides = LinkedHashMap(overrides),
        mine = mine
    )

    private fun interpret(op: AssistantOp, e: AssistantInterpreter.Env = env()): AssistantInterpreter.Outcome =
        AssistantInterpreter.interpret(listOf(op), e)

    private fun interpret(ops: List<AssistantOp>, e: AssistantInterpreter.Env): AssistantInterpreter.Outcome =
        AssistantInterpreter.interpret(ops, e)

    // ------------------------------------------------------------ 换 / 停 / 恢复

    @Test
    fun replaceUsesTheTargetDatesOwnWeekByDefault() {
        // "这周四按周三的课表上"：周次缺省 = 目标日期所在周（第 7 周），否则单双周会串味
        val out = interpret(AssistantOp.Replace(thu, day = 3, week = null))
        assertTrue(out.errors.toString(), out.ok)
        assertEquals(1, out.days.size)
        val o = out.days[0].override
        assertEquals(DayOverrides.Mode.REPLACE, o?.mode)
        assertEquals(7, o?.sourceWeek)
        assertEquals(3, o?.sourceDay)
    }

    @Test
    fun replaceWithTheSameWeekdayAndWeekDoesNothing() {
        // 与手动路径一致：这就是"当天本身"，不是一次调课 —— 静默跳过而不是报错
        val out = interpret(AssistantOp.Replace(thu, day = 4, week = null))
        assertTrue(out.ok)
        assertFalse("不该产生改动", out.changed)
        assertTrue(out.days.isEmpty())
    }

    @Test
    fun replaceWithAWeekOutsideTheTermIsRefused() {
        val out = interpret(AssistantOp.Replace(thu, day = 3, week = 99))
        assertFalse(out.ok)
        assertTrue(out.errors[0].contains("本学期"))
    }

    @Test
    fun clearEmptiesTheDay() {
        val out = interpret(AssistantOp.Clear(tue))
        assertTrue(out.ok)
        assertEquals(DayOverrides.Mode.CLEAR, out.days[0].override?.mode)
        assertTrue(out.days[0].after.isEmpty())
    }

    @Test
    fun resetDropsTheOverrideAndRestoresTheBaseDay() {
        val e = env(overrides = mapOf(tue to DayOverrides.Override(DayOverrides.Mode.CLEAR)))
        val out = interpret(AssistantOp.Reset(tue), e)
        assertTrue(out.ok)
        assertEquals(null, out.days[0].override)
        // 恢复原课表 = 回到"没有任何覆盖"那一版（周二本来没课，所以是空表）
        assertTrue(out.days[0].after.isEmpty())
    }

    @Test
    fun resetWhenNothingWasOverriddenIsANoOp() {
        val out = interpret(AssistantOp.Reset(tue))
        assertTrue(out.ok)
        assertFalse(out.changed)
    }

    // ------------------------------------------------------------ 加 / 删 / 改（节次→行序）

    @Test
    fun addConvertsSectionNumberToRowIndex() {
        // S=5-6 是"第5-6节"，落到行 7-8 —— 写错这一格是历史上真踩过的坑
        val out = interpret(AssistantOp.Add(tue, "形势与政策", "天山堂A101", "", 5, 6))
        assertTrue(out.errors.toString(), out.ok)
        val o = out.days[0].override
        assertEquals(DayOverrides.Mode.ADD, o?.mode)
        assertEquals(1, o?.extra?.size)
        assertEquals(7, o?.extra?.get(0)?.startSection)
        assertEquals(8, o?.extra?.get(0)?.endSection)
        assertEquals("形势与政策", o?.extra?.get(0)?.name)
    }

    @Test
    fun addOnAReplacedOrClearedDayIsRefusedWithTheManualPathsWording() {
        // 手动路径（ui/DayEditDialog.addOne）就是这么拦的；两处说法必须一模一样
        val e = env(overrides = mapOf(tue to DayOverrides.Override(DayOverrides.Mode.CLEAR)))
        val out = interpret(AssistantOp.Add(tue, "形势与政策", "", "", 3, 4), e)
        assertFalse(out.ok)
        assertTrue(out.errors[0].contains("当天已调整为「替换/停课」，请先点『恢复原课表』再加课"))
    }

    @Test
    fun addAcceptsASecondSessionOnAnAlreadyAddedDay() {
        val first = DayOverrides.Override(
            DayOverrides.Mode.ADD,
            extra = listOf(session("形势与政策", 2, 3, 4))
        )
        val e = env(overrides = mapOf(tue to first))
        val out = interpret(AssistantOp.Add(tue, "讲座", "", "", 5, 6), e)
        assertTrue(out.errors.toString(), out.ok)
        assertEquals(2, out.days[0].override?.extra?.size)
    }

    @Test
    fun dropBySectionMaterialisesTheDayAsAList() {
        val out = interpret(AssistantOp.Drop(thu, section = 5, name = null))
        assertTrue(out.errors.toString(), out.ok)
        val o = out.days[0].override
        assertTrue("删/改一律写成整份列表（与 ui/SessionEditDialog 一致）", o?.isList == true)
        assertEquals(listOf("英语"), o?.extra?.map { it.name })
    }

    @Test
    fun dropByAmbiguousNameAsksForTheSection() {
        val e = env(days = mapOf(thu to listOf(session("英语", 4, 3, 4), session("英语听说", 4, 5, 6))))
        val out = interpret(AssistantOp.Drop(thu, section = null, name = "英语"), e)
        assertFalse(out.ok)
        assertTrue(out.errors[0].contains("匹配到 2 节"))
        assertTrue(out.errors[0].contains("S=第几节"))
    }

    @Test
    fun dropWithUnknownNameListsWhatTheDayHas() {
        val out = interpret(AssistantOp.Drop(thu, section = null, name = "体育"))
        assertFalse(out.ok)
        assertTrue(out.errors[0].contains("没找到"))
        // 报错里必须带上这天有什么课，否则用户根本不知道模型错在哪
        assertTrue(out.errors[0].contains("英语"))
        assertTrue(out.errors[0].contains("数学"))
    }

    @Test
    fun editChangesOnlyTheGivenField() {
        val out = interpret(
            AssistantOp.Edit(thu, section = 3, name = null, setName = null, setRoom = "天山堂B202", setTeacher = null, setStart = null, setEnd = null)
        )
        assertTrue(out.errors.toString(), out.ok)
        val kept = out.days[0].override?.extra?.first { it.name == "英语" }
        assertEquals("天山堂B202", kept?.room)
        assertEquals(3, kept?.startSection)
        assertEquals(4, kept?.endSection)
        // 另一节原样保留
        assertTrue(out.days[0].override?.extra?.any { it.name == "数学" } == true)
    }

    @Test
    fun editCanMoveASessionToOtherSections() {
        val out = interpret(
            AssistantOp.Edit(thu, section = 3, name = null, setName = null, setRoom = null, setTeacher = null, setStart = 7, setEnd = 8)
        )
        assertTrue(out.errors.toString(), out.ok)
        val moved = out.days[0].override?.extra?.first { it.name == "英语" }
        assertEquals(7, moved?.startSection)
        assertEquals(8, moved?.endSection)
    }

    // ------------------------------------------------------------ 一天多条：顺序有意义

    @Test
    fun opsOnTheSameDayAreReducedInOrder() {
        // "先把周四换成周三的课，再把那门课删掉" —— 第二条作用在第一条的结果上
        val e = env()
        val out = interpret(
            listOf(
                AssistantOp.Replace(thu, day = 3, week = null),
                AssistantOp.Drop(thu, section = null, name = "物理")
            ),
            e
        )
        assertTrue(out.errors.toString(), out.ok)
        assertEquals(1, out.days.size)
        val o = out.days[0].override
        assertTrue(o?.isList == true)
        assertTrue("换过来的那门课被删掉了，这天没课了", o?.extra?.isEmpty() == true)
        assertEquals(2, out.days[0].notes.size)
    }

    // ------------------------------------------------------------ 边界

    @Test
    fun pastDatesAreRefused() {
        val out = interpret(AssistantOp.Clear(LocalDate.of(2026, 10, 1)))
        assertFalse(out.ok)
        assertTrue(out.errors[0].contains("过去"))
    }

    @Test
    fun unknownWeekBasisIsRefused() {
        val out = interpret(AssistantOp.Clear(tue), env(week1Override = null))
        assertFalse(out.ok)
        assertTrue(out.errors[0].contains("第几周"))
    }

    @Test
    fun emptyTimetableIsRefusedUpFront() {
        val out = interpret(AssistantOp.Clear(tue), env(sectionsOverride = emptyList()))
        assertFalse(out.ok)
        assertTrue(out.errors[0].contains("还没有课表"))
    }

    @Test
    fun nothingIsWrittenWhenAnyOpFails() {
        // 一份计划里只要有一条不合法，就**整份不应用** —— 这是"绝不部分应用"的落点
        val out = interpret(
            listOf(AssistantOp.Clear(tue), AssistantOp.Clear(LocalDate.of(2026, 10, 1))),
            env()
        )
        assertFalse(out.ok)
        assertTrue(out.days.isEmpty())
        assertTrue(out.courses.isEmpty())
    }

    // ------------------------------------------------------------ 自定义课程

    @Test
    fun courseAddGoesThroughTheSameValidationAsTheManualEditor() {
        val out = interpret(
            AssistantOp.CourseAdd(
                name = "实验课", room = "实验楼", teacher = "",
                day = 3, start = 5, end = 6, startWeek = 1, endWeek = 16, parity = Parity.ODD
            )
        )
        assertTrue(out.errors.toString(), out.ok)
        val c = out.courses[0].add
        assertEquals("实验课", c?.session?.name)
        assertEquals("节次也要换成行序", 7, c?.session?.startSection)
        assertEquals(8, c?.session?.endSection)
    }

    @Test
    fun courseAddRefusesADuplicate() {
        val existing = UserCourses.Course(
            id = "x",
            session = session("实验课", 3, 7, 8)
        )
        val out = interpret(
            AssistantOp.CourseAdd("实验课", "", "", 3, 5, 6, 1, 16, Parity.NONE),
            env(mine = listOf(existing))
        )
        assertFalse(out.ok)
        assertTrue(out.errors[0].contains("已经有一门一样的课"))
    }

    @Test
    fun courseAddSurfacesTheValidatorsOwnWording() {
        // 单双周对不上（第 1-16 周里当然有单周，所以这里改用"起止周颠倒"来触发校验器）
        val out = interpret(
            AssistantOp.CourseAdd("实验课", "", "", 3, 5, 6, 16, 1, Parity.NONE)
        )
        assertFalse(out.ok)
        assertTrue("校验器的话要原样给用户看", out.errors[0].contains("加课没通过检查"))
    }

    @Test
    fun courseDeleteMatchesByNameAndReportsAmbiguity() {
        val a = UserCourses.Course("1", session("实验课", 3, 7, 8))
        val b = UserCourses.Course("2", session("实验课B", 5, 7, 8))
        val one = interpret(AssistantOp.CourseDelete("实验课B"), env(mine = listOf(a, b)))
        assertTrue(one.errors.toString(), one.ok)
        assertEquals("2", one.courses[0].deleteId)

        val many = interpret(AssistantOp.CourseDelete("实验课"), env(mine = listOf(a, b)))
        assertFalse(many.ok)
        assertTrue(many.errors[0].contains("匹配到 2 门"))
    }

    @Test
    fun courseDeleteWithNothingMatchingListsWhatTheUserHas() {
        val out = interpret(
            AssistantOp.CourseDelete("体育"),
            env(mine = listOf(UserCourses.Course("1", session("实验课", 3, 7, 8))))
        )
        assertFalse(out.ok)
        assertTrue(out.errors[0].contains("实验课"))
    }

    // ------------------------------------------------------------ 预览文案

    @Test
    fun previewSpeaksInDaysNotOperations() {
        val out = interpret(
            listOf(AssistantOp.Replace(thu, day = 3, week = null)),
            env(days = mapOf(thu to listOf(english, math), LocalDate.of(2026, 10, 15) to listOf(english, math)))
        )
        val lines = AssistantInterpreter.renderPreview(out, result)
        assertTrue(lines.isNotEmpty())
        assertTrue("第一行要有日期与前后节数：$lines", lines[0].contains("10月15日"))
        assertTrue(lines[0].contains("→"))
        assertTrue("要写清换成了哪一周星期几", lines.any { it.contains("周三") })
    }
}
