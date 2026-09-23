package app.timetable.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.timetable.MainActivity

object Notifications {

    const val CHANNEL_REMINDER = "course_reminder"
    const val CHANNEL_SYNC = "sync_status"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDER,
                "上课提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "按提前量提醒下一节课"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                "课表同步",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "课表抓取失败或登录过期时提示" }
        )
    }

    private fun mainIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun remind(
        context: Context,
        id: Int,
        title: String,
        text: String
    ) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = Notification.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context))
            .build()
        runCatching { nm.notify(id, n) }
    }

    fun notifySyncProblem(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = Notification.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("课表同步失败")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context))
            .build()
        runCatching { nm.notify(1001, n) }
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    /** Android 13+ 需要运行时授权通知权限 */
    fun canPost(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.areNotificationsEnabled()
    }

    fun openNotificationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
