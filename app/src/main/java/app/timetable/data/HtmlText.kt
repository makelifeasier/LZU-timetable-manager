package app.timetable.data

/**
 * HTML 文本工具。全部为纯函数，不依赖 Android，便于 JVM 单测。
 *
 * 针对兰大教务系统页面的两个坑：
 *  1. 浏览器保存页面时注入的 <wbr>（Firefox 自动换行）会把「999999999999」切成
 *     「9999999999<wbr>99」，必须剥离后再做正则。
 *  2. 保存版页面尾部会附带浏览器扩展注入的 <div id="immersive-translate-popup">，
 *     体积大且含 SVG，解析时应忽略。
 */
object HtmlText {

    private val ENTITIES: Map<String, String> = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"",
        "apos" to "'", "ldquo" to "\u201C", "rdquo" to "\u201D", "lsquo" to "\u2018",
        "rsquo" to "\u2019", "hellip" to "\u2026", "mdash" to "\u2014", "ndash" to "\u2013",
        "middot" to "\u00B7", "times" to "\u00D7", "copy" to "\u00A9", "brvbar" to "\u00A6"
    )

    private val WBR_RE = Regex("(?i)<wbr\\s*/?>")
    private val SCRIPT_RE = Regex("(?is)<script\\b.*?</script\\s*>")
    private val STYLE_RE = Regex("(?is)<style\\b.*?</style\\s*>")
    private val COMMENT_RE = Regex("(?s)<!--.*?-->")
    private val BR_RE = Regex("(?i)<br\\s*/?>")
    private val TAG_RE = Regex("(?is)<[^>]+>")
    private val WS_RE = Regex("\\s+")

    /** 解码 HTML 实体（含数字实体 &#39; / &#x4e2d;） */
    fun decode(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                sb.append(c); i++; continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                sb.append(c); i++; continue
            }
            val name = s.substring(i + 1, semi)
            if (name.startsWith("#")) {
                val body = name.substring(1)
                val cp = if (body.startsWith("x") || body.startsWith("X"))
                    body.substring(1).toIntOrNull(16) else body.toIntOrNull()
                if (cp != null && cp > 0 && cp <= 0x10FFFF) {
                    sb.appendCodePoint(cp); i = semi + 1; continue
                }
                sb.append(c); i++; continue
            }
            val rep = ENTITIES[name.lowercase()]
            if (rep != null) {
                sb.append(rep); i = semi + 1; continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** 剥离 <wbr>、脚本、样式、注释——保结构、去噪声 */
    fun stripNoise(html: String): String {
        var s = WBR_RE.replace(html, "")
        s = SCRIPT_RE.replace(s, "")
        s = STYLE_RE.replace(s, "")
        s = COMMENT_RE.replace(s, "")
        return s
    }

    /** 标签剥离，得到纯文本（<br> 视为换行） */
    fun plainText(html: String): String =
        decode(BR_RE.replace(TAG_RE.replace(html, " "), "\n"))

    /** 全空白折叠成单空格 */
    fun squeeze(s: String): String = WS_RE.replace(s, " ").trim()

    /**
     * 单元格内容 → 有效文本行。
     * <br> 切行、剥标签、解码实体、去空行；`&nbsp;` 单元格会得到空列表。
     */
    fun cellLines(cellHtml: String): List<String> {
        val withBreaks = BR_RE.replace(cellHtml, "\n")
        val noTags = TAG_RE.replace(withBreaks, "")
        return decode(noTags)
            .split('\n')
            .map { it.replace('\u00A0', ' ') }
            .map { squeeze(it) }
            .filter { it.isNotEmpty() }
    }

    /**
     * 取 `<tag id="id">…</tag>` 的内部 HTML，按同名标签配平（支持嵌套同名标签）。
     * 找不到返回 null。
     */
    fun extractById(html: String, id: String, tag: String = "table"): String? {
        val openRe = Regex(
            "(?is)<" + tag + "\\b[^>]*\\bid\\s*=\\s*[\"']" + Regex.escape(id) + "[\"'][^>]*>"
        )
        val head = openRe.find(html) ?: return null
        val start = head.range.last + 1
        val openTag = Regex("(?is)<" + tag + "\\b")
        val closeTag = Regex("(?is)</" + tag + "\\s*>")
        var depth = 1
        var i = start
        while (i < html.length) {
            val nxtOpen = openTag.find(html, i)
            val nxtClose = closeTag.find(html, i)
            if (nxtClose == null) return null
            if (nxtOpen != null && nxtOpen.range.first < nxtClose.range.first) {
                depth++
                i = nxtOpen.range.last + 1
            } else {
                depth--
                if (depth == 0) return html.substring(start, nxtClose.range.first)
                i = nxtClose.range.last + 1
            }
        }
        return null
    }

    /** 从字节流解码：优先 meta 声明，其次 GBK（教务系统实际编码），最后 UTF-8 兜底 */
    fun decodeBytes(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val declared = Regex("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)").find(head)
            ?.groupValues?.get(1)
        val candidates = listOfNotNull(declared, "GBK", "UTF-8", "GB18030")
        for (name in candidates) {
            val cs = try {
                charset(name)
            } catch (e: Exception) {
                continue
            } ?: continue
            val text = try {
                String(bytes, cs)
            } catch (e: Exception) {
                continue
            }
            // 替换字符过多说明选错编码
            val bad = text.count { it == '\uFFFD' }
            if (bad == 0 || bad * 100 < text.length) return text
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun charset(name: String): java.nio.charset.Charset? = try {
        java.nio.charset.Charset.forName(name)
    } catch (e: Exception) {
        null
    }
}
