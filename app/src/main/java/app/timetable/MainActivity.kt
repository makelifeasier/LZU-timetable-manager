package app.timetable

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import app.timetable.data.ParseResult
import app.timetable.data.Prefs
import app.timetable.data.Session
import app.timetable.data.TimetableRepository
import app.timetable.data.WeekCalc
import app.timetable.databinding.ActivityMainBinding
import app.timetable.share.ImageExporter
import app.timetable.ui.Backgrounds
import app.timetable.ui.BaseActivity
import app.timetable.ui.Ui
import java.time.LocalDate
import java.time.LocalTime

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    private val listener: () -> Unit = { render() }

    /** 手动预览的周次；0 = 跟随今天 */
    private var overrideWeek = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        TimetableRepository.init(this)

        binding.timetable.onSessionTap = { showSessionDetail(it) }
        binding.prevWeek.setOnClickListener { shiftWeek(-1) }
        binding.nextWeek.setOnClickListener { shiftWeek(1) }
        binding.thisWeek.setOnClickListener { jumpToCurrentWeek() }
        binding.syncBtn.setOnClickListener { syncNow() }
        binding.menuBtn.setOnClickListener { showMenu(it) }
        binding.bannerButton.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        overrideWeek = Prefs.manualWeek
        TimetableRepository.addListener(listener)
        maybeAutoRefresh()
        render()
        maybePromptFirstRun()
    }

    /**
     * 全新安装（没有任何课表数据）时自动把登录页推上来一次。
     *
     * 目的：任何兰大同学装上、打开，下一步就是登录 —— 不用自己去找入口。
     * 只弹一次：用户退出后不再骚扰，横幅和 ⋮ 菜单里都还能再次进入。
     * 已导入过的老用户不受影响。
     */
    private fun maybePromptFirstRun() {
        if (Prefs.hasTimetable || Prefs.firstRunPrompted) return
        if (TimetableRepository.result().sessions.isNotEmpty()) return
        Prefs.firstRunPrompted = true
        startActivity(Intent(this, LoginActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        applyBackground()
        render()
    }

    /** 自定义背景铺在根布局上（含蒙版），课表画布那层就不再铺底色 */
    private fun applyBackground() {
        val custom = Backgrounds.buildDrawable(this, Ui.isNight(this))
        binding.root.background = custom ?: ColorDrawable(getColor(R.color.page_bg))
    }

    override fun onDestroy() {
        TimetableRepository.removeListener(listener)
        super.onDestroy()
    }

    // ------------------------------------------------------------- 菜单

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_LOGIN, 0, R.string.action_login)
        popup.menu.add(0, MENU_SYNC, 1, R.string.action_sync)
        popup.menu.add(0, MENU_TODAY, 2, R.string.action_today)
        popup.menu.add(0, MENU_EXPORT, 3, R.string.action_export)
        popup.menu.add(0, MENU_SETTINGS, 4, R.string.action_settings)
        popup.menu.add(0, MENU_DIAG, 5, R.string.action_diagnostics)
        popup.setOnMenuItemClickListener { onMenuAction(it.itemId) }
        popup.show()
    }

    private fun onMenuAction(id: Int): Boolean = when (id) {
        MENU_LOGIN -> {
            startActivity(Intent(this, LoginActivity::class.java)); true
        }

        MENU_SYNC -> {
            syncNow(); true
        }

        MENU_TODAY -> {
            showToday(); true
        }

        MENU_EXPORT -> {
            exportImage(); true
        }

        MENU_SETTINGS -> {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }

        MENU_DIAG -> {
            startActivity(Intent(this, DiagnosticsActivity::class.java)); true
        }

        else -> false
    }

    private fun syncNow() {
        binding.statusLine.text = "正在同步…"
        TimetableRepository.refresh(this) { st ->
            Toast.makeText(this, st.message, Toast.LENGTH_SHORT).show()
            render()
        }
    }

    // ------------------------------------------------------------- 渲染

    private fun resolvedWeek(result: ParseResult, today: LocalDate): Pair<Int, Int> {
        val w1 = Prefs.week1MondayDate()
        val real = if (w1 != null) WeekCalc.weekOf(today, w1) else 0
        val shown = if (overrideWeek > 0) overrideWeek else real.coerceAtLeast(1)
        return shown to real
    }

    private fun render() {
        val result = TimetableRepository.result()
        val status = TimetableRepository.status
        val today = LocalDate.now()
        val (week, realWeek) = resolvedWeek(result, today)
        val weekMonday = Prefs.week1MondayDate()?.plusDays(((week - 1) * 7).toLong())

        binding.infoText.text = listOf(result.term.display, result.term.className)
            .filter { it.isNotBlank() }
            .joinToString("  ·  ")
            .ifBlank { "未导入课表" }

        val syncWhen = if (status.at > 0) relativeTime(status.at) else "尚未同步"
        // 登录过期只在状态行里轻描淡写一句，不再用黄条一直糊在脸上 ——
        // 缓存课表照样能看，反复提示没有意义
        binding.statusLine.text = if (result.sessions.isEmpty() && !Prefs.hasTimetable) {
            // 全新安装：别显示「0 段课 · 刚刚同步」这种自相矛盾的话
            "未导入课表"
        } else {
            buildString {
                append("${result.sessions.size} 段课  ·  $syncWhen")
                if (status.loginExpired && result.sessions.isNotEmpty()) append("  ·  建议重新登录")
            }
        }

        binding.weekText.text = if (weekMonday == null) {
            // 还没有课表时不要声称「第 1 周」（那只是个占位值，会误导新同学）
            "暂无课表"
        } else {
            val sun = weekMonday.plusDays(6)
            "第 $week 周   ${weekMonday.monthValue}/${weekMonday.dayOfMonth} – ${sun.monthValue}/${sun.dayOfMonth}"
        }

        val showingNow = week == realWeek
        binding.timetable.setData(
            result,
            week,
            if (showingNow) WeekCalc.dayOf(today) else 0,
            if (showingNow) LocalTime.now() else null,
            weekMonday,
            Prefs.style,
            weekendTint = Prefs.weekendTint
        )

        // 只有「完全没有数据」才弹提示条；有数据就安静地显示课表
        if (result.sessions.isEmpty()) {
            binding.banner.visibility = View.VISIBLE
            binding.bannerText.text = when {
                status.loginExpired -> "登录已过期，登录后即可导入课表"
                // 全新安装：说明白「只要登录一次」，不然任何同学打开都是一片空白
                !Prefs.hasTimetable -> "首次使用：登录一次统一身份认证，自动导入你的课表"
                else -> "还没有课表数据，点这里重新导入"
            }
        } else {
            binding.banner.visibility = View.GONE
        }
    }

    private fun shiftWeek(delta: Int) {
        val result = TimetableRepository.result()
        val today = LocalDate.now()
        val (week, _) = resolvedWeek(result, today)
        val max = maxOf(result.maxWeek, 20)
        overrideWeek = (week + delta).coerceIn(1, max)
        Prefs.manualWeek = overrideWeek
        render()
    }

    private fun jumpToCurrentWeek() {
        overrideWeek = 0
        Prefs.manualWeek = 0
        render()
    }

    private fun maybeAutoRefresh() {
        val status = TimetableRepository.status
        val stale = System.currentTimeMillis() - status.at > 30 * 60 * 1000L
        // 一直没数据的失败尝试要允许重试 —— 否则启动瞬间网络没就绪（很常见），
        // 失败时间会刷新 status.at，接下来半小时都不会再自动试一次
        val noDataAndFailed = TimetableRepository.result().sessions.isEmpty() && !status.ok
        if (!status.loginExpired && (status.at == 0L || stale || noDataAndFailed)) {
            TimetableRepository.refresh(this) { render() }
        }
    }

    // ------------------------------------------------------------- 今日

    // ------------------------------------------------------------- 课块详情

    /**
     * 点某节课看完整信息。
     * 格子宽度只有 40 多 dp，长课名/教师不可能全画下，这里是「显示不全」的兜底出口。
     */
    private fun showSessionDetail(s: Session) {
        val result = TimetableRepository.result()
        val start = result.sectionStart(s.startSection)
        val end = result.sectionEnd(s.endSection)
        val body = buildString {
            append(result.sectionRangeLabel(s.startSection, s.endSection))
            if (start.isNotEmpty()) {
                append("    ").append(start)
                if (end.isNotEmpty()) append(" – ").append(end)
            }
            append("\n\n")
            if (s.room.isNotEmpty()) append("地点    ").append(s.room).append("\n")
            if (s.teacher.isNotEmpty()) append("教师    ").append(s.teacher).append("\n")
            append("星期    ").append(Session.dayLabel(s.day)).append("\n")
            append("周次    ").append(s.weeks)
            if (s.seqNo.isNotEmpty()) append("\n课序号  ").append(s.seqNo)
        }
        AlertDialog.Builder(this)
            .setTitle(s.name)
            .setMessage(body)
            .setPositiveButton("好", null)
            .show()
    }

    private fun showToday() {
        val result = TimetableRepository.result()
        val today = LocalDate.now()
        val (week, _) = resolvedWeek(result, today)
        val list = WeekCalc.todaySessions(result, today, week)
        val text = if (list.isEmpty()) {
            "${Session.dayLabel(WeekCalc.dayOf(today))} · 第 $week 周\n\n今天没有课，好好休息。"
        } else {
            list.joinToString("\n\n") { s ->
                val time = result.sectionStart(s.startSection)
                buildString {
                    append(result.sectionRangeLabel(s.startSection, s.endSection))
                    if (time.isNotEmpty()) append("   ").append(time)
                    append("\n").append(s.name)
                    val where = s.room
                    if (where.isNotEmpty()) append("\n").append(where)
                    if (s.teacher.isNotEmpty()) append("   ").append(s.teacher)
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("${Session.dayLabel(WeekCalc.dayOf(today))} · 第 $week 周")
            .setMessage(text)
            .setPositiveButton("好", null)
            .show()
    }

    private fun exportImage() {
        val result = TimetableRepository.result()
        if (result.sessions.isEmpty()) {
            Toast.makeText(this, "还没有课表数据", Toast.LENGTH_SHORT).show()
            return
        }
        val today = LocalDate.now()
        val (week, _) = resolvedWeek(result, today)
        val uri = ImageExporter.export(this, week)
        if (uri == null) {
            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
            return
        }
        ImageExporter.share(this, uri, "第 $week 周课表")
    }

    private fun relativeTime(at: Long): String {
        val diff = System.currentTimeMillis() - at
        if (diff < 0) return "刚刚同步"
        val min = diff / 60000
        return when {
            min < 1 -> "刚刚同步"
            min < 60 -> "$min 分钟前同步"
            min < 60 * 24 -> "${min / 60} 小时前同步"
            else -> "${min / (60 * 24)} 天前同步"
        }
    }

    companion object {
        private const val MENU_LOGIN = 1
        private const val MENU_SYNC = 2
        private const val MENU_TODAY = 3
        private const val MENU_EXPORT = 4
        private const val MENU_SETTINGS = 5
        private const val MENU_DIAG = 6
    }
}
