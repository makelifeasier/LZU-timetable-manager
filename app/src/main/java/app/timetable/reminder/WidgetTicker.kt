package app.timetable.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.timetable.data.TimetableRepository
import app.timetable.widget.TodayWidgetProvider
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 小组件显示的是「正在上 · 还剩 25 分」这类会随时间变化的文案，
 * 而系统对小组件的定时刷新最短只到 30 分钟，光靠它文案会明显过期。
 * 所以这里在每个**上课/下课边界**各排一次刷新，换天时再补一次。
 */
object WidgetTicker {

    private const val REQ_BASE = 45000
    private const val REQ_SPAN = 64

    fun reschedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        cancelAll(app, am)

        val result = TimetableRepository.result()
        if (result.sections.isEmpty()) return

        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val zone = ZoneId.systemDefault()

        val boundaries = LinkedHashSet<LocalTime>()
        for (s in result.sections) {
            s.startTime?.let { boundaries += it }
            s.endTime?.let { boundaries += it }
        }

        var req = REQ_BASE
        for (t in boundaries.sorted()) {
            val at = LocalDateTime.of(today, t)
            if (!at.isAfter(now)) continue
            if (req > REQ_BASE + REQ_SPAN) break
            fireAt(app, am, at.atZone(zone).toInstant().toEpochMilli(), req++)
        }
        // 次日凌晨补一次：换天 + 重排新一天的边界
        fireAt(
            app, am,
            LocalDateTime.of(today.plusDays(1), LocalTime.of(0, 5))
                .atZone(zone).toInstant().toEpochMilli(),
            req
        )
    }

    fun cancelAll(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        cancelAll(app, am)
    }

    private fun cancelAll(app: Context, am: AlarmManager) {
        for (req in REQ_BASE..(REQ_BASE + REQ_SPAN)) {
            val pi = PendingIntent.getBroadcast(
                app, req, intentFor(app),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: continue
            Alarms.cancel(am, pi)
        }
    }

    private fun fireAt(app: Context, am: AlarmManager, at: Long, req: Int) {
        val pi = PendingIntent.getBroadcast(
            app, req, intentFor(app),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        Alarms.schedule(am, at, pi)
    }

    private fun intentFor(app: Context): Intent =
        Intent(app, TodayWidgetProvider::class.java).setAction(TodayWidgetProvider.ACTION_TICK)
}
