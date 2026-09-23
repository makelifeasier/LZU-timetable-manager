import SwiftUI
import UIKit

//  自绘课表 —— 与 Android 版 `TimetableRenderer.kt` 对应。
//
//  这里刻意把 Android 上**被验证过**的几条规则一起搬过来：
//   1. 列表/卡片只显示**整行**，底部不切半行
//   2. 宽度不够时**先缩字**，最后才截断
//   3. 断行优先落在**语义边界**（汉字↔字母数字、空格后）
//   4. 行数由**高度**决定，只给后面的段落预留最少行数
//   5. 周末底色可切换（淡底色 / 与工作日相同）

struct TimetableCanvas: View {

    let result: ParseResult
    let week: Int
    let todayDay: Int
    let now: TimeOfDay?
    let weekMonday: Date?
    let style: Int
    let weekendTint: Bool
    let dark: Bool
    let accent: Color

    // 与 Android 版一致的几何常量
    private static let headerH: CGFloat = 48
    private static let rowH: CGFloat = 62
    private static let timeCol: CGFloat = 40
    private static let minRows = 12

    // MARK: - 配色

    struct Palette {
        let page: Color, surface: Color, hairline: Color
        let text: Color, text2: Color, text3: Color
        let nowLine: Color, weekendWash: Color
    }

    static func palette(dark: Bool) -> Palette {
        Palette(
            page: dark ? Color(hex: 0x0F1115) : .white,
            surface: dark ? Color(hex: 0x171A20) : .white,
            hairline: dark ? Color(hex: 0x23282F) : Color(hex: 0xEEF0F3),
            text: dark ? Color(hex: 0xEDEFF2) : Color(hex: 0x101828),
            text2: dark ? Color(hex: 0x98A2B3) : Color(hex: 0x667085),
            text3: dark ? Color(hex: 0x6B7280) : Color(hex: 0x98A2B3),
            nowLine: dark ? Color(hex: 0xFF6B6B) : Color(hex: 0xE5484D),
            weekendWash: dark ? Color.white.opacity(0.08) : Color.black.opacity(0.03)
        )
    }

    /// 课名哈希 → 固定配色，保证同一门课每次渲染同色（与 Android 同一套色值与算法）
    static let paletteColors: [Color] = [
        0x1E6FD9, 0x7C3AED, 0xDB2777, 0xDC2626, 0xEA580C, 0xCA8A04,
        0x16A34A, 0x0D9488, 0x0891B2, 0x4F46E5
    ].map { Color(hex: $0) }

    static func colorFor(_ name: String, seed: Int) -> Color {
        var h = seed &* 31 &+ 7
        for scalar in name.unicodeScalars { h = h &* 31 &+ Int(scalar.value) }
        let idx = ((h % paletteColors.count) + paletteColors.count) % paletteColors.count
        return paletteColors[idx]
    }

    struct Style {
        let tint: Double, radius: CGFloat, showBar: Bool, filled: Bool
        static let all: [Style] = [
            Style(tint: 34, radius: 9, showBar: true, filled: true),
            Style(tint: 16, radius: 9, showBar: false, filled: true),
            Style(tint: 66, radius: 9, showBar: true, filled: true),
            Style(tint: 34, radius: 18, showBar: true, filled: true),
            Style(tint: 34, radius: 2, showBar: true, filled: true),
            Style(tint: 0, radius: 9, showBar: true, filled: false)
        ]
        static func of(_ i: Int) -> Style { all[min(max(i, 0), all.count - 1)] }
        static let names = ["清爽", "素雅", "醒目", "圆润", "直角", "极简"]
    }

    // MARK: - 尺寸

    static func sectionCount(_ result: ParseResult) -> Int {
        max(result.sections.map { $0.index }.max() ?? 0, minRows)
    }
    static func dayCol(width: CGFloat, density: CGFloat = 1) -> CGFloat { (width - timeCol) / 7 }
    static func contentHeight(width: CGFloat, result: ParseResult) -> CGFloat {
        headerH + CGFloat(sectionCount(result)) * rowH
    }
    private func xFor(_ day: Int, width: CGFloat) -> CGFloat { Self.timeCol + CGFloat(day - 1) * Self.dayCol(width: width) }
    private func yFor(_ section: Int) -> CGFloat { Self.headerH + CGFloat(section - 1) * Self.rowH }

    var body: some View {
        GeometryReader { geo in
            Canvas { ctx, size in
                draw(ctx: ctx, size: size)
            }
        }
        .frame(height: Self.contentHeight(width: UIScreen.main.bounds.width, result: result))
    }

    // MARK: - 绘制

