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
import app.timetable.data.Session
import app.timetable.data.TimetableRepository
import app.timetable.data.WeekCalc
import app.timetable.databinding.ActivitySettingsBinding
import app.timetable.donate.DonateDialog
import app.timetable.donate.Donations
import app.timetable.greet.Holidays
import app.timetable.greet.LunarCalendar
import app.timetable.notify.Notifications
import app.timetable.ui.Backgrounds
import app.timetable.ui.BaseActivity
import app.timetable.ui.CourseEditorDialog
import app.timetable.ui.PhotoCropDialog
import app.timetable.ui.StylePreviewView
import app.timetable.ui.TimetableRenderer
import app.timetable.ui.Ui
import app.timetable.ui.YearCalendarView
import app.timetable.widget.TodayWidgetProvider
import app.timetable.widget.WidgetData
import app.timetable.widget.WidgetPhotos
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /**
     * 「现在第几周」步进器的草稿值。
     *
     * `draftInit` 是必须的：页面里任何一个开关/chip 都会走 build() 重建整页，
     * 如果每次都无条件把草稿重置成真实周次，用户把 3 调到 9 之后顺手点一下同页的开关，
     * 步进器就悄悄回到 3，再点「应用」设的还是 3（改动被吃掉）。
     */
    private var draftWeek = 1
    private var draftInit = false

    /** 余量判断三个选项与它们的取值（顺序和取值故意不一致：自动在中间才是符合直觉的排法） */
    private val OVERRIDE_LABELS = listOf("自动", "强制显示", "强制隐藏")
    private val OVERRIDE_VALUES = listOf(0, 1, -1)

    /** 课表风格预览的引用：改「周末底色」时也要重画它（它读的是 Prefs，但不会自己 invalidate） */
    private var stylePreview: StylePreviewView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        build()
    }

    /**
     * 监听仓库变化：设置页里的「立即同步」也是同步入口，同步完那三行状态
     * （状态 / 最近一次 / HTTP）必须跟着变，否则用户会以为同步没生效。
     *
     * 但**输入框有焦点时不要重建页面** —— 用户正在手填课表链接，
     * 后台同步刚好回来重建一次，他打的字就被清掉了。
     */
    private val repoListener: () -> Unit = {
        if (currentFocus !is EditText) build()
    }

    override fun onStart() {
        super.onStart()
        TimetableRepository.addListener(repoListener)
    }

    override fun onStop() {
        TimetableRepository.removeListener(repoListener)
        super.onStop()
    }

    // ------------------------------------------------------------- 构建

    private fun build() {
        binding.container.removeAllViews()

        val today = LocalDate.now()
        val w1 = Prefs.week1MondayDate()
        val realWeek = if (w1 != null) WeekCalc.weekOf(today, w1) else 1
        // 只在进页面时取一次真实周次当草稿，之后重建页面不要覆盖用户正在调的值
        if (!draftInit) {
            draftWeek = realWeek.coerceIn(1, 30)
            draftInit = true
        }

        buildSource()
        buildMyCourses()
        buildAccount()
        buildWeek(today, w1, realWeek)
        buildReminder()
        buildAppearance()
        buildGreeting()
        buildMisc()
        buildSupport()
    }

    /**
     * 「我的课程」：自己加教务系统里没有的课（实验课、重修、辅导班……）。
     *
     * 放在「课表来源」下面，因为它回答的是同一个问题："我的课表里都有什么"。
     * 加进去的课和抓回来的课走同一条数据出口（TimetableRepository.result()），
     * 所以课表页、桌面小组件、导出图片、上课提醒四处都会认。
     */
    private fun buildMyCourses() {
        sectionTitle("我的课程")
        card {
            row(
                "增加课程",
                "教务系统里没有的课可以自己加：选星期几、第几节到第几节、" +
                    "第几周到第几周（单周/双周/每周都行）。加完之后小组件和上课提醒也认。"
            ) { CourseEditorDialog.show(this@SettingsActivity) }
        }
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
                        // Android 13+ 的 POST_NOTIFICATIONS 是运行时权限，不申请的话
                        // 闹钟按时触发、通知却会被系统直接丢掉（而且失败是不抛异常的）。
                        // 在用户**主动打开提醒**的这一刻申请，是这个权限最合理的时机。
                        requestNotificationPermissionIfNeeded()
                        build()
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
            // Android 14+ 起精确闹钟权限默认不预授予，拿不到就只能退化成"大约在那个时候"，
            // 最坏会晚上 10 分钟（上课之后才响）。这里给一个明确的开启入口，别让用户干等。
            row("提醒时间不准？", "系统允许「闹钟和提醒」精确到分钟后，通知才会准点") {
                openExactAlarmSettings()
            }
        }
    }

    // ------------------------------------------------- 权限

    private val REQ_NOTIF = 0x9B01

    /**
     * 申请动态通知权限（Android 13+）。
     *
     * 为什么不做成"一进设置页就申请"：用户在还没决定要不要提醒时被弹权限框，
     * 大概率直接拒绝，之后系统不再允许弹第二次 —— 那就彻底收不到提醒了。
     * 在开关被打开的那一刻申请，意图最清楚。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIF) return
        build()
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("已允许通知，提醒会准时到")
        } else {
            toast("没有通知权限，提醒到了也弹不出来 —— 可以点上面的「通知权限未开启」去开")
        }
    }

    /** 跳到"闹钟和提醒"权限页（有的系统没有这一页，就退回到本应用的设置页） */
    private fun openExactAlarmSettings() {
        val am = getSystemService(android.app.AlarmManager::class.java)
        val exactOk = am == null || am.canScheduleExactAlarms()
        if (exactOk) {
            toast("当前已经允许精确提醒")
            return
        }
        val ok = runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
            )
        }.isSuccess
        if (!ok) {
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", packageName, null)
                    }
                )
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
                // 小组件的明暗是自己读 Prefs 画的，不刷新的话会一直停在旧配色上
                TodayWidgetProvider.refreshAll(this@SettingsActivity)
                // 只重建本页只能让自己变深；主页在 onResume 里会自己检测到变化并重建
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
            // 样例预览：把样张小图直接画在这儿，用户不用进课表页就能比较 6 种风格的差别。
            // 高度 = (表头 48dp + 4 行 × 62dp) × 0.78 ≈ 230dp，另加一点内边距。
            // 底色用浅灰圆角卡片，缩略图才有"样片"的样子（白底白线会显得很空、很丑）。
            val preview = StylePreviewView(this@SettingsActivity).apply {
                style = Prefs.style
                background = getDrawable(R.drawable.bg_card_alt)
            }
            // 留一个引用：下面的「周末底色」改了之后也要重画它，
            // 否则预览里周末列还是旧的淡底色，用户会以为开关没生效
            stylePreview = preview
            addPadded(preview, heightDp = StylePreviewView.HEIGHT_DP, bottom = 10)
            // 点选风格只更新"选中态 + 预览"两处，不重建整页 —— 重建会让设置页闪一下，
            // 而挑风格本来就是连续点好几下的动作（丝滑的关键就在这儿）。
            val chips = chipGridLive(TimetableRenderer.Style.NAMES, Prefs.style) { index ->
                Prefs.style = index
                preview.style = index
                // 极轻的淡入：让"换了风格"这件事被眼睛确认到，又不至于让连续点击变得拖沓
                Ui.enter(preview, fromDp = 0f, duration = 140)
                TimetableRepository.notifyDataChanged(this@SettingsActivity)
            }
            addView(chips.view)
        }

        // ---------------- 周末底色 ----------------
        sectionTitle("周末底色")
        card {
            row("周六周日那一列", "要不要跟工作日区分开")
            val chips = chipGridLive(Prefs.WEEKEND_NAMES, Prefs.weekendMode) { index ->
                Prefs.weekendMode = index
                // 上面那张风格样片也画着周末列，它虽然读的是 Prefs 但不会自己重画
                stylePreview?.invalidate()
                TimetableRepository.notifyDataChanged(this@SettingsActivity)
            }
            addView(chips.view)
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

            // ---- 每日一句（默认关闭）----
            row(
                "每日一句",
                "在课程列表下方的空白处显示一句话（内置、离线，不联网）",
                Switch(this@SettingsActivity).apply {
                    isChecked = Prefs.quoteEnabled
                    setOnCheckedChangeListener { _, checked ->
                        Prefs.quoteEnabled = checked
                        TodayWidgetProvider.refreshAll(this@SettingsActivity)
                        build()
                    }
                }
            )

            // ---- 图片框（默认关闭）----
            row(
                "图片",
                "在小组件底部显示你选的图片（最多 ${WidgetData.MAX_PHOTOS} 张）",
                Switch(this@SettingsActivity).apply {
                    isChecked = Prefs.photoEnabled
                    setOnCheckedChangeListener { _, checked ->
                        Prefs.photoEnabled = checked
                        TodayWidgetProvider.refreshAll(this@SettingsActivity)
                        build()
                    }
                }
            )
            if (Prefs.photoEnabled) {
                val count = WidgetData.photoList(this@SettingsActivity).count { java.io.File(it).exists() }
                row(
                    "选择图片（$count/${WidgetData.MAX_PHOTOS}）",
                    "从相册挑；会压缩到 1600px 并复制进 App，卸载即清"
                ) { pickPhotos() }
                if (count > 0) {
                    row("清除图片", "从小组件里移除全部图片") {
                        WidgetPhotos.clear(this@SettingsActivity)
                        TodayWidgetProvider.refreshAll(this@SettingsActivity)
                        build()
                    }
                }
                row(
                    "自动换图",
                    if (count <= 1) {
                        "只有 1 张图片时不用自动换 —— 再选几张才会转起来"
                    } else {
                        "不开也会自动换：在小组件的图片上**点一下**就换下一张。" +
                            "打开这个开关只是让它按间隔自己换（更耗电，桌面组件不是动图）"
                    },
                    Switch(this@SettingsActivity).apply {
                        isEnabled = count > 1
                        isChecked = Prefs.photoFlipEnabled && count > 1
                        setOnCheckedChangeListener { _, checked ->
                            Prefs.photoFlipEnabled = checked
                            TodayWidgetProvider.refreshAll(this@SettingsActivity)
                            build()
                        }
                    }
                )
                if (Prefs.photoFlipEnabled) {
                    addView(
                        chipGrid(
                            Prefs.photoFlipChoices.map { "$it 秒" },
                            // 存量值可能不在选项列表里（老版本写进 prefs 的），
                            // indexOf 会返回 -1；直接 coerceAtLeast(0) 会"高亮成 5 秒"，
                            // 而实际仍按老值轮播 —— 那就对不上了，所以先落到默认值上再取下标
                            Prefs.photoFlipChoices
                                .indexOf(Prefs.photoFlipSeconds)
                                .takeIf { it >= 0 } ?: 0
                        ) { index ->
                            Prefs.photoFlipSeconds = Prefs.photoFlipChoices[index]
                            TodayWidgetProvider.refreshAll(this@SettingsActivity)
                            build()
                        }
                    )
                }
            }

            // 说明：这里曾经有「显示方式（填满/完整）」「裁哪一段」「放大倍数」三组开关。
            // 用户明确不要倍率这种东西 —— 他要的是**导入时自己裁**（见 PhotoCropDialog），
            // 之后"空间够就完整显示、不够就显示他裁的那块"由程序自动决定，
            // 不需要用户再去理解"倍率/裁剪位置"这些参数。所以那三个键彻底不用了。

            // ---- 余量判断（少数机型上报高度不准时的兜底）----
            if (Prefs.quoteEnabled || Prefs.photoEnabled) {
                row(
                    "下方空白判断",
                    "自动 = 按系统给的高度算余量；显示不出来时改成「强制显示」"
                )
                // 取值不单调（自动=0、强制显示=+1、强制隐藏=-1），所以**不能**用 index±1 换算：
                // 那样三个选项会整体错位一格（默认高亮成「强制显示」，点「自动」反而强制隐藏）。
                addView(
                    chipGrid(
                        OVERRIDE_LABELS,
                        OVERRIDE_VALUES.indexOf(Prefs.widgetExtrasOverride).coerceAtLeast(0)
                    ) { index ->
                        Prefs.widgetExtrasOverride = OVERRIDE_VALUES[index]
                        TodayWidgetProvider.refreshAll(this@SettingsActivity)
                        build()
                    }
                )
            }
        }
    }

    // ------------------------------------------------- 节日祝福与全年日历

    /**
     * 节日祝福 + 全年日历。
     *
     * 设计取舍：
     *  - **总开关默认关**。这功能是"锦上添花"，默认弹窗会打扰大多数只想看课表的同学；
     *  - 打开后**公历默认开、农历和校历默认关** —— 公历节日人人都认，
     *    农历和校历节点属于"你说了才给你看"的信息；
     *  - 全年日历跟着总开关出现（关着时不占屏幕高度），日历里的小圆点按类别上色，
     *    和上面的三个开关一一对应，用户能立刻看出自己关掉了哪一类。
     */
    private fun buildGreeting() {
        sectionTitle("节日祝福")

        // 局部扩展函数：卡片内部就是 LinearLayout；注意 row() 自己会把行加进卡片，不要再包 addView
        fun LinearLayout.switchRow(
            title: String,
            subtitle: String,
            value: Boolean,
            set: (Boolean) -> Unit
        ) {
            row(
                title,
                subtitle,
                Switch(this@SettingsActivity).apply {
                    isChecked = value
                    setOnCheckedChangeListener { _, checked ->
                        set(checked)
                        build()
                    }
                }
            )
        }

        card {
            switchRow(
                "节日祝福",
                "节日当天打开 App 时弹一句祝福（内置，不联网）",
                Prefs.greetEnabled
            ) { Prefs.greetEnabled = it }

            if (!Prefs.greetEnabled) {
                row("全年日历", "打开上面的开关后，这里会显示一整年的节日日历")
                return@card
            }

            switchRow("公历节日", "元旦、劳动节、国庆节等", Prefs.greetSolar) { Prefs.greetSolar = it }
            switchRow(
                "农历节日",
                "春节、元宵、端午、七夕、中秋、重阳、腊八等",
                Prefs.greetLunar
            ) { Prefs.greetLunar = it }
            switchRow(
                "校历节点",
                "开学、期中期末、寒暑假等（参考节点，非官方校历）",
                Prefs.greetSchool
            ) { Prefs.greetSchool = it }

            row(
                "全年日历",
                "每个格子里直接写着那天是什么节（同一天有多个节时写最主要的一个，圆点表示还涉及哪几类）。" +
                    "点某天可以看到当天的全部节日。"
            )
            addView(
                YearCalendarView(this@SettingsActivity).apply {
                    setGreetFlags(Prefs.greetSolar, Prefs.greetLunar, Prefs.greetSchool)
                    onDayTap = { date -> showDayHolidays(date) }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = dp(16); marginEnd = dp(16)
                        topMargin = dp(6); bottomMargin = dp(14)
                    }
                }
            )
        }
    }

    /** 点日历上的某天：列出当天的节日（只看已启用的类别） */
    private fun showDayHolidays(date: LocalDate) {
        val hits = Holidays.of(date).filter { kind ->
            when (kind.kind) {
                Holidays.Kind.SOLAR -> Prefs.greetSolar
                Holidays.Kind.LUNAR -> Prefs.greetLunar
                Holidays.Kind.SCHOOL -> Prefs.greetSchool
            }
        }
        val head = "${date.monthValue} 月 ${date.dayOfMonth} 日 · " +
            Session.dayLabel(date.dayOfWeek.value)
        val lunar = LunarCalendar.label(date)
        val body = if (hits.isEmpty()) {
            "$head\n$lunar\n\n这天没有节日。"
        } else {
            "$head\n$lunar\n\n" + hits.joinToString("\n\n") { "${it.name}\n${it.greeting}" }
        }
        AlertDialog.Builder(this)
            .setTitle("这一天")
            .setMessage(body)
            .setPositiveButton("好", null)
            .show()
    }

    // ------------------------------------------------- 小组件图片选择

    private val REQ_PHOTOS = 0x9A01

    private fun pickPhotos() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        runCatching { startActivityForResult(intent, REQ_PHOTOS) }
            .onFailure { toast("这台设备没有可用的相册选择器") }
    }


    /** 三列一行的可选项 */
    private fun chipGrid(names: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout =
        chipGridLive(names, selected, onPick).view

    /**
     * 不重建页面就能改选中态的 chip 网格。
     *
     * 为什么需要：[chipGrid] 把"选中态"烘进了每个 chip 的文案与背景里，所以传统做法是
     * 「点一下 → 改设置 → 全页 build()」。挑风格、切周末底色这类动作用户会**连续点好几下**，
     * 每点一次整页重建会让设置页反复闪、滚动位置也可能跳 —— 这正是"不丝滑"的来源。
     * 这里返回一个 refresh：调用方自己决定是"只更新选中态"还是"顺便重建别的区域"。
     */
    private class ChipGrid(val view: LinearLayout, val refresh: (Int) -> Unit)

    private fun chipGridLive(
        names: List<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ): ChipGrid {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        val chips = ArrayList<TextView>(names.size)

        // 选中态：✓ 前缀 + 强调色背景。点一下自己就把选中态画好（不需要调用方重建页面）
        fun paint(picked: Int) {
            chips.forEachIndexed { index, chip ->
                val on = index == picked
                chip.text = if (on) "✓ ${names[index]}" else names[index]
                chip.setTextColor(color(if (on) R.color.accent else R.color.text_secondary))
                chip.background = getDrawable(if (on) R.drawable.bg_accent_chip else R.drawable.bg_pill)
            }
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
            val chip = TextView(this).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener {
                    paint(index)
                    onPick(index)
                }
            }
            chips += chip
            line!!.addView(
                chip,
                LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            )
        }
        paint(selected)
        return ChipGrid(wrap) { paint(it) }
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

        // 小组件图片（可多选）
        if (requestCode == REQ_PHOTOS) {
            if (resultCode != RESULT_OK || data == null) return
            val uris = ArrayList<android.net.Uri>()
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            }
            data.data?.let { if (uris.isEmpty()) uris.add(it) }
            if (uris.isEmpty()) return

            // 不再"选完直接存"：先让用户自己裁 —— 裁剪框的形状就是小组件里图片区**真实的形状**
            // （按组件几乘几算出来的），所以他框住的那一块，就是组件里会显示的那一块。
            // 空间够时整张完整显示，不够时取这块的中间部分（都由渲染端自动决定，没有倍率可调）。
            PhotoCropDialog.start(this, uris) { saved ->
                toast(if (saved.isEmpty()) "没有导入图片" else "已导入 ${saved.size} 张图片")
                TodayWidgetProvider.refreshAll(this)
                build()
            }
            return
        }

        // 课表背景图
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
            row(
                "界面动效",
                "切周、切换课表风格时的淡入过渡。关掉后所有切换瞬间完成",
                Switch(this@SettingsActivity).apply {
                    isChecked = Prefs.animEnabled
                    setOnCheckedChangeListener { _, checked ->
                        Prefs.animEnabled = checked
                        // 立刻给个反馈，用户能马上看到开/关的差别
                        if (checked) {
                            binding.container.alpha = 0f
                            Ui.enter(binding.container, fromDp = 0f, duration = 160)
                        }
                    }
                }
            )
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
    private fun LinearLayout.addPadded(
        v: View,
        horizontal: Int = 16,
        bottom: Int = 6,
        /** > 0 时给固定高度（dp）；0 = wrap_content */
        heightDp: Int = 0
    ) {
        addView(
            v,
            LinearLayout.LayoutParams(MATCH_PARENT, if (heightDp > 0) dp(heightDp) else WRAP).apply {
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
