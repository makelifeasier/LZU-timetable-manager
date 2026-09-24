package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 换学期后「第 1 周周一」该怎么办。
 *
 * 这里测的是 [TimetableRepository.planWeek1] 这个纯决策函数 —— commit() 本身要 Context，
 * 在 JVM 单测里跑不起来（unitTests.isReturnDefaultValues = true，没有真实现），
 * 所以决策从 IO 里剥出来单独验。
 *
 * 对应的是这条每年复发一次的故障链路：
 *   上学期装过 → 下学期重新导入 → week1 还是上学期日期 → 当前周次算成 30+
 *   → sessionsOn / weekGrid 一条 `weeks.contains(week)` 都命中不了
 *   → 课表页空白、小组件空、提醒不排，而且「本周」按钮也救不回来（算出来的周次本来就是错的）。
 */
class TimetableRepositoryWeek1Test {

    private val autumn2026 = TimetableRepository.termSignatureOf(TermInfo(year = "2026", term = "秋"))
    private val spring2027 = TimetableRepository.termSignatureOf(TermInfo(year = "2027", term = "春"))

    /** 2026 秋的第 1 周周一（周三），来自真实校历形状 */
    private val autumnWeek1 = "2026-09-07"

    // ------------------------------------------------------ 学期签名

    @Test
    fun signatureIsStableAndDistinguishesTerms() {
        assertEquals("2026 秋", autumn2026)
        assertEquals("2027 春", spring2027)
        // 年份变了、季节变了，都是另一份课表
        assertTrue(TimetableRepository.termSignatureOf(TermInfo("2027", "秋")) != autumn2026)
        assertTrue(TimetableRepository.termSignatureOf(TermInfo("2026", "春")) != autumn2026)
    }

    @Test
    fun signatureIgnoresStudentNoAndClassName() {
        // 学号/班级不进签名：同一个班同一个人，学号打印格式变化不该被当成换学期
        val a = TimetableRepository.termSignatureOf(TermInfo("2026", "秋", "3202100000", "2021级"))
        val b = TimetableRepository.termSignatureOf(TermInfo("2026", "秋", "", ""))
        assertEquals(a, b)
    }

    @Test
    fun signatureIsBlankWhenTermMissing() {
        assertEquals("", TimetableRepository.termSignatureOf(TermInfo()))
        assertEquals("", TimetableRepository.termSignatureOf(TermInfo(year = "  ", term = "")))
    }

    @Test
    fun signatureToleratesWhitespaceFromHtml() {
        assertEquals("2026 秋", TimetableRepository.termSignatureOf(TermInfo(" 2026 ", " 秋 ")))
    }

    // ------------------------------------------------------ 决策：核心回归

    @Test
    fun reimportSameTermNeverOverwritesManualWeek1() {
        // 用户在第 3 周把手动校正过（上学期估算偏了一周），之后每天的自动同步
        // 都会走到这里 —— 一次都不能覆盖他的值，否则校正形同虚设。
        val plan = TimetableRepository.planWeek1(autumn2026, autumnWeek1, autumn2026)
        assertFalse("同一学期不许重算基准", plan.reestimateWeek1)
        assertFalse("同一学期不许清手动周次", plan.resetManualWeek)
        assertFalse(plan.seedSignature)
    }

    @Test
    fun termChangeForcesReestimate() {
        // 上学期存的是 2026-09-07，现在是 2027 春 —— 必须重算
        val plan = TimetableRepository.planWeek1(autumn2026, autumnWeek1, spring2027)
        assertTrue("换学期必须重算第 1 周", plan.reestimateWeek1)
        assertTrue("换学期必须写新签名", plan.seedSignature)
        assertTrue("换学期要把手动周次清回跟随今天", plan.resetManualWeek)
        assertTrue(plan.termChanged)
    }

    @Test
    fun yearChangeAloneIsAlsoATermChange() {
        val plan = TimetableRepository.planWeek1(
            TimetableRepository.termSignatureOf(TermInfo("2026", "春")),
            "2026-02-23",
            TimetableRepository.termSignatureOf(TermInfo("2027", "春"))
        )
        assertTrue(plan.reestimateWeek1)
    }

