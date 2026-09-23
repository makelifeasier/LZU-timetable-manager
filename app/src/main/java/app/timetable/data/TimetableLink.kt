package app.timetable.data

/**
 * 「自动发现本人的课表链接」的纯逻辑层。
 *
 * 为什么需要它：教务系统课表页长这样 ——
 *   /academic/manager/coursearrange/showTimetable.do?id=<学籍内部号>&yearid=<学年>&termid=<学期>&…
 * 其中 `id` **每个学生都不一样**，`yearid/termid` **每学期都变**。
 * 把任何一条硬编码进 App，其他同学装上就打不开自己的课表。
 *
 * 所以登录后先去门户页面上把这条链接扫出来。本文件不碰 WebView，只做字符串处理，
 * 因此可以纯 JVM 单测（也刻意不依赖 org.json）。
 */
object TimetableLink {

    const val ORIGIN = "https://jwk.lzu.edu.cn"
    const val COURSEARRANGE_DIR = "/academic/manager/coursearrange/"
    const val PATH = "/academic/manager/coursearrange/showTimetable.do"

    /**
     * 课表页的路径特征（**放宽过，为了抗改版**）。
     *
     * 原来这里只认 `showTimetable.do?…` —— 那是清华同方（兰大）的叫法。
     * 别家各有各的名字，只认一个字符串等于"非兰大学校一条候选都留不下"，
     * 自动发现必然失败（选完学校、登录成功，却永远找不到课表）：
     *
     *   showTimetable   清华同方（兰大）
     *   xskbcx / kbcx   `…/kbcx/xskbcx_cxXsKb.html`
     *   xskb_list       `…/xskb/xskb_list.do`
     *   coursetableforstd / coursetable  `…/courseTableForStd!courseTable.action`
     *   kbList / xskb   其它常见叫法
     *
     * 刻意保持**宽松**：多留几条候选不会出错（下面 [pick] 会按参数打分挑最像的），
     * 而漏掉一条就是"这所学校用不了"。
     */
    val TIMETABLE_HINTS = listOf(
        "showtimetable", "xskbcx", "xskb_list", "xskb", "kbcx", "kbList",
        "coursetableforstd", "coursetable", "kbcxlist"
    )

    /**
     * 一条链接看起来像不像课表页。
     *
     * 与 [TIMETABLE_HINTS] 同源 —— 注入脚本也用它（脚本里硬写一份的话，两边会漂移，
     * 表现是"扫描脚本找不到候选、按文字点菜单却能成功"这种半通不通的状态）。
     */
    fun looksLikeTimetable(url: String): Boolean {
        val low = url.lowercase()
        return TIMETABLE_HINTS.any { low.contains(it.lowercase()) }
    }

