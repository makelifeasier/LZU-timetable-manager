package app.timetable.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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

    // ------------------------------------------------- Android 13+ 的 POST_NOTIFICATIONS

    /**
     * 当前是否**还需要**去要一次通知权限。
     *
     * 为什么必须有这个：AndroidManifest 里只声明 POST_NOTIFICATIONS 是不够的 ——
     * 它是 API 33 起的运行时权限。只声明、不申请，通知会在 PostNotification 那一层
     * 被系统直接丢掉：不报错、不提示，用户只会看到"提醒时间到了什么都没弹"。
     * 而本 App 的提醒恰恰只有通知这一种表现形式，等于提醒功能整个哑掉。
     *
     * 判定用 `areNotificationsEnabled()`（而不是自检权限字符串）：
     * 它同时覆盖"从没申请过"和"申请了但被拒"两种状态。只返回"要不要申请"，
     * 不负责弹窗 —— 弹窗必须在 Activity 里做（本文件不碰 Activity）。
     */
    fun needsPermission(context: Context): Boolean =
        needsPermission(Build.VERSION.SDK_INT, canPost(context))

    /**
     * 纯判定：给定系统版本与"通知当前是否可用"，要不要去要权限。
     *
     * 抽成 static 是为了能单元测试：`Build.VERSION.SDK_INT` 在 JVM 单测里是假值
     * （testOptions.unitTests.isReturnDefaultValues = true → 一律返回 0），
     * 直接测 [needsPermission] 永远只能测到"不需要"这一支。
     * 这里把真正的分支逻辑摊开，测试传 32/33 就能覆盖两边。
     */
    @JvmStatic
    fun needsPermission(sdkInt: Int, notificationsEnabled: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU && !notificationsEnabled

    /**
     * 去系统设置里给本 App 打开通知的 Intent；拿不到就返回 null。
     *
     * 设计成"返回 Intent 而不是直接 startActivity"：
     *  - 调用方（设置页）才知道该不该先弹个说明、该不该加 FLAG_ACTIVITY_NEW_TASK
     *    （Activity 里不需要，从非 Activity 上下文里必须有）；
     *  - 单元测试里没有 Activity，也就不可能去测一个会真的跳转的函数；
     *    把 Intent 交出去，跳不跳、怎么跳由调用方决定。
     * 返回 null 的条件是包名为空 —— 那种情况下带 EXTRA_APP_PACKAGE 的 Intent
     * 会落到"所有应用的通知设置"，用户还得自己再找一遍，不如让调用方改走别的入口。
     */
    fun requestIntent(context: Context): Intent? {
        val pkg = context.packageName
        // 包名为空时宁可返回 null：带空 EXTRA_APP_PACKAGE 的 Intent 会落到
        // "所有应用的通知设置"，用户还得自己再找一遍，不如让调用方改走别的入口。
        if (pkg.isNullOrBlank()) return null
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
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
