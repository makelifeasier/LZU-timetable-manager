package app.timetable.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 用户自己加的课程（实验课、重修、辅导班、体育选修……教务课表里没有的那些）。
 *
 * ## 为什么单独存一份，而不是写进 `Prefs.resultJson`
 * `resultJson` 是**教务系统抓回来的原始数据的缓存**，下次同步会被整份覆盖 ——
 * 自定义课程混进去，第一次自动同步就没了。两者的生命周期也不一样：
 * 缓存随时可以清（`Prefs.clearCache()` 清的正是它这份清单），
 * 而自定义课程是用户手敲进去的，清了就得重新敲一遍。
 *
 * 所以这里用**另一个 SharedPreferences 文件**（`user_courses`）+ 自己的 key，
 * `data/Prefs.kt` 一个字都没动（它的 `CACHE_KEYS` 有单测在比对清单，
 * 而"自定义课程不是缓存、不该被清空缓存带走"这条判断本来也不该混进那份清单）。
 *
 * ## 时间为什么要换算
 * 用户填的是"第几节"，而 [Session.startSection] 存的是**表格行序** ——
 * 兰大课表页面中间插了「中午1节 / 中午2节」两行，第 5 节的行序其实是 7。
 * 换算逻辑与 `ui/DayEditDialog` 里的那段一致（按 label 找，找不到退回"第 N 个显示出来的节次"），
 * 抽到这里是为了能单测，也为了两边不会各发明一套。
 *
 * ## 存的是什么
 * 存 [Session]，`category = "自定义"`：这样仓库合并后，课表页 / 小组件 / 导出图片 / 提醒
 * 全都当它是一门普通的课，不需要任何一条链路单独照顾它。
 */
internal object UserCourses {

    /** 自定义课程在 [Session.category] 里的标记（区分来源，将来要单独统计也靠它） */
    const val CATEGORY = "自定义"

    /** 用户能填的周次上界。与设置里的周次切换范围同一个口径 */
    const val MAX_WEEK = 30

    /** 行序的合理上界：兰大课表最多 14 行，留点余量防止脏数据把卡片画到屏幕外 */
    private const val MAX_ROW = 40

    private const val FILE = "user_courses"
    private const val KEY = "courses"

    /**
     * 一条自定义课。
     *
     * [id] 只用于"编辑/删除定位"，**不放进 [Session]**：Session 里没有它的位置，
     * 而且 id 一旦混进 `Session.key`（合并判据）就会让"同一门课的两段"永远合不上。
     */
    data class Course(val id: String, val session: Session)

    // ================================================================= 纯逻辑（可单测）

    /** 表单的原始输入。全是字符串 —— 界面填什么就是什么，解析与校验都在 [check] 里收口 */
    data class Input(
        val name: String,
        val room: String = "",
        val teacher: String = "",
        val day: Int = 1,
        val startSection: String = "",
        val endSection: String = "",
        val startWeek: String = "",
        val endWeek: String = "",
        val parity: Parity = Parity.NONE
    )

    /** 校验结果：[errors] 非空 = 没通过（文案直接给用户看）；[session] 非空 = 可以存了 */
    data class Check(val errors: List<String> = emptyList(), val session: Session? = null) {
        val ok: Boolean get() = session != null
    }

    /** 周次校验结果 */
    data class WeekCheck(val span: WeekSpan? = null, val error: String? = null)

    /** 「第N节」里的 N；"中午1节"这种非数字标签返回 null */
    fun sectionNoOf(label: String): Int? {
        val t = label.trim()
        if (t.length < 3 || !t.startsWith("第") || !t.endsWith("节")) return null
        return t.substring(1, t.length - 1).trim().toIntOrNull()
    }

