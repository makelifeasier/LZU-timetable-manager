package app.timetable.data

/**
 * 登录态判定。纯函数，便于单测。
 *
 * 真实行为（对 jwk.lzu.edu.cn 实测）：未登录访问 showTimetable.do 时，
 * 服务器不会跳到 sso.lzu.edu.cn，而是重定向到
 *   https://jwk.lzu.edu.cn/manager/coursearrange/login.jsp
 * 而该路径本身返回 404（Tomcat 报「文件未找到」）。
 * 所以只判断 SSO 域名会漏判，必须同时识别 login.jsp 这类重定向。
 */
object LoginState {

    fun needsLogin(
        requestedUrl: String,
        finalUrl: String,
        httpCode: Int,
        html: String
    ): Boolean {
        if (finalUrl.contains("sso.lzu.edu.cn")) return true
        if (finalUrl.contains("login", ignoreCase = true)) return true

        val looksLikeLoginPage = html.contains("统一身份认证") ||
            html.contains("请登录") ||
            html.contains("loginForm")
        if (looksLikeLoginPage && TimetableParser.parse(html).sessions.isEmpty()) return true

        // 被重定向到别处、且没拿到正常页面 —— 基本就是被挡在登录外
        if (httpCode !in 200..299 && finalUrl.isNotEmpty() && finalUrl != requestedUrl) return true

        return false
    }
}
