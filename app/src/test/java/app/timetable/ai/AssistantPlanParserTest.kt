package app.timetable.ai

import app.timetable.data.Parity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 助手协议（行式小语言）的解析。
 *
 * 这是**唯一一处"外部文本直接决定 App 行为"的入口**，所以用例刻意偏执：围栏、寒暄、
 * 中文引号、别名键、缺字段、越界、超限、同日期乱序…… 只要有一格放宽，用户的课表就可能
 * 被一句它没看懂的话改掉。
 */
class AssistantPlanParserTest {

    private fun ok(raw: String): AssistantReply {
        val r = AssistantPlanParser.parse(raw)
        assertTrue("本该解析成功，却失败：${(r as? AssistantPlanParser.Result.Bad)?.reason}", r is AssistantPlanParser.Result.Ok)
        return (r as AssistantPlanParser.Result.Ok).reply
    }

    private fun bad(raw: String): String {
        val r = AssistantPlanParser.parse(raw)
        assertTrue("本该解析失败，却通过了", r is AssistantPlanParser.Result.Bad)
        return (r as AssistantPlanParser.Result.Bad).reason
    }

    private fun ops(raw: String): List<AssistantOp> =
        (ok(raw) as AssistantReply.PlanOps).ops

    // ------------------------------------------------------------ 基本形态

    @Test
    fun readsAsk() {
        val reply = ok("ASK: 你说的是哪一天？")
        assertTrue(reply is AssistantReply.Ask)
        assertEquals("你说的是哪一天？", (reply as AssistantReply.Ask).question)
    }

    @Test
    fun toleratesChatterAndFences() {
        // 模型很爱先客套一句、再把计划包在代码围栏里 —— 这些是"字体习惯"，不是语义
        val raw = """
            好的，我理解你的意思，以下是计划：
            ```
            PLAN
            OVERRIDE 2026-10-15 CLEAR
            NOTE 把这天停掉
            ```
        """.trimIndent()
        val list = ops(raw)
        assertEquals(1, list.size)
        assertEquals(AssistantOp.Clear(LocalDate.of(2026, 10, 15)), list[0])
        assertEquals("把这天停掉", (ok(raw) as AssistantReply.PlanOps).note)
    }

    @Test
    fun planWithNoOpsIsValidAndMeansNothingToDo() {
        val list = ops("PLAN")
        assertTrue(list.isEmpty())
    }

    @Test
    fun missingMarkerIsReportedNotGuessed() {
        val reason = bad("我建议你把这天的课取消。")
        assertTrue(reason.contains("ASK"))
        assertTrue(reason.contains("PLAN"))
    }

    @Test
    fun anEmptyAskIsRefused() {
        assertTrue(bad("ASK:").contains("空"))
    }

    // ------------------------------------------------------------ 日期与星期几

    @Test
    fun acceptsSeveralDateSpellingsButNormalisesToOne() {
        val expected = LocalDate.of(2026, 10, 15)
        for (text in listOf("2026-10-15", "2026/10/15", "2026.10.15", "2026年10月15日")) {
            assertEquals(
                "日期写法 $text 应当都认",
                AssistantOp.Clear(expected),
                ops("PLAN\nOVERRIDE $text CLEAR")[0]
            )
        }
    }

    @Test
    fun refusesImpossibleDate() {
        assertTrue(bad("PLAN\nOVERRIDE 2026-02-30 CLEAR").contains("日期"))
        assertTrue(bad("PLAN\nOVERRIDE 明天 CLEAR").contains("日期"))
    }

    @Test
    fun acceptsWeekdayNamesAndNumbers() {
        val expected = AssistantOp.Replace(LocalDate.of(2026, 10, 15), 3, null)
        for (day in listOf("3", "周三", "星期三", "礼拜三", "周3")) {
            assertEquals(
                "DAY=$day 应当都认",
                expected,
                ops("PLAN\nOVERRIDE 2026-10-15 REPLACE DAY=$day")[0]
            )
        }
    }

    @Test
    fun refusesBadWeekdayAndWeek() {
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-15 REPLACE DAY=8").contains("DAY"))
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-15 REPLACE WEEK=abc").contains("WEEK"))
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-15 REPLACE WEEK=99").contains("WEEK"))
    }

    // ------------------------------------------------------------ 六类当天操作

    @Test
    fun parsesReplaceClearReset() {
        val list = ops(
            """
            PLAN
            OVERRIDE 2026-10-15 REPLACE DAY=3 WEEK=8
            OVERRIDE 2026-10-20 CLEAR
            OVERRIDE 2026-10-22 RESET
            """.trimIndent()
        )
        assertEquals(AssistantOp.Replace(LocalDate.of(2026, 10, 15), 3, 8), list[0])
        assertEquals(AssistantOp.Clear(LocalDate.of(2026, 10, 20)), list[1])
        assertEquals(AssistantOp.Reset(LocalDate.of(2026, 10, 22)), list[2])
    }

