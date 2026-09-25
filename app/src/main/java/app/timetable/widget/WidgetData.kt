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

    /** widget_today_info.xml 里声明的 minWidth（默认摆放宽度） */
    const val DECLARED_MIN_WIDTH_DP = 150

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
        val id = activeWidgetId(context) ?: return 0
        return runCatching {
            mgr.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
        }.getOrDefault(0)
    }

    /**
     * 正在构建的那一个小组件 id（0 = 没指定，按第一个算）。
     *
     * [TodayWidgetProvider.build] 会在自己那一次构建里把它设成当前 id。为什么需要：
     * 桌面上**同时存在两个尺寸不同的组件**时（用户拖了一个 4×2、又拖了一个 4×4），
     * 以前所有实例都读 `ids[0]` 的尺寸 —— 第二个就按第一个的高度排版，
     * 高度对不上时要么底部留一大片空白、要么最后一个元素（图片）被裁掉一条。
     * 每个实例用自己的 options 才是对的，而 options 是**按 id** 取的（见 [activeWidgetId]）。
     */
    @Volatile
    var buildingWidgetId: Int = 0

    /** 这一次该读哪一个实例的 options：优先"正在构建的那个"，否则退回第一个 */
    private fun activeWidgetId(context: Context): Int? {
        val mgr = AppWidgetManager.getInstance(context) ?: return null
        val ids = runCatching {
            mgr.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
        }.getOrDefault(IntArray(0))
        if (ids.isEmpty()) return null
        if (buildingWidgetId != 0 && ids.contains(buildingWidgetId)) return buildingWidgetId
        return ids[0]
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

    /**
     * 实测出来的 chrome（dp）。按"密度 / 字体缩放 / 内容宽度"缓存 —— 这三个量不变时
     * 头部高度也不会变，不必每次刷新都 inflate 一遍布局（那是主线程上的几十微秒级开销）。
     */
    private var chromeCacheKey: String = ""
    private var chromeCacheValue: Float = CHROME_DP - FOOTER_DP

    /** 实测值的合理区间（dp）：超出这个范围说明量出来的东西不是头部，宁可退回常量 */
    private const val MIN_CHROME_DP = 24f
    private const val MAX_CHROME_DP = 120f

    /**
     * 头部/内边距实际占用的高度（dp）—— **实测**，不再是写死的 49dp。
     *
     * [chromeDpFor] 那 49dp 是"根布局 paddingTop 8 + 头部 37 + paddingBottom 4"这个**假设**。
     * 头部是 `wrap_content` 的两行文字（15sp 标题 + 11sp 副标题）加上右边的胶囊/刷新按钮，
     * 它的真实高度取决于**用户手机的字体大小与字体本身**（系统"字体大小"、厂商自带字体、
     * 无障碍放大都会改它）。只要真实头部比假设高 6dp，整条链就比格子高 6dp —— 而最后一个元素
     * （图片框）会被宿主裁掉 6dp，用户看到的就是"下方图片被遮住一小部分"。
     *
     * 做法：把这套布局原样 inflate 出来（同一份 XML、同一套字体度量），把头部那几个文案填成
     * 真实长度，量一次头部高度，再加根布局自己的上下 padding。结果按
     * （密度、字体缩放、内容宽度）缓存 —— 这几个量不变时高度也不会变，不必每次刷新都 inflate。
     *
     * 量不出来（极少数 ROM 的 inflate 失败）就退回 [chromeDpFor] 的常量：
     * **宁可沿用旧的估算，也不能因为"量不出来"让整个组件不显示**。
     */
    fun chromeDp(context: Context): Float {
        val density = context.resources.displayMetrics.density
        val widthPx = (contentWidthDp(context) * density).toInt().coerceAtLeast(1)
        val key = "$density/${context.resources.configuration.fontScale}/$widthPx"
        if (key == chromeCacheKey) return chromeCacheValue
        val measured = runCatching { measureChromeDp(context, widthPx, density) }
            .getOrNull()
            ?.takeIf { it in MIN_CHROME_DP..MAX_CHROME_DP }
            ?: chromeDpFor(widgetHeightDp(context), manualRows(context))
        chromeCacheKey = key
        chromeCacheValue = measured
        return measured
    }

    /**
     * 真正去量那一次（[chromeDp] 的实现）：inflate → 填文案 → 量头部 → 加根布局的上下 padding。
     *
     * 文案是**代表性**的：标题按真实格式造一个最长的日期，副标题按最长的那种拼法
     * （`第 N 周 · 接下来 X 节 · 句子`，它 maxLines=1，所以换行不会发生，只有字体高度在起作用）。
     * 刷新按钮的文字写在 XML 里（`android:text`），不需要在这里补。
     */
    private fun measureChromeDp(context: Context, widthPx: Int, density: Float): Float {
        val inflater = android.view.LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.widget_today, null) as? android.view.ViewGroup
            ?: return chromeDpFor(widgetHeightDp(context), manualRows(context))
        root.findViewById<android.widget.TextView>(R.id.widget_title)
            ?.text = "12月28日 周日"
        root.findViewById<android.widget.TextView>(R.id.widget_subtitle)
            ?.text = "第 99 周  ·  接下来 99 节  ·  每日一句占位"
        root.findViewById<android.widget.TextView>(R.id.widget_mode)
            ?.text = "接下来 ▾"
        // 头部的真实高度与宽度无关（这里所有文字都是单行 + 胶囊/按钮都是 wrap_content），
        // 但仍然按真实内容宽度去量：万一哪天有人给头部加了会换行的东西，这里也不会量歪
        val header = root.getChildAt(0) ?: return chromeDpFor(widgetHeightDp(context), manualRows(context))
        header.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        val px = root.paddingTop + header.measuredHeight + root.paddingBottom
        return px.toFloat() / density
    }

    /**
     * 列表高度（**像素**）= 整行数 × **一行真实占的像素**。
     *
     * 用 px 而不是 dp：RemoteViews 把 dp 换算成 px 时会取整（38dp × 2.625 = 99.75px →
     * 布局参数取整成 100px），按 dp 算出来的列表高度会与"行实际占的高度"差一点点
     * （4 行时 ceil(4 × 99.75) = 399px，而四行实际要 400px → 最后一行被切掉 1px）。
     *
     * 所以这里的口径是**与框架完全一致**的那一个：每行 = `round(38 × density)` 像素
     * （`LayoutParams` 的 dp 换算就是 `complexToDimensionPixelSize`，四舍五入），
     * 列表高度 = 它 × 行数。整数像素相乘，永远不会再对不上。
     */
    fun rowSlotPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        return Math.max(1, Math.round(naturalSlotDp(context) * density))
    }

    fun listHeightPx(context: Context): Int = visibleRows(context) * rowSlotPx(context)

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

    /**
     * 纯函数版 chrome（单测里不必造 Context）—— 现在它是**兜底常量**，不是运行时用的值。
     *
     * 运行时一律走 [chromeDp]（实测）。这里恒为 [CHROME_DP] − [FOOTER_DP]（49dp），
     * 也就是"页脚那一行删掉之后"的老估算：只有在 [chromeDp] 量不出来、或者单测里没有真 Context
     * 时才用到它。保留 `heightDp / manualRows` 两个参数只为让老调用方（参考表那类断言）
     * 读起来还是原来的样子。
     */
    fun chromeDpFor(
        @Suppress("UNUSED_PARAMETER") heightDp: Int,
        @Suppress("UNUSED_PARAMETER") manualRows: Int,
        @Suppress("UNUSED_PARAMETER") slotDp: Float = ROW_SLOT_DP
    ): Float = CHROME_DP - FOOTER_DP

    /** 能放下的整行数（自动 1..4；手动指定时照办，1..6）—— 用**实测**的头部高度 */
    fun visibleRows(context: Context): Int =
        rowsFor(
            widgetHeightDp(context),
            manualRows(context),
            naturalSlotDp(context),
            photoReserveDp(context),
            chromeDp(context)
        )

    /**
     * 纯函数（可单测）：**副标题那一行** = `第 N 周 · 每日一句`。
     *
     * 用户要求："把话（每日一句）放在第 N 周接下来第 N 节**同一行**" ——
     * 于是句子既不占列表的一格、也不占列表下方一条，一个像素的位置都不多花。
     * 太长就交给布局的 `maxLines=1 + ellipsize=end` 截断（宁可截断，也不换行把头部撑高）。
     *
     * 后来又按用户要求去掉了「接下来 X 节 / 整天 X 节」那一小段：
     * 右上角那个胶囊本来就写着「接下来 ▾ / 整天 ▾」，同一件事不必说两遍；
     * 而且它占掉的宽度正好是句子最需要的（句子一长就会被省略号截掉）。
     *
     * @param quote 每日一句；关着开关（或没有句子）时传 null → 只显示「第 N 周」
     */
    fun subtitleText(week: Int, quote: String?): String {
        val head = "第 $week 周"
        return if (quote.isNullOrBlank()) head else "$head  ·  $quote"
    }

    /**
     * 纯函数核心（可单测）：给定组件高度，能排下几整行。
     *
     * 与 [visibleRows] 同源，抽出来是为了能在单测里把"90dp 的最小格子"整条链路
     * （采用高度 → 收页脚 → 整行数）跑一遍，不必造 Context。
     *
     * [reserveDp] 是**底部图片区先占走的预留高度**（[photoReserveDp]，没开图片时恒为 0）。
     * 为什么先扣它再算行数（真机反馈："向下拉只是多出一行课，图片还是那么扁"）：
     *   - 扣掉预留 = "这块高度是图片的，别拿来排行" → 同一档高度里拖动组件，行数**不变**，
     *     多出来的高度全部落到图片上；
     *   - 攒够一整行（[ROW_SLOT_DP]）之后才会多出一行课，同时图片回到理想高度 ——
     *     所以"行数固定"是**一段区间内**固定，而不是永远钉死（永远钉死就没法用高格子看更多课了）。
     * 默认值 0 让所有老调用方（与老单测）逐字保持原语义。
     *
     * **手动指定行数时不看预留**（用户说了几行就是几行，见 [resolveRows]）：图片拿剩下的部分，
     * 剩下的不够硬底线（[PHOTO_HARD_MIN_DP]）时干脆不显示 —— 用户明确要了行数，就别跟他抢。
     */
    fun rowsFor(
        heightDp: Int,
        manualRows: Int,
        slotDp: Float = ROW_SLOT_DP,
        reserveDp: Int = 0,
        chromeDp: Float = chromeDpFor(heightDp, manualRows, slotDp)
    ): Int {
        // chrome 默认走"页脚删掉之后"的常量 49dp，运行时由调用方传**实测值**（见 [chromeDp]）
        val available = (heightDp.toFloat() - chromeDp - reserveDp).coerceAtLeast(0f)
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
        // 这一行是**给用户核对"手机上装的是哪一版"**用的（没有 adb 也能看）：
        // 看到 [LAYOUT_MARKER] 就是这一版；看不到就说明装的是旧包。
        val quoteOn = quoteShown(context)
        val layoutLine = "布局版本=${LAYOUT_MARKER}" +
            " · 副标题=「${subtitleText(currentWeek(context), if (quoteOn) "…" else null)}」"
        val quoteLine = "每日一句=${if (quoteOn) "开（拼在副标题那一行）" else "关"} · 页脚=（这一行已删除）"
        // 头部高度是**实测**的（见 [chromeDp]）。用户报"图片被遮住/下面有空白"时，
        // 这一行是唯一能远程判断"到底是头部比常量高多少"的数字 ——
        // 手机字体调大过、装了厂商字体的机器，这个值都会明显偏离 49dp。
        // 整段 runCatching：诊断页在这一行上炸掉的话，用户就再也看不到别的信息了。
        val chromeText = runCatching {
            String.format(java.util.Locale.CHINA, "%.1f", chromeDp(context))
        }.getOrElse { "?" }
        val boxText = runCatching { photoBoxDp(context).toString() }.getOrElse { "?" }
        val listText = runCatching { listHeightPx(context).toString() }.getOrElse { "?" }
        return "已添加 ${ids.size} 个 · 采用高度 ${h}dp（$verdict） · 行高 ${slotText}dp · 放得下 $rows 整行" +
            "\n图片区：头部实测 ${chromeText}dp（常量 49dp）· 图片框 ${boxText}dp · 清单行 ${listText}px" +
            "\n$quoteLine" +
            "\n系统原值：minH=$minH maxH=$maxH minW=$minW maxW=$maxW" +
            "｜本应用声明：minH=$DECLARED_MIN_HEIGHT_DP minResizeH=$DECLARED_MIN_RESIZE_HEIGHT_DP" +
            "\n$layoutLine"
    }

    /** 诊断页显示"图片框多高"（[ExtrasPlanner] 的结论；图片没开时是 0） */
    fun photoBoxDp(context: Context): Int = runCatching {
        Prefs.init(context)
        val space = extraSpaceDp(context)
        ExtrasPlanner.plan(
            override = Prefs.widgetExtrasOverride,
            photoEnabled = Prefs.photoEnabled,
            photoCount = photoList(context).size,
            availableDp = space,
            wantedPhotoDp = photoBoxTargetDp(
                space,
                contentWidthDp(context),
                PhotoBitmap.photoAspect(context)
            )
        ).photoHeightDp
    }.getOrDefault(0)

    /**
     * 诊断用：**这一版布局的代号**（改了小组件布局就改它）。
     *
     * 用户在诊断页里看到这一串，就能确认手机上跑的是哪一版 ——
     * 这一轮反复出现"装上去没变化"，而手机上**看不出装的是哪一版**正是排查的死结
     * （包名、版本号都一样，只有这里能区分）。
     *
     * v6：图片区的渲染改成"只画一层、填满"（比例一致 = 整张铺满；不一致 = 取中间块）。
     * 上一版那句里的"模糊补边"整条删掉了 —— 那一层在真机上会露出**块状色块**
     * （用户原话："图片上下怎么有模糊的像素图片块"），见 [PhotoFit] 的类注释。
     */
    const val LAYOUT_MARKER = "v6-图片填满(整张/取中间块)+头部实测"

    /**
     * 每日一句此刻要不要显示：开关开着（[Prefs.quoteEnabled]）且没有选「强制隐藏」
     * （[Prefs.widgetExtrasOverride] = -1 的语义是"这些附加内容我不要"）。
     *
     * 它现在是**副标题那一行的一部分**（[subtitleText]），不占列表、不占图片区。
     */
    fun quoteShown(context: Context): Boolean {
        Prefs.init(context)
        return Prefs.quoteEnabled && Prefs.widgetExtrasOverride >= 0
    }

    // ------------------------------------------------- 列表下方的扩展区（现在只剩图片）
    //
    // 每日一句已经拼进副标题那一行（[subtitleText]，不占任何固定高度），所以"扩展区"只剩图片，
    // 这里的账也就只有一条：
    //
    //   照片需要的高 ← "这张照片在这个内容宽度下需要多高"，只由「照片自己的比例」决定
    //                  （[photoWantedHeightDp]）；
    //   预留         ← 排行数之前先扣掉的就是**同一个高度** + 它的 6dp 上边距（[photoReserveDp]）。
    //
    // 两处取同一个值，于是"排完整数行之后剩下的高度 ≥ 照片需要的高"，而剩下的那部分**全部**
    // 交给图片框（[ExtrasPlanner.plan]：自动档的框高 = 余量 − 6dp 上边距）：
    // 底部零头恒为 0（判据 [ExtrasPlanner.leftoverBelowBoxDp]），照片按 [PhotoFit.layout]
    // "居中取一块填满"——比例一致时就是整张原样显示。

    /**
     * 纯函数（可单测）：**这张照片在这个内容宽度下需要多高**（dp）—— 也就是"照片自己想要的框高"。
     *
     * ## 它现在有两个用途，都不是"直接当框高"（自动档）
     *
     * 1. **排行数时的预留**：[photoReserveDp] 用它（+6dp 上边距）先占住一块，于是同一档高度里
     *    拖动组件时行数在一整行之内不变，多出来的高度归图片区；
     * 2. **强制显示档的框高**（[ExtrasPlanner.plan] 里 override > 0 那一支）。
     *
     * 自动档的框高是"列表下方的**全部**剩余空间 − 6dp"（[ExtrasPlanner]）——它**恒 ≥ 本值**
     * （因为行数是扣掉本值算出来的），所以照片永远不会被挤到"需要的高度"以下；多出来的那部分
     * 由 [PhotoFit.layout] 居中取一块填满，而不是变成上下留白。
     *
     * ## 为什么"零留白"这条结论仍然靠它
     *
     * 用户的照片是**按裁剪框裁出来的**，而裁剪框的高就是本值（[photoFrameHeightDp]）——
     * 于是"框 == 照片需要的高"这件事在**导入时的那个组件尺寸**上精确成立 → 照片整张显示。
     * 只有在他后来改过组件尺寸时才会真的裁到（裁的是正中间那一块）。
     *
     * ## 两个边界
     *
     *  - **[PHOTO_MIN_DP] 下限**：内容宽很窄（或照片极扁）时算出来只有十几 dp，
     *    那就不是"一张图"而是一条彩条了 —— 抬到下限；
     *  - **[PHOTO_MAX_DP] 上限**：照片想要的高度最多 150dp。它管的是**预留**与**裁剪框**
     *    （不能因为一张竖屏截图就让课程行被预留吃光），而**不是**框高的上限：
     *    框高由剩余空间决定，可能超过它（见 [ExtrasPlanner]）。
     *    拉宽组件时预留仍会跟着变大 —— 因为照片的比例固定，宽度上去了高度自然也上去。
     *
     * 取不到宽度（部分启动器不上报尺寸）或比例缺失/脏值时返回 **0**：
     * 0 在这里的意思是"没有图片框"，调用方一律按"没有照片"处理 ——
     * 这是本项目一贯的兜底方向（宁可什么都不显示，也不按瞎猜的数字排版）。
     * 刻意**不**退回某个固定比例（老代码退回 16:9）：那正是"框的形状与用户裁好的照片不一致"
     * 的另一个入口，退回去等于把上次修的病换个方式再犯一次。
     *
     * @param aspect 照片的宽高比 `宽/高`（> 0 才有效，见 [PhotoBitmap.photoAspect]）
     */
    fun photoWantedHeightDp(contentWidthDp: Int, aspect: Float?): Int {
        if (contentWidthDp <= 0) return 0
        val a = aspect ?: return 0
        if (a.isNaN() || a <= 0f) return 0
        val heightDp = contentWidthDp.toFloat() / a
        return Math.round(heightDp).coerceIn(PHOTO_MIN_DP, PHOTO_MAX_DP)
    }

    /**
     * 当前照片需要多高（dp）—— 这就是图片框该有的高度。
     *
     * 比例取自**照片列表里的第一张**（[PhotoBitmap.photoAspect]）：导入时那个裁剪框的形状是
     * 统一的，所有照片本来就该同比例，所以"第一张"就是"这个用户的照片形状"。为什么不取
     * "当前要显示的那一张"：点一下换下一张时组件高度会跟着跳（见 [PhotoBitmap.photoAspect]）。
     */
    fun photoWantedHeightDp(context: Context): Int =
        photoWantedHeightDp(contentWidthDp(context), PhotoBitmap.photoAspect(context))

    /**
     * 图片区**先占走**的预留高度（dp）= 图片框需要的高 + 它的 6dp 上边距；没开图片时是 0。
     *
     * 三个"0"的场合，都是为了让不用图片的人完全不受这次改动影响：
     *  - 图片开关没开 / 一张图都没有 → 没有图片区，没什么可预留的；
     *  - [Prefs.widgetExtrasOverride] = -1（用户强制隐藏）→ 预留了反而白少一行课；
     *  - 照片的比例取不到（[photoWantedHeightDp] 返回 0）→ 同上，不预留。
     *
     * **刻意不给"每日一句"预留**：它拼在副标题那一行（[subtitleText]），既不占列表格子也不占固定高度，
     * 不参与这里的预算。只有"课表组件里课程行数比图片的精确比例重要"这一条老规矩仍然成立：
     * 宁可图片少一行可见，也不为了图片去多扣一行课。
     */
    fun photoReserveDp(context: Context): Int {
        Prefs.init(context)
        if (Prefs.widgetExtrasOverride < 0) return 0
        if (!Prefs.photoEnabled) return 0
        if (photoList(context).isEmpty()) return 0
        return photoWantedHeightDp(context) + AREA_GAP_DP
    }

    /**
     * 照片自己要的框高（dp）—— 就是 [photoWantedHeightDp]，**与剩余空间无关**。
     *
     * 保留这个名字（它以前是"框能塞多高就塞多高"）是因为调用点读起来仍然要能对上是哪一块内容；
     * 而**真正决定框高**的是 [ExtrasPlanner.plan]：自动档的框高 = 列表下方的全部剩余空间 − 6dp。
     * 这个函数给的是那个过程的**下界**（行数是按它预留的）与强制显示档的框高。
     *
     * 空间不够时这里也**不压**这个值：压了之后行数会按一个放不下的高度去排，底部照样会漏出空白。
     *
     * @param availableDp 这个参数**保留了签名但已经不参与计算** —— 传什么都不影响结果。
     *        留着它是有意的：调用点仍然把余量传进来，读者一眼能看到"这里曾经按余量算"。
     */
    fun photoBoxTargetDp(
        @Suppress("UNUSED_PARAMETER") availableDp: Int,
        contentWidthDp: Int,
        aspect: Float?,
        @Suppress("UNUSED_PARAMETER") gapDp: Int = AREA_GAP_DP
    ): Int = photoWantedHeightDp(contentWidthDp, aspect)

    /**
     * 纯函数（可单测）：**先给图片区留出位置**，再用剩下的高度排整行 —— 本轮的入口。
     *
     * 顺序是有意的，不能换：
     *  1. 预留 = 照片想要的高度（[photoWantedHeightDp]）+ 6dp 上边距；
     *  2. 行数 = [(高度 − 头部/页脚 − 预留) / 行高] 取整（[rowsFor]，**整数行**）。
     *
     * 排完之后的剩余高度（预留那块 + 不足一整行的零头）**全部**归图片框 ——
     * 框高 = 余量 − 6dp（[ExtrasPlanner.plan]），所以组件底部不会留下任何空白；
     * 照片按 [PhotoFit.layout] "居中取一块填满"。
     */
    fun photoRowsFor(
        heightDp: Int,
        contentWidthDp: Int,
        manualRows: Int,
        aspect: Float?,
        slotDp: Float = ROW_SLOT_DP
    ): Int {
        val box = photoWantedHeightDp(contentWidthDp, aspect)
        val reserve = if (box > 0) box + AREA_GAP_DP else 0
        return rowsFor(heightDp, manualRows, slotDp, reserve)
    }

    // ------------------------------------------- 裁剪框：组件是几乘几 → 框是什么形状

    /**
     * 当前尺寸下**裁剪框**的形状：用户在导入图片时看到的那个框，就是小组件图片区的形状。
     *
     * 用户原话：「注意组件是几乘几」。这里不能写死 16:9 / 2:1 —— 桌面上的小组件占
     * `W 列 × H 行`，但**一格多少像素由启动器决定**，所以只有
     * `AppWidgetManager.getAppWidgetOptions(id)` 报上来的 dp 尺寸才是真的
     * （[contentWidthDp] 与 [widgetHeightDp] 都走这条路）。
     *
     * 比例 = 图片区**内容宽** : 图片区**该有多高**（[photoFrameHeightDp]）。
     * 取不到尺寸时由 [PhotoCrop.frame] 给兜底 2:1（理由写在那边的注释里）。
     */
    fun photoFrame(context: Context): CropFrame =
        PhotoCrop.frame(contentWidthDp(context), photoFrameHeightDp(context))

    /** 当前尺寸下图片区该有多高（dp）—— 就是裁剪框的"高" */
    fun photoFrameHeightDp(context: Context): Int =
        photoWantedHeightDp(context)

    /**
     * 纯函数（可单测）：这个尺寸下**裁剪框**的高（dp）—— 也就是照片"想要"的高度。
     *
     * ## 裁剪框为什么要与照片自己的比例同源
     *
     * 用户在裁剪界面里选的那一块要落到组件里原样出现，唯一的前提就是**那条框与照片同比例**：
     * 按框裁出来的图，比例必然等于框比例（[PhotoCrop.encodeSize] 就是按框比例算的存盘尺寸）。
     *
     * ## 它**不等于**组件里那个框的高（本轮改过，别混）
     *
     * 组件里的图片框现在吃掉"列表下方的全部剩余空间"（[ExtrasPlanner]），所以：
     *  - 在**导入图片那一刻的组件尺寸**上，两者通常相等 → 照片整张显示；
     *  - 用户后来改过组件尺寸 → 框变高/变扁 → [PhotoFit.layout] **居中取一块填满**（不拉伸、不留白）。
     *
     * 也就是说"框高随组件高度变"这件事现在**只发生在渲染侧**，而且是被 [PhotoFit] 明确处理过的一档；
     * 裁剪框仍然只由"照片的比例 × 内容宽度"决定（[photoWantedHeightDp]），所以它与组件高度无关 ——
     * 这一条不能动：如果裁剪框跟着组件高度走，用户在 4×3 上导入的照片比例就会和"4×2 时导入的"
     * 不一样，同一个组件里混进两种形状的照片。
     *
     * 这也是为什么这里不需要 `heightDp / manualRows / quoteEnabled / override` 这些参数 ——
     * 它们只影响"排几行课"与"框实际多高"，不影响"用户该按什么形状裁"。
     *
     * @param aspect 照片的比例；null / 非法时返回 0（调用方按"没有图片框"处理，
     *               [PhotoCrop.frame] 会退兜底 2:1 —— 那是画框的兜底，不是排版数字的兜底）
     */
    fun photoFrameHeightDp(contentWidthDp: Int, aspect: Float?): Int =
        photoWantedHeightDp(contentWidthDp, aspect)

    /**
     * 列表下方还剩多少 dp 可用。
     *
     * 计算方式：上报高度 − 头部页脚（chrome）− 课程列表占用的整行高度。
     * 根布局是 match_parent、子控件自顶向下排列，所以余量就落在底部。
     *
     * **注意**：[visibleRows] 已经把 [photoReserveDp] 扣掉了，所以这里返回的余量**包含
     * 那块预留**（照片想要的高度 + 上边距 + 排不下一整行的零头）。**这一整块现在都归图片框**
     * （[ExtrasPlanner.plan]：框高 = 余量 − 6dp），于是组件底部不再漏出那条零头 —— 那正是用户
     * 反馈的"下拉之后依然有空白"。
     *
     * 于是"开了图片却什么都看不到"只剩一种可能：格子被压得极小（90dp 那种最小尺寸）——
     * 连余量扣掉 6dp 上边距之后都不够图片的硬底线 [PHOTO_HARD_MIN_DP]，那就只好不显示
     * （宁可不显示，也不要一条 5dp 的彩条）。其余情况（≥150dp 的格子）图片一定会出来。
     *
     * **这是估算**：上报高度本身在个别启动器上不准（荣耀把声明值当高度上报，见
     * [looksLikeDeclaredEcho]），所以调用方一律"宁可少显示"：只要不确定就隐藏，
     * 绝不会出现"显示了半截句子"这种更糟的观感。
     */
    fun extraSpaceDp(context: Context): Int {
        val h = widgetHeightDp(context)
        // 列表只有课程行（每日一句拼在副标题那一行里，不占列表）：列完课程剩下的全归图片区
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
     * 图片框的**内容宽度**（dp）= 系统回报的格子宽度 − 根布局左右内边距。
     *
     * 用来算"该把图解码成多少像素"（见 [PhotoBitmap.targetPx]）：按格子宽度解码会多解 24dp×density
     * 那么多列像素 —— 那些像素宿主根本不会显示，却要跟着 RemoteViews 一起过 binder。
     *
     * 取不到尺寸时按声明的 minWidth 估（只影响清晰度，不影响功能）。
     */
    fun contentWidthDp(context: Context): Int {
        val opts = widgetOptions(context) ?: return DECLARED_MIN_WIDTH_DP - CONTENT_INSET_DP
        val w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        if (w <= 0) return DECLARED_MIN_WIDTH_DP - CONTENT_INSET_DP
        return (w - CONTENT_INSET_DP).coerceAtLeast(1)
    }

    // 图片框高度现在只有一处定义：[photoWantedHeightDp]（照片比例 × 内容宽度）。
    // 两个老实现都已删除，留着只会再被误用：
    //   · photoHeightDpFor(width, height) —— 按格子宽高在 0.72 / 1.15 之间跳，
    //     同一个框换个高度就换个比例，图被裁得怪；
    //   · photoHeightForWidthDp(width) —— 固定 16:9。看起来只是"定一个好看的形状"，
    //     但用户裁好的照片比例是**另一个**数（例如 3.14:1），框比照片高出来的每一 dp
    //     都会变成图片上下的空白（真机日志里的"留边=74px(28%)"就是这么来的）。
    //     框该怎么高只有一个正确答案：照片自己的比例。

    /** 当前小组件的 options（取不到返回 null）—— 取的是**正在构建的那个实例**的（见 [activeWidgetId]） */
    private fun widgetOptions(context: Context): android.os.Bundle? {
        val mgr = AppWidgetManager.getInstance(context) ?: return null
        val id = activeWidgetId(context) ?: return null
        return runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
    }

    /**
     * 强制显示档给图片框留的**宽松余量**（dp）：22dp。
     *
     * 这个数原来叫"一句话的占用"（句子 11sp≈16dp + 6dp 上边距）—— 句子已经搬进列表了，
     * 它现在只剩一个用途：**强制显示档用 `余量 + 22dp` 的宽松度**，
     * 与老代码 `min(理想高度, 余量 + 22)` 逐值对齐，免得真要救的机型反而比之前显示得更小。
     */
    const val EXTRA_MIN_DP = 22

    /**
     * 图片框的 `layout_marginTop`（dp）。
     *
     * 布局里写死 6dp，必须算进"余量"里 —— 只算控件高度不算边距，累计起来正好会把
     * 最后一块挤出去几个 dp（表现就是"图片下沿被裁掉一条"）。
     */
    const val AREA_GAP_DP = 6

    /**
     * 图片框的**硬底线**高度（dp）：低于它就不算"一张图"了，宁可不显示。
     *
     * 为什么不是 [PHOTO_MIN_DP]（56）：56 是"按宽高比算出来的理想值不会低于它"，
     * 属于**好看**的下限；而"能不能显示"是另一回事 —— 真机上小组件上报的余量常常只有 20~40dp，
     * 拿 56 当显示门槛，用户开了图片反而永远看不到（把"看得见但扁"变成了"看不见"）。
     */
    const val PHOTO_HARD_MIN_DP = 32

    /**
     * 小组件根布局的左右内边距合计（dp）：`widget_today.xml` 里 paddingStart/End 各 12dp。
     *
     * 图片框宽度是 `match_parent`，也就是**内容宽度**；算宽高比时得用内容宽度。
     * 这个常量与布局必须同步改（单测 [app.timetable.widget.WidgetExtrasPlanTest] 会读 XML 核对）。
     */
    const val CONTENT_INSET_DP = 24

    /**
     * 图片框的**理想高度**下限/上限（dp）。
     *
     * [PHOTO_MIN_DP]：按 16:9 算出来时不会低于这个高度（低于它连"一条图"都算不上）；
     * [PHOTO_MAX_DP]：也不会高于它 —— 既是观感上限（再高就不是课表下方的配图了），
     * 也顺手把过 binder 的像素数量按住（见 [PhotoBitmap.MAX_BITMAP_BYTES]）。
     */
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
