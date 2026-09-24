package app.timetable.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.timetable.data.ParseResult
import app.timetable.data.Session
import app.timetable.data.WeekCalc
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * 课表网格绘制核心。App 内自定义 View 与导出图片共用，因此只依赖 Canvas。
 *
 * 视觉语言：低饱和课程底色 + 左侧饱和色条 + 圆角卡片 + 三档文字。
 * 文字排版**严格受卡片高度约束**（放不下就截断加省略号），绝不溢出到相邻格子。
 */
object TimetableRenderer {

    private const val HEADER_DP = 48f
    private const val ROW_DP = 62f
    private const val TIME_COL_DP = 40f
    private const val MIN_ROWS = 12
    private const val BAR_W_DP = 3f

    /** 课程配色：明暗模式下都有足够对比度的中高饱和色 */
    private val PALETTE = intArrayOf(
        0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt(), 0xFFE11D48.toInt(),
        0xFFEA580C.toInt(), 0xFFCA8A04.toInt(), 0xFF16A34A.toInt(), 0xFF0D9488.toInt(),
        0xFF0891B2.toInt(), 0xFF4F46E5.toInt()
    )

    /** 6 种卡片风格，对应设置页里的「课表风格」 */
    class Style(
        val tint: Int,
        val radiusDp: Float,
        val showBar: Boolean,
        val filled: Boolean
    ) {
        companion object {
            val ALL = listOf(
                Style(34, 9f, true, true),      // 0 清爽
                Style(16, 9f, false, true),     // 1 素雅
                Style(66, 9f, true, true),      // 2 醒目
                Style(34, 18f, true, true),     // 3 圆润
                Style(34, 2f, true, true),      // 4 直角
                Style(0, 9f, true, false)       // 5 极简（只留色条与描边）
            )
            val NAMES = listOf("清爽", "素雅", "醒目", "圆润", "直角", "极简")
            fun of(index: Int): Style = ALL[index.coerceIn(0, ALL.size - 1)]
        }
    }

    /** 课名哈希 → 固定配色，保证同一门课每次渲染同色 */
    fun colorFor(name: String, seed: Int): Int {
        var h = seed * 31 + 7
        for (ch in name) h = h * 31 + ch.code
        return PALETTE[Math.floorMod(h, PALETTE.size)]
    }

    /** 一次解析出的颜色集合，避免绘制时反复判断明暗 */
    class Palette(val dark: Boolean, val accent: Int) {
        val page = if (dark) 0xFF0F1115.toInt() else 0xFFFFFFFF.toInt()
        val surface = if (dark) 0xFF171A20.toInt() else 0xFFFFFFFF.toInt()
        val surfaceAlt = if (dark) 0xFF1E2229.toInt() else 0xFFF5F7FA.toInt()
        val hairline = if (dark) 0xFF23282F.toInt() else 0xFFEEF0F3.toInt()
        val text = if (dark) 0xFFEDEFF2.toInt() else 0xFF101828.toInt()
        val text2 = if (dark) 0xFF98A2B3.toInt() else 0xFF667085.toInt()
        val text3 = if (dark) 0xFF6B7280.toInt() else 0xFF98A2B3.toInt()
        val nowLine = if (dark) 0xFFFF6B6B.toInt() else 0xFFE5484D.toInt()
        val weekendWash = if (dark) 0x14FFFFFF else 0x08000000
    }

    class Metrics(val density: Float, val width: Float, val sectionCount: Int) {
        val timeCol: Float = TIME_COL_DP * density
        val headerH: Float = HEADER_DP * density
        val rowH: Float = ROW_DP * density
        val dayCol: Float = (width - timeCol) / 7f
        fun xFor(day: Int): Float = timeCol + (day - 1) * dayCol
        fun yFor(section: Int): Float = headerH + (section - 1) * rowH
        fun height(): Float = headerH + sectionCount * rowH
    }

    /**
     * @param minRows 最少画几行。真实课表页用 [MIN_ROWS]（只有 2 节课时也画满一屏，
     *                避免页面塌成一条）；设置页的风格预览会传更小的值（样张只有 6 节），
     *                这样预览不会比内容高出一大截。
     */
    fun metrics(width: Float, density: Float, result: ParseResult, minRows: Int = MIN_ROWS): Metrics {
        val maxRow = maxOf(result.sections.maxOfOrNull { it.index } ?: 0, minRows)
        return Metrics(density, width, maxRow)
    }

