package app.timetable.data

/**
 * 兰大教务系统「学生课表」解析器。纯函数，无 Android 依赖。
 *
 * 页面契约（已由真实页面确认）：
 *   <table id="timetable">
 *     <tr><th>&nbsp;</th><th>周一</th>…<th>周日</th></tr>
 *     <tr><th nowrap>第1节<br>08:30<br>┆<br>09:15</th>
 *         <td id="1-1" class="center">&lt;&lt;高阶英语1&gt;&gt;;23<br>天山堂A301<br>甲老师<br>4-18周双周<br>讲课学时</td>…
 *
 * 关键点：单元格 id 形如「星期-节次」，坐标直接给出，不需要靠表头推列。
 */
object TimetableParser {

    private val TR_RE = Regex("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>")
    private val CELL_RE = Regex("(?is)<t([dh])\\b([^>]*)>(.*?)</t\\1\\s*>")
    private val ID_RE = Regex("(?is)\\bid\\s*=\\s*[\"']([^\"']*)[\"']")
    private val ROWSPAN_RE = Regex("(?is)\\browspan\\s*=\\s*[\"']?(\\d+)")
    private val GRID_ID_RE = Regex("^(\\d+)-(\\d+)$")
    private val WEEK_RE = Regex("^(\\d+)\\s*(?:-\\s*(\\d+))?\\s*周\\s*(全周|单周|双周|单|双)?$")
    private val NAME_RE = Regex("^<<\\s*(.+?)\\s*>>\\s*;?\\s*(\\d*)$")
    private val HM_RE = Regex("^\\d{1,2}:\\d{2}$")
    private val HOURS_RE = Regex("学时\\s*$")
    private val ROOM_HINT_RE = Regex("[楼室馆场区]")
    private val DAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun parse(rawHtml: String): ParseResult {
        val html = HtmlText.stripNoise(rawHtml)
        val term = parseTerm(html)
        val table = HtmlText.extractById(html, "timetable")

        // 页面里没有兰大那套 id="timetable" 结构（分栏改了、或抽成了新模板）。
        // 兰大页面上正常都有；没有时交给兜底解析器，别让整页直接作废。
        if (table == null) return GenericTableParser.parse(rawHtml)

        val (sections, sessions) = parseGrid(table)
        val result = ParseResult(term, sections, sessions, parseNoArrangement(html))

        // 结构对上了却一段课都没解析出来（页面改版、或本周确实空表）→ 再给通用解析器一次机会，
        // 但它必须真的解析出课才作数，否则仍然返回兰大这份结果（保留「无具体时间的课程」那一栏）。
        return if (result.sessions.isEmpty()) {
            GenericTableParser.parse(rawHtml).takeIf { it.sessions.isNotEmpty() } ?: result
        } else {
            result
        }
    }

    // ---------------------------------------------------------------- 网格

    private class Cell(
        val isHeader: Boolean,
        val day: Int,
        val section: Int,
        val rowspan: Int,
        val html: String
    )

    private fun parseGrid(tableHtml: String): Pair<List<Section>, List<Session>> {
        val sections = LinkedHashMap<Int, Section>()
        val sessions = ArrayList<Session>()
        var lastSection = 0
        var dayHeaderSeen = false

        for (tr in TR_RE.findAll(tableHtml)) {
            val inner = tr.groupValues[1]
            val rawCells = CELL_RE.findAll(inner).toList()
            if (rawCells.isEmpty()) continue

            val headers = rawCells.filter { it.groupValues[1].equals("h", true) }
            val dataCells = rawCells.filter { it.groupValues[1].equals("d", true) }

            // 天头行：全 th 且出现「周一」…「周日」
            if (dataCells.isEmpty()) {
                val texts = headers.map { HtmlText.squeeze(HtmlText.plainText(it.groupValues[3])) }
                if (texts.any { t -> DAY_LABELS.any { t.startsWith(it) } || t.startsWith("星期") }) {
                    dayHeaderSeen = true
                    continue
                }
                // 整行都被上方 rowspan 覆盖：仍要登记该节次的上课时间
                if (headers.size == 1) {
                    lastSection += 1
                    registerSection(sections, lastSection, headers[0].groupValues[3])
                }
                continue
            }

            val cells = ArrayList<Cell>()
            var positionalDay = 0
            for (c in dataCells) {
                positionalDay++
                val attrs = c.groupValues[2]
                val id = ID_RE.find(attrs)?.groupValues?.get(1)?.trim() ?: ""
                val span = ROWSPAN_RE.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val grid = GRID_ID_RE.matchEntire(id)
                val day = grid?.groupValues?.get(1)?.toIntOrNull() ?: positionalDay
                val sec = grid?.groupValues?.get(2)?.toIntOrNull() ?: 0
                cells += Cell(false, day, sec, maxOf(1, span), c.groupValues[3])
            }

            val rowSection = cells.map { it.section }.filter { it > 0 }.minOrNull()
                ?: (lastSection + 1)
            lastSection = rowSection

            if (rowSection !in sections) {
                val headHtml = headers.firstOrNull()?.groupValues?.get(3)
                registerSection(sections, rowSection, headHtml)
            }

            for (cell in cells) {
                val sec = if (cell.section > 0) cell.section else rowSection
                val endSec = sec + cell.rowspan - 1
                val lines = HtmlText.cellLines(cell.html)
                if (lines.isEmpty()) continue
                sessions += parseCellBlocks(lines, cell.day, sec, endSec)
            }
        }

        if (!dayHeaderSeen) {
            // 结构异常但 id 在：坐标仍可用，静默继续
        }
        return sections.values.sortedBy { it.index } to sessions
    }

