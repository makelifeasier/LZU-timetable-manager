package app.timetable.ai

import app.timetable.data.Parity
import java.time.LocalDate

/**
 * 助手「说」出来的**操作**（还没落盘）。
 *
 * ## 为什么用一套小语言，而不是 JSON
 *
 *  - **跨服务商**：`response_format: json_object` 只有 DeepSeek 这类服务商支持，而我们保留了
 *    自定义端点（主要 DeepSeek，但不只 DeepSeek）。行式语言在谁那儿都成立。
 *  - **算术留在 App**：模型只做"声明"（哪一天、换成星期几、加第几节），周次基准、节次↔行序换算、
 *    单双周、把"删掉某一节"物化成"当天整份列表"这些全部由 Kotlin 侧算 —— 那是可单测的部分，
 *    交给模型算就是不可测的部分。
 *  - **严格可拒**：任何一行看不懂就整份拒绝（见 [AssistantPlanParser]），不会出现"一半生效"。
 *
 * ## `S=` 一律是"第几节"，不是行序
 *
 * 兰大课表里「中午1节/中午2节」也各占一行，**第 5 节的行序其实是 7**。用户和模型嘴里说的都是
 * "第几节"，所以这里的 `start/end` 存的是**节次**，由解释器用
 * `UserCourses.rowOfSection` 换算成行序 —— 这条边界有单测钉住，是历史上真踩过的坑。
 */
internal sealed interface AssistantOp {

    /** 挂在某个**日期**上的当日调课操作 */
    sealed interface Dated : AssistantOp {
        val date: LocalDate
    }

    /** 影响整学期的「我自己的课」（与当日调课是两套数据） */
    sealed interface Course : AssistantOp

    /** 换这一天：取第 [week] 周的星期 [day] 的课；两个都可缺省（缺省=该日期自己的周/星期几） */
    data class Replace(override val date: LocalDate, val day: Int?, val week: Int?) : Dated

    /** 这一天停课 */
    data class Clear(override val date: LocalDate) : Dated

    /** 恢复这一天的原课表（删掉覆盖） */
    data class Reset(override val date: LocalDate) : Dated

    /** 这一天加一节课（[start]/[end] 是**节次**） */
    data class Add(
        override val date: LocalDate,
        val name: String,
        val room: String,
        val teacher: String,
        val start: Int,
        val end: Int
    ) : Dated

    /** 这一天删掉某一节：用 [section]（节次）或 [name] 指定，二者必须有且只有一个 */
    data class Drop(override val date: LocalDate, val section: Int?, val name: String?) : Dated

    /** 这一天改掉某一节：[section]/[name] 是**匹配**条件，`set*` 是**新值**，至少要有一个新值 */
    data class Edit(
        override val date: LocalDate,
        val section: Int?,
        val name: String?,
        val setName: String?,
        val setRoom: String?,
        val setTeacher: String?,
        val setStart: Int?,
        val setEnd: Int?
    ) : Dated

    /** 加一门自己的课（整学期生效，走 UserCourses 那套校验） */
    data class CourseAdd(
        val name: String,
        val room: String,
        val teacher: String,
        val day: Int,
        val start: Int,
        val end: Int,
        val startWeek: Int,
        val endWeek: Int,
        val parity: Parity
    ) : Course

    /** 删掉自己加的一门课（按课名匹配） */
    data class CourseDelete(val name: String) : Course
}

/** 模型的一次回复：要么反问一句，要么给一份计划 */
internal sealed interface AssistantReply {

    /** 信息不够时的反问。用户接着答，形成同一个对话框里的迷你多轮 */
    data class Ask(val question: String) : AssistantReply

    /** 计划。[note] 是给用户看的一句话（模型可以顺便解释它理解成了什么） */
    data class PlanOps(val ops: List<AssistantOp>, val note: String?) : AssistantReply
}

/**
 * 把模型输出的文本解析成 [AssistantReply]。
 *
 * 规矩：
 *  - 出现 `ASK: …` → 反问；出现 `PLAN` → 后面每一行是一条操作；
 *  - **容忍**前面有寒暄、有 ``` 代码围栏、有空行（模型很爱加这些东西）；
 *  - 任何一行看不懂 → [Result.Bad]（**整份丢弃**，绝不部分应用）；
 *  - 操作条数与节次范围都有上限，防止模型"一次改一学期"。
 *
 * 之所以这么"较真"：这是唯一一处"外部文本决定 App 行为"的入口。放宽一格，
 * 用户的课表就可能被一句它没看懂的话改掉。
 */
