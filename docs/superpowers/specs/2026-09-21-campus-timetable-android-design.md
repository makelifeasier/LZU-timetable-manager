# 兰大课表 Android（桌面小组件）设计规格

日期：2026-09-21
状态：已冻结（v1 范围）

## 1. 目标

把兰州大学教务系统「学生课表」页面自动导入安卓端，并在**桌面小组件**上直接显示今日课程；App 内可看完整周课表、切周次、导出图片、按提前量提醒上课。

目标页面（用户提供）：

```
https://jwk.lzu.edu.cn/academic/manager/coursearrange/showTimetable.do
    ?id=900001&yearid=46&termid=2&timetableType=STUDENT&sectionType=BASE
```

## 2. 已验证的约束

| 项 | 结论 |
| --- | --- |
| 鉴权 | 未登录会被 302 到 `https://jwk.lzu.edu.cn/manager/coursearrange/login.jsp`（**该路径本身返回 HTTP 404**），并非直接跳 `sso.lzu.edu.cn`。**必须处理登录态**，且不能只认 SSO 域名 |
| 编码 | 页面为 **GBK**（`<meta charset=GBK>`），HTTP 响应解码不能按 UTF-8 |
| 页面含当前周次 | **否**。全页无「当前周/第N周/开学日期」信息 → 周次须由用户设定一次「第1周周一」推算 |
| 服务端渲染 | 是。课表是服务端直出 HTML，无 AJAX 拉取 → 抓到 HTML 即可离线解析 |
| 本机编译环境 | JDK 17 (Temurin) + `<Android SDK>`（platform 37 / build-tools 36）+ Gradle 9.5 + AGP 9.3 缓存 |
| Cookie 共享 | `android.webkit.CookieManager` **不是** `java.net.CookieHandler`，WebView 登录的 Cookie 不会自动出现在 `HttpURLConnection` 上，必须手工读写 Cookie 头 |

## 3. 页面结构契约（已从真实页面确认）

主表：`<table id="timetable">`

```html
<tr><th>&nbsp;</th><th>周一</th><th>周二</th>…<th>周日</th></tr>
<tr class="infolist_hr_common">
  <th nowrap="">第1节<br>08:30<br>┆<br>09:15</th>
  <td id="1-1" class="center">&lt;&lt;高阶英语1&gt;&gt;;23<br>天山堂A301<br>甲老师<br>4-18周双周<br>讲课学时</td>
  …
</tr>
```

要点：

1. **单元格 `id="星期-节次"`**：`1-1` = 周一第 1 节。坐标由 id 直接给出，无需按表头推列。
2. 行首 `<th>` 携带该节次的**标签与上下课时间**：`第1节 / 08:30 / 09:15`。共 14 节，其中第 5、6 节名为「中午1节」「中午2节」，但 `id` 序号仍连续为 5、6。
3. 单元格文本 = `<<课程名>>;课序号` ⏎ `上课地点` ⏎ `任课教师` ⏎ `周次` ⏎ `学时类型`。
   - 周次形态：`4-19周全周`、`4-18周双周`、`6-15周单周`。
   - 空单元格为 `&nbsp;`。
4. **一个格子可含多门/多段课程**：以每行 `<<` 开头为新的一段（例：周二第5节为 `4-8周 秦岭堂A312` 与 `9-19周 秦岭堂B106` 两段）。
5. 浏览器保存页面时会插入 `<wbr>`（Firefox 自动换行）与 `<wbr>` 分词标签，**解析前必须剥离**；保存版还含沉浸式翻译注入的 `<div id="immersive-translate-popup">`，须忽略。
6. 次要表：`<table id="noArrangement">`，列为 `课程号/课程名称/课序号/任课教师/合班/上课周次/星期/上课地点`，收录「没有具体上课时间或地点的课程」（如网络共享课）。星期与地点可为空。
7. 头部信息：`2026 秋`、`学生课表: 999999999999`（学号）、`班级：2026示例班`。
8. `<!--调课信息-->` 为占位注释，本样本为空。

## 4. 数据模型

