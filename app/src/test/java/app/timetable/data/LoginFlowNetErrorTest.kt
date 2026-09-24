package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录页 WebView 的**主框架加载失败**文案。
 *
 * 背景：登录页原来只认 `onPageFinished`，门户超时 / DNS 挂掉 / TLS 出错时状态栏永远停在
 * 「正在打开你的课表…」，WebView 里只有一张系统错误页 —— 既不报错也没有下一步，
 * 用户只能干等（而同一条后台抓取路径反而有 20s 超时，见 net/Fetcher）。
 *
 * 所以这段文案有两条硬要求，也就是下面钉住的东西：
 *  1. **说清是网络的哪一环**（断网 / 域名 / 超时 / 证书各说各的，不能一句"加载失败"糊过去）；
 *  2. **给出下一步动作** —— 重试入口就是登录页右上角的 ⋮ 菜单，文案里必须点名。
 *
 * 这些错误码常量在 LoginFlow 里是普通 Int（不 import android.webkit），
 * 取值与 `WebViewClient.ERROR_*` 一一对应，这样纯 JVM 单测才能直接比对。
 */
class LoginFlowNetErrorTest {

    @Test
    fun errorCodesMirrorWebViewConstants() {
        // 与 WebViewClient.ERROR_HOST_LOOKUP / ERROR_CONNECT / ERROR_IO / ERROR_TIMEOUT
        // 以及 onReceivedSslError 用的哨兵值保持一致（写错一个数字，文案就会张冠李戴）
        assertEquals(-2, LoginFlow.NET_ERR_HOST_LOOKUP)
        assertEquals(-6, LoginFlow.NET_ERR_CONNECT)
        assertEquals(-7, LoginFlow.NET_ERR_IO)
        assertEquals(-8, LoginFlow.NET_ERR_TIMEOUT)
        assertEquals(-11, LoginFlow.NET_ERR_SSL)
    }

    @Test
    fun timeoutAndDnsAndConnectAreToldApart() {
        val dns = LoginFlow.loadFailureText(LoginFlow.NET_ERR_HOST_LOOKUP)
        val timeout = LoginFlow.loadFailureText(LoginFlow.NET_ERR_TIMEOUT)
        val connect = LoginFlow.loadFailureText(LoginFlow.NET_ERR_CONNECT)
        val ssl = LoginFlow.loadFailureText(LoginFlow.NET_ERR_SSL)

        assertTrue(dns.contains("域名"))
        assertTrue(timeout.contains("超时"))
        assertTrue(connect.contains("连不上"))
        assertTrue(ssl.contains("证书"))
        // 四种原因必须是四句不同的话（否则等于没分类）
        assertEquals(4, setOf(dns, timeout, connect, ssl).size)
    }

    @Test
    fun everyMessageTellsTheUserWhatToDoNext() {
        val codes = listOf(
            LoginFlow.NET_ERR_HOST_LOOKUP, LoginFlow.NET_ERR_CONNECT, LoginFlow.NET_ERR_IO,
            LoginFlow.NET_ERR_TIMEOUT, LoginFlow.NET_ERR_SSL, 0, -999
        )
        for (code in codes) {
            for (sawAuth in listOf(false, true)) {
                val text = LoginFlow.loadFailureText(code, sawAuth)
                assertTrue("错误码 $code 的文案不能是空的", text.isNotBlank())
                assertTrue("错误码 $code 必须给出重试入口", text.contains("⋮"))
                assertTrue("错误码 $code 必须说清楚要重新登录或重试", text.contains("重新登录") || text.contains("重试"))
                // 绝不能还停留在"正在打开…"那种什么都不告诉用户的状态
                assertFalse(text.contains("正在打开"))
            }
        }
    }

    @Test
    fun alreadyAuthenticatedUserIsNotToldToLogInAgain() {
        // 已经走过认证页/票据页 = 登录本身是成功的，问题只是"这一页打不开"，
        // 文案不能让人以为要重新登录一遍（那是"没找到课表页"那类问题的提示）
        val afterAuth = LoginFlow.loadFailureText(LoginFlow.NET_ERR_TIMEOUT, sawAuth = true)
        val beforeAuth = LoginFlow.loadFailureText(LoginFlow.NET_ERR_TIMEOUT, sawAuth = false)
        assertTrue(afterAuth.contains("已登录成功"))
        assertFalse(beforeAuth.contains("已登录成功"))
        assertTrue(beforeAuth.contains("网络没通"))
    }
}