internal object AssistantPlanParser {

    /** 一次最多几条操作：超过就不像是"调几天课"，更像是模型跑偏了 */
    const val MAX_OPS = 30

    /** 节次上限。课表里真实出现过 1..14（见 `Model.kt` 的注释） */
    const val MAX_SECTION = 14

    /** 周次上限：一学期不可能超过这个数 */
    const val MAX_WEEK = 40

    sealed interface Result {
        data class Ok(val reply: AssistantReply) : Result
        data class Bad(val reason: String) : Result
    }

    fun parse(raw: String, maxOps: Int = MAX_OPS): Result {
        // 围栏、中文引号、CRLF 一律先抹平：这些是模型的"字体习惯"，不是语义
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        var start = -1
        var ask: String? = null
        for ((i, line) in lines.withIndex()) {
            val t = clean(line)
            if (t.isEmpty()) continue
            val up = t.uppercase()
            if (up.startsWith("ASK:")) {
                ask = t.substringAfter(':').trim()
                start = i
                break
            }
            if (up == "PLAN" || up.startsWith("PLAN:") || up.startsWith("PLAN ")) {
                start = i
                break
            }
        }

        if (start < 0) {
            return Result.Bad(
                "模型没按约定回答：既没有「ASK:」也没有「PLAN」。原文开头：" + raw.trim().take(120)
            )
        }
        if (ask != null) {
            if (ask.isBlank()) return Result.Bad("「ASK:」后面是空的，模型没说要问什么")
            return Result.Ok(AssistantReply.Ask(ask))
        }

        val ops = ArrayList<AssistantOp>()
        var note: String? = null

        // PLAN 这一行后面可能还跟着一条操作（"PLAN OVERRIDE …"）：不静默丢掉
        val restOfHead = clean(lines[start]).let {
            if (it.uppercase().startsWith("PLAN:")) it.substring(5) else it.substring(4)
        }.trim()
        if (restOfHead.isNotEmpty()) {
            val r = parseOp(restOfHead)
            if (r is OpResult.Bad) return Result.Bad("第 ${start + 1} 行看不懂：${r.reason}")
            ops += (r as OpResult.Ok).op
        }

        for (i in (start + 1) until lines.size) {
            val t = clean(lines[i])
            if (t.isEmpty()) continue
            val up = t.uppercase()

            // 模型重复写 PLAN 的情况：忽略即可，不要当成错
            if (up == "PLAN" || up.startsWith("PLAN:") || up.startsWith("PLAN ")) continue

            if (up == "NOTE" || up.startsWith("NOTE:") || up.startsWith("NOTE ")) {
                note = t.removePrefix("NOTE").removePrefix(":").trim().ifBlank { null }
                continue
            }

            when (val r = parseOp(t)) {
                is OpResult.Bad -> return Result.Bad("第 ${i + 1} 行看不懂：${r.reason}｜原文：$t")
                is OpResult.Ok -> {
                    ops += r.op
                    if (ops.size > maxOps) {
                        return Result.Bad("一次改动太多（超过 $maxOps 条），请分批说")
                    }
                }
            }
        }
        return Result.Ok(AssistantReply.PlanOps(ops, note))
    }

    // ------------------------------------------------------------------ 内部

    private sealed interface OpResult {
        data class Ok(val op: AssistantOp) : OpResult
        data class Bad(val reason: String) : OpResult
    }

    /** 抹掉围栏反引号并去掉首尾空白。反引号在值里几乎不可能出现，直接删最省事也最稳 */
    private fun clean(line: String): String = line.replace("`", "").trim()

    private fun parseOp(line: String): OpResult {
        val toks = tokenize(line)
        if (toks.isEmpty()) return OpResult.Bad("空行")
        return when (toks[0].uppercase()) {
            "OVERRIDE" -> parseOverride(toks)
            "COURSE" -> parseCourse(toks)
            else -> OpResult.Bad("未知操作「${toks[0]}」，只认 OVERRIDE / COURSE")
        }
    }