    /**
     * 用户能填的最大"第几节"。
     *
     * 取 label 里真实存在的「第N节」最大值（兰大是 12）——**不能**用 `sections.size`：
     * 那份表里还有「中午1节 / 中午2节」两行，用行数当上界会放用户填到不存在的第 14 节。
     * 课表里压根没有「第N节」这种标签时（别的学校/页面改版），退回"节次个数"，
     * 与 DayEditDialog 的兜底口径一致。
     */
    fun maxSectionNo(sections: List<Section>): Int =
        sections.mapNotNull { sectionNoOf(it.label) }.maxOrNull() ?: sections.size

    /**
     * 「第几节」→ 表格行序。
     *
     * 先按 label 精确找（`第5节` → 行 7）；找不到再退化成"第 N 个显示出来的节次"，
     * 最后才返回 null（调用方据此报"课表里没有这一节"）。
     */
    fun rowOfSection(sections: List<Section>, no: Int): Int? {
        sections.firstOrNull { sectionNoOf(it.label) == no }?.let { return it.index }
        return sections.getOrNull(no - 1)?.index
    }

    /**
     * 表格行序 → 「第几节」（[rowOfSection] 的反函数）。编辑表单必须用它。
     *
     * 为什么：存下去的是**行序**，而表单里那一格问的是"第几节"。
     * 不翻的话，一条"第 5 节"的课再打开会显示成 7；用户不改节次直接保存时，
     * 7 又被当成"第 7 节"存成行 9 —— **课自己往下挪了两行**，而且没有任何提示。
     * 返回 null = 这个行序在当前课表里压根不存在（课表重新导入过、结构变了），
     * 调用方应当让用户重新选一次节次，而不是拿行序当节号糊上去。
     */
    fun sectionNoOfRow(sections: List<Section>, row: Int): Int? {
        val at = sections.firstOrNull { it.index == row } ?: return null
        sectionNoOf(at.label)?.let { return it }
        // 这一行是「中午1节」这种非数字标签（或整张表都没有「第N节」标签）：退回
        // "它是表里第几个节次" —— 与 rowOfSection 在同样情形下的兜底口径互逆。
        // 用户从表单选不了中午那两行（能选的节号 1..12 落在别的行上），
        // 所以这条兜底只在"课表结构换过"时才会被走到。
        val nth = sections.indexOfFirst { it.index == row }
        return if (nth >= 0) nth + 1 else null
    }

    /**
     * 周次校验：起止周必须都在 1..[MAX_WEEK]，且开始不能晚于结束。
     *
     * 额外查一条"单双周陷阱"：区间里一个符合单/双周的周都没有时，
     * 这节课**永远不会显示**。这种错用户自己看不出来（他以为加成功了），
     * 与其让他过几天回来问"我加的课怎么没了"，不如当场拦住。
     */
    fun checkWeeks(startText: String, endText: String, parity: Parity): WeekCheck {
        val a = startText.trim()
        val b = endText.trim()
        if (a.isEmpty() || b.isEmpty()) return WeekCheck(error = "周次没填全：填第几周到第几周，例如 1 到 16")
        val start = a.toIntOrNull()
        val end = b.toIntOrNull()
        if (start == null || end == null) {
            return WeekCheck(error = "周次要填数字（第 1–${MAX_WEEK} 周）")
        }
        if (start < 1 || end < 1 || start > MAX_WEEK || end > MAX_WEEK) {
            return WeekCheck(error = "周次要在第 1–${MAX_WEEK} 周之间，现在填的是 ${start}–${end}")
        }
        if (start > end) {
            return WeekCheck(error = "周次颠倒了：开始（第 ${start} 周）比结束（第 ${end} 周）还晚")
        }
        if (parity != Parity.NONE) {
            val hit = (start..end).any { w -> if (parity == Parity.ODD) w % 2 == 1 else w % 2 == 0 }
            if (!hit) {
                val word = if (parity == Parity.ODD) "单周" else "双周"
                return WeekCheck(error = "和「${word}」对不上：第 ${start}–${end} 周里没有${word}，这门课一次都不会出现")
            }
        }
        return WeekCheck(span = WeekSpan(start, end, parity))
    }

