import Foundation

//  周次推算与「正在上 / 接下来」。与 Android 版 `WeekCalc.kt` 对应。

enum WeekCalc {

    // MARK: - 基础

    /// 该日期所在周的周一
    static func mondayOf(_ date: Date) -> Date {
        let cal = calendar
        let weekday = cal.component(.weekday, from: date)   // 1=周日 … 7=周六
        let isoDay = (weekday + 5) % 7 + 1                  // 1=周一 … 7=周日
        return cal.date(byAdding: .day, value: -(isoDay - 1), to: cal.startOfDay(for: date))!
    }

    /// 由「现在是第 N 周」反推第 1 周周一
    static func week1Monday(today: Date, currentWeek: Int) -> Date {
        let weeks = max(0, currentWeek - 1)
        return calendar.date(byAdding: .day, value: -weeks * 7, to: mondayOf(today))!
    }

    /// 第 1 周周一 → 给定日期属于第几周（可为 <=0，调用方决定是否夹紧）
    static func weekOf(_ date: Date, week1Monday: Date) -> Int {
        let days = calendar.dateComponents([.day], from: mondayOf(week1Monday), to: calendar.startOfDay(for: date)).day ?? 0
        return Int(floor(Double(days) / 7.0)) + 1
    }

    /// 1=周一 … 7=周日
    static func dayOf(_ date: Date) -> Int {
        (calendar.component(.weekday, from: date) + 5) % 7 + 1
    }

    /// 该日期之后（含当天）的第一个周一
    static func firstMondayOnOrAfter(_ date: Date) -> Date {
        let d = calendar.startOfDay(for: date)
        let isoDay = (calendar.component(.weekday, from: d) + 5) % 7 + 1
        return calendar.date(byAdding: .day, value: (8 - isoDay) % 7, to: d)!
    }

    /// 猜「第 1 周周一」（教务系统页面里没有当前周次）
    /// 秋季学期从 9 月第一个周一算起，春季学期从 2 月 20 日后第一个周一算起。
    static func guessWeek1Monday(termYear: String, season: String, today: Date) -> Date? {
        let year = Int(termYear) ?? calendar.component(.year, from: today)
        func date(_ month: Int, _ day: Int) -> Date {
            calendar.date(from: DateComponents(year: year, month: month, day: day)) ?? today
        }
        if season.contains("秋") {
            let start = firstMondayOnOrAfter(date(9, 1))
            return today >= calendar.startOfDay(for: start) ? start : nil
        }
        if season.contains("春") {
            let start = firstMondayOnOrAfter(date(2, 20))
            return today >= calendar.startOfDay(for: start) ? start : nil
        }
        return nil
    }

    /// 首次导入时用：猜不出来就退回「本周=第 1 周」
    static func initialWeek1Monday(result: ParseResult, today: Date) -> Date {
        guessWeek1Monday(termYear: result.term.year, season: result.term.term, today: today)
            ?? week1Monday(today: today, currentWeek: 1)
    }

    // MARK: - 课程筛选

    static func sessionsFor(_ result: ParseResult, day: Int, week: Int) -> [Session] {
        result.sessions
            .filter { $0.day == day && $0.weeks.contains(week) }
            .sorted { ($0.startSection, $0.endSection, $0.name) < ($1.startSection, $1.endSection, $1.name) }
    }

    /// 合并同一门课相邻节次（周二第5节+第6节 → 第5-6节）
    static func merge(_ sessions: [Session]) -> [Session] {
        var out: [Session] = []
        for s in sessions.sorted(by: { ($0.startSection, $0.endSection) < ($1.startSection, $1.endSection) }) {
            if var last = out.last, last.key == s.key, s.startSection <= last.endSection + 1 {
                last.endSection = max(last.endSection, s.endSection)
                out[out.count - 1] = last
            } else {
                out.append(s)
            }
        }
        return out
    }

    static func todaySessions(_ result: ParseResult, date: Date, week: Int) -> [Session] {
        merge(sessionsFor(result, day: dayOf(date), week: week))
    }

    /// 某周某天的全部课（供课表网格用）
    static func weekGrid(_ result: ParseResult, week: Int) -> [Int: [Session]] {
        var grid: [Int: [Session]] = [:]
        for day in 1...7 { grid[day] = merge(sessionsFor(result, day: day, week: week)) }
        return grid
    }

    static func currentSession(_ result: ParseResult, date: Date, week: Int, now: TimeOfDay) -> Session? {
        todaySessions(result, date: date, week: week).first { s in
            guard let start = result.section(s.startSection)?.startTime else { return false }
            let end = result.section(s.endSection)?.endTime ?? start
            return now >= start && now <= end
        }
    }

    // MARK: - 正在上 / 接下来

    /// 正在上 + 接下来要上的课，**跨天**按时间排序（小组件用）
    static func agenda(
        _ result: ParseResult,
        week1Monday: Date,
        now: Date,
        lookaheadDays: Int = 8,
        limit: Int = 6
    ) -> [Agenda] {
        guard !result.sessions.isEmpty, limit > 0 else { return [] }
        var out: [Agenda] = []
        let nowTime = TimeOfDay(hour: calendar.component(.hour, from: now),
                                minute: calendar.component(.minute, from: now))

        for offset in 0...lookaheadDays {
            let date = calendar.date(byAdding: .day, value: offset, to: calendar.startOfDay(for: now))!
            let week = weekOf(date, week1Monday: week1Monday)
            if week < 1 { continue }
            for s in todaySessions(result, date: date, week: week) {
                guard let start = result.section(s.startSection)?.startTime else { continue }
                let end = result.section(s.endSection)?.endTime ?? start
                if offset == 0 && end < nowTime { continue }          // 今天已结束的跳过
                let ongoing = offset == 0 && nowTime >= start && nowTime <= end
                out.append(Agenda(session: s, date: date, start: start, end: end, ongoing: ongoing))
                if out.count >= limit { return out }
            }
        }
        return out
    }

    /// 「正在上 · 还剩 25 分」这类相对提示
    static func relativeLabel(_ item: Agenda, now: Date) -> String {
        let nowTime = TimeOfDay(hour: calendar.component(.hour, from: now),
                                minute: calendar.component(.minute, from: now))
        if item.ongoing {
            let left = item.end.minutesFromMidnight - nowTime.minutesFromMidnight
            return left <= 1 ? "即将下课" : "还剩 \(left) 分"
        }
        let days = calendar.dateComponents(
            [.day],
            from: calendar.startOfDay(for: now),
            to: calendar.startOfDay(for: item.date)
        ).day ?? 0

        switch days {
        case 0:
            let mins = item.start.minutesFromMidnight - nowTime.minutesFromMidnight
            if mins <= 1 { return "马上开始" }
            if mins < 60 { return "还有 \(mins) 分" }
            return "还有 \(mins / 60) 小时"      // 「还有 192 分」不像人话
        case 1:
            return "明天"
        default:
            return Session.dayLabel(dayOf(item.date))
        }
    }

    // MARK: - 工具

    static var calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.firstWeekday = 2      // 周一
        c.locale = Locale(identifier: "zh_CN")
        return c
    }()
}