    private fun parseOverride(toks: List<String>): OpResult {
        if (toks.size < 3) return OpResult.Bad("格式应为 OVERRIDE <日期> <动作> [键=值…]")
        val date = parseDate(toks[1])
            ?: return OpResult.Bad("日期要写成 2026-10-15，收到「${toks[1]}」")
        val action = toks[2].uppercase()
        val kv = LinkedHashMap<String, String>()
        for (i in 3 until toks.size) {
            when (val r = splitKv(toks[i])) {
                is Kv.Bad -> return OpResult.Bad(r.why)
                is Kv.Ok -> {
                    if (kv.containsKey(r.key)) return OpResult.Bad("「${r.key}」给了两次")
                    kv[r.key] = r.value
                }
            }
        }

        fun only(vararg allowed: String): String? {
            val bad = kv.keys.firstOrNull { it !in allowed }
            return if (bad == null) null else "$action 不认「$bad」这个键（只认 ${allowed.joinToString("/")}）"
        }

        return when (action) {
            "REPLACE" -> {
                only("DAY", "WEEK")?.let { return OpResult.Bad(it) }
                val day = kv["DAY"]?.let {
                    parseDay(it) ?: return OpResult.Bad("DAY 要写 1-7 或 周一…周日，收到「$it」")
                }
                val week = kv["WEEK"]?.let {
                    val n = it.toIntOrNull() ?: return OpResult.Bad("WEEK 要是数字，收到「$it」")
                    if (n !in 1..MAX_WEEK) return OpResult.Bad("WEEK 超出 1-$MAX_WEEK")
                    n
                }
                OpResult.Ok(AssistantOp.Replace(date, day, week))
            }

            "CLEAR" -> {
                only()?.let { return OpResult.Bad(it) }
                OpResult.Ok(AssistantOp.Clear(date))
            }

            "RESET" -> {
                only()?.let { return OpResult.Bad(it) }
                OpResult.Ok(AssistantOp.Reset(date))
            }

            "ADD" -> {
                only("NAME", "ROOM", "TEACHER", "S")?.let { return OpResult.Bad(it) }
                val name = kv["NAME"]?.trim().orEmpty()
                if (name.isEmpty()) return OpResult.Bad("ADD 必须给 name=课名")
                val s = kv["S"] ?: return OpResult.Bad("ADD 必须给 S=第几节（如 S=3-4）")
                val (a, b) = parseSections(s)
                    ?: return OpResult.Bad("S 要写 3 或 3-4（1-$MAX_SECTION），收到「$s」")
                OpResult.Ok(
                    AssistantOp.Add(
                        date = date, name = name,
                        room = kv["ROOM"]?.trim().orEmpty(),
                        teacher = kv["TEACHER"]?.trim().orEmpty(),
                        start = a, end = b
                    )
                )
            }

            "DROP" -> {
                only("S", "NAME")?.let { return OpResult.Bad(it) }
                val hasS = kv.containsKey("S")
                val hasName = kv.containsKey("NAME")
                if (hasS == hasName) return OpResult.Bad("DROP 要用 S=第几节 或 name=课名 指定，且只能给一个")
                val section = kv["S"]?.let {
                    val (a, b) = parseSections(it)
                        ?: return OpResult.Bad("S 要写 3 或 3-4（1-$MAX_SECTION），收到「$it」")
                    if (a != b) return OpResult.Bad("DROP 的 S 只接受单独一节（如 S=5）")
                    a
                }
                val name = kv["NAME"]?.trim()?.ifBlank { null }
                OpResult.Ok(AssistantOp.Drop(date, section, name))
            }

            "EDIT" -> {
                only("S", "NAME", "SETNAME", "SETROOM", "SETTEACHER", "SETS")?.let { return OpResult.Bad(it) }
                val hasS = kv.containsKey("S")
                val hasName = kv.containsKey("NAME")
                if (hasS == hasName) return OpResult.Bad("EDIT 要用 S=第几节 或 name=课名 指定改哪一节，且只能给一个")
                val section = kv["S"]?.let {
                    val (a, b) = parseSections(it)
                        ?: return OpResult.Bad("S 要写 3 或 3-4（1-$MAX_SECTION），收到「$it」")
                    if (a != b) return OpResult.Bad("EDIT 的 S 只接受单独一节（如 S=3）")
                    a
                }
                val setS = kv["SETS"]?.let {
                    val (a, b) = parseSections(it)
                        ?: return OpResult.Bad("setS 要写 3 或 3-4（1-$MAX_SECTION），收到「$it」")
                    a to b
                }
                val newName = kv["SETNAME"]?.trim()
                val newRoom = kv["SETROOM"]?.trim()
                val newTeacher = kv["SETTEACHER"]?.trim()
                if (setS == null && newName == null && newRoom == null && newTeacher == null) {
                    return OpResult.Bad("EDIT 至少要给一个 set* 新值（setName/setRoom/setTeacher/setS）")
                }
                OpResult.Ok(
                    AssistantOp.Edit(
                        date = date,
                        section = section,
                        name = kv["NAME"]?.trim()?.ifBlank { null },
                        setName = newName,
                        setRoom = newRoom,
                        setTeacher = newTeacher,
                        setStart = setS?.first,
                        setEnd = setS?.second
                    )
                )
            }

            else -> OpResult.Bad("未知的当天动作「$action」（REPLACE/CLEAR/RESET/ADD/DROP/EDIT）")
        }
    }

