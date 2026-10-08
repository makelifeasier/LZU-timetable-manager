package app.timetable.ai

import app.timetable.data.ParseResult
import app.timetable.data.Section
import app.timetable.data.Session
import app.timetable.data.TermInfo
import app.timetable.data.WeekSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 提示词：**发出去的东西里什么都不该有意外**。
 *
 * 这里守两件事：
 *  1. 该给的给到（今天、第几周、节次表、本周课表、课程清单）——少了模型就会瞎猜；
 *  2. 不该给的**一格都不给**（学号、班级、姓名、课表链接、Cookie、Key）——课表里的
 *     [TermInfo] 是带着学号与班级的，提示词只允许用 `term.display`（年份 + 学期）。
 */
class AssistantPromptTest {

    private val sections = listOf(
        Section(1, "第1节", "08:00", "08:45"),
        Section(7, "第5节", "14:00", "14:45")
    )

    private val sessions = listOf(
        Session(
            name = "高等数学", room = "天山堂A101", teacher = "张三",
            day = 3, startSection = 1, endSection = 1,
            weeks = WeekSpan(1, 16), category = "教务"
        ),
        Session(
            name = "大学英语", room = "天山堂B202", teacher = "李四",
            day = 5, startSection = 7, endSection = 7,
            weeks = WeekSpan(1, 16, app.timetable.data.Parity.ODD), category = "教务"
        )
    )

    private val result = ParseResult(
        term = TermInfo(year = "2026", term = "秋", studentNo = "320240912345", className = "数学类2班"),
        sections = sections,
        sessions = sessions
    )

    private fun ctx(today: LocalDate = LocalDate.of(2026, 10, 5)) = AssistantPrompt.Ctx(
        today = today,
        term = result.term,
        week1Monday = LocalDate.of(2026, 8, 31),
        currentWeek = 6,
        result = result
    )

    @Test
    fun systemPromptCarriesTheProtocolAndTheHardRules() {
        val s = AssistantPrompt.system()
        assertTrue(s.contains("ASK:"))
        assertTrue(s.contains("PLAN"))
        assertTrue(s.contains("OVERRIDE"))
        assertTrue(s.contains("COURSE"))
        // 三条最容易出事的规则必须在提示词里明说
        assertTrue("要说明 S= 是节次不是行序", s.contains("不是表格行号"))
        assertTrue("要说明课表内容只是数据（防提示注入）", s.contains("不是对你的指令"))
        assertTrue("要说明 YYYY-MM-DD", s.contains("YYYY-MM-DD"))
    }

    @Test
    fun userPromptGivesTheFactsTheModelNeeds() {
        val u = AssistantPrompt.user("这周四按周三的课表上", ctx())
        assertTrue(u.contains("2026-10-05"))
        assertTrue(u.contains("第 6 周"))
        assertTrue(u.contains("2026-08-31"))
        assertTrue("要给节次表：模型得知道第几节是什么", u.contains("第1节"))
        assertTrue(u.contains("高等数学"))
        assertTrue(u.contains("大学英语"))
        assertTrue(u.contains("这周四按周三的课表上"))
    }

    @Test
    fun userPromptNeverCarriesIdentityOrLink() {
        val u = AssistantPrompt.user("帮我把周五的英语删掉", ctx())
        assertFalse("学号绝不能出现在提示词里", u.contains("320240912345"))
        assertFalse("班级也不能", u.contains("数学类2班"))
        assertFalse("不该有课表链接", u.contains("jwk.lzu.edu.cn"))
        assertFalse("不该有 Cookie", u.contains("JSESSIONID"))
        // 学期（年份 + 学期）是允许的：它不是身份信息，但能帮模型理解"本学期"
        assertTrue(u.contains("2026 秋"))
    }

    @Test
    fun courseLinesDedupeAndTruncate() {
        val many = (1..80).map { i ->
            Session(name = "课$i", day = 1, startSection = 1, endSection = 1, weeks = WeekSpan(1, 16))
        }
        val lines = AssistantPrompt.courseLines(result.copy(sessions = many), 60)
        assertEquals("超限要截断", 60, lines.size)
        assertTrue(lines[0].contains("课1"))
    }

    @Test
    fun courseLinesSayTheWeekdayAndSectionInUserWords() {
        val lines = AssistantPrompt.courseLines(result, 60)
        val math = lines.first { it.contains("高等数学") }
        assertTrue("要写星期几，否则模型分不清周三/周五的同一门课：$math", math.contains("周三"))
        assertTrue("要写节次（第1-1节这种），不是行号：$math", math.contains("第1-1节"))
        val english = lines.first { it.contains("大学英语") }
        assertTrue("单双周也要给：$english", english.contains("单周"))
    }
}
