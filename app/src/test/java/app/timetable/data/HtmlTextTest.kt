package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {

    @Test
    fun decodesNamedAndNumericEntities() {
        assertEquals("<<高等代数（一）>>", HtmlText.decode("&lt;&lt;高等代数（一）&gt;&gt;"))
        assertEquals("'", HtmlText.decode("&#39;"))
        assertEquals("中", HtmlText.decode("&#x4e2d;"))
        assertEquals("A B", HtmlText.decode("A&nbsp;B"))
        assertEquals("a&b", HtmlText.decode("a&amp;b"))
        assertEquals("裸 & 号", HtmlText.decode("裸 & 号"))
    }

    @Test
    fun stripsWbrInjectedByBrowser() {
        val raw = "学生课表: 9999999999<wbr>99"
        val clean = HtmlText.squeeze(HtmlText.stripNoise(raw))
        assertEquals("学生课表: 999999999999", clean)
    }

    @Test
    fun stripsScriptsAndComments() {
        val html = "<td><!--for Firefox auto wrap--><script>var a=1;</script>&nbsp;</td>"
        val clean = HtmlText.stripNoise(html)
        assertFalse("脚本内容应被剥掉", clean.contains("var a=1"))
        assertFalse("注释应被剥掉", clean.contains("Firefox"))
        assertTrue("保留 &nbsp; 以便判定空单元格", clean.contains("&nbsp;"))
        // 普通标签不属于「噪声」，stripNoise 只去扩注入物，结构留给调用方
        assertTrue(clean.contains("<td>"))
    }

    @Test
    fun cellLinesSplitsOnBrAndDropsBlanks() {
        val lines = HtmlText.cellLines(
            "&lt;&lt;高阶英语1&gt;&gt;;<wbr>23<br>天山堂A301<br>甲老师<br>4-18周双周<br>讲课学时"
        )
        assertEquals(
            listOf("<<高阶英语1>>;23", "天山堂A301", "甲老师", "4-18周双周", "讲课学时"),
            lines
        )
    }

    @Test
    fun nbspOnlyCellYieldsNoLines() {
        assertEquals(emptyList<String>(), HtmlText.cellLines("&nbsp;"))
        assertEquals(emptyList<String>(), HtmlText.cellLines("   "))
        assertEquals(emptyList<String>(), HtmlText.cellLines(""))
    }

    @Test
    fun extractByIdHandlesNestedSameTag() {
        val html = "<table id=\"a\"><tr><td><table><tr><td>x</td></tr></table></td></tr></table>"
        assertEquals(
            "<tr><td><table><tr><td>x</td></tr></table></td></tr>",
            HtmlText.extractById(html, "a")
        )
    }

    @Test
    fun extractByIdDoesNotMatchSiblingPrefix() {
        val html = "<div id=\"subTitle\">忽略</div><div id=\"title\">要的</div>"
        assertEquals("要的", HtmlText.extractById(html, "title", "div"))
        assertNull(HtmlText.extractById(html, "nope", "div"))
    }

    @Test
    fun decodeBytesUsesDeclaredGbkCharset() {
        val bytes = javaClass.classLoader!!
            .getResourceAsStream("timetable-2026autumn-gbk.html")!!.readBytes()
        val text = HtmlText.decodeBytes(bytes)
        assertTrue("应正确解码 GBK 中文", text.contains("学生课表"))
        assertTrue(text.contains("天山堂A301"))
        assertTrue(text.contains("高等代数"))
    }

    @Test
    fun decodeBytesFallsBackToUtf8() {
        val text = HtmlText.decodeBytes("没有 meta 声明的中文内容".toByteArray(Charsets.UTF_8))
        assertEquals("没有 meta 声明的中文内容", text)
    }

    @Test
    fun sectionParsesClockTimes() {
        assertEquals(java.time.LocalTime.of(8, 30), Section.parseHm("08:30"))
        assertEquals(java.time.LocalTime.of(22, 30), Section.parseHm("22:30"))
        assertNull(Section.parseHm("第1节"))
        assertNull(Section.parseHm(""))
        assertNull(Section.parseHm("25:00"))
    }
}
