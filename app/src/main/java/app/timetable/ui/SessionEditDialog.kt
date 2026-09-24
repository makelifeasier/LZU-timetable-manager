package app.timetable.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.timetable.R
import app.timetable.data.DayOverrides
import app.timetable.data.ParseResult
import app.timetable.data.Section
import app.timetable.data.Session
import app.timetable.data.TimetableRepository
import java.time.LocalDate

/**
 * 「单独改掉这一节课」的小弹窗 —— 只影响**这一天**的这一节，不动整学期课表。
 *
 * 为什么和「点表头调一整天」并存：两者的心智模型不同。
 *  - 表头调一整天：今天有事，整天不上/整天换到别的周的课；
 *  - 这一节：下午第三节换了教室、或者老师临时把课挪走。
 * 实测里用户两种都会遇到，所以两个入口都留着，各自管各自的范围。
 *
 * 两个刻意的设计：
 *  - **节次用 chips 选，不让手输数字**。用户填的"第几节"和表格行序并不是一回事
 *    （兰大页面里"中午1节/中午2节"各占一行，第 5 节的行序是 7）。
 *    让界面直接列出"第1节…第8节"这些**标签**，点一下就存它对应的行序，从根上不可能错。
 *  - 结果写成 [DayOverrides.Override.list]（"这一天的课表就是这几段"），
 *    而不是 ADD（加课）—— 因为这里做的是"替换/删除"，用 ADD 会让删掉的课静默冒回来。
 */
internal class SessionEditDialog(
    private val activity: Activity,
    private val date: LocalDate,
    private val target: Session
) {

    private val ctx: Context get() = activity

    private val result: ParseResult get() = TimetableRepository.result()

    /** 当天当前看得见的整份课表（改/删都基于它算） */
    private val dayList: List<Session> get() = TimetableRepository.sessionsOn(ctx, date)

    /** 用户选中的起始/结束**行序**，初始值来自被点的那一节 */
    private var startRow = target.startSection
    private var endRow = target.endSection

    fun show() {
        val name = field("课名", target.name)
        val room = field("教室（可留空）", target.room)
        val teacher = field("教师（可留空）", target.teacher)

        val picker = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val startChips = chipRow(picker, "从第几节") { row -> startRow = row }
        val endChips = chipRow(picker, "到第几节") { row -> endRow = row }

        fun paintChips() {
            startChips()
            endChips()
        }

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(name)
            addView(room)
            addView(teacher)
            addView(picker)
            addView(
                TextView(ctx).apply {
                    text = "${date.monthValue} 月 ${date.dayOfMonth} 日 · ${Session.dayLabel(date.dayOfWeek.value)}" +
                        " —— 只改这一天，不影响其它周次"
                    textSize = 11f
                    setTextColor(ctx.getColor(R.color.text_tertiary))
                    setPadding(0, dp(8), 0, 0)
                }
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("修改这节课")
            .setView(scroll(body))
            .setNegativeButton("取消", null)
            .setNeutralButton("删除这节课") { _, _ -> delete() }
            .setPositiveButton("保存") { _, _ -> save(name, room, teacher) }
            .create()
        dialog.show()
        // 弹窗显示之后再刷 chips：选中态的底色需要知道当前值
        paintChips()
    }

    // ------------------------------------------------------------- 动作

    private fun save(name: TextView, room: TextView, teacher: TextView) {
        val newName = name.text.toString().trim()
        if (newName.isEmpty()) {
            toast("课名不能为空")
            return
        }
        if (endRow < startRow) {
            toast("结束节次不能早于开始节次")
            return
        }
        val edited = target.copy(
            name = newName,
            room = room.text.toString().trim(),
            teacher = teacher.text.toString().trim(),
            day = date.dayOfWeek.value,
            startSection = startRow,
            endSection = endRow
        )
        val list = replaceInDay(edited)
        DayOverrides.put(ctx, date, DayOverrides.Override.list(list))
        TimetableRepository.notifyDataChanged(ctx)
        toast("已改：${result.sectionRangeLabel(startRow, endRow)} $newName")
    }

    private fun delete() {
        val list = dayList.filterNot { sameSlot(it, target) }
        // 空列表也要写：用户"把当天这最后一节删了"是合法状态，
        // 不写的话会被读成"没调整过"，被删的课下次刷新就回来了
        DayOverrides.put(ctx, date, DayOverrides.Override.list(list))
        TimetableRepository.notifyDataChanged(ctx)
        toast(if (list.isEmpty()) "已删除，这一天没有课了" else "已删除这一节")
    }

    /** 把当天列表里的目标那一节换成 [edited]（其余原样保留，顺序按节次重排） */
    private fun replaceInDay(edited: Session): List<Session> {
        val list = dayList.map { if (sameSlot(it, target)) edited else it }
        return list.sortedWith(compareBy({ it.startSection }, { it.name }))
    }

    /** 同一节次的判定：课名 + 起止行序（列表面板里点中的那一段） */
    private fun sameSlot(a: Session, b: Session): Boolean =
        a.name == b.name && a.startSection == b.startSection && a.endSection == b.endSection

    // ------------------------------------------------------------- 视图

    private fun scroll(v: View): View = android.widget.ScrollView(ctx).apply { addView(v) }

    private fun field(hintText: String, value: String): TextView =
        android.widget.EditText(ctx).apply {
            hint = hintText
            setText(value)
            textSize = 14f
            setTextColor(ctx.getColor(R.color.text_primary))
            background = ctx.getDrawable(R.drawable.bg_card_alt)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        }

    /**
     * 一行节次 chips。取自课表里**真实存在的节次**（用它的 label 显示、index 存储），
     * 所以"第几节"永远和行序对得上。
     */
    private fun chipRow(parent: LinearLayout, title: String, onPick: (Int) -> Unit): () -> Unit {
        parent.addView(
            TextView(ctx).apply {
                text = title
                textSize = 12f
                setTextColor(ctx.getColor(R.color.text_secondary))
                setPadding(0, dp(6), 0, dp(2))
            }
        )
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val holders = ArrayList<Pair<Section, TextView>>()
        val sections = result.sections.sortedBy { it.index }
        for (s in sections) {
            val chip = TextView(ctx).apply {
                text = s.label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(7), dp(9), dp(7))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    marginEnd = dp(5); topMargin = dp(3)
                }
                isClickable = true
                setOnClickListener {
                    onPick(s.index)
                    paint(holders)
                }
            }
            holders.add(s to chip)
            line.addView(chip)
        }
        parent.addView(
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(line)
            }
        )
        return { paint(holders) }
    }

    private fun paint(holders: List<Pair<Section, TextView>>) {
        for ((s, chip) in holders) {
            val on = s.index == startRow || s.index == endRow ||
                (s.index > startRow && s.index < endRow)
            chip.background = ctx.getDrawable(
                if (s.index == startRow || s.index == endRow) R.drawable.bg_accent_chip else R.drawable.bg_pill
            )
            chip.setTextColor(
                ctx.getColor(if (s.index == startRow || s.index == endRow) R.color.accent else R.color.text_secondary)
            )
            chip.alpha = if (on) 1f else 0.55f
        }
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = Ui.dp(ctx, v.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
