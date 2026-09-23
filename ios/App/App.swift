import SwiftUI

@main
struct LZUWidgetApp: App {
    var body: some Scene {
        WindowGroup { MainView() }
    }
}

/// 主页：周次切换 + 课表 + 菜单
struct MainView: View {

    @State private var result: ParseResult = Store.result
    @State private var week: Int = 0
    @State private var showLogin = false
    @State private var showSettings = false
    @State private var detail: Session?
    @State private var toast: String?

    @Environment(\.colorScheme) private var systemScheme

    private var isDark: Bool {
        switch Store.darkMode {
        case 1: return false
        case 2: return true
        default: return systemScheme == .dark
        }
    }

    private var today: Date { Date() }
    private var realWeek: Int {
        guard let w1 = Store.week1Monday else { return 1 }
        return max(1, WeekCalc.weekOf(today, week1Monday: w1))
    }
    private var currentWeek: Int { week == 0 ? realWeek : week }
    private var weekMonday: Date? {
        Store.week1Monday.flatMap { WeekCalc.calendar.date(byAdding: .day, value: (currentWeek - 1) * 7, to: WeekCalc.mondayOf($0)) }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                header
                if result.sessions.isEmpty {
                    banner
                }
                ScrollView([.vertical]) {
                    TimetableCanvas(
                        result: result,
                        week: currentWeek,
                        todayDay: currentWeek == realWeek ? WeekCalc.dayOf(today) : 0,
                        now: currentWeek == realWeek ? nowTimeOfDay() : nil,
                        weekMonday: weekMonday,
                        style: Store.style,
                        weekendTint: Store.weekendTint,
                        dark: isDark,
                        accent: Color(hex: 0x1E6FD9)
                    )
                }
                .background(Color(hex: isDark ? 0x0F1115 : 0xFFFFFF))
            }
            .navigationTitle("兰大课表")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("登录并导入课表") { showLogin = true }
                        Button("立即同步") { syncNow() }
                        Button("设置") { showSettings = true }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
            .sheet(isPresented: $showLogin) {
                LoginView { ok in
                    showLogin = false
                    if ok { result = Store.result; week = 0 }
                }
            }
            .sheet(isPresented: $showSettings) {
                SettingsView { result = Store.result }
            }
            .sheet(item: $detail) { s in
                SessionDetail(session: s, result: result)
            }
            .overlay(alignment: .bottom) {
                if let toast {
                    Text(toast).font(.footnote).padding(10)
                        .background(.ultraThinMaterial, in: Capsule())
                        .padding(.bottom, 24)
                        .task { try? await Task.sleep(nanoseconds: 1_800_000_000); self.toast = nil }
                }
            }
        }
        .onAppear { result = Store.result }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button { shift(-1) } label: { Image(systemName: "chevron.left") }
            VStack(spacing: 2) {
                Text("第 \(currentWeek) 周").font(.headline)
                if let wm = weekMonday {
                    let sun = WeekCalc.calendar.date(byAdding: .day, value: 6, to: wm) ?? wm
                    Text("\(md(wm)) – \(md(sun))").font(.caption2).foregroundStyle(.secondary)
                }
            }
            .frame(minWidth: 108)
            Button { shift(1) } label: { Image(systemName: "chevron.right") }
            if week != 0 {
                Button("本周") { week = 0 }
                    .font(.caption).buttonStyle(.bordered)
            }
            Spacer()
            Text(result.term.display.isEmpty ? "未导入课表" : result.term.display)
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
    }

    private var banner: some View {
        Button {
            showLogin = true
        } label: {
            HStack {
                Image(systemName: "info.circle")
                Text("首次使用：登录一次统一身份认证，自动导入你的课表")
                    .font(.footnote)
                Spacer()
                Text("去登录").font(.footnote.bold())
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
            .background(Color(hex: 0x1E6FD9).opacity(0.12))
        }
        .buttonStyle(.plain)
    }

    private func shift(_ delta: Int) {
        let base = currentWeek
        week = min(max(base + delta, 1), max(result.maxWeek, 20))
    }

    private func md(_ d: Date) -> String {
        let c = WeekCalc.calendar.dateComponents([.month, .day], from: d)
        return "\(c.month ?? 0)/\(c.day ?? 0)"
    }

    private func nowTimeOfDay() -> TimeOfDay {
        let c = WeekCalc.calendar.dateComponents([.hour, .minute], from: Date())
        return TimeOfDay(hour: c.hour ?? 0, minute: c.minute ?? 0)
    }

    private func syncNow() {
        guard let url = URL(string: Store.timetableURL), !Store.timetableURL.isEmpty else {
            toast = "还没导入课表，先「登录并导入课表」"
            return
        }
        var req = URLRequest(url: url)
        req.httpShouldHandleCookies = true      // 复用 WKWebView 留下的会话
        URLSession.shared.dataTask(with: req) { data, _, _ in
            DispatchQueue.main.async {
                guard let data, let html = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .shiftJIS) else {
                    toast = "抓取失败"; return
                }
                let parsed = TimetableParser.parse(html)
                if parsed.sessions.isEmpty {
                    toast = "抓到的页面里没有课表"
                } else {
                    Store.result = parsed
                    Store.ensureWeek1(parsed)
                    Store.lastSync = Date()
                    Store.lastMessage = "已同步 \(parsed.sessions.count) 段课"
                    result = parsed
                    toast = "同步成功"
                }
            }
        }.resume()
    }
}

/// 点一节课 → 完整信息（窄列里长课名必然放不下，这是兜底出口）
struct SessionDetail: View {
    let session: Session
    let result: ParseResult
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                row("课程", session.name)
                row("节次", result.sectionRangeLabel(session.startSection, session.endSection))
                if let st = result.section(session.startSection)?.start,
                   let et = result.section(session.endSection)?.end {
                    row("时间", "\(st) – \(et)")
                }
                row("星期", Session.dayLabel(session.day))
                row("周次", session.weeks.display)
                if !session.room.isEmpty { row("教室", session.room) }
                if !session.teacher.isEmpty { row("教师", session.teacher) }
                if !session.seqNo.isEmpty { row("课序号", session.seqNo) }
            }
            .navigationTitle(session.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("好") { dismiss() } } }
        }
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack(alignment: .top) {
            Text(k).foregroundStyle(.secondary).frame(width: 56, alignment: .leading)
            Text(v)
            Spacer()
        }
        .font(.subheadline)
    }
}

extension Session: Identifiable {
    public var id: String { "\(key)#\(day)-\(startSection)-\(endSection)" }
}
