package app.timetable

import android.content.Intent
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import app.timetable.data.LoginFlow
import app.timetable.data.Prefs
import app.timetable.data.TimetableLink
import app.timetable.data.TimetableRepository
import app.timetable.databinding.ActivityLoginBinding
import app.timetable.ui.BaseActivity
import org.json.JSONTokener

/**
 * 登录并导入课表 —— **任何兰大学生装上就能直接用**。
 *
 * 关于「为什么不能硬编码课表链接」：
 * 课表页形如 …/showTimetable.do?id=<学籍内部号>&yearid=<学年>&termid=<学期>&…
 * `id` 每人不同、`yearid/termid` 每学期都变。所以本 Activity 登录后**自动发现本人的链接**：
 * 先登录门户，再在门户页面里扫出「学生课表」入口（见 LoginFlow.DO_DISCOVERY），
 * 扫不到就用去掉 id 的兜底链接（教务系统通常按会话解析当前学生）。
 *
 * 关键教训（真机实测）：**不能直接打开课表 URL 让用户登录**。
 * 未登录访问 showTimetable.do 时，教务系统会把浏览器重定向到
 *   https://jwk.lzu.edu.cn/manager/coursearrange/login.jsp
 * 而这个路径在本部署里根本不存在（Tomcat 返回 404「文件未找到」），
 * 用户在那里看不到任何登录框，等于被堵死。
 *
 * 正确入口是门户：
 *   https://jwk.lzu.edu.cn/academic/  →302→  https://sso.lzu.edu.cn  （统一身份认证）
 * 登录成功后再从门户里找出课表页取源码。
 */
