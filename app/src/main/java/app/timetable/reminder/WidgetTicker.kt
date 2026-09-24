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

    /**
     * 每日心跳的时刻（次日凌晨）。
     *
     * 为什么是 00:05 而不是 00:00：换天后要立刻重算"今天"，但 00:00 那一分钟
     * 正撞上系统的日期变更与一堆整点任务；挪 5 分钟既避开拥挤，又保证
     * 用户 6 点起床看到的已经是新一天。
     */
    val HEARTBEAT_TIME: LocalTime = LocalTime.of(0, 5)

    fun reschedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        cancelAll(app, am)

        // 上课提醒那边只排未来 N 天的一次性闹钟，续期原本只发生在
        // App 启动 / 开机 / 同步成功 / 设置变更四处 —— 没有任何一处保证每天都发生。
        // 结果就是"一周多不开 App，提醒永久消失"，而且不报错、不提示。
        // 每天 00:05 的心跳（见文件头）是这个 App 唯一不依赖用户行为的每日续命点，
        // 所以在这里重排一次提醒。
        //
        // 注意位置：必须紧跟在 cancelAll 后面、**在所有 return 之前**。
        // 上面自己刚把整个 requestCode 区间的闹钟取消掉了，紧接着的 return
        // （没有课表数据时）会导致"取消了却不再排" —— 把提醒一起误杀。
        ReminderScheduler.reschedule(app)

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

        // 次日凌晨补一次：换天 + 重排新一天的边界 + 续期上课提醒（见上面 ReminderScheduler 那行）
        val heartbeat = LocalDateTime.of(today.plusDays(1), HEARTBEAT_TIME)
        fireAt(app, am, heartbeat.atZone(zone).toInstant().toEpochMilli(), req)
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
