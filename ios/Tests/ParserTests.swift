import XCTest
@testable import LZUWidget

//  与 Android 版**同一批断言**。
//
//  我（作者）在 Windows 上写这些代码，**从未编译运行过**。你在 Mac 上执行
//     xcodebuild test -scheme LZUWidget -destination 'platform=iOS Simulator,name=iPhone 15'
//  跑绿，就等于证明「Swift 移植与已通过测试的 Kotlin 实现行为一致」。

final class ParserTests: XCTestCase {

    private func fixture() -> String {
        let bundle = Bundle(for: type(of: self))
        guard let url = bundle.url(forResource: "timetable-2026autumn", withExtension: "html"),
              let html = try? String(contentsOf: url, encoding: .utf8) else {
            XCTFail("找不到测试样本 timetable-2026autumn.html（应随测试 target 一起打包）")
            return ""
        }
        return html
    }

    private func parsed() -> ParseResult { TimetableParser.parse(fixture()) }

    // MARK: - 表头

    func testParsesTermHeader() {
        let t = parsed().term
        XCTAssertEqual("2026", t.year)
        XCTAssertEqual("秋", t.term)
        XCTAssertEqual("999999999999", t.studentNo)
        XCTAssertEqual("2026示例班", t.className)
    }

    // MARK: - 节次

    func testParsesAllFourteenSectionsWithTimes() {
        let r = parsed()
        XCTAssertEqual(14, r.sections.count)
        XCTAssertEqual(Array(1...14), r.sections.map { $0.index })

        XCTAssertEqual("第1节", r.section(1)?.label)
        XCTAssertEqual("08:30", r.section(1)?.start)
        XCTAssertEqual("09:15", r.section(1)?.end)

        // 第 5、6 行是「中午1节」「中午2节」
        XCTAssertEqual("中午1节", r.section(5)?.label)
        XCTAssertEqual("12:20", r.section(5)?.start)
        XCTAssertEqual("中午2节", r.section(6)?.label)
        XCTAssertEqual("13:25", r.section(6)?.start)

        // 行序 7 的 label 是「第5节」—— 显示必须用 label，不能用行序
        XCTAssertEqual("第5节", r.section(7)?.label)
        XCTAssertEqual("第12节", r.section(14)?.label)
        XCTAssertEqual("22:30", r.section(14)?.end)
    }

    func testSectionRangeLabelUsesLabelNotRowIndex() {
        let r = parsed()
        XCTAssertEqual("第1-2节", r.sectionRangeLabel(1, 2))
        // 行序 7、8 对应「第5节」「第6节」
        XCTAssertEqual("第5-6节", r.sectionRangeLabel(7, 8))
        XCTAssertEqual("第5节", r.sectionRangeLabel(7, 7))
    }

    // MARK: - 单元格

    func testParsesSingleSessionFromRealCell() {
        let r = parsed()
        guard let s = r.sessions.first(where: { $0.day == 1 && $0.startSection == 1 }) else {
            XCTFail("没解析出周一第 1 节的课"); return
        }
        XCTAssertEqual("高阶英语1", s.name)
        XCTAssertEqual("23", s.seqNo)
        XCTAssertEqual("天山堂A301", s.room)
        XCTAssertEqual("甲老师", s.teacher)
        XCTAssertEqual(.even, s.weeks.parity)       // 4-18周双周
        XCTAssertEqual(4, s.weeks.start)
        XCTAssertEqual(18, s.weeks.end)
    }

    func testParsesOddWeekParity() {
        let r = parsed()
        guard let s = r.sessions.first(where: { $0.weeks.parity == .odd }) else {
            XCTFail("样本里有单周课，应当解析出来"); return
        }
        XCTAssertEqual(.odd, s.weeks.parity)
    }

    func testParsesNoArrangementTable() {
        let r = parsed()
        XCTAssertFalse(r.unscheduled.isEmpty, "样本里有 #noArrangement 表")
    }

    // MARK: - 周次与合并

    func testWeekSpanContains() {
        XCTAssertTrue(WeekSpan(4, 18, .even).contains(4))
        XCTAssertTrue(WeekSpan(4, 18, .even).contains(18))
        XCTAssertFalse(WeekSpan(4, 18, .even).contains(5))     // 双周 → 第 5 周不算
        XCTAssertTrue(WeekSpan(4, 18, .none).contains(5))
        XCTAssertFalse(WeekSpan(4, 18, .none).contains(19))
    }

    func testMergeAdjacentSameSession() {
        let a = Session(name: "数学", seqNo: "1", room: "A101", teacher: "张", weeks: WeekSpan(1, 18), day: 2, startSection: 5, endSection: 5)
        let b = Session(name: "数学", seqNo: "1", room: "A101", teacher: "张", weeks: WeekSpan(1, 18), day: 2, startSection: 6, endSection: 6)
        let merged = WeekCalc.merge([a, b])
        XCTAssertEqual(1, merged.count)
        XCTAssertEqual(5, merged[0].startSection)
        XCTAssertEqual(6, merged[0].endSection)
    }

    func testWeekOfCountsFromWeek1Monday() {
        let w1 = WeekCalc.calendar.date(from: DateComponents(year: 2026, month: 9, day: 7))!
        let d = WeekCalc.calendar.date(from: DateComponents(year: 2026, month: 10, day: 27))!
        XCTAssertEqual(8, WeekCalc.weekOf(d, week1Monday: w1))
    }

