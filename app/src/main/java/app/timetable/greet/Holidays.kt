package app.timetable.greet

import app.timetable.data.WeekCalc
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay

/**
 * 节日表：公历节日 + 农历节日 + 校历节点。
 *
 * 纯 JVM（只依赖 [WeekCalc] 里的纯日期函数），可以在单测里直接跑，不需要设备。
 *
 * 三类分开是有意的 —— 用户可能只想被公历节日打扰（元旦、国庆），不想被农历节日打扰
 * （很多人一年也过不了一次中元节），校历节点更是"仅供参考"。所以 [Kind] 与设置页的
 * 三个开关一一对应，由 HolidayGreeter 负责过滤。
 */
internal object Holidays {

    enum class Kind { SOLAR, LUNAR, SCHOOL }

    data class Holiday(val name: String, val greeting: String, val kind: Kind)

    /** 公历固定日期的节日。用 [MonthDay] 而不是 int，避免"2 月 30 日"这种笔误悄悄进表。 */
    private val SOLAR: List<Pair<MonthDay, Holiday>> = listOf(
        MonthDay.of(1, 1) to Holiday("元旦", "新的一年，从今天开始算", Kind.SOLAR),
        MonthDay.of(2, 14) to Holiday("情人节", "有喜欢的人，就别只说在心里", Kind.SOLAR),
        MonthDay.of(3, 8) to Holiday("妇女节", "愿她被认真对待", Kind.SOLAR),
        MonthDay.of(3, 12) to Holiday("植树节", "种下的，都会长起来", Kind.SOLAR),
        MonthDay.of(4, 1) to Holiday("愚人节", "今天说的真话，也算玩笑", Kind.SOLAR),
        MonthDay.of(5, 1) to Holiday("劳动节", "歇一天，也是正经事", Kind.SOLAR),
        MonthDay.of(5, 4) to Holiday("青年节", "年轻的时候，别急着成熟", Kind.SOLAR),
        MonthDay.of(6, 1) to Holiday("儿童节", "今天可以幼稚一点", Kind.SOLAR),
        MonthDay.of(7, 1) to Holiday("建党节", "记住来路，才看得清去处", Kind.SOLAR),
        MonthDay.of(8, 1) to Holiday("建军节", "记住守着的人", Kind.SOLAR),
        MonthDay.of(9, 10) to Holiday("教师节", "谢谢教过你的人", Kind.SOLAR),
        // 兰大校庆日：1909-09-17 建校
        MonthDay.of(9, 17) to Holiday("校庆", "兰大生日快乐，也祝你顺利", Kind.SOLAR),
        MonthDay.of(10, 1) to Holiday("国庆节", "假期到了，好好休息", Kind.SOLAR),
        MonthDay.of(10, 31) to Holiday("万圣节", "今晚可以装作什么都不怕", Kind.SOLAR),
        MonthDay.of(12, 24) to Holiday("平安夜", "平安是最朴素的愿望", Kind.SOLAR),
        MonthDay.of(12, 25) to Holiday("圣诞节", "冬天里的一个小节日", Kind.SOLAR),
    )

    // 母亲节/父亲节不是固定日期：分别是 5 月第 2 个周日、6 月第 3 个周日。
    // 用"周日 + 日期区间"判断（第 2 个周日必然落在 8..14 号），比数星期几可靠。
    private val MOTHERS_DAY = Holiday("母亲节", "给妈妈打个电话吧", Kind.SOLAR)
    private val FATHERS_DAY = Holiday("父亲节", "和爸爸也说几句", Kind.SOLAR)

    private data class LunarFix(val month: Int, val day: Int, val holiday: Holiday)

    /** 农历固定月日的节日。闰月里的同名日子**不算节日**（闰五月初五不是端午）。 */
    private val LUNAR: List<LunarFix> = listOf(
        LunarFix(1, 1, Holiday("春节", "新春顺遂，万事安心", Kind.LUNAR)),
        LunarFix(1, 15, Holiday("元宵", "元宵甜甜，日子也甜", Kind.LUNAR)),
        // 兰州属北方，小年取腊月二十三
        LunarFix(2, 2, Holiday("龙抬头", "二月二，抬头看看天", Kind.LUNAR)),
        LunarFix(5, 5, Holiday("端午", "端午安康，粽子要吃", Kind.LUNAR)),
        LunarFix(7, 7, Holiday("七夕", "今夜星河很长", Kind.LUNAR)),
        LunarFix(7, 15, Holiday("中元", "记得，就是还在", Kind.LUNAR)),
        LunarFix(8, 15, Holiday("中秋", "月圆了，记得看", Kind.LUNAR)),
        LunarFix(9, 9, Holiday("重阳", "天凉了，记得添衣", Kind.LUNAR)),
        LunarFix(12, 8, Holiday("腊八", "腊八了，喝碗热粥", Kind.LUNAR)),
        LunarFix(12, 23, Holiday("小年", "小年到，年味开始", Kind.LUNAR)),
    )

    private val NEW_YEARS_EVE = Holiday("除夕", "一年到头，辛苦了", Kind.LUNAR)

