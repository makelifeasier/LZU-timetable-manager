import Foundation

//  课表链接自动发现 —— 与 Android 版 `TimetableLink.kt` 对应。
//
//  为什么需要它：课表页形如
//    …/showTimetable.do?id=<学籍内部号>&yearid=<学年>&termid=<学期>&…
//  `id` 每人不同、`yearid/termid` 每学期都变，硬编码任何一条都只有作者能用。

enum TimetableLink {

    static let origin = "https://jwk.lzu.edu.cn"
    static let coursearrangeDir = "/academic/manager/coursearrange/"
    static let path = "/academic/manager/coursearrange/showTimetable.do"

    /// 发现脚本的哨兵值：脚本语法错误时 WebView 会静默返回空，
    /// 看上去和「页面里真的没有入口」一模一样，靠它区分。
    static let sentinel = "LZU-DISCOVERY-V1"

    // MARK: - 归一化

    /// HTML 实体还原（链接在 <a href> 里常写成 &amp;）
    static func unescape(_ u: String) -> String {
        u.replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&#38;", with: "&")
            .replacingOccurrences(of: "&#x26;", with: "&")
            .replacingOccurrences(of: "&quot;", with: "\"")
    }

    /// 相对路径补全成绝对链接。`base` 必须是**门户自身的** scheme://host[:port]，
    /// 否则学校换域名后就会去请求一个错的站点。
    static func absolutize(_ u: String, base: String = origin) -> String {
        let s = u.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("http://") || s.hasPrefix("https://") { return s }
        if s.hasPrefix("//") { return "https:" + s }
        if s.hasPrefix("/") { return base + s }
        if s.isEmpty { return s }
        if s.contains("showTimetable.do") { return base + coursearrangeDir + s }
        return base + "/" + s
    }

    /// 从 URL 取「协议://主机[:端口]」
    static func originOf(_ url: String) -> String {
        let s = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let r = s.range(of: "://") else { return origin }
        let scheme = String(s[s.startIndex..<r.lowerBound])
        var rest = String(s[r.upperBound...])
        rest = rest.components(separatedBy: "/")[0]
            .components(separatedBy: "?")[0]
            .components(separatedBy: "#")[0]
        return rest.isEmpty ? origin : "\(scheme)://\(rest)"
    }

    static func hostOf(_ url: String) -> String {
        var s = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if let r = s.range(of: "://") { s = String(s[r.upperBound...]) }
        s = s.components(separatedBy: "/")[0]
            .components(separatedBy: "?")[0]
            .components(separatedBy: "#")[0]
        if let at = s.lastIndex(of: "@") { s = String(s[s.index(after: at)...]) }
        s = s.components(separatedBy: ":")[0]
        return s
    }