    private func draw(ctx: GraphicsContext, size: CGSize) {
        let pal = Self.palette(dark: dark)
        let width = size.width
        let grid = WeekCalc.weekGrid(result, week: week)

        ctx.fill(Path(CGRect(x: 0, y: 0, width: width, height: size.height)), with: .color(pal.page))

        // 周末列淡底（设置可关）
        if weekendTint {
            let rect = CGRect(x: xFor(6, width: width), y: Self.headerH,
                              width: Self.dayCol(width: width) * 2, height: size.height - Self.headerH)
            ctx.fill(Path(rect), with: .color(pal.weekendWash))
        }

        // 今天列淡底
        if todayDay >= 1 && todayDay <= 7 {
            let rect = CGRect(x: xFor(todayDay, width: width), y: Self.headerH,
                              width: Self.dayCol(width: width), height: size.height - Self.headerH)
            ctx.fill(Path(rect), with: .color(accent.opacity(dark ? 0.09 : 0.06)))
        }

        // 表头
        ctx.fill(Path(CGRect(x: 0, y: 0, width: width, height: Self.headerH)), with: .color(pal.surface))
        drawHeader(ctx: ctx, width: width, pal: pal)

        // 网格线
        var gridPath = Path()
        for i in 1..<Self.sectionCount(result) {
            let y = Self.headerH + CGFloat(i) * Self.rowH
            gridPath.move(to: CGPoint(x: Self.timeCol, y: y))
            gridPath.addLine(to: CGPoint(x: width, y: y))
        }
        gridPath.move(to: CGPoint(x: Self.timeCol, y: Self.headerH))
        gridPath.addLine(to: CGPoint(x: Self.timeCol, y: size.height))
        ctx.stroke(gridPath, with: .color(pal.hairline), lineWidth: 1)

        drawTimeColumn(ctx: ctx, width: width, pal: pal)
        drawCards(ctx: ctx, width: width, pal: pal, grid: grid)
        drawNowLine(ctx: ctx, width: width, pal: pal)

        if !grid.values.contains(where: { !$0.isEmpty }) {
            drawEmpty(ctx: ctx, size: size, pal: pal)
        }
    }

    private func drawHeader(ctx: GraphicsContext, width: CGFloat, pal: Palette) {
        let today = Date()
        for day in 1...7 {
            let label = Session.dayLabel(day)
            var text = Text(label).font(.system(size: 12, weight: .semibold)).foregroundColor(pal.text)
            if day == todayDay { text = text.foregroundColor(accent) }
            ctx.draw(text, at: CGPoint(x: xFor(day, width: width) + Self.dayCol(width: width) / 2, y: Self.headerH / 2 + 1))

            if let wm = weekMonday {
                let date = WeekCalc.calendar.date(byAdding: .day, value: (week - 1) * 7 + (day - 1), to: WeekCalc.mondayOf(wm))
                if let d = date {
                    let dayNum = WeekCalc.calendar.component(.day, from: d)
                    ctx.draw(Text("\(dayNum)").font(.system(size: 10)).foregroundColor(pal.text2),
                             at: CGPoint(x: xFor(day, width: width) + Self.dayCol(width: width) / 2, y: Self.headerH - 9))
                }
            }
            _ = today
        }
    }

    private func drawTimeColumn(ctx: GraphicsContext, width: CGFloat, pal: Palette) {
        for s in result.sections {
            let top = yFor(s.index)
            ctx.draw(Text(s.label).font(.system(size: 10.5, weight: .medium)).foregroundColor(pal.text),
                     at: CGPoint(x: Self.timeCol / 2, y: top + Self.rowH * 0.34))
            if !s.start.isEmpty {
                ctx.draw(Text(s.start).font(.system(size: 8.5)).foregroundColor(pal.text2),
                         at: CGPoint(x: Self.timeCol / 2, y: top + Self.rowH * 0.60))
            }
            if !s.end.isEmpty {
                ctx.draw(Text(s.end).font(.system(size: 8.5)).foregroundColor(pal.text2),
                         at: CGPoint(x: Self.timeCol / 2, y: top + Self.rowH * 0.80))
            }
        }
    }