    /**
     * 整个表单的校验。返回的 [Check.session] 里，节次**已经是行序**，周次已经是 [WeekSpan]。
     *
     * 为什么把这一坨做成纯函数：它是这个功能里唯一"错了就会静默错"的地方 ——
     * 节次记错行，课就画到别的格子里，提醒时间也跟着错（见文件头的说明）。
     * 抽成不碰 Android 的函数，才能像现在这样一条条钉住。
     */
    fun check(input: Input, sections: List<Section>): Check {
        val errors = ArrayList<String>(3)
        val name = input.name.trim()
        if (name.isEmpty()) errors += "课名不能为空（写「体育选修」这种就行）"
        if (input.day !in 1..7) errors += "星期几没选对"

        var startRow = 0
        var endRow = 0
        if (sections.isEmpty()) {
            // 节次表来自教务课表页面。没有它就没法把"第几节"落到表格的哪一行，
            // 硬存一个数字只会把课画到错误的格子上 —— 宁可拒绝。
            errors += "还没读到课表的节次表：先同步一次课表，再来添加"
        } else {
            val a = input.startSection.trim()
            val b = input.endSection.trim().ifEmpty { a }
            val na = a.toIntOrNull()
            val nb = b.toIntOrNull()
            val max = maxSectionNo(sections)
            when {
                na == null || nb == null -> errors += "节次要填数字，例如 3 到 4"
                na < 1 || nb < 1 -> errors += "节次从第 1 节开始"
                na > max || nb > max -> errors += "节次超范围：这门课最多到第 ${max} 节"
                else -> {
                    // 填反了就按小的在前算（和 DayEditDialog 的"加一节课"一致，不报错）
                    val lo = minOf(na, nb)
                    val hi = maxOf(na, nb)
                    val r1 = rowOfSection(sections, lo)
                    val r2 = rowOfSection(sections, hi)
                    if (r1 == null || r2 == null) {
                        // 中文是合法标识符字符，"$lo节" 会被当成变量名，必须写 ${}
                        errors += "课表里找不到第 ${lo}–${hi} 节对应的行"
                    } else {
                        startRow = r1
                        endRow = r2
                    }
                }
            }
        }

        val wc = checkWeeks(input.startWeek, input.endWeek, input.parity)
        wc.error?.let { errors += it }

        if (errors.isNotEmpty() || wc.span == null) return Check(errors = errors)

        return Check(
            session = Session(
                name = name,
                room = input.room.trim(),
                teacher = input.teacher.trim(),
                weeks = wc.span,
                category = CATEGORY,
                day = input.day,
                startSection = startRow,
                endSection = endRow
            )
        )
    }

    /**
     * 把自定义课程并进教务课表 —— **整个 App 唯一的合并点**。
     *
     * 合并规则与理由：
     *  - **同一天同一节撞了都保留**（不覆盖、不去重）：和现在"给这天加一节课"的行为一致。
     *    教务课表与用户自己记的课是两份真相，谁也压不过谁；而且真要覆盖的话，
     *    "我加的体育被系统课顶掉了"这种丢数据的事故不会有第二次解释机会 ——
     *    宁可让用户看见两段课（他能立刻看出撞了，自己改节次），也不要静默吞掉一段。
     *  - **追加时按 (星期, 起始节, 结束节, 课名) 排序**，教务课程的原始顺序原样不动：
     *    所有"按天显示"的消费方（`WeekCalc.sessionsFor` / `merge` / `weekGrid`）本来就会自己重排，
     *    重排整份列表只会让诊断页导出、`maxWeek` 计算跟着动，收益为零。
     *  - 自定义课的 `category` 是「自定义」，与教务课的 `Session.key` 天然不同，
     *    所以 `WeekCalc.merge` 不会把它们相邻合并成一段（各是各的卡片）。
     */
    fun mergeInto(base: ParseResult, mine: List<Session>): ParseResult {
        if (mine.isEmpty()) return base
        val ordered = mine.sortedWith(
            compareBy({ it.day }, { it.startSection }, { it.endSection }, { it.name })
        )
        return base.copy(sessions = base.sessions + ordered)
    }

