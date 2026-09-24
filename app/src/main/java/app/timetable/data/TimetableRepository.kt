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

    /**
     * 教务系统解析出来的**原始**结果。只由 init / reloadFromPrefs / commit 写入。
     *
     * 自定义课程**不**往这里写，也不写回 `Prefs.resultJson`：那是下一次同步会被整份覆盖的
     * 原始数据缓存，混进去第一次自动同步就没了（理由详见 [UserCourses] 的文件头注释）。
     */
    @Volatile
    private var rawParsed: ParseResult = ParseResult()

    /** 用户自己加的课（进程内副本，内容和 [UserCourses] 的存储一致） */
    @Volatile
    private var userSessions: List<Session> = emptyList()

    /**
     * 对外统一的课表 = [rawParsed] + 自定义课程。
     *
     * 为什么合并只在这一层做一次：`sessionsOn` / `weekGrid` / `WeekCalc.agenda` /
     * 课表页 / 桌面小组件 / 导出图片 / 上课提醒，读的都是 `result()`（或经由 `sessionsOn`）。
     * 把合并放进这个唯一的读取入口，就不会再出现"App 里看得见、小组件里没有"那种
     * 各条链路各合并一遍才会有的不一致。
     */
    @Volatile
    private var cached: ParseResult = ParseResult()

    @Volatile
    var status: SyncStatus = SyncStatus()
        private set

    fun init(context: Context) {
        Prefs.init(context)
        rawParsed = TimetableJson.fromJson(Prefs.resultJson)
        reloadUserCourses(context)
        status = SyncStatus(
            // 这里的"同步成功/N 段课"说的都是**教务抓回来的量**，不含用户自己加的课：
            // 状态行是用来判断"同步这件事成没成"的，把自定义课程算进去会让它自相矛盾。
            ok = Prefs.lastSyncAt > 0 && rawParsed.sessions.isNotEmpty(),
            message = Prefs.lastSyncMessage.ifBlank { "尚未同步" },
            httpCode = Prefs.lastHttpCode,
            finalUrl = Prefs.lastFinalUrl,
            bytes = Prefs.lastHtmlBytes,
            sessionCount = rawParsed.sessions.size,
            loginExpired = Prefs.loginExpired,
            at = Prefs.lastSyncAt
        )
    }

    fun result(): ParseResult = cached

    /** 重新拼出对外的课表（教务原始 + 自定义）。任何一方变了都要调一次 */
    private fun republish() {
        cached = UserCourses.mergeInto(rawParsed, userSessions)
    }

    /** 从存储里重读自定义课程并重新拼装。启动、重新导入、用户增删改之后各调一次 */
    private fun reloadUserCourses(context: Context) {
        userSessions = UserCourses.sessions(context)
        republish()
    }

    // ------------------------------------------------- 当日调课（覆盖）统一入口

    /**
     * 某一天的课表（**已应用当日覆盖**）。
     *
     * 所有"看某一天要上什么"的地方都必须走这里：课表页、桌面小组件、导出图片、提醒。
     * 否则会出现"App 里改了当天、小组件没变"这种最难解释的不一致 ——
     * 当日覆盖本质上是**多了一条数据来源**，只能靠单一入口收口。
     */
    fun sessionsOn(context: Context, date: java.time.LocalDate): List<Session> {
        val r = result()
        val w1 = Prefs.week1MondayDate() ?: return emptyList()
        val week = WeekCalc.weekOf(date, w1)
        if (week < 1) return emptyList()
        val day = WeekCalc.dayOf(date)
        val base = WeekCalc.sessionsFor(r, day, week)
        // 一律合并后再返回：调用方（课表页 / 小组件 / 今日弹窗）都拿它当"可直接显示的列表"。
        // 不合并的话「第5节+第6节」会显示成两行，与没调课时的表现不一致。
        val o = DayOverrides.get(context, date) ?: return WeekCalc.merge(base)
        return WeekCalc.merge(
            when {
                // 「整份列表」模式：这一天完全由 extra 给出（用户点某一节课改掉/删掉了它）。
                // 合并相邻节次那一步在 DayOverrides.listFor 里，和纯逻辑测试共用同一段代码。
                o.isList -> DayOverrides.listFor(o, day)

                // 替换：换成"第 sourceWeek 周的 sourceDay（默认与这天同星期几）那一天的课"
                o.mode == DayOverrides.Mode.REPLACE ->
                    if (o.sourceWeek < 1) {
                        base
                    } else {
                        // 取值逻辑抽在 DayOverrides.replaceSource 里（纯函数、有单测）：
                        // 这段曾经只按"同一个星期几"取，导致"周四换周三的课"做不到
                        DayOverrides.replaceSource(r, o, day)
                    }

                o.mode == DayOverrides.Mode.CLEAR -> emptyList()
                else -> DayOverrides.mergeSorted(base, o.extra, day)
            }
        )
    }

    /**
     * 某一周的网格（**已应用当日覆盖**）。课表页画的就是它。
     *
     * 逐日套一遍覆盖：只有当被覆盖的那一天正好落在当前显示的这周里才会生效。
     */
    fun weekGrid(context: Context, week: Int): Map<Int, List<Session>> {
        val base = WeekCalc.weekGrid(result(), week)
        val overrides = DayOverrides.all(context)
        if (overrides.isEmpty()) return base
        val w1 = Prefs.week1MondayDate() ?: return base
        val monday = w1.plusWeeks((week - 1).toLong())
        val out = LinkedHashMap<Int, List<Session>>()
        for (day in 1..7) {
            val date = monday.plusDays((day - 1).toLong())
            val o = overrides[date]
            out[day] = if (o == null) base[day].orEmpty() else sessionsOn(context, date)
        }
        // 覆盖可能引入"同一节重复"（ADD 加的课与原课同节），逐日再合并一次
        return out.mapValues { (_, list) -> WeekCalc.merge(list) }
    }

    /** 设置变更后：重排提醒 + 刷新小组件 + 通知 UI */
    fun notifyDataChanged(context: Context) {
        val app = context.applicationContext
        // 顺带重读自定义课程：加了/改了/删了自己的课之后，对话框只需要调这一个方法，
        // 课表页、小组件、导出图片、提醒就会一起换成新数据 —— 依旧是"一个入口收口"。
        // （重读的是内存里的 SharedPreferences 加一份小 JSON，代价可以忽略；
        //   这个方法的调用频率是"用户点一下设置"级别。）
        reloadUserCourses(app)
        TodayWidgetProvider.refreshAll(app)
        ReminderScheduler.reschedule(app)
        WidgetTicker.reschedule(app)
        notifyChanged()
    }

    /** 清空缓存后从 Prefs 重新载入 */
    fun reloadFromPrefs(context: Context) {
        rawParsed = TimetableJson.fromJson(Prefs.resultJson)
        reloadUserCourses(context)          // 内部会 republish()
        status = status.copy(sessionCount = rawParsed.sessions.size)
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
            // 这里**不能**把 loginExpired 清掉：解析出 0 门课既可能是"抓错页面"，
            // 也可能是"会话真的过期了、抓到的是登录页"。一刀切清掉的话，
            // 用户在未登录页面上点一次「导入当前页面」，主界面和小组件上的
            // "建议重新登录"就会消失，而下次同步照样失败 —— 提示没了，问题还在。
            Prefs.lastSyncMessage = emptyMessage ?: "$source：解析到 0 门课（页面结构可能变了，见诊断）"
            Prefs.lastSyncAt = System.currentTimeMillis()
            status = SyncStatus(
                ok = false, message = Prefs.lastSyncMessage, bytes = html.length,
                sessionCount = 0, at = Prefs.lastSyncAt,
                loginExpired = Prefs.loginExpired
            )
            notifyChanged()
            return false
        }
        commit(context, parsed, html, "已从${source}导入")
        return true
    }

    // ---------------------------------------------- 换学期后周次基准要不要重算（纯逻辑）

    /**
     * 一份课表的「学期签名」：形如 `"2026 秋"`，两个字段都空时返回空串。
     *
     * 之所以不直接用 `TermInfo.display`：签名会被写进 SharedPreferences 并长期参与比较，
     * 需要**稳定**。display 是给人看的文案，哪天改了连接方式或过滤规则，
     * 老用户存量签名就会和新的对不上，被误判成「换学期了」，白重置一次周次基准。
     *
     * 为什么 year、term 任一变化都要算换学期：年份变了（2026 春 → 2027 春）或季节变了
     * （2026 春 → 2026 秋）都意味着另一份课表，周次基准必须重来。
     */
    fun termSignatureOf(term: TermInfo): String =
        listOf(term.year.trim(), term.term.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /**
     * 重新导入后该怎么处理「第 1 周周一」。
     *
     * 背景：教务系统课表页里**没有当前周次**，第 1 周周一只能估（见 WeekCalc.initialWeek1Monday）。
     * 以前只在 `week1Monday` 为空时才估 —— 于是上学期装过、下学期重新导入，基准还停在上学期：
     * 当前周次会被算成 30+，`sessionsOn` / `weekGrid` 找不到任何 `weeks.contains(week)` 的课，
     * 课表页空白、小组件空、提醒不排，而且「本周」按钮也救不回来（real 就是错的），
     * 必须进设置手改。每学期复发一次。
     *
     * 取舍（下面前两条最要紧）：
     *  - 学期**没变**时绝不动 `week1Monday`：用户可能在设置里精确校正过，
     *    重新导入（自动同步每天都在做）不能把他的手改冲掉。
     *  - 老版本升级上来的用户签名是空的，但基准已经有了 —— 这种情况只**补签名、不重算**，
     *    否则一升级就把所有人的校正清零。
     */
    data class Week1Plan(
        /** 把 [termSignatureOf] 的结果写回 Prefs（写成新签名，或给老用户补上） */
        val seedSignature: Boolean = false,
        /** 用 WeekCalc.initialWeek1Monday 重算第 1 周周一 */
        val reestimateWeek1: Boolean = false,
        /** 把「手动预览周次」清回 0（跟随今天） */
        val resetManualWeek: Boolean = false,
        /** 学期真的变了（只用于日志/说明，不代表一定重算过） */
        val termChanged: Boolean = false
    )

    /**
     * 纯决策：给定「已存签名 / 已存第 1 周周一 / 新解析出的签名」，该做什么。
     * 一个 Android API 都不碰，所以能直接单元测试（见 TimetableRepositoryWeek1Test）。
     */
    fun planWeek1(storedSignature: String, storedWeek1: String, newSignature: String): Week1Plan {
        val sig = newSignature.trim()
        val stored = storedSignature.trim()

        // 新学期还没解析出来（页面缺学年/季节）：什么都不做。
        // 这时既不能写空签名，也不能估算 —— 估出来的基准可能完全是错的。
        if (sig.isEmpty()) return Week1Plan()

        // 签名变了（或第一次有签名）。老用户只补签名：他的基准是有意校正过的。
        if (stored.isEmpty()) return Week1Plan(seedSignature = true)

        if (stored == sig) {
            return if (storedWeek1.isBlank()) {
                // 学期没变但基准还没定过（装上后从没导入成功过）：估一个，并清掉手动周次
                Week1Plan(resetManualWeek = true)
            } else {
                // 学期没变、基准已有 —— 保持用户手动校正过的值，绝不覆盖
                Week1Plan()
            }
        }

        // 学期换了：整学期都会因为错误基准而变空，必须重算，并把手动周次清回「跟随今天」
        return Week1Plan(
            seedSignature = true,
            reestimateWeek1 = true,
            resetManualWeek = true,
            termChanged = true
        )
    }

    private fun commit(context: Context, parsed: ParseResult, html: String, message: String) {
        rawParsed = parsed
        // 立刻重新拼装：同步回来的从来只有教务课表，自定义课程得原样留在课表上
        republish()
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

        // 周次基准：教务系统页面上没有当前周次，只能按学期估（估算不准也只是差一两周，
        // 设置里可精确校正）；但**换了学期就必须重估** —— 否则整学期课表全空且救不回来。
        // 具体取舍见 planWeek1。
        val newSignature = termSignatureOf(parsed.term)
        val plan = planWeek1(
            storedSignature = Prefs.termSignature,
            storedWeek1 = Prefs.week1Monday,
            newSignature = newSignature
        )
        val today = java.time.LocalDate.now()
        // 写基准只有两个时机：① 换学期（planWeek1 给出的 reestimateWeek1）；
        // ② 基准本来就是空的（首次导入、或清空缓存后重新导入 —— 这条与老代码等价）。
        // 其余一律不写：用户手动校正过的基准不能被每天的自动同步冲掉。
        if (plan.reestimateWeek1 || Prefs.week1Monday.isBlank()) {
            Prefs.week1Monday = WeekCalc.initialWeek1Monday(parsed, today).toString()
        }
        if (plan.seedSignature) Prefs.termSignature = newSignature
        if (plan.resetManualWeek) Prefs.manualWeek = 0

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