    fun contentHeight(width: Float, density: Float, result: ParseResult): Float =
        metrics(width, density, result).height()

    fun draw(
        canvas: Canvas,
        width: Float,
        density: Float,
        result: ParseResult,
        week: Int,
        dark: Boolean,
        accent: Int,
        todayDay: Int,
        nowTime: LocalTime?,
        weekMonday: LocalDate?,
        seed: Int,
        style: Int = 0,
        /** 背景已由调用方画好时置 false（自定义图片背景） */
        drawBackground: Boolean = true,
        /**
         * 周末列是否铺一层淡底色。
         * 设置里可切换：现在这种「周末浅底」或「与工作日相同」（= false）。
         */
        weekendTint: Boolean = true,
        /** 诊断用：收集「哪段文字被截断/被压缩」的记录（真机看不到画面时靠它验证） */
        report: MutableList<String>? = null,
        /** 最少画几行，见 [metrics] */
        minRows: Int = MIN_ROWS,
        /**
         * 已应用"当日调课"覆盖的网格（星期 → 课）。null = 直接用原始课表。
         *
         * 渲染器本身不认识 Context，所以覆盖由 TimetableRepository 算好后传进来 ——
         * 这样才能保证"渲染用的课表"和"小组件/提醒用的课表"是同一份。
         */
        grid: Map<Int, List<Session>>? = null
    ) {
        val p = Palette(dark, accent)
        val m = metrics(width, density, result, minRows)
        val h = m.height()
        val d = density
        val days = grid ?: WeekCalc.weekGrid(result, week)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.hairline
            strokeWidth = 1f * d
        }

        if (drawBackground) {
            fill.color = p.page
            canvas.drawRect(0f, 0f, width, h, fill)
        }

        // 周末列淡底，视觉上把工作日与休息日分开（设置里可关掉，改成与工作日一致）
        if (weekendTint) {
            fill.color = p.weekendWash
            canvas.drawRect(m.xFor(6), m.headerH, m.xFor(7) + m.dayCol, h, fill)
        }

        // 今天列淡底
        if (todayDay in 1..7) {
            fill.color = accent
            fill.alpha = if (dark) 22 else 16
            canvas.drawRect(m.xFor(todayDay), m.headerH, m.xFor(todayDay) + m.dayCol, h, fill)
            fill.alpha = 255
        }

        // 表头
        fill.color = if (drawBackground) p.surface else blend(p.surface, p.page, 0.82f)
        canvas.drawRect(0f, 0f, width, m.headerH, fill)
        canvas.drawLine(0f, m.headerH, width, m.headerH, hair)

        drawHeader(canvas, m, p, accent, todayDay, weekMonday, d)

        // 行分隔线（只画在内容区，避免穿过时间列文字）
        for (i in 1 until m.sectionCount) {
            val y = m.headerH + i * m.rowH
            canvas.drawLine(m.timeCol, y, width, y, hair)
        }
        canvas.drawLine(m.timeCol, m.headerH, m.timeCol, h, hair)

        drawTimeColumn(canvas, m, p, result, d)
        drawCards(canvas, m, p, result, days, todayDay, nowTime, seed, d, Style.of(style), report)
        drawNowLine(canvas, m, p, result, todayDay, nowTime, d)

