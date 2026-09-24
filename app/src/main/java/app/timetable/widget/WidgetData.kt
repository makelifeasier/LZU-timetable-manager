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
        return effectiveHeightDp(reportedHeightDp(context))
    }

    /**
     * 纯函数（可单测）：把系统上报的高度换算成"采用高度"。
     *
     * 只有**恰好等于声明值的那一档**（[DECLARED_MIN_HEIGHT_DP] = 150dp 附近）才当成
     * "启动器把声明值回显上来了"，抬到兜底高度；其余一律原样采信。
     *
     * 特别地，**90dp 是 [DECLARED_MIN_RESIZE_HEIGHT_DP]，是用户真能把组件拖到的最小尺寸**，
     * 它是一个合法的真实高度，必须照实算：收掉页脚后 49dp（头部）+ 38dp（1 整行）= 87dp ≤ 90dp，
     * 刚好放一整行课。以前把 89..91dp 一起当回显抬成 150dp，于是按 150dp 排出
     * 2 行 76dp + 头尾 71dp = 147dp 硬塞进 90dp 的格子 —— 底部切出半行，只剩标题和被切一半的第一行。
     */
    fun effectiveHeightDp(rawDp: Int): Int {
        if (rawDp <= 0) return FALLBACK_WIDGET_DP
        return if (looksLikeDeclaredEcho(rawDp)) maxOf(rawDp, FALLBACK_WIDGET_DP) else rawDp
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
     * 这个高度是不是"我们声明的 minHeight 被原样回显"？
     *
     * 小米 / OPPO / Pixel 回报的是**当前格子高度**（100~300dp 各不相同，都是真话）；
     * 但荣耀 MagicOS 这一族实测会把**声明值本身**当高度上报。区别在于：真的格子高度是
     * 连续分布的，回显只可能落在声明值上 —— 所以只对 `minHeight ± 1` 这个窄区间做校正。
     *
     * **为什么只认 minHeight、不认 minResizeHeight（90dp）**：
     * 90dp 不是"回显噪声"，而是**用户能把组件拖到的最小尺寸**（minResizeHeight），
     * 也就是一个会真实出现的格子高度。把它一起当成回显抬到 150dp，就会按 2 行去排版
     * 却塞进 90dp 的格子 —— 底部切出半行（真机上表现为"只看到标题和半行课"）。
     * 90dp 该有的样子是：收掉页脚（49dp 头部）+ 1 整行（38dp）= 87dp，正好放下一行课。
     * 换句话说，这里宁可漏判（真被回显 90dp 的机型按 90dp 排 1 行）也不能误判
     * （把用户真拖到的最小格子撑成 150dp 的排法）。而当前 minHeight 就是 150dp、
     * 兜底值也是 150dp，所以这一段校正对正常机型已经是恒等操作，只防再次改回去。
     */
    fun looksLikeDeclaredEcho(rawDp: Int): Boolean =
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
    fun dropFooter(context: Context): Boolean =
        compactFor(widgetHeightDp(context), manualRows(context), naturalSlotDp(context))

    /**
     * 纯函数核心（可单测）：要不要收掉页脚。
     *
     * 判据与 [dropFooter] 完全同源 —— 抽出来只是为了能在单测里直接喂高度，
     * 不必造 Context。
     */
    fun compactFor(heightDp: Int, manualRows: Int, slotDp: Float = ROW_SLOT_DP): Boolean {
        // 用户手动指定了行数 → 不自作主张。他要几行就给几行，
        // 页脚排在课程下面：万一还是放不下，系统裁掉的是页脚，不是课。
        if (manualRows > 0) return false
        val h = heightDp.toFloat()
        val normalRows = ((h - CHROME_DP) / slotDp).toInt().coerceAtLeast(1)
        val compactRows = ((h - CHROME_DP + FOOTER_DP) / slotDp).toInt().coerceAtLeast(1)
        val normalOverflows = CHROME_DP + normalRows * slotDp > h
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
    fun visibleRows(context: Context): Int =
        rowsFor(widgetHeightDp(context), manualRows(context), naturalSlotDp(context))

    /**
     * 纯函数核心（可单测）：给定组件高度，能排下几整行。
     *
     * 与 [visibleRows] 同源，抽出来是为了能在单测里把"90dp 的最小格子"整条链路
     * （采用高度 → 收页脚 → 整行数）跑一遍，不必造 Context。
     */
    fun rowsFor(heightDp: Int, manualRows: Int, slotDp: Float = ROW_SLOT_DP): Int {
        val chrome = if (compactFor(heightDp, manualRows, slotDp)) CHROME_DP - FOOTER_DP else CHROME_DP
        val available = (heightDp.toFloat() - chrome).coerceAtLeast(0f)
        return resolveRows(available, slotDp, manualRows)
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
            looksLikeDeclaredEcho(minH) -> "⚠ 疑似回报了声明的 minHeight，已按 ${FALLBACK_WIDGET_DP}dp 估"
            else -> "正常"
        }
        val h = widgetHeightDp(context)
        return "已添加 ${ids.size} 个 · 采用高度 ${h}dp（$verdict） · 行高 ${slotText}dp · 放得下 $rows 整行" +
            "\n系统原值：minH=$minH maxH=$maxH minW=$minW maxW=$maxW" +
            "｜本应用声明：minH=$DECLARED_MIN_HEIGHT_DP minResizeH=$DECLARED_MIN_RESIZE_HEIGHT_DP"
    }

    // ------------------------------------------------- 列表下方的扩展区（每日一句 / 图片）

    /**
     * 列表下方还剩多少 dp 可用。
     *
     * 计算方式：上报高度 − 头部页脚（chrome）− 课程列表占用的整行高度。
     * 根布局是 match_parent、子控件自顶向下排列，所以余量就落在底部。
     *
     * **这是估算**：上报高度本身在个别启动器上不准（荣耀把声明值当高度上报，见
     * [looksLikeDeclaredEcho]），所以调用方一律"宁可少显示"：只要不确定就隐藏，
     * 绝不会出现"显示了半截句子"这种更糟的观感。
     */
    fun extraSpaceDp(context: Context): Int {
        val h = widgetHeightDp(context)
        val used = chromeDp(context) + visibleRows(context) * naturalSlotDp(context)
        return (h - used).toInt()
    }

    /** 底部扩展区（每日一句 / 图片）是否允许显示 */
    fun extrasAllowed(context: Context): Boolean {
        Prefs.init(context)
        return when (Prefs.widgetExtrasOverride) {
            -1 -> false          // 用户强制隐藏
            1 -> true            // 用户强制显示（高度判断不准的机型用这个救）
            else -> extraSpaceDp(context) >= EXTRA_MIN_DP
        }
    }

    /** 图片是否可显示：开关开 + 至少有一张图 */
    fun hasPhotos(context: Context): Boolean {
        Prefs.init(context)
        return Prefs.photoEnabled && photoList(context).isNotEmpty()
    }

    /** 已选图片的本地路径列表（最多 5 张） */
    fun photoList(context: Context): List<String> {
        Prefs.init(context)
        val raw = Prefs.photoUris
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_PHOTOS)
    }

    /**
     * 图片框高度（dp）。
     *
     * 按小组件的**宽高比**自适应：宽扁时做成接近方形，竖长时做成长方形 ——
     * 这样在两种极端尺寸下都不会出现"一张细长条图"或"图把课程挤没"。
     */
    fun photoHeightDp(context: Context): Int {
        val opts = widgetOptions(context) ?: return PHOTO_MIN_DP
        val w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        if (w <= 0 || h <= 0) return PHOTO_MIN_DP
        // 竖长（高一倍以上）：图更高；宽扁：图矮一点，优先保住课程行
        val ratio = if (h >= w) 1.15f else 0.72f
        return (w * ratio).toInt().coerceIn(PHOTO_MIN_DP, PHOTO_MAX_DP)
    }

    /** 当前小组件的 options（取不到返回 null） */
    private fun widgetOptions(context: Context): android.os.Bundle? {
        val mgr = AppWidgetManager.getInstance(context) ?: return null
        val ids = runCatching {
            mgr.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
        }.getOrDefault(IntArray(0))
        val id = ids.firstOrNull() ?: return null
        return runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
    }

    /** 列表下方的扩展区最少需要多少 dp 才显示（一句话约 20dp，图片更多） */
    const val EXTRA_MIN_DP = 22

    /** 图片框的最小/最大高度（dp） */
    const val PHOTO_MIN_DP = 56
    const val PHOTO_MAX_DP = 150

    /** 最多几张图片 */
    const val MAX_PHOTOS = 5

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
            else TimetableRepository.sessionsOn(context, today).mapNotNull { s ->
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
            // 传 sessionsOn：小组件显示的「接下来」必须和 App 里看到的一致，
            // 否则当天调课之后小组件还在念原来的课
            WeekCalc.agenda(
                result,
                week1,
                now,
                LOOKAHEAD_DAYS,
                MAX_ITEMS,
                sessionsOn = { d -> TimetableRepository.sessionsOn(context, d) }
            ).map { a ->
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
