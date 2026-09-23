package app.timetable.debug

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
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
import app.timetable.data.Prefs
import app.timetable.data.TimetableRepository
import app.timetable.data.WeekCalc
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
            report.append("RESULT=FAIL ").append(t.javaClass.name).append(": ").append(t.message)
        }
        Log.i(TAG, report.toString())
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
        const val EXTRA_URL = "url"

        /** 诊断用：强制小组件高度（dp） */
        const val EXTRA_HEIGHT = "h"

        /** 教务系统 CAS 票据校验页的路径（不带 ticket，避免消耗真实票据） */
        private const val DEFAULT_PROBE = "http://jwk.lzu.edu.cn/academic/login/lzu/loginLds6Valid.jsp"
    }
}
