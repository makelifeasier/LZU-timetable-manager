package app.timetable.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import app.timetable.R
import app.timetable.data.Prefs
import app.timetable.data.TimetableRepository
import app.timetable.data.WeekCalc
import app.timetable.ui.Ui
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 小组件与它的集合视图服务共用的取数、配色与行模型 */
internal object WidgetData {

    /** 正在上 + 接下来 */
    const val MODE_NEXT = 0

    /** 一整天全部课 */
    const val MODE_TODAY = 1

    const val MAX_ITEMS = 40

    /** 往前找几天，覆盖「这周五看下周一」 */
    private const val LOOKAHEAD_DAYS = 8

    /**
     * 列表项的展示模型 —— 两种模式共用同一个类型，
     * 因此「起止时间」在两种模式下都一定会显示。
     */
    class Row(
        val name: String,
        val spanLabel: String,
        val room: String,
        val start: LocalTime,
        val end: LocalTime,
        val ongoing: Boolean,
        /** 相对时间提示（「还有 2 小时」）；整天模式下为空 */
        val hint: String
    ) {
        /** 08:30–10:10 */
        val timeRange: String get() = "$start–$end"
    }

    fun mode(context: Context): Int {
        Prefs.init(context)
        return Prefs.widgetMode
    }

    // ----------------------------------------------------- 尺寸：只显示整行

    /**
     * 每行的固定占用高度（dp）= 卡片 36dp + 间距 2dp，与 `widget_today_row.xml` 的
     * 外壳高度**逐字对应**。
     *
     * **为什么改成固定值**（真机反馈："第三行露半个"）：
     * 以前是运行时把行样式 inflate 出来量一次真实行高，再按它算列表高度。
     * 但那个量出来的值只对「量的时候用的那段文案」成立 —— 而右侧时间那一栏
     * 在不同数据下是 1~3 行（"正在上" / "明天" / "08:30–10:10"），
     * **同一列表里行高本身就不一致**，列表高度取多少都不可能是所有行高的整数倍，
     * 于是底部必然切出半行。
     *
     * 现在行高由布局钉死（外壳 38dp），列表高度取 38dp 的整数倍，两者恒等，
     * **不可能**再出现半行。
     */
    const val ROW_SLOT_DP = 38f

    /** 头部 + 底部 + 内外边距（按当前布局实测约 71dp） */
    const val CHROME_DP = 71f

    /** 页脚那一行的高度（dp） */
    const val FOOTER_DP = 22f

    /**
     * 低于这个高度就收起页脚。
     *
     * 为什么要收：**各家的"一格"大小不一样**。同样 150dp，OPPO 的格子大、算 2 行；
     * 小米（MIUI/HyperOS）格子小、算 3 行 —— 所以小米上很难往下缩到 3×2。
     * 现在把声明的最小高度降到 90dp，并在这里把页脚收掉，
     * 矮组件里也能塞下 1~2 整行课程，而不是页脚被裁掉一半。
     */
    const val COMPACT_BELOW_DP = 140

    /** 取不到尺寸时的兜底高度，与 widget_today_info.xml 的 minHeight 一致 */
    const val FALLBACK_WIDGET_DP = 150

    /** widget_today_info.xml 里声明的 minHeight（默认摆放尺寸） */
    const val DECLARED_MIN_HEIGHT_DP = 150

    /** widget_today_info.xml 里声明的 minResizeHeight（能被缩到多小） */
    const val DECLARED_MIN_RESIZE_HEIGHT_DP = 90

    /** 列表最多渲染多少整行（自动模式封顶 4 行，手动指定最多 6 行） */
    const val AUTO_MAX_ROWS = 4
    const val MANUAL_MAX_ROWS = 6

    /**
     * 诊断用：强制小组件高度（dp），0 = 不强制。
     * 只由 debug 自检广播设置，release 里恒为 0 —— 用来在模拟器上量
     * 「被压到 100dp / 130dp 时会显示几整行、有没有裁切」。
     */
    @Volatile
    var debugHeightOverrideDp: Int = 0

    /** 当前小组件的实际高度（dp） */
    fun widgetHeightDp(context: Context): Int {
        if (debugHeightOverrideDp > 0) return debugHeightOverrideDp
        val raw = reportedHeightDp(context)
        if (raw <= 0) return FALLBACK_WIDGET_DP
        // 启动器"回显"我们声明的 minResizeHeight（见 looksLikeDeclaredEcho）时，
        // 这个数字不含任何信息 —— 按 150dp 估，至少稳定给出 2 整行。
        return if (looksLikeDeclaredEcho(raw)) maxOf(raw, FALLBACK_WIDGET_DP) else raw
    }

    /** 系统**原样**回报的高度（dp），不做任何校正；0 = 没回报 */
    fun reportedHeightDp(context: Context): Int {
        val mgr = AppWidgetManager.getInstance(context) ?: return 0
        val ids = runCatching {
            mgr.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
        }.getOrDefault(IntArray(0))
        val id = ids.firstOrNull() ?: return 0
        return runCatching {
            mgr.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
        }.getOrDefault(0)
    }