```kotlin
data class WeekSpan(val start: Int, val end: Int, val parity: Parity) // NONE/ODD/EVEN
data class Section(val index: Int, val label: String, val start: String, val end: String)
data class Session(            // 一段具体的课：解析的最小单位
    val name: String, val seqNo: String, val room: String, val teacher: String,
    val weeks: WeekSpan, val category: String,          // 讲课学时/实验学时…
    val day: Int, val startSection: Int, val endSection: Int   // 1..7, 1..14
)
data class TermInfo(val year: String, val term: String, val studentNo: String, val className: String)
data class UnscheduledCourse(val code:String,val name:String,val seqNo:String,val teacher:String,val weeks:WeekSpan?,val day:String?,val room:String?)
data class ParseResult(val term: TermInfo, val sections: List<Section>, val sessions: List<Session>, val unscheduled: List<UnscheduledCourse>)
```

## 5. 架构

零第三方运行时依赖（仅 Android framework + Kotlin stdlib）：minSdk 31 足够用现代框架 API，省掉 AndroidX 带来的解析/体积/构建风险。

```
application/App.kt            CookieManager 安装为默认 CookieHandler
data/Model.kt                 数据模型（纯 Kotlin，无 Android 依赖 → 可单测）
data/HtmlText.kt              实体解码 / <br> 归一 / 标签剥离 / 表格切分（纯函数）
data/TimetableParser.kt       HTML → ParseResult（纯函数）
data/WeekCalc.kt              周次推算、单双周过滤、相邻节次合并、今日课程（纯函数）
data/Prefs.kt                 SharedPreferences + org.json 持久化
data/TimetableRepository.kt   取数/解析/缓存/同步状态编排（单例）
net/Fetcher.kt                HttpURLConnection 拉取 + GBK 解码 + 登录态判定
ui/LoginActivity.kt           WebView 登录 → JS 注入取 outerHTML → 解析并入库
ui/MainActivity.kt            周课表（自定义 View）+ 周次切换 + 菜单
ui/TimetableView.kt           自绘课表网格 View（同时用于导出图片）
ui/SettingsActivity.kt        登录/第1周/提醒/深色/配色/清缓存
ui/DiagnosticsActivity.kt     抓取与解析诊断 + 导出原始 HTML
widget/TodayWidgetProvider.kt 桌面小组件（今日课程清单）
reminder/ReminderScheduler.kt AlarmManager 排课提醒
reminder/ReminderReceiver.kt  到点发通知
reminder/BootReceiver.kt      开机/升级后重排
share/ImageExporter.kt        画布导出 PNG → MediaStore → 分享
```

## 6. 数据流

```
用户点「登录导入」→ LoginActivity 加载课表 URL
   → SSO 登录（人工完成，验证码/短信均可）
   → 落回 jwk.lzu.edu.cn：evaluateJavascript 取 document.documentElement.outerHTML
   → TimetableParser.parse(html)
   → Prefs 存 sessions/sections/term + Cookie 留在 CookieManager
   → 刷新桌面小组件 + 排提醒

后台刷新：AlarmManager(30min) / 小组件刷新按钮 / App 启动
   → Fetcher 用 CookieManager 的 Cookie 直接 GET
   → 若返回 SSO 登录页 → 标记「登录态失效」，小组件提示重新登录
   → 成功则覆盖缓存
```

## 7. 周次

- 设置项：**第1周周一日期**。首次导入时用一步「现在第几周」让用户确认，自动反推 `week1Monday = 本周一 - (N-1)*7`。
- `currentWeek = floor((today - week1Monday)/7) + 1`。
- 小组件与 App 顶部显示「第 N 周」；可手动切到任意周预览。
- 单双周：`parity != NONE` 时要求 `week % 2` 匹配。

## 8. 错误处理

| 情况 | 行为 |
| --- | --- |
| 登录态失效 | 小组件显示「登录已过期，点此重新登录」；App 顶部黄色横幅 |
| 网络失败 | 继续用缓存，标注「上次同步：x 分钟前」 |
| 解析结果为空 | 进入诊断页；提供「导出原始 HTML」按钮，便于二次适配 |
| 精确闹钟权限被拒 | 降级为 `setWindow` 非精确提醒 |

## 9. 测试

JVM 单测（`./gradlew test`）：