        // 空状态
        val hasAny = days.values.any { it.isNotEmpty() }
        if (!hasAny) {
            val title: String
            val hint: String
            if (result.sessions.isEmpty()) {
                title = "还没有课表数据"
                hint = "点右上角「同步」，或 ⋮ →「重新登录」导入"
            } else {
                title = "第 $week 周没有课"
                hint = "用 ‹ › 切换周次看看"
            }
            drawEmpty(canvas, m, p, d, title, hint)
        }
    }

    /** 将 c 以 alpha 比例叠到 base 上（自定义背景时表头需要半透明） */
    private fun blend(c: Int, base: Int, alpha: Float): Int {
        fun ch(shift: Int): Int = ((c shr shift and 0xFF) * alpha + (base shr shift and 0xFF) * (1 - alpha)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ------------------------------------------------------------------ 表头

    private fun drawHeader(
        canvas: Canvas,
        m: Metrics,
        p: Palette,
        accent: Int,
        todayDay: Int,
        weekMonday: LocalDate?,
        d: Float
    ) {
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 12f * d
            isFakeBoldText = true
        }
        val date = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 10f * d
            color = p.text3
        }
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            alpha = 30
        }

        for (day in 1..7) {
            val cx = m.xFor(day) + m.dayCol / 2f
            val isToday = day == todayDay
            if (isToday) {
                val pillW = 48f * d
                val pillH = 22f * d
                canvas.drawRoundRect(
                    RectF(cx - pillW / 2f, 6f * d, cx + pillW / 2f, 6f * d + pillH),
                    pillH / 2f, pillH / 2f, pill
                )
            }
            name.color = if (isToday) accent else p.text2
            canvas.drawText(Session.dayLabel(day), cx, 21f * d, name)
            if (weekMonday != null) {
                val dd = weekMonday.plusDays((day - 1).toLong())
                canvas.drawText("${dd.monthValue}/${dd.dayOfMonth}", cx, 38f * d, date)
            }
        }
    }

    // -------------------------------------------------------------- 时间列

    private fun drawTimeColumn(
        canvas: Canvas,
        m: Metrics,
        p: Palette,
        result: ParseResult,
        d: Float
    ) {
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 10.5f * d
            color = p.text2
            isFakeBoldText = true
        }
        val start = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 8.5f * d
            color = p.text3
        }
        // 起止时间都要显示：只给开始时间，学生无法判断这节课上到几点
        val end = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 8.5f * d
            color = p.text3
        }
        for (s in result.sections) {
            val cy = m.yFor(s.index) + m.rowH / 2f
            canvas.drawText(s.label, m.timeCol / 2f, cy - 9f * d, label)
            if (s.start.isNotEmpty()) canvas.drawText(s.start, m.timeCol / 2f, cy + 2f * d, start)
            if (s.end.isNotEmpty()) canvas.drawText(s.end, m.timeCol / 2f, cy + 12f * d, end)
        }
    }

    // ---------------------------------------------------------------- 课程

    private fun drawCards(
        canvas: Canvas,
        m: Metrics,
        p: Palette,
        result: ParseResult,
        grid: Map<Int, List<Session>>,
        todayDay: Int,
        nowTime: LocalTime?,
        seed: Int,
        d: Float,
        style: Style,
        report: MutableList<String>? = null
    ) {
        val card = Paint(Paint.ANTI_ALIAS_FLAG)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        // 注意：这里必须写 this.style —— 本函数的参数也叫 style（卡片风格），
        // 不限定接收者的话会把 Paint.style 解析成给参数赋值
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE }
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * d
            isFakeBoldText = true
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f * d }
        val tiny = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f * d }
        val radius = style.radiusDp * d

        for (day in 1..7) {
            for (s in grid[day].orEmpty()) {
                val c = colorFor(s.name, seed)
                val left = m.xFor(day) + 2.5f * d
                val right = m.xFor(day) + m.dayCol - 3f * d
                val top = m.yFor(s.startSection) + 2.5f * d
                val bottom = m.yFor(s.endSection + 1) - 2.5f * d
                if (right - left < 14f * d || bottom - top < 18f * d) continue

                val rect = RectF(left, top, right, bottom)

                if (style.filled) {
                    card.color = c
                    card.alpha = if (p.dark) (style.tint * 1.4f).toInt().coerceAtMost(96) else style.tint
                    canvas.drawRoundRect(rect, radius, radius, card)
                    card.alpha = 255
                } else {
                    stroke.color = c
                    stroke.alpha = 70
                    stroke.strokeWidth = 1f * d
                    canvas.drawRoundRect(rect, radius, radius, stroke)
                    stroke.alpha = 255
                }

                // 当前正在上的课：描边强调
                if (day == todayDay && nowTime != null && spans(result, s, nowTime)) {
                    stroke.color = c
                    stroke.strokeWidth = 1.5f * d
                    canvas.drawRoundRect(rect, radius, radius, stroke)
                }

                // 左侧饱和色条
                var textLeft = left + 6f * d
                if (style.showBar) {
                    val barX = left + 4f * d
                    val barTop = top + 6f * d
                    val barBottom = bottom - 6f * d
                    if (barBottom > barTop) {
                        bar.color = c
                        canvas.drawRoundRect(
                            RectF(barX, barTop, barX + BAR_W_DP * d, barBottom),
                            BAR_W_DP * d / 2f, BAR_W_DP * d / 2f, bar
                        )
                    }
                    textLeft = barX + BAR_W_DP * d + 4f * d
                }

                // 文字：严格按可用高度排版，放不下就加省略号，绝不溢出卡片
                val maxW = right - textLeft - 4f * d
                if (maxW <= 0f) continue

                val extra = s.teacher.ifEmpty {
                    if (s.weeks.start > 1 || s.weeks.end < 25) s.weeks.toString() else ""
                }
                // 行数不写死，交给**高度**决定（占 2 节的卡片本来就该把长课名显示全）；
                // 只给教室和教师预留最少行数，防止长课名把它们挤没。
                drawFitted(
                    canvas = canvas,
                    blocks = listOf(
                        TextBlock("课名", s.name, name, 11f * d, 12.5f * d, 8),
                        // reserveAfter = 2：画课名时先把教室的这 2 行扣出来，教室才不会被挤没
                        TextBlock("教室", s.room, sub, 8.5f * d, 9.5f * d, 2, reserveAfter = 2),
                        TextBlock("教师", extra, tiny, 8f * d, 9.5f * d, 2)
                    ),
                    x = textLeft,
                    top = top + 8f * d,
                    bottom = bottom - 5f * d,
                    maxWidth = maxW,
                    colors = intArrayOf(p.text, p.text2, p.text3),
                    report = report
                )
            }
        }
    }

    /** 一段可换行的文字及其优先级（越靠前越重要） */
    private class TextBlock(
        val label: String,
        val text: String,
        val paint: Paint,
        /** 基准字号（px）。缩小宽度时改的是它，绘制完再还原 */
        val sizePx: Float,
        val lineHeight: Float,
        val maxLines: Int,
        /**
         * 给**后面的段落**预留几行。
         *
         * 课名不设死上限（占 2 节的卡片很高，长课名本来就该显示全），
         * 但只要教室还排在后面，就先扣掉它的 2 行 —— 否则长课名会把教室挤没。
         * 这就是「课名显示不全」和「教室显示不全」反复横跳的根源：
         * 靠固定行数分配永远顾此失彼，得让**高度**说话。
         */
        val reserveAfter: Int = 0
    )

    /** 宽度不够时依次尝试的横向压缩比（只压宽度，不压高度，所以不影响行数） */
    private val SCALES = floatArrayOf(0.92f, 0.84f, 0.76f, 0.68f)

    /**
     * 是否允许"宽度不够先缩字"。**仅供诊断做 A/B 对照**：
     * 关掉它就是修复前的行为，用来量"修复到底救回了几段文字"。
     */
    @Volatile
    var allowShrink: Boolean = true

    /**
     * 在 [top, bottom) 内排版若干段文字：放不下就**先缩字**，再截断加省略号。
     *
     * 为什么必须缩：一节 45 分钟的课，卡片可用文字宽度只有 ~80px（7 天分屏后就这么宽），
     * 而「天山堂A303」在 9dp 字号下要 118px —— 不缩就只剩截断，
     * 教室里那行等于白写（真机反馈："教室不点击显示不全"）。
     * 压缩到 0.76 左右就能整行放下，肉眼几乎看不出变形。
     */
    private fun drawFitted(
        canvas: Canvas,
        blocks: List<TextBlock>,
        x: Float,
        top: Float,
        bottom: Float,
        maxWidth: Float,
        colors: IntArray,
        report: MutableList<String>? = null
    ) {
        var y = top
        for ((index, block) in blocks.withIndex()) {
            if (block.text.isEmpty()) continue
            val remaining = bottom - y
            // 后面几段各留 reserveAfter 行，别被前面的段落吃光
            val reserve = blocks.drop(index + 1)
                .sumOf { (it.lineHeight * it.reserveAfter).toDouble() }
                .toFloat()
            val usable = (bottom - reserve) - y
            if (usable < block.lineHeight * 0.85f && remaining < block.lineHeight * 0.85f) {
                report?.add("${block.label} 放不下（剩余 ${remaining.toInt()}px）: ${block.text}")
                break
            }

            // 行数由**高度**决定，maxLines 只作为上限兜底
            val fits = (usable / block.lineHeight).toInt().coerceAtLeast(1)
            val maxLines = minOf(block.maxLines, fits)

            var wrapped = wrap(block.paint, block.text, maxWidth, maxLines)
            var scale = 1f
            if (allowShrink && wrapped.truncated) {
                // 用**改字号**来缩，而不是 textScaleX：
                // textScaleX 在 measureText(String,int,int) 这个重载里不生效，
                // 缩了等于没缩（实测：修复前后截断数完全一样）。
                for (cand in SCALES) {
                    block.paint.textSize = block.sizePx * cand
                    val w = wrap(block.paint, block.text, maxWidth, maxLines)
                    if (!w.truncated) {
                        wrapped = w
                        scale = cand
                        break
                    }
                }
            }
            if (wrapped.lines.isEmpty()) {
                block.paint.textSize = block.sizePx
                continue
            }

            block.paint.color = colors.getOrElse(index) { colors.last() }
            block.paint.textSize = block.sizePx * scale
            for ((i, line) in wrapped.lines.withIndex()) {
                val isLast = i == wrapped.lines.lastIndex
                val text =
                    if (isLast && wrapped.truncated) ellipsize(block.paint, line, maxWidth) else line
                canvas.drawText(text, x, y + block.lineHeight * 0.78f, block.paint)
                y += block.lineHeight
            }
            block.paint.textSize = block.sizePx

            if (wrapped.truncated) {
                report?.add(
                    "${block.label} 被截断（宽 ${maxWidth.toInt()}px, ${maxLines}行）: " +
                        "${block.text} → ${wrapped.lines.joinToString("|")}…"
                )
            } else if (scale < 1f) {
                report?.add("${block.label} 缩到 $scale 才放下: ${block.text}")
            }
        }
    }

    private fun spans(result: ParseResult, s: Session, now: LocalTime): Boolean {
        val start = result.section(s.startSection)?.startTime ?: return false
        val end = result.section(s.endSection)?.endTime ?: return false
        return !now.isBefore(start) && !now.isAfter(end)
    }

    /** 当前时刻落在哪一节 */
    private fun sectionIndexAt(result: ParseResult, now: LocalTime): Int? =
        result.sections.firstOrNull { s ->
            val st = s.startTime
            val en = s.endTime
            st != null && en != null && !now.isBefore(st) && !now.isAfter(en)
        }?.index

    /** 命中测试：点到的格子里是哪门课（用于「点一下看完整信息」） */
    fun hitTest(
        result: ParseResult,
        week: Int,
        metrics: Metrics,
        x: Float,
        y: Float,
        grid: Map<Int, List<Session>>? = null
    ): Session? {
        if (y < metrics.headerH || x < metrics.timeCol) return null
        val day = ((x - metrics.timeCol) / metrics.dayCol).toInt() + 1
        if (day !in 1..7) return null
        val section = ((y - metrics.headerH) / metrics.rowH).toInt() + 1
        return (grid ?: WeekCalc.weekGrid(result, week))[day].orEmpty()
            .firstOrNull { section >= it.startSection && section <= it.endSection }
    }

    /**
     * 命中"星期标题"那一条（表头上方）时返回星期列号 1..7，否则 null。
     *
     * 单独一个函数而不是并进 [hitTest]：表头本来没有课块，点它是"调整这一天的课表"，
     * 语义完全不同 —— 混在一起会让 hitTest 的调用方分不清点到的是课还是标题。
     */
    fun hitDayHeader(metrics: Metrics, x: Float, y: Float): Int? {
        if (y >= metrics.headerH || x < metrics.timeCol) return null
        val day = ((x - metrics.timeCol) / metrics.dayCol).toInt() + 1
        return if (day in 1..7) day else null
    }

    // -------------------------------------------------------------- 当前时刻

    private fun drawNowLine(
        canvas: Canvas,
        m: Metrics,
        p: Palette,
        result: ParseResult,
        todayDay: Int,
        nowTime: LocalTime?,
        d: Float
    ) {
        if (nowTime == null || todayDay !in 1..7) return
        val idx = sectionIndexAt(result, nowTime) ?: return
        val sec = result.section(idx) ?: return
        val st = sec.startTime ?: return
        val en = sec.endTime ?: return
        val total = java.time.Duration.between(st, en).toSeconds().coerceAtLeast(1)
        val passed = java.time.Duration.between(st, nowTime).toSeconds()
        val frac = (passed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val y = m.yFor(sec.index) + frac * m.rowH

        val x0 = m.xFor(todayDay)
        val x1 = x0 + m.dayCol
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.nowLine
            strokeWidth = 2f * d
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(x0 + 5f * d, y, x1 - 5f * d, y, ink)

        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.nowLine }
        canvas.drawCircle(x0 + 5f * d, y, 3.5f * d, dot)

        val clock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.nowLine
            textSize = 9f * d
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(
            String.format(Locale.CHINA, "%02d:%02d", nowTime.hour, nowTime.minute),
            m.timeCol / 2f, y - 6f * d, clock
        )
    }

    // -------------------------------------------------------------- 空状态

    private fun drawEmpty(
        canvas: Canvas,
        m: Metrics,
        p: Palette,
        d: Float,
        title: String,
        hint: String
    ) {
        val cx = m.timeCol + (m.width - m.timeCol) / 2f
        val cy = m.headerH + (m.height() - m.headerH) / 2f

        val cardW = minOf(m.width - 48f * d, 300f * d)
        val cardH = 96f * d
        val rect = RectF(cx - cardW / 2f, cy - cardH / 2f, cx + cardW / 2f, cy + cardH / 2f)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.surfaceAlt }
        canvas.drawRoundRect(rect, 16f * d, 16f * d, fill)

        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 14f * d
            color = p.text
            isFakeBoldText = true
        }
        val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 11f * d
            color = p.text3
        }
        canvas.drawText(title, cx, cy - 6f * d, t)
        canvas.drawText(hint, cx, cy + 16f * d, s)
    }

    // ---------------------------------------------------------------- 换行

    /** 换行结果；truncated 表示还有内容没排下 */
    class Wrapped(val lines: List<String>, val truncated: Boolean)

    /** 是否「宽」字符（汉字/中文标点）。用来找「天山堂A303」这类词的断点 */
    private fun isWide(ch: Char): Boolean = ch.code > 0x2E80

    /**
     * 在 [start, end) 里挑一个「语义断点」：空格之后，或汉字→字母/数字之间。
     *
     * 纯函数，便于单测 —— 断点选错就会把「天山堂A101」切成「天山」/「堂A10…」，
     * 既没显示全，也读不出是哪栋楼哪间房。
     * 断点太靠前（不足一半）就不值得，直接返回 end 硬切。
     *
     * @return 断点下标；没有合适断点时返回 end
     */
    fun semanticCut(text: String, start: Int, end: Int): Int {
        val span = end - start
        if (span <= 1) return end
        for (k in (end - 1) downTo (start + 1)) {
            val prev = text[k - 1]
            val cur = text[k]
            if (prev == ' ' || (isWide(prev) && !isWide(cur))) {
                return if (k - start < span / 2) end else k
            }
        }
        return end
    }

    /**
     * 按宽度断行，中文友好。最多 [maxLines] 行，有剩余则 truncated = true。
     *
     * 断点选择很关键：纯粹按"能塞几个字"硬切，会把「天山堂A303」切成
     * 「天山」/「堂A…」。所以优先在**语义边界**断（见 [semanticCut]）。
     */
    fun wrap(paint: Paint, text: String, maxWidth: Float, maxLines: Int): Wrapped {
        if (maxWidth <= 0f || text.isEmpty() || maxLines <= 0) {
            return Wrapped(emptyList(), text.isNotEmpty())
        }
        val lines = ArrayList<String>(maxLines)
        val n = text.length
        var i = 0
        while (i < n && lines.size < maxLines) {
            // 这一行最多能塞到哪
            var end = i
            while (end < n && paint.measureText(text, i, end + 1) <= maxWidth) end++
            if (end == i) end = i + 1          // 单个字符都放不下：硬塞，避免死循环

            val cut = semanticCut(text, i, end)
            lines += text.substring(i, cut)
            i = cut
        }
        return Wrapped(lines, i < n)
    }

    /** 把一行裁到宽度内并加省略号 */
    fun ellipsize(paint: Paint, text: String, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val dots = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + dots) > maxWidth) end--
        return if (end <= 0) dots else text.substring(0, end) + dots
    }
}
