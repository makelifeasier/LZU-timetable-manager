package app.timetable.debug

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import app.timetable.R
import app.timetable.data.DayOverrides
import app.timetable.data.ParseResult
import app.timetable.data.Prefs
import app.timetable.data.Section
import app.timetable.data.Session
import app.timetable.data.TimetableJson
import app.timetable.data.TimetableRepository
import app.timetable.data.WeekCalc
import app.timetable.greet.GreetingDialog
import app.timetable.greet.Holidays
import app.timetable.greet.LunarCalendar
import app.timetable.greet.QuoteOfDay
import app.timetable.ui.SampleTimetable
import app.timetable.ui.StylePreviewView
import app.timetable.ui.TimetableRenderer
import app.timetable.widget.AgendaFactory
import app.timetable.widget.TodayWidgetProvider
import app.timetable.widget.WidgetData
import java.time.LocalDate

/**
 * 仅 debug 构建包含的自检工具。
 *
 *  - SELFCHECK：把小组件的 RemoteViews 真正 inflate + 测量，验证桌面渲染参数
 *  - NETCHECK ：用 WebView 去请求一个**明文 http** 的教务系统 URL，
 *               验证 network_security_config 的放行规则是否生效
 *               （根因见 res/xml/network_security_config.xml 的注释）
 *
 * 触发：
 *   adb shell am broadcast -a app.timetable.debug.SELFCHECK -n app.timetable.debug/.DebugSelfCheckReceiver --ei h 150
 *
 * 注意 `-n` 后面是 **.DebugSelfCheckReceiver**（不是 .debug.DebugSelfCheckReceiver）：
 * 类名 = 源码包 app.timetable.debug + DebugSelfCheckReceiver，多写一层会解析成不存在的组件，
 * 广播静默地没有任何接收者（`dumpsys activity broadcasts` 里 terminalCount=0），排查很费时间。
 */
class DebugSelfCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val forcedH = intent.getIntExtra(EXTRA_HEIGHT, 0)
        if (intent.action == ACTION_SELFCHECK && forcedH > 0) {
            WidgetData.debugHeightOverrideDp = forcedH
        }
        try {
            when (intent.action) {
                ACTION_NETCHECK -> probeCleartext(context, intent.getStringExtra(EXTRA_URL) ?: DEFAULT_PROBE)
                ACTION_RENDERCHECK -> renderCheck(context)
                ACTION_STYLECHECK -> styleCheck(context)
                ACTION_FEATURECHECK -> featureCheck(context)
                ACTION_SEED -> seedData(context)
                ACTION_PHOTOSEED -> photoSeed(
                    context,
                    intent.getStringExtra("file"),
                    // `am broadcast -e n 8` 传进来的是**字符串** extra，getIntExtra 会拿不到值、
                    // 静默退成默认值（自检里"设了 8 却只造了 2 张"就是这么来的）。
                    // 两个都读，字符串优先。
                    intent.getStringExtra("n")?.trim()?.toIntOrNull()
                        ?: intent.getIntExtra("n", 2)
                )
                ACTION_PHOTOMGR -> photoManagerCheck(context)
                ACTION_AUTOCROPCHECK -> autoCropCheck(context, intent.getStringExtra("n")?.trim()?.toIntOrNull() ?: 3)
                ACTION_PICKFAKE -> pickFake(context, intent.getStringExtra("n")?.trim()?.toIntOrNull() ?: 2)
                ACTION_ASSIST -> assistantCheck(context, intent.getStringExtra("reply"))
                ACTION_AIFAKE -> aiFake(context, intent.getStringExtra("case").orEmpty())
                ACTION_AIDUMP -> assistantDump(
                    context,
                    intent.getStringExtra("date")?.trim()?.let {
                        runCatching { java.time.LocalDate.parse(it) }.getOrNull()
                    }
                )
                ACTION_AICONFIG -> assistantConfig(
                    context,
                    clear = intent.getStringExtra("clear")?.trim() == "1"
                )
                ACTION_PINWIDGET -> pinWidget(context)
                ACTION_CALCHECK -> calendarCheck(context)
                ACTION_CROPCHECK -> cropCheck(context)
                ACTION_WIDGETSHOT -> widgetShot(context, intent.getIntExtra(EXTRA_HEIGHT, 0))
                ACTION_GREETCHECK -> greetCheck(context)
                else -> widgetSelfCheck(context)
            }
        } finally {
            WidgetData.debugHeightOverrideDp = 0
        }
    }

    // --------------------------------------------------------- 课表渲染自检

    /**
     * 按**屏幕真实宽度**渲染一遍课表，统计有多少段文字被截断 / 被压缩。
     *
     * 为什么需要它：手机上看不到的画面问题（"教室显示不全"）没法靠肉眼远程确认，
     * 但"哪段文字在哪一格被截断、可用宽度是多少"是可以量出来的。
     * 修完之后这个数字应当下降 —— 这是可验证的验收标准，而不是"看起来好点了"。
     */
    private fun renderCheck(context: Context) {
        val report = ArrayList<String>()
        try {
            Prefs.init(context)
            val result = TimetableRepository.result()
            val dm = context.resources.displayMetrics
            val density = dm.density
            val width = dm.widthPixels.toFloat()

            // **必须挑一个有课的周次**！
            // 曾经直接用"当前周"渲染 —— 而样本课表在当前周恰好没课，
            // 于是画的是空状态卡片，所有截断计数都成了 0，
            // 看起来"全都没问题"，其实一个字都没画（真被这个坑过）。
            val w0 = Prefs.week1MondayDate()
            val maxWeek = maxOf(result.maxWeek, 20)
            var week = if (w0 != null) WeekCalc.weekOf(LocalDate.now(), w0) else 1
            var best = -1
            for (cand in 1..maxWeek) {
                val n = WeekCalc.weekGrid(result, cand).values.sumOf { it.size }
                if (n > best) { best = n; week = cand }
            }

            val today = LocalDate.now()
            val w1 = Prefs.week1MondayDate()
            val currentWeek = if (w1 != null) WeekCalc.weekOf(today, w1) else 1
            // week / best 已在上面挑好（有课的那一周）
            val h = TimetableRenderer.contentHeight(width, density, result).toInt().coerceAtLeast(1)

            // 同一份数据渲染两遍：先关掉"缩字"（= 修复前的行为），再打开（= 现在）。
            // 这样"救回了几段文字"是量出来的，不是感觉出来的。
            fun pass(shrink: Boolean): Map<String, Int> {
                val rep = ArrayList<String>()
                TimetableRenderer.allowShrink = shrink
                val bmp = Bitmap.createBitmap(width.toInt(), h, Bitmap.Config.ARGB_8888)
                TimetableRenderer.draw(
                    canvas = Canvas(bmp),
                    width = width,
                    density = density,
                    result = result,
                    week = week,
                    dark = false,
                    accent = context.getColor(R.color.accent),
                    todayDay = 0,
                    nowTime = null,
                    weekMonday = w1,
                    seed = Prefs.colorSeed,
                    style = Prefs.style,
                    drawBackground = true,
                    report = rep
                )
                bmp.recycle()
                if (!shrink) report.addAll(rep)
                return mapOf(
                    "课名截断" to rep.count { it.startsWith("课名 被截断") },
                    "教室截断" to rep.count { it.startsWith("教室 被截断") },
                    "教室压缩" to rep.count { it.startsWith("教室 缩到") },
                    "教师截断" to rep.count { it.startsWith("教师 被截断") },
                    "整段放不下" to rep.count { it.contains("放不下") }
                )
            }

            val before = pass(shrink = false)
            val after = pass(shrink = true)
            TimetableRenderer.allowShrink = true

            // 周末底色开关的客观验证：同一高度采样「周六列」和「周三列」的像素。
            // 开 = 两块颜色不同；关 = 完全相同（这就是设置里那两项的差别）。
            fun weekendProbe(tint: Boolean): String {
                val bmp = Bitmap.createBitmap(width.toInt(), h, Bitmap.Config.ARGB_8888)
                TimetableRenderer.draw(
                    canvas = Canvas(bmp), width = width, density = density, result = result,
                    week = week, dark = false, accent = context.getColor(R.color.accent),
                    todayDay = 0, nowTime = null, weekMonday = w1, seed = Prefs.colorSeed,
                    style = Prefs.style, drawBackground = true, weekendTint = tint
                )
                val mt = TimetableRenderer.metrics(width, density, result)
                // 采样点要落在**格子内部**：正好落在 headerH + rowH*n 上会踩到横向网格线
                // （实测就踩过，两次采样都拿到网格线色 eef0f3，看起来像"开关没生效"）
                val y = (mt.headerH + mt.rowH * 6.5f).toInt().coerceIn(1, h - 1)
                val sat = bmp.getPixel((mt.xFor(6) + mt.dayCol / 2f).toInt(), y)
                val wed = bmp.getPixel((mt.xFor(3) + mt.dayCol / 2f).toInt(), y)
                bmp.recycle()
                return "周六=${Integer.toHexString(sat)} 周三=${Integer.toHexString(wed)}"
            }
            val tintOn = weekendProbe(true)
            val tintOff = weekendProbe(false)

            Log.i(
                TAG,
                "RENDERCHECK RESULT=OK 屏宽=${width.toInt()}px(${(width / density).toInt()}dp) " +
                    "density=$density 课程段=${result.sessions.size} " +
                    "当前周=$currentWeek 渲染周=$week 该周课块=$best"
            )
            Log.i(TAG, "RENDERCHECK 修复前(不缩字)= $before")
            Log.i(TAG, "RENDERCHECK 修复后(先缩字)= $after")
            Log.i(TAG, "RENDERCHECK 周末底色 开(淡底色): $tintOn")
            Log.i(TAG, "RENDERCHECK 周末底色 关(同工作日): $tintOff")
            report.take(10).forEach { Log.i(TAG, "RENDERCHECK   样张 $it") }
        } catch (t: Throwable) {
            Log.i(TAG, "RENDERCHECK RESULT=FAIL ${t.javaClass.name}: ${t.message}")
        }
    }

    // --------------------------------------------------------- 小组件自检

    private fun widgetSelfCheck(context: Context) {
        val report = StringBuilder("WIDGET_SELFCHECK ")
        try {
            // 诊断可以强制一个高度（-e h 110），用来量"被压到矮尺寸时会怎样" ——
            // 小米等启动器的格子更小，真机上很容易被压到 100~130dp
            val forced = WidgetData.debugHeightOverrideDp
            val compact = WidgetData.compact(context)
            val chrome = WidgetData.chromeDp(context)
            val views = TodayWidgetProvider.build(context, AppWidgetManager.INVALID_APPWIDGET_ID)
            val root = views.apply(context, null)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST)
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)

            val list = root.findViewById<ViewGroup>(R.id.widget_list)
            val title = root.findViewById<TextView>(R.id.widget_title)?.text?.toString()
            val subtitle = root.findViewById<TextView>(R.id.widget_subtitle)?.text?.toString()
            val empty = root.findViewById<TextView>(R.id.widget_empty)
            val footer = root.findViewById<TextView>(R.id.widget_footer)?.text?.toString()
            val modeBtn = root.findViewById<View>(R.id.widget_mode)

            // 直接跑一遍集合视图工厂，验证「可滑动列表」的每一项也能渲染
            val factory = AgendaFactory(context)
            factory.onCreate()
            factory.onDataSetChanged()
            val count = factory.getCount()
            val rowViews = (0 until minOf(count, 3)).map { i ->
                factory.getViewAt(i).apply(context, null)
            }
            val rowDesc = rowViews.mapIndexed { i, r ->
                val t = r.findViewById<TextView>(R.id.row_time)?.text?.toString()?.replace("\n", "/")
                val n = r.findViewById<TextView>(R.id.row_name)?.text?.toString()
                "#$i $t | $n"
            }.joinToString("  ;  ")

            // 分别量「被固定后的高度」和「内容自然高度」：
            // 前者决定几行能铺满，后者大于前者就会裁字。
            // 注意必须用 EXACTLY —— UNSPECIFIED 会忽略 layoutParams.height，量到的永远是内容高。
            val density = context.resources.displayMetrics.density
            val probe = rowViews.firstOrNull()
            val rowForced = probe?.let { r ->
                val lp = r.layoutParams
                val hSpec = if (lp != null && lp.height > 0) {
                    View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY)
                } else {
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                }
                r.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST), hSpec)
                r.measuredHeight
            } ?: 0
            val rowNatural = probe?.let { r ->
                r.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                r.measuredHeight
            } ?: 0
            val rowH = rowForced

            // 关键回归指标：列表里每一行的实际高度必须**完全一致**。
            // 只要有一行不同（历史 bug：右侧时间栏 1~3 行导致行高跳变），
            // 列表高度就不可能是所有行高的整数倍，底部必然切出半行。
            val heights = rowViews.map { r ->
                val lp = r.layoutParams
                val hSpec = if (lp != null && lp.height > 0) {
                    View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY)
                } else {
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                }
                r.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST), hSpec)
                r.measuredHeight
            }
            val hMin = heights.minOrNull() ?: 0
            val hMax = heights.maxOrNull() ?: hMin

            // 列表高度应当是「整行高度」的整数倍 —— 否则底部会切出半行。
            // 行间距已经烘进行高里（外壳 38dp = 卡片 36dp + 间距 2dp），所以这里**不再加 2dp**：
            // 早先按 margin 额外加 2dp 是错的，ListView 会忽略列表项的 layout_margin。
            val listH = list?.layoutParams?.height ?: -1
            val slot = rowH

            report.append("RESULT=OK")
                .append(" measured=").append(root.measuredWidth).append("x").append(root.measuredHeight)
                .append(" list=").append(if (list != null) "present" else "MISSING")
                .append(" listH=").append(listH)
                .append(" slot=").append(slot)
                .append(" wholeRows=").append(if (slot > 0) listH.toFloat() / slot else -1f)
                .append(" modeBtn=").append(if (modeBtn != null) "present" else "MISSING")
                .append(" items=").append(count)
                .append(" rowH=").append(rowH)
                .append(" rowNatural=").append(rowNatural)
                .append(" clip=").append(rowNatural - rowForced)
                .append(" rowHeights=").append(hMin).append("..").append(hMax)
                .append(" uniform=").append(hMin == hMax)
                .append(" emptyVisible=").append(empty?.visibility == View.VISIBLE)
                .append(" 高度=").append(if (forced > 0) "${forced}dp(强制)" else "自然")
                .append(" compact=").append(compact)
                .append(" chrome=").append(chrome.toInt())
                .append(" footerVisible=")
                .append(root.findViewById<TextView>(R.id.widget_footer)?.visibility == View.VISIBLE)
                .append(" title=").append(title)
                .append(" subtitle=").append(subtitle)
                .append(" footer=").append(footer)
                .append(" rows=[").append(rowDesc).append("]")
        } catch (t: Throwable) {
            // 带上完整堆栈：InflateException 的 message 只到"哪个类"，
            // 真正的原因在 Caused by 里（例如 RemoteViews 的类白名单拦截）
            report.append("RESULT=FAIL ").append(t.javaClass.name).append(": ").append(t.message)
            Log.i(TAG, "WIDGET_SELFCHECK STACK", t)
        }
        Log.i(TAG, report.toString())
    }

    // --------------------------------------------------------- 风格预览自检

    /**
     * 把设置页那 6 种「课表风格」小样张各渲染一遍，用**像素统计**给出可读的结论。
     *
     * 为什么需要：真机上我看不到画面（只有文字），"预览是不是空白""6 种风格是不是真的不一样"
     * 这种问题没法靠肉眼远程确认。而它恰好可以量化：
     *  - 非背景像素占比 → 证明画上了东西（空白会接近 0%）
     *  - 不同颜色数 → 6 种风格之间应当有明显差异（只差 1~2 色说明风格参数没生效）
     * 验收标准：全部风格非底占比 > 10%，且不同风格的色数/占比不全相同。
     */
    private fun styleCheck(context: Context) {
        val w = 620
        val h = 640
        val bg = 0xFFF5F7FA.toInt()
        val sb = StringBuilder("STYLECHECK")
        try {
            val density = context.resources.displayMetrics.density * StylePreviewView.SCALE
            for (i in app.timetable.ui.TimetableRenderer.Style.NAMES.indices) {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(bg)
                TimetableRenderer.draw(
                    canvas = canvas,
                    width = w.toFloat(),
                    density = density,
                    result = SampleTimetable.result(),
                    week = SampleTimetable.WEEK,
                    dark = false,
                    accent = 0xFF1E6FD9.toInt(),
                    todayDay = SampleTimetable.TODAY_DAY,
                    nowTime = null,
                    weekMonday = null,
                    seed = 20260923,
                    style = i,
                    drawBackground = false,
                    weekendTint = true,
                    report = null,
                    minRows = SampleTimetable.result().sections.size
                )
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                val colors = HashSet<Int>()
                var nonBg = 0
                for (p in px) {
                    colors.add(p)
                    if (p != bg) nonBg++
                }
                val pct = nonBg * 100.0 / px.size
                sb.append(" ")
                    .append(TimetableRenderer.Style.NAMES[i])
                    .append("=")
                    .append(String.format(java.util.Locale.CHINA, "%.1f%%", pct))
                    .append("/").append(colors.size).append("色")
                bmp.recycle()
            }
            Log.i(TAG, sb.toString())
        } catch (t: Throwable) {
            Log.i(TAG, "STYLECHECK RESULT=FAIL ${t.javaClass.name}: ${t.message}")
        }
    }

    // --------------------------------------------------------- 新功能自检（每日一句 / 图片 / 节日 / 调课）

    /**
     * 写入一份**合成课表**，让依赖真实数据的自检有东西可测。
     *
     * 为什么需要：模拟器上没登录过教务系统，没有数据时调课自检会 SKIP ——
     * 看起来"通过"，其实什么都没验证。样本课表是合成的（1–25 周全周），
     * 不含任何真实学生的信息。
     */
    private fun seedData(context: Context) {
        try {
            Prefs.init(context)
            val monday = WeekCalc.mondayOf(LocalDate.now())
            Prefs.resultJson = TimetableJson.toJson(realisticSample())
            Prefs.week1Monday = monday.toString()
            Prefs.timetableUrl =
                "http://jwk.lzu.edu.cn/academic/manager/coursearrange/showTimetable.do?seed=1"
            Prefs.firstRunPrompted = true
            // 自检要的是"能直接操作课表页"的稳定状态：让新手引导别在自动化流程里弹出来挡点击
            // （它不是被测对象，而且遮罩会吃掉后面所有 input tap）
            Prefs.guideShown = true
            // 节日祝福默认是关的；自检时打开，好验证"节日当天真的会弹"
            Prefs.greetEnabled = true
            Prefs.greetLastShownDate = ""
            TimetableRepository.reloadFromPrefs(context)
            val r = TimetableRepository.result()
            val week = WeekCalc.weekOf(LocalDate.now(), monday)
            Log.i(
                TAG,
                "SEED 合成课表已写入 段数=${r.sessions.size} 节次=${r.sections.size} " +
                    "第1周周一=$monday 当前周=$week 本周网格段数=" +
                    TimetableRepository.weekGrid(context, week).values.sumOf { it.size } +
                    " 节次表=" + r.sections.joinToString(",") { "${it.index}:${it.label}" }
            )
        } catch (t: Throwable) {
            Log.i(TAG, "SEED RESULT=FAIL ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * 复刻兰大真实页面的**行序陷阱**：中午1节 / 中午2节 也各占一行，
     * 所以「第 5 节」的行序其实是 7。
     *
     * 为什么自检数据要专门造这一点：界面各处存的都是行序，只有加课那一个入口
     * 需要把用户输入的「第几节」翻译成行序。如果种子数据里行序恰好等于节次
     * （样本课表就是如此），这个翻译对不对**根本测不出来**。
     */
    private fun realisticSample(): ParseResult {
        val base = SampleTimetable.result()
        val table = listOf(
            Section(1, "第1节", "08:30", "09:15"),
            Section(2, "第2节", "09:25", "10:10"),
            Section(3, "第3节", "10:30", "11:15"),
            Section(4, "第4节", "11:25", "12:10"),
            Section(5, "中午1节", "12:20", "13:05"),
            Section(6, "中午2节", "13:15", "14:00"),
            Section(7, "第5节", "14:30", "15:15"),
            Section(8, "第6节", "15:25", "16:10"),
            Section(9, "第7节", "16:30", "17:15"),
            Section(10, "第8节", "17:25", "18:10")
        )
        // 把样本里的行序 1..4 原样保留，5 及以后整体后移两行
        fun remap(row: Int) = if (row <= 4) row else row + 2
        return ParseResult(
            term = base.term,
            sections = table,
            sessions = base.sessions.map {
                it.copy(
                    startSection = remap(it.startSection),
                    endSection = remap(it.endSection)
                )
            },
            unscheduled = base.unscheduled
        )
    }

    /**
     * 一次性验证三块新功能的**行为**（不只是"编译通过"）。
     *
     * 当日调课：同一份数据依次套上 替换 / 清空 / 加课 / 恢复，段数必须分别变成
     * 源周段数 / 0 / >=基准 / 回到基准，并且课表页用的 weekGrid 也要跟着变 ——
     * 数据源如果没收口到 sessionsOn，这里立刻露馅。
     * 节日：拿已知日期交叉验证农历（春节必须是正月初一）。
     * 每日一句 / 图片：句子非空、图片数量可读。
     *
     * 自检会写一条临时调课记录再删掉，跑完不留痕。
     */
    private fun featureCheck(context: Context) {
        try {
            Prefs.init(context)
            TimetableRepository.init(context)
            val today = LocalDate.now()
            val w1 = Prefs.week1MondayDate()

            Log.i(TAG, "FEATURECHECK ---- 当日调课 ----")
            if (w1 == null) {
                Log.i(TAG, "FEATURECHECK 调课=SKIP 未设置第 1 周（先发 SEED 广播灌入合成课表）")
            } else {
                val week = WeekCalc.weekOf(today, w1)
                val monday = w1.plusWeeks((week - 1).toLong())
                var bestDay = 1
                var bestDate = monday
                var bestCount = -1
                for (day in 1..7) {
                    val date = monday.plusDays((day - 1).toLong())
                    val n = TimetableRepository.sessionsOn(context, date).size
                    if (n > bestCount) {
                        bestCount = n; bestDay = day; bestDate = date
                    }
                }
                val base = TimetableRepository.sessionsOn(context, bestDate).size
                Log.i(TAG, "FEATURECHECK 基准 第${week}周 周$bestDay $bestDate 段数=$base")

                // 找一个段数和基准不同的源周，"替换"前后才有可见差异
                val result = TimetableRepository.result()

                // 样本课表里除了「高阶英语1」（第 4–18 周的双周）之外都是全周课，
                // 所以"替换"必须挑一个单双周能体现差异的日期来测，否则替换前后一样多，
                // 测试会假通过（第一版就是这么写的，看起来 OK 其实什么都没测）。
                // 第 5 周（单周）周二本来没有课，替换成第 4 周（双周）周二应当出现 1 段。
                val oddDate = w1.plusWeeks(4).plusDays(1)
                val evenDate = w1.plusWeeks(3).plusDays(1)
                val oddBase = TimetableRepository.sessionsOn(context, oddDate).size
                val evenCount = TimetableRepository.sessionsOn(context, evenDate).size
                DayOverrides.put(
                    context, oddDate,
                    DayOverrides.Override(DayOverrides.Mode.REPLACE, sourceWeek = 4)
                )
                val replaced = TimetableRepository.sessionsOn(context, oddDate).size
                DayOverrides.remove(context, oddDate)
                Log.i(
                    TAG,
                    "FEATURECHECK 替换 第5周周二(单周,期望本来 0)=$oddBase " +
                        "换成第4周(双周,该天 $evenCount 段) 得到=$replaced " +
                        (if (oddBase == 0 && replaced == evenCount && replaced > 0) "OK" else "FAIL")
                )

                // 清空 / 加课 / 恢复 用"本周课最多的一天"
                val gridBefore = TimetableRepository.weekGrid(context, week).values.sumOf { it.size }
                DayOverrides.put(context, bestDate, DayOverrides.Override(DayOverrides.Mode.CLEAR))
                val cleared = TimetableRepository.sessionsOn(context, bestDate).size
                val gridCleared = TimetableRepository.weekGrid(context, week).values.sumOf { it.size }

                val extra = Session(
                    name = "自检加课", room = "自检教室", day = bestDay,
                    startSection = 1, endSection = 2
                )
                DayOverrides.put(
                    context, bestDate,
                    DayOverrides.Override(DayOverrides.Mode.ADD, extra = listOf(extra))
                )
                val added = TimetableRepository.sessionsOn(context, bestDate).size
                val gridAdded = TimetableRepository.weekGrid(context, week).values.sumOf { it.size }

                DayOverrides.remove(context, bestDate)
                val restored = TimetableRepository.sessionsOn(context, bestDate).size

                Log.i(TAG, "FEATURECHECK 清空当天 期望=0 实际=$cleared " + (if (cleared == 0) "OK" else "FAIL"))
                Log.i(TAG, "FEATURECHECK 加一节课 期望=${base + 1} 实际=$added " + (if (added == base + 1) "OK" else "FAIL"))
                Log.i(TAG, "FEATURECHECK 恢复原课表 期望=$base 实际=$restored " + (if (restored == base) "OK" else "FAIL"))
                Log.i(
                    TAG,
                    "FEATURECHECK 课表页网格 清空后=$gridCleared(期望 ${gridBefore - base}) " +
                        "加课后=$gridAdded(期望 ${gridBefore + 1}) " +
                        (if (gridCleared == gridBefore - base && gridAdded == gridBefore + 1) "OK" else "FAIL")
                )
            }

            Log.i(TAG, "FEATURECHECK ---- 节日祝福 ----")
            Log.i(
                TAG,
                "FEATURECHECK 开关 总=${Prefs.greetEnabled} 公历=${Prefs.greetSolar} " +
                    "农历=${Prefs.greetLunar} 校历=${Prefs.greetSchool} (期望 false/true/false/false)"
            )
            for (d in listOf(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 17),
                LocalDate.of(2026, 6, 19),
                LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 10, 1),
                today
            )) {
                val hits = Holidays.of(d).joinToString("/") { "${it.kind}:${it.name}" }
                Log.i(TAG, "FEATURECHECK $d ${LunarCalendar.label(d)} 节日=[${hits.ifEmpty { "无" }}]")
            }
            val hitsToday = Holidays.of(today).filter {
                when (it.kind) {
                    Holidays.Kind.SOLAR -> Prefs.greetSolar
                    Holidays.Kind.LUNAR -> Prefs.greetLunar
                    Holidays.Kind.SCHOOL -> Prefs.greetSchool
                }
            }
            Log.i(
                TAG,
                "FEATURECHECK 今日弹窗判定 应弹=" +
                    (Prefs.greetEnabled && hitsToday.isNotEmpty() &&
                        Prefs.greetLastShownDate != today.toString()) +
                    " 命中=${hitsToday.size} 上次=${Prefs.greetLastShownDate.ifEmpty { "从未" }}"
            )

            Log.i(TAG, "FEATURECHECK ---- 小组件扩展 ----")
            val quote = QuoteOfDay.forDate(today)
            Log.i(TAG, "FEATURECHECK 每日一句 开关=${Prefs.quoteEnabled} 长度=${quote.length} 内容=$quote")
            val listed = WidgetData.photoList(context)
            Log.i(
                TAG,
                "FEATURECHECK 图片 开关=${Prefs.photoEnabled} 轮播=${Prefs.photoFlipEnabled}" +
                    "(${Prefs.photoFlipSeconds}秒) 名单=${listed.size} 条 实际存在=" +
                    listed.count { java.io.File(it).exists() } + " 个文件"
            )
            Log.i(
                TAG,
                "FEATURECHECK 余量 覆盖=${Prefs.widgetExtrasOverride} " +
                    "允许扩展=${WidgetData.extrasAllowed(context)} 余量dp=${WidgetData.extraSpaceDp(context).toInt()}"
            )
            Log.i(TAG, "FEATURECHECK DONE")
        } catch (t: Throwable) {
            Log.i(TAG, "FEATURECHECK RESULT=FAIL ${t.javaClass.name}: ${t.message}", t)
        }
    }

    /**
     * 直接弹一次节日祝福卡片（挑一个已知节日），用来验证弹窗本身能正常显示。
     *
     * 为什么不走 HolidayGreeter：它按"今天"判定，而模拟器是 production build
     * 改不了系统日期 —— 逢不上节日就永远看不到弹窗。这里绕过日期判断，
     * 用 10 月 1 日（国庆，公历类默认开）直接把卡片调起来。
     */
    private fun greetCheck(context: Context) {
        try {
            Prefs.init(context)
            val date = LocalDate.of(2026, 10, 1)
            val hits = Holidays.of(date)
            Log.i(
                TAG,
                "GREETCHECK 日期=$date ${LunarCalendar.label(date)} " +
                    "命中=[${hits.joinToString("/") { it.name }}] 祝福语长度=${hits.firstOrNull()?.greeting?.length}"
            )
            val activity = currentActivity()
            if (activity == null) {
                Log.i(TAG, "GREETCHECK RESULT=FAIL 没有前台 Activity（先把 App 打开再发广播）")
                return
            }
            GreetingDialog.show(activity, date, hits)
            Log.i(TAG, "GREETCHECK RESULT=OK 已调用 GreetingDialog.show")
        } catch (t: Throwable) {
            Log.i(TAG, "GREETCHECK RESULT=FAIL ${t.javaClass.name}: ${t.message}", t)
        }
    }

    /** 反射拿前台 Activity：debug-only 的取巧做法，release 包里没有这段 */
    private fun currentActivity(): android.app.Activity? = try {
        val cls = Class.forName("android.app.ActivityThread")
        val thread = cls.getMethod("currentActivityThread").invoke(null)
        val activities = cls.getDeclaredField("mActivities").apply { isAccessible = true }
            .get(thread) as Map<*, *>
        activities.values.firstNotNullOfOrNull { record ->
            val recordCls = record!!.javaClass
            val paused = recordCls.getDeclaredField("paused").apply { isAccessible = true }
                .getBoolean(record)
            if (paused) null else recordCls.getDeclaredField("activity").apply { isAccessible = true }
                .get(record) as? android.app.Activity
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * 造若干张图放进小组件图片目录，并打开"图片 + 每日一句"两个开关。
     *
     * 为什么要在 App 里生成而不是 `adb push`：图片必须是**我们自己的包目录**下的文件，
     * 才能复现"启动器跨进程读私有文件"这条路径；生成比 push + chmod 可靠得多。
     *
     * @param count 造几张（默认 2）。上限提到 50 张之后，需要"很多张图"才能验
     *              「管理图片」的缩略图网格与多选删除（发 `-e n 8`）。上限夹到 [MAX_SEED]
     *              以免自检本身耗时太久（每张都要画条纹 + 压 JPEG）。
     */
    private fun photoSeed(context: Context, realFile: String? = null, count: Int = 2) {
        try {
            Prefs.init(context)
            val dir = java.io.File(context.filesDir, "photos").apply { mkdirs() }
            val n = count.coerceIn(1, MAX_SEED)
            // 两张一循环的**颜色明显不同**的图：这样"点一下换下一张"能用像素验证
            // （第 1 张纯蓝、第 2 张纯绿 —— 采样点里蓝绿比例互换，就说明真的换了）
            val colors = listOf(0xFF1E6FD9.toInt(), 0xFF2E9E5B.toInt())
            val made = (1..n).map { k ->
                val fileName = "seed$k.jpg"
                val bgColor = colors[(k - 1) % colors.size]
                val label = "PHOTO $k"
                val f = java.io.File(dir, fileName)
                // 640×204 ≈ 3.14:1 —— 与 4×2 组件上"按裁剪框裁好"的照片同形状。
                //
                // 画成**垂直条纹**而不是纯色：纯色图被"缩小再放大"也还是纯色，
                // 根本看不出有没有模糊底；有条纹时条纹被糊掉是能**量**出来的
                // （相邻列的色差会骤降）。这是"模糊块真的没了吗"唯一的客观判据。
                val bmp = Bitmap.createBitmap(640, 204, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(bgColor)
                val stripe = android.graphics.Paint().apply {
                    color = 0xFFFFFFFF.toInt()
                    style = android.graphics.Paint.Style.FILL
                }
                var sx = 0f
                while (sx < 640f) {
                    canvas.drawRect(sx, 0f, sx + 8f, 204f, stripe)
                    sx += 16f
                }
                canvas.drawText(
                    label,
                    150f,
                    130f,
                    android.graphics.Paint().apply {
                        color = 0xFF000000.toInt()
                        textSize = 56f
                        isFakeBoldText = true
                    }
                )
                java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
                f.absolutePath
            }

            // photoUris 存的是**绝对路径**，每行一个（见 WidgetPhotos）
            // realFile：用一张真实尺寸的照片来复现（生成的小图测不出"大图/竖图"这类问题）
            val real = realFile?.let { java.io.File(context.filesDir, it) }
            Prefs.photoUris = if (real != null && real.isFile) {
                Log.i(TAG, "PHOTOSEED 用真实照片 ${real.absolutePath} (${real.length()} 字节)")
                real.absolutePath
            } else {
                made.joinToString("\n")
            }
            Prefs.photoEnabled = true
            Prefs.quoteEnabled = true
            // 「自动」档 = 用户实际用的档位。以前自检把它设成"强制显示"，是为了在矮组件上也能看到图，
            // 但那会绕过余量判断 —— 现在要验的恰恰是"余量不足时会怎样"，所以必须用自动档。
            Prefs.widgetExtrasOverride = 0
            // 种完立刻刷一次组件，否则桌面还停在上一张/空状态（自检会读不到新数值）
            TodayWidgetProvider.refreshAll(context)
            Log.i(
                TAG,
                "PHOTOSEED 已生成 ${made.size} 张图 photoEnabled=${Prefs.photoEnabled} " +
                    "quoteEnabled=${Prefs.quoteEnabled} photoList=${WidgetData.photoList(context)}"
            )
        } catch (t: Throwable) {
            Log.i(TAG, "PHOTOSEED RESULT=FAIL ${t.javaClass.name}: ${t.message}", t)
        }
    }

    /**
     * 把小组件真的**放到桌面上**。
     *
     * 为什么非做不可：小组件的图片是由**启动器进程**去读取的，而自检是在我们自己的进程里
     * inflate 的 —— 同一个 uid 读自己私有目录当然成功，所以"自检通过"完全掩盖了
     * "启动器读不到"这类问题（图片一直空白就是这么漏掉的）。
     * 只有真的钉到桌面上，才能看到宿主进程来取图时的真实调用方 uid。
     */
    private fun pinWidget(context: Context) {
        try {
            val mgr = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, TodayWidgetProvider::class.java)
            val ok = mgr.requestPinAppWidget(provider, null, null)
            Log.i(TAG, "PINWIDGET requestPinAppWidget 返回=$ok（true 后系统会弹确认框，需要点一下「添加」）")
        } catch (t: Throwable) {
            Log.i(TAG, "PINWIDGET RESULT=FAIL ${t.javaClass.name}: ${t.message}", t)
        }
    }

    // --------------------------------------------------------- 全年日历自检

    /**
     * 把设置页那个「全年日历」按**真机屏幕宽度**渲染一遍，导出成 PNG 并统计像素。
     *
     * 为什么非要这么做：日历是 Canvas 自绘控件，uiautomator 看不到它里面的任何东西
     * （格子里写了什么节、字够不够大，dump 里全是空的）。而"画出来没有"恰恰是
     * 单测查不到的 —— 单测只能验几何数字。所以这里直接渲染 + 数像素 + 导出图片，
     * 人也能打开看。
     *
     * 导出位置：`/sdcard/Android/data/<包名>/files/calendar-check.png`（adb pull 可直接取）。
     */
    private fun calendarCheck(context: Context) {
        try {
            Prefs.init(context)
            val width = context.resources.displayMetrics.widthPixels
            val view = app.timetable.ui.YearCalendarView(context).apply {
                setGreetFlags(true, true, true)
            }
            // 按真实屏宽量一遍（设置页里的可用宽度会略小，这里取整屏宽已经够判断可读性）
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val h = view.measuredHeight.coerceAtLeast(1)
            view.layout(0, 0, width, h)

            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(0xFFFFFFFF.toInt())
            view.draw(canvas)

            val px = IntArray(width * h)
            bmp.getPixels(px, 0, width, 0, 0, width, h)
            var nonBg = 0
            var dark = 0
            val colors = HashSet<Int>()
            for (p in px) {
                colors.add(p)
                if (p != 0xFFFFFFFF.toInt()) nonBg++
                // 深色像素 ≈ 文字（网格线是浅灰，圆点是彩色的）
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r < 120 && g < 120 && b < 120) dark++
            }
            val out = java.io.File(context.getExternalFilesDir(null), "calendar-check.png")
            java.io.FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(
                TAG,
                "CALCHECK 尺寸=${width}x$h px（${(width / context.resources.displayMetrics.density).toInt()}dp 宽）" +
                    " 非背景=${"%.1f".format(nonBg * 100.0 / px.size)}%" +
                    " 深色(文字)=${"%.1f".format(dark * 100.0 / px.size)}% 颜色数=${colors.size}" +
                    " 导出=${out.absolutePath} (${out.length()} 字节)"
            )
            bmp.recycle()
        } catch (t: Throwable) {
            Log.i(TAG, "CALCHECK RESULT=FAIL ${t.javaClass.name}: ${t.message}", t)
        }
    }

    // --------------------------------------------------------- 裁剪界面自检

    /**
     * 直接把「导入时的裁剪界面」拉起来，用它自检用的那张图。
     *
     * 为什么需要：这是个**全新的界面**，正常入口藏在"设置 → 选图片 → 系统相册"后面，
     * 自动化点不进去。而新界面第一次上真机的失败方式（构造参数、View 层次、
     * 图片解码路径）恰恰是单测查不到的 —— 单测里 Canvas/Bitmap 全是假实现。
     * 这里只负责"能不能正常打开"，观感还是得人看。
     */
    private fun cropCheck(context: Context) {
        try {
            val activity = currentActivity()
            if (activity == null) {
                Log.i(TAG, "CROPCHECK RESULT=FAIL 没有前台 Activity（先把 App 打开再发广播）")
                return
            }
            val f = java.io.File(context.filesDir, "realfoto.png").takeIf { it.isFile }
                ?: java.io.File(context.filesDir, "photos/seed1.jpg")
            if (!f.isFile) {
                Log.i(TAG, "CROPCHECK RESULT=FAIL 找不到可用的测试图（先发 PHOTOSEED）")
                return
            }
            Log.i(TAG, "CROPCHECK 打开裁剪界面，用图 ${f.absolutePath} (${f.length()} 字节)")
            app.timetable.ui.PhotoCropDialog.start(activity, listOf(android.net.Uri.fromFile(f))) { saved ->
                Log.i(TAG, "CROPCHECK 裁剪流程结束，保存了 ${saved.size} 张：$saved")
            }
        } catch (t: Throwable) {
            Log.i(TAG, "CROPCHECK RESULT=FAIL ${t.javaClass.name}: ${t.message}", t)
        }
    }

    // --------------------------------------------------------- 图片管理界面自检

    /**
     * 直接把「管理图片」界面拉起来（不经过"设置 → 滚动 → 点进去"）。
     *
     * 为什么需要：这个界面是代码里拼出来的（格数随张数变化），失败方式全在 View 层次上
     * —— 缩略图解码、格子高度、选中态、删除确认，单测里一个都碰不到（Canvas/BitmapFactory
     * 在单测里都是假实现）。这里只保证"能正常打开、格子数对"，剩下的靠 uiautomator 点。
     */
    private fun photoManagerCheck(context: Context) {
        try {
            val activity = currentActivity()
            if (activity == null) {
                Log.i(TAG, "PHOTOMGR RESULT=FAIL 没有前台 Activity（先把 App 打开再发广播）")
                return
            }
            val recorded = app.timetable.widget.WidgetPhotos.recorded(context)
            Log.i(TAG, "PHOTOMGR 打开管理界面，记录里 ${recorded.size} 张：$recorded")
            app.timetable.ui.PhotoManagerDialog.show(
                activity = activity,
                onDeleted = { deleted, remaining ->
                    Log.i(TAG, "PHOTOMGR 删掉 $deleted 张，剩 $remaining 张")
                },
                onClosed = {
                    Log.i(TAG, "PHOTOMGR 界面已关闭，记录现在剩 " +
                        "${app.timetable.widget.WidgetPhotos.recorded(context).size} 张")
                }
            )
        } catch (t: Throwable) {
            Log.i(TAG, "PHOTOMGR RESULT=FAIL ${t.javaClass.name}: ${t.message}", t)
        }
    }

    // --------------------------------------------------------- 批量自动裁剪自检

    /**
     * 跑一遍**批量自动裁剪**（`WidgetPhotos.importAutoCropped`）：把自检用的那张图反复导入 [count] 次。
     *
     * 为什么需要：这条路径不经过裁剪界面，用户也点不进来（它藏在"一次多选 ≥2 张"后面），
     * 而它自己算裁剪矩形（`PhotoCrop.centerCoverFraction` → `pixelRect` → `createBitmap`），
     * 算错一格就会崩在 `Bitmap.createBitmap`。单测只能验纯函数，这里验的是**真图上的结果**：
     * 日志会把每张新图的尺寸与比例打出来，可以拿它和组件图片框的比例对数。
     */
    private fun autoCropCheck(context: Context, count: Int) {
        try {
            val activity = currentActivity()
            if (activity == null) {
                Log.i(TAG, "AUTOCROPCHECK RESULT=FAIL 没有前台 Activity（先把 App 打开再发广播）")
                return
            }
            val src = java.io.File(context.filesDir, "realfoto.png").takeIf { it.isFile }
                ?: java.io.File(context.filesDir, "photos/seed1.jpg")
            if (!src.isFile) {
                Log.i(TAG, "AUTOCROPCHECK RESULT=FAIL 找不到可用的测试图（先发 PHOTOSEED）")
                return
            }
            val n = count.coerceIn(1, 20)
            val uris = (1..n).map { android.net.Uri.fromFile(src) }
            val plan = app.timetable.ui.PhotoCropDialog.plan(activity)
            Log.i(
                TAG,
                "AUTOCROPCHECK 开始：源 ${src.name}(${src.length()}B) × $n 张，" +
                    "图片框=${plan.frame.label} 存盘尺寸=${plan.outWidth}x${plan.outHeight}"
            )
            val before = app.timetable.widget.WidgetPhotos.recorded(context).size
            // 这条路径在生产里是在后台线程跑的，自检为了日志顺序同步跑（单次调用只处理几张）
            val saved = app.timetable.widget.WidgetPhotos.importAutoCropped(
                context = context,
                uris = uris,
                frame = plan.frame,
                outWidth = plan.outWidth,
                outHeight = plan.outHeight
            )
            val after = app.timetable.widget.WidgetPhotos.recorded(context).size
            for (path in saved) {
                val bounds = app.timetable.widget.PhotoBitmap.boundsOf(path)
                val ratio = if (bounds != null && bounds[1] > 0) {
                    bounds[0].toFloat() / bounds[1].toFloat()
                } else {
                    -1f
                }
                Log.i(
                    TAG,
                    "AUTOCROPCHECK 产出 ${java.io.File(path).name} " +
                        "${bounds?.get(0)}x${bounds?.get(1)} 比例=${
                            String.format("%.3f", ratio)
                        } 框比例=${String.format("%.3f", plan.frame.ratio)}"
                )
            }
            Log.i(
                TAG,
                "AUTOCROPCHECK RESULT=OK 新留 ${saved.size}/$n 张，记录 $before → $after 张"
            )
        } catch (t: Throwable) {
            Log.i(TAG, "AUTOCROPCHECK RESULT=FAIL ${t.javaClass.name}: ${t.message}", t)
        }
    }

    // --------------------------------------------------------- 多选导入链路自检

    /**
     * 假装「用户一次从相册选了好几张」，把结果直接喂给设置页的 `onActivityResult`。
     *
     * 为什么需要：一次选 ≥2 张时会弹出「逐张裁剪 / 自动裁剪」的选择框，而这条路
     * **自动化点不进去** —— 系统相册（DocumentsUI）的多选在 Recent 视图里不可用，
     * 换目录又要按机型和系统版本点不同入口。这里只伪造"相册返回的结果"，
     * 后面**走的是与真实用户完全相同的代码**（同一个 requestCode、同一个 onActivityResult、
     * 同一个选择框、同一条批量导入路径）。
     *
     * 图片用 App 私有目录里的文件（`file://` URI）：与 App 同 uid，读得到；
     * 换成 `/sdcard` 下的路径反而会因为 App 没有存储权限而读不出 —— 那是 URI 的问题，
     * 不是被验代码的问题。
     */
    private fun pickFake(context: Context, count: Int) {
        try {
            val activity = currentActivity()
            if (activity == null) {
                Log.i(TAG, "PICKFAKE RESULT=FAIL 没有前台 Activity")
                return
            }
            val sources = listOf("realfoto.png", "photos/seed1.jpg", "photos/seed2.jpg")
                .map { java.io.File(context.filesDir, it) }
                .filter { it.isFile }
            if (sources.isEmpty()) {
                Log.i(TAG, "PICKFAKE RESULT=FAIL 找不到可用的测试图（先发 PHOTOSEED）")
                return
            }
            val n = count.coerceIn(1, 3)
            val chosen = (0 until n).map { sources[it % sources.size] }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*" }
            if (n == 1) {
                intent.data = android.net.Uri.fromFile(chosen[0])
            } else {
                val clip = android.content.ClipData.newRawUri(
                    "picked", android.net.Uri.fromFile(chosen[0])
                )
                chosen.drop(1).forEach {
                    clip.addItem(android.content.ClipData.Item(android.net.Uri.fromFile(it)))
                }
                intent.clipData = clip
            }
            Log.i(TAG, "PICKFAKE 伪造相册返回 $n 张：${chosen.map { it.name }}（Activity=${activity.javaClass.simpleName}）")
            val method = android.app.Activity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java
            )
            method.isAccessible = true
            method.invoke(activity, REQ_PHOTOS_FAKE, android.app.Activity.RESULT_OK, intent)
            Log.i(TAG, "PICKFAKE RESULT=OK 已把结果交给设置页（应弹出「逐张裁剪／自动裁剪」选择框）")
        } catch (t: Throwable) {
            Log.i(TAG, "PICKFAKE RESULT=FAIL ${t.javaClass.name}: ${t.message}", t)
        }
    }

    // --------------------------------------------------------- AI 助手自检

    /**
     * 打开「AI 助手」对话框，用**假模型**（[InjectedAssistantModel]）替换真模型。
     *
     * 为什么必须能替换：真 Key 只有用户有，而"解析 → 解释 → 预览 → 应用 → 撤销"整条链路
     * 都要在设备上验。假模型走的是**同一个接口**，所以除"谁来生成那段文本"之外，
     * 后面全是生产代码。
     *
     * `-e reply "PLAN…"` 可以顺带注入这次要返回的文本（不注入就是"没配 Key"那种初始态，
     * 用来验引导界面）。
     */
    private fun assistantCheck(context: Context, reply: String?) {
        try {
            val activity = currentActivity()
            if (activity == null) {
                Log.i(TAG, "ASSIST RESULT=FAIL 没有前台 Activity（先把 App 打开再发广播）")
                return
            }
            if (!reply.isNullOrBlank()) {
                FakeAssistantModel.reply = reply
                Log.i(TAG, "ASSIST 已注入回复 ${reply.length} 字：${reply.take(120)}")
            }
            Log.i(
                TAG,
                "ASSIST 打开助手对话框（假模型）· 已配 Key=${app.timetable.data.AssistantConfig.hasKey(context)}" +
                    " 已同意=${app.timetable.data.AssistantConfig.consented(context)}" +
                    " 可撤销=${app.timetable.ai.AssistantUndo.available(context)}"
            )
            app.timetable.ui.AssistantDialog.show(
                activity = activity,
                model = InjectedAssistantModel(),
                onDone = { assistantDump(context) }
            )
        } catch (t: Throwable) {
            Log.i(TAG, "ASSIST RESULT=FAIL ${t.javaClass.name}: ${t.message}", t)
        }
    }

    /**
     * 用回环假服务把 `ai/AssistantClient` 的**真实 socket 链路**跑一遍。
     *
     * 场景：`ok` / `401` / `402` / `429` / `500` / `timeout`。
     * 断言落在日志里：请求头有没有 `Authorization`、请求体里有没有 `model` / `temperature` /
     * `thinking`（DeepSeek 专有字段）、有没有把 Key 写进**请求体**（必须没有）、
     * 以及各错误码被翻译成什么话。
     */
    private fun aiFake(context: Context, caseName: String) {
        // 两条额外模式：raw=裸 socket 回环自测（定位"卡在哪一步"）、host=打宿主机上的假服务
        when (caseName.trim().lowercase()) {
            "raw" -> {
                Log.i(TAG, "AIFAKE 裸 socket 回环自测开始")
                FakeHttp.rawSocketCheck { line -> Log.i(TAG, "AIFAKE-RAW 结论：$line") }
                return
            }

            "host", "10.0.2.2" -> {
                Log.i(TAG, "AIFAKE 打宿主机假服务（10.0.2.2:8877）")
                FakeHttp.callHost(8877) { line -> Log.i(TAG, "AIFAKE-HOST 结论：$line") }
                return
            }
        }
        val agent = when (caseName.trim().lowercase()) {
            "401", "badkey" -> FakeHttp.Case.BAD_KEY
            "402", "nobalance" -> FakeHttp.Case.NO_BALANCE
            "429", "ratelimit" -> FakeHttp.Case.RATE_LIMIT
            "500", "server" -> FakeHttp.Case.SERVER_ERROR
            "timeout" -> FakeHttp.Case.TIMEOUT
            else -> FakeHttp.Case.OK
        }
        Log.i(TAG, "AIFAKE 开始，场景=${if (caseName.isBlank()) "ok" else caseName}")
        FakeHttp.run(
            agent = agent,
            readTimeoutMs = if (agent == FakeHttp.Case.TIMEOUT) 1_000 else 60_000
        ) { line ->
            Log.i(TAG, "AIFAKE 完成：$line")
        }
    }

    /**
     * 写一份**假配置**（dummy Key + 已同意），或者清掉它。
     *
     * 为什么需要：真 Key 只有用户有，而"对话框在有配置时会走哪条路、没配置时会不会发请求"
     * 这两件事必须在设备上各验一次。这里写的 `sk-test-dummy` 是**假 Key**，
     * 配合 `ASSIST` 的假模型时根本不会出网；即使出网也只会被服务商拒绝 —— 不碰任何真实额度。
     */
    private fun assistantConfig(context: Context, clear: Boolean) {
        try {
            if (clear) {
                app.timetable.data.AssistantConfig.clearKey(context)
                app.timetable.data.AssistantConfig.setConsented(context, false)
                Log.i(TAG, "AICONFIG 已清空 Key 与同意状态")
            } else {
                app.timetable.data.AssistantConfig.save(
                    context = context,
                    provider = app.timetable.data.AssistantConfig.Provider.DEEPSEEK,
                    baseUrl = app.timetable.data.AssistantConfig.DEEPSEEK_BASE,
                    model = app.timetable.data.AssistantConfig.DEEPSEEK_MODEL,
                    key = "sk-test-dummy"
                )
                app.timetable.data.AssistantConfig.setConsented(context, true)
                Log.i(TAG, "AICONFIG 已写入假配置（dummy Key，仅供自检）")
            }
            Log.i(
                TAG,
                "AICONFIG 现在：ready=${app.timetable.data.AssistantConfig.ready(context)} " +
                    "掩码=${app.timetable.data.AssistantConfig.maskedKey(context).ifEmpty { "(空)" }} " +
                    "端点=${app.timetable.data.AssistantConfig.endpoint(context)}"
            )
        } catch (t: Throwable) {
            Log.i(TAG, "AICONFIG RESULT=FAIL ${t.javaClass.name}: ${t.message}", t)
        }
    }

    /** 把助手相关的状态打出来（撤销、当日覆盖、自定义课程、今天生效的课表） */
    private fun assistantDump(context: Context, intentDate: java.time.LocalDate? = null) {        try {
            Prefs.init(context)
            TimetableRepository.init(context)
            val today = java.time.LocalDate.now()
            val overrides = app.timetable.data.DayOverrides.all(context)
            val courses = app.timetable.data.UserCourses.all(context)
            Log.i(
                TAG,
                "AIDUMP 撤销可用=${app.timetable.ai.AssistantUndo.available(context)} " +
                    "（${app.timetable.ai.AssistantUndo.describe(context) ?: "-"}）"
            )
            Log.i(TAG, "AIDUMP 当日覆盖 ${overrides.size} 条：" + overrides.entries.joinToString(" | ") { (d, o) ->
                "$d=${o.mode}" + (if (o.mode == app.timetable.data.DayOverrides.Mode.REPLACE) "(第${o.sourceWeek}周 星期${if (o.sourceDay == 0) "同" else o.sourceDay})" else "") +
                    (if (o.extra.isNotEmpty()) "[${o.extra.joinToString(",") { it.name + ":" + it.startSection + "-" + it.endSection }}]" else "")
            })
            Log.i(TAG, "AIDUMP 自定义课程 ${courses.size} 门：" + courses.joinToString(" | ") { it.session.name + "@" + it.session.day + ":" + it.session.startSection + "-" + it.session.endSection })
            Log.i(
                TAG,
                "AIDUMP 今天（$today）生效：" + TimetableRepository.sessionsOn(context, today)
                    .joinToString(" | ") { it.name + ":" + it.startSection + "-" + it.endSection }
            )
            // 全量课表：自检要挑一个"本来有课"的日期来做替换，否则改动看不出来
            Log.i(
                TAG,
                "AIDUMP 全部课程：${TimetableRepository.result().sessions.joinToString(" | ") {
                    it.name + " 星期" + it.day + " 行" + it.startSection + "-" + it.endSection +
                        " 周" + it.weeks.start + "-" + it.weeks.end
                }}"
            )
            // 指定日期的生效结果：`.AIDUMP -e date 2026-10-15` —— 这是课表页/组件/导出/提醒共用的唯一入口
            intentDate?.let { d ->
                Log.i(
                    TAG,
                    "AIDUMP $d 生效：" + TimetableRepository.sessionsOn(context, d)
                        .joinToString(" | ") { it.name + ":" + it.startSection + "-" + it.endSection }
                )
            }
            Log.i(
                TAG,
                "AIDUMP 配置：服务商=${app.timetable.data.AssistantConfig.provider(context)} " +
                    "地址=${app.timetable.data.AssistantConfig.baseUrl(context)} " +
                    "模型=${app.timetable.data.AssistantConfig.model(context)} " +
                    "有Key=${app.timetable.data.AssistantConfig.hasKey(context)} " +
                    "掩码=${app.timetable.data.AssistantConfig.maskedKey(context).ifEmpty { "(空)" }} " +
                    "已同意=${app.timetable.data.AssistantConfig.consented(context)}"
            )
        } catch (t: Throwable) {
            Log.i(TAG, "AIDUMP RESULT=FAIL ${t.javaClass.name}: ${t.message}", t)
        }
    }

    // --------------------------------------------------------- 小组件出图自检

    /**
     * 把小组件**真的画成一张位图**并导出，同时读回图片框四角的像素。
     *
     * 为什么必须这么验：圆角遮罩是在位图上画的（`PhotoBitmap.maskCorners`），
     * 而单测里 `Canvas`/`Path` 全是假实现、`Path.op` 甚至返回 false ——
     * "遮罩到底画上去了没有"单测**根本测不了**。这里走的是生产同一条 inflate/measure/draw 链路，
     * 导出的图人也能直接打开看。
     *
     * 导出位置：`/sdcard/Android/data/<包名>/files/widget-shot.png`
     */
    private fun widgetShot(context: Context, forcedH: Int) {
        try {
            Prefs.init(context)
            TimetableRepository.init(context)
            if (forcedH > 0) WidgetData.debugHeightOverrideDp = forcedH
            try {
                val views = TodayWidgetProvider.build(context, AppWidgetManager.INVALID_APPWIDGET_ID)
                val root = views.apply(context, null)
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST)
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)

                val w = root.measuredWidth.coerceAtLeast(1)
                val h = root.measuredHeight.coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bmp))

                // 图片框的位置与四角像素
                val area = root.findViewById<View>(R.id.widget_photo_area)
                val info = if (area != null && area.visibility == View.VISIBLE && area.width > 0) {
                    // 往内缩 2px：圆角边缘本身是抗锯齿的，踩在弧线上取样会得到中间色
                    val inset = 2
                    val c = listOf(
                        "左上" to intArrayOf(area.left + inset, area.top + inset),
                        "右上" to intArrayOf(area.right - inset, area.top + inset),
                        "左下" to intArrayOf(area.left + inset, area.bottom - inset),
                        "右下" to intArrayOf(area.right - inset, area.bottom - inset),
                        "正中" to intArrayOf(area.left + area.width / 2, area.top + area.height / 2)
                    ).joinToString(" ") { (name, xy) ->
                        val p = bmp.getPixel(
                            xy[0].coerceIn(0, w - 1),
                            xy[1].coerceIn(0, h - 1)
                        )
                        "$name=#%08X".format(p)
                    }
                    "图片框=[${area.left},${area.top}][${area.right},${area.bottom}] $c"
                } else {
                    "图片框=不可见"
                }

                val out = java.io.File(context.getExternalFilesDir(null), "widget-shot.png")
                java.io.FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                Log.i(
                    TAG,
                    "WIDGETSHOT ${w}x$h px 高度=${if (forcedH > 0) "${forcedH}dp(强制)" else "自然"} " +
                        "导出=${out.absolutePath}(${out.length()} 字节) $info"
                )
            } finally {
                WidgetData.debugHeightOverrideDp = 0
            }
        } catch (t: Throwable) {
            Log.i(TAG, "WIDGETSHOT RESULT=FAIL ${t.javaClass.name}: ${t.message}", t)
        }
    }

    // --------------------------------------------------------- 明文策略自检

    private fun probeCleartext(context: Context, url: String) {
        Log.i(TAG, "NETCHECK start $url")
        try {
            val web = WebView(context)
            web.settings.javaScriptEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                    Log.i(TAG, "NETCHECK pageStarted $u")
                }

                override fun onPageFinished(view: WebView, u: String) {
                    Log.i(TAG, "NETCHECK RESULT=LOADED url=$u  (明文未被拦截)")
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    Log.i(
                        TAG,
                        "NETCHECK RESULT=HTTP_${errorResponse.statusCode} url=${request.url}" +
                            "  (明文未被拦截，服务器返回错误页)"
                    )
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (!request.isForMainFrame) return
                    Log.i(
                        TAG,
                        "NETCHECK RESULT=ERROR code=${error.errorCode} desc=${error.description}" +
                            " url=${request.url}  (若 desc 含 CLEARTEXT 则策略未生效)"
                    )
                }
            }
            web.loadUrl(url)
        } catch (t: Throwable) {
            Log.i(TAG, "NETCHECK RESULT=FAIL ${t.javaClass.name}: ${t.message}")
        }
    }

    companion object {
        const val TAG = "SELFCHECK"
        const val ACTION_SELFCHECK = "app.timetable.debug.SELFCHECK"
        const val ACTION_NETCHECK = "app.timetable.debug.NETCHECK"
        const val ACTION_RENDERCHECK = "app.timetable.debug.RENDERCHECK"
        const val ACTION_STYLECHECK = "app.timetable.debug.STYLECHECK"
        const val ACTION_FEATURECHECK = "app.timetable.debug.FEATURECHECK"
        const val ACTION_SEED = "app.timetable.debug.SEED"
        const val ACTION_PHOTOSEED = "app.timetable.debug.PHOTOSEED"
        const val ACTION_PINWIDGET = "app.timetable.debug.PINWIDGET"
        const val ACTION_CALCHECK = "app.timetable.debug.CALCHECK"
        const val ACTION_CROPCHECK = "app.timetable.debug.CROPCHECK"
        const val ACTION_PHOTOMGR = "app.timetable.debug.PHOTOMGR"
        const val ACTION_AUTOCROPCHECK = "app.timetable.debug.AUTOCROPCHECK"
        const val ACTION_PICKFAKE = "app.timetable.debug.PICKFAKE"

        /** 打开 AI 助手（假模型）；可带 `-e reply "PLAN…"` 注入这次要返回的文本 */
        const val ACTION_ASSIST = "app.timetable.debug.ASSIST"

        /** 用回环假服务真跑一遍 AI 的 HTTP 链路；`-e case ok|401|402|429|500|timeout` */
        const val ACTION_AIFAKE = "app.timetable.debug.AIFAKE"

        /** 打印助手相关状态（撤销、覆盖、自定义课程、配置摘要） */
        const val ACTION_AIDUMP = "app.timetable.debug.AIDUMP"

        /** 写/清**假**配置（dummy Key），供自检验"有配置/没配置"两条路；`-e clear 1` 清空 */
        const val ACTION_AICONFIG = "app.timetable.debug.AICONFIG"

        /**
         * 设置页里"相册返回"那个 requestCode（`SettingsActivity` 的 `REQ_PHOTOS`）。
         *
         * 这里是**复制**的常量而不是引用（它是 private）。这样"码对不上"这件事会立刻表现为
         * "点了没反应"，比悄悄走到别的分支好查得多。
         */
        const val REQ_PHOTOS_FAKE = 0x9A01

        /** 自检一次最多造几张图（多了自检本身就慢，条纹 + JPEG 每张都要几十毫秒） */
        const val MAX_SEED = 60
        const val ACTION_WIDGETSHOT = "app.timetable.debug.WIDGETSHOT"
        const val ACTION_GREETCHECK = "app.timetable.debug.GREETCHECK"
        const val EXTRA_URL = "url"

        /** 诊断用：强制小组件高度（dp） */
        const val EXTRA_HEIGHT = "h"

        /** 教务系统 CAS 票据校验页的路径（不带 ticket，避免消耗真实票据） */
        private const val DEFAULT_PROBE = "http://jwk.lzu.edu.cn/academic/login/lzu/loginLds6Valid.jsp"
    }
}
