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
 * 全年农历/节日一览：一张 Canvas 画 12 个月，每个月 7 列。
 *
 * **为什么自绘**：一年 366 天，用 TextView 就是 366 个（加上月份标题与星期表头接近 500 个）
 * 实例，装进 LinearLayout 后光是 measure/layout 就要几十毫秒，滚动时还会持续触发
 * requestLayout；而这个视图只展示"日期 + 一到三个小圆点"，没有文本编辑、没有点击态动画，
 * 用 Canvas 一次画完更省内存也更快。点击靠坐标反推单元格，不需要任何子 View。
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

    /** 类别开关（默认全开；设置页会用 setGreetFlags 覆盖成用户的选择） */
    private var showSolar = true
    private var showLunar = true
    private var showSchool = true

    // ------------------------------------------------------------ 布局尺寸（onMeasure 算好）
    private var cols = 3
    private var rows = 4
    private var cellW = 0f
    private var cellH = 0f
    private var blockW = 0f
    private var blockH = 0f
    private var originX = 0f
    private var originY = 0f
    private var gapX = 0f
    private var gapY = 0f
    private var titleH = 0f
    private var weekH = 0f
    private var padInBlock = 0f

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
    internal fun holidayOf(date: LocalDate): List<Holidays.Holiday> =
        (if (date.year == year) holidayCache[date] ?: emptyList() else Holidays.of(date))
            .filter { enabled(it.kind) }

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

    // ------------------------------------------------------------ 测量

    /**
     * 高度按月份数算：行数 = ⌈12 / 列数⌉，每格高度跟着宽度走，所以整个视图是自适应的。
     * 宽度只切列数（窄屏 3 列、平板 4 列），不切字号 —— 字号再小就认不出"28"了。
     *
     * 放进 ScrollView 时必须用 wrap_content：这里始终上报完整高度，
     * 如果按外面给的 EXACTLY 高度压缩，下半年的格子会被裁掉。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val d = resources.displayMetrics.density

        cols = (w / d / MIN_MONTH_W_DP).toInt().coerceIn(3, 4)
        rows = (12 + cols - 1) / cols

        originX = dp(2f)
        originY = dp(4f)
        gapX = dp(10f)
        gapY = dp(12f)
        titleH = dp(15f)
        weekH = dp(10f)

        val columnW = (w - originX * 2 - gapX * (cols - 1)) / cols
        // 单元格宽度 = 列宽 / 7；上限 24dp 是为了平板上别把日历拉成一张巨表
        cellW = (columnW / 7f).coerceAtMost(dp(24f))
        cellH = cellW + dp(4f)          // 多出的 4dp 是给节日小圆点留的位置
        blockW = cellW * 7f
        // 列宽用不完时把月份块居中，避免左边贴边、右边空一截
        padInBlock = ((columnW - blockW) / 2f).coerceAtLeast(0f)
        blockH = titleH + weekH + cellH * 6f

        // 字号随格子走，保证 320dp 宽的机器上"28"也不会糊成一团
        val daySize = (cellW / d * 0.52f).coerceIn(7f, 10f)
        dayPaint.textSize = sp(daySize)
        selDayPaint.textSize = sp(daySize)
        todayTextPaint.textSize = sp(daySize)

        val h = originY * 2 + rows * blockH + gapY * (rows - 1)
        setMeasuredDimension(w, h.toInt().coerceAtLeast(1))
    }

    // ------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cellW <= 0f) return
        for (index in 0 until 12) {
            val cx = index % cols
            val cy = index / cols
            val x = originX + cx * (blockW + padInBlock * 2f + gapX) + padInBlock
            val y = originY + cy * (blockH + gapY)
            drawMonth(canvas, index + 1, x, y)
        }
    }

    private fun drawMonth(canvas: Canvas, month: Int, x0: Float, y0: Float) {
        // 月份标题（"1 月"…）：左对齐块首，视觉上与下面的列对齐
        canvas.drawText("$month 月", x0, y0 + titleH * 0.78f, titlePaint)

        // 星期表头。App 其余部分（课表、周次）都用「周一为第一天」，这里保持一致
        for (i in 0 until 7) {
            canvas.drawText(
                WEEK_CHARS[i],
                x0 + (i + 0.5f) * cellW,
                y0 + titleH + weekH * 0.82f,
                weekPaint
            )
        }

        val first = LocalDate.of(year, month, 1)
        val lead = first.dayOfWeek.value - 1          // 周一 = 0
        val len = first.lengthOfMonth()
        val gridTop = y0 + titleH + weekH
        val dotR = (cellH * 0.075f).coerceIn(dp(1.2f), dp(1.8f))

        for (day in 1..len) {
            val idx = lead + day - 1
            val col = idx % 7
            val row = idx / 7
            val left = x0 + col * cellW
            val top = gridTop + row * cellH
            val date = LocalDate.of(year, month, day)
            val isToday = date == today
            val isSelected = date == selected

            drawDay(canvas, date, day, left, top, isToday, isSelected, dotR)
        }
    }

    private fun drawDay(
        canvas: Canvas,
        date: LocalDate,
        day: Int,
        left: Float,
        top: Float,
        isToday: Boolean,
        isSelected: Boolean,
        dotR: Float
    ) {
        val cx = left + cellW / 2f
        val circleCy = top + cellH * 0.42f
        val radius = minOf(cellW * 0.36f, cellH * 0.32f)
        val baseline = top + cellH * 0.52f
        val label = day.toString()

        // 先画底：选中（淡底 + 圆环）、今天（实心圆），两者可同时成立 —— 今天被选中时
        // 就是"实心圆外面套一圈"，仍然能看出是今天
        if (isSelected) {
            canvas.drawCircle(cx, circleCy, radius, selFillPaint)
            canvas.drawCircle(cx, circleCy, radius, selRingPaint)
        }
        if (isToday) {
            canvas.drawCircle(cx, circleCy, radius, todayFillPaint)
        }

        // 优先"今天"：今天又正好被点中时，字色用底色的反色（白/黑），圆环仍然画着，
        // 两个信息都不丢
        val paint = when {
            isToday -> todayTextPaint
            isSelected -> selDayPaint
            else -> dayPaint
        }
        canvas.drawText(label, cx, baseline, paint)

        // 节日小圆点：最多三个（公历 / 农历 / 校历各一）。
        // 按"类"而不是按"节日个数"画 —— 元旦撞上腊八再撞上寒假开始，画一排点
        // 会在十几 dp 的格子里糊成一条线。
        val kinds = kindsOf(holidayCache[date] ?: emptyList())
        if (kinds.isEmpty()) return
        val spacing = dotR * 2f + dp(1.2f)
        val total = kinds.size * dotR * 2f + (kinds.size - 1) * dp(1.2f)
        var dotCx = cx - total / 2f + dotR
        val dotCy = top + cellH * 0.80f
        for (kind in kinds) {
            val paint = when (kind) {
                Holidays.Kind.SOLAR -> dotSolarPaint
                Holidays.Kind.LUNAR -> dotLunarPaint
                Holidays.Kind.SCHOOL -> dotSchoolPaint
            }
            canvas.drawCircle(dotCx, dotCy, dotR, paint)
            dotCx += spacing
        }
    }

    /** 按固定顺序去重出当天涉及的节日类别（最多 3 个） */
    private fun kindsOf(hits: List<Holidays.Holiday>): List<Holidays.Kind> {
        if (hits.isEmpty()) return emptyList()
        val out = ArrayList<Holidays.Kind>(3)
        for (kind in KIND_ORDER) {
            // 关掉的类别连点都不画：设置里三个开关和这里的三种颜色是一一对应的，
            // 关掉之后日历上还留着一排点会让人以为开关没生效
            if (enabled(kind) && hits.any { it.kind == kind }) out += kind
        }
        return out
    }

    /**
     * 跟随设置里的三个类别开关（公历 / 农历 / 校历）。
     * 关掉的类别不画点、点某天也查不到 —— 单一判据，避免"看不到点但点进去有内容"。
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
                val hit = dayAt(event.x, event.y)
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

    /**
     * 坐标反推日期。这一步替代了"每个格子一个 View"的命中测试，
     * 所以边界要自己严格校验：点到星期表头、月份块之间的空隙、不存在的日期（月初前、
     * 月末后）都必须返回 null，否则会回报一个"看得见但不在那里"的日期。
     */
    private fun dayAt(x: Float, y: Float): LocalDate? {
        if (cellW <= 0f || x < originX || y < originY) return null
        val colStride = blockW + padInBlock * 2f + gapX
        val col = ((x - originX) / colStride).toInt()
        if (col < 0 || col >= cols) return null
        val rowBlock = ((y - originY) / (blockH + gapY)).toInt()
        if (rowBlock < 0 || rowBlock >= rows) return null

        val monthIndex = rowBlock * cols + col
        if (monthIndex >= 12) return null

        val blockLeft = originX + col * colStride + padInBlock
        val blockTop = originY + rowBlock * (blockH + gapY)
        val gx = x - blockLeft
        val gy = y - blockTop - titleH - weekH
        if (gx < 0f || gx >= blockW || gy < 0f) return null

        val cellCol = (gx / cellW).toInt()
        val cellRow = (gy / cellH).toInt()
        if (cellCol !in 0..6 || cellRow !in 0..5) return null

        val month = monthIndex + 1
        val first = LocalDate.of(year, month, 1)
        val lead = first.dayOfWeek.value - 1
        val day = cellRow * 7 + cellCol - lead + 1
        if (day < 1 || day > first.lengthOfMonth()) return null
        return LocalDate.of(year, month, day)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        /** 一个月份块的最小可用宽度：再窄就放不下"28"两个字 + 三个圆点 */
        private const val MIN_MONTH_W_DP = 104f

        /** 星期表头，周一为第一天（与 WeekCalc / 课表一致） */
        private val WEEK_CHARS = arrayOf("一", "二", "三", "四", "五", "六", "日")

        /** 圆点的绘制顺序，与设置页里三个开关的顺序一致 */
        private val KIND_ORDER = listOf(
            Holidays.Kind.SOLAR,
            Holidays.Kind.LUNAR,
            Holidays.Kind.SCHOOL
        )
    }
}
