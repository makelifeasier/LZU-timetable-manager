package app.timetable

import android.app.Application
import android.webkit.CookieManager
import app.timetable.data.Prefs
import app.timetable.data.TimetableRepository
import app.timetable.notify.Notifications
import app.timetable.reminder.ReminderScheduler
import app.timetable.reminder.WidgetTicker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)

        // 注意：android.webkit.CookieManager 并不继承 java.net.CookieHandler，
        // 所以 WebView 登录后的 Cookie 不会自动出现在 HttpURLConnection 上。
        // 抓取时由 net/Fetcher 主动取 CookieManager 的 Cookie 头（见 Fetcher.readCookie）。
        CookieManager.getInstance().setAcceptCookie(true)

        Notifications.ensureChannels(this)
        TimetableRepository.init(this)
        ReminderScheduler.reschedule(this)
        WidgetTicker.reschedule(this)
    }
}
