package app.timetable.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build

/** 精确闹钟的降级策略，提醒与小组件刷新共用 */
internal object Alarms {

    private const val WINDOW_MS = 10 * 60 * 1000L

    fun schedule(am: AlarmManager, at: Long, pi: PendingIntent) {
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pi)
            }
        } catch (e: SecurityException) {
            runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pi) }
        }
    }

    fun cancel(am: AlarmManager, pi: PendingIntent) {
        am.cancel(pi)
        pi.cancel()
    }
}