    /**
     * 页面全文兜底扫描：抓课表页链接，直到引号/空白/尖括号。
     *
     * 前缀 `[:/\w.\-]*` 负责**从路径开头（乃至整条绝对网址）开始匹配**，而不是从关键词中间开始。
     * 它必须包含 `:` 和 `/`：否则跨不过 `http://host:port/` 这一段，
     * 匹配会退化成从端口号开始，抓出 `8080/a/b/c_…?…` 这种缺协议缺主机的残片。
     */
    private val HTML_RE = Regex(
        """[:/\w.\-]*(?:${TIMETABLE_HINTS.joinToString("|") { Regex.escape(it) }})""" +
            """[^"'\s<>\\]*\?[^"'\s<>\\]+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 发现脚本的哨兵值。
     *
     * 注入脚本一旦有语法错误，WebView 会静默返回空 —— 看上去和「页面上真的没有课表入口」
     * 一模一样，故障极难定位（真实发生过：正则转义写错，于是永远走兜底）。
     * 让脚本把哨兵作为第一个元素返回，就能把这两种情况区分开。
     */
    const val SENTINEL = "DISCOVERY-V1"

    /** 自动发现的结果：脚本到底有没有跑成功 + 候选链接 */
    data class Discovery(val ran: Boolean, val urls: List<String>)

    /** 解析发现脚本的返回值，识别哨兵 */
    fun parseDiscovery(raw: String?): Discovery {
        val all = parseJsArray(raw)
        val ran = all.firstOrNull() == SENTINEL
        return Discovery(ran, if (ran) all.drop(1) else all)
    }

    /** HTML 实体还原。链接在 <a href> 里常常写成 &amp; */
    fun unescape(u: String): String = u
        .replace("&amp;", "&")
        .replace("&#38;", "&")
        .replace("&#x26;", "&")
        .replace("&quot;", "\"")

    /** 相对路径补全成绝对链接 */
    fun absolutize(u: String, base: String = ORIGIN): String {
        val s = u.trim()
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> base + s
            s.isEmpty() -> s
            // 页面里常见裸写 showTimetable.do?…（相对当前目录）
            s.contains("showTimetable.do") -> base + COURSEARRANGE_DIR + s
            else -> base + "/" + s
        }
    }

    /**
     * 从 URL 取「协议://主机[:端口]」。用于让兜底链接跟随**配置的**门户，
     * 而不是写死一个域名。
     */
    fun originOf(url: String): String {
        val s = url.trim()
        val i = s.indexOf("://")
        if (i < 0) return ORIGIN
        val scheme = s.substring(0, i)
        var rest = s.substring(i + 3)
        rest = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (rest.isBlank()) return ORIGIN
        return "$scheme://$rest"
    }

    /** 取 query 参数 */
    fun param(url: String, key: String): String? {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return null
        for (pair in q.split('&')) {
            val k = pair.substringBefore('=', "")
            if (k == key) return pair.substringAfter('=', "")
        }
        return null
    }

    /**
     * 解析 `evaluateJavascript` 返回的 JSON 数组。
     * 形如 `["http://a","http://b"]`、`[]`，也可能带包裹引号或返回 null。
     */
    fun parseJsArray(raw: String?): List<String> {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "null" || s == "undefined") return emptyList()

        var body = s
        // evaluateJavascript 若拿到的是**字符串**，会再包一层引号并把内部引号转义：
        //   "[\"http://a\",\"http://b\"]"
        // 必须先按 JSON 字符串反转义，否则内层 \" 会被当成真的引号而串到下一项上。
        if (body.length >= 2 && body.startsWith("\"") && body.endsWith("\"")) {
            body = body.substring(1, body.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        if (body.startsWith("[")) body = body.substring(1)
        if (body.endsWith("]")) body = body.dropLast(1)

        val out = ArrayList<String>()
        var i = 0
        while (i < body.length) {
            if (body[i] != '"') { i++; continue }
            val sb = StringBuilder()
            i++
            while (i < body.length && body[i] != '"') {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    when (val n = body[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'u' -> {
                            val hex = body.substring(i + 2, minOf(i + 6, body.length))
                            val code = hex.toIntOrNull(16)
                            if (code != null && hex.length == 4) { sb.append(code.toChar()); i += 4 }
                            else { sb.append('\\'); }
                        }
                        else -> sb.append(n)
                    }
                    i += 2
                } else {
                    sb.append(c); i++
                }
            }
            out.add(sb.toString())
            i++
        }
        return out
    }

    /** 页面全文兜底扫描到的候选链接 */
    fun scanHtml(html: String): List<String> =
        HTML_RE.findAll(html).map { it.value }.toList()

    /**
     * 归一化候选：补绝对路径 + 还原实体。
     *
     * `origin` 必须是**门户自身的** scheme://host[:port]：门户里的 href 多为根相对路径
     * （/academic/…），若按写死的域名补全，学校换域名后就会去请求一个错的站点。
     */
    fun normalize(candidates: List<String>, origin: String = ORIGIN): List<String> =
        candidates.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { unescape(it) }
            .map { absolutize(it, origin) }
            // 原来这里是 contains("showTimetable") —— 只认兰大那套叫法，
            // 只认一个旧字符串的话，学校一改路径这条候选就会被整条丢掉，见 TIMETABLE_HINTS。
            .filter { looksLikeTimetable(it) }
            .distinct()
            .toList()

    /**
     * 从候选里挑最合适的一条。
     *
     * 优先「学生 · 小节课表（BASE）」：本 App 解析的是小节时间，大节课表(COMBINE)列不出来。
     * 其次「学生 · 任意」，最后任何含 showTimetable 的链接。
     */
    fun pick(candidates: List<String>, origin: String = ORIGIN): String? {
        val uniq = normalize(candidates, origin)
        if (uniq.isEmpty()) return null

        fun score(u: String): Int {
            var s = 0
            if (param(u, "timetableType") == "STUDENT") s += 100
            if (param(u, "sectionType") == "BASE") s += 50
            if (param(u, "id").orEmpty().isNotBlank()) s += 10
            if (param(u, "yearid").orEmpty().isNotBlank()) s += 5
            if (param(u, "termid").orEmpty().isNotBlank()) s += 5
            // 别家的"本人课表"强特征：门户里往往还有"班级课表/课表打印/空教室查询"之类，
            // 给这几条加分，避免挑中隔壁那些（兰大那套参数加分仍然最高，不受影响）。
            val low = u.lowercase()
            if (low.contains("xskbcx") || low.contains("coursetableforstd") ||
                low.contains("xskb_list") || low.contains("showtimetable")
            ) s += 30
            return s
        }

        // 同分时保留页面里先出现的（门户菜单顺序通常就是对的）
        var best: String? = null
        var bestScore = -1
        for (u in uniq) {
            val sc = score(u)
            if (sc > bestScore) { bestScore = sc; best = u }
        }
        return best
    }

    /**
     * 一条候选都没有时的兜底链接：把 id 去掉，只带页面上见过的 yearid/termid。
     * 很多教务系统会按会话里当前登录的学生解析课表，所以去掉 id 往往仍能打开。
     *
     * @param origin 门户来源（scheme://host[:port]）。跟随配置的门户，
     *               避免学校换域名后兜底指向一个已经不存在的地址。
     */
    fun fallback(candidates: List<String>, origin: String = ORIGIN): String {
        val uniq = normalize(candidates, origin)
        val yearId = uniq.firstNotNullOfOrNull { param(it, "yearid")?.takeIf { v -> v.isNotBlank() } }
        val termId = uniq.firstNotNullOfOrNull { param(it, "termid")?.takeIf { v -> v.isNotBlank() } }

        val parts = ArrayList<String>()
        if (yearId != null) parts.add("yearid=$yearId")
        if (termId != null) parts.add("termid=$termId")
        parts.add("timetableType=STUDENT")
        parts.add("sectionType=BASE")
        return origin + PATH + "?" + parts.joinToString("&")
    }

    /**
     * 整个发现流程对外的答案：优先挑到的那条，挑不到就退回兜底链接。
     */
    fun resolve(candidates: List<String>, origin: String = ORIGIN): String =
        pick(candidates, origin) ?: fallback(candidates, origin)

    /**
     * 这条链接值不值得真的去请求一次？
     *
     * **真机实测教训**：兜底链接里没有 `yearid` 时，兰大教务系统会返回一张
     * `<title>提示信息</title> … 学年传递错误 …` 的页面 —— 这种请求注定失败。
     * 那就不该白发一次请求、再拿一张看不懂的错误页去烦用户。
     *
     * 只要链接带着 `yearid`（从页面扫到的，或从上次成功导入的链接继承来的），就值得一试。
     */
    fun hasYearParam(url: String): Boolean = !param(url, "yearid").isNullOrBlank()
}