- `TimetableParserTest`：真实样本（含沉浸式翻译注入与 `<wbr>`）解析出 7 列 14 节；`td id` 坐标正确；多段格子拆成 2 段；单双周解析；`noArrangement` 表解析；空表格不崩。
- `HtmlTextTest`：实体解码、`<br>`/`<wbr>` 处理、GBK 字节解码。
- `WeekCalcTest`：周次推算、跨年、单双周过滤、相邻节合并、今日课程排序。

真机验证：`adb install` → 登录 → 桌面添加小组件 → 对照教务系统网页核对。

## 10. 交付

- 工程：`<仓库根目录>`
- APK：`app/build/outputs/apk/debug/app-debug.apk`
- 本规格文档

## 11. 已知风险

首次解析基于用户提供的**单一真实样本**（2026 秋、14 节、无 rowspan）。若其他学期/专业页面出现 `rowspan` 合并、或「大节课表」(sectionType=COMBINE) 结构差异，解析器按以下顺序兜底：id 坐标 → 表头文本 → 诊断导出人工适配。解析器已内置 rowspan 占位处理。

## 12. 验收记录（2026-09-21）

在 Pixel_7 模拟器（Android 37 / API 37）上做了端到端验收，而非仅「编译通过」。

| # | 验收项 | 方法 | 结果 |
| --- | --- | --- | --- |
| 1 | 解析器正确性 | 40 个 JVM 单测，主样本为真实页面 | 40/40 通过 |
| 2 | 报文真实性 | 独立用正则统计真实页面非空单元格 | 45 段课（9/10/10/4/8/4/0），与解析结果一致 |
| 3 | 抓取链路 | 主机起本地 HTTP 服务返回 **GBK 原始字节**，App 指向 `10.0.2.2:8080` | 缓存写入 `已同步 45 段课`；`htmlBytes=31954` |
| 4 | GBK 解码 | 同上（服务端不声明 charset，靠 meta 嗅探） | 课程名/教师/教室中文全部正确 |
| 5 | 网格渲染 | 截图像素统计 | 106,921 个配色像素命中 7 种课程色；今天列高亮 33,457 px；当前时刻红线 138 px；无错误横幅 |
| 6 | 小组件 | debug-only 广播把 `RemoteViews` 真正 inflate 并测量 | `RESULT=OK measured=508x469 rows=2`，标题「9月21日 周一」、副标题「第 5 周」、首行「第3-4节」 |
| 7 | 单双周 + 合并 | 同上（第 5 周为单周） | `4-18周双周` 的高阶英语1 被正确排除；行序 3/4 合并显示为「第3-4节」 |
| 8 | 真实登录态降级 | release 包装机后直连真实 `jwk.lzu.edu.cn` | 无崩溃，横幅提示「登录已过期，需要重新登录」 |
| 9 | 正式包纯净度 | `aapt2 dump xmltree` / `apksigner verify` | 无 `usesCleartextTraffic`、无自检广播、无 `debuggable`、签名有效 |

### 验收中发现并修复的真实缺陷

初版登录态判定只检查最终 URL 是否含 `sso.lzu.edu.cn`。实测发现未登录时
服务器重定向到 `https://jwk.lzu.edu.cn/manager/coursearrange/login.jsp`（该路径返回 404），
导致 App 报出含糊的「服务器返回 HTTP 404」而非引导登录。

修复：抽出纯函数 `LoginState.needsLogin(requestedUrl, finalUrl, httpCode, html)`，
增加 `login` 路径识别与「被重定向到非原 URL 且状态码异常」判定，并补 6 个单测
（含「同 URL 返回 500 不应误判为登录过期」这类反向用例）。
修复后真机复测显示「登录已过期，需要重新登录」+「去登录」按钮。

**教训**：这个缺陷只有对着真实服务器跑才会暴露 —— 本地 mock 永远返回我预想的跳转路径。

### 用户实测反馈后的第二轮修复

用户直接在手机上看到：登录页只有 Tomcat 的
`文件[/manager/coursearrange/login.jsp]未找到` —— **根本没有登录框，无从登录**。

**根因**：登录入口选错了。未登录访问课表 URL 时教务系统的重定向目标是坏的；
正确入口是门户 `https://jwk.lzu.edu.cn/academic/`（302 → `https://sso.lzu.edu.cn`，实测 200）。

