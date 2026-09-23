package app.timetable.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.timetable.data.TimetableRepository
import app.timetable.widget.TodayWidgetProvider

/** 开机/升级后闹钟会被清掉，需要重排 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        TimetableRepository.init(app)
        TodayWidgetProvider.refreshAll(app)
        ReminderScheduler.reschedule(app)
    }
}
