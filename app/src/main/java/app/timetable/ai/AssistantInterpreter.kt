package app.timetable.ai

import android.content.Context
import app.timetable.data.DayOverrides
import app.timetable.data.ParseResult
import app.timetable.data.Prefs
import app.timetable.data.Section
import app.timetable.data.Session
import app.timetable.data.TimetableRepository
import app.timetable.data.UserCourses
import app.timetable.data.WeekCalc
import java.time.LocalDate

/**
 * 把助手的操作翻译成**真正要落盘的东西**，并算出"这一天改完长什么样"。
 *
 * ## 顺序敏感
 *
 * 同一天可以有多条操作，而且顺序有意义："先把周四换成周三的课，再把第 3 节删掉" —— 第二条
 * 是在第一条**的结果**上做的。所以这里是"按日期分组 → 按出现顺序归约 → 得到最终状态"，
 * 预览显示的也是**最终状态**（而不是操作清单）：用户要判断的是"我那天到底上什么课"。
 *
 * ## 每一条都与手动路径对齐（不是"差不多的另一套"）
 *
 *  - `ADD` 遇到当天已有「替换/停课/整份列表」时**拒绝**，文案与 `ui/DayEditDialog.addOne()`
 *    一字不差 —— 手动路径就是这么拦的，助手如果偷偷改成别的行为，用户会遇到"同一个操作
 *    两个说法"；
 *  - `DROP`/`EDIT` 物化成 [DayOverrides.Override.list]（"这一天的课表就是这几段"），
 *    与 `ui/SessionEditDialog` 完全一致；空列表也照样写（"把最后一节删了"是合法状态）；
 *  - 节次一律经 [UserCourses.rowOfSection] 换算成行序后才存。
 *
 * ## 为什么要 [Env] 这一层
 *
 * 这些决策全都依赖"当前课表/当前覆盖/我自己的课"，而它们都要 Context。把读数据抽成 [Env]，
 * 解释逻辑就能在单测里跑真的分支（而不是靠"Android API 返回默认值"的假象）。
 */
internal object AssistantInterpreter {

    /** 读数据的抽象。生产实现见 [ProductionEnv]，单测给假的 */
    interface Env {
        val today: LocalDate
        val week1Monday: LocalDate?

        /** 课表的节次表（行序 ↔ 节次标签） */
        val sections: List<Section>

        fun result(): ParseResult

        /** 某个日期**当前生效**的课表（已应用当日覆盖） */
        fun dayList(date: LocalDate): List<Session>

        fun existingOverride(date: LocalDate): DayOverrides.Override?

        /** 用户自己加的课（整学期） */
        fun courses(): List<UserCourses.Course>
    }

    /** 一天改完之后的最终状态 */
    data class DayChange(
        val date: LocalDate,
        val before: List<Session>,
        val after: List<Session>,
        /** null = 删掉这一天的覆盖（恢复原课表） */
        val override: DayOverrides.Override?,
        /** 逐条中文说明（"换成第 8 周周三的课"），预览里显示 */
        val notes: List<String>
    ) {
        val changed: Boolean get() = before != after || override != null
    }

    /** 自定义课程的增删 */
    data class CourseChange(
        val add: UserCourses.Course? = null,
        val deleteId: String? = null,
        val name: String,
        val note: String
    )

    data class Outcome(
        val days: List<DayChange>,
        val courses: List<CourseChange>,
        val errors: List<String>
    ) {
        val ok: Boolean get() = errors.isEmpty()
        val changed: Boolean get() = days.isNotEmpty() || courses.isNotEmpty()
    }

    /** 单次最多影响多少天（操作数上限 30 已经卡了一道，这里是第二道） */
    const val MAX_DAYS = 90

