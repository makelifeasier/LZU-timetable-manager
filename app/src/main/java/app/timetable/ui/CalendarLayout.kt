package app.timetable.ui

import app.timetable.greet.Holidays
import java.time.LocalDate

/**
 * 年历的纯排版计算：排几列几行、格子多大、格内字号多少、名字截断到几个字、
 * 同一天命中多个节日时画哪个、以及"点到哪一天"的坐标反推。
 *
 * **为什么要单独抽一个文件**：这些量原本散在 [YearCalendarView] 的 onMeasure / onDraw /
 * onTouchEvent 里，必须经过 MeasureSpec、Paint、Canvas 才算得出来。而本项目的单测开着
 * `testOptions.unitTests.isReturnDefaultValues = true` —— Android 的 API 在 JVM 单测里全是
 * 返回 0/null 的 stub，`Paint.measureText` 恒等于 0，写出来的断言等于什么都没测。
 * 把算术搬到这里之后，"12 个月排成几行""2 个汉字塞不塞得进格子""点到 1 号左边那格该不该
 * 返回 1 号"这些最容易错、错了又最难看出来的地方都能用纯 JVM 断言钉死。
 * 因此：**这个文件不允许 import 任何 android.\* / androidx.\* / app.timetable.R**。
 *
 * 单位约定：入参用 dp 与 density，出参 [Metrics] 全部是 **px**。
 * 只在最末尾乘一次 density，调用方拿到就能直接画 —— 少一次手动换算就少一处漏乘的机会。
 */
internal object CalendarLayout {

    /** 一年 12 个月全部画出来（不分页、不折叠） */
    const val MONTHS = 12

    /** 一周 7 天；周一排第一列，与 WeekCalc / 课表 / 周次口径一致 */
    const val DAYS_PER_WEEK = 7

    /** 一个月最多占 6 行格子：1 号落在周日（前置空格 6 个）且当月 31 天时正好排满 6 行 */
    const val WEEKS_PER_MONTH = 6

    /**
     * 格子里最多画几个汉字。
     *
     * 规则定死为「取前 2 个字，不加省略号」，三条理由：
     *  1. 节日名绝大多数是 2~3 字（元旦 / 劳动节 / 国庆节 / 春节 / 中秋 / 校庆…），
     *     取前 2 字既是最短可辨识长度，也正好是窄屏格宽能放下的上限 ——
     *     汉字是全角，1 个字占 1 个字号宽，2 个字就是 2×字号；
     *  2. 一个"…"要额外占 0.5~1 个字宽，窄屏（格宽约 20dp、名字字号约 7dp）加上就会顶出格子；
     *  3. **不做"格子宽就多画一个字"的浮动规则**：那会让同一个节日在手机上显示"国庆"、
     *     在平板上显示"国庆节"，用户截图对比时反而以为出了 bug。宁可处处一致。
     *
     * 已知的别扭之处（不粉饰）：3 字名截成前 2 字偶尔会读起来怪，例如「龙抬头」→「龙抬」。
     * 修法是在这张表外再挂一份"简称表"，但那多一份数据就多一处和 Holidays 漂移的可能，
     * 当前不值当 —— 真要改，改 2 字上限为 3 字并同步调大 MIN_CELL_W_DP 即可。
     */
    const val NAME_MAX_CHARS = 2

    // ------------------------------------------------------------------ 几何常量（dp）

    private const val ORIGIN_X_DP = 2f
    private const val ORIGIN_Y_DP = 4f
    private const val GAP_X_DP = 10f
    /** 行间距：块与块之间留 10dp，比原来的 12dp 小一点，抵掉一部分"2 列变 6 行"多出来的高度 */
    private const val GAP_Y_DP = 10f
    private const val TITLE_H_DP = 15f
    private const val WEEK_H_DP = 10f

    /**
     * 一个月份块里最多/最少排几个月。**窄屏从 3 列改成 2 列**是这次改动的核心：
     * 360dp 屏在设置页里可用宽度约 296dp，3 列时一格只有 13dp 宽，
     * 连"28"两个字都勉强，更画不下节日名；2 列时一格约 20dp，正好放下「数字 + 2 字名」。
     */
    private const val MIN_COLS = 2
    private const val MAX_COLS = 4

