package app.timetable.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.timetable.data.Prefs
import app.timetable.data.TimetableRepository
import app.timetable.data.WeekCalc
import java.time.LocalDate
import java.time.ZoneId

/**
 * 按「提前 N 分钟」为未来 7 天的每节课注册闹钟。
 * 精确闹钟权限被拒时自动降级为 setWindow（不精确但可用）。
 */
object ReminderScheduler {

    private const val REQ_BASE = 43100
    private const val REQ_SPAN = 240

    fun reschedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        cancelAll(app, am)

        if (!Prefs.reminderEnabled) return
        val result = TimetableRepository.result()
        if (result.sessions.isEmpty()) return
        val week1 = Prefs.week1MondayDate() ?: return

        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val lead = Prefs.reminderMinutes.toLong()
        var req = REQ_BASE

        outer@ for (offset in 0..6) {
            val date = today.plusDays(offset.toLong())
            val week = WeekCalc.weekOf(date, week1)
            if (week < 1) continue
            for (s in WeekCalc.todaySessions(result, date, week)) {
                val start = result.section(s.startSection)?.startTime ?: continue
                val trigger = date.atTime(start).minusMinutes(lead)
                    .atZone(zone).toInstant().toEpochMilli()
                if (trigger <= System.currentTimeMillis() + 5000L) continue

                val intent = Intent(app, ReminderReceiver::class.java).apply {
                    putExtra(ReminderReceiver.EXTRA_ID, req)
                    putExtra(ReminderReceiver.EXTRA_NAME, s.name)
                    putExtra(ReminderReceiver.EXTRA_ROOM, s.room)
                    putExtra(ReminderReceiver.EXTRA_TEACHER, s.teacher)
                    putExtra(
                        ReminderReceiver.EXTRA_SPAN,
                        result.sectionRangeLabel(s.startSection, s.endSection)
                    )
                    putExtra(ReminderReceiver.EXTRA_TIME, result.sectionStart(s.startSection))
                }
                val pi = PendingIntent.getBroadcast(
                    app, req, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                Alarms.schedule(am, trigger, pi)
                req++
                if (req > REQ_BASE + REQ_SPAN) break@outer
            }
        }
    }

    fun cancelAll(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        cancelAll(app, am)
    }

    private fun cancelAll(app: Context, am: AlarmManager) {
        for (req in REQ_BASE..(REQ_BASE + REQ_SPAN)) {
            val pi = PendingIntent.getBroadcast(
                app, req, Intent(app, ReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: continue
            Alarms.cancel(am, pi)
        }
    }
}