    fun interpret(ops: List<AssistantOp>, env: Env): Outcome {
        val errors = ArrayList<String>()
        val days = ArrayList<DayChange>()
        val courses = ArrayList<CourseChange>()

        val sections = env.sections
        if (sections.isEmpty()) {
            // 没课表就没法把"第几节"换成行序，也就没法安全地改
            return Outcome(emptyList(), emptyList(), listOf("还没有课表：请先登录并同步一次课表"))
        }

        // 按日期分组，但保留"第一次出现"的顺序（预览里的顺序要稳定）
        val byDate = LinkedHashMap<LocalDate, MutableList<AssistantOp.Dated>>()
        for (op in ops) {
            if (op is AssistantOp.Dated) {
                byDate.getOrPut(op.date) { ArrayList() } += op
            }
        }
        if (byDate.size > MAX_DAYS) {
            errors += "一次影响的日期太多（${byDate.size} 天）"
        }

        for ((date, dateOps) in byDate) {
            reduceDay(date, dateOps, env, errors)?.let { days += it }
        }

        for (op in ops) {
            when (op) {
                is AssistantOp.CourseAdd -> reduceCourseAdd(op, env, errors)?.let { courses += it }
                is AssistantOp.CourseDelete -> reduceCourseDelete(op, env, errors)?.let { courses += it }
                else -> Unit
            }
        }

        return Outcome(
            days = if (errors.isEmpty()) days else emptyList(),
            courses = if (errors.isEmpty()) courses else emptyList(),
            errors = errors
        )
    }

    // ------------------------------------------------------------ 一天的归约

    private fun reduceDay(
        date: LocalDate,
        ops: List<AssistantOp.Dated>,
        env: Env,
        errors: MutableList<String>
    ): DayChange? {
        val label = dayLabel(date)
        val w1 = env.week1Monday
        if (w1 == null) {
            errors += "$label：还不知道现在第几周，请先同步一次课表"
            return null
        }
        val week = WeekCalc.weekOf(date, w1)
        val day = WeekCalc.dayOf(date)
        if (week < 1) {
            errors += "$label：这一天不在本学期范围内"
            return null
        }
        if (date.isBefore(env.today)) {
            errors += "$label：过去的日期不用再调课了"
            return null
        }

        val result = env.result()
        val sections = env.sections
        val before = env.dayList(date)
        // 本来（没有任何覆盖）那一版：恢复原课表、以及 ADD 的合并基准都要用它
        val base = WeekCalc.merge(WeekCalc.sessionsFor(result, day, week))

        var override: DayOverrides.Override? = env.existingOverride(date)
        var working: List<Session> = before
        val notes = ArrayList<String>()

        for (op in ops) {
            when (op) {
                is AssistantOp.Replace -> {
                    val srcDay = op.day ?: day
                    val srcWeek = op.week ?: week
                    if (srcWeek !in 1..result.maxWeek) {
                        errors += "$label：第 $srcWeek 周不在本学期（1-${result.maxWeek}）"
                        return null
                    }
                    if (srcDay == day && srcWeek == week) {
                        // 与手动路径一致：这就是"当天本身"，不是一次调课
                        notes += "本来就是${Session.dayLabel(day)}的课，这次不动"
                        continue
                    }
                    override = DayOverrides.Override(
                        DayOverrides.Mode.REPLACE,
                        sourceWeek = srcWeek,
                        sourceDay = if (srcDay == day) 0 else srcDay
                    )
                    working = DayOverrides.replaceSource(result, override, day)
                    notes += if (srcDay == day) {
                        "换成第 $srcWeek 周的课"
                    } else {
                        "换成第 $srcWeek 周${Session.dayLabel(srcDay)}的课"
                    }
                }

                is AssistantOp.Clear -> {
                    override = DayOverrides.Override(DayOverrides.Mode.CLEAR)
                    working = emptyList()
                    notes += "当天停课"
                }

                is AssistantOp.Reset -> {
                    override = null
                    working = base
                    notes += "恢复原课表"
                }

                is AssistantOp.Add -> {
                    // 与 DayEditDialog.addOne() 一字不差：手动路径就是这么拦的
                    if (override != null && override.mode != DayOverrides.Mode.ADD) {
                        errors += "$label：当天已调整为「替换/停课」，请先点『恢复原课表』再加课"
                        return null
                    }
                    val rows = sectionToRows(sections, op.start, op.end)
                    if (rows == null) {
                        errors += "$label：第 ${op.start}-${op.end} 节不存在"
                        return null
                    }
                    val added = Session(
                        name = op.name,
                        room = op.room,
                        teacher = op.teacher,
                        day = day,
                        startSection = rows.first,
                        endSection = rows.second
                    )
                    val extra = (override?.extra ?: emptyList()) + added
                    override = DayOverrides.Override(DayOverrides.Mode.ADD, extra = extra)
                    working = DayOverrides.mergeSorted(base, extra, day)
                    notes += "加一节课：${op.name}（${sectionLabel(result, rows.first, rows.second)}）"
                }

                is AssistantOp.Drop -> {
                    val hit = matchOne(working, op.section, op.name, label, sections, errors)
                        ?: return null
                    val next = working.filterNot { it === hit }
                    override = DayOverrides.Override.list(next)
                    working = next
                    notes += "删掉：${hit.name}（${sectionLabel(result, hit.startSection, hit.endSection)}）"
                }

                is AssistantOp.Edit -> {
                    val hit = matchOne(working, op.section, op.name, label, sections, errors)
                        ?: return null
                    val newStart: Int
                    val newEnd: Int
                    if (op.setStart != null && op.setEnd != null) {
                        val rows = sectionToRows(sections, op.setStart, op.setEnd)
                        if (rows == null) {
                            errors += "$label：第 ${op.setStart}-${op.setEnd} 节不存在"
                            return null
                        }
                        newStart = rows.first; newEnd = rows.second
                    } else {
                        newStart = hit.startSection; newEnd = hit.endSection
                    }
                    val edited = hit.copy(
                        name = op.setName ?: hit.name,
                        room = op.setRoom ?: hit.room,
                        teacher = op.setTeacher ?: hit.teacher,
                        day = day,
                        startSection = newStart,
                        endSection = newEnd
                    )
                    val next = working.map { if (it === hit) edited else it }
                        .sortedWith(compareBy({ it.startSection }, { it.name }))
                    override = DayOverrides.Override.list(next)
                    working = next
                    notes += "改：${hit.name} → ${edited.name}（${sectionLabel(result, newStart, newEnd)}）"
                }

            }
        }

        val changedNow = override != env.existingOverride(date) || before != working
        if (!changedNow) return null
        return DayChange(date, before, working, override, notes)
    }