    /**
     * 一格的最小宽度。19dp 是反推出来的：名字字号 = 格宽 × [NAME_TEXT_RATIO]，
     * 19dp 对应 6.8dp，大约就是汉字还能认出笔画的下限。
     * 再窄就只能靠 [NAME_TEXT_MIN_DP] 的 6dp 兜底，笔画糊成一团 —— 所以宁可少排一列，
     * 让每个月份块占满整行，也不把 12 个月硬塞成 4 列。
     *
     * 注：可用宽度窄到 ~190dp 以下时（对应 254dp 的屏，本 App 的 minSdk 31 真机上不会出现），
     * 2 列也已经不够 19dp，这时 columnsFor 仍返回 2 列，6dp 的字会顶到格子边。
     * 与其为不存在的屏加一条 1 列的退化路径（12 行 × 12 个月，高到没法看），不如留这条边界。
     */
    private const val MIN_CELL_W_DP = 19f

    /**
     * 一格的最大宽度。上限的存在意义是把多出来的宽度还给月份块内部的居中留白
     * （[Metrics.padInBlock]），而不是让平板上 12 个月被拉成一张横跨整屏的巨表。
     * 28dp = 2 个 8dp 汉字（16dp）+ 两侧各 6dp 余量，够宽裕了。
     */
    private const val MAX_CELL_W_DP = 28f

    /** 日期数字字号 = 格宽 × 0.52，夹在 7~10dp：再小认不出"28"，再大格子里塞不下第二行名字 */
    private const val DAY_TEXT_RATIO = 0.52f
    private const val DAY_TEXT_MIN_DP = 7f
    private const val DAY_TEXT_MAX_DP = 10f

    /** 节日名字号 = 格宽 × 0.36，夹在 6~8dp：比日期小一号，但不小于 6dp（汉字的可辨认下限） */
    private const val NAME_TEXT_RATIO = 0.36f
    private const val NAME_TEXT_MIN_DP = 6f
    private const val NAME_TEXT_MAX_DP = 8f

    /**
     * 系统字体缩放的**封顶系数**。格内文字跟随系统字号，但最多跟到 1.3 倍。
     *
     * 为什么必须封顶：格子宽度由"屏宽 ÷ 列数"唯一决定，不随字号变宽。名字要放 2 个全角汉字，
     * 渲染宽度 = 2 × 字号 × 字体缩放。格宽 20dp、名字字号 7.25dp 时：
     *   缩放 1.0 → 14.5dp（富余 5.5dp）
     *   缩放 1.3 → 18.9dp（富余 1.2dp，刚好还在格子里）
     *   缩放 1.5 → 21.8dp（**顶出格子**，相邻两天的名字首尾相接，比字不放大更难看清）
     * 所以 1.3 是"保证名字永远在自己格子里"这条不变式所能容忍的最大值，再大就按 1.3 封顶。
     * 代价写在这里：把系统字号调到 1.5 以上的用户，看到的日历字不会跟着继续变大 ——
     * 这是有意的取舍（真到了需要 1.5 倍字号的视力，7 列的年历本来就不该是主要入口）。
     */
    const val FONT_SCALE_CAP = 1.3f

    /** 格内三行（日期 / 名字 / 圆点）的高度系数：前两行按行距算，第三行给圆点固定 5dp */
    private const val DATE_ROW_MUL = 1.20f
    private const val NAME_ROW_MUL = 1.20f
    private const val DOT_ROW_DP = 5f

    /** 圆点半径 = 格高 × 0.075，夹在 1.2~1.8dp（沿用改动前的手感） */
    private const val DOT_RADIUS_RATIO = 0.075f
    private const val DOT_RADIUS_MIN_DP = 1.2f
    private const val DOT_RADIUS_MAX_DP = 1.8f

    /** 圆点之间的间距（dp），也是圆点这一行的横向排布依据 */
    const val DOT_GAP_DP = 1.2f

    /** "今天/选中"的圆半径 = min(格宽 × 0.36, 日期行高的一半) —— 让它只包住数字，不压到下面的名字 */
    private const val CIRCLE_R_RATIO = 0.36f

    /** 文字基线在行中心下方 0.36em 处，视觉上才是"居中"的（汉字/数字的字面中心比行盒中心偏下） */
    private const val BASELINE_RATIO = 0.36f

    /** 圆点的绘制顺序，与设置页里三个开关的顺序一致 */
    private val KIND_ORDER = listOf(
        Holidays.Kind.SOLAR,
        Holidays.Kind.LUNAR,
        Holidays.Kind.SCHOOL
    )

    // ------------------------------------------------------------------ 测量结果