**修复**：
1. `LoginActivity` 改为先加载门户，登录成功回教务系统后再自动跳课表页取源码。
2. 抽出纯函数 `LoginFlow.next(url, bounced, navigated)` 管理导航决策，并补 11 个单测
   —— 这段逻辑有真实死循环风险（门户 ↔ 坏链 login.jsp），且「到达认证页时必须同时清零
   `bounced` 与 `navigated`」这条容易漏（漏了会导致登录成功后不再自动开课表页，已单独写回归测试）。
3. 登录页加 ⋮ 菜单兜底：导入当前页面 / 走认证入口 / 切换桌面版·手机版 / 清空登录状态。

**同时发现的第二个缺陷（更致命）**：真机布局 dump 显示
`action_bar` 占 y 136–283，而 `banner` 在 y 144–292 —— **系统 ActionBar 压在横幅上，
用户点「去登录」实际点到的是 ActionBar**。

**根因**：`targetSdk 35+` 在 Android 15 起强制 edge-to-edge，窗口内容从 (0,0) 铺满，
被系统栏覆盖；而 `Theme.DeviceDefault.DayNight` 自带 ActionBar，正好压在顶部内容上。

**修复**：主题改为 `windowActionBar=false`，自建顶栏 + `PopupMenu`；
新增 `Ui.applyEdgeToEdge()` 用 `setDecorFitsSystemWindows(false)` 配合
`systemBars()|displayCutout()` 的 insets 变成根布局 padding
（挂在 `BaseActivity.setContentView` 之后调用 —— 放 `onCreate` 里 `android.R.id.content`
还没有子 View，等于没生效）。

复测：`headerBar` 从 y=136（状态栏高度）开始，`bannerButton` 完整可见 [891,459][1064,585]，
点击 (978,522) 成功打开认证页，WebView 内实测出现
「兰州大学统一身份认证 / 登录目标系统 / 账号登录 / 换一张 / 登录 / 忘记密码」。

**教训**：验证 UI 只确认「控件存在且有文字」是不够的 —— 必须确认它**没有被遮挡、可点击**。
uiautomator 会正常dump出被覆盖的节点，只看节点存在会漏掉这类问题。

### 第三轮修复：CAS 票据页的明文 HTTP 被拦

用户第二次实测反馈：

```
当前页面：http://jwk.lzu.edu.cn/academic/login/lzu/loginLds6Valid.jsp?ticket=ST-...
网页无法打开　net:ERR_CLEARTEXT_NOT_PERMITTED
```

**根因**：统一身份认证通过后，服务器用**明文 http** 回跳 CAS 票据校验页。
Android 自 targetSdk 28 起默认禁止明文 HTTP，请求被直接拦死 ——
更隐蔽的是服务器本身对该路径会 http→https 重定向，但**第一个明文请求就已被拦**，
重定向根本没机会发生。

**修复**（两处，缺一不可）：
1. 新增 `res/xml/network_security_config.xml`：`base-config` 保持 `false`（其他域名仍强制 HTTPS），
   仅对 `jwk.lzu.edu.cn` 放行明文。**没有**使用全局 `usesCleartextTraffic=true`。
2. `LoginFlow` 增加 `isCasTicketReturn()`：识别票据回跳页并直接跳课表页，
   否则会停在票据页不动。同时把「失效登录页」的判据从宽泛的 `login.jsp` 收紧为
   `/manager/coursearrange/login.jsp`，避免把 `loginLds6Valid.jsp` 误判成坏链。

**验证方式**：debug 构建里加了 `NETCHECK` 探针（用 WebView 请求指定 URL 并回报结果），
做了 A/B 对照 —— 这是关键，只测正向无法区分「策略生效」和「策略压根没启用」：

| 探针 | 结果 | 结论 |
| --- | --- | --- |
| `http://jwk.lzu.edu.cn/.../loginLds6Valid.jsp` | `HTTP_404` 后 `LOADED`（并被重定向到 https） | 明文**已放行** |
| `http://www.baidu.com/`（白名单外） | `net::ERR_CLEARTEXT_NOT_PERMITTED` | 明文**仍被拦** |

单测从 51 增至 56，新增的 5 个覆盖票据页识别、票据页不循环、以及
「新增规则后坏链仍能正确兜回」的回归。

### 第四轮：视觉重做 + 小组件改为时间感知

**用户反馈 A**：「都不错，就是太丑了」。