    private fun parseCourse(toks: List<String>): OpResult {
        if (toks.size < 2) return OpResult.Bad("格式应为 COURSE <ADD|DELETE> 键=值…")
        val action = toks[1].uppercase()
        val kv = LinkedHashMap<String, String>()
        for (i in 2 until toks.size) {
            when (val r = splitKv(toks[i])) {
                is Kv.Bad -> return OpResult.Bad(r.why)
                is Kv.Ok -> {
                    if (kv.containsKey(r.key)) return OpResult.Bad("「${r.key}」给了两次")
                    kv[r.key] = r.value
                }
            }
        }
        return when (action) {
            "ADD" -> {
                val bad = kv.keys.firstOrNull { it !in listOf("NAME", "ROOM", "TEACHER", "DAY", "S", "W", "PARITY") }
                if (bad != null) return OpResult.Bad("COURSE ADD 不认「$bad」这个键")
                val name = kv["NAME"]?.trim().orEmpty()
                if (name.isEmpty()) return OpResult.Bad("COURSE ADD 必须给 name=课名")
                val day = parseDay(kv["DAY"].orEmpty())
                    ?: return OpResult.Bad("COURSE ADD 要给 DAY=1-7 或 周三")
                val (a, b) = parseSections(kv["S"].orEmpty())
                    ?: return OpResult.Bad("COURSE ADD 要给 S=第几节（如 S=5-6）")
                val (w1, w2) = parseWeeks(kv["W"].orEmpty())
                    ?: return OpResult.Bad("COURSE ADD 要给 W=第几到第几周（如 W=1-16，单周也给 W=1-16 PARITY=ODD）")
                val parity = kv["PARITY"]?.let {
                    parseParity(it) ?: return OpResult.Bad("PARITY 只能是 NONE/ODD/EVEN（或 单/双）")
                } ?: Parity.NONE
                OpResult.Ok(
                    AssistantOp.CourseAdd(
                        name = name,
                        room = kv["ROOM"]?.trim().orEmpty(),
                        teacher = kv["TEACHER"]?.trim().orEmpty(),
                        day = day, start = a, end = b,
                        startWeek = w1, endWeek = w2, parity = parity
                    )
                )
            }

            "DELETE" -> {
                val bad = kv.keys.firstOrNull { it != "NAME" }
                if (bad != null) return OpResult.Bad("COURSE DELETE 只认 name=")
                val name = kv["NAME"]?.trim().orEmpty()
                if (name.isEmpty()) return OpResult.Bad("COURSE DELETE 必须给 name=课名")
                OpResult.Ok(AssistantOp.CourseDelete(name))
            }

            else -> OpResult.Bad("COURSE 只认 ADD / DELETE，收到「$action」")
        }
    }

