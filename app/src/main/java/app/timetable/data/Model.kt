package app.timetable.data

import java.time.LocalTime

/** 单双周 */
enum class Parity { NONE, ODD, EVEN }

/** 起止周 + 单双周 */
data class WeekSpan(val start: Int, val end: Int, val parity: Parity = Parity.NONE) {
    fun contains(week: Int): Boolean =
        week >= start && week <= end && when (parity) {
            Parity.NONE -> true
            Parity.ODD -> week % 2 == 1
            Parity.EVEN -> week % 2 == 0
        }

    override fun toString(): String {
        val p = when (parity) {
            Parity.NONE -> "全周"
            Parity.ODD -> "单周"
            Parity.EVEN -> "双周"
        }
        // 注意：Kotlin 中中文是合法标识符字符，"$end周" 会被解析为变量 end周，必须加花括号
        return "${start}-${end}周$p"
    }

    companion object {
        /** 页面缺周次信息时的宽默认值，避免显示成空 */
        val UNKNOWN = WeekSpan(1, 25, Parity.NONE)
    }
}

/** 节次：第 N 节的上课时间，来自课表每行行首 <th>第1节<br>08:30<br>┆<br>09:15</th> */
data class Section(val index: Int, val label: String, val start: String, val end: String) {
    val startTime: LocalTime? get() = parseHm(start)
    val endTime: LocalTime? get() = parseHm(end)

    companion object {
        fun parseHm(s: String): LocalTime? {
            val parts = s.trim().split(":")
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return LocalTime.of(h, m)
        }
    }
}

/**
 * 一段具体的课：解析的最小单位。
 * 同一个格子里的多段课（不同周次/不同教室）会拆成多条。
 */
data class Session(
    val name: String,
    val seqNo: String = "",
    val room: String = "",
    val teacher: String = "",
    val weeks: WeekSpan = WeekSpan.UNKNOWN,
    val category: String = "",
    val day: Int,              // 1=周一 … 7=周日
    val startSection: Int,     // 1..14
    val endSection: Int
) {
    /** 合并相邻节次时的同一性判据 */
    val key: String get() = "$name|$seqNo|$room|$teacher|$weeks|$category"

    /**
     * 节次的原始行序区间显示。**不能直接当界面文案** ——
     * 行序与「第N节」并不相等（第 5 节的行序是 7），
     * 界面请改用 ParseResult.sectionRangeLabel()。
     */
    val sectionSpanLabel: String get() = "$startSection-$endSection"

    companion object {
        val DAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        fun dayLabel(day: Int): String = DAY_LABELS.getOrElse(day - 1) { "周$day" }
    }
}

/** 表头信息：2026 秋 / 学号 / 班级 */
data class TermInfo(
    val year: String = "",
    val term: String = "",
    val studentNo: String = "",
    val className: String = ""
) {
    val display: String get() = listOf(year, term).filter { it.isNotEmpty() }.joinToString(" ")
}

/** 「没有具体上课时间或地点的课程」表（#noArrangement）里的一行 */
data class UnscheduledCourse(
    val code: String = "",
    val name: String = "",
    val seqNo: String = "",
    val teacher: String = "",
    val weeks: WeekSpan? = null,
    val day: String = "",
    val room: String = ""
)

/**
 * 小组件用的「一条安排」：正在上 或 接下来要上的某节具体日期的课。
 * 带真实日期，因此可以跨天排序（今天上完了就自动显示明天的）。
 */
data class Agenda(
    val session: Session,
    val date: java.time.LocalDate,
    val start: java.time.LocalTime,
    val end: java.time.LocalTime,
    val ongoing: Boolean
) {
    fun startsAt(): java.time.LocalDateTime = date.atTime(start)
    fun endsAt(): java.time.LocalDateTime = date.atTime(end)
    val sectionLabel: String get() = session.sectionSpanLabel
}

data class ParseResult(
    val term: TermInfo = TermInfo(),
    val sections: List<Section> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val unscheduled: List<UnscheduledCourse> = emptyList()
) {
    val isEmpty: Boolean get() = sessions.isEmpty() && unscheduled.isEmpty()

    fun section(index: Int): Section? = sections.firstOrNull { it.index == index }
    fun sectionStart(index: Int): String = section(index)?.start ?: ""
    fun sectionEnd(index: Int): String = section(index)?.end ?: ""

    /** 节次的显示名。必须用 label：id 序号是行序，第 5 节的行序是 7 */
    fun sectionLabel(index: Int): String = section(index)?.label ?: "第${index}节"

    /** 合并段的显示，如「第1-2节」；中午1节/中午2节这类非数字标签退回用 ~ 连接 */
    fun sectionRangeLabel(start: Int, end: Int): String {
        val a = sectionLabel(start)
        val b = sectionLabel(end)
        if (start == end) return a
        val na = if (a.startsWith("第") && a.endsWith("节")) a.substring(1, a.length - 1).toIntOrNull() else null
        val nb = if (b.startsWith("第") && b.endsWith("节")) b.substring(1, b.length - 1).toIntOrNull() else null
        return if (na != null && nb != null) "第${na}-${nb}节" else "$a~$b"
    }

    /** 学期周数上界，用于周次切换的范围 */
    val maxWeek: Int get() = sessions.maxOfOrNull { it.weeks.end } ?: 20
}