    func testGuessWeek1MondayForAutumn() {
        let today = WeekCalc.calendar.date(from: DateComponents(year: 2026, month: 10, day: 20))!
        let w1 = WeekCalc.guessWeek1Monday(termYear: "2026", season: "秋", today: today)
        XCTAssertEqual(WeekCalc.calendar.date(from: DateComponents(year: 2026, month: 9, day: 7)), w1)
    }
}

// MARK: - HTML 工具

final class HtmlTextTests: XCTestCase {

    func testStripsWbrInjectedByBrowser() {
        let raw = "学生课表: 9999999999<wbr>99"
        XCTAssertEqual("学生课表: 999999999999", HtmlText.squeeze(HtmlText.stripNoise(raw)))
    }

    func testCellLinesSplitsOnBr() {
        let lines = HtmlText.cellLines("&lt;&lt;高阶英语1&gt;&gt;;<wbr>23<br>天山堂A301<br>甲老师<br>4-18周双周<br>讲课学时")
        XCTAssertEqual(["<<高阶英语1>>;23", "天山堂A301", "甲老师", "4-18周双周", "讲课学时"], lines)
    }

    func testEntityDecode() {
        XCTAssertEqual("a?b=1&c=2", HtmlText.decode("a?b=1&amp;c=2"))
        XCTAssertEqual("中", HtmlText.decode("&#x4e2d;"))
        XCTAssertEqual("中", HtmlText.decode("&#20013;"))
    }

    func testExtractByIdBalancesNestedSameTag() {
        let html = "<table id=\"timetable\"><tr><td><table><tr><td>x</td></tr></table></td></tr></table>"
        let inner = HtmlText.extractById(html, id: "timetable")
        XCTAssertNotNil(inner)
        XCTAssertTrue(inner!.contains("<table>"))
        XCTAssertTrue(inner!.hasSuffix("</table>"))
    }
}

// MARK: - 链接发现

final class LinkTests: XCTestCase {

    private let base = "https://jwk.lzu.edu.cn/academic/manager/coursearrange/showTimetable.do"
    private func link(_ id: String, type: String = "STUDENT", section: String = "BASE") -> String {
        "\(base)?id=\(id)&yearid=46&termid=2&timetableType=\(type)&sectionType=\(section)"
    }

    func testPickPrefersStudentBasicSection() {
        let candidates = [link("1", type: "TEACHER"),
                          link("2", section: "COMBINE"),
                          link("3")]
        XCTAssertEqual(link("3"), TimetableLink.pick(candidates))
    }

    func testParseJsArrayHandlesDoubleWrappedPayload() {
        // evaluateJavascript 拿到字符串时会再包一层引号并转义
        let raw = "\"[\\\"https://a/x\\\",\\\"https://b/y\\\"]\""
        XCTAssertEqual(["https://a/x", "https://b/y"], TimetableLink.parseJsArray(raw))
    }

    func testDiscoverySentinelMeansScriptRan() {
        let raw = "[\"\(TimetableLink.sentinel)\",\"\(link("3"))\"]"
        let d = TimetableLink.parseDiscovery(raw)
        XCTAssertTrue(d.ran)
        XCTAssertEqual([link("3")], d.urls)

        XCTAssertFalse(TimetableLink.parseDiscovery("[]").ran)
        XCTAssertFalse(TimetableLink.parseDiscovery(nil).ran)
    }

    func testRelativeLinksResolveAgainstConfiguredOrigin() {
        let relative = "/academic/manager/coursearrange/showTimetable.do?id=777777&yearid=99&termid=1&timetableType=STUDENT&sectionType=BASE"
        let picked = TimetableLink.pick([relative], origin: "http://10.0.2.2:8080")
        XCTAssertEqual("http://10.0.2.2:8080" + relative, picked)
    }

    func testFallbackWithoutYearIsNotWorthRequesting() {
        // 真机实测：没有 yearid 时教务系统回「学年传递错误」，这种请求注定失败
        let f = TimetableLink.fallback([], origin: "https://jwk.lzu.edu.cn")
        XCTAssertNil(TimetableLink.param(f, "yearid"))
        XCTAssertFalse(TimetableLink.hasYearParam(f))
    }

    func testFallbackInheritsYearFromSavedURL() {
        let f = TimetableLink.fallback([link("900001")], origin: "https://jwk.lzu.edu.cn")
        XCTAssertTrue(TimetableLink.hasYearParam(f))
        XCTAssertEqual("46", TimetableLink.param(f, "yearid"))
        XCTAssertNil(TimetableLink.param(f, "id"))       // 仍然不带别人的 id
    }

    func testHostAndOrigin() {
        XCTAssertEqual("jwk.lzu.edu.cn", TimetableLink.hostOf("https://jwk.lzu.edu.cn/academic/"))
        XCTAssertEqual("10.0.2.2", TimetableLink.hostOf("http://10.0.2.2:8080/portal.html"))
        XCTAssertEqual("http://10.0.2.2:8080", TimetableLink.originOf("http://10.0.2.2:8080/x/y"))
    }
}

// MARK: - 断行（与 Android 同一条规则）

final class WrapTests: XCTestCase {

    func testSemanticCutBetweenChineseAndRoomNumber() {
        XCTAssertEqual(3, TimetableCanvas.semanticCut("天山堂A101", start: 0, end: 4))
    }

    func testSemanticCutFallsBackToHardCut() {
        XCTAssertEqual(3, TimetableCanvas.semanticCut("高等数学分析", start: 0, end: 3))
    }
}
