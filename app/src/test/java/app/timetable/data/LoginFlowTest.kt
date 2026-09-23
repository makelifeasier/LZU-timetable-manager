package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginFlowTest {

    /** 每个学生 id/yearid/termid 都不同，测试里用中性假值 */
    private val timetable =
        "https://jwk.lzu.edu.cn/academic/manager/coursearrange/showTimetable.do" +
            "?id=900001&yearid=46&termid=2&timetableType=STUDENT&sectionType=BASE"

    private val brokenLogin = "https://jwk.lzu.edu.cn/manager/coursearrange/login.jsp"
    private val portal = "https://jwk.lzu.edu.cn/academic/"
    private val sso = "https://sso.lzu.edu.cn/login"

    /** 实测形态：认证通过后服务器用**明文 http** 回跳票据校验页 */
    private val casTicket =
        "http://jwk.lzu.edu.cn/academic/login/lzu/loginLds6Valid.jsp?ticket=ST-73012-GM16EFb5cOsORshhARf9-cas01"

    /**
     * 模拟 Activity 里的四个标记。到真正的登录页要**全部清零**，
     * 否则重新登录后既不会兜回门户也不会再自动发现课表。
     */
    private class Run {
        var bounced = false
        var navigated = false
        var discovered = false
        var wentPortal = false
        val steps = ArrayList<LoginFlow.Step>()
        var hops = 0

        fun step(url: String): LoginFlow.Step {
            hops++
            if (LoginFlow.isRealLoginPage(url)) {
                bounced = false; navigated = false; discovered = false; wentPortal = false
            }
            val s = LoginFlow.next(url, bounced, navigated, discovered, wentPortal)
            steps += s
            when (s) {
                LoginFlow.Step.BOUNCE_TO_PORTAL -> bounced = true
                LoginFlow.Step.GO_PORTAL -> wentPortal = true
                LoginFlow.Step.DO_DISCOVERY -> discovered = true
                LoginFlow.Step.GO_TIMETABLE -> navigated = true
                else -> Unit
            }
            return s
        }
    }

    private fun one(url: String) = LoginFlow.next(url, false, false)

    // ---------------------------------------------------------------- 票据 / 坏链

    @Test
    fun recognisesCasTicketReturnPage() {
        assertTrue(LoginFlow.isCasTicketReturn(casTicket))
        // 票据页不能被误当成那个失效的登录页
        assertFalse(LoginFlow.isCasTicketReturn(brokenLogin))
        assertFalse(LoginFlow.isCasTicketReturn(portal))
    }

    @Test
    fun casTicketGoesBackToPortalFirst() {
        // 票据页只是中转，本身不是门户，扫不到课表链接 → 先回门户
        assertEquals(LoginFlow.Step.GO_PORTAL, one(casTicket))
    }

    @Test
    fun casTicketReturnDoesNotLoop() {
        assertEquals(
            LoginFlow.Step.SHOW_CURRENT,
            LoginFlow.next(casTicket, false, false, discoveryDone = false, wentToPortalAfterTicket = true)
        )
    }

    @Test
    fun brokenLoginStillBouncesAfterTicketRuleAdded() {
        // 回归：新增票据页规则后，不能把失效的登录页也当成票据页
        assertEquals(LoginFlow.Step.BOUNCE_TO_PORTAL, one(brokenLogin))
    }

    @Test
    fun bouncesFromBrokenLoginRedirectOnce() {
        // 第一次撞上坏链 → 兜回门户
        assertEquals(
            LoginFlow.Step.BOUNCE_TO_PORTAL,
            LoginFlow.next(brokenLogin, alreadyBouncedFromBrokenLogin = false, alreadyNavigatedToTimetable = true)
        )
        // 第二次不再兜，避免 门户↔坏链 死循环
        assertEquals(
            LoginFlow.Step.SHOW_CURRENT,
            LoginFlow.next(brokenLogin, alreadyBouncedFromBrokenLogin = true, alreadyNavigatedToTimetable = true)
        )
    }

    // ---------------------------------------------------------------- 自动发现

    @Test
    fun portalTriggersDiscoveryInsteadOfOpeningAHardcodedUrl() {
        // 核心改动：登录后落在门户上，必须先自动发现「本人」的课表链接，
        // 不能拿一条写死在 App 里的别人链接去打开。
        assertEquals(LoginFlow.Step.DO_DISCOVERY, one(portal))
    }

    @Test
    fun opensTimetableAfterDiscoveryWhenNotYetNavigated() {
        assertEquals(
            LoginFlow.Step.GO_TIMETABLE,
            LoginFlow.next(portal, false, false, discoveryDone = true, wentToPortalAfterTicket = true)
        )
    }

    @Test
    fun doesNotNavigateToTimetableTwice() {
        assertEquals(
            LoginFlow.Step.SHOW_CURRENT,
            LoginFlow.next(portal, false, true, discoveryDone = true, wentToPortalAfterTicket = true)
        )
    }

    @Test
    fun discoveryRunsOnlyOncePerLogin() {
        // 发现过之后再回门户，不该无限重复发现
        val r = Run()
        assertEquals(LoginFlow.Step.DO_DISCOVERY, r.step(portal))
        assertEquals(LoginFlow.Step.GO_TIMETABLE, r.step(portal))  // 已发现但还没跳
        assertEquals(LoginFlow.Step.SHOW_CURRENT, r.step(portal))  // 都做过了
        assertEquals(3, r.hops)
    }

    @Test
    fun savedUrlOnLaterRefreshSkipsDiscovery() {
        // 后台定时刷新是直接开已保存的课表链接，这一页不该再触发发现
        assertEquals(LoginFlow.Step.CAPTURE, one(timetable))
        assertEquals(
            LoginFlow.Step.CAPTURE,
            LoginFlow.next(timetable, false, true, discoveryDone = false, wentToPortalAfterTicket = true)
        )
    }

    // ---------------------------------------------------------------- 完整路径

    @Test
    fun realFirstRunSequenceHasNoLoop() {
        // 全新装机首次使用：认证页 → 登录 → 明文票据页 → 门户 → 发现 → 课表 → 抓取
        val r = Run()
        assertEquals(LoginFlow.Step.SHOW_LOGIN_HINT, r.step(sso))
        assertEquals(LoginFlow.Step.GO_PORTAL, r.step(casTicket))
        assertEquals(LoginFlow.Step.DO_DISCOVERY, r.step(portal))
        assertEquals(LoginFlow.Step.CAPTURE, r.step(timetable))
        assertEquals(4, r.steps.size)
        assertEquals(4, r.hops)
    }

    @Test
    fun expiredSessionSequenceTerminates() {
        // 会话过期：课表 URL → 坏链 → 认证页，不应无限循环
        val r = Run()
        assertEquals(LoginFlow.Step.BOUNCE_TO_PORTAL, r.step(brokenLogin))
        assertEquals(LoginFlow.Step.SHOW_LOGIN_HINT, r.step(sso))
        assertEquals(2, r.hops)
    }

    @Test
    fun reloginAfterExpiryStillReachesTimetable() {
        // 兜回门户 → 认证页（清标记）→ 登录成功 → 票据页 → 门户 → 必须能**再次**自动发现
        val r = Run()
        assertEquals(LoginFlow.Step.BOUNCE_TO_PORTAL, r.step(brokenLogin))
        assertEquals(LoginFlow.Step.SHOW_LOGIN_HINT, r.step(sso))
        assertEquals(LoginFlow.Step.GO_PORTAL, r.step(casTicket))
        assertEquals(LoginFlow.Step.DO_DISCOVERY, r.step(portal))
        assertEquals(LoginFlow.Step.CAPTURE, r.step(timetable))
    }

    @Test
    fun discoveryFlagMustAlsoClearOnLoginPage() {
        // 关键回归：若不把 discovered 一起清零，第二次登录后门户会走到 SHOW_CURRENT，
        // 用户看到的就是「登录了但一直没课表」。
        val r = Run()
        assertEquals(LoginFlow.Step.DO_DISCOVERY, r.step(portal))
        assertEquals(LoginFlow.Step.GO_TIMETABLE, r.step(portal))
        assertEquals(LoginFlow.Step.SHOW_CURRENT, r.step(portal))   // 本轮已用完
        assertEquals(LoginFlow.Step.SHOW_LOGIN_HINT, r.step(sso))   // 重新登录 → 清标记
        assertEquals(LoginFlow.Step.GO_PORTAL, r.step(casTicket))
        assertEquals(LoginFlow.Step.DO_DISCOVERY, r.step(portal))   // 必须能再次发现
    }

    @Test
    fun brokenLoginAfterDiscoveryStillTerminates() {
        // 发现出来的链接若本身也不通 → 坏链 → 门户：不该来回弹
        val r = Run()
        assertEquals(LoginFlow.Step.DO_DISCOVERY, r.step(portal))
        assertEquals(LoginFlow.Step.GO_TIMETABLE, r.step(portal))
        assertEquals(LoginFlow.Step.BOUNCE_TO_PORTAL, r.step(brokenLogin))
        assertEquals(LoginFlow.Step.SHOW_CURRENT, r.step(brokenLogin))  // 第二次不再兜
        assertEquals(LoginFlow.Step.SHOW_CURRENT, r.step(portal))       // 门户也不再乱跳
        assertEquals(5, r.hops)
    }

    // ---------------------------------------------------------------- 杂项

    @Test
    fun showsHintOnSsoPage() {
        assertEquals(LoginFlow.Step.SHOW_LOGIN_HINT, one(sso))
    }

    @Test
    fun capturesOnTimetablePage() {
        assertEquals(LoginFlow.Step.CAPTURE, one(timetable))
    }

    @Test
    fun unknownPageJustShowsCurrent() {
        assertEquals(LoginFlow.Step.SHOW_CURRENT, one("about:blank"))
    }

    @Test
    fun recognisesRealLoginPage() {
        // 到真正的登录页要清标记，否则登录成功回来不会自动开课表页
        assertTrue(LoginFlow.isRealLoginPage(sso))
        assertFalse(LoginFlow.isRealLoginPage(portal))
        assertFalse(LoginFlow.isRealLoginPage(timetable))
        assertFalse(LoginFlow.isRealLoginPage(brokenLogin))
    }

    // ---------------------------------------------------------------- 门户主机识别

    @Test
    fun hostOfExtractsHostName() {
        assertEquals("jwk.lzu.edu.cn", LoginFlow.hostOf(portal))
        assertEquals("jwk.lzu.edu.cn", LoginFlow.hostOf("http://jwk.lzu.edu.cn/x?y=1#z"))
        assertEquals("10.0.2.2", LoginFlow.hostOf("http://10.0.2.2:8080/portal.html"))
        assertEquals("sso.lzu.edu.cn", LoginFlow.hostOf(sso))
    }

    @Test
    fun isSitePageMatchesHostAndSubdomains() {
        assertTrue(LoginFlow.isSitePage(portal, "jwk.lzu.edu.cn"))
        assertTrue(LoginFlow.isSitePage("https://x.jwk.lzu.edu.cn/a", "jwk.lzu.edu.cn"))
        assertFalse(LoginFlow.isSitePage("https://www.baidu.com/", "jwk.lzu.edu.cn"))
        // 提防后缀钓鱼：jwk.lzu.edu.cn.evil.com 不算本校站点
        assertFalse(LoginFlow.isSitePage("https://jwk.lzu.edu.cn.evil.com/", "jwk.lzu.edu.cn"))
        assertFalse(LoginFlow.isSitePage("about:blank", "jwk.lzu.edu.cn"))
    }

    @Test
    fun discoveryFollowsConfiguredPortalHostNotAHardcodedDomain() {
        // 学校换域名/端口后自动发现不能跟着一起失效
        assertEquals(
            LoginFlow.Step.DO_DISCOVERY,
            LoginFlow.next(
                "http://10.0.2.2:8080/portal.html",
                alreadyBouncedFromBrokenLogin = false,
                alreadyNavigatedToTimetable = false,
                portalHost = "10.0.2.2"
            )
        )
    }

    @Test
    fun unrelatedPagesDoNotTriggerDiscovery() {
        // 用户在登录页里随手浏览别处时，不能被自动发现劫持着到处跳
        assertEquals(LoginFlow.Step.SHOW_CURRENT, one("https://www.baidu.com/"))
        assertEquals(LoginFlow.Step.SHOW_CURRENT, one("about:blank"))
    }
}