    /// 取 query 参数
    static func param(_ url: String, _ key: String) -> String? {
        guard let q = url.split(separator: "?", maxSplits: 1).dropFirst().first else { return nil }
        for pair in q.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1)
            if kv.first.map(String.init) == key { return kv.count > 1 ? String(kv[1]) : "" }
        }
        return nil
    }

    // MARK: - 发现脚本返回值

    struct Discovery {
        var ran: Bool
        var urls: [String]
    }

    /// 解析注入脚本的返回值。JS 若返回字符串，WebView 会再包一层引号并转义：
    ///   "[\"http://a\",\"http://b\"]"
    static func parseDiscovery(_ raw: String?) -> Discovery {
        let all = parseJsArray(raw)
        let ran = all.first == sentinel
        return Discovery(ran: ran, urls: ran ? Array(all.dropFirst()) : all)
    }

    /// 极简 JSON 字符串数组解析（不依赖 JSONSerialization，便于与 Android 侧对照）
    static func parseJsArray(_ raw: String?) -> [String] {
        guard var body = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !body.isEmpty,
              body != "null", body != "undefined" else { return [] }

        if body.hasPrefix("\"") && body.hasSuffix("\"") && body.count >= 2 {
            body = String(body.dropFirst().dropLast())
                .replacingOccurrences(of: "\\\"", with: "\"")
                .replacingOccurrences(of: "\\\\", with: "\\")
        }
        if body.hasPrefix("[") { body = String(body.dropFirst()) }
        if body.hasSuffix("]") { body = String(body.dropLast()) }

        var out: [String] = []
        let chars = Array(body)
        var i = 0
        while i < chars.count {
            guard chars[i] == "\"" else { i += 1; continue }
            var sb = ""
            i += 1
            while i < chars.count && chars[i] != "\"" {
                let c = chars[i]
                if c == "\\" && i + 1 < chars.count {
                    switch chars[i + 1] {
                    case "n": sb.append("\n")
                    case "t": sb.append("\t")
                    case "r": sb.append("\r")
                    case "\"": sb.append("\"")
                    case "\\": sb.append("\\")
                    case "/": sb.append("/")
                    case "u":
                        let hex = String(chars[(i + 2)..<min(i + 6, chars.count)])
                        if let code = UInt32(hex, radix: 16), let scalar = Unicode.Scalar(code) {
                            sb.unicodeScalars.append(scalar)
                            i += 4
                        } else {
                            sb.append("\\")
                        }
                    default: sb.append(chars[i + 1])
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i += 1
                }
            }
            out.append(sb)
            i += 1
        }
        return out
    }

    // MARK: - 挑链接

    static func normalize(_ candidates: [String], origin base: String = origin) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for c in candidates {
            let t = c.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.isEmpty { continue }
            let abs = absolutize(unescape(t), base: base)
            guard abs.contains("showTimetable") else { continue }
            if seen.insert(abs).inserted { out.append(abs) }
        }
        return out
    }

    /// 优先「学生 · 小节课表（BASE）」：App 解析的是小节时间，大节课表(COMBINE)列不出来
    static func pick(_ candidates: [String], origin base: String = origin) -> String? {
        let uniq = normalize(candidates, origin: base)
        guard !uniq.isEmpty else { return nil }

        func score(_ u: String) -> Int {
            var s = 0
            if param(u, "timetableType") == "STUDENT" { s += 100 }
            if param(u, "sectionType") == "BASE" { s += 50 }
            if !(param(u, "id") ?? "").isEmpty { s += 10 }
            if !(param(u, "yearid") ?? "").isEmpty { s += 5 }
            if !(param(u, "termid") ?? "").isEmpty { s += 5 }
            return s
        }

        var best: String? = nil
        var bestScore = -1
        for u in uniq {
            let sc = score(u)
            if sc > bestScore { bestScore = sc; best = u }
        }
        return best
    }

    /// 一条候选都没有时的兜底：去掉 id，只带见过的 yearid/termid
    static func fallback(_ candidates: [String], origin base: String = origin) -> String {
        let uniq = normalize(candidates, origin: base)
        let yearId = uniq.compactMap { param($0, "yearid") }.first { !$0.isEmpty }
        let termId = uniq.compactMap { param($0, "termid") }.first { !$0.isEmpty }
        var parts: [String] = []
        if let y = yearId { parts.append("yearid=\(y)") }
        if let t = termId { parts.append("termid=\(t)") }
        parts.append("timetableType=STUDENT")
        parts.append("sectionType=BASE")
        return base + path + "?" + parts.joined(separator: "&")
    }

    /// 这条链接值不值得真去请求一次？
    ///
    /// **真机实测**：没有 `yearid` 时教务系统直接回一张「学年传递错误」的提示页，
    /// 这种请求注定失败，就不该白发一次、再甩一张看不懂的错误页给用户。
    static func hasYearParam(_ url: String) -> Bool {
        !(param(url, "yearid") ?? "").isEmpty
    }

    static func resolve(_ candidates: [String], origin base: String = origin) -> String {
        pick(candidates, origin: base) ?? fallback(candidates, origin: base)
    }
}
