package app.timetable.greet

import android.app.Activity
import app.timetable.data.Prefs
import java.time.LocalDate

/**
 * 进 App 时的节日祝福。由调用方在 onResume/onCreate 里挂一行 [maybeShow] 即可，
 * 弹与不弹全部在这里决定（调用方不需要做任何判断）。
 *
 * 触发条件必须同时满足：
 *  1. 总开关 [Prefs.greetEnabled] 打开（默认关闭 —— 没人喜欢装个课表 App 就被弹窗）；
 *  2. 今天命中节日，且**至少有一个**命中的节日属于已打开的类别
 *     （只开了公历节日的用户，不该在端午被弹一次）；
 *  3. 今天还没弹过（[Prefs.greetLastShownDate] 记的是 yyyy-MM-dd 而不是 boolean ——
 *     存日期才能"跨天自动重置"，不需要任何定时任务去清标志位）。
 *
 * 一条都没命中时**什么都不做**：绝不弹一个空对话框。
 */
object HolidayGreeter {

    fun maybeShow(activity: Activity) {
        // 整条链路包在 runCatching 里。这是一个挂在生命周期上的副作用，
        // 任何一个环节出问题（Prefs 尚未 init、Activity 正在销毁、
        // 弹窗拿不到 window token）都不应该让用户"打开 App 就闪退"——
        // 少弹一次祝福是小事，闪退是大事。
        runCatching {
            if (!Prefs.greetEnabled) return
            // isFinishing / isDestroyed 时 show() 会抛 BadTokenException，先挡掉
            if (activity.isFinishing || activity.isDestroyed) return

            val today = LocalDate.now()
            val key = today.toString()
            if (Prefs.greetLastShownDate == key) return

            val hits = Holidays.of(today).filter { enabled(it.kind) }
            if (hits.isEmpty()) return

            // 先记日期、再弹窗：弹窗这一步一旦开始就算"用掉了今天这次机会"。
            // 万一渲染失败，第二天再弹一次是好结果；最糟的是同一天反复弹。
            Prefs.greetLastShownDate = key
            GreetingDialog.show(activity, today, hits)
        }
    }

    /** 该类别是否被用户打开。三类与设置页的三个开关一一对应 */
    private fun enabled(kind: Holidays.Kind): Boolean = when (kind) {
        Holidays.Kind.SOLAR -> Prefs.greetSolar
        Holidays.Kind.LUNAR -> Prefs.greetLunar
        Holidays.Kind.SCHOOL -> Prefs.greetSchool
    }
}
