package app.timetable.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.timetable.R
import app.timetable.data.ParseResult
import app.timetable.data.Parity
import app.timetable.data.Session
import app.timetable.data.TimetableRepository
import app.timetable.data.UserCourses

/**
 * 「增加课程」对话框（设置页的入口调 [show] 就行）：自己加实验课、重修、辅导班、体育选修……
 * ——这些课经常压根不在教务课表里。
 *
 * 几个取舍，写在这里免得以后被"顺手改掉"：
 *
 *  - **为什么放在设置页而不是课表页**：课表页那块地方画的是**教务系统的数据**，
 *    往里塞自己编的课，用户就分不清哪条是学校给的、哪条是自己记的。
 *    设置页里的是"我自己的东西"，语义干净，也不挤占看课表的地方。
 *  - **列表态与表单态合在一个对话框里**：这是"三两下加一门课"的场景，
 *    不是长编辑。多开一个 Activity 要处理返回栈与刷新，收益为零；
 *    改完立刻回列表，还能顺手再改一条。
 *  - **节次让用户填"第几节"，存进去的是行序**：两者不相等（第 5 节的行序是 7，
 *    中间插着「中午1节 / 中午2节」两行）。换算借用 [UserCourses.rowOfSection]，
 *    和「当日调课 → 加一节课」是同一套逻辑，不另发明一套；
 *    表单里还实时回显"课表上＝第几节"，换算错了用户当场就能看见。
 *  - **保存/删除后只调 [TimetableRepository.notifyDataChanged]**：
 *    课表页、桌面小组件、导出图片、上课提醒全都从仓库的 result()/sessionsOn() 取数，
 *    刷新与合并都在那一层收口，这里不需要（也不应该）挨个通知一遍。
 */
internal class CourseEditorDialog(private val activity: Activity) {

    private val ctx: Context get() = activity

    private lateinit var titleView: TextView
    private lateinit var body: LinearLayout

    /** 正在编辑哪一条；null = 新增。列表态与表单态就靠它区分 */
    private var editing: UserCourses.Course? = null

    /** 表单里当前选中的星期 / 单双周。pill 的选中态与保存时的取值都读它 */
    private var pickedDay = 1
    private var pickedParity = Parity.NONE

    // 表单控件引用：点保存时直接从这里读，不去视图树里捞（重建视图后引用会一起换掉）
    private var nameField: EditText? = null
    private var roomField: EditText? = null
    private var teacherField: EditText? = null
    private var startSectionField: EditText? = null
    private var endSectionField: EditText? = null
    private var startWeekField: EditText? = null
    private var endWeekField: EditText? = null
    private var noticeView: TextView? = null

