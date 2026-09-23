import Foundation

//  兰大教务系统「学生课表」解析器 —— 与 Android 版 `TimetableParser.kt` 对应，纯函数。
//
//  页面契约（已由真实页面确认）：
//    <table id="timetable">
//      <tr><th>&nbsp;</th><th>周一</th>…<th>周日</th></tr>
//      <tr><th nowrap>第1节<br>08:30<br>┆<br>09:15</th>
//          <td id="1-1" class="center">&lt;&lt;高阶英语1&gt;&gt;;23<br>天山堂A301<br>甲老师<br>4-18周双周<br>讲课学时</td>…
//
//  关键点：单元格 id 形如「星期-节次」，坐标直接给出，不需要靠表头推列。
//
//  取子串一律走 `g(source, match, i)` —— 正则匹配结果只有在**原始字符串**上才能取到子串，
//  脱离 source 的写法是错的（第一版就写错过，特此标注）。

enum TimetableParser {

    private static func re(_ pattern: String, _ options: NSRegularExpression.Options = [.caseInsensitive, .dotMatchesLineSeparators]) -> NSRegularExpression {
        try! NSRegularExpression(pattern: pattern, options: options)
    }

    private static let trRe = re("<tr\\b[^>]*>(.*?)</tr\\s*>")
    private static let cellRe = re("<t([dh])\\b([^>]*)>(.*?)</t\\1\\s*>")
    private static let idRe = re("\\bid\\s*=\\s*[\"']([^\"']*)[\"']")
    private static let rowspanRe = re("\\browspan\\s*=\\s*[\"']?(\\d+)")
    private static let gridIdRe = re("^(\\d+)-(\\d+)$", [])
    private static let weekRe = re("^(\\d+)\\s*(?:-\\s*(\\d+))?\\s*周\\s*(全周|单周|双周|单|双)?$", [])
    private static let nameRe = re("^<<\\s*(.+?)\\s*>>\\s*;?\\s*(\\d*)$", [])
    private static let hmRe = re("^\\d{1,2}:\\d{2}$", [])
    private static let hoursRe = re("学时\\s*$", [])
    private static let roomHintRe = re("[楼室馆场区]", [])
    private static let studentRe = re("学生课表\\s*[:：]\\s*([0-9]{4,})", [])
    private static let yearRe = re("(20\\d{2})\\s*([春秋])", [])
    private static let classRe = re("班级\\s*[:：]\\s*([^\\s(（]{1,40})", [])
    private static let dayLabels = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

    // MARK: - 入口

    static func parse(_ rawHtml: String) -> ParseResult {
        let html = HtmlText.stripNoise(rawHtml)
        let term = parseTerm(html)
        guard let table = HtmlText.extractById(html, id: "timetable") else {
            return ParseResult(term: term)
        }
        let grid = parseGrid(table)
        return ParseResult(term: term,
                           sections: grid.sections,
                           sessions: grid.sessions,
                           unscheduled: parseNoArrangement(html))
    }

    // MARK: - 网格

    private struct Cell {
        var day: Int
        var section: Int
        var rowspan: Int
        var html: String
    }

