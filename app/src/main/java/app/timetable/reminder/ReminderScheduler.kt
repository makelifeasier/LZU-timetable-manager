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
 * 按「提前 N 分钟」为未来 [WINDOW_DAYS] 天的每节课注册一次性闹钟。
 * 精确闹钟权限被拒时自动降级为 setWindow（不精确但可用）。
 *
 * 两个必须一起看的常量：
 *  - [WINDOW_DAYS] 是排程窗口；
 *  - [REQ_BASE]..[REQ_BASE] + [REQ_SPAN] 是 requestCode 区间，**取消时按这个区间逐个扫**。
 * 窗口一放大，一次排的闹钟就变多，requestCode 上界绝对不能越过扫描区间 ——
 * 越过的那些闹钟排得上、取消不掉，换设置/停课之后还会照常响。
 */
object ReminderScheduler {

    private const val REQ_BASE = 43100
    private const val REQ_SPAN = 240

    /**
     * 一次排多少天。
     *
     * 为什么从 7 天放宽到 14 天：闹钟全是一次性的，续期只发生在 App 启动 / 开机 /
     * 同步成功 / 设置变更这几处。只排 7 天的话，**一周不开 App 就永久不再响**
     * —— 而且不报错、不提示，用户只会觉得"提醒坏了"。
     * 14 天给"偶尔开一次"的人留了一整周余量，数量上也只是多一倍。
     *
     * 上限推导：14 天 × 每天最多 12 节课 = 168 < [REQ_SPAN] 240，
     * 所以下面计划里的上界永远落在取消扫描区间内（ReminderWindowTest 会守住这条）。
     */
    private const val WINDOW_DAYS = 14

    /** 教务系统课表一共 12 个节次，用它当"一天最多几节课"的上界 */
    private const val MAX_SESSIONS_PER_DAY = 12

    /** 排程窗口覆盖的「今天 + offset」天数。抽成函数是为了单元测试能直接验算上界 */
    fun offsets(): IntRange = 0 until WINDOW_DAYS

    /** 一次排程最多会占用多少个 requestCode。 */
    internal val maxRequestCodes: Int get() = WINDOW_DAYS * MAX_SESSIONS_PER_DAY

    /** 取消扫描区间能不能覆盖最坏情况 —— 覆盖不了就是「排得上、取消不掉」 */
    internal fun spanCoversWindow(): Boolean = maxRequestCodes <= REQ_SPAN

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

        outer@ for (offset in offsets()) {
            val date = today.plusDays(offset.toLong())
            val week = WeekCalc.weekOf(date, week1)
            if (week < 1) continue
            // 走 sessionsOn：当天调课（替换/停课/加课）之后提醒也要跟着变，
            // 不然"App 里明明清空了，闹钟还是响了"这种问题最难解释
            for (s in TimetableRepository.sessionsOn(app, date)) {
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