    /**
     * 按空白分词，但 `"…"`（含中文引号）里的空白当内容。
     * 课名里带空格的情况不算罕见（"大学 英语"），所以值得有这一层。
     */
    private fun tokenize(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (ch in line) {
            when {
                ch == '"' || ch == '\u201C' || ch == '\u201D' -> inQuote = !inQuote
                ch == ' ' || ch == '\t' -> {
                    if (inQuote) {
                        sb.append(ch)
                    } else if (sb.isNotEmpty()) {
                        out += sb.toString(); sb.clear()
                    }
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** `键=值` → (规范键, 值)。键不认识就当错误（严格），值可以是空串 */
    private sealed interface Kv {
        data class Ok(val key: String, val value: String) : Kv

        /** [why] 直接进用户看到的错误里：模型到底错在"没写成 键=值"还是"自己发明了键"，是两件事 */
        data class Bad(val why: String) : Kv
    }

    private fun splitKv(tok: String): Kv {
        val i = tok.indexOf('=')
        if (i <= 0) return Kv.Bad("「$tok」不是 键=值 的形式")
        val rawKey = tok.substring(0, i)
        val key = normKey(rawKey) ?: return Kv.Bad("不认「${rawKey.trim()}」这个键")
        return Kv.Ok(key, tok.substring(i + 1))
    }

    private fun normKey(raw: String): String? = when (raw.trim().uppercase()) {
        "DAY", "WEEKDAY", "星期", "星期几" -> "DAY"
        "WEEK", "WEEKNUM", "周", "第几周" -> "WEEK"
        "NAME", "COURSE", "课名", "课程", "课程名" -> "NAME"
        "ROOM", "教室" -> "ROOM"
        "TEACHER", "老师", "教师" -> "TEACHER"
        "S", "SECTION", "SECTIONS", "节", "节次" -> "S"
        "W", "WEEKS", "WEEKSPAN", "周次" -> "W"
        "PARITY", "单双周" -> "PARITY"
        "SETNAME" -> "SETNAME"
        "SETROOM" -> "SETROOM"
        "SETTEACHER" -> "SETTEACHER"
        "SETS" -> "SETS"
        else -> null
    }

    /** 2026-10-15 / 2026/10/15 / 2026.10.15 / 2026年10月15日 都认 */
    private fun parseDate(raw: String): LocalDate? {
        val s = raw.trim()
            .replace("年", "-").replace("月", "-").replace("日", "")
            .replace('/', '-').replace('.', '-')
        val parts = s.split('-').filter { it.isNotBlank() }
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (y !in 2000..2100) return null
        return runCatching { LocalDate.of(y, m, d) }.getOrNull()
    }

    /** 1..7、周1、星期三、礼拜天 都认 */
    private fun parseDay(raw: String): Int? {
        val s = raw.trim()
            .replace("星期", "").replace("礼拜", "").replace("周", "")
            .trim()
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return if (it in 1..7) it else null }
        return when (s) {
            "一", "1" -> 1
            "二", "2" -> 2
            "三", "3" -> 3
            "四", "4" -> 4
            "五", "5" -> 5
            "六", "6" -> 6
            "日", "天", "7" -> 7
            else -> null
        }
    }

    /** `3` / `3-4` / `第3节` / `3~4` / `3到4` → (起, 止)，都是**节次** */
    private fun parseSections(raw: String): Pair<Int, Int>? {
        val s = raw.trim()
            .replace("第", "").replace("节", "").replace("次", "")
            .replace(" ", "").replace("、", "-")
        if (s.isEmpty()) return null
        val parts = s.split('-', '~', '到', '至').filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.size > 2) return null
        val a = parts[0].toIntOrNull() ?: return null
        val b = if (parts.size == 2) (parts[1].toIntOrNull() ?: return null) else a
        if (a !in 1..MAX_SECTION || b !in 1..MAX_SECTION || b < a) return null
        return a to b
    }

    /** `1-16` / `1` / `第1-16周` → (起, 止) */
    private fun parseWeeks(raw: String): Pair<Int, Int>? {
        val s = raw.trim()
            .replace("第", "").replace("周", "").replace(" ", "").replace("、", "-")
        if (s.isEmpty()) return null
        val parts = s.split('-', '~', '到', '至').filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.size > 2) return null
        val a = parts[0].toIntOrNull() ?: return null
        val b = if (parts.size == 2) (parts[1].toIntOrNull() ?: return null) else a
        if (a !in 1..MAX_WEEK || b !in 1..MAX_WEEK || b < a) return null
        return a to b
    }

    private fun parseParity(raw: String): Parity? = when (raw.trim().uppercase().replace("周", "")) {
        "NONE", "ALL", "无", "全部", "不限" -> Parity.NONE
        "ODD", "单" -> Parity.ODD
        "EVEN", "双" -> Parity.EVEN
        else -> null
    }
}
