package app.timetable.greet

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 公历 → 农历。纯 JVM 实现：无 Android 依赖、无网络、无第三方库（只需 JUnit 就能测）。
 *
 * **为什么用压缩表而不是写算法**：农历的置闰由定朔定气决定（日月视黄经差为 0 的瞬间所在日
 * 才是初一，且要考虑东八区与 ΔT 修正），不是一条算术公式能复现的。1900–2100 每年一个 int
 * 的压缩表是整个行业（Java/JS/Python 的各类农历库）通行的做法：体积约 800 字节，
 * 结果与紫金山天文台历书一致，且不需要在手机上下载任何数据。
 */
internal object LunarCalendar {

    /** 农历日期。[leap] 为 true 表示落在闰月里（月号与所闰的月份相同） */
    data class LunarDate(val year: Int, val month: Int, val day: Int, val leap: Boolean)

    /**
     * 表起点：1900-01-31 是农历 1900 年正月初一。
     * 整个算法都在数"距这一天的天数"，所以它必须是表里第一个农历年的正月初一。
     */
    private val BASE: LocalDate = LocalDate.of(1900, 1, 31)

    private const val FIRST_YEAR = 1900
    private const val LAST_YEAR = 2100

    /**
     * 1900–2100 压缩农历表，每年一个 int：
     *
     * ```
     * bit16      闰月是否 30 天（1 = 大月 30 天，0 = 小月 29 天）
     * bit15..4   正月…腊月是否 30 天（1 = 大月，0 = 小月 29 天）
     * bit3..0    闰月月份，0 = 当年不闰
     * ```
     *
     * 编码取自通行的天文年历压缩表（201 项 = 1900…2100）。抽查过的锚点：
     * 1900 闰八月、2033 闰十一月（罕见的"闰冬月"）、2017 闰六月、2020 闰四月、2025 闰六月 —— 均与历书一致。
     */
    private val TABLE = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2, // 1900-1909
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977, // 1910-1919
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970, // 1920-1929
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950, // 1930-1939
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557, // 1940-1949
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0, // 1950-1959
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0, // 1960-1969
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6, // 1970-1979
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570, // 1980-1989
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0, // 1990-1999
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5, // 2000-2009
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930, // 2010-2019
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530, // 2020-2029
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45, // 2030-2039
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0, // 2040-2049
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0, // 2050-2059
        0x0a2e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4, // 2060-2069
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0, // 2070-2079
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160, // 2080-2089
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252, // 2090-2099
        0x0d520                                                                                    // 2100
    )

    /** 取某一年的编码。**夹紧下标**：表外年份宁可退化成最近的已知年，也不要抛异常。 */
    private fun info(year: Int): Int = TABLE[(year - FIRST_YEAR).coerceIn(0, TABLE.size - 1)]

    /** 闰月月份，0 = 当年不闰 */
    private fun leapMonth(year: Int): Int = info(year) and 0xF

    /** 闰月天数；不闰时为 0（这样"全年天数 = 12 个平月 + 闰月"可以直接相加） */
    private fun leapDays(year: Int): Int =
        if (leapMonth(year) == 0) 0 else if (info(year) and 0x10000 != 0) 30 else 29

    /** 第 [month] 个平月（1..12）的天数：29 或 30 */
    private fun monthDays(year: Int, month: Int): Int =
        if (info(year) and (0x10000 shr month) != 0) 30 else 29

    /** 农历某年的总天数（353..385） */
    private fun yearDays(year: Int): Int {
        var sum = 348                       // 12 个月先都按小月 29 天算
        for (month in 1..12) {
            if (monthDays(year, month) == 30) sum++
        }
        return sum + leapDays(year)
    }

    /**
     * 19 个回归年 ≈ 235 个朔望月（默冬章），因此相隔 19 年的农历月日基本相同
     * （误差约"每 200 年 1 天"）。
     *
     * 表外日期（1900-01-31 之前、2100 年之后）就靠这个周期平移到表内再算：
     * 拿到月/日后把年份差加回去。比抛异常或返回一堆 0 有意义得多 ——
     * 农历节日判断只关心"月日"，年份差个位数天对观感毫无影响。
     */
    private fun metonicShift(date: LocalDate): Int {
        val inTable = !date.isBefore(BASE) && date.year <= LAST_YEAR
        if (inTable) return 0
        val k = Math.round((2000 - date.year) / 19.0).toInt()
        val shift = k * 19
        // LocalDate 的年份上限是 ±999999999，平移越界会抛 DateTimeException。
        // 极端输入（比如公元 99 万年）干脆不平移，让它落到下面的夹紧逻辑里。
        val target = date.year.toLong() + shift
        return if (target in -999_000_000L..999_000_000L) shift else 0
    }

    /**
     * 公历 → 农历。**任何输入都不抛异常**：
     * 超出 1900–2100 时走 19 年周期近似；2100 年尾几天的溢出则夹紧到腊月，
     * 返回一个"量级合理"的值。
     */
    fun lunarOf(date: LocalDate): LunarDate {
        val shift = metonicShift(date)
        val d = if (shift == 0) date else date.plusYears(shift.toLong())

        // 从表起点开始逐年吃掉天数，定位到农历年
        var offset = ChronoUnit.DAYS.between(BASE, d).toInt()
        var year = FIRST_YEAR
        while (year < LAST_YEAR && offset >= yearDays(year)) {
            offset -= yearDays(year)
            year++
        }
        // 2100 年尾的溢出（农历 2100 年比公历年短几天）夹到该年最后一天
        if (offset >= yearDays(year)) offset = yearDays(year) - 1

        // 逐月吃掉天数。闰月紧跟在"被闰的那个月"之后，月号相同、leap = true
        val leap = leapMonth(year)
        var month = 1
        var isLeap = false
        while (true) {
            val days = if (isLeap) leapDays(year) else monthDays(year, month)
            if (offset < days) break
            offset -= days
            if (!isLeap && leap == month) {
                isLeap = true             // 进入闰 month 月，月号不变
            } else {
                isLeap = false
                month++
            }
        }
        return LunarDate(year - shift, month, offset + 1, isLeap)
    }

    private val MONTH_NAMES = arrayOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月"
    )

    /**
     * 「农历八月十五」这种中文写法。
     *
     * 农历日子**不能直接写数字**：老历里没有"1 号"，只有"初一"；
     * 廿 / 三十 这些写法在口语里就是节日的一部分（"腊月廿三"就是小年）。
     */
    fun label(date: LocalDate): String {
        val l = lunarOf(date)
        val month = MONTH_NAMES[(l.month - 1).coerceIn(0, 11)]
        return "农历" + (if (l.leap) "闰" else "") + month + dayLabel(l.day)
    }

    /** 初一…初十 / 十一…十九 / 二十 / 廿一…廿九 / 三十 */
    private fun dayLabel(day: Int): String = when {
        day == 10 -> "初十"
        day == 20 -> "二十"
        day == 30 -> "三十"
        day < 10 -> "初" + DIGITS[day]
        day < 20 -> "十" + DIGITS[day - 10]
        day < 30 -> "廿" + DIGITS[day - 20]
        else -> "三十"
    }

    /** 索引 1..9 = 一…九（0 位占位，让 DIGITS[n] 读起来跟"数字 n"一致） */
    private val DIGITS = arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
}
