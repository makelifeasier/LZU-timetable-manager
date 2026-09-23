import WidgetKit
import SwiftUI

//  桌面小组件 —— 与 Android 版 `TodayWidgetProvider` 对应。
//
//  ⚠️ iOS 平台限制（非实现取舍）：
//   · **不能上下滑动**：WidgetKit 小组件是静态快照，系统不提供可滚动列表。
//     改为按组件尺寸显示尽可能多的**整行**，多出来的要靠更大尺寸或切换模式。
//   · 行高固定 36pt：与 Android 侧同样的理由 —— 列表高度取其整数倍，底部才不切半行。
//   · 刷新由系统按预算调度，不保证 30 分钟一次（课表本来也几乎不变）。

struct Row: Identifiable {
    var id: String { name + time + place }
    var name: String
    var place: String
    var time: String          // 08:30–10:10
    var hint: String          // 明天 / 还剩 25 分 / 正在上
    var ongoing: Bool
    var color: Color
}

struct Entry: TimelineEntry {
    var date: Date
    var title: String
    var subtitle: String
    var footer: String
    var rows: [Row]
    var emptyText: String?
    var dark: Bool
}

struct Provider: TimelineProvider {

    func placeholder(in context: Context) -> Entry {
        Entry(date: Date(), title: "9月22日 周二", subtitle: "第 3 周 · 接下来 2 节",
              footer: "刚刚同步", rows: [
                Row(name: "高等数学", place: "第1-2节 · 天山堂A101", time: "08:30–10:10",
                    hint: "明天", ongoing: false, color: .blue)
              ], emptyText: nil, dark: false)
    }

    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        completion(build(context))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        let entry = build(context)
        // 下一节课开始/结束时各刷一次；再兜一个 30 分钟的底
        let next = Calendar.current.date(byAdding: .minute, value: 30, to: Date()) ?? Date().addingTimeInterval(1800)
        completion(Timeline(entries: [entry], policy: .after(next)))
    }

    // MARK: 组装

    private func build(_ context: Context) -> Entry {
        let result = Store.result
        let today = Date()
        let w1 = Store.week1Monday ?? WeekCalc.initialWeek1Monday(result: result, today: today)
        let week = max(1, WeekCalc.weekOf(today, week1Monday: w1))
        let dark = Store.darkMode == 2
        let title = fmt(today)
        let maxRows = rowBudget(context)

        guard !result.sessions.isEmpty else {
            return Entry(date: today, title: title, subtitle: "未同步",
                         footer: Store.lastMessage, rows: [],
                         emptyText: "打开 App 登录并导入课表", dark: dark)
        }

        var rows: [Row] = []
        if Store.widgetMode == 1 {
            // 整天：今天从早到晚全部课
            for s in WeekCalc.todaySessions(result, date: today, week: week) {
                rows.append(makeRow(result, s, hint: "", ongoing: false, date: today))
                if rows.count >= maxRows { break }
            }
            let subtitle = "第 \(week) 周  ·  今天 \(rows.count) 节"
            return Entry(date: today, title: title, subtitle: subtitle,
                         footer: syncHint(), rows: rows,
                         emptyText: rows.isEmpty ? "今天没有课 🎉" : nil, dark: dark)
        } else {
            // 接下来：正在上 + 后面要上的，跨天
            let now = Date()
            let agenda = WeekCalc.agenda(result, week1Monday: w1, now: now, lookaheadDays: 8, limit: 40)
            for item in agenda {
                rows.append(makeRow(result, item.session,
                                    hint: WeekCalc.relativeLabel(item, now: now),
                                    ongoing: item.ongoing, date: item.date))
                if rows.count >= maxRows { break }
            }
            let subtitle = "第 \(week) 周  ·  接下来 \(rows.count) 节"
            return Entry(date: today, title: title, subtitle: subtitle,
                         footer: syncHint(), rows: rows,
                         emptyText: rows.isEmpty ? "接下来几天都没有课" : nil, dark: dark)
        }
    }

    private func makeRow(_ result: ParseResult, _ s: Session, hint: String, ongoing: Bool, date: Date) -> Row {
        let start = result.section(s.startSection)?.start ?? ""
        let end = result.section(s.endSection)?.end ?? ""
        let place = [result.sectionRangeLabel(s.startSection, s.endSection), s.room]
            .filter { !$0.isEmpty }.joined(separator: " · ")
        return Row(name: s.name, place: place, time: "\(start)–\(end)",
                   hint: ongoing ? ["正在上", hint].filter { !$0.isEmpty }.joined(separator: " · ") : hint,
                   ongoing: ongoing, color: TimetableCanvas.colorFor(s.name, seed: Store.colorSeed))
    }

    /// 组件高度能放几行（行高固定 36pt + 22pt 的头部与页脚）
    private func rowBudget(_ context: Context) -> Int {
        let h = context.displaySize.height
        let chrome: CGFloat = 58      // 头部 + 页脚
        return max(1, min(6, Int((h - chrome) / 36)))
    }

    private func fmt(_ d: Date) -> String {
        let c = WeekCalc.calendar.dateComponents([.month, .day], from: d)
        return "\(c.month ?? 0)月\(c.day ?? 0)日 \(Session.dayLabel(WeekCalc.dayOf(d)))"
    }

    private func syncHint() -> String {
        guard let t = Store.lastSync else { return Store.lastMessage }
        let mins = Int(Date().timeIntervalSince(t) / 60)
        if mins < 1 { return "刚刚同步" }
        if mins < 60 { return "\(mins) 分钟前同步" }
        return "\(mins / 60) 小时前同步"
    }
}

// MARK: - 视图

struct WidgetView: View {
    var entry: Entry

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text(entry.title).font(.system(size: 13, weight: .semibold))
                Spacer()
                Text(entry.subtitle).font(.system(size: 10)).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 12).padding(.top, 10).padding(.bottom, 4)

            if let text = entry.emptyText {
                Spacer()
                Text(text).font(.system(size: 12)).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                Spacer()
            } else {
                VStack(spacing: 2) {
                    ForEach(entry.rows) { row in
                        HStack(spacing: 8) {
                            Circle().fill(row.color).frame(width: 7, height: 7)
                            VStack(alignment: .leading, spacing: 0) {
                                Text(row.name)
                                    .font(.system(size: 12))
                                    .lineLimit(1)
                                Text(row.place)
                                    .font(.system(size: 9))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                            }
                            Spacer(minLength: 4)
                            VStack(alignment: .trailing, spacing: 0) {
                                Text(row.time)
                                    .font(.system(size: 10, weight: .semibold))
                                    .lineLimit(1)
                                if !row.hint.isEmpty {
                                    Text(row.hint)
                                        .font(.system(size: 9))
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                            }
                        }
                        .frame(height: 36)          // 行高固定：底部永远不切半行
                        .padding(.horizontal, 12)
                    }
                }
            }

            Spacer(minLength: 0)
            Text(entry.footer)
                .font(.system(size: 9)).foregroundStyle(.secondary)
                .padding(.horizontal, 12).padding(.bottom, 8)
        }
        .containerBackground(for: .widget) {
            Color(entry.dark ? 0x1B1E24 : 0xFFFFFF)
        }
    }
}

@main
struct TimetableWidgetBundle: WidgetBundle {
    var body: some Widget {
        TimetableWidget()
    }
}

struct TimetableWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "TimetableWidget", provider: Provider()) { entry in
            WidgetView(entry: entry)
        }
        .configurationDisplayName("兰大课表")
        .description("今日课程 / 接下来要上的课")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
