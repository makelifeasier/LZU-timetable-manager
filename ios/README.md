# 兰大课表 · iOS 版（SwiftUI + WidgetKit）

把兰州大学教务系统「学生课表」导入 iPhone，并在**桌面小组件**上显示今日课程。
这是 Android 版的同源移植：解析器 / 周次计算 / 链接发现与 Android 版**逐条对应**。

---

## ⚠️ 先读这一段：交付状态

| | 情况 |
| --- | --- |
| **代码** | 完整可读、结构与 Android 版一一对应 |
| **编译/运行** | ❌ **我从未编译过**（开发机是 Windows，没有 macOS/Xcode）。首次在你的 Mac 上构建时，可能需要修几个编译错误 |
| **单测** | 已按 Android 版的同一批断言写好（`ios/Tests/`），**你在 Mac 上跑一次就等于验证了移植** |
| **真机** | ❌ 未验证 |

请把 `xcodebuild test` 的输出发我，我按报错逐个修。**解析器那几个测试最重要** —— 它对应 Android 上 13 个已通过的断言。

---

## 一、iOS 上做不到"一模一样"的地方（平台限制，非实现取舍）

| 功能 | Android | iOS | 说明 |
| --- | --- | --- | --- |
| 小组件**上下滑动** | ✅ ListView | ❌ | WidgetKit 小组件是静态快照，**系统不提供可滚动列表**。改为：一屏显示 N 节（N 随组件尺寸变大），装不下就靠更大尺寸或「整天/接下来」切换 |
| 小组件切换模式 | ✅ 图标按钮 | ⚠️ iOS 17+ 按钮 | 用 App Intent 做按钮；iOS 16 只能点开 App 切 |
| 后台定时刷新 | ✅ 每 30 分钟 | ⚠️ 系统配额 | WidgetKit 的刷新由系统按预算调度，**不能保证 30 分钟一次**；课表本来也几乎不变，够用 |
| 精确到分钟的上课提醒 | ✅ AlarmManager | ⚠️ 本地通知 | 用 `UNUserNotificationCenter` 定时通知，系统会合并/延迟，通常差几秒 |
| 导出整周图片 | ✅ | ✅ | SwiftUI `ImageRenderer` |
| 6 种课表风格 / 自定义背景 / 周末底色 | ✅ | ✅ | 同源实现 |
| 自动登录导入 | ✅ WebView | ✅ WKWebView | 同一套「门户 → 认证 → 发现课表链接」流程 |

### ⚠️ 小组件需要付费开发者账号

iOS 里 App 与小组件之间的数据共享**只能通过 App Group**，而 **App Group 属于需要付费账号（$99/年）的能力** —— 免费 Apple ID 签不出来。
所以：

- **只想自己手机上用整个 App**：免费账号够（7 天一续签，或用 AltStore 自动续）
- **要用桌面小组件**：需要一个付费开发者账号；要发给同学用，则走 TestFlight（同样需要付费账号）

---

## 二、构建

需要：**macOS + Xcode 15+**（WidgetKit 的按钮需要 Xcode 15 / iOS 17 SDK；不用按钮则 Xcode 14 也行）

### 没有 Mac 怎么办？

`.github/workflows/ci.yml` 里的 `ios` 任务就是为这件事准备的：**GitHub 给公开仓库免费提供 macOS 运行器**，
每次 push 都会在 Apple 的机器上 `xcodegen generate` + `xcodebuild test`。
所以你不买 Mac 也能拿到编译错误和测试结果 —— 把日志发我，我按报错修。

其它选择（按性价比排）：