    /** 列表页的显示顺序：按星期、再按起始节 */
    fun sortedForDisplay(list: List<Course>): List<Course> = list.sortedWith(
        compareBy({ it.session.day }, { it.session.startSection }, { it.session.endSection }, { it.session.name })
    )

    /** 新增或按 id 覆盖（**纯逻辑**：删除/编辑的真实规则在这里，JSON 只是它的壳） */
    fun upsert(list: List<Course>, course: Course): List<Course> {
        val i = list.indexOfFirst { it.id == course.id }
        if (i < 0) return list + course
        return list.toMutableList().also { it[i] = course }
    }

    /** 按 id 删除（不存在就是原列表，不抛错） */
    fun without(list: List<Course>, id: String): List<Course> = list.filterNot { it.id == id }

    /** 新条目的 id。用 UUID 是为了"同一条被编辑两次"能稳定覆盖，而不是又加一条 */
    fun newId(): String = UUID.randomUUID().toString()

    // ================================================================= 存取

    fun all(context: Context): List<Course> =
        decode(prefs(context).getString(KEY, "").orEmpty())

    /** 仓库读的入口：只要 [Session]，id 不进课表 */
    fun sessions(context: Context): List<Session> = all(context).map { it.session }

    /** 保存（新增或覆盖），返回保存后的整份列表 */
    fun save(context: Context, course: Course): List<Course> {
        val next = upsert(all(context), course)
        write(context, next)
        return next
    }

    /** 删除一条，返回删除后的整份列表 */
    fun remove(context: Context, id: String): List<Course> {
        val next = without(all(context), id)
        write(context, next)
        return next
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun write(context: Context, list: List<Course>) {
        prefs(context).edit().putString(KEY, encode(list)).apply()
    }

    /**
     * 序列化。**故意不存 category**：读回来一律是「自定义」——
     * 存了反而给了脏数据一个冒充教务课程的机会。
     */
    internal fun encode(list: List<Course>): String {
        val items = JSONArray()
        for (c in list) {
            val s = c.session
            items.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", s.name)
                    .put("room", s.room)
                    .put("teacher", s.teacher)
                    .put("day", s.day)
                    .put("s", s.startSection)
                    .put("e", s.endSection)
                    .put("ws", s.weeks.start)
                    .put("we", s.weeks.end)
                    .put("parity", s.weeks.parity.name)
            )
        }
        return JSONObject().put("v", 1).put("items", items).toString()
    }

    /**
     * 反序列化。逐条做**兜底与夹紧**：这份文件是用户数据，可能来自旧版本、
     * 也可能被手改过 —— 宁可读成一个合理的值，也不要让课表上出现一片乱七八糟的格子。
     * 没有课名的条目直接丢（它在界面上没法被识别，也没法被删除）。
     */
    internal fun decode(raw: String): List<Course> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Course>()
        runCatching {
            val items = JSONObject(raw).optJSONArray("items") ?: return@runCatching
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                if (name.isEmpty()) continue
                var ws = o.optInt("ws", 1).coerceIn(1, MAX_WEEK)
                var we = o.optInt("we", 16).coerceIn(1, MAX_WEEK)
                if (ws > we) {
                    val t = ws; ws = we; we = t
                }
                val s = o.optInt("s", 1).coerceIn(1, MAX_ROW)
                val e = o.optInt("e", s).coerceIn(1, MAX_ROW)
                out += Course(
                    id = o.optString("id").ifBlank { newId() },
                    session = Session(
                        name = name,
                        room = o.optString("room").trim(),
                        teacher = o.optString("teacher").trim(),
                        weeks = WeekSpan(
                            ws, we,
                            runCatching { Parity.valueOf(o.optString("parity")) }.getOrDefault(Parity.NONE)
                        ),
                        category = CATEGORY,
                        day = o.optInt("day", 1).coerceIn(1, 7),
                        startSection = minOf(s, e),
                        endSection = maxOf(s, e)
                    )
                )
            }
        }
        return out
    }
}
