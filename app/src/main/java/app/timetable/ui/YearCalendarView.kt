package app.timetable.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import app.timetable.R
import app.timetable.greet.Holidays
import java.time.LocalDate

/**
 * 全年农历/节日一览：一张 Canvas 画 12 个月，每个月 7 列，**格子里直接写节日名字**
 * （日期数字下面一行，例如"1 元旦""19 端午"），不再依赖日历外面那张节日清单。
 *
 * **为什么自绘**：一年 366 天，用 TextView 就是 366 个（加上月份标题与星期表头接近 500 个）
 * 实例，装进 LinearLayout 后光是 measure/layout 就要几十毫秒，滚动时还会持续触发
 * requestLayout；而这个视图只展示"日期 + 一个节日名 + 最多三个小圆点"，没有文本编辑、
 * 没有点击态动画，用 Canvas 一次画完更省内存也更快。点击靠坐标反推单元格，不需要任何子 View。
 *
 * **为什么格子必须变大**：名字要占一行，一格至少要放得下 2 个全角汉字。汉字是方体，
 * 1 个字宽 = 1 个字号宽，窄屏上名字字号约 7dp —— 也就是说一格至少要 ~19dp 宽。
 * 原来的 3 列（360dp 屏上一格只有 13dp）根本放不下，所以窄屏列数从 3 降成 2：
 * 宽度翻倍（13dp → 20dp），代价是 12 个月从 4 行变成 6 行，整个日历高度接近翻倍。
 * 具体数字、以及"为什么不再用 sp 定字号"，见 [CalendarLayout] 里的注释。
 *
 * 视图本身**只读**：它不认识设置项，也不负责"节日祝福开关"，那些逻辑由调用方接线。
 */
class YearCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 点击某一天的回调（只在点到真实存在的日期时触发，月份标题/表头/空白格不触发） */
    var onDayTap: ((LocalDate) -> Unit)? = null

    private val today: LocalDate = LocalDate.now()
    private var year: Int = today.year
    private var selected: LocalDate? = today

    /**
     * 全年节日缓存：只存"有节日的那几天"。onDraw 里如果每格都去算节日，
     * 一帧就是 372 次公历→农历换算（每次要从 1900 年逐年累加），滚动时肉眼可见地掉帧。
     */
    private var holidayCache: Map<LocalDate, List<Holidays.Holiday>> = emptyMap()

    /**
     * 最近一次测量的结果（onMeasure 算好，onDraw / onTouchEvent 只读）。
     * 里面的量与单位全是 px，换算逻辑在 [CalendarLayout] 里，这里只负责用。
     */
    private var metrics: CalendarLayout.Metrics? = null

    /** 类别开关（默认全开；设置页会用 setGreetFlags 覆盖成用户的选择） */
    private var showSolar = true
    private var showLunar = true
    private var showSchool = true

    // ------------------------------------------------------------ 画笔

    private val accentColor: Int = context.getColor(R.color.accent)

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        typeface = Typeface.DEFAULT_BOLD
        textSize = sp(11f)
    }
    private val weekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_tertiary)
        textSize = sp(7.5f)
        textAlign = Paint.Align.CENTER
    }
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_primary)
        textSize = sp(9f)
        textAlign = Paint.Align.CENTER
    }
    private val selDayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        textSize = sp(9f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    /**
     * 节日名。颜色用 text_secondary 而不是按类别上色（红=农历/绿=校历）：
     * 类别已经由下面那排小圆点表达了，名字再用三种颜色，一是把 7dp 的小字压进低对比度的
     * 红绿（浅色底上比灰字难认），二是会让一屏 12 个月看起来花花绿绿，
     * "哪天是节"这个主要信息反而被颜色抢走注意力。
     *
     * 字号不在这里写死：每格宽度随屏宽变，字号由 [CalendarLayout] 算好（nameTextPx），
     * 在 onMeasure 里统一赋值 —— 与日期数字同源，不会两边对不上。
     */
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        textAlign = Paint.Align.CENTER
    }

    /** 今天：实心圆 + 反色数字，一眼就能在 12 个月里找到 */
    private val todayFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.FILL
    }
    private val todayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 深色模式下 accent 是浅蓝（#6FA8F5），白字会糊；用页面底色当字色才够对比
        color = context.getColor(R.color.page_bg)
        textSize = sp(9f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    /** 选中：淡色圆底 + 描边圆环，与"今天"的实心圆明确区分 */
    private val selFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.FILL
        alpha = 38
    }
    private val selRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }

    /**
     * 三类节日的小圆点。颜色刻意不用同一支画笔的三个 alpha：
     * 圆点只有 3dp，靠透明度区分在阳光下根本看不出，必须用色相区分。
     *
     * 名字已经写了"主要是什么节"，圆点为什么还留着：两者表达的不是一件事 ——
     * 圆点表达"这天有几类节日"，名字表达"最主要的是哪一个"。
     * 元旦撞上腊八时，名字写"元旦"，但三个点里有红色的那个才说明农历也有事。
     */
    private val dotSolarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        style = Paint.Style.FILL
    }
    private val dotLunarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (Ui.isNight(context)) 0xFFFF9E80.toInt() else 0xFFE0523C.toInt()
        style = Paint.Style.FILL
    }
    private val dotSchoolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (Ui.isNight(context)) 0xFF9CC7A8.toInt() else 0xFF4F8A66.toInt()
        style = Paint.Style.FILL
    }

    init {
        isClickable = true
        rebuildCache()
    }

    /** 设置显示年份并重建节日缓存（12 个月全画，高度不变，所以不需要 requestLayout） */
    fun setYear(year: Int) {
        if (this.year == year) return
        this.year = year
        selected = selected?.takeIf { it.year == year } ?: today.takeIf { it.year == year }
        rebuildCache()
        invalidate()
    }

    /** 某天的节日（当前年份走缓存，其它年份现算）。internal：Holidays 本身是 internal */
    internal fun holidayOf(date: LocalDate): List<Holidays.Holiday> = visibleHits(date)

    private fun rebuildCache() {
        val map = HashMap<LocalDate, List<Holidays.Holiday>>(32)
        var d = LocalDate.of(year, 1, 1)
        val end = LocalDate.of(year, 12, 31)
        while (!d.isAfter(end)) {
            val hits = Holidays.of(d)
            if (hits.isNotEmpty()) map[d] = hits
            d = d.plusDays(1)
        }
        holidayCache = map
    }

    /**
     * 当天**按开关过滤后**还应该显示的节日。名字和圆点都只走这一条路：
     * 关掉的类别不画点、也不写名字，点某天同样查不到 —— 单一判据，
     * 避免"看不到东西但点进去有内容"。
     *
     * 三个开关全开时直接返回缓存里的原表（不新建 List）：那是最常见的情况，
     * 一帧 372 个格子每个都 filter 一次是白白的分配。
     */
    private fun visibleHits(date: LocalDate): List<Holidays.Holiday> {
        val all = if (date.year == year) holidayCache[date] ?: return emptyList() else Holidays.of(date)
        if (all.isEmpty()) return emptyList()
        if (showSolar && showLunar && showSchool) return all
        return all.filter { enabled(it.kind) }
    }

    // ------------------------------------------------------------ 测量

    /**
     * 几何计算全部交给 [CalendarLayout.layoutFor]（纯函数，能在 JVM 单测里断言），
     * 这里只做三件事：把宽度换成 dp、把算出来的字号灌进画笔、上报高度。
     *
     * 放进 ScrollView 时必须用 wrap_content：这里**始终上报完整高度**，
     * 如果按外面给的 EXACTLY 高度压缩，下半年的格子会被裁掉。
     * 改动后窄屏变成 2 列 6 行，高度公式里的 rows 跟着变 —— 逻辑没动，只是块变了。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val d = resources.displayMetrics.density
        val m = CalendarLayout.layoutFor(
            widthDp = w / d,
            density = d,
            fontScale = resources.configuration.fontScale
        )
        metrics = m

        // 格内文字的字号跟着格子走（px），不再用 sp —— 格子宽度由屏宽决定、不会随系统字号变宽，
        // 用 sp 的话系统字体一放大，2 个汉字就会横向顶出格子。取舍与封顶系数见 FONT_SCALE_CAP。
        dayPaint.textSize = m.dayTextPx
        selDayPaint.textSize = m.dayTextPx
        todayTextPaint.textSize = m.dayTextPx
        namePaint.textSize = m.nameTextPx

        setMeasuredDimension(w, m.totalHeight.toInt().coerceAtLeast(1))
    }

    // ------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = metrics ?: return
        if (m.cellW <= 0f) return
        for (index in 0 until CalendarLayout.MONTHS) {
            drawMonth(canvas, index + 1, CalendarLayout.blockLeft(index, m), CalendarLayout.blockTop(index, m), m)
        }
    }

    private fun drawMonth(canvas: Canvas, month: Int, x0: Float, y0: Float, m: CalendarLayout.Metrics) {
        // 月份标题（"1 月"…）：左对齐块首，视觉上与下面的列对齐
        canvas.drawText("$month 月", x0, y0 + m.titleH * 0.78f, titlePaint)

        // 星期表头。App 其余部分（课表、周次）都用「周一为第一天」，这里保持一致
        for (i in 0 until CalendarLayout.DAYS_PER_WEEK) {
            canvas.drawText(
                WEEK_CHARS[i],
                x0 + (i + 0.5f) * m.cellW,
                y0 + m.titleH + m.weekH * 0.82f,
                weekPaint
            )
        }

        val first = LocalDate.of(year, month, 1)
        val lead = CalendarLayout.leadOffset(year, month)
        val len = first.lengthOfMonth()

        for (day in 1..len) {
            val left = x0 + CalendarLayout.cellLeftInBlock(day, lead, m)
            val top = y0 + CalendarLayout.cellTopInBlock(day, lead, m)
            drawDay(canvas, LocalDate.of(year, month, day), day, left, top, m)
        }
    }

    private fun drawDay(
        canvas: Canvas,
        date: LocalDate,
        day: Int,
        left: Float,
        top: Float,
        m: CalendarLayout.Metrics
    ) {
        val cx = left + m.cellW / 2f
        val circleCy = top + m.circleCy
        // 圆只包住**日期那一行**，不再按整格高度算：整格里现在多了名字行，
        // 按老公式（0.42 × 格高）画出来的圆会盖到名字上。
        val radius = m.circleRadius

        // 先画底：选中（淡底 + 圆环）、今天（实心圆），两者可同时成立 —— 今天被选中时
        // 就是"实心圆外面套一圈"，仍然能看出是今天
        if (date == selected) {
            canvas.drawCircle(cx, circleCy, radius, selFillPaint)
            canvas.drawCircle(cx, circleCy, radius, selRingPaint)
        }
        if (date == today) {
            canvas.drawCircle(cx, circleCy, radius, todayFillPaint)
        }

        // 优先"今天"：今天又正好被点中时，字色用底色的反色（白/黑），圆环仍然画着，
        // 两个信息都不丢
        val paint = when {
            date == today -> todayTextPaint
            date == selected -> selDayPaint
            else -> dayPaint
        }
        canvas.drawText(day.toString(), cx, top + m.dateBaselineY, paint)

        val hits = visibleHits(date)
        if (hits.isEmpty()) return

        // 名字：同一天多个节日只画优先级最高的那一个（公历 > 农历 > 校历）
        CalendarLayout.primaryHoliday(hits)?.let { top1 ->
            val label = CalendarLayout.fitName(top1.name)
            if (label.isNotEmpty()) {
                canvas.drawText(label, cx, top + m.nameBaselineY, namePaint)
            }
        }

        // 圆点：最多三个（公历 / 农历 / 校历各一）。
        // 按"类"而不是按"节日个数"画 —— 元旦撞上腊八再撞上寒假开始，画一排点
        // 会在十几 dp 的格子里糊成一条线。
        val kinds = CalendarLayout.kindsOf(hits)
        for (i in kinds.indices) {
            val dotCx = CalendarLayout.dotCenterX(
                index = i,
                count = kinds.size,
                cellCenterX = cx,
                dotRadius = m.dotRadius,
                gapPx = m.dotGapPx
            )
            canvas.drawCircle(dotCx, top + m.dotCenterY, m.dotRadius, dotPaint(kinds[i]))
        }
    }

    private fun dotPaint(kind: Holidays.Kind): Paint = when (kind) {
        Holidays.Kind.SOLAR -> dotSolarPaint
        Holidays.Kind.LUNAR -> dotLunarPaint
        Holidays.Kind.SCHOOL -> dotSchoolPaint
    }

    /**
     * 跟随设置里的三个类别开关（公历 / 农历 / 校历）。
     * 关掉的类别不画点、不写名字、点某天也查不到 —— 单一判据。
     * 名字长度固定为 2 个字，不随开关变化，所以不需要 requestLayout，invalidate 就够。
     */
    fun setGreetFlags(solar: Boolean, lunar: Boolean, school: Boolean) {
        if (showSolar == solar && showLunar == lunar && showSchool == school) return
        showSolar = solar
        showLunar = lunar
        showSchool = school
        invalidate()
    }

    private fun enabled(kind: Holidays.Kind): Boolean = when (kind) {
        Holidays.Kind.SOLAR -> showSolar
        Holidays.Kind.LUNAR -> showLunar
        Holidays.Kind.SCHOOL -> showSchool
    }

    // ------------------------------------------------------------ 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val m = metrics
                val hit = if (m == null) null else CalendarLayout.dateAt(event.x, event.y, year, m)
                if (hit != null) {
                    performClick()
                    selected = hit
                    invalidate()
                    onDayTap?.invoke(hit)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        /** 星期表头，周一为第一天（与 WeekCalc / 课表一致） */
        private val WEEK_CHARS = arrayOf("一", "二", "三", "四", "五", "六", "日")
    }
}
