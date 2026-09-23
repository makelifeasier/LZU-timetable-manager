package app.timetable.data

import java.util.Locale

/**
 * 通用课表解析器：不认识任何教务系统的 id / class 约定，只按「表格长得像课表」来判断。
 *
 * 定位：兰大解析器（TimetableParser）解析不出内容时的兜底。它只依赖三件事：
 *  1. 星期列名（周一/星期一/周1/礼拜一/Mon…）；
 *  2. 行头里的节次号与 HH:MM 时间；
 *  3. 单元格本身像不像一段课。
 *
 * 纯 Kotlin + Kotlin stdlib，不碰 android.* —— 这样它能直接在 JVM 单测里跑。
 * HtmlText 本身是纯函数（它的注释就是这么写的），因此直接复用，不自造一套文本清洗。
 *
 * 已知取舍（宁可抽不到，也不要抽错）：
 *  - 单元格里一行同时写「教室 教师」且中间只用空格分隔时，会把整行当教室；
 *    空格既分隔字段又分隔姓名，无标签时无法可靠区分。
 *  - 纯数字课名的课（少见）会被当成节次行头丢掉。
 */
internal object GenericTableParser {

    // ------------------------------------------------------------------ 正则

    private val TABLE_RE = Regex("(?is)<table\\b[^>]*>(.*?)</table\\s*>")
    private val TR_RE = Regex("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>")
    /** 允许漏写 </td> / 标签串位：配平失败就回退到下一个 <td|th> */
    private val CELL_RE = Regex(
        "(?is)<t([dh])\\b([^>]*)>(.*?)(?:</t\\1\\s*>|(?=<t[dh]\\b)|</tr\\s*>|\\z)"
    )
    private val ROWSPAN_RE = Regex("(?is)\\browspan\\s*=\\s*[\"']?(\\d+)")
    private val COLSPAN_RE = Regex("(?is)\\bcolspan\\s*=\\s*[\"']?(\\d+)")

    private val BR_RE = Regex("(?is)<br\\s*/?>")
    /** (?s) 让 . 吃掉换行；不写 lazy，否则未闭合标签只会剩一个字符 */
    private val TAG_RE = Regex("(?s)<[^>]*>")
    private val SEMI_RE = Regex("[;；|]")

