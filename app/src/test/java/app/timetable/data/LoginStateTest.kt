package app.timetable.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录态判定回归测试。
 * 样本取自对 jwk.lzu.edu.cn 的真实探测：未登录时最终落点是
 * https://jwk.lzu.edu.cn/manager/coursearrange/login.jsp 且返回 HTTP 404。
 */
class LoginStateTest {

    private val requested =
        "https://jwk.lzu.edu.cn/academic/manager/coursearrange/showTimetable.do" +
            "?id=900001&yearid=46&termid=2&timetableType=STUDENT&sectionType=BASE"

    private fun realPage(): String =
        javaClass.classLoader!!.getResourceAsStream("timetable-2026autumn.html")!!
            .readBytes().toString(Charsets.UTF_8)

    @Test
    fun detectsLoginJspRedirectWith404() {
        // 真实服务器未登录时的行为
        assertTrue(
            LoginState.needsLogin(
                requested,
                "https://jwk.lzu.edu.cn/manager/coursearrange/login.jsp",
                404,
                "<html><body>HTTP状态 404 - 未找到</body></html>"
            )
        )
    }

    @Test
    fun detectsSsoRedirect() {
        assertTrue(LoginState.needsLogin(requested, "https://sso.lzu.edu.cn/login?x=1", 200, ""))
    }

    @Test
    fun detectsLoginPageBody() {
        assertTrue(
            LoginState.needsLogin(
                requested, requested, 200,
                "<html><body><h1>统一身份认证</h1><form>请登录</form></body></html>"
            )
        )
    }

    @Test
    fun doesNotFlagHealthyTimetablePage() {
        assertFalse(LoginState.needsLogin(requested, requested, 200, realPage()))
    }

    @Test
    fun doesNotFlagPlainServerError() {
        // 同一 URL 上的 500 是服务端故障，不该说成登录过期
        assertFalse(LoginState.needsLogin(requested, requested, 500, "Internal Server Error"))
    }

    @Test
    fun doesNotFlagEmptyButOkResponse() {
        assertFalse(LoginState.needsLogin(requested, requested, 200, "<html><body>空</body></html>"))
    }
}