    private fun registerSection(into: MutableMap<Int, Section>, index: Int, headHtml: String?) {
        val lines = headHtml?.let { HtmlText.cellLines(it) } ?: emptyList()
        val times = lines.filter { HM_RE.matches(it) }
        val label = lines.firstOrNull { !HM_RE.matches(it) && it != "┆" } ?: "第${index}节"
        into[index] = Section(index, label, times.getOrNull(0) ?: "", times.getOrNull(1) ?: "")
    }

    /**
     * 单元格文本行 → 若干 Session。
     * 每行以 `<<课程名>>;课序号` 开头视为新的一段课。
     */
    internal fun parseCellBlocks(
        lines: List<String>,
        day: Int,
        startSection: Int,
        endSection: Int
    ): List<Session> {
        val blocks = ArrayList<MutableList<String>>()
        for (line in lines) {
            if (NAME_RE.matches(line)) {
                blocks += mutableListOf(line)
            } else if (blocks.isNotEmpty()) {
                blocks.last() += line
            }
            // 出现在首个课名之前的行（脏数据）直接丢弃
        }

        val out = ArrayList<Session>(blocks.size)
        for (block in blocks) {
            val m = NAME_RE.matchEntire(block[0]) ?: continue
            val name = m.groupValues[1].trim()
            if (name.isEmpty()) continue
            val seqNo = m.groupValues[2].trim()
            val rest = block.drop(1)

            val weekIdx = rest.indexOfFirst { WEEK_RE.matches(it) }
            val weeks = if (weekIdx >= 0) {
                parseWeeks(rest[weekIdx]) ?: WeekSpan.UNKNOWN
            } else {
                WeekSpan.UNKNOWN
            }

            val category = rest.getOrNull(weekIdx + 1)?.takeIf { HOURS_RE.containsMatchIn(it) } ?: ""
            val meta = rest.filterIndexed { i, v -> i != weekIdx && !HOURS_RE.containsMatchIn(v) }

            var room = meta.getOrNull(0)?.trim().orEmpty()
            var teacher = meta.getOrNull(1)?.trim().orEmpty()
            if (teacher.isEmpty() && room.isNotEmpty() && !ROOM_HINT_RE.containsMatchIn(room)) {
                // 只给了一行元信息且不像地点 → 当教师
                teacher = room
                room = ""
            }

            out += Session(
                name = name,
                seqNo = seqNo,
                room = room,
                teacher = teacher,
                weeks = weeks,
                category = category,
                day = day,
                startSection = startSection,
                endSection = endSection
            )
        }
        return out
    }

    fun parseWeeks(text: String): WeekSpan? {
        val m = WEEK_RE.matchEntire(HtmlText.squeeze(text)) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: a
        val parity = when (m.groupValues[3]) {
            "单周", "单" -> Parity.ODD
            "双周", "双" -> Parity.EVEN
            else -> Parity.NONE
        }
        return WeekSpan(minOf(a, b), maxOf(a, b), parity)
    }

    // ------------------------------------------------- 无时间地点课程表

    private fun parseNoArrangement(html: String): List<UnscheduledCourse> {
        val table = HtmlText.extractById(html, "noArrangement") ?: return emptyList()
        var headers: List<String> = emptyList()
        val out = ArrayList<UnscheduledCourse>()

        for (tr in TR_RE.findAll(table)) {
            val cells = CELL_RE.findAll(tr.groupValues[1]).toList()
            if (cells.isEmpty()) continue
            val texts = cells.map { HtmlText.squeeze(HtmlText.plainText(it.groupValues[3])) }
            if (cells.all { it.groupValues[1].equals("h", true) }) {
                headers = texts
                continue
            }
            fun at(i: Int) = texts.getOrElse(i) { "" }
            fun col(name: String): String {
                val i = headers.indexOfFirst { it.replace(" ", "") == name }
                return if (i >= 0) at(i) else ""
            }
            val hasHeaders = headers.isNotEmpty()
            val name = (if (hasHeaders) col("课程名称") else at(1)).trim()
            if (name.isEmpty()) continue
            val weeksText = if (hasHeaders) col("上课周次") else at(5)
            out += UnscheduledCourse(
                code = (if (hasHeaders) col("课程号") else at(0)).trim(),
                name = name,
                seqNo = (if (hasHeaders) col("课序号") else at(2)).trim(),
                teacher = (if (hasHeaders) col("任课教师") else at(3)).trim(),
                weeks = parseWeeks(weeksText),
                day = (if (hasHeaders) col("星期") else at(6)).trim(),
                room = (if (hasHeaders) col("上课地点") else at(7)).trim()
            )
        }
        return out
    }

    // ------------------------------------------------------------- 表头

    internal fun parseTerm(html: String): TermInfo {
        val titleHtml = HtmlText.extractById(html, "title", "div")
        val titleText = HtmlText.squeeze(HtmlText.plainText(titleHtml ?: ""))
        val whole = HtmlText.squeeze(HtmlText.plainText(html))

        val studentNo = Regex("学生课表\\s*[:：]\\s*([0-9]{4,})")
            .find(titleText)?.groupValues?.get(1)
            ?: Regex("学生课表\\s*[:：]\\s*([0-9]{4,})").find(whole)?.groupValues?.get(1)
            ?: ""

        val yearTerm = Regex("(20\\d{2})\\s*([春秋])").find(titleText)
            ?: Regex("(20\\d{2})\\s*([春秋])").find(whole)

        val className = Regex("班级\\s*[:：]\\s*([^\\s(（]{1,40})")
            .find(whole)?.groupValues?.get(1)?.trim() ?: ""

        return TermInfo(
            year = yearTerm?.groupValues?.get(1) ?: "",
            term = yearTerm?.groupValues?.get(2) ?: "",
            studentNo = studentNo,
            className = className
        )
    }
}