    private static func parseGrid(_ tableHtml: String) -> (sections: [Section], sessions: [Session]) {
        var sections: [Int: Section] = [:]
        var sessions: [Session] = []
        var lastSection = 0

        for tr in matches(trRe, tableHtml) {
            let inner = g(tableHtml, tr, 1)
            let rawCells = matches(cellRe, inner)
            if rawCells.isEmpty { continue }

            let headers = rawCells.filter { g(inner, $0, 1).lowercased() == "h" }
            let dataCells = rawCells.filter { g(inner, $0, 1).lowercased() == "d" }

            // 天头行：全 th 且出现「周一」…「周日」
            if dataCells.isEmpty {
                let texts = headers.map { HtmlText.squeeze(HtmlText.plainText(g(inner, $0, 3))) }
                if texts.contains(where: { t in dayLabels.contains { t.hasPrefix($0) } || t.hasPrefix("星期") }) {
                    continue
                }
                // 整行都被上方 rowspan 覆盖：仍要登记该节次的上课时间
                if headers.count == 1 {
                    lastSection += 1
                    registerSection(&sections, index: lastSection, headHtml: g(inner, headers[0], 3))
                }
                continue
            }

            var cells: [Cell] = []
            var positionalDay = 0
            for c in dataCells {
                positionalDay += 1
                let attrs = g(inner, c, 2)
                let id = firstMatch(idRe, attrs).map { g(attrs, $0, 1) } ?? ""
                let span = firstMatch(rowspanRe, attrs).flatMap { Int(g(attrs, $0, 1)) } ?? 1

                let gridId = firstMatch(gridIdRe, id)
                let day = gridId.flatMap { Int(g(id, $0, 1)) } ?? positionalDay
                let sec = gridId.flatMap { Int(g(id, $0, 2)) } ?? 0
                cells.append(Cell(day: day, section: sec, rowspan: max(1, span), html: g(inner, c, 3)))
            }

            let rowSection = cells.map { $0.section }.filter { $0 > 0 }.min() ?? (lastSection + 1)
            lastSection = rowSection

            if sections[rowSection] == nil {
                let headHtml = headers.first.map { g(inner, $0, 3) }
                registerSection(&sections, index: rowSection, headHtml: headHtml)
            }

            for cell in cells {
                let sec = cell.section > 0 ? cell.section : rowSection
                let endSec = sec + cell.rowspan - 1
                let lines = HtmlText.cellLines(cell.html)
                if lines.isEmpty { continue }
                sessions += parseCellBlocks(lines, day: cell.day, startSection: sec, endSection: endSec)
            }
        }

        return (sections.values.sorted { $0.index < $1.index }, sessions)
    }

    private static func registerSection(_ into: inout [Int: Section], index: Int, headHtml: String?) {
        let lines = headHtml.map { HtmlText.cellLines($0) } ?? []
        let times = lines.filter { firstMatch(hmRe, $0) != nil }
        let label = lines.first { firstMatch(hmRe, $0) == nil && $0 != "┆" } ?? "第\(index)节"
        into[index] = Section(index: index,
                              label: label,
                              start: times.indices.contains(0) ? times[0] : "",
                              end: times.indices.contains(1) ? times[1] : "")
    }

    /// 单元格文本行 → 若干 Session。每行以 `<<课程名>>;课序号` 开头视为新的一段课。
    static func parseCellBlocks(_ lines: [String], day: Int, startSection: Int, endSection: Int) -> [Session] {
        var blocks: [[String]] = []
        for line in lines {
            if firstMatch(nameRe, line) != nil {
                blocks.append([line])
            } else if !blocks.isEmpty {
                blocks[blocks.count - 1].append(line)
            }
            // 出现在首个课名之前的行（脏数据）直接丢弃
        }

        var out: [Session] = []
        for block in blocks {
            guard let nameMatch = firstMatch(nameRe, block[0]) else { continue }
            let name = g(block[0], nameMatch, 1).trimmingCharacters(in: .whitespaces)
            if name.isEmpty { continue }
            let seqNo = g(block[0], nameMatch, 2).trimmingCharacters(in: .whitespaces)
            let rest = Array(block.dropFirst())

            let weekIndex = rest.firstIndex { firstMatch(weekRe, $0) != nil }
            let weeks: WeekSpan
            if let wi = weekIndex, let w = parseWeeks(rest[wi]) {
                weeks = w
            } else {
                weeks = .unknown
            }

            var category = ""
            if let wi = weekIndex, rest.indices.contains(wi + 1), firstMatch(hoursRe, rest[wi + 1]) != nil {
                category = rest[wi + 1]
            }
            let meta = rest.enumerated()
                .filter { $0.offset != weekIndex && firstMatch(hoursRe, $0.element) == nil }
                .map { $0.element }

            var room = (meta.indices.contains(0) ? meta[0] : "").trimmingCharacters(in: .whitespaces)
            var teacher = (meta.indices.contains(1) ? meta[1] : "").trimmingCharacters(in: .whitespaces)
            if teacher.isEmpty && !room.isEmpty && firstMatch(roomHintRe, room) == nil {
                // 只给了一行元信息且不像地点 → 当教师
                teacher = room
                room = ""
            }

            out.append(Session(name: name, seqNo: seqNo, room: room, teacher: teacher,
                               weeks: weeks, category: category,
                               day: day, startSection: startSection, endSection: endSection))
        }
        return out
    }