    private func drawCards(ctx: GraphicsContext, width: CGFloat, pal: Palette, grid: [Int: [Session]]) {
        let st = Style.of(style)
        for day in 1...7 {
            for s in grid[day] ?? [] {
                let c = Self.colorFor(s.name, seed: Store.colorSeed)
                let left = xFor(day, width: width) + 2.5
                let right = xFor(day, width: width) + Self.dayCol(width: width) - 3
                let top = yFor(s.startSection) + 2.5
                let bottom = yFor(s.endSection + 1) - 2.5
                if right - left < 14 || bottom - top < 18 { continue }

                let rect = CGRect(x: left, y: top, width: right - left, height: bottom - top)
                let shape = Path(roundedRect: rect, cornerRadius: st.radius)
                if st.filled {
                    ctx.fill(shape, with: .color(c.opacity(st.tint / 255)))
                } else {
                    ctx.stroke(shape, with: .color(c.opacity(0.28)), lineWidth: 1)
                }
                if day == todayDay, let now, isOngoing(s, now: now) {
                    ctx.stroke(shape, with: .color(c), lineWidth: 1.5)
                }
                if st.showBar {
                    let bar = CGRect(x: left + 4, y: top + 4, width: 2.5, height: bottom - top - 8)
                    ctx.fill(Path(roundedRect: bar, cornerRadius: 1.5), with: .color(c))
                }

                let textLeft = left + (st.showBar ? 12 : 6)
                let maxW = right - textLeft - 4
                if maxW <= 0 { continue }

                let extra = s.teacher.isEmpty
                    ? ((s.weeks.start > 1 || s.weeks.end < 25) ? s.weeks.display : "")
                    : s.teacher

                drawFitted(ctx: ctx, blocks: [
                    Block(label: "课名", text: s.name, size: 11, lineHeight: 12.5, maxLines: 8, color: pal.text, bold: true),
                    Block(label: "教室", text: s.room, size: 8.5, lineHeight: 9.5, maxLines: 2, color: pal.text2, reserveAfter: 2),
                    Block(label: "教师", text: extra, size: 8, lineHeight: 9.5, maxLines: 2, color: pal.text3)
                ], x: textLeft, top: top + 8, bottom: bottom - 5, maxWidth: maxW)
            }
        }
    }

    private func isOngoing(_ s: Session, now: TimeOfDay) -> Bool {
        guard let start = result.section(s.startSection)?.startTime else { return false }
        let end = result.section(s.endSection)?.endTime ?? start
        return now >= start && now <= end
    }

    private func drawNowLine(ctx: GraphicsContext, width: CGFloat, pal: Palette) {
        guard todayDay >= 1, todayDay <= 7, let now else { return }
        let label = Session.dayLabel(WeekCalc.dayOf(Date()))
        _ = label
        var y: CGFloat = Self.headerH
        for s in result.sections {
            guard let st = s.startTime, let et = s.endTime else { continue }
            if now >= st && now <= et {
                let frac = CGFloat(now.minutesFromMidnight - st.minutesFromMidnight) /
                           CGFloat(max(1, et.minutesFromMidnight - st.minutesFromMidnight))
                y = yFor(s.index) + rowHeight(of: s) * frac
                break
            }
        }
        var p = Path()
        p.move(to: CGPoint(x: Self.timeCol, y: y))
        p.addLine(to: CGPoint(x: width, y: y))
        ctx.stroke(p, with: .color(pal.nowLine), lineWidth: 1.2)
        let dot = CGRect(x: Self.timeCol - 3.5, y: y - 3.5, width: 7, height: 7)
        ctx.fill(Path(ellipseIn: dot), with: .color(pal.nowLine))
    }

    private func rowHeight(of s: Section) -> CGFloat { Self.rowH }

    private func drawEmpty(ctx: GraphicsContext, size: CGSize, pal: Palette) {
        let cx = Self.timeCol + (size.width - Self.timeCol) / 2
        let cy = Self.headerH + (size.height - Self.headerH) / 2
        let w = min(size.width - 48, 300)
        let card = CGRect(x: cx - w / 2, y: cy - 48, width: w, height: 96)
        ctx.fill(Path(roundedRect: card, cornerRadius: 16), with: .color(pal.hairline))

        let title = result.sessions.isEmpty ? "还没有课表数据" : "第 \(week) 周没有课"
        let hint = result.sessions.isEmpty ? "点右上角「同步」或「登录导入」" : "用 ‹ › 切换周次看看"
        ctx.draw(Text(title).font(.system(size: 14, weight: .semibold)).foregroundColor(pal.text),
                 at: CGPoint(x: cx, y: cy - 8))
        ctx.draw(Text(hint).font(.system(size: 11)).foregroundColor(pal.text3),
                 at: CGPoint(x: cx, y: cy + 14))
    }

    // MARK: - 文字排版（与 Android 同一套规则）

    struct Block {
        var label: String
        var text: String
        var size: CGFloat
        var lineHeight: CGFloat
        var maxLines: Int
        var color: Color
        var bold: Bool = false
        /// 给后面的段落预留几行
        var reserveAfter: Int = 0
    }

