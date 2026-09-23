package app.timetable.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.timetable.net.Fetcher
import app.timetable.notify.Notifications
import app.timetable.reminder.ReminderScheduler
import app.timetable.reminder.WidgetTicker
import app.timetable.widget.TodayWidgetProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** 抓取 / 解析 / 缓存 / 广播给 UI、小组件、提醒的编排中心 */
object TimetableRepository {

    data class SyncStatus(
        val ok: Boolean = false,
        val message: String = "尚未同步",
        val httpCode: Int = 0,
        val finalUrl: String = "",
        val bytes: Int = 0,
        val sessionCount: Int = 0,
        val loginExpired: Boolean = false,
        val at: Long = 0L
    )

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "timetable-io").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var cached: ParseResult = ParseResult()

    @Volatile
    var status: SyncStatus = SyncStatus()
        private set

    fun init(context: Context) {
        Prefs.init(context)
        cached = TimetableJson.fromJson(Prefs.resultJson)
        status = SyncStatus(
            ok = Prefs.lastSyncAt > 0 && cached.sessions.isNotEmpty(),
            message = Prefs.lastSyncMessage.ifBlank { "尚未同步" },
            httpCode = Prefs.lastHttpCode,
            finalUrl = Prefs.lastFinalUrl,
            bytes = Prefs.lastHtmlBytes,
            sessionCount = cached.sessions.size,
            loginExpired = Prefs.loginExpired,
            at = Prefs.lastSyncAt
        )
    }

    fun result(): ParseResult = cached

    /** 设置变更后：重排提醒 + 刷新小组件 + 通知 UI */
    fun notifyDataChanged(context: Context) {
        val app = context.applicationContext
        TodayWidgetProvider.refreshAll(app)
        ReminderScheduler.reschedule(app)
        WidgetTicker.reschedule(app)
        notifyChanged()
    }

    /** 清空缓存后从 Prefs 重新载入 */
    fun reloadFromPrefs(context: Context) {
        cached = TimetableJson.fromJson(Prefs.resultJson)
        status = status.copy(sessionCount = cached.sessions.size)
        notifyDataChanged(context)
    }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    private fun notifyChanged() {
        main.post { listeners.toList().forEach { runCatching { it() } } }
    }

    /**
     * 解析并落库（登录页抓到 HTML 后调用）
     *
     * @param emptyMessage 没解析出课程时的提示。默认是「页面结构可能变了」，
     *   但首次使用还没登录成功时，抓到的往往是登录页或 404 页 ——
     *   那种情况下说"结构变了"会把人吓一跳，调用方可以换成更贴切的说明。
     */
    fun acceptHtml(
        context: Context,
        html: String,
        source: String = "登录页",
        emptyMessage: String? = null
    ): Boolean {
        val parsed = TimetableParser.parse(html)
        Prefs.diagnosticsHtml = html
        if (parsed.sessions.isEmpty()) {
            Prefs.loginExpired = false
            Prefs.lastSyncMessage = emptyMessage ?: "$source：解析到 0 门课（页面结构可能变了，见诊断）"
            Prefs.lastSyncAt = System.currentTimeMillis()
            status = SyncStatus(
                ok = false, message = Prefs.lastSyncMessage, bytes = html.length,
                sessionCount = 0, at = Prefs.lastSyncAt
            )
            notifyChanged()
            return false
        }
        commit(context, parsed, html, "已从${source}导入")
        return true
    }

    private fun commit(context: Context, parsed: ParseResult, html: String, message: String) {
        cached = parsed
        Prefs.resultJson = TimetableJson.toJson(parsed)
        Prefs.lastSyncAt = System.currentTimeMillis()
        Prefs.lastSyncMessage = message
        Prefs.loginExpired = false
        Prefs.diagnosticsHtml = html
        status = SyncStatus(
            ok = true, message = message,
            httpCode = Prefs.lastHttpCode, finalUrl = Prefs.lastFinalUrl,
            bytes = html.length, sessionCount = parsed.sessions.size, at = Prefs.lastSyncAt
        )
        // 首次导入：按学期估算第 1 周（教务系统页面上没有当前周次）。
        // 估算不准也只是差一两周，用户可在设置里精确校正 ——
        // 但绝不能假设「本周就是第 1 周」，那样第 8 周才装 App 的同学会看到完全错的课表。
        if (Prefs.week1Monday.isBlank()) {
            Prefs.week1Monday = WeekCalc.initialWeek1Monday(parsed, java.time.LocalDate.now()).toString()
        }
        TodayWidgetProvider.refreshAll(context)
        ReminderScheduler.reschedule(context)
        WidgetTicker.reschedule(context)
        notifyChanged()
    }

    /** 后台抓取；完成回调在主线程 */
    fun refresh(context: Context, onDone: ((SyncStatus) -> Unit)? = null) {
        val app = context.applicationContext
        val url = Prefs.timetableUrl

        // 还没导入过课表（首次安装、或刚清空数据）：没有任何链接可抓。
        // 这里必须挡住 —— 否则会拿空 URL 去发请求，报一堆看不懂的网络错误。
        if (url.isBlank()) {
            val msg = "还没导入课表，先点「登录导入」"
            Prefs.lastSyncMessage = msg
            Prefs.loginExpired = false
            status = status.copy(ok = false, message = msg, loginExpired = false, at = System.currentTimeMillis())
            TodayWidgetProvider.refreshAll(app)
            main.post { onDone?.invoke(status); notifyChanged() }
            return
        }

        io.execute {
            val out = Fetcher.fetch(url)
            Prefs.lastHttpCode = out.httpCode
            Prefs.lastFinalUrl = out.finalUrl
            Prefs.lastHtmlBytes = out.bytes
            if (out.html.isNotEmpty()) Prefs.diagnosticsHtml = out.html

            when {
                out.error.isNotEmpty() -> {
                    val msg = "网络失败：${out.error}"
                    Prefs.lastSyncMessage = msg
                    status = status.copy(ok = false, message = msg, at = System.currentTimeMillis())
                    main.post { Notifications.notifySyncProblem(app, msg) }
                }

                out.loginExpired -> {
                    Prefs.loginExpired = true
                    val msg = "登录已过期，需要重新登录"
                    Prefs.lastSyncMessage = msg
                    status = status.copy(
                        ok = false, message = msg, loginExpired = true,
                        httpCode = out.httpCode, finalUrl = out.finalUrl, bytes = out.bytes,
                        at = System.currentTimeMillis()
                    )
                }

                !out.ok -> {
                    val msg = "服务器返回 HTTP ${out.httpCode}"
                    Prefs.lastSyncMessage = msg
                    status = status.copy(ok = false, message = msg, httpCode = out.httpCode, at = System.currentTimeMillis())
                }

                else -> {
                    val parsed = TimetableParser.parse(out.html)
                    if (parsed.sessions.isEmpty()) {
                        val msg = "抓到了页面但解析出 0 门课（见诊断页导出）"
                        Prefs.lastSyncMessage = msg
                        Prefs.loginExpired = false
                        status = SyncStatus(
                            ok = false, message = msg, httpCode = out.httpCode,
                            finalUrl = out.finalUrl, bytes = out.bytes,
                            sessionCount = 0, at = System.currentTimeMillis()
                        )
                    } else {
                        main.post { commit(app, parsed, out.html, "已同步 ${parsed.sessions.size} 段课") }
                    }
                }
            }
            TodayWidgetProvider.refreshAll(app)
            main.post { onDone?.invoke(status); notifyChanged() }
        }
    }
}