    /** 行头节次：阿拉伯数字的范围写法 `1-2节` / `1、2节` */
    private val SECTION_RANGE_RE = Regex("第?\\s*(\\d{1,2})\\s*[-~～、,，]\\s*(\\d{1,2})\\s*节")
    /** 常见紧凑写法：`0102节` = 第1-2节，`0506节` = 第5-6节 */
    private val COMPACT_SECTION_RE = Regex("(\\d{2})(\\d{2})\\s*节")
    /** 行头就是纯数字：`1`、`2`、`1.`、`2、`（英文课表常见） */
    private val BARE_SECTION_RE = Regex("^(\\d{1,2})\\s*[.、]?$")
    /**
     * 「上午/下午/晚上/中午/清晨/傍晚」开头的行头 —— 它后面的数字是**行内序数**，不是全局节次号。
     * 「中午1节」是当天第 5 段课，把它当成"第 1 节"会与真正的第 1 节撞车（putIfAbsent 不覆盖），
     * 结果是节次数少两段、时间对不上（兰大那份真实页面正好有「中午1节/中午2节」）。
     */
    private val PERIOD_RE = Regex("上午|下午|晚上|中午|清晨|傍晚|早间")
    /** 中文数字节次：「第一节」「第一节至第二节」——很多学校行头就是写中文的 */
    private val CN_SECTION_RE = Regex("第\\s*([一二三四五六七八九十])+\\s*节")
    private val SECTION_FIRST_RE = Regex("(\\d{1,2})")
    private val CN_NUM_RE = Regex("[一二三四五六七八九十]")
    private val CN_NUM_VALUE = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10
    )
    /** 节次行头的特征词：出现它说明这一格是行头，不是课程内容 */
    private val SECTION_HINT_RE = Regex("节|上午|下午|晚上|中午|早|晚|大节")
    /** 节次号必须紧邻「节」字才算数：这样 `1-2节` 能取到范围，而 `0830` 不会被当成 8 */
    private val DIGIT_RUN_RE = Regex("\\d{1,2}")
    /** 时间对必须是 HH:MM（带冒号）。没有冒号的数字不能当时间，否则 "0830" 会误判 */
    private val TIME_RE = Regex("(?<!\\d)(?:[01]?\\d|2[0-3])\\s*[:：]\\s*[0-5]\\d(?!\\d)")
    private val TIME_HM_RE = Regex("(\\d{2}):(\\d{2})")
    private val SEP_NOISE_RE = Regex("[┆│|]")

    /**
     * 课名外壳：`<<高等数学>>;23`、`《高等数学》`、`【高等数学】`、`[高等数学]`。
     * 括号必须成对写死：写成字符类 `[<<...]+` 时，正则会把 `<<` 只吃一个 `<`
     * 然后在第一个 `>` 上收尾，`>>;23` 里的 `>` 会混进课名（回退解析器最怕的就是这个）。
     */
    private val BRACKET_PAIR_RE = Regex("^(?:<<|《|【|\\[)\\s*(.+?)\\s*(?:>>|》|】|\\])")
    private val BRACKET_OPEN_RE = Regex("^[<<《【]\\s*")
    private val NAME_PREFIX_RE = Regex("^[◆●○◇■□▲△※*·•\\-—\\s]+")
    private val TRAILING_SEQ_RE = Regex("[;；]\\s*(\\d{1,6})\\s*$")
    private val TRAILING_PAREN_SEQ_RE = Regex("[（(]\\s*(?:课序号|序号)?\\s*(\\d{1,6})\\s*[)）]\\s*$")

    /** 一行是不是「开头的课名」：带书名号，或带明确字段标签 */
    private val NAME_MARK_RE = Regex("^\\s*(?:[<<《【\\[]|(?:课程名称|课程名|课名|科目)\\s*[:：])")
    /** 一行是不是上一门课的续行（「一字段一行」的排布） */
    private val CONTINUATION_RE = Regex(
        "^\\s*(?:教师|老师|上课教师|任课教师|授课教师|主讲教师|地点|上课地点|教室|授课地点|教学班|周次|上课周次|上课时间|时间)\\s*[:：]"
    )

    private val KEY_CURRICULUM_RE =
        Regex("^\\s*(?:课程名称|课程名|课名|科目|教学班名称)\\s*[:：]?\\s*(.+)$")
    private val KEY_TEACHER_RE =
        Regex("^\\s*(?:任课教师|上课教师|授课教师|主讲教师|教师|老师)\\s*[:：]?\\s*(.+)$")
    private val KEY_ROOM_RE =
        Regex("^\\s*(?:上课地点|授课地点|教室|地点|场地)\\s*[:：]?\\s*(.+)$")
    private val KEY_WEEKS_RE =
        Regex("^\\s*(?:上课周次|周次|节次周次|周数)\\s*[:：]?\\s*(.+)$")

    private val TEACHER_TAIL_RE = Regex("(?:老师|教师|教授)$")
    private val ROOM_HINT_RE = Regex("楼|室|场|馆|院|区|中心|机房|实验|礼堂|操场|教室")
    /** A301 / A-301 / 东A101：字母（可选单个汉字前缀）+ 数字，且数字不超 4 位 */
    private val ROOM_CODE_RE = Regex("^[\\u4e00-\\u9fa5]?[A-Za-z]{1,4}-?\\d{1,4}[A-Za-z]?$")
    private val TEACHER_NAME_RE = Regex("^[A-Za-z]{0,6}[\\u4e00-\\u9fa5]{2,4}$")
    /** 纯英文姓名（Smith / John Smith）—— 中外合作办学与英文课表的教师栏就是这个形态 */
    private val TEACHER_ASCII_RE = Regex("^[A-Za-z]{2,20}(?:\\s+[A-Za-z]{2,20})?$")
    private val TEACHER_LIST_RE = Regex(
        "^\\s*[A-Za-z\\u4e00-\\u9fa5]{2,6}(?:\\s*[,，、]\\s*[A-Za-z\\u4e00-\\u9fa5]{2,6})+\\s*,?\\s*$"
    )
    private val CATEGORY_RE = Regex("学时|学分|讲课|上机|实验|实践|实习|研讨|习题|课程设计|通识|必修|选修")

    private val SPACES_RE = Regex("[\\s\\u3000]+")

    /** 不合法学年的上界：教务系统周次不会超过这个数，超过说明把别的数字当成了周次 */
    private const val MAX_WEEK = 30

    // ------------------------------------------------------------- 表头识别

    private val CN_DIGIT = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7,
        '天' to 7, '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5, '6' to 6, '7' to 7
    )
    private val CN_DAY_RE = Regex("^(?:周|星期|礼拜)\\s*([一二三四五六日天1-7])$")
    private val EN_DAYS = mapOf(
        "monday" to 1, "mon" to 1,
        "tuesday" to 2, "tue" to 2, "tues" to 2,
        "wednesday" to 3, "wed" to 3,
        "thursday" to 4, "thu" to 4, "thur" to 4, "thurs" to 4,
        "friday" to 5, "fri" to 5,
        "saturday" to 6, "sat" to 6,
        "sunday" to 7, "sun" to 7
    )

    // ------------------------------------------------------------ 周次识别

    /** 行里直接出现「1,3,5-9周」「1-16周(单)」时只取这一段，别把「讲课学时」之类的尾巴带进数字解析 */
    private val WEEKS_KEY_RE =
        Regex("\\d{1,2}\\s*(?:[-~～]\\s*\\d{1,2})?(?:\\s*,\\s*\\d{1,2}\\s*(?:[-~～]\\s*\\d{1,2})?)*\\s*周(?:\\s*[（(]?\\s*[单双全]\\s*[)）]?)?")
    /** 没有「周」字时，整行必须纯是周次写法才认（避免把「学时 32」当成第 32 周） */
    private val WEEKED_LINE_RE = Regex("^[\\d\\s,\\-~～、]+$")
    /** 「全周」「每周」这种不带数字的写法 */
    private val NAMED_ALL_WEEKS_RE = Regex("^(?:全|每)\\s*周$|^(?:每周|全周|整周)$")
    private val HAS_DIGIT_RE = Regex("\\d")

    // ------------------------------------------------------------------ 入口

    fun parse(rawHtml: String): ParseResult = runCatching {
        val html = HtmlText.stripNoise(rawHtml)
        val term = parseTerm(html)
        val table = pickTable(html) ?: return ParseResult(term = term)
        val (sections, sessions) = parseGrid(table)
        ParseResult(term, sections, sessions)
    }.getOrElse {
        // 页面可以烂到任何程度；兜底解析器出错不该让调用方崩
        ParseResult()
    }

    // ------------------------------------------------------------------ 选表

    /**
     * 选「最像课表」的表：≥4 个星期列 且 ≥4 行带节次/时间，同分取星期列更多者。
     */
    internal fun pickTable(html: String): String? {
        var best: String? = null
        var bestScore = 0
        for (m in TABLE_RE.findAll(html)) {
            val t = m.groupValues[1]
            val cols = countWeekdayColumns(t)
            if (cols < 4) continue
            val rows = countSectionRows(t)
            if (rows < 4) continue
            val score = cols * 100 + rows
            if (score > bestScore) {
                bestScore = score
                best = t
            }
        }
        return best
    }

    internal fun countWeekdayColumns(table: String): Int {
        var best = 0
        for (tr in TR_RE.findAll(table)) {
            val texts = CELL_RE.findAll(tr.groupValues[1]).map { groupText(it) }.toList()
            if (isWeekdayHeaderRow(texts)) best = maxOf(best, texts.count { dayLabelOf(it) != null })
        }
        return best
    }

    internal fun countSectionRows(table: String): Int {
        var n = 0
        for (tr in TR_RE.findAll(table)) {
            val cells = CELL_RE.findAll(tr.groupValues[1]).toList()
            if (cells.isEmpty()) continue
            if (isWeekdayHeaderRow(cells.map { groupText(it) })) continue
            if (parseSectionRange(cellLines(cells[0].groupValues[3])) != null) n++
        }
        return n
    }

    /**
     * 整行都是星期名 → 天头行。
     *
     * **这里原先是导致"所有页面都解析为空"的元凶**：老判据要求第一格要么是空的、要么本身是星期名，
     * 可真实课表第一格写的是「节次」「时间」「星期」「日期」这类角落标题 ——
     * 于是连兰大自己的页面都被判成"不是课表"，选表阶段就整页放弃了。
     *
     * 现在改成「星期格必须占绝大多数」：允许**最多一个**非空且非星期的格子（就是那个角落），
     * 既认得出真实表头，也不会把数据行误判成表头（数据行里含课程名的格子远多于一个）。
     */
    private fun isWeekdayHeaderRow(texts: List<String>): Boolean {
        val days = texts.count { dayLabelOf(it) != null }
        if (days < 4) return false
        val others = texts.count { it.isNotBlank() && dayLabelOf(it) == null }
        return others <= 1
    }

    // ---------------------------------------------------------------- 网格

    private class Cell(val col: Int, val rowSpan: Int, val html: String)

    private fun parseGrid(tableHtml: String): Pair<List<Section>, List<Session>> {
        val sectionMap = LinkedHashMap<Int, Section>()
        val sessions = ArrayList<Session>()

        val rows = ArrayList<List<Cell>>()
        var covers = IntArray(0)
        for (tr in TR_RE.findAll(tableHtml)) {
            val cells = buildCells(tr.groupValues[1], covers)
            // 结算本行的跨行占用：减到 1 表示下一行还能盖一次
            for (c in covers.indices) if (covers[c] > 1) covers[c]--
            for (c in cells) {
                if (c.col >= covers.size) covers = covers.copyOf(c.col + 1)
                if (c.rowSpan > 1) covers[c.col] = c.rowSpan
            }
            if (cells.isEmpty()) continue
            rows += cells
        }

        val dayOffset = detectDayOffset(rows)

        // **必须剔掉天头行**（周一…周日那一行）：它的角落格写的是「节次」「时间」，
        // 会被当成一个节次行（`SECTION_HINT_RE` 命中「节」）并占用节次号 1 ——
        // 而下面用 putIfAbsent 登记，于是**真正的第 1 节被挤掉**，
        // 表现为第 1 节没有上下课时间、标签变成「节次」。这是解析器最初"什么都没解析出来"的元凶之一。
        val body = rows.filter { cells ->
            !isWeekdayHeaderRow(cells.map { HtmlText.squeeze(HtmlText.plainText(it.html)) })
        }

        // 混用两种行头的页面（兰大：第1节…第4节、中午1节、中午2节、第5节…第12节）一律按**行序**编号。
        // 只对单体行头特殊处理是不够的：「中午1节」按行序落在第 5 位，而后面真正的「第5节」也报 5，
        // 两者撞号（谁覆盖谁都少一段），并且时间会整体错位。整表统一按行序编号后，
        // 与兰大专用解析器的编号方式一致，14 段课都在、时间也对得上。
        val sequential = body.any { cells ->
            val l = cells.firstOrNull { it.col == 0 }?.let { cellLines(it.html) } ?: emptyList()
            l.isNotEmpty() && PERIOD_RE.containsMatchIn(l.first())
        }

        // 第一遍：行头 → sections（节次号 + 上课时间）
        var cursor = 0
        val startSec = IntArray(body.size)
        val endSec = IntArray(body.size)
        for ((i, cells) in body.withIndex()) {
            val lines = cells.firstOrNull { it.col == 0 }?.let { cellLines(it.html) } ?: emptyList()
            // 「上午/下午/中午…」开头的行头按行序推进（见 PERIOD_RE 注释），优先级高于其中的数字
            val period = lines.isNotEmpty() && PERIOD_RE.containsMatchIn(lines.first())
            val span = if (sequential || period) null else
                lines.takeIf { it.isNotEmpty() }?.let { parseSectionRange(it) }
            val hinted = span == null && lines.isNotEmpty() && SECTION_HINT_RE.containsMatchIn(lines[0])
            val a: Int
            val b: Int
            when {
                span != null -> {
                    a = span.first; b = span.second; cursor = b
                }
                // 行头是「中午1节」「上午第一节」这类：数字是行内计数，不是全局节次号，必须按行序推进
                hinted || period -> {
                    a = ++cursor; b = a
                }
                // 没有行头（或行头是空的）：按物理行序推进
                else -> {
                    a = ++cursor; b = a
                }
            }
            startSec[i] = a
            endSec[i] = b
            val label = sectionLabel(lines, a)
            val explicit = span != null
            // **显式节次号要覆盖游标猜出来的**：有的页面同时用两种行头
            // （兰大真实页面：第1节…第4节、中午1节、中午2节、第5节…第12节）。
            // 「中午1节」按行序猜成第 5 节，随后真正的「第5节」必须能把它顶掉；
            // 若两者都用 putIfAbsent，真正的第 5、6 节会被静默丢弃 —— 14 段课只剩 12 段。
            val head = Section(a, label, timeAt(lines, 0), timeAt(lines, 1))
            if (explicit) sectionMap[a] = head else sectionMap.putIfAbsent(a, head)
            for (k in a + 1..b) {
                // 跨节行头（`0102节 08:00-09:35`）：这几节共用同一段时间。
                // 关键是**末节也要有 end** —— App 算一堂课的下课时间用的是「结束节次的 end」，
                // 只给首节写 end 的话，跨节大课会显示成「08:00–」（真机上是空白）。
                val s = Section(k, "第${k}节", timeAt(lines, 0), timeAt(lines, 1))
                if (explicit) sectionMap[k] = s else sectionMap.putIfAbsent(k, s)
            }
        }

        // 第二遍：单元格 → sessions（rowspan 决定 endSection）
        for ((i, cells) in body.withIndex()) {
            for (cell in cells) {
                if (cell.col < dayOffset) continue
                val day = cell.col - dayOffset + 1
                if (day < 1 || day > 7) continue
                val lines = cellLines(cell.html)
                if (lines.isEmpty()) continue
                val flat = HtmlText.squeeze(HtmlText.plainText(cell.html))
                if (flat.isEmpty() || dayLabelOf(flat) != null || TIME_RE.matches(flat)) continue
                if (SECTION_HINT_RE.containsMatchIn(flat) && parseSectionRange(lines) != null) continue
                val last = minOf(i + maxOf(1, cell.rowSpan) - 1, body.size - 1)
                val end = if (last > i) maxOf(endSec[i], endSec[last]) else endSec[i]
                sessions += parseCellSessions(lines, day, startSec[i], maxOf(startSec[i], end))
            }
        }

        return sectionMap.values.sortedBy { it.index } to mergeAdjacent(sessions)
    }

    /**
     * 把「同一门课、同一天、节次相邻」的段并成一条。
     *
     * 有的学校一个格子只写一节（第一节一格，第二节再写一遍同一门课），
     * 不合并的话课表会变成一堆散段；合并后 1-2 节显示成一条，与兰大「大节课表」的观感一致。
     * 判据用 Session.key（课名+课序号+教室+教师+周次+类型），所以换老师/换教室/换周次不会误并。
     */
    private fun mergeAdjacent(list: List<Session>): List<Session> {
        // **必须先按 (课程, 星期) 分组，不能只比较"列表里相邻的两个"**：
        // 解析是**按表格行**产出 Session 的，一行里有多个星期列，
        // 于是同一门课的两节之间会夹着别的天的课（周一第一节、周二第一节、周一第二节…），
        // 按相邻元素比较时它们根本不相邻 —— 一门 1-2 节的大课会显示成两条（真机表现：重复课）。
        val groups = LinkedHashMap<String, MutableList<Session>>()
        for (s in list) groups.getOrPut("${s.key}|${s.day}") { ArrayList() } += s

        val out = ArrayList<Session>()
        for (group in groups.values) {
            var cur: Session? = null
            for (s in group.sortedBy { it.startSection }) {
                val prev = cur
                if (prev != null && s.startSection <= prev.endSection + 1) {
                    val merged = prev.copy(endSection = maxOf(prev.endSection, s.endSection))
                    out[out.size - 1] = merged
                    cur = merged
                } else {
                    out += s
                    cur = s
                }
            }
        }
        // 输出顺序固定为「按星期、再按节次」，与页面顺序无关，便于测试与显示
        return out.sortedWith(compareBy({ it.day }, { it.startSection }))
    }

    /** 把一行的单元格展开到网格列：被上方 rowspan 占住的列整格跳过（不新建单元格） */
    private fun buildCells(rowHtml: String, covers: IntArray): List<Cell> {
        val out = ArrayList<Cell>()
        var col = 0
        for (m in CELL_RE.findAll(rowHtml)) {
            // covers[col] > 1 表示上方 rowspan 还在盖这一列，本行的这个格子不存在
            while (col < covers.size && covers[col] > 1) col++
            val attrs = m.groupValues[2]
            val rowSpan = maxOf(1, ROWSPAN_RE.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 1)
            val colSpan = maxOf(1, COLSPAN_RE.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 1)
            out += Cell(col, rowSpan, m.groupValues[3])
            col += colSpan
        }
        return out
    }

    private fun groupText(m: MatchResult): String = HtmlText.squeeze(HtmlText.plainText(m.groupValues[3]))

    /** 数据列偏移 = 星期列名出现的最小网格列；没有天头行时靠首列是不是行头来判断 */
    private fun detectDayOffset(rows: List<List<Cell>>): Int {
        for (cells in rows) {
            val dayCols = cells.mapNotNull { c -> dayLabelOf(HtmlText.plainText(c.html))?.let { c.col } }
            if (dayCols.isNotEmpty()) return dayCols.min()
            val head = cells.firstOrNull { it.col == 0 } ?: continue
            val lines = cellLines(head.html)
            val looksLikeHead = lines.isEmpty() ||
                SECTION_HINT_RE.containsMatchIn(lines[0]) ||
                TIME_RE.containsMatchIn(lines[0])
            return if (looksLikeHead) 1 else 0
        }
        return 0
    }

    // ------------------------------------------------------------ 节次行头

    /** 行头 → (起始节, 结束节)。认不出返回 null */
    private fun parseSectionRange(lines: List<String>): Pair<Int, Int>? {
        if (lines.isEmpty()) return null
        val text = lines.joinToString(" ").replace('：', ':')
        SECTION_RANGE_RE.find(text)?.let { m ->
            val a = m.groupValues[1].toIntOrNull()
            val b = m.groupValues[2].toIntOrNull()
            if (a != null && b != null && a in 1..20 && b in 1..20) return minOf(a, b) to maxOf(a, b)
        }
        // 紧凑写法必须排在通用数字分支之前：`0102节` 走下面会只取到 "01"，
        // 而且因为下一个字符不是「节」而被判为"不是节次行"，整行时间也就丢了。
        COMPACT_SECTION_RE.find(text)?.let { m ->
            val a = m.groupValues[1].toIntOrNull()
            val b = m.groupValues[2].toIntOrNull()
            if (a != null && b != null && a in 1..20 && b in 1..20) return minOf(a, b) to maxOf(a, b)
        }
        // 行头就是一个纯数字（英文课表常写「1」「2」）：只在首 token 完全是数字时才认，
        // 免得把 `08:00` 这类时间行头当成节次
        BARE_SECTION_RE.find(lines.first().trim())?.let { m ->
            val v = m.groupValues[1].toIntOrNull()
            if (v != null && v in 1..20) return v to v
        }
        // 「第一节」：中文数字节次，必须排在阿拉伯分支之前，
        // 否则 SECTION_FIRST_RE 会从「第1节」里取到数字，却在「第一节」上什么也取不到
        if (CN_SECTION_RE.containsMatchIn(text)) {
            val num = parseChineseNumber(text)
            if (num != null && num in 1..20) return num to num
        }
        // 数字必须紧挨着「节」才当节次号：`0830`（无冒号的 08:30）不能读成第 8 节
        val run = DIGIT_RUN_RE.find(text) ?: return null
        val v = run.value.toIntOrNull() ?: return null
        if (text.getOrNull(run.range.last + 1) != '节') return null
        if (v !in 1..20) return null
        return v to v
    }

    /**
     * 「第一节」→ 1、「第十一节」→ 11、「第二十节」→ 20。
     * 学校行头只用到二十以内，所以按「十位+个位」的规则手写，不引整套中文数字库。
     */
    private fun parseChineseNumber(text: String): Int? {
        val m = CN_SECTION_RE.find(text) ?: return null
        val raw = CN_NUM_RE.findAll(m.value).map { it.value }.toList()
        if (raw.isEmpty()) return null
        val digits = raw.map { CN_NUM_VALUE[it.first()] ?: return null }
        return when {
            digits.size == 1 -> digits[0]
            digits.first() == 10 -> 10 + digits.drop(1).sum()      // 十一…十九
            digits.last() == 10 -> digits.dropLast(1).sum() * 10   // 二十、三十
            else -> null
        }
    }

    private fun sectionLabel(lines: List<String>, index: Int): String {
        for (line in lines) {
            val s = pickLabelPart(line)
            if (s.isNotEmpty()) return s
        }
        return "第${index}节"
    }

    /** 行头里去掉时间与分隔符后剩下的就是标签本身（「第1节」「第一节」「上午第一节」） */
    private fun pickLabelPart(line: String): String {
        var s = SEP_NOISE_RE.replace(line.replace('：', ':'), " ")
        s = s.replace(TIME_RE, " ").replace(Regex("\\s*[-~～]\\s*$"), " ")
        s = HtmlText.squeeze(s)
        return if (s.length in 1..24) s else ""
    }

    private fun timeAt(lines: List<String>, ordinal: Int): String {
        val all = ArrayList<String>()
        for (line in lines) {
            for (m in TIME_HM_RE.findAll(line.replace('：', ':'))) {
                val h = m.groupValues[1].toInt()
                val min = m.groupValues[2].toInt()
                if (h in 0..23 && min in 0..59) {
                    all += h.toString().padStart(2, '0') + ":" + min.toString().padStart(2, '0')
                }
            }
        }
        return all.getOrElse(ordinal) { "" }
    }

    // ------------------------------------------------------ 单元格 → 课程

    /**
     * 单元格文本行 → 若干 Session。多门课/多段用 <br>/<wbr>/;/空行分隔。
     *
     * 分段必须是「先按行、再按分号」：`<<课程名>>;23` 里的分号属于课名行本身，
     * 先按分号切会把课名和课序号劈成两条，导致 seqNo 丢失、后面所有字段错位。
     */
    private fun parseCellSessions(
        lines: List<String>,
        day: Int,
        startSection: Int,
        endSection: Int
    ): List<Session> {
        val blocks = ArrayList<MutableList<String>>()
        var current: MutableList<String>? = null
        var prevLineSeen = false
        for (line in lines) {
            // **首行（课名行）整行保留，不按分号切**：`<<数据结构>>;2` 里那个分号后面
            // 跟的就是课序号，切开会得到两行（`<<数据结构>>` 和 `2`），课序号丢了不算，
            // 那个孤零零的「2」还会被当成周次（`2` → 2-2周），把真正的周次覆盖掉。
            val startsNew = !prevLineSeen || current == null || isBlockStart(line)
            val pieces = if (startsNew) {
                listOf(line)
            } else {
                SEMI_RE.split(line)
            }.map { HtmlText.squeeze(it) }.filter { it.isNotEmpty() }
            if (pieces.isEmpty()) {
                current = null          // 空行 = 分段边界
                prevLineSeen = false
                continue
            }
            if (startsNew) {
                current = ArrayList()
                blocks += current!!
            }
            current!!.addAll(pieces)
            prevLineSeen = true
        }
        return blocks.mapNotNull { toSession(it, day, startSection, endSection) }
    }

    /**
     * 这一行是不是**新课的开始**。
     *
     * 只认"课名标记"（`<<>>`/《》/【】 或「课程名称：」前缀）。
     * **不能把「教师：」「地点：」「周次：」算成新块的开头** —— 那是 *续行* 标记。
     * 原来把它们也当块开头，「一行一个字段」的单元格就被拆成了 4 门课：
     * 课名一门、教师一门、地点一门、周次一门（课名/教室/教师全空，整页看起来就是解析失败）。
     */
    private fun isBlockStart(line: String): Boolean = NAME_MARK_RE.containsMatchIn(line)

    private fun toSession(
        lines: List<String>,
        day: Int,
        startSection: Int,
        endSection: Int
    ): Session? {
        val raw = NAME_PREFIX_RE.replace(lines.first().trim(), "")
        if (raw.isEmpty()) return null
        if (dayLabelOf(raw) != null || TIME_RE.matches(raw)) return null

        val name: String
        var seqNo = ""
        var consumed = 0
        val pairs = BRACKET_PAIR_RE.find(raw)
        if (pairs != null) {
            name = pairs.groupValues[1].trim()
            val after = raw.substring(pairs.range.last + 1).trim()
            seqNo = TRAILING_SEQ_RE.find(after)?.groupValues?.get(1)?.trim()
                ?: TRAILING_PAREN_SEQ_RE.find(after)?.groupValues?.get(1)?.trim()
                ?: ""
            consumed = 1
        } else if (BRACKET_OPEN_RE.containsMatchIn(raw)) {
            // 只有左书名号（不合法但常见）：砍掉开头一个括号再取
            name = NAME_PREFIX_RE.replace(BRACKET_OPEN_RE.replace(raw, ""), "").trim()
            consumed = 1
        } else {
            val m = KEY_CURRICULUM_RE.find(raw)
            name = (m?.groupValues?.get(1) ?: raw).trim()
            // **课名那一行必须算"已消费"**：多数页面的课名就是裸文本，
            // 没有 `<<>>` 也没有「课程名称：」前缀。不消费的话它会留在 rest 里，
            // 而「高等数学」这种 4 个汉字**正好命中教师正则**（2~4 个汉字），
            // 于是教师字段变成课名（真机表现：教师一栏显示课程名）。
            consumed = 1
        }
        if (name.isEmpty() || SECTION_FIRST_RE.matches(name)) return null

        val rest = lines.drop(consumed)

        // 周次：第一个能解析出周次的行（含「周次：」字段行）
        var weeks = WeekSpan.UNKNOWN
        var weekIdx = -1
        for ((i, line) in rest.withIndex()) {
            val w = parseWeeks(line)
            if (w != null) {
                weeks = w; weekIdx = i; break
            }
            val key = KEY_WEEKS_RE.find(line)
            if (key != null) {
                val w2 = parseWeeks(key.groupValues[1])
                if (w2 != null) {
                    weeks = w2; weekIdx = i; break
                }
            }
        }

        var category = ""
        var room = ""
        var teacher = ""
        for ((i, line) in rest.withIndex()) {
            if (i == weekIdx) continue
            if (category.isEmpty() && CATEGORY_RE.containsMatchIn(line)) {
                category = line
                continue
            }
            val keyRoom = KEY_ROOM_RE.find(line)
            if (room.isEmpty() && keyRoom != null) {
                room = keyRoom.groupValues[1].trim(); continue
            }
            val keyTeacher = KEY_TEACHER_RE.find(line)
            if (teacher.isEmpty() && keyTeacher != null) {
                teacher = keyTeacher.groupValues[1].trim(); continue
            }
            if (KEY_CURRICULUM_RE.containsMatchIn(line)) continue
            // 字段顺序并不统一（课名/教师/周次/教室 与 课名/教室/教师/周次 都见过），
            // 所以这里不能靠「第几行」判断，必须按内容分别试，且两者互不抢占
            if (room.isEmpty() && looksLikeRoom(line)) {
                room = line; continue
            }
            if (teacher.isEmpty() && looksLikeTeacher(line)) teacher = line
        }

        return Session(
            name = name,
            seqNo = seqNo,
            room = room,
            teacher = teacher,
            weeks = weeks,
            category = category,
            day = day,
            startSection = startSection,
            endSection = maxOf(startSection, endSection)
        )
    }

    // ------------------------------------------------- 无标签单行字段判定

    private fun looksLikeRoom(s: String): Boolean {
        val t = s.trim().replace(TEACHER_TAIL_RE, "")
        if (t.isEmpty()) return false
        if (ROOM_HINT_RE.containsMatchIn(t)) return true
        if (ROOM_CODE_RE.matches(t)) return true          // A101、逸夫楼A101 之外的「东A101」也已覆盖
        // 「东区运动场」这类带数字的中文地名：数字让它不可能是纯姓名
        return t.length >= 3 && t.any { it.isDigit() } && !TEACHER_NAME_RE.matches(t)
    }

    private fun looksLikeTeacher(s: String): Boolean {
        val t = SPACES_RE.replace(s.trim(), "")
        if (t.isEmpty()) return false
        if (t.any { it.isDigit() } || ROOM_HINT_RE.containsMatchIn(t)) return false
        if (TEACHER_LIST_RE.matches(s.trim())) return true
        if (TEACHER_ASCII_RE.matches(t)) return true
        return TEACHER_NAME_RE.matches(t)
    }

    // ---------------------------------------------------------------- 周次

    /**
     * 比 TimetableParser.parseWeeks 更宽：支持 `1,3,5-9周`、`1~16周`、`第1-16周`、
     * `1-16周(单)`、`1-16(周)`、`全周`。完全读不出周次返回 null（调用方用 UNKNOWN）。
     *
     * 注意：整个字段行（如 `2-16周双周` 后面还挂着 `实验学时`）会一起传进来，
     * 所以必须先定位「周次片段」再解析，不能对整个行做数字替换——
     * 否则「学时」里的「学」被替换掉之后 `toIntOrNull()` 会直接失败。
     */
    internal fun parseWeeks(text: String): WeekSpan? {
        val src = HtmlText.squeeze(text)
        if (src.isEmpty()) return null

        // 先摘出周次片段；整行就是「数字[符]数字」时也接受（有的系统周次列不带「周」字）
        val fragment = when {
            // 「全周」「每周」这类**没有数字**的写法要最先判：它也含「周」字，
            // 排在下面会被 WEEKS_KEY_RE 抓不到、然后走"去掉周字后为空 → null"那条路（原来就是如此）
            NAMED_ALL_WEEKS_RE.matches(src) -> return WeekSpan.UNKNOWN
            src.contains("周") -> WEEKS_KEY_RE.find(src)?.value ?: src
            // 不带「周」字时必须**看得出是区间或列表**（`1-16`、`1,3,5-9`）。
            // 只给一个裸数字的行不能算周次：单元格里到处是孤立数字（课序号、节次、学分），
            // 认成周次会把真正的周次覆盖掉（真实故障：`<<数据结构>>;2` 里的 2 变成了「2-2周」）。
            WEEKED_LINE_RE.matches(src) && HAS_DIGIT_RE.containsMatchIn(src) &&
                src.any { it == '-' || it == '~' || it == '～' || it == ',' || it == '，' } -> src
            else -> return null
        }

        // 单/双在剥掉「周」之后再判定：这样「第十周」里的「十」不会在替换前被当成双周
        var s = fragment.replace("第", "").replace("周", "")
        s = s.replace("全", "")
        val parity = when {
            s.contains("单") -> Parity.ODD
            s.contains("双") -> Parity.EVEN
            else -> Parity.NONE
        }
        s = s.replace("单", "").replace("双", "")
        s = s.replace(Regex("[\\s()（）.。:：]"), "")
        if (s.isEmpty()) return null

        val nums = ArrayList<Int>()
        for (part in s.split(",").filter { it.isNotBlank() }) {
            val bounds = part.split(Regex("[-~～、]")).filter { it.isNotBlank() }
            if (bounds.isEmpty()) return null
            for (bound in bounds) {
                val v = bound.toIntOrNull() ?: return null
                if (v < 1 || v > MAX_WEEK) return null
                nums += v
            }
        }
        if (nums.isEmpty()) return null
        val lo = nums.min()
        val hi = nums.max()

        return when (parity) {
            Parity.NONE -> WeekSpan(lo, hi, Parity.NONE)
            // 单/双周把边界对齐到对应奇偶，`2-16周双周` 的 2 才是真实起点
            Parity.ODD -> {
                val a = if (lo % 2 == 1) lo else lo + 1
                val b = if (hi % 2 == 1) hi else hi - 1
                if (a <= b) WeekSpan(a, b, Parity.ODD) else WeekSpan(lo, hi, Parity.ODD)
            }
            Parity.EVEN -> {
                val a = if (lo % 2 == 0) lo else lo + 1
                val b = if (hi % 2 == 0) hi else hi - 1
                if (a <= b) WeekSpan(a, b, Parity.EVEN) else WeekSpan(lo, hi, Parity.EVEN)
            }
        }
    }

    // ---------------------------------------------------------------- term

    private fun parseTerm(html: String): TermInfo {
        val titleHtml = HtmlText.extractById(html, "title", "div")
        val title = titleHtml?.let { HtmlText.squeeze(HtmlText.plainText(it)) } ?: ""
        val titleTag = Regex("(?is)<title\\b[^>]*>(.*?)</title\\s*>")
            .find(html)?.groupValues?.get(1)?.let { HtmlText.squeeze(HtmlText.plainText(it)) } ?: ""
        val head = HtmlText.squeeze(HtmlText.plainText(html.take(20000)))

        val studentNo = pick(Regex("学生课表\\s*[:：]\\s*([0-9]{4,})"), title, titleTag, head)
            ?.groupValues?.get(1) ?: ""
        val className = pick(Regex("班级\\s*[:：]\\s*([^\\s(（]{1,40})"), title, head)
            ?.groupValues?.get(1)?.trim() ?: ""

        // 「2026 秋」「2026-2027学年第一学期」「2026年秋季学期」都要能出年份
        pick(Regex("(20\\d{2})\\s*(?:[-~～—]\\s*20\\d{2}\\s*学年)?\\s*([春秋夏冬])"), title, head)
            ?.let {
                return TermInfo(
                    year = it.groupValues[1].trim(),
                    term = it.groupValues[2].trim(),
                    studentNo = studentNo,
                    className = className
                )
            }

        // 只写了「2026-2027学年第一学期」：年份可测，季节只能留空
        pick(Regex("(20\\d{2})\\s*[-~～—]\\s*20\\d{2}\\s*学年"), title, head)
            ?.let {
                return TermInfo(
                    year = it.groupValues[1].trim(),
                    term = "",
                    studentNo = studentNo,
                    className = className
                )
            }

        val season = pick(Regex("(20\\d{2})[^0-9]{0,6}([春秋夏冬])"), title, head)
        return TermInfo(
            year = season?.groupValues?.get(1)?.trim().orEmpty(),
            term = season?.groupValues?.get(2)?.trim().orEmpty(),
            studentNo = studentNo,
            className = className
        )
    }

    private fun pick(re: Regex, vararg texts: String): MatchResult? {
        for (t in texts) {
            if (t.isEmpty()) continue
            re.find(t)?.let { return it }
        }
        return null
    }

    // ---------------------------------------------------------------- 杂项

    /** 单元格内容 → 有效文本行。<br>/<wbr> 切行、剥标签、解码实体、去空行 */
    private fun cellLines(cellHtml: String): List<String> {
        val withBreaks = BR_RE.replace(cellHtml, "\n")
        val noTags = TAG_RE.replace(withBreaks, "")
        return HtmlText.decode(noTags)
            .split('\n')
            .map { HtmlText.squeeze(it.replace('\u00A0', ' ')) }
            .filter { it.isNotEmpty() }
    }

    private fun dayLabelOf(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        CN_DAY_RE.find(t)?.let { m -> return CN_DIGIT[m.groupValues[1].first()] }
        return EN_DAYS[t.lowercase(Locale.ROOT).trimEnd('.', '：', ':')]
    }
}