    private static let scales: [CGFloat] = [0.92, 0.84, 0.76, 0.68]

    /// 在 [top, bottom) 内排版：宽度不够**先缩字**，最后才截断。
    private func drawFitted(ctx: GraphicsContext, blocks: [Block], x: CGFloat, top: CGFloat,
                            bottom: CGFloat, maxWidth: CGFloat) {
        var y = top
        for (index, block) in blocks.enumerated() {
            if block.text.isEmpty { continue }
            let remaining = bottom - y
            let reserve = blocks.dropFirst(index + 1).reduce(CGFloat(0)) { $0 + $1.lineHeight * CGFloat($1.reserveAfter) }
            let usable = (bottom - reserve) - y
            if usable < block.lineHeight * 0.85 && remaining < block.lineHeight * 0.85 { break }

            let fits = max(1, Int(usable / block.lineHeight))
            let maxLines = min(block.maxLines, fits)

            var scale: CGFloat = 1
            var wrapped = Self.wrap(block.text, size: block.size, bold: block.bold, maxWidth: maxWidth, maxLines: maxLines)
            if wrapped.truncated {
                for cand in Self.scales {
                    let w = Self.wrap(block.text, size: block.size * cand, bold: block.bold, maxWidth: maxWidth, maxLines: maxLines)
                    if !w.truncated { wrapped = w; scale = cand; break }
                }
            }
            _ = scale
            if wrapped.lines.isEmpty { continue }

            for (i, line) in wrapped.lines.enumerated() {
                let isLast = i == wrapped.lines.count - 1
                var text = line
                if isLast && wrapped.truncated {
                    text = Self.ellipsize(line, size: block.size, bold: block.bold, maxWidth: maxWidth)
                }
                ctx.draw(
                    Text(text).font(.system(size: block.size, weight: block.bold ? .semibold : .regular))
                        .foregroundColor(block.color),
                    at: CGPoint(x: x, y: y + block.lineHeight * 0.55),
                    anchor: .leading
                )
                y += block.lineHeight
            }
        }
    }

    struct Wrapped { var lines: [String]; var truncated: Bool }

    private static func measure(_ s: String, size: CGFloat, bold: Bool) -> CGFloat {
        let font = UIFont.systemFont(ofSize: size, weight: bold ? .semibold : .regular)
        return (s as NSString).size(withAttributes: [.font: font]).width
    }

    /// 是否「宽」字符（汉字/中文标点）
    private static func isWide(_ ch: Character) -> Bool {
        guard let scalar = ch.unicodeScalars.first else { return false }
        return scalar.value > 0x2E80
    }

    /// 在 [start, end) 里挑语义断点：空格后，或汉字→字母/数字之间。断点太靠前就硬切。
    static func semanticCut(_ text: String, start: Int, end: Int) -> Int {
        let chars = Array(text)
        let span = end - start
        if span <= 1 { return end }
        var k = end - 1
        while k > start {
            let prev = chars[k - 1], cur = chars[k]
            if prev == " " || (isWide(prev) && !isWide(cur)) {
                return (k - start < span / 2) ? end : k
            }
            k -= 1
        }
        return end
    }

    /// 按宽度断行，最多 maxLines 行
    static func wrap(_ text: String, size: CGFloat, bold: Bool, maxWidth: CGFloat, maxLines: Int) -> Wrapped {
        guard maxWidth > 0, maxLines > 0, !text.isEmpty else { return Wrapped(lines: [], truncated: !text.isEmpty) }
        let chars = Array(text)
        var lines: [String] = []
        var i = 0
        while i < chars.count && lines.count < maxLines {
            var end = i
            while end < chars.count, measure(String(chars[i...end]), size: size, bold: bold) <= maxWidth { end += 1 }
            if end == i { end = i + 1 }        // 单字符都放不下：硬塞，避免死循环
            let cut = semanticCut(text, start: i, end: end)
            lines.append(String(chars[i..<cut]))
            i = cut
        }
        return Wrapped(lines: lines, truncated: i < chars.count)
    }

    static func ellipsize(_ text: String, size: CGFloat, bold: Bool, maxWidth: CGFloat) -> String {
        if measure(text, size: size, bold: bold) <= maxWidth { return text }
        var chars = Array(text)
        while !chars.isEmpty {
            let candidate = String(chars) + "…"
            if measure(candidate, size: size, bold: bold) <= maxWidth { return candidate }
            chars.removeLast()
        }
        return "…"
    }
}

extension Color {
    init(hex: Int) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}
