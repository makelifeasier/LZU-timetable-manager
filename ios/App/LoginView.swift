import SwiftUI
import WebKit

//  登录并自动导入课表 —— 与 Android 版 `LoginActivity.kt` 对应。
//
//  关键教训（真机实测，与平台无关，所以原样搬过来）：
//   1. **不能直接打开课表 URL 让用户登录**：未登录访问 showTimetable.do 会被重定向到
//      /manager/coursearrange/login.jsp，而那个路径在部署里不存在（404、没有登录框）。
//      正确入口是门户 https://jwk.lzu.edu.cn/academic/ → 302 → 统一身份认证。
//   2. **菜单可能是点开才生成的**：只扫一遍门户首页会扫到 0 条候选，退回到不带 yearid 的
//      兜底链接 → 教务系统回一张「学年传递错误」。所以按步骤来：
//      扫描 → 等 AJAX → 展开父菜单 → 再扫 → 点「学生课表」→ 再扫。
//   3. **已保存的链接优先**：有就直接打开，跳过整个发现流程（这就是「登录后自动跳课表页」）。

struct LoginView: View {

    var onFinish: (Bool) -> Void

    @State private var status = "正在打开统一身份认证…"
    @State private var ready = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Text(status)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.vertical, 8)

                WebViewBox(
                    start: LoginView.startURL,
                    onStatus: { status = $0 },
                    onImported: { onFinish(true) },
                    onCancel: { onFinish(false) }
                )
            }
            .navigationTitle("登录并导入课表")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    /// 首次进入门户；已导入过则直接开上次那条链接（= 1.9 那样自动跳课表页）
    static var startURL: URL {
        let saved = Store.timetableURL
        if !saved.isEmpty, let u = URL(string: saved) { return u }
        return URL(string: "https://jwk.lzu.edu.cn/academic/")!
    }
}

// MARK: - WKWebView 封装（含多步发现）

struct WebViewBox: UIViewRepresentable {

    let start: URL
    let onStatus: (String) -> Void
    let onImported: () -> Void
    let onCancel: () -> Void

    /// 当前活动实例，供工具栏动作调用
    private static weak var active: Coordinator?

