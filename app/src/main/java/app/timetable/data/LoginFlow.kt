package app.timetable.data

/**
 * 登录页导航决策。纯函数，便于单测 —— 这段逻辑有真实的死循环风险：
 * 门户 → 课表 → 坏链 login.jsp → 门户 → …
 *
 * 背景：未登录访问课表 URL 时教务系统会重定向到
 * `https://jwk.lzu.edu.cn/manager/coursearrange/login.jsp`，而该路径是 404（没有登录框），
 * 所以必须改走门户 `https://jwk.lzu.edu.cn/academic/`（302 到统一身份认证）。
 */
object LoginFlow {

    enum class Step {
        /** 当前就是课表页 → 抓取 */
        CAPTURE,

        /** 撞上失效的 login.jsp → 改走门户 */
        BOUNCE_TO_PORTAL,

        /** 停在统一身份认证页 → 提示用户登录 */
        SHOW_LOGIN_HINT,

        /** 认证票据回跳页 → 回到门户首页（此页不是门户，不能在这里找课表） */
        GO_PORTAL,

        /** 已登录门户 → 在页面里自动发现"本人的"课表链接 */
        DO_DISCOVERY,

        /** 已登录回到教务系统 → 打开课表 URL */
        GO_TIMETABLE,

        /** 其他页面：只更新状态文字，不自动跳转 */
        SHOW_CURRENT
    }

    /**
     * @param alreadyBouncedFromBrokenLogin 撞坏链后是否已经兜回过门户
     * @param alreadyNavigatedToTimetable   是否已经跳过一次课表页（防止反复跳转）
     * @param discoveryDone                 是否已经在本轮登录里跑过课表链接自动发现
     * @param wentToPortalAfterTicket       拿到 CAS 票据后是否已经回过门户
     * @param portalHost                    登录入口的主机名。「已登录门户」按它判断，
     *                                      而不是写死 jwk.lzu.edu.cn —— 学校换域名/换部署时
     *                                      自动发现不能跟着一起失效。
     */
    fun next(
        url: String,
        alreadyBouncedFromBrokenLogin: Boolean,
        alreadyNavigatedToTimetable: Boolean,
        discoveryDone: Boolean = false,
        wentToPortalAfterTicket: Boolean = false,
        portalHost: String = DEFAULT_PORTAL_HOST
    ): Step = when {
        // 是不是"课表页"按**特征表**判断，而不是写死兰大那一条路径（原来只认 showTimetable）。
        // 而教务系统换代/改版后路径会变（历史上前后的路径名换过好几轮）——
        // 只认一个字符串的话，学校一改版就会走到最后一步却永远不触发抓取，
        // 表现就是"选完学校、登录成功、页面停在课表上，App 却说没找到入口"。
        TimetableLink.looksLikeTimetable(url) -> Step.CAPTURE

        // 教务系统那个失效的登录页：/manager/coursearrange/login.jsp（404、没有登录框）
        url.contains("/manager/coursearrange/login.jsp") ->
            if (alreadyBouncedFromBrokenLogin) Step.SHOW_CURRENT else Step.BOUNCE_TO_PORTAL

        url.contains("sso.lzu.edu.cn") -> Step.SHOW_LOGIN_HINT

        // CAS 票据回跳页，说明认证刚通过。这一页只是中转，先回门户首页 ——
        // 课表链接要在真正的门户页面上才扫得到。
        isCasTicketReturn(url) ->
            if (wentToPortalAfterTicket) Step.SHOW_CURRENT else Step.GO_PORTAL

        isSitePage(url, portalHost) && !url.contains("login") ->
            when {
                // 关键：每个学生的课表 id/yearid/termid 都不同，先自动发现本人那条链接
                !discoveryDone -> Step.DO_DISCOVERY
                alreadyNavigatedToTimetable -> Step.SHOW_CURRENT
                else -> Step.GO_TIMETABLE
            }

        else -> Step.SHOW_CURRENT
    }

    const val DEFAULT_PORTAL_HOST = "jwk.lzu.edu.cn"

    /** url 是否属于门户站点 */
    fun isSitePage(url: String, portalHost: String): Boolean {
        val h = hostOf(url)
        return portalHost.isNotBlank() && h.isNotBlank() && (h == portalHost || h.endsWith(".$portalHost"))
    }

