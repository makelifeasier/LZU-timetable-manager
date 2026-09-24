package app.timetable.greet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 每日一句的取句规则。
 *
 * 这里的三个断言对应三个真实约束：
 *  - 同一天稳定 —— 小组件一天会被刷新多次，句子跳来跳去会显得很廉价；
 *  - 相邻日子会变 —— 否则"每日一句"就成了"每月一句"；
 *  - 长度 ≤ 20 —— 小组件那一行放不下更多，超了会被截断成半句。
 */
class QuoteOfDayTest {

    private val anchor = LocalDate.of(2026, 1, 1)

    @Test
    fun sameDayAlwaysGivesTheSameQuote() {
        val d = LocalDate.of(2026, 9, 17)
        val first = QuoteOfDay.forDate(d)
        repeat(5) { assertEquals(first, QuoteOfDay.forDate(d)) }
    }

    @Test
    fun quotesChangeAcrossDays() {
        // 抽连续 30 天：至少要出现 10 句不同的，否则池子太小或取模有问题
        val distinct = (0 until 30).map { QuoteOfDay.forDate(anchor.plusDays(it.toLong())) }.toSet()
        assertTrue("30 天只出现了 ${distinct.size} 句", distinct.size >= 10)
    }

    @Test
    fun poolHasAtLeastFortyQuotesAndCyclesWithoutRepeat() {
        // 连续 48 天内不应重复：这既是"池子 ≥ 40"的证明，也说明取模没有退化
        val window = (0 until 48).map { QuoteOfDay.forDate(anchor.plusDays(it.toLong())) }
        assertEquals("48 天内出现了重复句子", 48, window.toSet().size)

        // 找出重复周期：它必须等于池子大小，且 ≥ 40
        val cycle = (1..120).first { QuoteOfDay.forDate(anchor.plusDays(it.toLong())) == window[0] }
        assertTrue("句子池只有 $cycle 句，要求 ≥ 40", cycle >= 40)
    }

    @Test
    fun everyQuoteFitsOneWidgetLine() {
        for (i in 0 until 400) {
            val q = QuoteOfDay.forDate(anchor.plusDays(i.toLong()))
            assertTrue("句子为空", q.isNotBlank())
            assertTrue("句子过长（${q.length} 字）：$q", q.length <= 20)
        }
    }

    @Test
    fun datesBeforeEpochDoNotCrash() {
        // epochDay 为负时如果直接用 % 会得到负下标 → 越界崩。这里覆盖 1950/1969/1970 前后
        for (text in listOf("1950-01-01", "1969-12-31", "1970-01-01", "1970-01-02")) {
            assertTrue(QuoteOfDay.forDate(LocalDate.parse(text)).isNotBlank())
        }
    }
}