    /**
     * 一次测量算出来的全部几何量，单位 **px**，坐标都是"相对视图左上角"或标注过的相对量。
     *
     * 做成不可变数据类：onMeasure 算一次，onDraw / onTouchEvent 直接读同一个实例，
     * 不会出现"测量时改了字号、绘制时忘了改"这种两份状态漂移（原来的写法就是
     * cellW/cellH/... 十来个字段各自维护）。
     */
    data class Metrics(
        val cols: Int,
        val rows: Int,
        val cellW: Float,
        val cellH: Float,
        val blockW: Float,
        val blockH: Float,
        val padInBlock: Float,
        val originX: Float,
        val originY: Float,
        val gapX: Float,
        val gapY: Float,
        val titleH: Float,
        val weekH: Float,
        val dayTextPx: Float,
        val nameTextPx: Float,
        val dotRadius: Float,
        val dotGapPx: Float,
        /** 日期基线，相对格子顶 */
        val dateBaselineY: Float,
        /** 节日名基线，相对格子顶 */
        val nameBaselineY: Float,
        /** 圆点行的圆心 y，相对格子顶 */
        val dotCenterY: Float,
        /** "今天/选中"圆环的圆心 y，相对格子顶（= 日期行中心） */
        val circleCy: Float,
        val circleRadius: Float,
        /** 整个视图应该上报的高度：始终是完整高度，供 ScrollView 用 wrap_content 测量 */
        val totalHeight: Float
    ) {
        /** 月份块内网格的起始 y（月份标题 + 星期表头之下） */
        val gridTop: Float get() = titleH + weekH

        /** 相邻两列月份块左上角的横距：块宽 + 块内左右居中留白 + 列间距 */
        val blockStrideX: Float get() = blockW + padInBlock * 2f + gapX

        /** 相邻两行月份块左上角的纵距 */
        val blockStrideY: Float get() = blockH + gapY
    }

    // ------------------------------------------------------------------ 列数 / 格子尺寸

    /**
     * 12 个月排成几列。从最宽的 4 列往下试，取第一个"格子还够宽"的列数：
     * 屏越宽月份排得越多，同时保证每一格都塞得下「日期数字 + 2 字节日名」。
     * 都试不出来（屏太窄）时退回 [MIN_COLS]（2 列），靠名字字号的 6dp 下限兜底。
     */
    fun columnsFor(widthDp: Float): Int {
        for (cols in MAX_COLS downTo MIN_COLS) {
            if (cellWidthDp(widthDp, cols) >= MIN_CELL_W_DP) return cols
        }
        return MIN_COLS
    }

    /** 列宽（一个月份块可以占的横向空间，含块内居中留白） */
    private fun columnWidthDp(widthDp: Float, cols: Int): Float {
        val usable = widthDp - ORIGIN_X_DP * 2f - GAP_X_DP * (cols - 1)
        return (usable / cols).coerceAtLeast(0f)
    }

    /** 一格宽度（一个月份块横向再切 7 天），已夹过 [MAX_CELL_W_DP] */
    fun cellWidthDp(widthDp: Float, cols: Int): Float =
        (columnWidthDp(widthDp, cols) / DAYS_PER_WEEK).coerceAtMost(MAX_CELL_W_DP)