根因是上一版全是系统默认控件（一排大小不一的灰 `Button`）与纯色矩形平铺的课块。
做法是先立设计系统再重画，而不是零散调参：

- 色板：`page/surface/surface_alt/stroke/hairline` + 文字三档 + 强调色（兰大蓝 `#1E6FD9`），明暗双套
- 标尺：8pt 间距栅格、圆角 16/10/999dp、触摸目标 40dp
- 控件：卡片、胶囊、涟漪（`<ripple>` + oval/rect mask）、彩色圆点、手写 chevron 矢量图
- 课表重画：由「整格高饱和色块」改为 **低饱和底（课程色 13% 透明）+ 左侧 3dp 饱和色条 + 圆角卡片**，
  相邻课程不再粘成一片；今天列改为胶囊表头 + 淡底；当前时刻加红线+圆点并在时间列标出时刻；周末列淡底
- 新增空状态、设置页改为卡片分组、图标改为渐变底 + 课程配色色块

**用户反馈 B**：「小组件显示接下来和正在上的课，而不是今天的」。

原实现把当天全部课静态列出 —— 下午三点还看到「今天第1节 高等数学」毫无意义。
新增纯函数 `WeekCalc.agenda(result, week1Monday, now, lookaheadDays, limit)`：
遍历未来 8 天、过滤已结束的课、标记 ongoing、跨天跨周排序；
配套 `relativeLabel()` 生成「正在上/还剩 22 分/明天/周一」这类相对时间。
**新增 12 个单测**覆盖：跨天顺延、跨周顺延、跳过已结束、limit、以及
「还有 192 分」改成「还有 3 小时」这类文案。

另加 `WidgetTicker`：系统对小组件的定时刷新最短只有 30 分钟，
不足以支撑「还剩 N 分」的时效，因此在每个上课/下课边界各排一次刷新，换天时再补一次。

**验证**：模拟器实测（周一 15:51，第 5 周）小组件遥测返回
`RESULT=OK ... subtitle=第 5 周 · 接下来 2 节+ row0=正在上` ——
此时数学分析（一）14:30–16:10 正在上，被正确识别。
布局 dump 确认无 ActionBar 遮挡、各区块 bounds 正常；截图像素统计非白占 27%、颜色分桶 51 个。

### 第五轮：可滑动小组件 + 6 种风格 + 自定义背景

**反馈 A**：「课程在内部显示不全」。

真 bug：`drawCards` 画文字时**没有任何高度约束** —— 1 节的卡片内高约 52dp，
却会塞进 3 行课名 + 2 行地点，文字直接画到下一格里。
修复：新增 `drawFitted()` 按可用高度逐段排版，放不下的截断并加省略号；
`wrap()` 改为返回 `Wrapped(lines, truncated)`，让调用方能判断是否被截断。

同时补**兜底出口**：手机屏宽 360dp 切成 7 列后每列只剩 40 多 dp，长课名本质上不可能全画下。
新增 `TimetableRenderer.hitTest()` + `TimetableView.onSessionTap`，
点任意课块弹出完整信息（课名/节次/上下课/地点/教师/周次/课序号）。
导出图从 1080px 加宽到 1440px（1440/4 = 360dp，每列约 62dp）便于分享。

**反馈 B**：「图标希望极简风格，一眼清楚」。
原图标是渐变底 + 4 个彩色块，小尺寸下糊成一团。
改为**纯色底 + 白卡片 + 单色网格**（1 竖 2 横），只用 L 命令的路径，零渲染风险。

**反馈 C**：「自己换 6 种风格，自己定义背景」。
- `TimetableRenderer.Style`：6 组参数（底色透明度 / 圆角 / 是否色条 / 是否填充）
- `Backgrounds`：默认 / 纯色（8 预设）/ 相册图片
  - 图片按屏宽缩放后**纵向平铺** —— 课表画布又高又窄，直接 cover 裁切只会露出一条竖缝
  - 叠蒙版（0–90%）保证文字可读；自定义背景时画布不再铺底色
    （否则会盖住图片，蒙版还会叠两次）
  - 相册图用 `takePersistableUriPermission` 持久化授权，重启后仍可读

