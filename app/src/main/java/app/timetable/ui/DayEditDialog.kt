package app.timetable.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import app.timetable.R
import app.timetable.data.DayOverrides
import app.timetable.data.Prefs
import app.timetable.data.Session
import app.timetable.data.TimetableRepository
import app.timetable.data.WeekCalc
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 「当日调课」弹窗：点课表上方的星期标题后打开。
 *
 * 交互设计（为什么这么做）：
 *  - **左右滑动切周**：一天一天翻，每页显示"第 N 周 · 周三"的课 —— 单双周、临时调课
 *    本质就是"想看看别的周的这天长什么样"，滑动是最贴切的手势；
 *  - **空周也有页面**：该周这天没课时显示一张居中的空状态卡，而不是"滑不动"，
 *    否则用户会以为控件坏了（这也是他要求的"无课的情况怎么做最丝滑"）；
 *  - 底部四个动作语义互斥且可回退：**替换 / 加一节课 / 清空当天 / 恢复原课表**。
 *    其中"加一节课"如果碰上"替换/清空"，会先提示先恢复 —— 规则明确比"随便叠加"更不容易出错。
 */
internal class DayEditDialog(
    private val activity: Activity,
    private val day: Int,
    private val date: LocalDate
) {

    private val ctx: Context get() = activity

    private var page = 0               // 当前显示第几周（0 基，对应 week = page + 1）
    private lateinit var flipper: ViewFlipper
    private lateinit var pageLabel: TextView
    private lateinit var statusLine: TextView

    private val maxWeek: Int
        get() {
            val r = TimetableRepository.result()
            val w1 = Prefs.week1MondayDate()
            val now = if (w1 != null) WeekCalc.weekOf(LocalDate.now(), w1) else 1
            return maxOf(r.maxWeek, now, 16).coerceAtMost(30)
        }

    private val dialog: AlertDialog by lazy {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(6))
        }

        // 标题：周三 · 10 月 8 日
        root.addView(
            TextView(ctx).apply {
                text = "${Session.dayLabel(day)} · ${date.format(MONTH_DAY)}"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ctx.getColor(R.color.text_primary))
            }
        )
        statusLine = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(2))
        }
        root.addView(statusLine)

        // 左右滑动切周
        flipper = ViewFlipper(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(220))
        }
        root.addView(flipper)

        pageLabel = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_tertiary))
            setPadding(0, dp(6), 0, dp(2))
        }
        root.addView(pageLabel)

        // 左右各一个"上一周/下一周"的小按钮：滑动手势在某些机型上不灵，按钮是保底
        root.addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(pill("‹ 上一周") { step(-1) })
                addView(pill("下一周 ›") { step(1) })
            }
        )

        // 四个动作
        root.addView(actionRow())
        root.addView(
            TextView(ctx).apply {
                text = "提示：这里的调整只影响这一天，不影响整学期课表"
                textSize = 11f
                setTextColor(ctx.getColor(R.color.text_tertiary))
                setPadding(0, dp(8), 0, 0)
            }
        )

        AlertDialog.Builder(activity)
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()
    }

    fun show() {
        page = (currentWeek() - 1).coerceIn(0, maxWeek - 1)
        // 必须先取一次 dialog：flipper / pageLabel / statusLine 都是在那段惰性构建里赋值的，
        // 直接调 rebuildPages() 会撞上 lateinit 未初始化（真机上一点就崩，已修）
        val d = dialog
        rebuildPages()
        d.show()
    }

    // ------------------------------------------------------------- 内部

    private fun currentWeek(): Int {
        val w1 = Prefs.week1MondayDate() ?: return 1
        return WeekCalc.weekOf(date, w1).coerceAtLeast(1)
    }

    private fun rebuildPages() {
        flipper.removeAllViews()
        for (w in 1..maxWeek) {
            flipper.addView(pageView(w))
        }
        flipper.displayedChild = page
        refreshLabels()
        // 滑动手势：ViewFlipper 自带滑动动画，这里只负责判断方向
        val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = (e1?.x ?: 0f) - e2.x
                if (kotlin.math.abs(dx) < dp(40)) return false
                step(if (dx > 0) 1 else -1)
                return true
            }
        })
        flipper.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            true
        }
    }

    private fun step(delta: Int) {
        val next = (page + delta).coerceIn(0, maxWeek - 1)
        if (next == page) {
            toast(if (delta > 0) "已经是最后一周" else "已经是第 1 周")
            return
        }
        page = next
        flipper.setInAnimation(ctx, android.R.anim.slide_in_left)
        flipper.setOutAnimation(ctx, android.R.anim.slide_out_right)
        if (delta > 0) flipper.showNext() else flipper.showPrevious()
        refreshLabels()
    }

    private fun refreshLabels() {
        val week = page + 1
        pageLabel.text = "第 $week 周 · ${Session.dayLabel(day)}"
        val o = DayOverrides.get(ctx, date)
        statusLine.text = when (o?.mode) {
            null -> "这一天还没调整过"
            DayOverrides.Mode.REPLACE -> "已调整：替换为第 ${o.sourceWeek} 周这天"
            DayOverrides.Mode.CLEAR -> "已调整：当天课全部取消"
            DayOverrides.Mode.ADD -> "已调整：额外加了 ${o.extra.size} 节课"
        }
    }

    /** 一周一页：该周的这天有哪些课 */
    private fun pageView(week: Int): View {
        val list = TimetableRepository.result()
        val sessions = WeekCalc.merge(WeekCalc.sessionsFor(list, day, week))
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(4))
        }
        if (sessions.isEmpty()) {
            // 空状态也要是一页：不然滑到这儿会像是"卡住了"
            box.gravity = Gravity.CENTER
            box.addView(
                TextView(ctx).apply {
                    text = "这周${Session.dayLabel(day)}没课"
                    textSize = 14f
                    setTextColor(ctx.getColor(R.color.text_secondary))
                }
            )
            box.addView(
                TextView(ctx).apply {
                    text = "（可以选择别的周替换过来，或直接清空当天）"
                    textSize = 11f
                    setTextColor(ctx.getColor(R.color.text_tertiary))
                    setPadding(0, dp(6), 0, 0)
                }
            )
            return box
        }
        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        sessions.forEach { s ->
            box.addView(card(s, list))
        }
        scroll.addView(box)
        return scroll
    }

    private fun card(s: Session, result: app.timetable.data.ParseResult): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.getDrawable(R.drawable.bg_card_alt)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
            layoutParams = lp
            addView(
                TextView(ctx).apply {
                    text = s.name
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ctx.getColor(R.color.text_primary))
                }
            )
            val start = result.section(s.startSection)?.start ?: ""
            val end = result.section(s.endSection)?.end ?: start
            val label = result.sectionRangeLabel(s.startSection, s.endSection)
            addView(
                TextView(ctx).apply {
                    text = listOf(label, if (start.isNotBlank()) "$start–$end" else "", s.room)
                        .filter { it.isNotBlank() }.joinToString("  ·  ")
                    textSize = 12f
                    setTextColor(ctx.getColor(R.color.text_secondary))
                    setPadding(0, dp(3), 0, 0)
                }
            )
        }

    private fun actionRow(): View = ScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
                addView(pill("替换为这周") { replace() })
                addView(pill("加一节课") { addOne() })
                addView(pill("清空当天") { clearDay() })
                addView(pill("恢复原课表") { restore() })
            }
        )
    }

    private fun replace() {
        val week = page + 1
        if (week == currentWeek()) {
            toast("这一页就是当天本身，换个周再点替换")
            return
        }
        DayOverrides.put(ctx, date, DayOverrides.Override(DayOverrides.Mode.REPLACE, sourceWeek = week))
        applyAndClose("已把${Session.dayLabel(day)}替换为第 $week 周")
    }

    private fun clearDay() {
        DayOverrides.put(ctx, date, DayOverrides.Override(DayOverrides.Mode.CLEAR))
        applyAndClose("已清空当天")
    }

    private fun restore() {
        DayOverrides.remove(ctx, date)
        applyAndClose("已恢复原课表")
    }

    private fun addOne() {
        val existing = DayOverrides.get(ctx, date)
        if (existing != null && existing.mode != DayOverrides.Mode.ADD) {
            toast("当天已调整为「替换/停课」，请先点『恢复原课表』再加课")
            return
        }
        val name = EditText(ctx, "课名，如：形势与政策")
        val room = EditText(ctx, "教室（可留空）")
        val section = EditText(ctx, "第几节（如 3 或 3-4）")
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(name); addView(room); addView(section)
        }
        AlertDialog.Builder(activity)
            .setTitle("给这天加一节课")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加") { _, _ ->
                val s = parseSection(section.text.toString()) ?: run {
                    toast("节次看不清，填 1–${maxSectionIndex()} 之间的数字，比如 3 或 3-4")
                    return@setPositiveButton
                }
                if (name.text.isBlank()) {
                    toast("课名不能为空")
                    return@setPositiveButton
                }
                val added = Session(
                    name = name.text.toString().trim(),
                    room = room.text.toString().trim(),
                    day = day,
                    startSection = s.first,
                    endSection = s.second
                )
                val extra = (existing?.extra ?: emptyList()) + added
                DayOverrides.put(ctx, date, DayOverrides.Override(DayOverrides.Mode.ADD, extra = extra))
                applyAndClose("已加 1 节课")
            }
            .show()
    }

    /**
     * 把用户填的「第几节」翻译成**行序**，返回 (起始行, 结束行)。
     *
     * 这里是必须翻译的，不能直接用：
     * 兰大课表 HTML 的 `Section.index` 是**表格行序**，而它跟「第几节」并不相等 ——
     * 中间的「中午1节/中午2节」也各占一行，于是第 1-4 节的行序是 1-4，
     * 第 5 节的行序其实是 7。用户按常识填「5」，如果原样当成行序，
     * 这节课会被画到「中午1节」那一行，提醒时间也会早约两小时。
     *
     * 翻译方式：按 label 找（label 就是「第N节」这种原始文案），
     * 找不到再退化成"第 N 个显示出来的节次"，最后才判非法。
     */
    private fun parseSection(text: String): Pair<Int, Int>? {
        val t = text.trim()
        val m = Regex("^(\\d{1,2})\\s*[-~～]?\\s*(\\d{1,2})?$").find(t) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: a
        val sections = TimetableRepository.result().sections.sortedBy { it.index }
        if (sections.isEmpty()) return null

        fun rowOf(n: Int): Int? {
            // 注意：中文是合法的标识符字符，"$n节" 会被 Kotlin 当成变量名 —— 必须用 ${n}
            sections.firstOrNull { it.label == "第${n}节" }?.let { return it.index }
            // 退回：第 n 个真实节次（课表里没有"第N节"这种标签时的兜底）
            return sections.getOrNull(n - 1)?.index
        }

        val start = rowOf(minOf(a, b)) ?: return null
        val end = rowOf(maxOf(a, b)) ?: return null
        return start to end
    }

    /** 用户能填的最大"第几节"：按真实节次数量算，而不是行序的最大值 */
    private fun maxSectionIndex(): Int = TimetableRepository.result().sections.size.coerceAtLeast(1)

    private fun applyAndClose(message: String) {
        TimetableRepository.notifyDataChanged(ctx)
        toast(message)
        dialog.dismiss()
    }

    private fun EditText(context: Context, hintText: String) = android.widget.EditText(context).apply {
        hint = hintText
        textSize = 14f
        setTextColor(context.getColor(R.color.text_primary))
        background = context.getDrawable(R.drawable.bg_card_alt)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setHorizontallyScrolling(false)
        maxLines = 2
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
    }

    private fun pill(text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ctx.getColor(R.color.text_primary))
            background = ctx.getDrawable(R.drawable.bg_pill)
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = dp(3); marginEnd = dp(3); topMargin = dp(4)
            }
            setOnClickListener { onClick() }
        }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = Ui.dp(ctx, v.toFloat())

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M 月 d 日")
    }
}