    static func parseWeeks(_ text: String) -> WeekSpan? {
        let s = HtmlText.squeeze(text)
        guard let m = firstMatch(weekRe, s), let a = Int(g(s, m, 1)) else { return nil }
        let b = Int(g(s, m, 2)) ?? a
        let parity: Parity
        switch g(s, m, 3) {
        case "单周", "单": parity = .odd
        case "双周", "双": parity = .even
        default: parity = .none
        }
        return WeekSpan(min(a, b), max(a, b), parity)
    }

    // MARK: - 无时间地点课程表

    private static func parseNoArrangement(_ html: String) -> [UnscheduledCourse] {
        guard let table = HtmlText.extractById(html, id: "noArrangement") else { return [] }
        var headers: [String] = []
        var out: [UnscheduledCourse] = []

        for tr in matches(trRe, table) {
            let inner = g(table, tr, 1)
            let cells = matches(cellRe, inner)
            if cells.isEmpty { continue }
            let texts = cells.map { HtmlText.squeeze(HtmlText.plainText(g(inner, $0, 3))) }
            if cells.allSatisfy({ g(inner, $0, 1).lowercased() == "h" }) {
                headers = texts
                continue
            }
            func at(_ i: Int) -> String { texts.indices.contains(i) ? texts[i] : "" }
            func col(_ name: String) -> String {
                guard let i = headers.firstIndex(where: { $0.replacingOccurrences(of: " ", with: "") == name }) else { return "" }
                return at(i)
            }
            let hasHeaders = !headers.isEmpty
            let name = (hasHeaders ? col("课程名称") : at(1)).trimmingCharacters(in: .whitespaces)
            if name.isEmpty { continue }
            let weeksText = hasHeaders ? col("上课周次") : at(5)
            out.append(UnscheduledCourse(
                code: (hasHeaders ? col("课程号") : at(0)).trimmingCharacters(in: .whitespaces),
                name: name,
                seqNo: (hasHeaders ? col("课序号") : at(2)).trimmingCharacters(in: .whitespaces),
                teacher: (hasHeaders ? col("任课教师") : at(3)).trimmingCharacters(in: .whitespaces),
                weeks: parseWeeks(weeksText),
                day: (hasHeaders ? col("星期") : at(6)).trimmingCharacters(in: .whitespaces),
                room: (hasHeaders ? col("上课地点") : at(7)).trimmingCharacters(in: .whitespaces)
            ))
        }
        return out
    }

    // MARK: - 表头

    static func parseTerm(_ html: String) -> TermInfo {
        let titleHtml = HtmlText.extractById(html, id: "title", tag: "div") ?? ""
        let titleText = HtmlText.squeeze(HtmlText.plainText(titleHtml))
        let whole = HtmlText.squeeze(HtmlText.plainText(html))

        let studentNo = firstMatch(studentRe, titleText).map { g(titleText, $0, 1) }
            ?? firstMatch(studentRe, whole).map { g(whole, $0, 1) }
            ?? ""

        // 学年学期：优先标题里找，找不到再全文找（取到哪个就用哪个，别混用两个来源）
        let yearSource = firstMatch(yearRe, titleText) != nil ? titleText : whole
        let yearMatch = firstMatch(yearRe, yearSource)

        let className = firstMatch(classRe, whole).map {
            g(whole, $0, 1).trimmingCharacters(in: .whitespaces)
        } ?? ""

        return TermInfo(
            year: yearMatch.map { g(yearSource, $0, 1) } ?? "",
            term: yearMatch.map { g(yearSource, $0, 2) } ?? "",
            studentNo: studentNo,
            className: className
        )
    }

    // MARK: - 正则小工具

    static func matches(_ re: NSRegularExpression, _ s: String) -> [NSTextCheckingResult] {
        re.matches(in: s, range: NSRange(s.startIndex..., in: s))
    }

    static func firstMatch(_ re: NSRegularExpression, _ s: String) -> NSTextCheckingResult? {
        re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s))
    }

    /// 取第 i 个捕获组（在**原始字符串**上取）
    static func g(_ source: String, _ match: NSTextCheckingResult, _ index: Int) -> String {
        guard index < match.numberOfRanges, let r = Range(match.range(at: index), in: source) else { return "" }
        return String(source[r])
    }
}
