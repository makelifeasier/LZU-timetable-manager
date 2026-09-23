package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自动发现」这一环的回归测试：**课表页路径特征表**与**是否该触发抓取**。
 *
 * 背景（真实会发生的故障）：候选筛选原来写死 `contains("showTimetable")`。
 * 这个字符串一旦失效（学校改版、换接口路径、改成统一门户里的另一个地址），
 * 用户就会卡在"登录成功、页面也对，但 App 说没找到入口"这一步 —— 而且完全看不出原因。
 *
 * 所以这里钉三件事：
 *  1. 路径特征表能覆盖住"换了名字的课表页"（下面几个路径形状都是真实系统里见过的）；
 *  2. 无关链接不会被误收（成绩查询、首页之类）；
 *  3. 兰大自己的链接**永远优先**，老行为一个字都不变。
 */
class TimetableLinkHintsTest {

    private val origin = "http://10.0.2.2:8080"

    // ------------------------------------------------------------ 课表页路径的几种真实形状

    @Test
    fun keepsTimetableLinkEndingWithHtmlAndQuery() {
        val u = "$origin/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151"
        assertEquals(listOf(u), TimetableLink.normalize(listOf(u), origin))
    }

    @Test
    fun keepsTimetableLinkEndingWithDoAndQuery() {
        val u = "$origin/jsxsd/xskb/xskb_list.do?xnxq01id=2025-2026-1"
        assertEquals(listOf(u), TimetableLink.normalize(listOf(u), origin))
    }

    @Test
    fun keepsTimetableLinkWithExclamationActionForm() {
        val u = "$origin/eams/courseTableForStd!courseTable.action?ignoreHead=1&settingKind=0"
        assertEquals(listOf(u), TimetableLink.normalize(listOf(u), origin))
    }

    @Test
    fun stillDropsUnrelatedLinks() {
        val junk = listOf(
            "$origin/jwglxt/xtgl/index_initMenu.html",
            "$origin/jwglxt/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005",
            "https://www.example.edu.cn/"
        )
        assertTrue(TimetableLink.normalize(junk, origin).isEmpty())
    }

    // ------------------------------------------------------------ 打分不误伤兰大

    @Test
    fun lzuLinkStillWinsOverOtherCandidates() {
        // 兰大页面上就算混进了别的候选，也必须挑兰大那条（参数加分 170 远高于其它特征分）
        val lzu = "$origin/academic/manager/coursearrange/showTimetable.do" +
            "?id=777&yearid=46&termid=2&timetableType=STUDENT&sectionType=BASE"
        val other = "$origin/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151"
        assertEquals(lzu, TimetableLink.pick(listOf(other, lzu), origin))
    }

    @Test
    fun fallbackIsNotBuiltFromUnknownTimetableLink() {
        // 没有 yearid 的链接不该拿来拼兜底请求 —— 那样只会给服务器发一个注定失败的请求
        val unknown = "$origin/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151"
        val fb = TimetableLink.fallback(listOf(unknown), origin)
        assertTrue(!TimetableLink.hasYearParam(fb))
    }

    // ------------------------------------------------------------ 全文兜底扫描

    @Test
    fun scanHtmlKeepsFullPathNotAFragment() {
        // 关键：扫描必须从**路径开头**开始，否则只会抓到 "kbcx/xskbcx_...?…" 这种缺目录的残片，
        // 补全后会指向一个错地址。
        val html = """<li><a href="http://10.0.2.2:8080/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151">学生课表</a></li>"""
        assertEquals(
            listOf("http://10.0.2.2:8080/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151"),
            TimetableLink.scanHtml(html)
        )
    }

    @Test
    fun scanHtmlStillWorksOnLzuLinks() {
        val html = """<script>window.open('showTimetable.do?id=778&timetableType=STUDENT&sectionType=BASE')</script>"""
        assertEquals(
            listOf("showTimetable.do?id=778&timetableType=STUDENT&sectionType=BASE"),
            TimetableLink.scanHtml(html)
        )
    }

    // ------------------------------------------------------------ 决策：必须进"抓取"

    /**
     * 能挑出候选还不够 —— 打开课表页之后，决策层还得**认出这是课表页并触发抓取**。
     *
     * 原来 `LoginFlow.next` 里只有 `url.contains("showTimetable")` 一条能进 CAPTURE，
     * 于是课表页换了路径时会走到最后一步却永远不抓，界面显示「没能自动找到课表入口」，
     * 而人明明已经停在课表页面上了。
     */
    @Test
    fun timetablePagesWithChangedPathsStillLeadToCapture() {
        val urls = listOf(
            "https://jwk.lzu.edu.cn/academic/manager/coursearrange/showTimetable.do?id=1&yearid=46",
            "$origin/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151",
            "http://jwk.example.edu.cn/jsxsd/xskb/xskb_list.do?xnxq01id=2025-2026-1",
            "https://eams.example.edu.cn/eams/courseTableForStd!courseTable.action"
        )
        for (u in urls) {
            assertEquals("必须触发抓取：$u", LoginFlow.Step.CAPTURE, LoginFlow.next(u, false, false))
        }
    }

    @Test
    fun ordinaryPagesStillDoNotTriggerCapture() {
        for (u in listOf(
            "$origin/jwglxt/xtgl/index_initMenu.html",
            "$origin/jwglxt/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005"
        )) {
            assertTrue("不该触发抓取：$u", LoginFlow.next(u, false, false) != LoginFlow.Step.CAPTURE)
        }
    }
}
