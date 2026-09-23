package app.timetable

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
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

    /** 刚点过「学生课表」菜单，正在等页面跳转或菜单展开 */
    private var pendingMenuClick = false

    /** 发现步骤的令牌：页面一换就让排队中的旧步骤作废 */
    private var discoveryToken = 0

    /** 本轮是否已经试过「上次成功导入过的课表链接」 */
    private var savedUrlTried = false

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
                if (url != null) binding.loginStatus.text = "正在打开：${shorten(url)}"
            }

            override fun onPageFinished(view: WebView, url: String) {
                onLoaded(url)
            }
        }

        loadStart()
    }

    // ------------------------------------------------------------- 导航决策

    private fun loadStart() {
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
        Prefs.loginTrace = ""
        binding.webView.loadUrl(Prefs.portalUrl)
    }

    private fun onLoaded(url: String) {
        // 导航轨迹。这条日志是排查「登录后一直不出课表」的第一手材料
        Log.i(TAG, "onLoaded: ${shorten(url)}")

        // 刚点过「学生课表」菜单：现在这个页面是点出来的结果，重新扫一轮
        // （点出来的是课表页就直接 CAPTURE，是中间页就再扫）
        if (pendingMenuClick) {
            pendingMenuClick = false
            discoveryDone = false
            Prefs.trace("点菜单后到达新页面，重新扫描")
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
                evaluate(clickJs(MENU_PARENT_KEYS)) { raw ->
                    Prefs.trace("点父菜单：${decodeJsString(raw).orEmpty().ifBlank { "无返回" }}")
                    delayed(STEP_DELAY_CLICK) { runStep(STEP_SCAN_AFTER_EXPAND) }
                }
            }

            STEP_CLICK_TIMETABLE -> {
                binding.loginStatus.text = "试着点开「学生课表」…"
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
        binding.webView.evaluateJavascript(js) { raw -> onResult(raw) }
    }

    /**
     * 延迟执行。
     *
     * 用令牌作废过期回调：页面一换（onLoaded 里 token++），上一页排队的步骤就作废 ——
     * 否则「点击后跳转」和「点击后原地展开」两条路径会同时往下跑，状态互相打架。
     */
    private fun delayed(ms: Long, body: () -> Unit) {
        val token = ++discoveryToken
        binding.webView.postDelayed({ if (token == discoveryToken) body() }, ms)
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
        capturing = true
        binding.webView.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw ->
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
                    binding.loginStatus.text = failMsg
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
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                Toast.makeText(this, "已清空登录状态", Toast.LENGTH_SHORT).show()
                loadStart()
            }
            true
        }

        else -> false
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
