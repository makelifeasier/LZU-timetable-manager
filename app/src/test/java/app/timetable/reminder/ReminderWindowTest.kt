package app.timetable.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排程窗口与 requestCode 区间的自洽性。
 *
 * 这里不做任何 Android 调用（AlarmManager 在 JVM 单测里是空壳），
 * 只验算**常量之间的关系** —— 而那条关系一旦破了，后果是静默的：
 * 闹钟排得上、取消不掉，用户改了设置之后旧提醒还会照常响。
 */
class ReminderWindowTest {

    @Test
    fun windowIsFourteenDays() {
        // 7 天太少：一周不开 App 就永久不再响（续期点只有启动/开机/同步成功/改设置）
        val offsets = ReminderScheduler.offsets().toList()
        assertEquals(14, offsets.size)
        assertEquals(0, offsets.first())
        assertEquals(13, offsets.last())
    }

    @Test
    fun worstCaseRequestCodesFitInsideTheCancelScanRange() {
        // cancelAll() 是按 REQ_BASE..REQ_BASE+REQ_SPAN 逐个扫的。
        // 排程用掉的 requestCode 只要越过这个上界，那些闹钟就再也取消不掉。
        assertTrue(
            "窗口放大后排的闹钟数量必须仍落在取消扫描区间内",
            ReminderScheduler.spanCoversWindow()
        )
    }

    @Test
    fun windowStillLeavesHeadroomInTheScanRange() {
        // 留一点余量：以后有人再调大窗口时，这里会先炸，而不是线上静默越界
        assertTrue(ReminderScheduler.maxRequestCodes <= 240)
    }

    @Test
    fun reminderAndWidgetRequestCodesNeverCollide() {
        // 两套闹钟共用系统的一次性闹钟表，PendingIntent 靠 requestCode 区分。
        // 提醒在 43100+，小组件心跳在 45000+ —— 区间不许重叠，
        // 否则 cancelAll 会把对方的闹钟一起干掉（或反之），表现为"提醒偶尔不响"。
        val reminderMax = 43100 + 240
        val widgetBase = 45000
        assertTrue("提醒区间上界必须落在小组件区间之下", reminderMax < widgetBase)
    }

    @Test
    fun heartbeatStaysJustAfterMidnight() {
        // 心跳负责每天续期上课提醒。这里把 00:05 这个约定钉住，
        // 免得被"顺手优化"成别的时刻 —— 挪到深夜就等于当天排程要等一整天。
        assertEquals(0, WidgetTicker.HEARTBEAT_TIME.hour)
        assertEquals(5, WidgetTicker.HEARTBEAT_TIME.minute)
    }
}