**反馈 D**：「希望小组件可以滑动来看一天的课」。
静态 `LinearLayout` 高度一到就被裁掉，改成集合视图：
`ListView` + `RemoteViewsService`（`AgendaFactory`），这是安卓可滚动小组件的唯一正路；
`refreshAll()` 里补 `notifyAppWidgetViewDataChanged`。
列表仍按 agenda 排序，一次最多 40 条，滑动即可看完整天乃至后几天。

**验证**（模拟器实测，周一 16:20，第 5 周）：

```
list=present items=21 emptyVisible=false
subtitle=第 5 周 · 今天还有 1 节   footer=刚刚同步 · 可上下滑动
row0=还有 2 小时 / 19:00 | 生物多样性与保护(通识) @ 第9-11节 · 秦岭堂A304
```

数学分析（14:30–16:10）刚下课被正确跳过，「今天还有 1 节」与事实一致。
release 清单确认 `TimetableWidgetService` 与 `BIND_REMOTEVIEWS` 均在位。

**顺带修掉的真实缺陷**：同步失败后 30 分钟内不会自动重试
（失败也会刷新 `status.at`，把「是否陈旧」的判断顶掉了）。
启动瞬间网络未就绪很常见，实测就撞上了。改为「无数据且上次失败」时每次打开都重试。

### 第六轮：修「点重新登录闪退」

用户反馈：点「重新登录」直接闪退。

真机复现拿到堆栈：

```
java.lang.ClassCastException: android.widget.FrameLayout cannot be cast to android.widget.Button
	at app.timetable.LoginActivity.onCreate(LoginActivity.kt:48)
```

根因：第五轮视觉重做时把登录页右上角的「⋮」从 `Button` 换成了 `FrameLayout`
（三个点的圆形按钮），主界面同步改成了 `findViewById<View>`，**登录页漏改**。

**防线尝试 1（失败，已实测）**：以为 Android Lint 的 `WrongViewCast` 能拦住。
把 bug 放回去跑 `:app:lintDebug` → `BUILD SUCCESSFUL`，报告里没有该项。
原因是该检查只覆盖 `findViewById(R.id.x) as T` 形式，
**对显式泛型 `findViewById<T>(R.id.x)` 不生效**。
（反向验证很关键 —— 不验证就会把一个并不存在的防线写进文档。）

**防线尝试 2（成功，已实测）**：启用 ViewBinding，4 个 Activity 全部改为强类型绑定。
反向验证：写 `val probe: Button = binding.loginMenuBtn` → 编译直接失败
`Initializer type mismatch: expected 'Button', actual 'FrameLayout'`。
类型不符从「运行时闪退」变成「编译错误」。

顺带把 `assembleRelease` 挂上 `lintRelease`（lint 仍能抓其他真问题 —— 它当场抓出了
`gradle.properties` 里 Windows 路径未转义的 `PropertyEscape` error）。

**复测**：主界面 → 设置 → 诊断 → 今日课程 → 导出图片 逐屏点击全部正常；
登录页 WebView 完整渲染认证页（统一身份认证 / 账号登录 / 换一张 / 忘记密码）。

**教训**：改布局就必须回归**所有**用到该布局的界面。
第五轮我验证了主界面菜单，却没点登录页 —— 而 bug 恰好只出在漏检的那个界面。

### 第七轮：修「周次无法设置」

用户反馈：周次无法设置。

复现时先排除了两个常见嫌疑 —— 步进器 `- 5 +` 完全正常（点两次 5→7，
派生日期同步更新），「应用」也不是点了没反应。真正的问题是：
**「应用」按钮根本不存在**。

对比设置页的 accessibility 节点列表：「当前设置」之后直接跳到「上课提醒」，
中间那行按钮整行缺失。同一原因还导致「保存链接」「重新登录」「立即同步」也都没渲染。

**根因**：辅助函数签名写坏了 ——

```kotlin
addButtonRow { textButton("应用") { … } }
```

lambda 里的 `textButton(...)` 只是**造出 View 并返回**，从没 `addView`，返回值被丢弃，
所以按钮不在视图树里。界面照常打开、不报任何错，但用户无从操作。

**修复**：改成收 `vararg Pair<String, () -> Unit>`：

```kotlin
buttonRow("应用" to { … }, "跟随今天" to { … })
```

由辅助函数内部完成 addView —— 让「漏 addView」在签名层面不可能发生。

