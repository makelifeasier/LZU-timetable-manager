import Foundation

//  数据模型 —— 与 Android 版 `Model.kt` 逐个字段对应。
//  字段名/语义刻意保持一致，方便两边对照排查。

/// 单双周
enum Parity: String, Codable {
    case none, odd, even

    /// 兼容 Android 侧写进 JSON 的枚举名（NONE/ODD/EVEN）
    init(kotlinName: String) {
        switch kotlinName.uppercased() {
        case "ODD": self = .odd
        case "EVEN": self = .even
        default: self = .none
        }
    }

    var label: String {
        switch self {
        case .none: return "全周"
        case .odd: return "单周"
        case .even: return "双周"
        }
    }
}

/// 起止周 + 单双周
struct WeekSpan: Codable, Equatable {
    var start: Int
    var end: Int
    var parity: Parity

    init(_ start: Int, _ end: Int, _ parity: Parity = .none) {
        self.start = start
        self.end = end
        self.parity = parity
    }

    /// 页面缺周次信息时的宽默认值，避免显示成空
    static let unknown = WeekSpan(1, 25, .none)

    func contains(_ week: Int) -> Bool {
        guard week >= start, week <= end else { return false }
        switch parity {
        case .none: return true
        case .odd: return week % 2 == 1
        case .even: return week % 2 == 0
        }
    }

    /// 「4-19周全周」
    var display: String { "\(start)-\(end)周\(parity.label)" }
}

/// 节次：第 N 节的上课时间（来自每行行首 <th>第1节<br>08:30<br>┆<br>09:15</th>）
struct Section: Codable, Equatable {
    var index: Int
    var label: String
    var start: String
    var end: String

    var startTime: TimeOfDay? { TimeOfDay(sectionString: start) }
    var endTime: TimeOfDay? { TimeOfDay(sectionString: end) }
}

/// 一天中的时刻（时:分）。用自带的 Date 太啰嗦，这里只要能在同一天内比大小。
struct TimeOfDay: Codable, Equatable, Comparable {
    var hour: Int
    var minute: Int

    init?(sectionString s: String) {
        let parts = s.trimmingCharacters(in: .whitespaces).split(separator: ":")
        guard parts.count == 2,
              let h = Int(parts[0]), let m = Int(parts[1]),
              (0...23).contains(h), (0...59).contains(m) else { return nil }
        hour = h
        minute = m
    }

    init(hour: Int, minute: Int) {
        self.hour = hour
        self.minute = minute
    }

    var minutesFromMidnight: Int { hour * 60 + minute }
    static func < (a: TimeOfDay, b: TimeOfDay) -> Bool { a.minutesFromMidnight < b.minutesFromMidnight }
    var display: String { String(format: "%02d:%02d", hour, minute) }
}

/// 一段具体的课：解析的最小单位。
/// 同一个格子里的多段课（不同周次/不同教室）会拆成多条。
struct Session: Codable, Equatable {
    var name: String
    var seqNo: String = ""
    var room: String = ""
    var teacher: String = ""
    var weeks: WeekSpan = .unknown
    var category: String = ""
    /// 1=周一 … 7=周日
    var day: Int
    /// 1..14（**行序**，不等于「第N节」，显示请用 ParseResult.sectionRangeLabel）
    var startSection: Int
    var endSection: Int

    /// 合并相邻节次时的同一性判据
    var key: String { "\(name)|\(seqNo)|\(room)|\(teacher)|\(weeks.display)|\(category)" }

    /// 行序区间（不是界面文案）
    var sectionSpanLabel: String { "\(startSection)-\(endSection)" }

    static let dayLabels = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
    static func dayLabel(_ day: Int) -> String {
        (day >= 1 && day <= 7) ? dayLabels[day - 1] : "周\(day)"
    }
}

/// 表头信息：2026 秋 / 学号 / 班级
struct TermInfo: Codable, Equatable {
    var year: String = ""
    var term: String = ""
    var studentNo: String = ""
    var className: String = ""

    var display: String { [year, term].filter { !$0.isEmpty }.joined(separator: " ") }
}

/// 「没有具体上课时间或地点的课程」表（#noArrangement）里的一行
struct UnscheduledCourse: Codable, Equatable {
    var code: String = ""
    var name: String = ""
    var seqNo: String = ""
    var teacher: String = ""
    var weeks: WeekSpan? = nil
    var day: String = ""
    var room: String = ""
}

struct ParseResult: Codable, Equatable {
    var term: TermInfo = TermInfo()
    var sections: [Section] = []
    var sessions: [Session] = []
    var unscheduled: [UnscheduledCourse] = []

    var isEmpty: Bool { sessions.isEmpty && unscheduled.isEmpty }

    func section(_ index: Int) -> Section? { sections.first { $0.index == index } }
    func sectionStart(_ index: Int) -> String { section(index)?.start ?? "" }
    func sectionEnd(_ index: Int) -> String { section(index)?.end ?? "" }

    /// 节次的显示名。必须用 label：id 序号是行序，第 5 节的行序是 7
    func sectionLabel(_ index: Int) -> String { section(index)?.label ?? "第\(index)节" }

    /// 合并段的显示，如「第1-2节」；「中午1节」这类非数字标签退回用 ~ 连接
    func sectionRangeLabel(_ start: Int, _ end: Int) -> String {
        let a = sectionLabel(start)
        let b = sectionLabel(end)
        if start == end { return a }
        func number(_ s: String) -> Int? {
            guard s.hasPrefix("第"), s.hasSuffix("节") else { return nil }
            return Int(s.dropFirst().dropLast())
        }
        if let na = number(a), let nb = number(b) { return "第\(na)-\(nb)节" }
        return "\(a)~\(b)"
    }

    /// 学期周数上界，用于周次切换范围
    var maxWeek: Int { sessions.map { $0.weeks.end }.max() ?? 20 }
}

/// 小组件用的「一条安排」：正在上 或 接下来要上的某节具体日期的课。
struct Agenda: Identifiable {
    var session: Session
    var date: Date
    var start: TimeOfDay
    var end: TimeOfDay
    var ongoing: Bool
    var id: String { "\(session.key)@\(date.timeIntervalSince1970)" }

    var sectionLabel: String { session.sectionSpanLabel }
    /// 「08:30–10:10」
    var timeRange: String { "\(start.display)–\(end.display)" }
}