    /**
     * 在当天课表里找"那一节"。
     *
     * 0 个匹配 → 报错并**列出这天有什么课**（否则用户只会看到一句"没找到"，
     * 还得自己去翻课表才知道模型说错了哪一节）；≥2 个匹配 → 也报错，要求用节次精确指定
     * —— 删掉一节用户没打算删的课，比多问一句糟得多。
     */
    private fun matchOne(
        list: List<Session>,
        section: Int?,
        name: String?,
        whenLabel: String,
        sections: List<Section>,
        errors: MutableList<String>
    ): Session? {
        val hits = when {
            section != null -> list.filter { s ->
                val from = UserCourses.sectionNoOfRow(sections, s.startSection) ?: s.startSection
                val to = UserCourses.sectionNoOfRow(sections, s.endSection) ?: s.endSection
                section in from..to
            }

            name != null -> {
                val q = name.replace(" ", "").lowercase()
                list.filter { it.name.replace(" ", "").lowercase().contains(q) }
            }

            else -> emptyList()
        }
        if (hits.isEmpty()) {
            val what = list.take(12).joinToString("、") { it.name }.ifEmpty { "这天空着" }
            errors += "$whenLabel：没找到那一节。这天有：$what"
            return null
        }
        if (hits.size > 1) {
            val what = hits.joinToString("、") { "${it.name}(${sectionLabelRaw(it)})" }
            errors += "$whenLabel：匹配到 ${hits.size} 节（$what），请用 S=第几节 指定"
            return null
        }
        return hits[0]
    }

    private fun sectionLabelRaw(s: Session): String = "${s.startSection}-${s.endSection}"