    @Test
    fun parsesAddWithQuotedName() {
        val list = ops("""PLAN
OVERRIDE 2026-10-20 ADD name="大学 英语" room=天山堂A101 teacher=张三 S=3-4""")
        assertEquals(
            AssistantOp.Add(
                date = LocalDate.of(2026, 10, 20),
                name = "大学 英语",
                room = "天山堂A101",
                teacher = "张三",
                start = 3, end = 4
            ),
            list[0]
        )
    }

    @Test
    fun addNeedsNameAndSection() {
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-20 ADD room=A101 S=3").contains("name"))
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-20 ADD name=形势与政策").contains("S="))
    }

    @Test
    fun dropTakesEitherSectionOrNameButExactlyOne() {
        assertEquals(
            AssistantOp.Drop(LocalDate.of(2026, 10, 20), 5, null),
            ops("PLAN\nOVERRIDE 2026-10-20 DROP S=5")[0]
        )
        assertEquals(
            AssistantOp.Drop(LocalDate.of(2026, 10, 20), null, "英语"),
            ops("PLAN\nOVERRIDE 2026-10-20 DROP name=英语")[0]
        )
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-20 DROP").contains("S="))
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-20 DROP S=5 name=英语").contains("只能给一个"))
    }

    @Test
    fun editNeedsATargetAndANewValue() {
        val list = ops("PLAN\nOVERRIDE 2026-10-20 EDIT name=英语 setRoom=天山堂B202 setS=5-6")
        assertEquals(
            AssistantOp.Edit(
                date = LocalDate.of(2026, 10, 20),
                section = null, name = "英语",
                setName = null, setRoom = "天山堂B202", setTeacher = null,
                setStart = 5, setEnd = 6
            ),
            list[0]
        )
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-20 EDIT name=英语").contains("set"))
    }

    @Test
    fun parsesCourseAddAndDelete() {
        val list = ops(
            """
            PLAN
            COURSE ADD name=实验课 room=实验楼 day=3 S=5-6 W=1-16 PARITY=ODD
            COURSE DELETE name=实验课
            """.trimIndent()
        )
        assertEquals(
            AssistantOp.CourseAdd(
                name = "实验课", room = "实验楼", teacher = "",
                day = 3, start = 5, end = 6, startWeek = 1, endWeek = 16, parity = Parity.ODD
            ),
            list[0]
        )
        assertEquals(AssistantOp.CourseDelete("实验课"), list[1])
    }

    @Test
    fun courseAddRequiresDaySectionAndWeeks() {
        assertTrue(bad("PLAN\nCOURSE ADD name=x S=1 W=1-16").contains("DAY"))
        assertTrue(bad("PLAN\nCOURSE ADD name=x day=3 W=1-16").contains("S="))
        assertTrue(bad("PLAN\nCOURSE ADD name=x day=3 S=1").contains("W="))
    }

    // ------------------------------------------------------------ 严格性

    @Test
    fun unknownOpUnknownActionUnknownKeyAreAllRefused() {
        assertTrue(bad("PLAN\nMOVE 2026-10-15 TO 2026-10-16").contains("未知操作"))
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-15 SWAP DAY=3").contains("未知的当天动作"))
        // 自己发明键（START/END/FROM/TO）必须拒绝，而不是被当成噪声忽略
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-15 ADD name=x S=3 START=1").contains("不认"))
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-15 REPLACE FROM=3").contains("不认"))
    }

    @Test
    fun reportsTheOffendingLineNumber() {
        val reason = bad("PLAN\nOVERRIDE 2026-10-15 CLEAR\nBLAH 1")
        assertTrue("要指出第几行，否则用户没法反馈：$reason", reason.contains("第 3 行"))
    }

    @Test
    fun refusesMoreThanTheOperationCap() {
        val many = (1..31).joinToString("\n") { "OVERRIDE 2026-10-${(it % 28) + 1} CLEAR" }
        assertTrue(bad("PLAN\n$many").contains("太多"))
        // 正好 30 条是允许的
        val thirty = (1..30).joinToString("\n") { "OVERRIDE 2026-10-${it} CLEAR" }
        assertEquals(30, ops("PLAN\n$thirty").size)
    }

    @Test
    fun refusesOutOfRangeSections() {
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-15 ADD name=x S=0").contains("S"))
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-15 ADD name=x S=15").contains("S"))
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-15 ADD name=x S=7-3").contains("S"))
    }

    @Test
    fun duplicateKeysAndBadPairsAreRefused() {
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-15 REPLACE DAY=3 DAY=4").contains("两次"))
        assertTrue(bad("PLAN\nOVERRIDE 2026-10-15 REPLACE 3").contains("键=值"))
    }

    @Test
    fun crlfAndBlankLinesAreFine() {
        val list = ops("PLAN\r\n\r\nOVERRIDE 2026-10-15 CLEAR\r\n")
        assertEquals(1, list.size)
    }

    @Test
    fun chineseKeyAliasesWork() {
        // 模型偶尔会把键名写成中文；认它，但归一成同一个键
        val list = ops("PLAN\nOVERRIDE 2026-10-20 ADD 课名=形势与政策 节次=3-4")
        assertEquals("形势与政策", (list[0] as AssistantOp.Add).name)
        assertEquals(3, (list[0] as AssistantOp.Add).start)
    }
}
