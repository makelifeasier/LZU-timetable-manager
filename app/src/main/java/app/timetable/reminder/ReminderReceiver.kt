package app.timetable.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.timetable.data.Prefs
import app.timetable.notify.Notifications

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_NAME) ?: return
        val room = intent.getStringExtra(EXTRA_ROOM).orEmpty()
        val teacher = intent.getStringExtra(EXTRA_TEACHER).orEmpty()
        val span = intent.getStringExtra(EXTRA_SPAN).orEmpty()
        val time = intent.getStringExtra(EXTRA_TIME).orEmpty()
        val lead = Prefs.reminderMinutes
        val id = intent.getIntExtra(EXTRA_ID, 9000)

        val body = buildString {
            append(span)
            if (time.isNotEmpty()) append(" ").append(time)
            append(" 开课")
            if (room.isNotEmpty()) append("，地点 ").append(room)
            if (teacher.isNotEmpty()) append("，").append(teacher)
        }

        Notifications.remind(context, id, "$lead 分钟后上课：$name", body)
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
        const val EXTRA_ROOM = "room"
        const val EXTRA_TEACHER = "teacher"
        const val EXTRA_SPAN = "span"
        const val EXTRA_TIME = "time"
    }
}
