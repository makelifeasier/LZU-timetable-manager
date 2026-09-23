package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableLinkTest {

    private val base = "https://jwk.lzu.edu.cn/academic/manager/coursearrange/showTimetable.do"

    private fun link(id: String, year: String = "46", term: String = "2", type: String = "STUDENT", section: String = "BASE") =
        "$base?id=$id&yearid=$year&termid=$term&timetableType=$type&sectionType=$section"

    // ------------------------------------------------------------ JS 返回值解析

    @Test
    fun parsesJsArray() {
        val raw = """["https://a/x","https://b/y"]"""
        assertEquals(listOf("https://a/x", "https://b/y"), TimetableLink.parseJsArray(raw))
    }

    @Test
    fun parsesEmptyJsArray() {
        assertTrue(TimetableLink.parseJsArray("[]").isEmpty())
        assertTrue(TimetableLink.parseJsArray("").isEmpty())
        assertTrue(TimetableLink.parseJsArray(null).isEmpty())
        assertTrue(TimetableLink.parseJsArray("null").isEmpty())
        assertTrue(TimetableLink.parseJsArray("undefined").isEmpty())
    }

    @Test
    fun parsesEscapedQuotesAndUnicode() {
        val raw = """["https://a/path?q=\"x\"","\u4e2d\u6587"]"""
        val out = TimetableLink.parseJsArray(raw)
        assertEquals(2, out.size)
        assertEquals("""https://a/path?q="x"""", out[0])
        assertEquals("中文", out[1])
    }

    @Test
    fun parsesDoubleWrappedJsArray() {
        // 有些 WebView 会把结果再包一层字符串引号
        val raw = """ "[\"https://a/x\"]" """
        assertEquals(listOf("https://a/x"), TimetableLink.parseJsArray(raw))
    }

    // ------------------------------------------------------------ 链接挑选

    @Test
    fun picksStudentBasicSectionLink() {
        val candidates = listOf(
            link("1", type = "TEACHER"),
            link("2", type = "STUDENT", section = "COMBINE"),  // 大节课表，本 App 解析不了
            link("3", type = "STUDENT", section = "BASE")      // ← 要这条
        )
        assertEquals(link("3"), TimetableLink.pick(candidates))
    }

    @Test
    fun prefersStudentOverTeacher() {
        val candidates = listOf(link("1", type = "TEACHER"), link("2", type = "STUDENT"))
        assertEquals(link("2"), TimetableLink.pick(candidates))
    }

    @Test
    fun fallsBackToAnyShowTimetableLink() {
        val candidates = listOf("https://jwk.lzu.edu.cn/academic/x/showTimetable.do?foo=1")
        assertEquals("https://jwk.lzu.edu.cn/academic/x/showTimetable.do?foo=1", TimetableLink.pick(candidates))
    }

    @Test
    fun ignoresUnrelatedLinks() {
        val candidates = listOf("https://jwk.lzu.edu.cn/academic/index.jsp", "https://www.lzu.edu.cn/")
        assertNull(TimetableLink.pick(candidates))
        assertTrue(TimetableLink.normalize(candidates).isEmpty())
    }

    @Test
    fun deduplicatesCandidates() {
        val l = link("3")
        assertEquals(l, TimetableLink.pick(listOf(l, l, l)))
    }

    // ------------------------------------------------------------ 归一化

    @Test
    fun unescapesHtmlEntities() {
        assertEquals("a?b=1&c=2", TimetableLink.unescape("a?b=1&amp;c=2"))
        assertEquals("a?b=1&c=2", TimetableLink.unescape("a?b=1&#38;c=2"))
        assertEquals("a?b=1&c=2", TimetableLink.unescape("a?b=1&#x26;c=2"))
    }

    @Test
    fun absolutizesRelativeLinks() {
        assertEquals(
            "https://jwk.lzu.edu.cn/academic/manager/coursearrange/showTimetable.do?id=1",
            TimetableLink.absolutize("/academic/manager/coursearrange/showTimetable.do?id=1")
        )
        assertEquals("https://jwk.lzu.edu.cn/x", TimetableLink.absolutize("x"))
        // 已经是绝对链接就不动
        assertEquals("http://jwk.lzu.edu.cn/x", TimetableLink.absolutize("http://jwk.lzu.edu.cn/x"))
    }

    @Test
    fun picksLinkWrittenWithHtmlEntityInHref() {
        // 门户里写成 &amp; 很常见
        val href = "/academic/manager/coursearrange/showTimetable.do?id=9001&amp;yearid=46&amp;termid=2&amp;timetableType=STUDENT&amp;sectionType=BASE"
        val picked = TimetableLink.pick(listOf(href))
        assertEquals("9001", TimetableLink.param(picked!!, "id"))
        assertEquals("STUDENT", TimetableLink.param(picked, "timetableType"))
        assertEquals("BASE", TimetableLink.param(picked, "sectionType"))
    }

    @Test
    fun paramReadsQueryValues() {
        assertEquals("46", TimetableLink.param(link("9"), "yearid"))
        assertEquals("9", TimetableLink.param(link("9"), "id"))
        assertNull(TimetableLink.param(link("9"), "nope"))
    }

    // ------------------------------------------------------------ 全文兜底扫描

    @Test
    fun scansHtmlForTimetableLinks() {
        val html = """
            <html><body>
              <li><a href="/academic/manager/coursearrange/showTimetable.do?id=777&amp;yearid=46&amp;termid=2&amp;timetableType=STUDENT&amp;sectionType=BASE">学生课表</a></li>
              <script>window.open('showTimetable.do?id=778&timetableType=STUDENT&sectionType=BASE')</script>
            </body></html>
        """.trimIndent()

        val found = TimetableLink.scanHtml(html)
        assertEquals(2, found.size)

        val picked = TimetableLink.pick(found)
        assertEquals("777", TimetableLink.param(picked!!, "id"))
    }

    // ------------------------------------------------------------ 兜底链接

    @Test
    fun fallbackDropsIdButKeepsTerm() {
        val fallback = TimetableLink.fallback(listOf(link("900001", year = "47", term = "1")))
        assertEquals("https://jwk.lzu.edu.cn/academic/manager/coursearrange/showTimetable.do?yearid=47&termid=1&timetableType=STUDENT&sectionType=BASE", fallback)
        assertNull(TimetableLink.param(fallback, "id"))
    }

    @Test
    fun fallbackWithoutAnyCandidateStillBuildsStudentBasicUrl() {
        val fallback = TimetableLink.fallback(emptyList())
        assertEquals("https://jwk.lzu.edu.cn/academic/manager/coursearrange/showTimetable.do?timetableType=STUDENT&sectionType=BASE", fallback)
    }

    @Test
    fun resolvePrefersDiscoveredLinkAndAlwaysReturnsSomething() {
        assertEquals(link("3"), TimetableLink.resolve(listOf(link("3"))))
        assertEquals(TimetableLink.fallback(emptyList()), TimetableLink.resolve(emptyList()))
    }

    @Test
    fun resolveNeverReturnsAnotherStudentsId() {
        // 从门户扫不到任何链接时，绝不能凭空捏一个 id 出来
        val resolved = TimetableLink.resolve(listOf("https://jwk.lzu.edu.cn/academic/index.jsp"))
        assertNull(TimetableLink.param(resolved!!, "id"))
    }

    // ------------------------------------------------------------ 门户 origin 跟随

    @Test
    fun originOfExtractsSchemeHostPort() {
        assertEquals("https://jwk.lzu.edu.cn", TimetableLink.originOf("https://jwk.lzu.edu.cn/academic/"))
        assertEquals("http://10.0.2.2:8080", TimetableLink.originOf("http://10.0.2.2:8080/portal.u8.html"))
    }

    @Test
    fun relativeLinksResolveAgainstConfiguredOriginNotAHardcodedDomain() {
        // 门户里的 href 基本都是根相对路径。若按写死的域名补全，换部署就会指到错站点。
        val relative =
            "/academic/manager/coursearrange/showTimetable.do?id=777777&yearid=99&termid=1&timetableType=STUDENT&sectionType=BASE"
        val picked = TimetableLink.pick(listOf(relative), "http://10.0.2.2:8080")
        assertEquals("http://10.0.2.2:8080$relative", picked)
    }

    @Test
    fun relativeAndAbsoluteFormsCollapseIntoOneCandidate() {
        // 注入脚本同时回传 getAttribute('href')（相对）和 a.href（浏览器解析后的绝对）。
        // 两者必须归一化成同一条，否则相对那条会被错误补全，抢在正确的那条前面被选中。
        // （真实发生过：自动发现选中了 https://jwk.lzu.edu.cn/… 而不是当前门户的地址）
        val relative =
            "/academic/manager/coursearrange/showTimetable.do?id=777777&yearid=99&termid=1&timetableType=STUDENT&sectionType=BASE"
        val absolute = "http://10.0.2.2:8080$relative"
        assertEquals(absolute, TimetableLink.pick(listOf(relative, absolute), "http://10.0.2.2:8080"))
        assertEquals(1, TimetableLink.normalize(listOf(relative, absolute), "http://10.0.2.2:8080").size)
    }

    @Test
    fun bareRelativeHrefAlsoFollowsConfiguredOrigin() {
        val picked = TimetableLink.pick(
            listOf("showTimetable.do?id=700001&timetableType=STUDENT&sectionType=BASE"),
            "http://10.0.2.2:8080"
        )
        assertEquals(
            "http://10.0.2.2:8080/academic/manager/coursearrange/showTimetable.do?id=700001&timetableType=STUDENT&sectionType=BASE",
            picked
        )
    }

    @Test
    fun fallbackFollowsConfiguredOrigin() {
        val f = TimetableLink.fallback(emptyList(), "http://10.0.2.2:8080")
        assertEquals(
            "http://10.0.2.2:8080/academic/manager/coursearrange/showTimetable.do?timetableType=STUDENT&sectionType=BASE",
            f
        )
    }

    // ------------------------------------------------------------ 「值不值得请求」判定

    @Test
    fun fallbackWithoutYearIsNotWorthRequesting() {
        // 真机实测：链接里没有 yearid 时，兰大教务系统直接回一张
        // `<title>提示信息</title> … 学年传递错误 …` 的页面 —— 这种请求注定失败，
        // 所以自动发现失败时不该再白发一次，更不该把那张错误页甩给用户看。
        val f = TimetableLink.fallback(emptyList(), "https://jwk.lzu.edu.cn")
        assertNull(TimetableLink.param(f, "yearid"))
        assertFalse(TimetableLink.hasYearParam(f))
    }

    @Test
    fun fallbackInheritsYearFromSavedUrlSoItIsWorthTrying() {
        // 上次成功导入过的链接里有 yearid/termid —— 继承过来就还值得一试；
        // 但**不能**把别人的 id 也一起带过来。
        val f = TimetableLink.fallback(listOf(link("900001")), "https://jwk.lzu.edu.cn")
        assertTrue(TimetableLink.hasYearParam(f))
        assertEquals("46", TimetableLink.param(f, "yearid"))
        assertEquals("2", TimetableLink.param(f, "termid"))
        assertNull(TimetableLink.param(f, "id"))
    }

    @Test
    fun hasYearParamRecognisesScannedLinks() {
        assertTrue(TimetableLink.hasYearParam(link("900001")))
        assertFalse(TimetableLink.hasYearParam("https://jwk.lzu.edu.cn/academic/index.jsp"))
        assertFalse(TimetableLink.hasYearParam(""))
    }

    // ------------------------------------------------------------ 发现脚本哨兵

    @Test
    fun discoverySentinelMeansScriptRan() {
        val raw = """["${TimetableLink.SENTINEL}","${link("3")}"]"""
        val d = TimetableLink.parseDiscovery(raw)
        assertTrue(d.ran)
        assertEquals(listOf(link("3")), d.urls)
    }

    @Test
    fun discoverySentinelAloneMeansScriptRanButNothingFound() {
        // 「脚本跑成功了，但页面里确实没有课表入口」—— 与下面的情况必须能区分
        val d = TimetableLink.parseDiscovery("""["${TimetableLink.SENTINEL}"]""")
        assertTrue(d.ran)
        assertTrue(d.urls.isEmpty())
    }

    @Test
    fun discoveryWithoutSentinelMeansScriptDidNotRun() {
        // 注入脚本语法错误时 WebView 静默返回 null / []
        assertFalse(TimetableLink.parseDiscovery("[]").ran)
        assertFalse(TimetableLink.parseDiscovery("null").ran)
        assertFalse(TimetableLink.parseDiscovery(null).ran)
        assertFalse(TimetableLink.parseDiscovery("").ran)
    }

    @Test
    fun discoverySentinelSurvivesDoubleWrappedJsPayload() {
        // 真实路径：evaluateJavascript 拿到字符串时会再包一层引号并转义
        val raw = "\"[\\\"${TimetableLink.SENTINEL}\\\",\\\"${link("8")}\\\"]\""
        val d = TimetableLink.parseDiscovery(raw)
        assertTrue(d.ran)
        assertEquals(listOf(link("8")), d.urls)
    }
}
