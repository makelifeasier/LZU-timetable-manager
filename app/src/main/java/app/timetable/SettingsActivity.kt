package app.timetable

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.timetable.data.Prefs
import app.timetable.data.TimetableRepository
import app.timetable.data.WeekCalc
import app.timetable.databinding.ActivitySettingsBinding
import app.timetable.donate.DonateDialog
import app.timetable.donate.Donations
import app.timetable.notify.Notifications
import app.timetable.ui.Backgrounds
import app.timetable.ui.BaseActivity
import app.timetable.ui.TimetableRenderer
import app.timetable.ui.Ui
import app.timetable.widget.TodayWidgetProvider
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var draftWeek = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        build()
    }

    // ------------------------------------------------------------- 构建

    private fun build() {
        binding.container.removeAllViews()

        val today = LocalDate.now()
        val w1 = Prefs.week1MondayDate()
        val realWeek = if (w1 != null) WeekCalc.weekOf(today, w1) else 1
        draftWeek = realWeek.coerceIn(1, 30)

        buildSource()
        buildAccount()
        buildWeek(today, w1, realWeek)
        buildReminder()
        buildAppearance()
        buildMisc()
        buildSupport()
    }

    /**
     * 支持作者。只有在 APK 里**真的带了收款码**时才显示这个入口 ——
     * 源码里刻意不含收款码（见 Donations 的注释），所以别人 clone 自己构建时
     * 这里不会出现一个点不动的死按钮。
     */
    private fun buildSupport() {
        if (!Donations.hasQr(this)) return

        sectionTitle("支持作者")
        card {
            row("捐赠", "完全自愿。不支持也不影响任何功能，课表该有的都有。")
            buttonRow(
                "显示收款码" to { DonateDialog.show(this@SettingsActivity) }
            )
        }
    }

    private fun buildSource() {
        sectionTitle("课表来源")
        card {
            row("当前课表链接", if (Prefs.hasTimetable) "已获取" else "还没有 —— 点下面的按钮登录一次即可自动获取")
            // 每个学生的 id/yearid/termid 都不同，所以这里默认是空的，
            // 由登录后的自动发现填进来。手填只是应急/高级用法。
            row("说明", "登录统一身份认证后自动获取，一般不需要手填。若自动获取失败，可把教务系统「学生课表」页面地址粘到这里。")
            val edit = EditText(this@SettingsActivity).apply {
                setText(Prefs.timetableUrl)
                hint = "留空即可，登录后自动获取"
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                textSize = 12f
                setTextColor(color(R.color.text_secondary))
                background = getDrawable(R.drawable.bg_card_alt)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setHorizontallyScrolling(false)
                maxLines = 3
            }
            addPadded(edit)
            buttonRow(
                "登录并自动获取" to {
                    startActivity(Intent(this@SettingsActivity, LoginActivity::class.java))
                },
                "保存手填链接" to {
                    Prefs.timetableUrl = edit.text.toString()
                    toast("已保存，回到主页点「同步」生效")
                },
                "清空链接" to {
                    Prefs.timetableUrl = ""
                    edit.setText("")
                    toast("已清空。下次登录会重新自动获取")
                }
            )
        }

        // 高级：门户地址万一变更（或需要指向校内镜像），用户能自己救回来，
        // 不必等出新版本。正常不需要动。
        card {
            row("登录入口（高级）", "默认是教务处门户，正常不用改")
            val portal = EditText(this@SettingsActivity).apply {
                setText(Prefs.portalUrl)
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                textSize = 12f
                setTextColor(color(R.color.text_secondary))
                background = getDrawable(R.drawable.bg_card_alt)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setHorizontallyScrolling(false)
                maxLines = 2
            }
            addPadded(portal)
            buttonRow(
                "保存登录入口" to {
                    Prefs.portalUrl = portal.text.toString()
                    portal.setText(Prefs.portalUrl)
                    toast("已保存")
                },
                "恢复默认" to {
                    Prefs.portalUrl = ""
                    portal.setText(Prefs.portalUrl)
                    toast("已恢复默认登录入口")
                }
            )
        }
    }

    private fun buildAccount() {
        val status = TimetableRepository.status
        val state = when {
            Prefs.loginExpired -> "登录已过期"
            status.ok -> "正常"
            else -> "未同步"
        }
        sectionTitle("登录与同步")
        card {
            row("状态", "$state　·　${TimetableRepository.result().sessions.size} 段课")
            row("最近一次", status.message.ifBlank { "尚无记录" })
            row("HTTP", "${status.httpCode}　${status.finalUrl.take(52)}")
            buttonRow(
                "登录并导入课表" to {
                    startActivity(Intent(this@SettingsActivity, LoginActivity::class.java))
                },
                "立即同步" to {
                    toast("开始同步…")
                    TimetableRepository.refresh(this@SettingsActivity) { st -> toast(st.message) }
                }
            )
        }
    }

    private fun buildWeek(today: LocalDate, w1: LocalDate?, realWeek: Int) {
        sectionTitle("周次")
        card {
            row(
                "现在第几周",
                "教务系统页面里没有当前周次，需要你校正一次"
            )
            // 步进器
            val value = TextView(this@SettingsActivity).apply {
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.text_primary))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val derived = TextView(this@SettingsActivity).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.text_tertiary))
            }
            fun refresh() {
                value.text = "$draftWeek"
                val m = WeekCalc.week1Monday(today, draftWeek)
                derived.text = "第 1 周周一 = ${m.format(DATE)}"
            }
            refresh()

            val stepper = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(8))
            }
            stepper.addView(
                textButton("-") { draftWeek = (draftWeek - 1).coerceAtLeast(1); refresh() },
                LinearLayout.LayoutParams(dp(56), WRAP, 0f)
            )
            stepper.addView(value, LinearLayout.LayoutParams(0, WRAP, 1f))
            stepper.addView(
                textButton("+") { draftWeek = (draftWeek + 1).coerceAtMost(30); refresh() },
                LinearLayout.LayoutParams(dp(56), WRAP, 0f)
            )
            addView(stepper)
            addPadded(derived)

            row("当前设置", w1?.let { "第 1 周周一 = ${it.format(DATE)}" } ?: "尚未设置")
            buttonRow(
                "应用" to {
                    Prefs.setWeek1FromCurrentWeek(today, draftWeek)
                    Prefs.manualWeek = 0
                    TimetableRepository.notifyDataChanged(this@SettingsActivity)
                    toast("已设为 ${Prefs.week1Monday}")
                    build()
                },
                "跟随今天" to {
                    Prefs.manualWeek = 0
                    TimetableRepository.notifyDataChanged(this@SettingsActivity)
                    toast("已切回自动跟随本周")
                }
            )
        }
    }

    private fun buildReminder() {
        sectionTitle("上课提醒")
        card {
            row(
                "开启提醒",
                "按下面设置的提前量推送通知",
                Switch(this@SettingsActivity).apply {
                    isChecked = Prefs.reminderEnabled
                    setOnCheckedChangeListener { _, checked ->
                        Prefs.reminderEnabled = checked
                        TimetableRepository.notifyDataChanged(this@SettingsActivity)
                    }
                }
            )
            var minutes = Prefs.reminderMinutes
            val value = TextView(this@SettingsActivity).apply {
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.text_primary))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            fun refresh() {
                value.text = "$minutes 分"
            }
            refresh()

            val stepper = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            stepper.addView(
                textButton("-5") {
                    minutes = (minutes - 5).coerceAtLeast(0)
                    Prefs.reminderMinutes = minutes
                    refresh()
                    TimetableRepository.notifyDataChanged(this@SettingsActivity)
                },
                LinearLayout.LayoutParams(dp(72), WRAP, 0f)
            )
            stepper.addView(value, LinearLayout.LayoutParams(0, WRAP, 1f))
            stepper.addView(
                textButton("+5") {
                    minutes = (minutes + 5).coerceAtMost(120)
                    Prefs.reminderMinutes = minutes
                    refresh()
                    TimetableRepository.notifyDataChanged(this@SettingsActivity)
                },
                LinearLayout.LayoutParams(dp(72), WRAP, 0f)
            )
            addView(stepper)

            if (!Notifications.canPost(this@SettingsActivity)) {
                row("通知权限未开启", "点这里去系统设置里允许通知") {
                    Notifications.openNotificationSettings(this@SettingsActivity)
                }
            }
        }
    }

    private fun buildAppearance() {
        sectionTitle("外观")
        card {
            row("主题", "深浅色与课程配色")
            val group = RadioGroup(this@SettingsActivity).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(8))
            }
            listOf("跟随系统", "浅色", "深色").forEachIndexed { index, text ->
                group.addView(
                    RadioButton(this@SettingsActivity).apply {
                        this.text = text
                        textSize = 15f
                        setTextColor(color(R.color.text_primary))
                        id = index + 100
                        setPadding(dp(8), dp(10), 0, dp(10))
                    }
                )
            }
            group.check(Prefs.darkMode + 100)
            group.setOnCheckedChangeListener { _, checkedId ->
                Prefs.darkMode = checkedId - 100
                recreate()
            }
            addView(group)
            row("课程配色", "同一门课始终同色，可整体换一套") {
                Prefs.colorSeed += 1
                TimetableRepository.notifyDataChanged(this@SettingsActivity)
                toast("已换配色")
            }
        }

        // ---------------- 课表风格：6 种 ----------------
        sectionTitle("课表风格")
        card {
            row("卡片样式", "点一下立刻切换，共 ${TimetableRenderer.Style.NAMES.size} 种")
            addView(
                chipGrid(TimetableRenderer.Style.NAMES, Prefs.style) { index ->
                    Prefs.style = index
                    TimetableRepository.notifyDataChanged(this@SettingsActivity)
                    build()
                }
            )
        }

        // ---------------- 周末底色 ----------------
        sectionTitle("周末底色")
        card {
            row("周六周日那一列", "要不要跟工作日区分开")
            addView(
                chipGrid(Prefs.WEEKEND_NAMES, Prefs.weekendMode) { index ->
                    Prefs.weekendMode = index
                    TimetableRepository.notifyDataChanged(this@SettingsActivity)
                    build()
                }
            )
        }

        // ---------------- 背景 ----------------
        sectionTitle("背景")
        card {
            row("背景来源", "默认 / 纯色 / 相册图片")
            addView(
                chipGrid(listOf("默认", "纯色", "图片"), Prefs.bgType) { index ->
                    Prefs.bgType = index
                    Backgrounds.invalidate()
                    TimetableRepository.notifyDataChanged(this@SettingsActivity)
                    build()
                }
            )

            when (Prefs.bgType) {
                1 -> {
                    row("底色", "挑一个")
                    addView(colorSwatches())
                }

                2 -> {
                    row("图片", Prefs.bgUri.ifBlank { "还没选，点这里从相册挑一张" }) {
                        pickPhoto()
                    }
                    row("清除图片", "恢复默认背景") {
                        Prefs.bgUri = ""
                        Prefs.bgType = 0
                        Backgrounds.invalidate()
                        TimetableRepository.notifyDataChanged(this@SettingsActivity)
                        build()
                    }
                    row("文字蒙版", "越大文字越清楚、背景越淡")
                    addView(scrimStepper())
                }
            }
        }

        // ---------------- 桌面小组件 ----------------
        sectionTitle("桌面小组件")
        card {
            row(
                "显示行数",
                if (Prefs.widgetRows == 0) {
                    "自动：按系统给的高度算。放得下几整行就显示几行（不会露半行）"
                } else {
                    "固定 ${Prefs.widgetRows} 行 —— 改完桌面立刻重排"
                }
            )
            addView(
                chipGrid(Prefs.WIDGET_ROWS_NAMES, Prefs.widgetRows) { index ->
                    Prefs.widgetRows = index
                    TodayWidgetProvider.refreshAll(this@SettingsActivity)
                    build()
                }
            )
            row(
                "组件下方留白太多 / 只显示得下一节课",
                "少数系统（如荣耀）回报的组件高度不准，应用只能保守地少排几行。" +
                    "把上面改成「3 行」或「4 行」即可填满 —— 你定几行就显示几行。"
            )
        }
    }

    /** 三列一行的可选项 */
    private fun chipGrid(names: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        var line: LinearLayout? = null
        names.forEachIndexed { index, name ->
            if (index % 3 == 0) {
                line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(3), 0, dp(3))
                }
                wrap.addView(line)
            }
            val picked = index == selected
            val chip = TextView(this).apply {
                text = if (picked) "✓ $name" else name
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(color(if (picked) R.color.accent else R.color.text_secondary))
                background = getDrawable(if (picked) R.drawable.bg_accent_chip else R.drawable.bg_pill)
                isClickable = true
                isFocusable = true
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { onPick(index) }
            }
            line!!.addView(
                chip,
                LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            )
        }
        return wrap
    }

    /** 底色色板：4 浅 4 深 */
    private fun colorSwatches(): LinearLayout {
        val palette = intArrayOf(
            0xFFF5F7FA.toInt(), 0xFFE8F1FC.toInt(), 0xFFE9F7EF.toInt(), 0xFFFFF3E0.toInt(),
            0xFF1E2229.toInt(), 0xFF16283E.toInt(), 0xFF102A22.toInt(), 0xFF2E2413.toInt()
        )
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        var line: LinearLayout? = null
        palette.forEachIndexed { index, value ->
            if (index % 4 == 0) {
                line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(3), 0, dp(3))
                }
                wrap.addView(line)
            }
            val picked = Prefs.bgColor == value
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(value)
                    cornerRadius = dp(10).toFloat()
                    if (picked) setStroke(dp(2), color(R.color.accent))
                }
                isClickable = true
                setOnClickListener {
                    Prefs.bgColor = value
                    Prefs.bgType = 1
                    Backgrounds.invalidate()
                    TimetableRepository.notifyDataChanged(this@SettingsActivity)
                    build()
                }
            }
            line!!.addView(
                swatch,
                LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            )
        }
        return wrap
    }

    private fun scrimStepper(): LinearLayout {
        val value = TextView(this).apply {
            text = "${Prefs.bgScrim}%"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        fun apply(delta: Int) {
            Prefs.bgScrim += delta
            value.text = "${Prefs.bgScrim}%"
            Backgrounds.invalidate()
            TimetableRepository.notifyDataChanged(this)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
            addView(textButton("-10") { apply(-10) }, LinearLayout.LayoutParams(dp(72), WRAP, 0f))
            addView(value, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(textButton("+10") { apply(10) }, LinearLayout.LayoutParams(dp(72), WRAP, 0f))
        }
    }

    private fun pickPhoto() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        runCatching { startActivityForResult(intent, REQ_PICK) }
            .onFailure { toast("打不开相册：${it.message}") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // 持久化权限，否则重启后读不到这张图
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        Prefs.bgUri = uri.toString()
        Prefs.bgType = 2
        Backgrounds.invalidate()
        TimetableRepository.notifyDataChanged(this)
        toast("已设置背景")
        build()
    }

    private fun buildMisc() {
        sectionTitle("其他")
        card {
            row("诊断", "抓取状态、解析结果，可导出原始页面") {
                startActivity(Intent(this@SettingsActivity, DiagnosticsActivity::class.java))
            }
            row("清空本地缓存", "登录态不受影响，下次同步会重新拉取") {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("清空缓存")
                    .setMessage("清空本地缓存的课表数据？登录状态不会受影响。")
                    .setPositiveButton("清空") { _, _ ->
                        Prefs.clearCache()
                        TimetableRepository.reloadFromPrefs(this@SettingsActivity)
                        toast("已清空")
                        build()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            row("关于", "兰大课表 $versionLabel") {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("关于")
                    .setMessage(
                        "兰大课表 $versionLabel\n\n" +
                            "把兰州大学教务系统「学生课表」导入到手机，并在桌面小组件上显示今日课程。\n\n" +
                            "数据只保存在本机；登录凭据由系统 WebView 的 Cookie 保管，" +
                            "App 不上传任何信息。\n\n" +
                            "第 1 周周一：${Prefs.week1Monday.ifBlank { "未设置" }}"
                    )
                    .setPositiveButton("好", null)
                    .show()
            }
        }
    }

    /** 版本号直接读系统里的安装信息，避免像以前那样写死后过期（曾长期显示 v1.9） */
    private val versionLabel: String by lazy {
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "v${info.versionName}"
        }.getOrDefault("")
    }

    // ------------------------------------------------------------- 组件

    private fun sectionTitle(text: String) {
        binding.container.addView(
            TextView(this).apply {
                this.text = text
                textSize = 12f
                letterSpacing = 0.06f
                setTextColor(color(R.color.text_tertiary))
                setPadding(dp(6), dp(20), dp(6), dp(8))
            }
        )
    }

    private fun card(build: LinearLayout.() -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
        }
        c.build()
        binding.container.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP))
    }

    /** 卡片内一行：标题 + 可选副标题 + 可选右侧控件；onClick 时整行可点 */
    private fun LinearLayout.row(
        title: String,
        subtitle: String? = null,
        trailing: View? = null,
        onClick: (() -> Unit)? = null
    ) {
        if (childCount > 0) addView(divider())
        val r = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                background = getDrawable(R.drawable.bg_row_ripple)
                setOnClickListener { onClick() }
            }
        }
        val col = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            TextView(this@SettingsActivity).apply {
                text = title
                textSize = 15f
                setTextColor(color(R.color.text_primary))
            }
        )
        if (!subtitle.isNullOrBlank()) {
            col.addView(
                TextView(this@SettingsActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(color(R.color.text_secondary))
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 3
                }
            )
        }
        r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (trailing != null) r.addView(trailing)
        addView(r)
    }

    /** 在卡片内加一个左右留白的控件（间距必须走 LayoutParams，View 没有 margin） */
    private fun LinearLayout.addPadded(v: View, horizontal: Int = 16, bottom: Int = 6) {
        addView(
            v,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP).apply {
                marginStart = dp(horizontal)
                marginEnd = dp(horizontal)
                bottomMargin = dp(bottom)
            }
        )
    }

    /**
     * 一行胶囊按钮。
     *
     * 刻意收成 vararg 形式：之前写成 `addButtonRow { textButton("应用") {…} }` 的 lambda，
     * textButton 只是「造出 View」而没有 addView，结果按钮全部不显示 ——
     * 用户点不到「应用」，周次就永远设不上。这个签名让「漏 addView」不可能发生。
     */
    private fun LinearLayout.buttonRow(vararg buttons: Pair<String, () -> Unit>) {
        val r = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(12), dp(6), dp(12), dp(12))
        }
        for ((label, action) in buttons) {
            r.addView(
                textButton(label, action),
                LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    marginStart = dp(6)
                    marginEnd = dp(6)
                }
            )
        }
        addView(r)
    }

    private fun textButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(color(R.color.accent))
            background = getDrawable(R.drawable.bg_pill)
            isClickable = true
            isFocusable = true
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { onClick() }
        }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
        setBackgroundColor(color(R.color.hairline))
    }

    private fun dp(v: Int): Int = Ui.dp(this, v.toFloat())

    private fun color(res: Int): Int = getColor(res)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_PICK = 7001
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private val MATCH_PARENT = LinearLayout.LayoutParams.MATCH_PARENT
    }
}
