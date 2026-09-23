import Foundation

//  HTML 文本工具 —— 与 Android 版 `HtmlText.kt` 对应，纯函数、无 UI 依赖。
//
//  针对兰大教务系统页面的两个坑：
//   1. 浏览器保存页面时注入的 <wbr>（Firefox 自动换行）会把「999999999999」切成
//      「9999999999<wbr>99」，必须剥离后再做正则。
//   2. 保存版页面尾部会附带浏览器扩展注入的 <div id="immersive-translate-popup">，
//      体积大且含 SVG，解析时应忽略。

enum HtmlText {

    private static let entities: [String: String] = [
        "nbsp": " ", "amp": "&", "lt": "<", "gt": ">", "quot": "\"",
        "apos": "'", "ldquo": "\u{201C}", "rdquo": "\u{201D}", "lsquo": "\u{2018}",
        "rsquo": "\u{2019}", "hellip": "\u{2026}", "mdash": "\u{2014}", "ndash": "\u{2013}",
        "middot": "\u{00B7}", "times": "\u{00D7}", "copy": "\u{00A9}", "brvbar": "\u{00A6}"
    ]

    private static let wbrRe = try! NSRegularExpression(pattern: "<wbr\\s*/?>", options: [.caseInsensitive])
    private static let scriptRe = try! NSRegularExpression(pattern: "<script\\b.*?</script\\s*>", options: [.caseInsensitive, .dotMatchesLineSeparators])
    private static let styleRe = try! NSRegularExpression(pattern: "<style\\b.*?</style\\s*>", options: [.caseInsensitive, .dotMatchesLineSeparators])
    private static let commentRe = try! NSRegularExpression(pattern: "<!--.*?-->", options: [.dotMatchesLineSeparators])
    private static let brRe = try! NSRegularExpression(pattern: "<br\\s*/?>", options: [.caseInsensitive])
    private static let tagRe = try! NSRegularExpression(pattern: "<[^>]+>", options: [.dotMatchesLineSeparators, .caseInsensitive])
    private static let wsRe = try! NSRegularExpression(pattern: "\\s+")

    /// 解码 HTML 实体（含数字实体 &#39; / &#x4e2d;）
    static func decode(_ s: String) -> String {
        guard s.contains("&") else { return s }
        var out = ""
        out.reserveCapacity(s.count)
        let chars = Array(s)
        var i = 0
        while i < chars.count {
            let c = chars[i]
            guard c == "&" else { out.append(c); i += 1; continue }
            // 找分号，且实体名不能太长
            var semi = -1
            var k = i + 1
            while k < chars.count && k - i <= 12 {
                if chars[k] == ";" { semi = k; break }
                k += 1
            }
            guard semi > 0 else { out.append(c); i += 1; continue }
            let name = String(chars[(i + 1)..<semi])
            if name.hasPrefix("#") {
                let body = String(name.dropFirst())
                let cp: UInt32? = (body.hasPrefix("x") || body.hasPrefix("X"))
                    ? UInt32(body.dropFirst(), radix: 16)
                    : UInt32(body)
                if let cp, cp > 0, cp <= 0x10FFFF, let scalar = Unicode.Scalar(cp) {
                    out.unicodeScalars.append(scalar)
                    i = semi + 1
                    continue
                }
                out.append(c); i += 1; continue
            }
            if let rep = entities[name.lowercased()] {
                out += rep
                i = semi + 1
                continue
            }
            out.append(c); i += 1
        }
        return out
    }

