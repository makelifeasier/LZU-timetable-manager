import Foundation
import WidgetKit

//  App Group 存储 —— App 与小组件共享数据。
//
//  ⚠️ App Group 属于需要**付费开发者账号**的能力。免费 Apple ID 签不出来，
//  那时小组件拿不到数据（只能显示「打开 App 同步」）。

enum Store {

    /// 改成你自己的（必须与 project.yml 里的 GROUP 一致）
    static let appGroup = "group.com.example.timetable"
    private static let defaults = UserDefaults(suiteName: appGroup)

    private enum Key {
        static let result = "resultJson"
        static let url = "url"
        static let week1 = "week1"          // ISO yyyy-MM-dd
        static let manualWeek = "manualWeek"
        static let widgetMode = "widgetMode"
        static let weekendMode = "weekendMode"
        static let style = "style"
        static let darkMode = "dark"
        static let colorSeed = "colorSeed"
        static let lastSync = "lastSync"
        static let lastMessage = "lastMessage"
        static let reminderEnabled = "remindOn"
        static let reminderMinutes = "remindMin"
    }

    // MARK: - 课表缓存

    static var result: ParseResult {
        get {
            guard let s = defaults?.string(forKey: Key.result), let d = s.data(using: .utf8) else { return ParseResult() }
            return (try? JSONDecoder().decode(ParseResult.self, from: d)) ?? ParseResult()
        }
        set {
            guard let d = try? JSONEncoder().encode(newValue), let s = String(data: d, encoding: .utf8) else { return }
            defaults?.set(s, forKey: Key.result)
            reloadWidgets()
        }
    }

    static var timetableURL: String {
        get { defaults?.string(forKey: Key.url) ?? "" }
        set { defaults?.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: Key.url) }
    }

    // MARK: - 周次

    static var week1Monday: Date? {
        get {
            guard let s = defaults?.string(forKey: Key.week1), !s.isEmpty else { return nil }
            let f = DateFormatter()
            f.dateFormat = "yyyy-MM-dd"
            f.calendar = WeekCalc.calendar
            return f.date(from: s)
        }
        set {
            guard let d = newValue else { defaults?.removeObject(forKey: Key.week1); return }
            let f = DateFormatter()
            f.dateFormat = "yyyy-MM-dd"
            f.calendar = WeekCalc.calendar
            defaults?.set(f.string(from: d), forKey: Key.week1)
        }
    }

    /// 手动预览周次；0 = 跟随今天
    static var manualWeek: Int {
        get { defaults?.integer(forKey: Key.manualWeek) ?? 0 }
        set { defaults?.set(newValue, forKey: Key.manualWeek) }
    }

    /// 首次导入：按学期估算第 1 周（教务系统页面里没有当前周次）
    static func ensureWeek1(_ result: ParseResult, today: Date = Date()) {
        guard week1Monday == nil else { return }
        week1Monday = WeekCalc.initialWeek1Monday(result: result, today: today)
    }

    // MARK: - 外观

    /// 小组件模式：0 正在上+接下来 / 1 一整天全部课
    static var widgetMode: Int {
        get { defaults?.integer(forKey: Key.widgetMode) ?? 0 }
        set { defaults?.set(newValue % 2, forKey: Key.widgetMode); reloadWidgets() }
    }

    /// 周末底色：0 淡底色（现在）/ 1 与工作日相同
    static var weekendMode: Int {
        get { defaults?.integer(forKey: Key.weekendMode) ?? 0 }
        set { defaults?.set(newValue % 2, forKey: Key.weekendMode) }
    }

    static var weekendTint: Bool { weekendMode == 0 }

    /// 课表风格 0..5
    static var style: Int {
        get { defaults?.integer(forKey: Key.style) ?? 0 }
        set { defaults?.set(min(max(newValue, 0), 5), forKey: Key.style) }
    }

    /// 0 跟随系统 / 1 浅色 / 2 深色
    static var darkMode: Int {
        get { defaults?.integer(forKey: Key.darkMode) ?? 0 }
        set { defaults?.set(min(max(newValue, 0), 2), forKey: Key.darkMode) }
    }

    static var colorSeed: Int {
        get { defaults?.object(forKey: Key.colorSeed) as? Int ?? 0 }
        set { defaults?.set(newValue, forKey: Key.colorSeed) }
    }

    // MARK: - 同步状态

    static var lastSync: Date? {
        get { defaults?.object(forKey: Key.lastSync) as? Date }
        set { defaults?.set(newValue, forKey: Key.lastSync) }
    }

    static var lastMessage: String {
        get { defaults?.string(forKey: Key.lastMessage) ?? "尚未同步" }
        set { defaults?.set(newValue, forKey: Key.lastMessage) }
    }

    // MARK: - 提醒

    static var reminderEnabled: Bool {
        get { defaults?.object(forKey: Key.reminderEnabled) as? Bool ?? true }
        set { defaults?.set(newValue, forKey: Key.reminderEnabled) }
    }

    static var reminderMinutes: Int {
        get { defaults?.object(forKey: Key.reminderMinutes) as? Int ?? 10 }
        set { defaults?.set(min(max(newValue, 0), 120), forKey: Key.reminderMinutes) }
    }

    // MARK: - 工具

    /// 让小组件立刻重画（iOS 17+ 才有 reloadWidgets；旧系统用 reloadAllTimelines）
    static func reloadWidgets() {
        if #available(iOS 14.0, *) {
            WidgetCenter.shared.reloadAllTimelines()
        }
    }
}