    static func importCurrentPage() { active?.capture() }
    static func relaunchPortal() {
        active?.webView.load(URLRequest(url: URL(string: "https://jwk.lzu.edu.cn/academic/")!))
    }
    static func clearLogin() {
        WKWebsiteDataStore.default().httpCookieStore.getAllCookies { cookies in
            cookies.forEach { WKWebsiteDataStore.default().httpCookieStore.delete($0) }
            DispatchQueue.main.async { relaunchPortal() }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .default()
        // 认证页会混用 http 资源，宽容一点避免白屏
        cfg.defaultWebpagePreferences.allowsContentJavaScript = true
        let web = WKWebView(frame: .zero, configuration: cfg)
        web.navigationDelegate = context.coordinator
        web.customUserAgent = nil
        web.load(URLRequest(url: start))
        context.coordinator.webView = web
        Self.active = context.coordinator
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.stopLoading()
        if active === coordinator { active = nil }
    }

    final class Coordinator: NSObject, WKNavigationDelegate {

        let parent: WebViewBox
        weak var webView: WKWebView?

        private var captured = false
        private var discoveryDone = false
        private var rounds = 0
        private let maxRounds = 4

        init(_ p: WebViewBox) { parent = p }

        // MARK: 导航

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            let url = webView.url?.absoluteString ?? ""

            // 1) 已经是课表页 → 抓取
            if url.contains("showTimetable") {
                parent.onStatus("已进入学生课表页，正在提取…")
                capture()
                return
            }
            // 2) 认证页 → 等用户登录
            if url.contains("sso.lzu.edu.cn") {
                parent.onStatus("请在下方登录（扫码或账号密码均可），登录成功后会自动导入课表")
                return
            }
            // 3) 失效的登录页 → 兜回门户（与 Android 版同一条规则）
            if url.contains("/manager/coursearrange/login.jsp") {
                parent.onStatus("该链接的登录页在教务系统里已失效（404），改走统一身份认证入口…")
                webView.load(URLRequest(url: URL(string: "https://jwk.lzu.edu.cn/academic/")!))
                return
            }
            // 4) 已登录门户
            if url.contains("jwk.lzu.edu.cn") {
                let saved = Store.timetableURL
                if !saved.isEmpty && !discoveryDone && rounds == 0 {
                    parent.onStatus("正在打开上次的课表页…")
                    rounds += 1
                    webView.load(URLRequest(url: URL(string: saved)!))
                    return
                }
                if !discoveryDone {
                    discoveryDone = true
                    rounds += 1
                    parent.onStatus("已登录，正在从门户里查找你的课表…")
                    discover(step: 1)
                    return
                }
                parent.onStatus("当前页面：\(short(url))（可点 ⋮ →「导入当前页面」手动抓取）")
                return
            }
            parent.onStatus("当前页面：\(short(url))")
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            parent.onStatus("加载失败：\(error.localizedDescription)")
        }

        // MARK: 抓取

        func capture() {
            guard !captured else { return }
            captured = true
            webView?.evaluateJavaScript("(function(){return document.documentElement.outerHTML;})()") { [weak self] r, _ in
                guard let self else { return }
                self.captured = false
                guard let html = r as? String, html.contains("<") else {
                    self.parent.onStatus("未取到页面源码，请重试")
                    return
                }
                self.accept(html)
            }
        }

        private func accept(_ html: String) {
            let parsed = TimetableParser.parse(html)
            guard !parsed.sessions.isEmpty else {
                // 把服务器的话翻译成可操作的建议
                if html.contains("学年传递错误") {
                    parent.onStatus("教务系统提示「学年传递错误」——请在网页里点开「学生课表」，再点 ⋮ →「导入当前页面」。")
                } else if html.contains("提示信息") && html.contains("error") {
                    parent.onStatus("教务系统返回的是提示页，不是课表页。请在网页里点开「学生课表」后手动导入。")
                } else {
                    parent.onStatus("这个页面里没有课表表格。请在网页里点开「学生课表」，再点 ⋮ →「导入当前页面」。")
                }
                return
            }
            Store.result = parsed
            Store.ensureWeek1(parsed)
            Store.lastSync = Date()
            Store.lastMessage = "已导入 \(parsed.sessions.count) 段课"
            if let u = webView?.url?.absoluteString, u.contains("showTimetable") {
                Store.timetableURL = u
            }
            parent.onStatus("导入成功：\(parsed.sessions.count) 段课")
            parent.onImported()
        }

        // MARK: 多步发现（扫描 → 等待 → 展开父菜单 → 扫描 → 点课表 → 扫描）

        private func discover(step: Int) {
            guard let webView else { return }
            switch step {
            case 1, 2, 4, 6:
                webView.evaluateJavaScript(Self.discoveryJS) { [weak self] r, _ in
                    guard let self else { return }
                    let d = TimetableLink.parseDiscovery(r as? String)
                    let base = TimetableLink.originOf(webView.url?.absoluteString ?? "")
                    if let picked = TimetableLink.pick(d.urls, origin: base) {
                        self.parent.onStatus("已找到你的课表链接，正在打开…")
                        webView.load(URLRequest(url: URL(string: picked)!))
                        return
                    }
                    switch step {
                    case 1:
                        // 菜单常是加载完之后由 AJAX 填进来的 → 等一会儿再扫
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { self.discover(step: 2) }
                    case 2:
                        self.parent.onStatus("试着展开「信息查询」这类菜单…")
                        self.click(WebViewBox.parentKeys) { self.discover(step: 4) }
                    case 4:
                        self.click(WebViewBox.timetableKeys) { self.discover(step: 6) }
                    default:
                        self.giveUp(d.urls, base: base)
                    }
                }
            default:
                giveUp([], base: TimetableLink.origin)
            }
        }

        private func click(_ keys: [String], then: @escaping () -> Void) {
            webView?.evaluateJavaScript(Self.clickJS(keys)) { _, _ in
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { then() }
            }
        }

        /// 收手：兜底链接若没有 yearid，请求注定返回「学年传递错误」，那就别发
        private func giveUp(_ candidates: [String], base: String) {
            let best = TimetableLink.pick(candidates, origin: base)
                ?? TimetableLink.fallback(candidates + [Store.timetableURL], origin: base)
            if TimetableLink.hasYearParam(best), let u = URL(string: best) {
                parent.onStatus("没找到课表入口，试着直接打开…")
                webView?.load(URLRequest(url: u))
            } else {
                parent.onStatus("没能自动找到课表入口。请在网页里点开「学生课表」，再点 ⋮ →「导入当前页面」。")
            }
        }

        private func short(_ s: String) -> String { s.count > 64 ? String(s.prefix(64)) + "…" : s }

        // MARK: 注入脚本
        //
        //  刻意**不用正则字面量**：JS 正则写在原生字符串里，`\]`、`\\` 这类转义极易写错，
        //  写错就是「整个脚本语法错误 → 静默返回空」，表现为「永远走兜底链接」，
        //  极难排查（Android 版真实踩过一次）。用 indexOf 扫字符串没有转义坑。

        static let parentKeys = ["信息查询", "教学信息", "课程信息", "教学管理", "我的课程", "公共信息",
                                 "综合查询", "培养方案", "学籍信息", "教学评价", "选课管理", "课程安排", "课表管理"]
        static let timetableKeys = ["学生课表", "我的课表", "课表查询", "本学期课表", "课表"]

        static let discoveryJS = """
        (function(){
          try{
            var out=[];
            var key='showTimetable.do?';
            function push(u){ if(u){ u=String(u); if(u.indexOf('showTimetable')>=0) out.push(u); } }
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
            var at=0;
            while(true){
              var i=html.indexOf(key,at);
              if(i<0) break;
              var j=i;
              while(j<html.length && '"\\'<>\\\\ \\t\\r\\n'.indexOf(html.charAt(j))<0) j++;
              out.push(html.substring(i,j));
              at=j+1;
            }
            var uniq=[],seen={};
            for(var x=0;x<out.length;x++){ if(!seen[out[x]]){ seen[out[x]]=1; uniq.push(out[x]); } }
            return ['\(TimetableLink.sentinel)'].concat(uniq);
          }catch(e){ return []; }
        })()
        """

        static func clickJS(_ keys: [String]) -> String {
            let arr = keys.map { "'\($0)'" }.joined(separator: ",")
            return """
            (function(){
              try{
                var keys=[\(arr)];
                function squeeze(s){ var o=''; for(var i=0;i<s.length;i++){ var c=s.charAt(i);
                  if(c!==' '&&c!=='\\t'&&c!=='\\n'&&c!=='\\r'&&c!=='\\u3000') o+=c; } return o; }
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
            """
        }
    }
}