**复测**：5 个按钮全部出现；步进 5→7；点「应用」后 prefs 写入 `week1=2026-08-10`，
主界面显示「第 7 周 9/21–9/27」。

**新增 `tools/smoke.ps1`**：真机冒烟，逐屏断言关键控件**存在**且无崩溃。
这正是本次缺的那道防线 —— 上一轮的冒烟只验证「界面能打开」，而这次界面确实能打开，
只是里面少了按钮。脚本本身还踩了两个坑：长页面必须边滚边找（否则把「在屏幕外」
虚报成「不存在」）；Windows PowerShell 5.1 要求 `.ps1` 带 UTF-8 BOM
（否则中文按 ANSI 解码成乱码，还会吃掉引号导致语法错误）。

**教训**：UI 验证要断言到「控件存在且可操作」，只断言「界面能打开」会整类漏掉。

### 第八轮：小组件两种模式 + 显示起止时间 + 去掉登录过期提示

用户四条反馈：

**A. 「组件应当显示课程的起始和结束时间，同样应用内部也需要」**
- 组件：行右侧改为「起止时间」（`19:00–21:35`），相对提示（`还有 2 小时`）放上一行。
  两种模式共用同一个 `WidgetData.Row` 模型 —— 类型上保证「起止时间」不可能漏掉。
- App：左侧节次列由「节次 + 开始时间」两行改为**三行**（节次 / 开始 / 结束）。
  只给开始时间，学生无法判断这节课上到几点。

**B. 「不用一直提示登录已过期，这个也不需要在组件里提示」**
- 组件里**彻底删除**该分支（`grep loginExpired` 在 widget 包已无任何匹配），
  缓存课表照常显示；只有「完全没有数据」时才引导登录。
- App 里黄条的触发条件从 `loginExpired` 改为**仅当没有任何数据**；
  有数据时只在状态行淡淡加一句「建议重新登录」。

**C. 「显示完整的两节课同时有下滑功能」**
不靠感觉调，而是把尺寸量出来算（自检里给单行做 `measure` 后上报 `rowH`）：

```
chrome（头+脚+内外边距）= 196px = 74.7dp
单行高                  = 118px = 45dp
最小高度 180dp          = 472px
→ (472 − 196) / 118 = 2.34 行
```

正好「两节完整可见 + 第三行露一截」，下滑自然成立。为此把默认高度提到
`targetCellHeight=4 / minHeight=180dp`，并收紧了头脚留白。

**D. 「两个模式通过小组件一个图标小按钮切换」**
- 新增 `Prefs.widgetMode`：0 接下来 / 1 整天
- 小组件右上角加图标按钮（`ic_widget_list`，纯 L 路径矢量 + `setColorFilter` 上色），
  点击广播 `ACTION_TOGGLE_MODE`，在「接下来 ↔ 整天」间切换
- 副标题显示当前模式与条数：`第 7 周 · 今天 3 节` / `第 7 周 · 接下来 22 节`

**实测**（周一 23:00，第 7 周）：

| 模式 | items | 副标题 | 前三条 |
| --- | --- | --- | --- |
| 接下来 | 22 | 第 7 周 · 接下来 22 节 | 明天/08:30–10:10 解析几何 … |
| 整天 | 3 | 第 7 周 · 今天 3 节 | 10:30–12:10 高等代数（一）/ 14:30–16:10 数学分析（一）/ 19:00–21:35 生物多样性与保护(通识) |

整天模式那 3 节与真实课表一致（第 7 周为单周，双周的高阶英语1 被正确排除）；
`modeBtn=present`，来回切换 22 ↔ 3 正常。

### 第九轮：小组件只显示整行 + 关掉渐隐边

用户反馈：「我想让小组件下面的第三节课不露出来，上面课的边框不被遮住」

两个独立问题：

1. **第三节课露半截**：`ListView` 的高度是「剩余空间」，不是行高的整数倍，
   最后一行必然被裁掉一半。
2. **顶部课的边框被遮**：`ListView` 默认开启 fading edge，在顶/底边缘盖一层渐变，
   正好糊住第一行与最后一行的圆角描边。

修复：

```kotlin
val available = widgetHeightDp(context, widgetId) - CHROME_DP
val visibleRows = (available / ROW_SLOT_DP).toInt().coerceIn(1, 4)
views.setViewLayoutHeight(R.id.widget_list, visibleRows * ROW_SLOT_DP, COMPLEX_UNIT_DIP)
```