    /**
     * 这个高度是不是"我们声明的 minResizeHeight 被原样回显"？
     *
     * 小米 / OPPO / Pixel 回报的是**当前格子高度**（100~300dp 各不相同，都是真话）；
     * 但荣耀 MagicOS 这一族实测会回报**声明值本身**。区别在于：真的格子高度是
     * 连续分布的，回显只可能落在那两个声明值上 —— 所以这里只对
     * `minResizeHeight ± 1` 这个**窄**区间做校正，绝不误伤真实的小格子。
     *
     * 声明 minHeight 从 90dp 提到 150dp 之后，回显值 = 150 = 兜底值，
     * 校正本身变成恒等操作（不再影响任何观感），这一段只是防止再次改回去。
     */
    fun looksLikeDeclaredEcho(rawDp: Int): Boolean =
        rawDp in (DECLARED_MIN_RESIZE_HEIGHT_DP - 1)..(DECLARED_MIN_RESIZE_HEIGHT_DP + 1) ||
            rawDp in (DECLARED_MIN_HEIGHT_DP - 1)..(DECLARED_MIN_HEIGHT_DP + 1)

    /** 手动指定的行数；0 = 跟随系统给的高度 */
    fun manualRows(context: Context): Int {
        Prefs.init(context)
        return Prefs.widgetRows.coerceIn(0, MANUAL_MAX_ROWS)
    }

    /**
     * 纯函数版行数解析（可单测）：手动行数优先，否则按可用高度整除。
     * 返回的**一定是整数** —— 这就是"永远不露半行"的全部保证。
     */
    fun resolveRows(availableDp: Float, slotDp: Float, manualRows: Int): Int {
        if (manualRows > 0) return manualRows.coerceIn(1, MANUAL_MAX_ROWS)
        if (slotDp <= 0f) return 1
        return (availableDp / slotDp).toInt().coerceIn(1, AUTO_MAX_ROWS)
    }

    /** 是否处于"矮组件"形态（收页脚） */
    fun compact(context: Context): Boolean = dropFooter(context)

    /**
     * 要不要收掉页脚。
     *
     * 判据不是"高度小于某个阈值"（那样会出现"高一点反而少一行"的断层），
     * 而是**看收掉它能不能换来一整行、或者不收就会被裁掉**：
     *  - 正常形态会溢出（内容比组件还高）→ 必须收
     *  - 放了页脚就不足 2 行、收掉正好够 2 行 → 收（小米那种矮格子就靠这条救回来）
     *  - 其它情况（≥150dp 的正常尺寸）→ **保持原样**，不在用户已经满意的观感上乱动
     */
    fun dropFooter(context: Context): Boolean {
        // 用户手动指定了行数 → 不自作主张。他要几行就给几行，
        // 页脚排在课程下面：万一还是放不下，系统裁掉的是页脚，不是课。
        if (manualRows(context) > 0) return false
        val h = widgetHeightDp(context)
        val slot = naturalSlotDp(context)
        val normalRows = ((h - CHROME_DP) / slot).toInt().coerceAtLeast(1)
        val compactRows = ((h - CHROME_DP + FOOTER_DP) / slot).toInt().coerceAtLeast(1)
        val normalOverflows = CHROME_DP + normalRows * slot > h
        return normalOverflows || (normalRows < 2 && compactRows > normalRows)
    }

    /** 头部/页脚实际占用的高度（矮组件里不含页脚） */
    fun chromeDp(context: Context): Float =
        if (dropFooter(context)) CHROME_DP - FOOTER_DP else CHROME_DP