    /**
     * 当天命中的所有节日（可能多个，例如元旦撞上腊八、国庆撞上中秋）。
     * 顺序固定为 公历 → 农历 → 校历，弹窗直接按这个顺序展示。
     */
    fun of(date: LocalDate): List<Holiday> {
        val out = ArrayList<Holiday>(3)
        solarOf(date)?.let { out += it }
        out += lunarHolidaysOf(date)
        for (node in schoolNodes(date.year)) {
            if (node.first == date) out += node.second
        }
        return out
    }

    /**
     * 某一公历年内的全部节日清单（按日期升序），给年历视图标点用。
     *
     * 实现上是逐天扫 365/366 次 [of] —— 看起来笨，但好处是**只有一份判定逻辑**：
     * 农历节日、除夕（要看次日）、母亲节这类浮动节日、校历节点全都自动包含进来，
     * 不会出现"年历上有、当天却不弹"这种两套逻辑漂移的问题。
     * 一次扫描约 366 × 常数，手机上不到 2ms，且只在打开年历时做一次。
     */
    fun all(year: Int): List<Pair<LocalDate, Holiday>> {
        val out = ArrayList<Pair<LocalDate, Holiday>>(48)
        var d = LocalDate.of(year, 1, 1)
        val end = LocalDate.of(year, 12, 31)
        while (!d.isAfter(end)) {
            for (h in of(d)) out += d to h
            d = d.plusDays(1)
        }
        return out
    }

    // ------------------------------------------------------------------ 内部

    private fun solarOf(date: LocalDate): Holiday? {
        val md = MonthDay.from(date)
        SOLAR.firstOrNull { it.first == md }?.let { return it.second }
        return when {
            date.monthValue == 5 && date.dayOfWeek == DayOfWeek.SUNDAY &&
                date.dayOfMonth in 8..14 -> MOTHERS_DAY

            date.monthValue == 6 && date.dayOfWeek == DayOfWeek.SUNDAY &&
                date.dayOfMonth in 15..21 -> FATHERS_DAY

            else -> null
        }
    }

    private fun lunarHolidaysOf(date: LocalDate): List<Holiday> {
        val l = LunarCalendar.lunarOf(date)
        // 闰月只用于"闰"本身，不重复过普通过节
        if (l.leap) return emptyList()

        val out = ArrayList<Holiday>(1)
        LUNAR.firstOrNull { it.month == l.month && it.day == l.day }?.let { out += it.holiday }

        // 除夕 = 腊月最后一天。用"次日是不是正月初一"判断，而不是写死"腊月三十"——
        // 腊月有 29 天的小月，写死三十会在那些年份直接漏掉除夕（一年里最不该漏的一个）。
        if (l.month == 12) {
            val next = LunarCalendar.lunarOf(date.plusDays(1))
            if (!next.leap && next.month == 1 && next.day == 1) out += NEW_YEARS_EVE
        }
        return out
    }

    /**
     * 校历节点（**参考节点，非官方校历**）。
     *
     * 依据只有两条，都取自这个 App 自己已经在用的学期口径（见 [WeekCalc.guessWeek1Monday]）：
     *   秋季学期 = 9 月 1 日后的第一个周一；春季学期 = 2 月 20 日后的第一个周一。
     * 复用同一个函数是为了避免"课表的第 1 周"和"祝福里的开学日"对不上。
     *
     * 期中/期末按 20 周学期估：期中 ≈ 第 9 周周一，期末 ≈ 第 17 周周一
     * （春季学期短一点，取第 8 周与第 16 周）。寒暑假与毕业季没有可靠公开日期，
     * 给的是常见区间的中值（寒假 1/15、暑假 7/15、毕业季 6/20）。
     *
     * 正因如此，这些 greeting 都带「参考节点，非官方校历」字样——用户按它安排行程前
     * 应该去查教务通知。真实的校历每年都不同，App 里不可能内置。
     */
    private fun schoolNodes(year: Int): List<Pair<LocalDate, Holiday>> {
        val autumn = WeekCalc.firstMondayOnOrAfter(LocalDate.of(year, 9, 1))
        val spring = WeekCalc.firstMondayOnOrAfter(LocalDate.of(year, 2, 20))
        val caveat = "（参考节点，非官方校历）"
        return listOf(
            spring to Holiday("春季开学", "开学了慢慢来$caveat", Kind.SCHOOL),
            spring.plusWeeks(7) to Holiday("期中周", "学期过半了$caveat", Kind.SCHOOL),
            spring.plusWeeks(15) to Holiday("期末周", "期末周到了$caveat", Kind.SCHOOL),
            LocalDate.of(year, 6, 20) to Holiday("毕业季", "毕业季到了$caveat", Kind.SCHOOL),
            LocalDate.of(year, 7, 15) to Holiday("暑假开始", "暑假开始了$caveat", Kind.SCHOOL),
            autumn to Holiday("秋季开学", "开学了慢慢来$caveat", Kind.SCHOOL),
            autumn.plusWeeks(8) to Holiday("期中周", "学期过半了$caveat", Kind.SCHOOL),
            autumn.plusWeeks(16) to Holiday("期末周", "期末周到了$caveat", Kind.SCHOOL),
            LocalDate.of(year, 1, 15) to Holiday("寒假开始", "寒假开始了$caveat", Kind.SCHOOL),
        )
    }
}