    private static func replace(_ re: NSRegularExpression, _ s: String, _ with: String) -> String {
        re.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: with)
    }

    /// 剥离 <wbr>、脚本、样式、注释 —— 保结构、去噪声
    static func stripNoise(_ html: String) -> String {
        var s = replace(wbrRe, html, "")
        s = replace(scriptRe, s, "")
        s = replace(styleRe, s, "")
        s = replace(commentRe, s, "")
        return s
    }

    /// 标签剥离，得到纯文本（<br> 视为换行）
    static func plainText(_ html: String) -> String {
        decode(replace(tagRe, replace(brRe, html, "\n"), " "))
    }

    /// 全空白折叠成单空格
    static func squeeze(_ s: String) -> String {
        replace(wsRe, s, " ").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 单元格内容 → 有效文本行。`&nbsp;` 单元格会得到空数组。
    static func cellLines(_ cellHtml: String) -> [String] {
        let withBreaks = replace(brRe, cellHtml, "\n")
        let noTags = replace(tagRe, withBreaks, "")
        return decode(noTags)
            .replacingOccurrences(of: "\u{00A0}", with: " ")
            .components(separatedBy: "\n")
            .map { squeeze($0) }
            .filter { !$0.isEmpty }
    }

    /// 取 `<tag id="id">…</tag>` 的内部 HTML，按同名标签配平（支持嵌套同名标签）。
    static func extractById(_ html: String, id: String, tag: String = "table") -> String? {
        let escaped = NSRegularExpression.escapedPattern(for: id)
        let openRe = try! NSRegularExpression(
            pattern: "<" + tag + "\\b[^>]*\\bid\\s*=\\s*[\"']" + escaped + "[\"'][^>]*>",
            options: [.caseInsensitive, .dotMatchesLineSeparators]
        )
        let openTag = try! NSRegularExpression(pattern: "<" + tag + "\\b", options: [.caseInsensitive, .dotMatchesLineSeparators])
        let closeTag = try! NSRegularExpression(pattern: "</" + tag + "\\s*>", options: [.caseInsensitive, .dotMatchesLineSeparators])

        let ns = html as NSString
        let full = NSRange(location: 0, length: ns.length)
        guard let head = openRe.firstMatch(in: html, range: full) else { return nil }

        let start = head.range.location + head.range.length
        var depth = 1
        var i = start
        while i < ns.length {
            let nextOpen = openTag.firstMatch(in: html, range: NSRange(location: i, length: ns.length - i))
            let nextClose = closeTag.firstMatch(in: html, range: NSRange(location: i, length: ns.length - i))
            guard let close = nextClose else { return nil }
            if let open = nextOpen, open.range.location < close.range.location {
                depth += 1
                i = open.range.location + open.range.length
            } else {
                depth -= 1
                if depth == 0 {
                    return ns.substring(with: NSRange(location: start, length: close.range.location - start))
                }
                i = close.range.location + close.range.length
            }
        }
        return nil
    }

    /// 从字节流解码：优先 meta 声明，其次 GBK（教务系统实际编码），最后 UTF-8 兜底
    static func decodeBytes(_ data: Data) -> String {
        let headData = data.prefix(4096)
        let head = String(data: headData, encoding: .isoLatin1) ?? ""
        let declaredRe = try! NSRegularExpression(pattern: "charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", options: [.caseInsensitive])
        var declared: String? = nil
        if let m = declaredRe.firstMatch(in: head, range: NSRange(head.startIndex..., in: head)),
           let r = Range(m.range(at: 1), in: head) {
            declared = String(head[r])
        }

        // CoreFoundation 的名字：GBK 在 iOS 上是 kCFStringEncodingGB_18030_2000
        var candidates: [String.Encoding] = []
        if let d = declared {
            let enc = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(
                CFStringConvertIANACharSetNameToEncoding(d as CFString)))
            if enc.rawValue != 0 { candidates.append(enc) }
        }
        candidates.append(String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(0x0631))) // GBK/GB18030
        candidates.append(.utf8)

        for enc in candidates {
            if let text = String(data: data, encoding: enc) {
                let bad = text.filter { $0 == "\u{FFFD}" }.count
                // 替换字符过多说明选错编码
                if bad == 0 || bad * 100 < text.count { return text }
            }
        }
        return String(data: data, encoding: .utf8) ?? ""
    }
}