    /**
     * 取"可注册域"（去掉 www 之外的前缀，并处理 edu.cn 这类多段后缀）。
     *
     * 为什么需要它：兰大的门户、认证、教务是**同一所大学下的不同子域**
     * （`jwk.lzu.edu.cn` 教务、`sso.lzu.edu.cn` 统一身份认证、`www.lzu.edu.cn` 主页），
     * 登录本来就要在这些子域之间来回跳。按严格同主机判断"是不是本校页面"的话，
     * 浏览器一跳子域就被判成"离开了本站"，**自动发现会当场停住**
     * （表现为"认证回来之后就再也不动了"，只能手动导入）。
     * 按可注册域判断后，`lzu.edu.cn` 的所有子域都算本校，校外域名仍然不算。
     */
    fun baseDomain(host: String): String {
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return h
        val parts = h.split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return h
        val last2 = parts.takeLast(2).joinToString(".")
        // 多段公共后缀：edu.cn / com.cn / net.cn / org.cn / gov.cn / ac.cn
        val multi = setOf("edu.cn", "com.cn", "net.cn", "org.cn", "gov.cn", "ac.cn", "edu.hk", "edu.tw", "edu.mo")
        return if (last2 in multi) parts.takeLast(3).joinToString(".") else last2
    }

    /** 从 URL 取主机名。不依赖 android.net.Uri，便于纯 JVM 单测 */
    fun hostOf(url: String): String {
        var s = url.trim()
        val i = s.indexOf("://")
        if (i >= 0) s = s.substring(i + 3)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfter('@')   // user:pass@host
        return s.substringBefore(':')
    }

    /**
     * CAS 登录票据回跳页。实测服务器会以**明文 http** 回跳：
     *   http://jwk.lzu.edu.cn/academic/login/lzu/loginLds6Valid.jsp?ticket=ST-...
     * 这一步必须放行明文 HTTP（见 res/xml/network_security_config.xml），
     * 且必须在流程里被识别 —— 否则会停在票据页不动。
     */
    fun isCasTicketReturn(url: String): Boolean =
        url.contains("jwk.lzu.edu.cn") &&
            (url.contains("Valid.jsp") || url.contains("/login/lzu/"))

    /**
     * 是否已到达真正的登录页。
     *
     * 到了就意味着上一轮会话作废，两个标记都必须清零：
     *  - `alreadyBouncedFromBrokenLogin`：否则再次撞坏链时不再兜回
     *  - `alreadyNavigatedToTimetable`：否则这次登录成功后就**不会**再自动打开课表页
     *    （例如「打开门户→课表→撞坏链→兜回门户→被 302 到认证页→登录→回到门户」这条路径）
     */
    fun isRealLoginPage(url: String): Boolean = url.contains("sso.lzu.edu.cn")

    // ------------------------------------------------- 主框架加载失败（登录页 WebView）

    /**
     * WebView 的错误码。
     *
     * 故意写成普通 Int 常量、**不 import android.webkit**：本文件是纯 JVM 逻辑
     * （连 hostOf 都不用 android.net.Uri），引了 Android 类型就没法在本机跑单测。
     * 取值与 `WebViewClient.ERROR_*` / `onReceivedSslError` 一一对应，调用方直接把
     * WebView 给的错误码传进来即可。
     */
    const val NET_ERR_HOST_LOOKUP = -2   // WebViewClient.ERROR_HOST_LOOKUP
    const val NET_ERR_CONNECT = -6       // ERROR_CONNECT
    const val NET_ERR_IO = -7            // ERROR_IO
    const val NET_ERR_TIMEOUT = -8       // ERROR_TIMEOUT
    const val NET_ERR_SSL = -11          // ERROR_FAILED_SSL_HANDSHAKE / onReceivedSslError

    /**
     * 主框架加载失败时给用户看的一句话。
     *
     * 为什么要有它：登录页原来只认 `onPageFinished`，门户超时 / DNS 挂掉 / TLS 出错时，
     * 状态栏永远停在「正在打开你的课表…」，WebView 里只有一张系统错误页 ——
     * 既不报错也没有下一步，用户只能干等（而同一条后台抓取路径反而有 20s 超时，见 net/Fetcher）。
     * 所以这段文案必须同时做到两件事：**说清是网络的哪一环**、**给出下一步动作**
     * （重试入口就是登录页右上角的 ⋮ 菜单）。
     *
     * @param sawAuth 本轮是否已经走过认证页/票据页。走过就说明"登录其实是成功的"，
     *   文案不能再让人以为要去重新登录 —— 那是另一类问题（登录成功但没找到课表页）。
     */
    fun loadFailureText(code: Int, sawAuth: Boolean = false): String {
        val what = when (code) {
            NET_ERR_HOST_LOOKUP -> "域名解析不了（多半是断网，或 DNS 不通）"
            NET_ERR_TIMEOUT -> "连接超时（网络太慢，或门户/教务系统没有响应）"
            NET_ERR_CONNECT, NET_ERR_IO -> "连不上服务器（网络断了，或被校园网/代理挡住）"
            NET_ERR_SSL -> "证书校验没过（连接不安全：校内网关劫持、或网关证书有问题时就是这样）"
            else -> "网页没能打开"
        }
        val who = if (sawAuth) "已登录成功，但要打开的页面没打开：" else "网络没通："
        return "$who$what。检查一下网络后，点右上角 ⋮ →「走统一身份认证入口」重新登录，" +
            "或等网络恢复后点 ⋮ →「导入当前页面」重试。"
    }
}
