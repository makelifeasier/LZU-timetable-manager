package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本校页面」的判定与可注册域。
 *
 * 背景：兰大的门户、统一身份认证、教务系统是**同一所大学下的不同子域**
 * （`jwk.lzu.edu.cn` 教务、`sso.lzu.edu.cn` 认证、`www.lzu.edu.cn` 主页），
 * 登录过程本来就会在这些子域之间来回跳。
 *
 * 如果按**严格同主机**判断"是不是本校页面"，浏览器一跳子域就被当成"离开了本站"，
 * 自动发现会当场停住（表现：认证回来之后再也不动，得手动导入）。
 * 所以改成按**可注册域**判断：`lzu.edu.cn` 的所有子域都算本校，
 * 校外域名仍然不算（也挡住 `lzu.edu.cn.evil.com` 这种伪装）。
 */
class LoginFlowSiteTest {

    @Test
    fun baseDomainHandlesEduCn() {
        assertEquals("example.edu.cn", LoginFlow.baseDomain("www.example.edu.cn"))
        assertEquals("example.edu.cn", LoginFlow.baseDomain("zhjw.example.edu.cn"))
        assertEquals("example.edu.cn", LoginFlow.baseDomain("example.edu.cn"))
        assertEquals("lzu.edu.cn", LoginFlow.baseDomain("jwk.lzu.edu.cn"))
        assertEquals("lzu.edu.cn", LoginFlow.baseDomain("sso.lzu.edu.cn"))
    }

    @Test
    fun baseDomainHandlesPlainAndMultiPartTld() {
        assertEquals("example.com", LoginFlow.baseDomain("jw.example.com"))
        assertEquals("example.com", LoginFlow.baseDomain("www.example.com"))
        assertEquals("example.com.cn", LoginFlow.baseDomain("jw.example.com.cn"))
        assertEquals("sustech.edu.cn", LoginFlow.baseDomain("www.sustech.edu.cn"))
        assertEquals("", LoginFlow.baseDomain(""))
    }

    @Test
    fun allSubdomainsOfOneSchoolCountAsSitePages() {
        val portal = LoginFlow.baseDomain("www.example.edu.cn")   // example.edu.cn
        assertTrue(LoginFlow.isSitePage("https://www.example.edu.cn/", portal))
        assertTrue(LoginFlow.isSitePage("https://jw.example.edu.cn/", portal))
        assertTrue(LoginFlow.isSitePage("https://zhjw.example.edu.cn/", portal))
        assertTrue(LoginFlow.isSitePage("https://ehall.example.edu.cn/", portal))
        // 外站不算
        assertFalse(LoginFlow.isSitePage("https://www.other.edu.cn/", portal))
        assertFalse(LoginFlow.isSitePage("https://fake-example.edu.cn/", portal))
    }

    @Test
    fun lzuPortalStillMatchesItsOwnSubdomains() {
        val portal = LoginFlow.baseDomain(LoginFlow.hostOf(Prefs.DEFAULT_PORTAL_URL))
        assertTrue(LoginFlow.isSitePage("https://jwk.lzu.edu.cn/academic/", portal))
        assertTrue(LoginFlow.isSitePage("https://sso.lzu.edu.cn/login", portal))
        assertFalse(LoginFlow.isSitePage("https://www.lzu.edu.cn.evil.com/", portal))
    }

    @Test
    fun clickingThroughToTimetableStillReachesCapture() {
        // 入口是官网：点进教务处（另一个子域）之后，课表页仍必须被判为"本校页面"并触发抓取
        val portal = LoginFlow.baseDomain("www.example.edu.cn")
        val jw = "https://jw.example.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151"
        assertTrue(LoginFlow.isSitePage(jw, portal))
        assertEquals(LoginFlow.Step.CAPTURE, LoginFlow.next(jw, false, false, portalHost = portal))
    }
}
