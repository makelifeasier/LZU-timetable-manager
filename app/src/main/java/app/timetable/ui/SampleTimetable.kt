package app.timetable.ui

import app.timetable.data.ParseResult
import app.timetable.data.Section
import app.timetable.data.Session
import app.timetable.data.TermInfo
import app.timetable.data.WeekSpan

/**
 * 设置页「课表风格」预览用的**合成小样张**。
 *
 * 为什么不用用户自己的课表：空课表时预览会是一片空白，用户刚装好、还没导入的时候
 * 正好是他在设置里挑风格的时候 —— 那会是最尴尬的体验。用固定样张就永远有内容，
 * 而且 6 种风格之间的差异（圆角、色条、填充/描边）能稳定地看出来。
 *
 * 样张数据刻意做得"有代表性"：长课名、跨节次、单双周、同一天多门课 ——
 * 这几样恰好是真实课表里最容易看出风格差别的地方。
 */
internal object SampleTimetable {

    /**
     * 样张只画 **4 节**。
     *
     * 之前画 6 节、整张缩到 0.62，结果是课名小到糊成一团 —— 预览本来是为了看清"风格差异"，
     * 糊了反而更糟。少画两行就能把缩放提到 0.78（字号约 10sp，看得清），
     * 而风格差异（圆角、色条、填充/描边）在 4 行里已经全部体现得出来。
     */
    private val SECTIONS = listOf(
        Section(1, "第1节", "08:30", "09:15"),
        Section(2, "第2节", "09:25", "10:10"),
        Section(3, "第3节", "10:30", "11:15"),
        Section(4, "第4节", "11:25", "12:10")
    )

    /** 4 门课：覆盖"跨节次 / 普通 / 单双周 / 中文长课名"四种最影响观感的形态 */
    private val SAMPLES = listOf(
        Sample("高等代数（一）", "天山堂B307", "戊老师", 1, 1, 2, WeekSpan.UNKNOWN),
        Sample("数学分析（一）", "天山堂A303", "丙老师", 3, 3, 3, WeekSpan.UNKNOWN),
        Sample("计算机基础与C语言", "秦岭堂A312", "丁老师", 5, 1, 1, WeekSpan.UNKNOWN),
        Sample("高阶英语1", "天山堂A301", "甲老师", 2, 2, 3, WeekSpan(4, 18, app.timetable.data.Parity.EVEN))
    )

    private class Sample(
        val name: String,
        val room: String,
        val teacher: String,
        val day: Int,
        val start: Int,
        val end: Int,
        val weeks: WeekSpan
    )

    /** 固定学期信息，让小样张的表头也像真的 */
    private val TERM = TermInfo(year = "2026", term = "秋", studentNo = "", className = "")

    fun result(): ParseResult = ParseResult(
        term = TERM,
        sections = SECTIONS,
        sessions = SAMPLES.map {
            Session(
                name = it.name,
                room = it.room,
                teacher = it.teacher,
                weeks = it.weeks,
                day = it.day,
                startSection = it.start,
                endSection = it.end
            )
        }
    )

    /** 样张固定在第 6 周渲染 —— 那一周单双周课正好在，块数最多，风格差异最明显 */
    const val WEEK = 6
    /** 样张里的"今天"固定为周三，这样今天列高亮也看得见 */
    const val TODAY_DAY = 3
}
