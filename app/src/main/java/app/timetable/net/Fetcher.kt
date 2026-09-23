package app.timetable.net

import android.webkit.CookieManager
import app.timetable.data.HtmlText
import app.timetable.data.LoginState
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用 WebView 登录留下的 Cookie 抓课表页。
 *
 * 关键点：android.webkit.CookieManager 不是 java.net.CookieHandler，
 * HttpURLConnection 不会自动带上 WebView 的 Cookie，
 * 必须自己读 Cookie 头、并把响应的 Set-Cookie 写回 CookieManager。
 */
object Fetcher {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    data class Outcome(
        val httpCode: Int = 0,
        val finalUrl: String = "",
        val html: String = "",
        val bytes: Int = 0,
        val loginExpired: Boolean = false,
        val error: String = "",
        val cookieSent: Boolean = false
    ) {
        val ok: Boolean get() = error.isEmpty() && httpCode in 200..299
    }

    fun fetch(url: String, timeoutMs: Int = 20000): Outcome {
        var conn: HttpURLConnection? = null
        return try {
            val cookie = readCookie(url)
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                setRequestProperty("Cache-Control", "no-cache")
                if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { it.readBytes() } ?: ByteArray(0)
            val html = HtmlText.decodeBytes(raw)
            val finalUrl = conn.url?.toString().orEmpty()

            storeResponseCookies(conn, finalUrl)

            Outcome(
                httpCode = code,
                finalUrl = finalUrl,
                html = html,
                bytes = raw.size,
                loginExpired = LoginState.needsLogin(url, finalUrl, code, html),
                cookieSent = cookie.isNotEmpty()
            )
        } catch (e: Exception) {
            Outcome(error = (e.javaClass.simpleName + ": " + (e.message ?: "")).trim())
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    fun readCookie(url: String): String =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()

    fun clearCookies() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    private fun storeResponseCookies(conn: HttpURLConnection, url: String) {
        if (url.isEmpty()) return
        runCatching {
            val cm = CookieManager.getInstance()
            for ((key, values) in conn.headerFields) {
                if (!key.equals("Set-Cookie", ignoreCase = true)) continue
                for (value in values ?: emptyList()) cm.setCookie(url, value)
            }
            cm.flush()
        }
    }

    /** 落到 SSO / login.jsp、或页面里没有课表表格却像登录页，即判定需要登录 */
    fun isLoginExpired(requestedUrl: String, finalUrl: String, httpCode: Int, html: String): Boolean =
        LoginState.needsLogin(requestedUrl, finalUrl, httpCode, html)
}
