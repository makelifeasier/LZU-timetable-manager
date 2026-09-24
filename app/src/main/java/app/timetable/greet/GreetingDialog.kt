package app.timetable.greet

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import app.timetable.databinding.DialogGreetingBinding
import app.timetable.ui.Ui
import java.time.LocalDate

/**
 * 节日祝福弹窗。风格与设置页一致：白/深底圆角卡片 + 兰大蓝强调色 + 胶囊按钮。
 *
 * 用 ViewBinding 而不是 findViewById：布局里控件类型写错（比如把 TextView 当 Button）
 * 会直接变成编译错误，而不是运行到那一行才 ClassCastException。
 */
internal object GreetingDialog {

    /** 农历月份名。十一月/十二月习惯叫冬月/腊月 */
    private val MONTHS = arrayOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月"
    )

    /** 农历日名。初一到三十 */
    private val DAYS = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    )

    fun show(activity: Activity, date: LocalDate, hits: List<Holidays.Holiday>) {
        val binding = DialogGreetingBinding.inflate(LayoutInflater.from(activity))

        // 一天可能命中多个节日：标题串起来，祝福各占一行
        binding.title.text = hits.joinToString(" · ") { it.name }
        binding.greeting.text = hits.joinToString("\n") { it.greeting }

        lunarLabel(date, hits)?.let {
            binding.lunar.text = it
            binding.lunar.visibility = View.VISIBLE
        }

        binding.btnOk.text = "知道啦"

        val dialog = AlertDialog.Builder(activity).setView(binding.root).create()
        binding.btnOk.setOnClickListener { dialog.dismiss() }

        dialog.window?.apply {
            // 卡片自己画圆角，窗口这层必须透明，否则方角底会露在卡片外面
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // 左右留白，圆角才看得出来
            val side = Ui.dp(activity, 22f)
            decorView.setPadding(side, Ui.dp(activity, 8f), side, Ui.dp(activity, 8f))
        }
        dialog.show()
    }

    /**
     * 农历节日才补一行小字（"农历正月初一"）。
     * 公历与校历节日的日期用户本来就知道，多一行只是噪音。
     */
    private fun lunarLabel(date: LocalDate, hits: List<Holidays.Holiday>): String? {
        if (hits.none { it.kind == Holidays.Kind.LUNAR }) return null
        val l = LunarCalendar.lunarOf(date)
        val month = MONTHS[l.month.coerceIn(1, 12) - 1]
        val day = DAYS[l.day.coerceIn(1, 30) - 1]
        return "农历" + (if (l.leap) "闰" else "") + month + day
    }
}