    private fun reduceCourseAdd(
        op: AssistantOp.CourseAdd,
        env: Env,
        errors: MutableList<String>
    ): CourseChange? {
        val sections = env.sections
        val rows = sectionToRows(sections, op.start, op.end)
        if (rows == null) {
            errors += "第 ${op.start}-${op.end} 节不存在"
            return null
        }
        val existing = env.courses()
        val dup = existing.firstOrNull {
            it.session.name.replace(" ", "").equals(op.name.replace(" ", ""), ignoreCase = true) &&
                it.session.day == op.day &&
                it.session.startSection == rows.first
        }
        if (dup != null) {
            errors += "已经有一门一样的课了：${dup.session.name}（${Session.dayLabel(dup.session.day)}）"
            return null
        }
        val check = UserCourses.check(
            UserCourses.Input(
                name = op.name,
                room = op.room,
                teacher = op.teacher,
                day = op.day,
                startSection = op.start.toString(),
                endSection = op.end.toString(),
                startWeek = op.startWeek.toString(),
                endWeek = op.endWeek.toString(),
                parity = op.parity
            ),
            sections
        )
        if (!check.ok || check.session == null) {
            errors += "加课没通过检查：${check.errors.joinToString("；").ifEmpty { "未知原因" }}"
            return null
        }
        return CourseChange(
            add = UserCourses.Course(UserCourses.newId(), check.session),
            name = op.name,
            note = "新增自己的课：${op.name}（${Session.dayLabel(op.day)}）"
        )
    }

    private fun reduceCourseDelete(
        op: AssistantOp.CourseDelete,
        env: Env,
        errors: MutableList<String>
    ): CourseChange? {
        val q = op.name.replace(" ", "").lowercase()
        val hits = env.courses().filter { it.session.name.replace(" ", "").lowercase().contains(q) }
        if (hits.isEmpty()) {
            val what = env.courses().joinToString("、") { it.session.name }.ifEmpty { "还没有自己加的课" }
            errors += "没找到自己加的课「${op.name}」。你自己加的课有：$what"
            return null
        }
        if (hits.size > 1) {
            errors += "「${op.name}」匹配到 ${hits.size} 门（${hits.joinToString("、") { it.session.name }}），请说全一点"
            return null
        }
        return CourseChange(deleteId = hits[0].id, name = hits[0].session.name, note = "删掉自己的课：${hits[0].session.name}")
    }

    // ------------------------------------------------------------ 换算与渲染

    /** 节次（1..14）→ 行序区间；任何一端不存在就是 null（**绝不猜**） */
    fun sectionToRows(sections: List<Section>, start: Int, end: Int): Pair<Int, Int>? {
        val a = UserCourses.rowOfSection(sections, start) ?: return null
        val b = UserCourses.rowOfSection(sections, end) ?: return null
        return if (b < a) null else a to b
    }

    /** "第1-2节" —— 用课表里真实的 label，避免把行序当成节次显示 */
    fun sectionLabel(result: ParseResult, startRow: Int, endRow: Int): String =
        result.sectionRangeLabel(startRow, endRow)

    fun dayLabel(date: LocalDate): String {
        val wd = "一二三四五六日".getOrElse(date.dayOfWeek.value - 1) { '?' }
        return "${date.monthValue}月${date.dayOfMonth}日（周$wd）"
    }

    /**
     * 预览文案：**按天**给出"改前 → 改后"。
     *
     * 刻意不按操作逐条列 —— 用户要判断的是"那天到底上什么课"，而不是"我执行了几条命令"。
     */
    fun renderPreview(outcome: Outcome, result: ParseResult): List<String> {
        val out = ArrayList<String>()
        for (d in outcome.days) {
            val head = "${dayLabel(d.date)}　${d.before.size} 节 → ${d.after.size} 节"
            out += head
            if (d.notes.isNotEmpty()) out += "　" + d.notes.joinToString("；")
            val after = d.after.take(8).joinToString("、") {
                "${it.name}(${sectionLabel(result, it.startSection, it.endSection)})"
            }
            out += "　改后：" + after.ifEmpty { "没有课" }
        }
        for (c in outcome.courses) out += c.note
        return out
    }

    /** 生产环境：真正的课表/覆盖/自定义课程 */
    class ProductionEnv(private val context: Context) : Env {

        override val today: LocalDate get() = LocalDate.now()

        override val week1Monday: LocalDate? get() = Prefs.week1MondayDate()

        override val sections: List<Section> get() = TimetableRepository.result().sections

        override fun result(): ParseResult = TimetableRepository.result()

        override fun dayList(date: LocalDate): List<Session> =
            TimetableRepository.sessionsOn(context, date)

        override fun existingOverride(date: LocalDate): DayOverrides.Override? =
            DayOverrides.get(context, date)

        override fun courses(): List<UserCourses.Course> = UserCourses.all(context)
    }
}