    private val dialog: AlertDialog by lazy {
        titleView = TextView(ctx).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ctx.getColor(R.color.text_primary))
        }
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // 内容可能很长（列表十几条、表单七个字段），套一层竖直滚动 ——
        // 对话框窗口自己有屏幕高度上限，ScrollView 会自然变成"放不下就滚"。
        val scroll = ScrollView(ctx).apply { addView(body) }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            addView(titleView)
            addView(scroll)
        }

        AlertDialog.Builder(activity)
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()
    }

    fun show() {
        // 必须先取一次 dialog：titleView / body 都是在上面那段惰性构建里赋值的，
        // 直接调 renderList() 会撞上 lateinit 未初始化（DayEditDialog 里踩过同样的坑）。
        val d = dialog
        renderList()
        d.show()
    }

    // ===================================================================== 列表态

    private fun renderList() {
        editing = null
        clearFormRefs()
        titleView.text = "我加的课"
        body.removeAllViews()

        val list = UserCourses.sortedForDisplay(UserCourses.all(ctx))
        val result = TimetableRepository.result()

        body.addView(
            hint(
                "教务课表里没有的课（实验课、重修、辅导班、体育选修……）可以加在这里。" +
                    "保存后课表页、桌面小组件、导出图片、上课提醒会一起看到它。"
            )
        )

        body.addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(4))
                addView(
                    TextView(ctx).apply {
                        text = if (list.isEmpty()) "还没有自己加的课" else "共 ${list.size} 门"
                        textSize = 13f
                        setTextColor(ctx.getColor(R.color.text_secondary))
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                    }
                )
                addView(primaryPill("＋ 增加课程") { renderForm(null) })
            }
        )

        if (list.isEmpty()) return
        list.forEach { body.addView(courseCard(it, result)) }
    }

    /** 列表里的一条：课名 + 时间地点 + 周次 + 编辑/删除 */
    private fun courseCard(c: UserCourses.Course, result: ParseResult): View {
        val s = c.session
        val start = result.section(s.startSection)?.start.orEmpty()
        val end = result.section(s.endSection)?.end ?: start
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.getDrawable(R.drawable.bg_card_alt)
            setPadding(dp(12), dp(10), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = dp(6)
                topMargin = dp(6)
            }

            addView(
                TextView(ctx).apply {
                    text = s.name
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ctx.getColor(R.color.text_primary))
                }
            )
            addView(
                TextView(ctx).apply {
                    // 节次用 sectionRangeLabel：它把行序翻译回「第N节」，
                    // 直接印行序（第 5 节 = 行 7）会让用户以为自己填错了
                    text = listOf(
                        Session.dayLabel(s.day),
                        result.sectionRangeLabel(s.startSection, s.endSection),
                        if (start.isNotBlank()) "$start–$end" else "",
                        s.room,
                        s.teacher
                    ).filter { it.isNotBlank() }.joinToString("  ·  ")
                    textSize = 12f
                    setTextColor(ctx.getColor(R.color.text_secondary))
                    setPadding(0, dp(3), 0, 0)
                }
            )
            addView(
                TextView(ctx).apply {
                    text = s.weeks.toString()
                    textSize = 11f
                    setTextColor(ctx.getColor(R.color.text_tertiary))
                    setPadding(0, dp(2), 0, 0)
                }
            )
            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(pill("编辑") { renderForm(c) })
                    addView(pill("删除") { confirmDelete(c) })
                }
            )
        }
    }

    /**
     * 删除前问一句。
     *
     * 不提示的话，这条卡片本身没什么"存在感"，误触点一下就没了，
     * 而它是用户自己敲进去的数据（重敲一遍的成本远高于一次确认框）。
     */
    private fun confirmDelete(c: UserCourses.Course) {
        AlertDialog.Builder(activity)
            .setTitle("删除「${c.session.name}」？")
            .setMessage("删掉之后课表页、小组件、导出图片和上课提醒里都会一起消失。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                UserCourses.remove(ctx, c.id)
                TimetableRepository.notifyDataChanged(ctx)
                toast("已删除：${c.session.name}")
                renderList()
            }
            .show()
    }

    // ===================================================================== 表单态

    /** @param existing null = 新增 */
    private fun renderForm(existing: UserCourses.Course?) {
        editing = existing
        clearFormRefs()
        titleView.text = if (existing == null) "增加课程" else "修改课程"
        body.removeAllViews()

        val result = TimetableRepository.result()
        val s = existing?.session
        val maxSection = UserCourses.maxSectionNo(result.sections)

        pickedDay = s?.day ?: 1
        pickedParity = s?.weeks?.parity ?: Parity.NONE

        val name = field("课名，如：体育选修", s?.name.orEmpty())
        val room = field("教室（可留空）", s?.room.orEmpty())
        val teacher = field("教师（可留空）", s?.teacher.orEmpty())

        // 存下去的是**行序**，表单里问的是"第几节" —— 编辑时必须翻回来。
        // 不翻的话：一条"第 5 节"（行 7）的课再打开会显示成 7，"不改直接保存"就被当成
        // 第 7 节存成行 9，课自己往下挪两行还毫无提示。
        val startNo = s?.let { UserCourses.sectionNoOfRow(result.sections, it.startSection) }
        val endNo = s?.let { UserCourses.sectionNoOfRow(result.sections, it.endSection) }
        if (existing != null && (startNo == null || endNo == null)) {
            // 行序在当前课表里不存在（课表重新导入过、节次表变了）：让用户重选，
            // 而不是拿行序当节号糊上去 —— 那会静默把课挪到别的格子里
            body.addView(notice("这条课原来记的节次对不上当前课表（课表可能重新导入过），请重新选一次节次再保存。"))
        }
        val startSection = numberField("1", startNo?.toString() ?: if (existing == null) "1" else "")
        val endSection = numberField("2", endNo?.toString() ?: if (existing == null) "2" else "")
        // 默认整学期：在学期总周数和 30 之间取小的（30 是填周次的上界）
        val defEnd = result.maxWeek.coerceIn(1, UserCourses.MAX_WEEK)
        val startWeek = numberField("1", (s?.weeks?.start ?: 1).toString())
        val endWeek = numberField("16", (s?.weeks?.end ?: defEnd).toString())

        nameField = name; roomField = room; teacherField = teacher
        startSectionField = startSection; endSectionField = endSection
        startWeekField = startWeek; endWeekField = endWeek

        if (result.sections.isEmpty()) {
            // 节次表来自教务课表页面。没有它，用户填的"第几节"没法落到表格哪一行，
            // 与其存一个猜出来的行序（课会被画到错误的格子、提醒早两小时），不如说清楚。
            body.addView(notice("还没读到课表的节次表，先去「登录导入」同步一次课表再来添加。"))
        }

        val sectionPreview = TextView(ctx).apply {
            textSize = 11f
            setTextColor(ctx.getColor(R.color.text_tertiary))
            setPadding(dp(2), dp(4), 0, dp(6))
        }
        val updatePreview = {
            // 中文是合法标识符字符，"$maxSection节" 会被当成变量名 —— 一律加花括号
            val a = startSection.text.toString().trim()
            val b = endSection.text.toString().trim().ifEmpty { a }
            val na = a.toIntOrNull()
            val nb = b.toIntOrNull()
            sectionPreview.text = when {
                na == null || nb == null -> "节次填数字，例如 3 到 4"
                na < 1 || nb < 1 -> "节次从第 1 节开始"
                na > maxSection || nb > maxSection -> "课表里最多第 ${maxSection} 节"
                else -> {
                    val r1 = UserCourses.rowOfSection(result.sections, minOf(na, nb))
                    val r2 = UserCourses.rowOfSection(result.sections, maxOf(na, nb))
                    if (r1 == null || r2 == null) {
                        "课表里找不到第 ${minOf(na, nb)}–${maxOf(na, nb)} 节对应的行"
                    } else {
                        // 把行序翻译回来给用户看：这一步就是"第 5 节 → 行 7"的现场
                        "课表上＝${result.sectionRangeLabel(r1, r2)}（第 ${r1}–${r2} 行）"
                    }
                }
            }
            val ok = na != null && nb != null && na >= 1 && nb >= 1 &&
                na <= maxSection && nb <= maxSection
            sectionPreview.setTextColor(
                ctx.getColor(if (ok) R.color.text_tertiary else R.color.accent)
            )
        }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = updatePreview()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        }
        startSection.addTextChangedListener(watcher)
        endSection.addTextChangedListener(watcher)
        updatePreview()

        body.addView(label("课名（必填）")); body.addView(name)
        body.addView(label("教室")); body.addView(room)
        body.addView(label("教师")); body.addView(teacher)

        body.addView(label("星期几"))
        body.addView(
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(
                    chips(Session.DAY_LABELS, { pickedDay - 1 }) { pickedDay = it + 1 }
                )
            }
        )

        body.addView(label("节次（第几节到第几节）"))
        body.addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(startSection, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(
                    TextView(ctx).apply {
                        text = "到"
                        textSize = 13f
                        setTextColor(ctx.getColor(R.color.text_secondary))
                        setPadding(dp(8), 0, dp(8), dp(8))
                    }
                )
                addView(endSection, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
        )
        body.addView(sectionPreview)

        body.addView(label("周次（从第几周到第几周）"))
        body.addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(startWeek, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(
                    TextView(ctx).apply {
                        text = "到"
                        textSize = 13f
                        setTextColor(ctx.getColor(R.color.text_secondary))
                        setPadding(dp(8), 0, dp(8), dp(8))
                    }
                )
                addView(endWeek, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
        )

        body.addView(label("单双周"))
        body.addView(
            chips(listOf("全周", "单周", "双周"), { PARITY_ORDER.indexOf(pickedParity) }) {
                pickedParity = PARITY_ORDER[it]
            }
        )

        val notice = TextView(ctx).apply {
            textSize = 12f
            // 用 accent 而不是自造的红色：这套界面里只有这五个颜色资源是稳定的，
            // 深色模式下自造的十六进制值很可能糊在背景里看不见。
            setTextColor(ctx.getColor(R.color.accent))
            setPadding(dp(2), dp(10), dp(2), 0)
            visibility = View.GONE
        }
        noticeView = notice
        body.addView(notice)

        body.addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
                addView(primaryPill(if (existing == null) "保存" else "保存修改") { save() })
                addView(pill("返回列表") { renderList() })
            }
        )
        body.addView(hint("「第几节」按教务课表的节次算；存进去时会自动换算成表格行，不用管。"))
    }

    /** 校验 + 落库 + 刷新。校验文案全部来自 [UserCourses.check] 的纯逻辑（有单测钉着） */
    private fun save() {
        val result = TimetableRepository.result()
        val input = UserCourses.Input(
            name = nameField?.text?.toString().orEmpty(),
            room = roomField?.text?.toString().orEmpty(),
            teacher = teacherField?.text?.toString().orEmpty(),
            day = pickedDay,
            startSection = startSectionField?.text?.toString().orEmpty(),
            endSection = endSectionField?.text?.toString().orEmpty(),
            startWeek = startWeekField?.text?.toString().orEmpty(),
            endWeek = endWeekField?.text?.toString().orEmpty(),
            parity = pickedParity
        )
        val check = UserCourses.check(input, result.sections)
        val session = check.session
        if (!check.ok || session == null) {
            // 错误就地显示（不弹 Toast 就走）：用户要一边看着自己填的值一边改，
            // Toast 三秒就没了，而且挡住表单
            noticeView?.let {
                it.visibility = View.VISIBLE
                it.text = check.errors.ifEmpty { listOf("保存失败：这条课程没通过校验") }.joinToString("\n")
            }
            return
        }

        val course = UserCourses.Course(
            // 新增才生成新 id；编辑沿用原 id，否则"改一次"会变成"多一条"
            id = editing?.id ?: UserCourses.newId(),
            session = session
        )
        UserCourses.save(ctx, course)
        // 这一句就把课表页、小组件、导出图片、提醒全刷新了（合并发生在仓库那一层）
        TimetableRepository.notifyDataChanged(ctx)
        toast(if (editing == null) "已添加：${session.name}" else "已保存：${session.name}")
        renderList()
    }

    // ===================================================================== 控件小工具

    private fun clearFormRefs() {
        nameField = null; roomField = null; teacherField = null
        startSectionField = null; endSectionField = null
        startWeekField = null; endWeekField = null
        noticeView = null
    }

    private fun label(text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(ctx.getColor(R.color.text_secondary))
        setPadding(dp(2), dp(10), 0, dp(4))
    }

    private fun hint(text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 11f
        setTextColor(ctx.getColor(R.color.text_tertiary))
        setPadding(dp(2), dp(6), dp(2), 0)
    }

    private fun notice(text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(ctx.getColor(R.color.accent))
        setPadding(dp(2), dp(8), dp(2), 0)
    }

    private fun field(hintText: String, value: String): EditText =
        EditText(ctx).apply {
            hint = hintText
            setText(value)
            textSize = 14f
            setTextColor(ctx.getColor(R.color.text_primary))
            setHintTextColor(ctx.getColor(R.color.text_tertiary))
            background = ctx.getDrawable(R.drawable.bg_card_alt)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setHorizontallyScrolling(false)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) }
        }

    private fun numberField(hintText: String, value: String): EditText =
        field(hintText, value).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            maxLines = 1
        }

    /**
     * 一排单选 chip。选中用强调色实心（和设置页里的 chip 一致），
     * 未选中用浅底 pill —— 两者的对比度在明暗两种模式下都够。
     */
    private fun chips(
        labels: List<String>,
        current: () -> Int,
        onPick: (Int) -> Unit
    ): LinearLayout {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>(labels.size)
        fun restyle() {
            views.forEachIndexed { i, v ->
                val on = i == current()
                v.background = ctx.getDrawable(if (on) R.drawable.bg_btn_primary else R.drawable.bg_pill)
                v.setTextColor(ctx.getColor(if (on) R.color.page_bg else R.color.text_primary))
            }
        }
        labels.forEachIndexed { i, text ->
            val v = pill(text) { onPick(i); restyle() }
            views += v
            box.addView(v)
        }
        restyle()
        return box
    }

    private fun pill(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(ctx.getColor(R.color.text_primary))
        background = ctx.getDrawable(R.drawable.bg_pill)
        isClickable = true
        isFocusable = true
        setPadding(dp(12), dp(9), dp(12), dp(9))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            marginStart = dp(3); marginEnd = dp(3); topMargin = dp(4)
        }
        setOnClickListener { onClick() }
    }

    private fun primaryPill(text: String, onClick: () -> Unit): TextView = pill(text).apply {
        setTextColor(ctx.getColor(R.color.page_bg))
        background = ctx.getDrawable(R.drawable.bg_btn_primary)
        setOnClickListener { onClick() }
    }

    private fun pill(text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(ctx.getColor(R.color.text_primary))
        background = ctx.getDrawable(R.drawable.bg_pill)
        isClickable = true
        isFocusable = true
        setPadding(dp(12), dp(9), dp(12), dp(9))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            marginStart = dp(3); marginEnd = dp(3); topMargin = dp(4)
        }
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = Ui.dp(ctx, v.toFloat())

    companion object {
        /** 设置页一行接上的入口：`CourseEditorDialog.show(this)` */
        fun show(activity: Activity) {
            CourseEditorDialog(activity).show()
        }

        /** 单双周 pill 的顺序，下标即选项 —— 三个值的小表，不搞枚举遍历 */
        private val PARITY_ORDER = listOf(Parity.NONE, Parity.ODD, Parity.EVEN)

        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