    /**
     * 按可用宽度算出整套几何量。
     *
     * @param widthDp 视图可用的宽度（dp），真机上是"屏宽 − 页面左右 padding − 卡片内边距"
     * @param density 像素密度，用来把 dp 换算成 [Metrics] 里的 px
     * @param fontScale 系统字体缩放（`resources.configuration.fontScale`），按 [FONT_SCALE_CAP] 封顶
     */
    fun layoutFor(widthDp: Float, density: Float, fontScale: Float = 1f): Metrics {
        val cols = columnsFor(widthDp)
        val rows = (MONTHS + cols - 1) / cols

        val cellWDp = cellWidthDp(widthDp, cols)
        val dayDp = (cellWDp * DAY_TEXT_RATIO).coerceIn(DAY_TEXT_MIN_DP, DAY_TEXT_MAX_DP)
        val nameDp = (cellWDp * NAME_TEXT_RATIO).coerceIn(NAME_TEXT_MIN_DP, NAME_TEXT_MAX_DP)

        // 格内文字跟随系统字号，但封顶 —— 理由见 FONT_SCALE_CAP
        val scale = fontScale.coerceIn(1f, FONT_SCALE_CAP)
        val dayTextDp = dayDp * scale
        val nameTextDp = nameDp * scale

        // 一格纵向分三行：日期行 + 名字行 + 圆点行。
        // 行高按字号算，所以任何宽度下"数字 + 名字"都不会在纵向叠在一起。
        val dateRowH = dayTextDp * DATE_ROW_MUL
        val nameRowH = nameTextDp * NAME_ROW_MUL
        val cellHDp = dateRowH + nameRowH + DOT_ROW_DP

        val blockWDp = cellWDp * DAYS_PER_WEEK
        val padInBlockDp = (columnWidthDp(widthDp, cols) - blockWDp) / 2f
        val blockHDp = TITLE_H_DP + WEEK_H_DP + cellHDp * WEEKS_PER_MONTH
        val totalHeightDp =
            ORIGIN_Y_DP * 2f + rows * blockHDp + GAP_Y_DP * (rows - 1).coerceAtLeast(0)

        val dotRadiusDp = (cellHDp * DOT_RADIUS_RATIO)
            .coerceIn(DOT_RADIUS_MIN_DP, DOT_RADIUS_MAX_DP)

        // 三行各自的光学居中：基线放在行中心下方 0.36em
        val dateBaselineDp = dateRowH / 2f + dayTextDp * BASELINE_RATIO
        val nameBaselineDp = dateRowH + nameRowH / 2f + nameTextDp * BASELINE_RATIO
        val dotCenterDp = dateRowH + nameRowH + DOT_ROW_DP / 2f
        val circleRadiusDp = minOf(cellWDp * CIRCLE_R_RATIO, dateRowH / 2f)

        // 只在这里乘一次 density，之后整条绘制链路都是 px
        return Metrics(
            cols = cols,
            rows = rows,
            cellW = cellWDp * density,
            cellH = cellHDp * density,
            blockW = blockWDp * density,
            blockH = blockHDp * density,
            padInBlock = padInBlockDp.coerceAtLeast(0f) * density,
            originX = ORIGIN_X_DP * density,
            originY = ORIGIN_Y_DP * density,
            gapX = GAP_X_DP * density,
            gapY = GAP_Y_DP * density,
            titleH = TITLE_H_DP * density,
            weekH = WEEK_H_DP * density,
            dayTextPx = dayTextDp * density,
            nameTextPx = nameTextDp * density,
            dotRadius = dotRadiusDp * density,
            dotGapPx = DOT_GAP_DP * density,
            dateBaselineY = dateBaselineDp * density,
            nameBaselineY = nameBaselineDp * density,
            dotCenterY = dotCenterDp * density,
            circleCy = dateRowH / 2f * density,
            circleRadius = circleRadiusDp * density,
            totalHeight = totalHeightDp * density
        )
    }

    // ------------------------------------------------------------------ 月份块 / 格子位置

    /** 第 [index] 个月（0-based）的月份块左上角 x */
    fun blockLeft(index: Int, m: Metrics): Float =
        m.originX + (index % m.cols) * m.blockStrideX + m.padInBlock

    /** 第 [index] 个月（0-based）的月份块左上角 y */
    fun blockTop(index: Int, m: Metrics): Float =
        m.originY + (index / m.cols) * m.blockStrideY

    /** 当月 1 号之前要空出几格：周一 = 0 */
    fun leadOffset(year: Int, month: Int): Int =
        LocalDate.of(year, month, 1).dayOfWeek.value - 1

    /**
     * 某天在月份块内的格子左边界（相对块左）。
     * 抽成函数是为了让"绘制"和"命中测试"共用同一份换算，避免两边各写一遍再对不上。
     */
    fun cellLeftInBlock(day: Int, lead: Int, m: Metrics): Float =
        ((lead + day - 1) % DAYS_PER_WEEK) * m.cellW

    /** 某天在月份块内的格子顶边（相对块顶，已含标题与星期表头的高度） */
    fun cellTopInBlock(day: Int, lead: Int, m: Metrics): Float =
        m.gridTop + ((lead + day - 1) / DAYS_PER_WEEK) * m.cellH

    /**
     * 圆点行里第 [index] 个圆心的 x（相对格子中心对称排布）。
     * 按"类"而不是按"节日个数"画，所以 count 最多是 3。
     */
    fun dotCenterX(index: Int, count: Int, cellCenterX: Float, dotRadius: Float, gapPx: Float): Float {
        if (count <= 0) return cellCenterX
        val total = count * dotRadius * 2f + (count - 1) * gapPx
        return cellCenterX - total / 2f + dotRadius + index * (dotRadius * 2f + gapPx)
    }

    // ------------------------------------------------------------------ 命中测试