class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding

    private var capturing = false
    private var bouncedFromBrokenLogin = false
    private var autoNavigatedToTimetable = false

    /** 本轮登录是否已经跑过课表链接自动发现 */
    private var discoveryDone = false

    /** CAS 票据回跳后是否已经回过门户（防止 门户↔票据 反复跳） */
    private var wentToPortalAfterTicket = false

    /** 自动发现出来的课表链接 */
    private var discoveredUrl: String? = null

    /** 本轮是否真的走过了认证页/票据页（= 用户确实登录成功了） */
    private var sawAuthInThisRun = false

    /** 自动发现已进行的「轮」数（每到一个新页面算一轮），用于兜住死循环 */
    private var discoveryRounds = 0

    /** 累积到的候选链接（多轮扫描的结果合并） */
    private val found = ArrayList<String>()

    /**
     * 刚点过页面里的菜单（我们注入的 clickJs），正在等页面跳转或菜单原地展开。
     *
     * 由 [onLoaded] 消费：跳到新页面即"发现中途换页"，要清掉 discoveryDone 重新发现。
     * **只在真正点下去的地方置 true** ——
     * 以前全文件只有两处写 false、没有任何地方写 true，这条分支是够不着的死代码，
     * 于是"点开学生课表跳到中间页"就永远按"发现已完成"往下走。
     */
    private var pendingMenuClick = false

    /**
     * 发现步骤的令牌：页面一换就让排队中的旧步骤作废。
     *
     * 与 [navigationToken] 的分工：这个令牌管"同一次导航内部"的抢占 ——
     * 「点击后跳转」和「点击后原地展开」会各自排一步，谁先到谁作数；
     * 而 [navigationToken] 管"换页"这种更大的事（见 [delayed] 里的双重校验）。
     */
    private var discoveryToken = 0

    /**
     * 主框架第几次开始加载 —— 也就是"这是哪一个页面"的代际号。
     *
     * 由 [onPageStarted] 递增。所有排队中的发现步骤与旧页面的脚本回调都拿它当凭据：
     * 页面一换，上一页排的队全部作废。以前只有 [discoveryToken]、而且换页时没人递增它，
     * 于是点菜单跳转之后，老页面那个"等 1.6 秒再扫一次"会在**新页面**上执行，
     * 把用户从刚到的页面上又强行带走（发现链和用户的点击互相抢导航）。
     */
    private var navigationToken = 0

    /** 本轮是否已经试过「上次成功导入过的课表链接」 */
    private var savedUrlTried = false

    /** 本轮自动发现是在哪一页发起的（用来识别"发现中途换页"，见 [onLoaded]） */
    private var discoveryUrl: String? = null

    /**
     * 抓取的"代际号"。
     *
     * evaluateJavascript 的回调没有超时机制：页面卡住时它可能永远不回，也可能**很久以后才回**。
     * 看门狗超时复位闩锁之后，新一轮抓取会用到这个号 —— 迟到的旧回调发现自己不是当前代，
     * 直接作废，不会去改新一轮的状态。
     */
    private var captureSeq = 0

    /** 抓取看门狗（超时复位闩锁），回调正常回来时撤掉 */
    private var captureWatchdog: Runnable? = null

    /** WebView 是否已销毁：destroy() 之后绝不能再 loadUrl / evaluateJavascript */
    private var webDestroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 用 ViewBinding 后控件类型来自布局 XML：类型对不上会**编译报错**，
        // 而不是像以前那样 findViewById<Button> 运行到这一行才 ClassCastException 闪退
        binding.loginMenuBtn.setOnClickListener { showMenu(it) }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            setSupportMultipleWindows(false)
            // 认证页会混用 http 资源，宽容一点避免白屏
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }

        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // 主框架开始加载 = 换页了 → 作废这一页排下的所有队。
                // 必须在"开始加载"而不是"加载完成"时作废：等 1.2~1.6 秒的那几步正好落在
                // 新页面的加载过程中，晚作废一步它们就已经在新页面上跑起来了
                // （表现：用户刚点进中间页，页面又被发现逻辑点走 / 跳走）。
                navigationToken++
                if (url != null && webUsable()) binding.loginStatus.text = "正在打开：${shorten(url)}"
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!webUsable()) return
                onLoaded(url)
            }

            /**
             * 主框架加载失败（断网 / DNS 解析不了 / 连接超时 / TLS 握手失败…）。
             *
             * **只看主框架**：新版这个回调对**所有资源**都会触发 —— 一张图、一个统计脚本
             * 加载失败不该把整条登录流程打断（子资源失败由 WebView 自己兜着）。
             *
             * 为什么必须补它：本 Activity 原来只认 onPageFinished，于是门户超时的表现是
             * 状态栏**永远**停在「正在打开你的课表…」，WebView 里是一张系统错误页，
             * 既不报错也没有下一步（对照：后台抓取那条路反而有 20s 超时，见 net/Fetcher）。
             */
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                val req = request ?: return
                if (!req.isForMainFrame) return
                failNetwork("主框架加载失败", error?.errorCode ?: 0)
            }

            /**
             * HTTP 4xx/5xx。
             *
             * 这里**不打断流程**：教务系统把「学年传递错误」这类提示页也是用错误码/正常码返回的，
             * 页面本身会渲染出来，后面 onPageFinished 的抓取逻辑能从内容里认出它。
             * 只在状态栏先说一句，免得用户对着"空白页"猜。
             */
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                val req = request ?: return
                if (!req.isForMainFrame) return
                if (!webUsable()) return
                val code = errorResponse?.statusCode ?: 0
                binding.loginStatus.text = "服务器返回 HTTP $code，这一页可能不是课表，正在按页面内容判断…"
                Prefs.trace("主框架 HTTP $code  ${shorten(req.url.toString())}")
            }

            /**
             * 证书错误。
             *
             * **不 proceed()**：放行等于把中间人劫持当正常路径走。默认实现就是 cancel，
             * 这里显式写出来，只为把"为什么打不开"讲给用户听（校园网网关劫持时会遇到）。
             */
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                failNetwork("证书错误 ${error?.primaryError ?: 0}", LoginFlow.NET_ERR_SSL)
            }
        }

        loadStart()
    }

    // ------------------------------------------------------------- 导航决策

    private fun loadStart() {
        // destroy() / 正在结束时绝不能再 loadUrl（登出回调可能晚到，见 MENU_LOGOUT）
        if (!webUsable()) return
        capturing = false
        bouncedFromBrokenLogin = false
        autoNavigatedToTimetable = false
        discoveryDone = false
        wentToPortalAfterTicket = false
        discoveredUrl = null
        sawAuthInThisRun = false
        discoveryRounds = 0
        found.clear()
        pendingMenuClick = false
        savedUrlTried = false
        discoveryUrl = null
        Prefs.loginTrace = ""
        binding.webView.loadUrl(Prefs.portalUrl)
    }

    /**
     * WebView 现在还能用吗。
     *
     * onDestroy 里 destroy() 之后 WebView 不可再碰，而有些回调是**晚到**的：
     * 清 Cookie 的 ValueCallback、网络失败回调、抓取/发现的排队任务。
     * 一律先用它挡一下，避免"Activity 已经退出还在 loadUrl"。
     */
    private fun webUsable(): Boolean = !webDestroyed && !isFinishing && !isDestroyed

    /**
     * 主框架加载失败的统一出口：把原因翻译成人话（含下一步动作），
     * 并**复位抓取闩锁** —— 页面在"正在提取…"时失败，闩锁若不松开，
     * 之后每次自动抓取都会在 [captureCurrentPage] 第一行静默 return，
     * 整轮会话的自动导入全废（只能靠 ⋮ →「导入当前页面」，而它恰好会先复位）。
     */
    private fun failNetwork(what: String, code: Int) {
        if (!webUsable()) return
        capturing = false
        binding.loginStatus.text = LoginFlow.loadFailureText(code, sawAuthInThisRun)
        Prefs.trace("网络失败：$what code=$code url=${shorten(binding.webView.url.orEmpty())}")
    }

    private fun onLoaded(url: String) {
        if (!webUsable()) return

        // 换页作废排队步骤这件事放在 onPageStarted 里做（见 navigationToken）：
        // 那里才是"页面换了"的第一个信号，放到这里等于晚了一整次加载。

        // 导航轨迹。这条日志是排查「登录后一直不出课表」的第一手材料
        Log.i(TAG, "onLoaded: ${shorten(url)}")

        // 刚点过「学生课表」菜单：现在这个页面是点出来的结果，重新扫一轮
        // （点出来的是课表页就直接 CAPTURE，是中间页就再扫）
        if (pendingMenuClick) {
            pendingMenuClick = false
            discoveryDone = false
            Prefs.trace("点菜单后到达新页面，重新扫描")
        }

        // 「发现中途换页」：本轮发现是在 A 页发起的，链接还没选出来就跳到了 B 页
        // （点菜单、页面自己重定向、用户手点，都算）。这时若仍按"发现已完成"往下走，
        // LoginFlow 会给出 GO_TIMETABLE，而 discoveredUrl 是空的 → 首次用户当场看到
        // 「没能自动找到课表入口」就此停住。把标记清掉，让新页面重新发现一轮
        // （轮数上限照旧兜住死循环）。
        // 已经选出了链接、或已经跳过一次课表页时**不清** —— 那两种情况再发现就变成
        // "劫持用户的浏览"了（用户可能正自己点着看）。
        if (discoveryDone && discoveredUrl == null && !autoNavigatedToTimetable &&
            discoveryUrl != null && discoveryUrl != url && discoveryRounds < MAX_DISCOVERY_ROUNDS
        ) {
            discoveryDone = false
            Prefs.trace("发现中途换页，改在新页面重新发现")
        }

        // 到达真正的登录页 = 上一轮会话作废，四个标记都要清零。
        // 尤其是 discoveryDone：不清的话「重新登录后」不会再自动发现课表。
        if (LoginFlow.isRealLoginPage(url)) {
            bouncedFromBrokenLogin = false
            autoNavigatedToTimetable = false
            discoveryDone = false
            wentToPortalAfterTicket = false
            sawAuthInThisRun = true
            discoveryRounds = 0
            found.clear()
        }
        // 票据页 = 认证刚通过。走到这里说明「用户确实登录成功了」，
        // 后面若抓不到课表，提示就不能再说「请先登录」。
        if (LoginFlow.isCasTicketReturn(url)) sawAuthInThisRun = true

        val step = LoginFlow.next(
            url,
            bouncedFromBrokenLogin,
            autoNavigatedToTimetable,
            discoveryDone,
            wentToPortalAfterTicket,
            // 按**可注册域**传入：内置表里多数学校只填了官网（www.x.edu.cn），
            // 教务在 jw.x.edu.cn —— 用严格同主机判断的话，人一点进教务处，自动发现就停了
            portalHost = LoginFlow.baseDomain(LoginFlow.hostOf(Prefs.portalUrl))
        )
        Log.i(TAG, "  决策=$step  portalHost=${LoginFlow.hostOf(Prefs.portalUrl)}")
        Prefs.trace("$step  ${shorten(url)}")

        when (step) {
            LoginFlow.Step.CAPTURE -> {
                binding.loginStatus.text = "已进入学生课表页，正在提取…"
                captureCurrentPage()
            }

            LoginFlow.Step.BOUNCE_TO_PORTAL -> {
                bouncedFromBrokenLogin = true
                binding.loginStatus.text = "该链接的登录页在教务系统里已失效（404），改走统一身份认证入口…"
                binding.webView.loadUrl(Prefs.portalUrl)
            }

            LoginFlow.Step.SHOW_LOGIN_HINT -> {
                binding.loginStatus.text = "请在下方登录（扫码或账号密码均可），登录成功后会自动导入课表"
            }

            LoginFlow.Step.GO_PORTAL -> {
                wentToPortalAfterTicket = true
                binding.loginStatus.text = "认证通过，正在回到教务系统…"
                binding.webView.loadUrl(Prefs.portalUrl)
            }

            LoginFlow.Step.DO_DISCOVERY -> {
                discoveryDone = true
                val saved = Prefs.timetableUrl
                if (saved.isNotBlank() && !savedUrlTried) {
                    // 已经有过一条能用的链接 —— 直接打开，跳过整个发现流程。
                    // 这正是 1.9 的体验：登录完自动跳到课表页读取。发现只是"第一次"才需要。
                    savedUrlTried = true
                    discoveredUrl = saved
                    binding.loginStatus.text = "正在打开上次的课表页…"
                    Prefs.trace("直接用已保存的课表链接（跳过自动发现）")
                    binding.webView.loadUrl(saved)
                } else {
                    // 没有历史链接（或刚才那条已经失效）→ 去门户里找
                    discoverTimetable()
                }
            }

            LoginFlow.Step.GO_TIMETABLE -> {
                autoNavigatedToTimetable = true
                val target = discoveredUrl?.takeIf { it.isNotBlank() } ?: Prefs.timetableUrl
                if (target.isBlank()) {
                    binding.loginStatus.text =
                        "没能自动找到课表入口。请在页面里手动打开「学生课表」，再点 ⋮ →「导入当前页面」"
                } else {
                    binding.loginStatus.text = "正在打开你的课表…"
                    binding.webView.loadUrl(target)
                }
            }

            LoginFlow.Step.SHOW_CURRENT -> {
                binding.loginStatus.text = "当前页面：${shorten(url)}　（可点 ⋮ →「导入当前页面」手动抓取）"
            }
        }
    }

    // ------------------------------------------------------------- 自动发现

    /**
     * 在已登录的门户页面上扫出「本人」的课表入口。
     *
     * **真机实测教训（v2.0 的失败原因）**：只扫一遍门户首页是不够的。
     * 兰大教务系统的菜单很可能是**点开才生成**的树形菜单，首屏 HTML 里根本没有
     * `showTimetable` 字样 —— 于是扫到 0 条候选，退回兜底链接，
     * 而兜底链接没有 `yearid`，教务系统直接回一张「学年传递错误」的提示页。
     *
     * 所以改成多轮：
     *   第 1 次：扫当前页（含 iframe/frame、整页 HTML 里的字面量）
     *   第 2 次：等 1.2 秒再扫 —— 菜单常是加载完之后由 AJAX 填进来的
     *   第 3 次：按文字找「学生课表」并**点它** —— 点开后浏览器地址栏里那条 URL
     *           才带着正确的 id/yearid/termid，这正是用户自己点出来的那条
     * 点击若引起跳转，onPageFinished 会回到 onLoaded 再开一轮；有轮数上限，不会死循环。
     */
    private fun discoverTimetable() {
        discoveryRounds++
        // 记下这一轮是在哪一页发起发现的：下一轮 onLoaded 若发现"换页了、链接还没选出来"，
        // 就知道这是「发现中途换页」，要重新发现而不是直接收尾（见 onLoaded）。
        discoveryUrl = binding.webView.url
        if (discoveryRounds > MAX_DISCOVERY_ROUNDS) {
            Prefs.trace("发现：已试 $discoveryRounds 轮，收手")
            giveUpDiscovery()
            return
        }
        runStep(STEP_SCAN)
    }

    /**
     * 一步一个动作。真实的教务系统门户有三种可能形态，这里全部覆盖：
     *  1. 首屏 HTML 里就有入口链接            → 第 1 步扫描命中
     *  2. 菜单是 AJAX 填充的（首屏没有）      → 第 2 步等 1.2 秒再扫
     *  3. 树形菜单要**点开才生成**（最常见）  → 第 3 步点父菜单，第 4 步再扫，
     *                                          第 5 步点「学生课表」，第 6 步再扫
     * 任何一步点到会跳转的链接时，onPageFinished 会自己开新一轮（有轮数上限）。
     */
    private fun runStep(step: Int) {
        when (step) {
            STEP_SCAN, STEP_SCAN_AGAIN, STEP_SCAN_AFTER_EXPAND, STEP_SCAN_AFTER_TIMETABLE -> {
                binding.loginStatus.text = when (step) {
                    STEP_SCAN -> "已登录，正在从门户里查找你的课表…"
                    STEP_SCAN_AGAIN -> "第一次没扫到，稍等再扫一次…"
                    STEP_SCAN_AFTER_EXPAND -> "菜单展开了，重新扫一次…"
                    else -> "刚点过课表入口，重新扫一次…"
                }
                evaluate(discoveryJs) { raw ->
                    val d = runCatching { TimetableLink.parseDiscovery(raw) }
                        .getOrDefault(TimetableLink.Discovery(false, emptyList()))
                    // 脚本没跑（语法错误 / 被 CSP 拦）必须留下痕迹：
                    // 否则它和「页面里确实没有入口」表现完全一样，极难排查
                    if (!d.ran) Log.w(TAG, "发现脚本未执行，raw=${raw?.take(200)}")
                    Prefs.trace("扫描$step：脚本=${d.ran} 候选=${d.urls.size}")
                    if (d.urls.isNotEmpty()) {
                        found.addAll(d.urls)
                        openPicked()
                        return@evaluate
                    }
                    when (step) {
                        STEP_SCAN -> delayed(STEP_DELAY_AJAX) { runStep(STEP_SCAN_AGAIN) }
                        STEP_SCAN_AGAIN -> runStep(STEP_EXPAND_MENU)
                        STEP_SCAN_AFTER_EXPAND -> runStep(STEP_CLICK_TIMETABLE)
                        else -> giveUpDiscovery()
                    }
                }
            }

            STEP_EXPAND_MENU -> {
                binding.loginStatus.text = "试着展开「信息查询」这类菜单…"
                // 点下去的菜单可能只是原地展开，也可能**跳到新页面**（中间页）。
                // 后者就是「发现中途换页」：由 onLoaded 消费这个标记，清掉 discoveryDone
                // 后在新页面重新发现。这里是唯一给 pendingMenuClick 置位的地方。
                pendingMenuClick = true
                evaluate(clickJs(MENU_PARENT_KEYS)) { raw ->
                    Prefs.trace("点父菜单：${decodeJsString(raw).orEmpty().ifBlank { "无返回" }}")
                    delayed(STEP_DELAY_CLICK) { runStep(STEP_SCAN_AFTER_EXPAND) }
                }
            }

            STEP_CLICK_TIMETABLE -> {
                binding.loginStatus.text = "试着点开「学生课表」…"
                // 同上：点「学生课表」跳到的往往是带正确 id/yearid/termid 的链接，
                // 但也可能是"选择学年"这类中间页 —— 中间页必须重新发现，不能当成"发现已完成"。
                pendingMenuClick = true
                evaluate(clickJs(MENU_TIMETABLE_KEYS)) { raw ->
                    Prefs.trace("点课表入口：${decodeJsString(raw).orEmpty().ifBlank { "无返回" }}")
                    delayed(STEP_DELAY_CLICK) { runStep(STEP_SCAN_AFTER_TIMETABLE) }
                }
            }

            else -> giveUpDiscovery()
        }
    }

    /** 跑一段注入脚本 */
    private fun evaluate(js: String, onResult: (String?) -> Unit) {
        // 晚到的排队步骤可能在 destroy() 之后才跑到这里（见 webUsable）
        if (!webUsable()) return
        val nav = navigationToken
        binding.webView.evaluateJavascript(js) { raw ->
            // 页面已经换掉了 → 这个结果属于上一页，丢掉（否则会在新页面上接着按老页面的
            // 发现进度往下走，把用户从新页面带走）
            if (nav == navigationToken && webUsable()) onResult(raw)
        }
    }

    /**
     * 延迟执行。
     *
     * 双重校验：
     *  - [discoveryToken]：同一次导航内部的抢占 —— 点菜单后「跳转」与「原地展开」
     *    两条路径会各排一步，后者必须作废，否则两条发现链同时往下跑、状态互相打架；
     *  - [navigationToken]：换页 —— 页面一开始加载，上一页排的队全部作废，
     *    绝不把用户从新页面上带走。
     */
    private fun delayed(ms: Long, body: () -> Unit) {
        val nav = navigationToken
        val token = ++discoveryToken
        binding.webView.postDelayed({
            if (nav == navigationToken && token == discoveryToken && webUsable()) body()
        }, ms)
    }

    /** 在累积到的候选里挑一条打开 */
    private fun openPicked() {
        val origin = TimetableLink.originOf(Prefs.portalUrl)
        val picked = TimetableLink.pick(found, origin)
        if (picked == null) {
            giveUpDiscovery()
            return
        }
        discoveredUrl = picked
        binding.loginStatus.text = "已找到你的课表链接（${shorten(picked)}），正在打开…"
        Prefs.trace("  选中=${shorten(picked)}")
        binding.webView.loadUrl(picked)
    }

    /**
     * 收手：不再自动找。
     *
     * 兜底链接若**没有 yearid**，请求是注定失败的（服务器只会回「学年传递错误」），
     * 所以那种情况下不发这次请求，直接把可操作的办法告诉用户 ——
     * 与其甩一张看不懂的教务系统错误页，不如说清"手点一下学生课表再导入"。
     * 上次成功导入过的链接里的 yearid/termid 会继承过来，这时就值得一试。
     */
    private fun giveUpDiscovery() {
        autoNavigatedToTimetable = true
        val origin = TimetableLink.originOf(Prefs.portalUrl)
        val best = TimetableLink.pick(found, origin)
            ?: TimetableLink.fallback(found + Prefs.timetableUrl, origin)
        if (TimetableLink.hasYearParam(best)) {
            discoveredUrl = best
            binding.loginStatus.text = "没找到课表入口，试着直接打开…"
            Prefs.trace("发现：直接试带学年参数的链接 ${shorten(best)}")
            binding.webView.loadUrl(best)
        } else {
            binding.loginStatus.text =
                "没能自动找到课表入口。请在网页里点开「学生课表」，再点 ⋮ →「导入当前页面」（成功后会被记住）。"
            Prefs.trace("发现：无学年参数，不做注定失败的请求，改由用户手动导入")
        }
    }

    /**
     * 按文字点菜单。keys 是要找的文字（先找 <a>，再找任意可点元素）；
     * iframe/frame 内部也找 —— 教务系统常用框架布局。
     * 同样**不用正则**，避免「字符类转义写错 → 整个脚本语法错误」那个坑。
     */
    private fun clickJs(keys: List<String>): String {
        val arr = keys.joinToString(",") { "'$it'" }
        return """
        (function(){
          try{
            var keys=[$arr];
            function squeeze(s){ var o=''; for(var i=0;i<s.length;i++){ var c=s.charAt(i);
              if(c!==' '&&c!=='\t'&&c!=='\n'&&c!=='\r'&&c!=='\u3000') o+=c; } return o; }
            function tryDoc(doc, prefix){
              if(!doc) return null;
              var all=doc.getElementsByTagName('*');
              for(var k=0;k<keys.length;k++){
                for(var i=0;i<all.length;i++){
                  if(all[i].tagName!=='A') continue;
                  var t=squeeze(all[i].textContent||'');
                  if(t.length>0&&t.length<=14&&t.indexOf(keys[k])>=0){ all[i].click(); return prefix+'A:'+t; }
                }
              }
              for(var k=0;k<keys.length;k++){
                for(var i=all.length-1;i>=0;i--){
                  var t=squeeze(all[i].textContent||'');
                  if(t.length>0&&t.length<=14&&t.indexOf(keys[k])>=0){ all[i].click(); return prefix+'E:'+t; }
                }
              }
              return null;
            }
            var r=tryDoc(document,'');
            if(r) return r;
            var fr=document.getElementsByTagName('iframe');
            for(var j=0;j<fr.length;j++){ try{ var r2=tryDoc(fr[j].contentDocument,'frame'+j+':'); if(r2) return r2; }catch(e){} }
            return 'none';
          }catch(e){ return 'err'; }
        })()
        """.trimIndent()
    }

    /**
     * 发现课表的注入脚本。返回候选链接数组（已去重）。
     *
     * 注意这里刻意**不用正则字面量**：JS 正则要写在 Kotlin 原生字符串里，
     * `\]`、`\\` 这类转义极易写错，而写错就是「整个脚本语法错误 → 静默返回空」，
     * 表现为「总是退化成兜底链接」这种极难排查的故障（已真实发生过一次）。
     * 用 indexOf 扫描字符串没有任何转义坑。
     */
    private val discoveryJs: String
        get() = """
        (function(){
          try{
            var out=[];
            var HINTS=[${TimetableLink.TIMETABLE_HINTS.joinToString(",") { "'$it'" }}];
            function hit(u){ if(!u) return false; var l=String(u).toLowerCase();
              for(var h=0;h<HINTS.length;h++){ if(l.indexOf(HINTS[h])>=0) return true; }
              return false;
            }
            function push(u){ if(hit(u)) out.push(String(u)); }
            function scan(doc){ if(!doc) return;
              var as=doc.getElementsByTagName('a');
              for(var i=0;i<as.length;i++){ push(as[i].getAttribute('href')); push(as[i].href); }
            }
            scan(document);
            var fr=document.getElementsByTagName('iframe');
            for(var j=0;j<fr.length;j++){ try{ scan(fr[j].contentDocument); }catch(e){} }
            var fm=document.getElementsByTagName('frame');
            for(var k=0;k<fm.length;k++){ try{ scan(fm[k].contentDocument); }catch(e){} }
            var html=document.documentElement.outerHTML;
            // 整页字面量兜底扫描：对每个特征词找出现位置，再**向前回退**到链接开头
            // （协议、主机、路径都属于同一段），否则只会抓到半截路径。
            for(var h=0;h<HINTS.length;h++){
              var at=0;
              while(true){
                var i=html.toLowerCase().indexOf(HINTS[h],at);
                if(i<0) break;
                var s=i;
                while(s>0){ var c=html.charAt(s-1);
                  if(c===':'||c==='/'||c==='.'||c==='-'||c==='_'||(c>='0'&&c<='9')||(c>='a'&&c<='z')||(c>='A'&&c<='Z')) s--; else break; }
                var j=i+HINTS[h].length;
                while(j<html.length && '"\'<>\\ \t\r\n'.indexOf(html.charAt(j))<0) j++;
                out.push(html.substring(s,j));
                at=j+1;
              }
            }
            var uniq=[],seen={};
            for(var x=0;x<out.length;x++){ if(!seen[out[x]]){ seen[out[x]]=1; uniq.push(out[x]); } }
            return ['${TimetableLink.SENTINEL}'].concat(uniq);
          }catch(e){ return []; }
        })()
        """.trimIndent()

    // ------------------------------------------------------------- 抓取

    private fun captureCurrentPage() {
        if (capturing) return
        if (!webUsable()) return
        capturing = true
        // 本次抓取的代际号：看门狗超时后，这个号会变，迟到的旧回调据此作废
        val seq = ++captureSeq
        armCaptureWatchdog(seq)
        binding.webView.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw ->
            // 看门狗已经判过超时、或已经开了新一轮抓取 → 这次结果没用了，直接丢掉
            if (seq != captureSeq) return@evaluateJavascript
            disarmCaptureWatchdog()
            val html = decodeJsString(raw)
            // 判据是「取到的到底是不是 HTML」，不是长度。
            // 之前用 length > 500：教务系统返回的提示页/错误页可能只有几百字节，
            // 会被误判成"没取到源码"，于是转去后台重抓，把真正的原因（页面里没有课表）
            // 盖成一条毫不相干的网络错误 —— 实测踩到过。
            if (html != null && html.contains("<")) {
                // 提示要分清三种情况，别再误伤已经登录成功的人：
                //  - 教务系统自己吐了提示页（比如「学年传递错误」）→ 直接翻译它的话
                //  - 本轮走过认证页/票据页 → 登录是成功的，问题出在"没找到课表页"
                //  - 否则才真的还没登录
                val firstTime = !Prefs.hasTimetable
                val failMsg = describeServerPage(html) ?: when {
                    sawAuthInThisRun -> "已登录，但没找到课表页。请在网页里手动点开「学生课表」，再点 ⋮ →「导入当前页面」"
                    firstTime -> "还没登录成功，所以这里没有课表。请在上方完成统一身份认证登录，登录后会自动导入。"
                    else -> "这个页面里没有课表表格。请手动导航到「学生课表」，再点 ⋮ →「导入当前页面」"
                }
                val ok = TimetableRepository.acceptHtml(
                    this,
                    html,
                    "登录页",
                    emptyMessage = if (firstTime || sawAuthInThisRun) failMsg else null
                )
                Prefs.trace("抓取：${html.length} 字 → ${if (ok) "成功" else "无课表"}")
                if (!ok) Prefs.trace("  页面像是：${describeServerPage(html)?.take(40) ?: "非课表页"}")
                Prefs.trace("  登录过=${sawAuthInThisRun} 当前URL=${shorten(binding.webView.url.orEmpty())}")
                if (ok) {
                    // 记住这条**真正抓到课表**的链接，后台刷新/小组件都靠它。
                    // 只在确实是课表页时才记，避免把随手浏览的页面存进去。
                    // 判据用特征表（原来写死 showTimetable）：路径一变就存不下链接，
                    // 下次进来就得重新扫一遍菜单 —— 能抓到课却"记不住"，用户会以为没生效。
                    val current = binding.webView.url
                    if (!current.isNullOrBlank() && TimetableLink.looksLikeTimetable(current)) {
                        Prefs.timetableUrl = current
                    }
                    // 把 WebView 的会话落盘，否则进程结束后登录态可能丢
                    runCatching { CookieManager.getInstance().flush() }
                    Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    // 取到的是非课表页 → 这里**绝不能报成功**。
                    // 其中有一类最容易被误报成成功的：页面确实是课表页，却一段课都没解析出来
                    // （本学期还没排课、或页面结构变了）。原来它会复用"没找到课表页"那套文案，
                    // 等于把"解析到 0 门课"和"没找到页"混在一起说。现在分开讲。
                    val atTimetablePage = TimetableLink.looksLikeTimetable(binding.webView.url.orEmpty())
                    binding.loginStatus.text = if (atTimetablePage) ZERO_COURSE_HINT else failMsg
                    capturing = false
                    // 刚才如果是直接打开「上次保存的链接」而没读到课表（常见于跨学期，
                    // 链接里的 yearid 过期了），就**回门户首页**重新找。
                    //
                    // 注意必须先回门户：发现是在「当前页面」上扫的，而在那条失效链接的
                    // 页面上扫，候选永远是 0（实测踩到）。
                    // 不清掉保存的链接：万一这次只是会话临时失效，下次它还能用。
                    if (savedUrlTried && discoveryRounds < MAX_DISCOVERY_ROUNDS) {
                        Prefs.trace("已保存的链接没读到课表，回门户重新找")
                        discoveryDone = false
                        autoNavigatedToTimetable = false
                        binding.webView.loadUrl(Prefs.portalUrl)
                    }
                }
            } else {
                capturing = false
                Prefs.trace("抓取：未取到源码，转后台抓取")
                binding.loginStatus.text = "未取到页面源码，改用后台抓取…"
                TimetableRepository.refresh(this) { st ->
                    // refresh 的回调在主线程，但可能晚到（用户/系统已经把页面关了）
                    if (!webUsable()) return@refresh
                    if (st.ok) {
                        Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        binding.loginStatus.text = "抓取失败：${st.message}（HTTP ${st.httpCode}）"
                    }
                }
            }
        }
    }

    /**
     * 抓取看门狗。
     *
     * `evaluateJavascript` 的回调**没有超时**：页面卡住（或脚本被 CSP 拦掉）它就永远不回来，
     * 而 [capturing] 是单向闩锁 —— 一次没回来，此后每次自动抓取都在 [captureCurrentPage]
     * 第一行 `if (capturing) return` 静默返回，**整轮会话的自动导入全废**：
     * 状态栏停在「已进入学生课表页，正在提取…」不动，只有 ⋮ →「导入当前页面」能救
     * （而它恰好会先把闩锁复位，所以现象是"手动导入管用、自动流程永远不行"）。
     * 这里给等待加一个上限，到点就松开闩锁并告诉用户下一步怎么办。
     */
    private fun armCaptureWatchdog(seq: Int) {
        val r = Runnable {
            if (seq != captureSeq) return@Runnable      // 结果已经回来了，看门狗不用管
            capturing = false
            if (webUsable()) {
                binding.loginStatus.text =
                    "页面没有回话（抓取超时，可能这一页卡住了）。" +
                        "可以点 ⋮ →「导入当前页面」再试一次；如果是登录过期，点「走统一身份认证入口」重新登录。"
            }
            Prefs.trace("抓取：等待页面回话超时，已复位抓取状态")
        }
        captureWatchdog = r
        binding.webView.postDelayed(r, CAPTURE_TIMEOUT_MS)
    }

    /** 结果回来了：撤掉看门狗，免得它过一会儿又把状态改掉 */
    private fun disarmCaptureWatchdog() {
        captureWatchdog?.let { binding.webView.removeCallbacks(it) }
        captureWatchdog = null
    }

    // ------------------------------------------------------------- 菜单

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_IMPORT_NOW, 0, "导入当前页面")
        popup.menu.add(0, MENU_PORTAL, 1, "走统一身份认证入口")
        popup.menu.add(0, MENU_UA, 2, "切换桌面版/手机版")
        popup.menu.add(0, MENU_LOGOUT, 3, "清空登录状态")
        popup.setOnMenuItemClickListener { onMenuAction(it.itemId) }
        popup.show()
    }

    private fun onMenuAction(id: Int): Boolean = when (id) {
        MENU_IMPORT_NOW -> {
            capturing = false
            captureCurrentPage()
            true
        }

        MENU_PORTAL -> {
            loadStart()
            true
        }

        MENU_UA -> {
            val desktop = binding.webView.settings.userAgentString == UA_DESKTOP
            binding.webView.settings.userAgentString = if (desktop) UA_MOBILE else UA_DESKTOP
            Toast.makeText(this, if (desktop) "已切换为手机版" else "已切换为桌面版", Toast.LENGTH_SHORT).show()
            binding.webView.reload()
            true
        }

        MENU_LOGOUT -> {
            // 用户**明确点了登出/要换账号**：本机存的那份课表也要一起清掉，否则就是把上一个人的
            // 东西留给下一个人用：
            //  1. `Prefs.timetableUrl` 那条链接里带着**上一个人的学籍内部号 `id=`**，
            //     不清的话下次登录时自动发现会先走"已经有过一条能用的链接 → 直接打开"这条捷径
            //     （见 DO_DISCOVERY），打开的正是别人的课表；
            //  2. 缓存课表（`Prefs.resultJson`）不清，登出后主页/小组件还在显示上一个人的课。
            // 走仓库现成的入口、不自己造轮子：clearCache() 清派生缓存，reloadFromPrefs()
            // 把"现在是空课表"推下去 —— 后者内部会刷新小组件、重排提醒并通知界面。
            //
            // ⚠ 别和下面抓取失败那条路径搞混：那里"上次保存的链接没读到课表"是**会话临时失效**
            //   （Cookie 过期），链接本身还是对的，清掉反而让下次要重扫一遍菜单，所以刻意不清。
            //   只有用户明确登出这一条路才清。
            Prefs.clearCache()
            Prefs.timetableUrl = ""
            TimetableRepository.reloadFromPrefs(this)

            // 除了 Cookie，还要清 **WebStorage 和表单数据**：本页 domStorageEnabled = true，
            // 门户/教务系统会把上一个账号的痕迹写进 localStorage/sessionStorage。
            // 只 removeAllCookies 的话，换账号登录会带着上一个人的本地存储（可能直接影响课表页的渲染）。
            // WebStorage.deleteAllData() 内部要走 WebView 的 IO 线程，必须在主线程调用 ——
            // 菜单回调本来就是主线程，所以放在这里正好。
            runCatching { WebStorage.getInstance().deleteAllData() }
            binding.webView.clearFormData()
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                // 回调可能在 Activity 已经结束时才回来：那时不能再 loadUrl
                if (!webUsable()) return@removeAllCookies
                Toast.makeText(this, "已清空登录状态与本地课表", Toast.LENGTH_SHORT).show()
                loadStart()
            }
            true
        }

        else -> false
    }

    /**
     * 退出：**先撤掉排队中的定时任务与加载，再 destroy()**。
     *
     * WebView 从来没有 destroy() 是这里的老问题：Activity 没了、WebView 还攥着页面与
     * 后台线程，长时间反复进登录页会白占内存。destroy() 之后这个 WebView 不可再用，
     * 所以顺序必须是"打标记 → 撤定时任务 → 停加载 → destroy"，否则晚到的回调
     * （Cookie 回调、抓取看门狗、发现步骤）会在销毁后再去 loadUrl / 执行脚本。
     */
    override fun onDestroy() {
        webDestroyed = true
        disarmCaptureWatchdog()
        runCatching { binding.webView.stopLoading() }
        runCatching { binding.webView.destroy() }
        super.onDestroy()
    }

    // ------------------------------------------------------------- 工具

    /**
     * 认出教务系统自己的提示页，把服务器的话翻译成用户能照着做的下一步。
     *
     * 实测样例（用户导出回来的原始页面，1704 字节）：
     *   <title>提示信息</title> … <td id="content_margin">学年传递错误 … TB2</td>
     * 收到它说明请求里的学年参数不对 —— 光看「无课表」用户完全不知道该怎么办。
     */
    private fun describeServerPage(html: String): String? = when {
        html.contains("学年传递错误") ->
            "教务系统提示「学年传递错误」—— 拿到了不带学年/学期参数的链接。" +
                "请在网页里点开「学生课表」，再点 ⋮ →「导入当前页面」（成功后这条链接会被记住）。"
        html.contains("提示信息") && html.contains("error") ->
            "教务系统返回的是提示页，不是课表页。请在网页里点开「学生课表」，再点 ⋮ →「导入当前页面」。"
        else -> null
    }

    private fun shorten(url: String): String =
        if (url.length > 70) url.take(70) + "…" else url

    /** evaluateJavascript 回传的是 JSON 字面量，需按 JSON 解析出原字符串 */
    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            (JSONTokener(raw).nextValue() as? String)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            raw.takeIf { it.length > 500 && !it.startsWith("\"") }
        }
    }

    companion object {
        private const val TAG = "Timetable"

        /**
         * 等页面回话（evaluateJavascript 取 outerHTML）的上限。
         *
         * 10 秒：正常页面几百毫秒就回来，超过这个数基本就是卡住了；
         * 又刻意短于后台抓取的 20s（见 net/Fetcher），用户不至于在这里干等太久。
         */
        private const val CAPTURE_TIMEOUT_MS = 10_000L

        /**
         * 「页面是课表页、却一段课都没解析出来」时的文案。
         *
         * 措辞刻意保持**中性**（"没解析出课程"）而不是"导入成功"：0 门课不是成功，
         * 数据库/提示都不能按成功处理。同时给两条可操作的出路，别让人对着空白页猜。
         */
        private const val ZERO_COURSE_HINT =
            "页面已加载，但没解析出课程（可能这学期还没排课，或课表页的结构变了）。" +
                "点 ⋮ →「导入当前页面」可重试，详细原因见 设置 →「诊断」。"

        /**
         * 自动发现最多扫几轮。
         * 每点一次「学生课表」菜单、每跳一个新页面都算一轮；4 轮足够覆盖
         * 「首屏无菜单 → AJAX 填充 → 点开菜单 → 中间页 → 课表页」，又不会死循环。
         */
        private const val MAX_DISCOVERY_ROUNDS = 4

        // 发现流程的步骤（见 runStep）
        private const val STEP_SCAN = 1
        private const val STEP_SCAN_AGAIN = 2
        private const val STEP_EXPAND_MENU = 3
        private const val STEP_SCAN_AFTER_EXPAND = 4
        private const val STEP_CLICK_TIMETABLE = 5
        private const val STEP_SCAN_AFTER_TIMETABLE = 6

        /** 等 AJAX 把菜单填进来的时间 */
        private const val STEP_DELAY_AJAX = 1200L

        /** 等菜单展开 / 页面跳转的时间 */
        private const val STEP_DELAY_CLICK = 1600L

        /**
         * 先展开父菜单（树形菜单一般要点开「信息查询」才出现「学生课表」）。
         *
         * 只放**菜单分组名**，不放「查询」「选课」这种宽泛词 ——
         * 实测「查询」会匹配到叶子项「成绩查询」而被点走，白白浪费一轮。
         */
        private val MENU_PARENT_KEYS = listOf(
            "信息查询", "教学信息", "课程信息", "教学管理", "我的课程", "公共信息",
            "综合查询", "培养方案", "学籍信息", "教学评价", "选课管理", "课程安排", "课表管理"
        )

        /** 课表入口本身 */
        private val MENU_TIMETABLE_KEYS = listOf(
            "学生课表", "我的课表", "课表查询", "本学期课表", "课表"
        )

        private const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val MENU_IMPORT_NOW = 0
        private const val MENU_PORTAL = 1
        private const val MENU_UA = 2
        private const val MENU_LOGOUT = 3
    }
}