| 方式 | 能做什么 | 代价 |
| --- | --- | --- |
| **GitHub Actions macOS 运行器** | 编译 + 跑单测 + 出测试报告 | 公开仓库免费；**没有图形界面**，不能手工点 UI |
| 云 Mac（MacinCloud / Scaleway Mac mini / MacStadium） | 完整的 Xcode GUI，能连真机 | 按小时/月计费 |
| Xcode Cloud · Codemagic · Bitrise | 移动端 CI，有免费额度 | 需要 Apple 开发者账号（Xcode Cloud） |
| **Windows 上的 Swift 工具链**（`winget install Swift.Toolchain` + VS Build Tools） | **只能跑纯逻辑的那几个文件**（模型/周次/解析器/链接发现） | UI 与小组件编不了 —— Windows 上没有 UIKit/SwiftUI/WidgetKit |
| iPad 上的 Swift Playgrounds | 小项目可以 | 这个工程跑不了 |
| 黑苹果 / macOS 虚拟机 | —— | 违反 Apple 许可协议，且驱动与性能坑很深，不推荐 |

**Xcode 就是 iOS 的 Android Studio，但它只有 macOS 版** —— 这是两个平台最大的不对称，
所以上面这些绕路方案才存在。

### 在 Mac 上构建

```bash
# 1. 生成 Xcode 工程（工程文件是脚本生成的，不手写 .pbxproj）
brew install xcodegen
cd ios
xcodegen generate

# 2. 跑单测（这一步等于验证「移植是否与 Android 版一致」）
xcodebuild test -scheme LZUWidget -destination 'platform=iOS Simulator,name=iPhone 15'

# 3. 打开工程，选你的 Team，改 Bundle ID 前缀，然后 Run
open LZUWidget.xcodeproj
```

**上手先改这三处**（都在 `project.yml` 里）：

```yaml
APP_ID: com.example.timetable     # 改成你自己的（必须全局唯一）
GROUP:  group.com.example.timetable   # App Group，同样要唯一
TEAM:   ABCDE12345                # 你的 Team ID（可留空用自动签名）
```

---

## 三、目录

```
ios/
├── project.yml                     XcodeGen 工程描述（app + widget 两个 target）
├── Shared/                         两个 target 共用的代码
│   ├── Model.swift                 数据模型（对应 Android 的 Model.kt）
│   ├── HtmlText.swift              HTML 文本工具（对应 HtmlText.kt，含 <wbr> 坑）
│   ├── TimetableParser.swift       课表解析（对应 TimetableParser.kt）
│   ├── WeekCalc.swift              周次/今日课（对应 WeekCalc.kt）
│   ├── TimetableLink.swift         课表链接发现（对应 TimetableLink.kt）
│   └── Store.swift                 App Group 存储 + JSON 缓存
├── App/
│   ├── App.swift                   入口
│   ├── MainView.swift              主页（周次切换 + 课表 + ⋮ 菜单）
│   ├── TimetableCanvas.swift       自绘课表（对应 TimetableRenderer.kt）
│   ├── LoginView.swift             登录并自动导入（对应 LoginActivity.kt）
│   └── SettingsView.swift          设置（对应 SettingsActivity.kt）
├── Widget/
│   └── TimetableWidget.swift       桌面小组件（对应 TodayWidgetProvider）
└── Tests/
    ├── ParserTests.swift           与 Android 同一批断言
    └── LinkTests.swift
```

---

## 四、与 Android 版的差异清单（实现层）

1. **没有「可滑动」**：小组件按尺寸显示 N 行，`maxRows` 由 `WidgetFamily` 决定
2. **行高固定 36pt**：与 Android 一致地用固定行高，保证底部不切半行
3. **分享/导出**：用 `ShareLink` 而不是 Intent
4. **登录发现流程**：Android 那个「扫描 → 等 AJAX → 展开父菜单 → 点课表 → 再扫」的 6 步状态机，在 iOS 上原样保留（`LoginView.swift`），因为它解决的是**教务系统**的形态问题，与平台无关
5. **Cookie**：`WKWebsiteDataStore.default().httpCookieStore`（等价于 Android 的 CookieManager）

---

## 五、隐私

与 Android 版一致：不保存账号密码（登录态在 WKWebView 的 Cookie 里，仅存本机）；
课表数据只写在本机 App Group 容器，不上传任何数据；只访问 `jwk.lzu.edu.cn` 与 `sso.lzu.edu.cn`。

**非官方项目**，与兰州大学无关，详见仓库根目录 README 的免责声明。