    /**
     * 坐标反推日期。这一步替代了"每个格子一个 View"的命中测试，所以边界必须自己严格校验：
     * 点到星期表头、月份块之间的空隙、不存在的日期（月初前 / 月末后）都要返回 null，
     * 否则会回报一个"看得见但不在那里"的日期。
     */
    fun dateAt(x: Float, y: Float, year: Int, m: Metrics): LocalDate? {
        if (m.cellW <= 0f || x < m.originX || y < m.originY) return null

        val col = ((x - m.originX) / m.blockStrideX).toInt()
        if (col < 0 || col >= m.cols) return null
        val rowBlock = ((y - m.originY) / m.blockStrideY).toInt()
        if (rowBlock < 0 || rowBlock >= m.rows) return null

        val monthIndex = rowBlock * m.cols + col
        if (monthIndex >= MONTHS) return null
        val month = monthIndex + 1

        val gx = x - blockLeft(monthIndex, m)
        val gy = y - blockTop(monthIndex, m) - m.gridTop
        if (gx < 0f || gx >= m.blockW || gy < 0f) return null

        val cellCol = (gx / m.cellW).toInt()
        val cellRow = (gy / m.cellH).toInt()
        if (cellCol !in 0 until DAYS_PER_WEEK || cellRow !in 0 until WEEKS_PER_MONTH) return null

        val day = cellRow * DAYS_PER_WEEK + cellCol - leadOffset(year, month) + 1
        if (day < 1 || day > LocalDate.of(year, month, 1).lengthOfMonth()) return null
        return LocalDate.of(year, month, day)
    }

    // ------------------------------------------------------------------ 节日名 / 优先级

    /**
     * 格子里要画的节日名：按 [NAME_MAX_CHARS] 截断。
     * 空串表示"这个名字不该画"（调用方直接跳过，避免在格子里留下一行空白）。
     */
    fun fitName(name: String, maxChars: Int = NAME_MAX_CHARS): String {
        if (maxChars <= 0) return ""
        if (name.length <= maxChars) return name
        // 按码点截断：当前节日名全是 BMP 汉字（length == 字数），多这几行只是不让将来加进来的
        // 非 BMP 字符（生僻字/emoji）被切成半个代理对，在格子里画出一个乱码方块
        var end = 0
        var taken = 0
        while (end < name.length && taken < maxChars) {
            end += Character.charCount(name.codePointAt(end))
            taken++
        }
        return name.substring(0, end)
    }

    /**
     * 同一天命中多个节日时，格子里只画优先级最高的那一个（圆点仍然全部保留 ——
     * 圆点表达"有几类节日"，名字表达"主要是什么节"，两者不冲突）。
     *
     * 优先级 **公历 > 农历 > 校历**，理由不是"公历更正统"，而是：
     *  1. 与 [Holidays.of] 的返回顺序、祝福弹窗里第一条、以及设置页节日清单的排序完全一致 ——
     *     日历上写"国庆"、点开第一条也是"国庆"，用户不会看到两套口径；
     *  2. 公历这一档里装着元旦 / 劳动节 / 国庆节这类**法定放假**的日子，
     *     对"我哪天不用上课"最有决策价值，占唯一的名字位最划算；
     *  3. 校历节点排最后：它们自己的 greeting 都写着「参考节点，非官方校历」，
     *     三个类别里可信度最低的一个不该霸占名字。
     *
     * 返回值用 [kindsOf] 的同一套顺序判定，不依赖入参 List 的既有顺序（那是实现细节，不该被依赖）。
     */
    fun primaryHoliday(hits: List<Holidays.Holiday>): Holidays.Holiday? {
        if (hits.isEmpty()) return null
        for (kind in KIND_ORDER) {
            hits.firstOrNull { it.kind == kind }?.let { return it }
        }
        // KIND_ORDER 覆盖了 Kind 的全部取值，正常到不了这里；留着是为了将来加类别时不会静默丢名字
        return hits.first()
    }

    /** 当天涉及的节日类别（按 [KIND_ORDER] 去重，最多 3 个）。调用方负责先按开关过滤 hits。 */
    fun kindsOf(hits: List<Holidays.Holiday>): List<Holidays.Kind> {
        if (hits.isEmpty()) return emptyList()
        val out = ArrayList<Holidays.Kind>(KIND_ORDER.size)
        for (kind in KIND_ORDER) {
            if (hits.any { it.kind == kind }) out += kind
        }
        return out
    }
}
