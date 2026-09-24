package app.timetable.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** 周次推算与今日课程。纯函数，无 Android 依赖。 */
object WeekCalc {

    /** 该日期所在周的周一 */
    fun mondayOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())

    /** 由「现在是第 N 周」反推第 1 周周一 */
    fun week1Monday(today: LocalDate, currentWeek: Int): LocalDate =
        mondayOf(today).minusDays(((currentWeek - 1).coerceAtLeast(0) * 7).toLong())

    /** 该日期之后（含当天）的第一个周一 */
    fun firstMondayOnOrAfter(date: LocalDate): LocalDate =
        date.plusDays(((8 - date.dayOfWeek.value) % 7).toLong())

    /**
     * 猜「第 1 周周一」。
     *
     * 背景：教务系统课表页里**没有当前周次**，所以首次导入时必须自己定一个基准。
     * 以前是直接假设「本周=第 1 周」—— 第 8 周才装上 App 的同学会看到完全错误的课表，
     * 而且错得很隐蔽（显示得像模像样）。改为按学期 + 开学时间估算：
     *
     *   秋季学期：**9 月 1 日所在那一周的周一**（2026 年 = 8 月 31 日，即 8.31–9.6 是第 1 周）
     *   春季学期：2 月 20 日**之后的第一个周一**（保守规则，见下）
     *
     * 注意"**所在那一周的周一**"和"那之后的第一个周一"**不是一回事**：
     * 9 月 1 日如果是周二，前者给出 8 月 31 日（正确，兰大秋季学期就是这么开学的，用户实测），
     * 后者会给到 9 月 7 日 —— 整整差一周，整个学期的周次全部偏移。
     *
     * **春季为什么仍然用"之后第一个周"**：秋季的 8.31 是有实测依据的，春季没有。
     * 在拿到同样确凿的依据之前，宁可沿用原来的保守规则 —— 万一春季真是 2 月下旬那个周一开学，
     * 贸然改成"所在周"就会让所有春季学期的用户提前一周，那种错比现在更糟。
     *
     * 猜得不准也只是差一两周（设置里可精确校正），远好过必然错的「第 1 周」。
     *
     * @return null 表示无法判断（学期不明，或今天还在开学日之前）
     */
    fun guessWeek1Monday(termYear: String, season: String, today: LocalDate): LocalDate? {
        val year = termYear.toIntOrNull() ?: today.year
        return when {
            season.contains("秋") -> {
                val start = mondayOf(LocalDate.of(year, 9, 1))
                start.takeIf { !today.isBefore(it) }
            }

            season.contains("春") -> {
                val start = firstMondayOnOrAfter(LocalDate.of(year, 2, 20))
                start.takeIf { !today.isBefore(it) }
            }

            else -> null
        }
    }

    /** 由已解析出的课表推断「第 1 周周一」；推断不出来时退回「本周=第 1 周」 */
    fun initialWeek1Monday(result: ParseResult, today: LocalDate): LocalDate =
        result.term.let { guessWeek1Monday(it.year, it.term, today) }
            ?: week1Monday(today, 1)

    /** 第 1 周周一 → 给定日期属于第几周（可为 <=0，调用方决定是否夹紧） */
    fun weekOf(date: LocalDate, week1Monday: LocalDate): Int =
        Math.floorDiv(ChronoUnit.DAYS.between(week1Monday, date), 7L).toInt() + 1

    fun dayOf(date: LocalDate): Int = date.dayOfWeek.value  // 1=周一 … 7=周日

    fun sessionsFor(result: ParseResult, day: Int, week: Int): List<Session> =
        result.sessions
            .filter { it.day == day && it.weeks.contains(week) }
            .sortedWith(compareBy({ it.startSection }, { it.endSection }, { it.name }))

    /** 合并同一门课相邻节次（周二第5节+第6节 → 第5-6节） */
    fun merge(sessions: List<Session>): List<Session> {
        val sorted = sessions.sortedWith(compareBy({ it.startSection }, { it.endSection }))
        val out = ArrayList<Session>(sorted.size)
        for (s in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.key == s.key && s.startSection <= last.endSection + 1) {
                out[out.size - 1] = last.copy(endSection = maxOf(last.endSection, s.endSection))
            } else {
                out += s
            }
        }
        return out
    }

    fun todaySessions(result: ParseResult, date: LocalDate, week: Int): List<Session> =
        merge(sessionsFor(result, dayOf(date), week))

    /** 下一节课（当天剩余中开课时间最晚于 now 的第一节） */
    fun nextSession(result: ParseResult, date: LocalDate, week: Int, now: LocalTime): Session? =
        todaySessions(result, date, week).firstOrNull { s ->
            val start = result.section(s.startSection)?.startTime ?: return@firstOrNull false
            start.isAfter(now)
        }

    /** 正在上的课 */
    fun currentSession(result: ParseResult, date: LocalDate, week: Int, now: LocalTime): Session? =
        todaySessions(result, date, week).firstOrNull { s ->
            val start = result.section(s.startSection)?.startTime ?: return@firstOrNull false
            val end = result.section(s.endSection)?.endTime ?: start
            !now.isBefore(start) && !now.isAfter(end)
        }

    /** 某周某天的全部课按节次铺开（供导出图片用） */
    fun weekGrid(result: ParseResult, week: Int): Map<Int, List<Session>> =
        (1..7).associateWith { day -> merge(sessionsFor(result, day, week)) }

    // ------------------------------------------------- 正在上 / 接下来

    /**
     * 正在上 + 接下来要上的课，**跨天跨周**按时间排序。
     *
     * 小组件用它取代「罗列今天全部课」—— 下午三点看「今天第1节 高等数学」没有任何意义。
     * 今天上完了就会自动顺延到明天的课。
     */
    fun agenda(
        result: ParseResult,
        week1Monday: LocalDate,
        now: LocalDateTime,
        lookaheadDays: Int = 8,
        limit: Int = 6,
        /**
         * 注入「某天到底有哪些课」。默认走按周次算的原始课表；
         * 小组件传 TimetableRepository.sessionsOn，这样「当日调课」的结果也能显示出来。
         */
        sessionsOn: ((LocalDate) -> List<Session>)? = null
    ): List<Agenda> {
        if (result.sessions.isEmpty() || limit <= 0) return emptyList()
        val out = ArrayList<Agenda>(limit)
        val today = now.toLocalDate()
        for (offset in 0..lookaheadDays) {
            val date = today.plusDays(offset.toLong())
            val week = weekOf(date, week1Monday)
            if (week < 1) continue
            val ofDay = sessionsOn?.invoke(date) ?: todaySessions(result, date, week)
            for (s in ofDay) {
                val start = result.section(s.startSection)?.startTime ?: continue
                val end = result.section(s.endSection)?.endTime ?: start
                if (!date.atTime(end).isAfter(now)) continue      // 已结束
                val ongoing = offset == 0 &&
                    !now.toLocalTime().isBefore(start) &&
                    !now.toLocalTime().isAfter(end)
                out += Agenda(s, date, start, end, ongoing)
                if (out.size >= limit) return out
            }
        }
        return out
    }

    /** 「正在上 · 还剩 25 分」这类相对时间提示 */
    fun relativeLabel(item: Agenda, now: LocalDateTime): String {
        if (item.ongoing) {
            val left = java.time.Duration.between(now, item.endsAt()).toMinutes()
            return if (left <= 1) "即将下课" else "还剩 $left 分"
        }
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), item.date)
        return when (days) {
            0L -> {
                val mins = java.time.Duration.between(now, item.startsAt()).toMinutes()
                when {
                    mins <= 1 -> "马上开始"
                    mins < 60 -> "还有 $mins 分"
                    else -> "还有 ${mins / 60} 小时"      // 「还有 192 分」不像人话
                }
            }
            1L -> "明天"
            2L -> "后天"
            else -> Session.dayLabel(item.date.dayOfWeek.value)
        }
    }
}
