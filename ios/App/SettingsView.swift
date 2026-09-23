import SwiftUI

/// 设置 —— 与 Android 版 `SettingsActivity.kt` 对应
struct SettingsView: View {

    var onChanged: () -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var style = Store.style
    @State private var weekendMode = Store.weekendMode
    @State private var widgetMode = Store.widgetMode
    @State private var darkMode = Store.darkMode
    @State private var url = Store.timetableURL
    @State private var week1 = Store.week1Monday
    @State private var manualWeek = Store.manualWeek
    @State private var reminderOn = Store.reminderEnabled
    @State private var reminderMin = Store.reminderMinutes
    @State private var message = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("课表来源") {
                    TextField("登录后自动获取，一般不用手填", text: $url, axis: .vertical)
                        .font(.footnote)
                        .lineLimit(1...3)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button("保存手填链接") {
                        Store.timetableURL = url
                        message = "已保存"
                    }
                    Button("清空链接", role: .destructive) {
                        Store.timetableURL = ""
                        url = ""
                        message = "已清空，下次登录会重新自动获取"
                    }
                }

                Section("周次") {
                    Stepper("现在第 \(manualWeek == 0 ? realWeek : manualWeek) 周", value: $manualWeek, in: 0...30)
                    Button("跟随今天") {
                        manualWeek = 0
                        Store.manualWeek = 0
                    }
                    DatePicker("第 1 周周一", selection: Binding(
                        get: { week1 ?? Date() },
                        set: { week1 = $0; Store.week1Monday = $0 }
                    ), displayedComponents: .date)
                }

                Section("周末底色") {
                    Picker("周六周日那一列", selection: $weekendMode) {
                        Text("淡底色（现在）").tag(0)
                        Text("与工作日相同").tag(1)
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: weekendMode) { _, v in
                        Store.weekendMode = v
                        onChanged()
                    }
                }

                Section("课表风格") {
                    Picker("卡片样式", selection: $style) {
                        ForEach(Array(TimetableCanvas.Style.names.enumerated()), id: \.offset) { i, n in
                            Text(n).tag(i)
                        }
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: style) { _, v in
                        Store.style = v
                        onChanged()
                    }
                }

                Section("深色模式") {
                    Picker("外观", selection: $darkMode) {
                        Text("跟随系统").tag(0)
                        Text("浅色").tag(1)
                        Text("深色").tag(2)
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: darkMode) { _, v in Store.darkMode = v }
                }

                Section("桌面小组件") {
                    Picker("显示模式", selection: $widgetMode) {
                        Text("正在上+接下来").tag(0)
                        Text("一整天全部课").tag(1)
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: widgetMode) { _, v in
                        Store.widgetMode = v
                        onChanged()
                    }
                    Text("iOS 的小组件不能上下滑动（系统限制），会按组件大小显示尽可能多的整行。")
                        .font(.caption).foregroundStyle(.secondary)
                }

                Section("上课提醒") {
                    Toggle("提前提醒", isOn: $reminderOn)
                        .onChange(of: reminderOn) { _, v in Store.reminderEnabled = v }
                    Stepper("提前 \(reminderMin) 分钟", value: $reminderMin, in: 0...120, step: 5)
                        .onChange(of: reminderMin) { _, v in Store.reminderMinutes = v }
                }

                Section("关于") {
                    LabeledContent("版本", value: "iOS 1.0")
                    Text("非官方项目，与兰州大学无关。数据只保存在本机（App Group），不上传任何信息。")
                        .font(.caption).foregroundStyle(.secondary)
                }

                if !message.isEmpty {
                    Section { Text(message).font(.footnote).foregroundStyle(.secondary) }
                }
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
    }

    private var realWeek: Int {
        guard let w1 = Store.week1Monday else { return 1 }
        return max(1, WeekCalc.weekOf(Date(), week1Monday: w1))
    }
}