    /**
     * 列表高度（**像素**，向上取整）。
     *
     * 用 px 而不是 dp：RemoteViews 把 dp 换算成 px 时会取整，
     * 2 行 = 76dp × 2.625 = 199.5px → 截成 199px，最后一行就差 0.5px
     * （自检里表现为 `整行数=2.99` 这种非整数）。ceil 一次就干净了。
     */
    fun listHeightPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        return Math.ceil(visibleRows(context) * naturalSlotDp(context) * density.toDouble()).toInt()
    }

    /**
     * 一行的占用高度（dp）。**现在恒等于 [ROW_SLOT_DP]**。
     *
     * 曾经这里会在运行时把行样式 inflate 出来"量一次真实行高"，看着更严谨，
     * 实际是错的：量出来的值只对量的时候那段文案成立，而右侧时间栏在不同数据下
     * 是 1~3 行，**同一列表里行高根本不统一**，于是列表高度永远对不齐，
     * 底部必然切出半行（真机反馈"第三行露半个"）。
     *
     * 现在行高由布局固定（外壳 38dp），这里直接返回同一个常量 —— 布局与计算
     * 同源，两者不可能不一致。
     */
    fun naturalSlotDp(@Suppress("UNUSED_PARAMETER") context: Context): Float = ROW_SLOT_DP

    /** 能放下的整行数（自动 1..4；手动指定时照办，1..6） */
    fun visibleRows(context: Context): Int {
        val manual = manualRows(context)
        val slot = naturalSlotDp(context)
        val available = (widgetHeightDp(context) - chromeDp(context)).coerceAtLeast(0f)
        return resolveRows(available, slot, manual)
    }

    /**
     * 给诊断页用的一句话：系统实际给了小组件多高、算出来能放几整行。
     *
     * 「小组件显示不对」这类反馈全靠这几个数字定位 ——
     * 到底是系统给的高度变了（**重新添加小组件会丢掉手动缩放的大小**，
     * 而"正好两节课"往往是"代码 + 你手动拖到的大小"共同的结果），
     * 还是 App 自己算错了。
     */
    fun placementSummary(context: Context): String {
        val mgr = AppWidgetManager.getInstance(context) ?: return "读不到小组件信息"
        val ids = runCatching {
            mgr.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
        }.getOrDefault(IntArray(0))
        val slot = naturalSlotDp(context)
        val rows = visibleRows(context)
        val manual = manualRows(context)
        val slotText = String.format(java.util.Locale.CHINA, "%.1f", slot)

        if (ids.isEmpty()) {
            return "尚未添加到桌面（按默认 ${FALLBACK_WIDGET_DP}dp 估算：放得下 $rows 整行）"
        }
        val opts = runCatching { mgr.getAppWidgetOptions(ids[0]) }.getOrNull()
        fun opt(key: String): Int = opts?.getInt(key) ?: 0
        val minH = opt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        val maxH = opt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val minW = opt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val maxW = opt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)

        val verdict = when {
            manual > 0 -> "手动指定 $manual 行"
            minH <= 0 -> "系统没回报，按默认 ${FALLBACK_WIDGET_DP}dp"
            looksLikeDeclaredEcho(minH) -> "⚠ 疑似回报了声明的最小数，已按 ${FALLBACK_WIDGET_DP}dp 估"
            else -> "正常"
        }
        val h = widgetHeightDp(context)
        return "已添加 ${ids.size} 个 · 采用高度 ${h}dp（$verdict） · 行高 ${slotText}dp · 放得下 $rows 整行" +
            "\n系统原值：minH=$minH maxH=$maxH minW=$minW maxW=$maxW" +
            "｜本应用声明：minH=$DECLARED_MIN_HEIGHT_DP minResizeH=$DECLARED_MIN_RESIZE_HEIGHT_DP"
    }

    /**
     * 列表高度（dp）= 整行数 × 实测行高。
     *
     * 刻意**不**铺满剩余高度：一旦多出几 dp，底部就会露出下一行的「头」，
     * 正是用户反馈的「第三节课露出来」。
     */
    fun listHeightDp(context: Context): Float =
        visibleRows(context) * naturalSlotDp(context)

    fun rows(context: Context): List<Row> {
        Prefs.init(context)
        TimetableRepository.init(context)
        val week1 = Prefs.week1MondayDate() ?: return emptyList()
        val result = TimetableRepository.result()
        if (result.sessions.isEmpty()) return emptyList()
        val now = LocalDateTime.now()

        return if (Prefs.widgetMode == MODE_TODAY) {
            val today = now.toLocalDate()
            val week = WeekCalc.weekOf(today, week1)
            if (week < 1) emptyList()
            else WeekCalc.todaySessions(result, today, week).mapNotNull { s ->
                val start = result.section(s.startSection)?.startTime ?: return@mapNotNull null
                val end = result.section(s.endSection)?.endTime ?: start
                Row(
                    name = s.name,
                    spanLabel = result.sectionRangeLabel(s.startSection, s.endSection),
                    room = s.room,
                    start = start,
                    end = end,
                    ongoing = !now.toLocalTime().isBefore(start) &&
                        !now.toLocalTime().isAfter(end),
                    hint = ""
                )
            }
        } else {
            WeekCalc.agenda(result, week1, now, LOOKAHEAD_DAYS, MAX_ITEMS).map { a ->
                Row(
                    name = a.session.name,
                    spanLabel = result.sectionRangeLabel(a.session.startSection, a.session.endSection),
                    room = a.session.room,
                    start = a.start,
                    end = a.end,
                    ongoing = a.ongoing,
                    hint = WeekCalc.relativeLabel(a, now)
                )
            }
        }
    }

    fun currentWeek(context: Context): Int {
        val week1 = Prefs.week1MondayDate() ?: return 0
        return WeekCalc.weekOf(LocalDate.now(), week1)
    }
}

/** 小组件用到的颜色与背景资源，明暗两套 */
internal class WidgetColors(val dark: Boolean) {
    val text = if (dark) 0xFFEDEFF2.toInt() else 0xFF101828.toInt()
    val sub = if (dark) 0xFF98A2B3.toInt() else 0xFF667085.toInt()
    val accent = if (dark) 0xFF6FA8F5.toInt() else 0xFF1E6FD9.toInt()
    val widgetBg = if (dark) R.drawable.widget_bg_dark else R.drawable.widget_bg
    val rowBg = if (dark) R.drawable.widget_row_bg_dark else R.drawable.widget_row_bg
    val rowNow = R.drawable.widget_row_now

    companion object {
        fun of(context: Context) = WidgetColors(Ui.isNight(context))
    }
}