并补 `onAppWidgetOptionsChanged`，缩放大小时重算；
`android:requiresFadingEdge="none"` 解决第 2 点。

**过程中自检抓到两个会让小组件直接废掉的坑**：

- 先用 `<Space>` 做加权占位 → `InflateException: Error inflating class android.widget.Space`
- 换成 `<View>` → 同样 `Error inflating class android.view.View`

两者都**没有 `@RemoteView` 注解**，不在 RemoteViews 的视图白名单内。
桌面 inflate 失败时不会崩 App，只会让**小组件变空白** —— 静默故障。
最终直接删掉占位控件（列表高度已是整行，余量只剩几 dp）。

**实测**：

```
listH=247px  slot=123px  wholeRows=2.008   ← 整数行，不再切半行
RESULT=OK    modeBtn=present
```

**教训**：RemoteViews 的布局只能用白名单内的类。写小组件布局后必须跑一次
「真正 inflate」的自检（`views.apply()`），这类错误在编译期完全看不出来，
在桌面上也只表现为空白。

### 第十轮：还原小组件尺寸 + 用「实测行高」精确排两整行

用户反馈：「原来的大小合适，你改错了，要显示两节课可滑动。原来不变只是要一个窗口
只显示两节课可滑动」

即：第九轮我把 `minHeight` 从 150dp 提到 180dp、`targetCellHeight` 从 3 提到 4 是**多余的**，
用户要的是**尺寸不动**，只是窗口里正好两节、可滑动。已还原为 `minHeight=150dp / targetCellHeight=3`。

真正的难点是「正好两整行」在 150dp 下要卡得非常准，过程中连踩三个坑：

**坑 1：`setViewLayoutHeight` 对 ListView 的 item 无效。**
`AbsListView` 会用自己生成的 LayoutParams 覆盖掉 item 上设的高度，
所以「把行高固定成槽位高」这条路走不通（自检里 `rowForced == rowNatural` 才暴露出来）。

**坑 2：inflate 出来就量的行高偏小 3–4dp。**
行里的 TextView 是**空的**，空文本量不到真实行高（32dp vs 实际 36dp），
据此算出的列表高度会把第二行裁掉 11dp。修法：measure 前先填上代表性文案。

**坑 3：靠估算槽位高不可靠。**
字体度量/字号缩放/密度不同，行高就不同；估小裁行、估大露头。
最终改为**运行时把行样式 inflate 出来量一次**，用实测值算整行数：

```kotlin
fun naturalSlotDp(context): Float   // inflate + 填文案 + measure，进程内缓存
fun visibleRows(context) = floor(available / naturalSlotDp)
fun listHeightDp(context) = visibleRows(context) * naturalSlotDp(context)
```

并且列表高度**刻意不铺满剩余高度** —— 多出几 dp 就会露出下一行的「头」。

**实测**（150dp 小组件）：

```
列表高度 = 199px (75.81dp)   实测行槽位 = 37.81dp
容纳 = 2.005 行；两行后剩余 0.19dp
✅ 正好两整行，无裁切、无露头
```

**教训**：涉及「正好放下 N 个」的布局，别用常量估算 —— 运行时量一次，成本极低而确定性天差地别。

### 环境踩坑（值得记下）

- `job_kill` 只结束 pwsh 外壳，**python 子进程会存活**并继续占用端口；
  多进程争抢同一端口会把响应体截断（`ERR_CONTENT_LENGTH_MISMATCH`）。
  最后改用单进程 Node 静态服务（`serve/server.js`），并在收尾时按 PID 清理。
- **不要按进程名批量杀进程**：`node: 2 / python: 2` 里可能包含宿主（DSH 自身）进程。
  应精确定位「谁占着 8080 端口」再杀。
- 模拟器截图存在**色彩变换**（纯白不变，但 `#FFC107` → `#F6BA07`），
  像素断言不能用精确相等，否则会得到假阴性。
- 出现「模拟器 WebView 能连通、而 App 的 HttpURLConnection 报 ConnectException」时，
  是**启动瞬间网络未就绪**，不是服务端问题 —— 别急着怀疑服务。