    @Test
    fun legacyUpgradeOnlySeedsSignatureWithoutTouchingWeek1() {
        // 老版本升级上来的用户：签名从没存过（空），但基准已经有了，而且可能是手动校正过的。
        // 这时只能补签名，绝不能重算 —— 否则一次升级把所有人的校正清零。
        val plan = TimetableRepository.planWeek1("", autumnWeek1, autumn2026)
        assertFalse("老用户升级不许重算基准", plan.reestimateWeek1)
        assertFalse(plan.resetManualWeek)
        assertTrue("但要补签名，下次换学期才认得出来", plan.seedSignature)
    }

    @Test
    fun firstEverImportSeedsSignatureButIsNotATermChange() {
        val plan = TimetableRepository.planWeek1("", "", autumn2026)
        assertTrue(plan.seedSignature)
        assertFalse(plan.reestimateWeek1)
        // 首次导入不算"换学期"：基准本来就空着，commit 里 `week1Monday.isBlank()` 那一支会补上
        assertFalse(plan.termChanged)
    }

    @Test
    fun sameTermWithBlankWeek1FallsBackToDefaultWeek() {
        // 签名在、基准却没了（用户清过数据）：估算一个，并把手动周次清回跟随今天，
        // 不然会拿"第 12 周"去配一个刚估出来的基准，得到一段谁也没见过的课表。
        val plan = TimetableRepository.planWeek1(autumn2026, "", autumn2026)
        assertFalse(plan.reestimateWeek1)
        assertTrue(plan.resetManualWeek)
    }

    @Test
    fun missingTermInNewPageChangesNothing() {
        // 页面改版/抽取失败导致学年季节解析不出来：既不能写空签名，也不能瞎估
        val plan = TimetableRepository.planWeek1(autumn2026, autumnWeek1, "")
        assertFalse(plan.seedSignature)
        assertFalse(plan.reestimateWeek1)
        assertFalse(plan.resetManualWeek)
    }

    // ------------------------------------------------------ 修复前 vs 修复后（同一条时间线）

    @Test
    fun staleWeek1MakesWholeTermEmptyAndReestimateFixesIt() {
        val week1Autumn = LocalDate.parse(autumnWeek1)          // 2026-09-07
        val reimportDay = LocalDate.of(2027, 3, 1)              // 2027 春第 2 周的周一（开学后）

        val parsed = ParseResult(
            term = TermInfo("2027", "春"),
            sessions = listOf(
                Session(
                    name = "高等数学", day = 1, startSection = 1, endSection = 2,
                    weeks = WeekSpan(1, 18)
                )
            )
        )

        // 修复前：基准不重算，2027-03-01 落在 2026-09-07 之后的第 26 周 → 查不到任何课
        val staleWeek = WeekCalc.weekOf(reimportDay, week1Autumn)
        assertEquals(26, staleWeek)
        assertTrue(WeekCalc.sessionsFor(parsed, 1, staleWeek).isEmpty())

        // 修复后：planWeek1 判定换学期 → commit 用 initialWeek1Monday 重估基准
        val plan = TimetableRepository.planWeek1(autumn2026, autumnWeek1, spring2027)
        assertTrue(plan.reestimateWeek1)
        val newWeek1 = WeekCalc.initialWeek1Monday(parsed, reimportDay)
        assertEquals("春季学期基准 = 2/20 之后第一个周一", LocalDate.of(2027, 2, 22), newWeek1)

        val freshWeek = WeekCalc.weekOf(reimportDay, newWeek1)
        assertEquals("重估后应当落在第 2 周", 2, freshWeek)
        assertEquals(1, WeekCalc.sessionsFor(parsed, 1, freshWeek).size)
    }

    @Test
    fun reestimateUsesParsedResultSoGridMatchesAgain() {
        // 端到端形状校验：重估之后 weekGrid 必须真的有课，而按旧基准是全空的
        val parsed = ParseResult(
            term = TermInfo("2027", "春"),
            sessions = listOf(
                Session(name = "大学物理", day = 3, startSection = 5, endSection = 6, weeks = WeekSpan(1, 16))
            )
        )
        val today = LocalDate.of(2027, 3, 3)                    // 周三
        val newWeek1 = WeekCalc.initialWeek1Monday(parsed, today)
        val week = WeekCalc.weekOf(today, newWeek1)
        assertEquals(2, week)
        assertTrue(WeekCalc.weekGrid(parsed, week)[3].orEmpty().isNotEmpty())
        assertTrue(WeekCalc.weekGrid(parsed, 26)[3].orEmpty().isEmpty())
    }
}
