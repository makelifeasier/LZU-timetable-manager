package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「整份列表」模式（[DayOverrides.Override.isList]）的纯逻辑测试。
 *
 * 它是给"点课表上某一节课、单独改掉或删掉它"用的：原来的三种模式都做不到这件事 ——
 * ADD 是"在原有课之上再加"（原来的那节盖不住），REPLACE 只存"换成第几周的那一天"
 * （装不下一个任意列表）。所以必须有一套"这一天就是这份列表"的语义。
 *
 * 为什么语义要单独钉住：错了的表现是"用户删了一节课，过一会儿它又回来了"，
 * 而且是在他自己的数据上 —— 这种事没有第二次解释机会。
 *
 * JSON 往返**不在这里测**：单测里 org.json 是返回默认值的假实现
 * （app/build.gradle.kts 的 unitTests.isReturnDefaultValues），
 * 所以序列化那两行只靠代码评审；能测的语义全部放在下面。
 */
class DayOverridesListTest {

    private fun session(name: String, day: Int, start: Int, end: Int, room: String = "") =
        Session(name = name, room = room, day = day, startSection = start, endSection = end)

    private val base = listOf(
        session("高等数学", 3, 1, 2),
        session("大学英语", 3, 5, 6)
    )

    private val monday = java.time.LocalDate.of(2026, 9, 21)

    @Test
    fun listModeReturnsExactlyWhatWasStored() {
        val stored = listOf(session("物理实验", 3, 3, 4), session("形势与政策", 3, 7, 8))
        val out = DayOverrides.apply(base, DayOverrides.Override.list(stored), day = 3)
        assertEquals(stored, out)
    }

    @Test
    fun listModeIgnoresTheUnderlyingTimetable() {
        // "把这一天第 3 节的课改掉"= 整份列表里只留新的那些 ——
        // 如果这里还漏出 base 里的课，用户会看到"改了一节，原来的还在"
        val stored = listOf(session("补课：物理实验", 3, 3, 4))
        val out = DayOverrides.apply(base, DayOverrides.Override.list(stored), day = 3)
        assertEquals(1, out.size)
        assertTrue(out.none { it.name == "高等数学" || it.name == "大学英语" })
    }

    @Test
    fun listModeNormalizesTheDayOfEveryEntry() {
        // day 只是记录，不该盖过"这个覆盖挂在哪个日期上"这件事（与 ADD 的口径一致）
        val stored = listOf(session("物理实验", 0, 3, 4), session("形势与政策", 5, 7, 8))
        val out = DayOverrides.apply(base, DayOverrides.Override.list(stored), day = 3)
        assertEquals(listOf(3, 3), out.map { it.day })
    }

    @Test
    fun emptyListIsAValidStateAndClearsTheDay() {
        val out = DayOverrides.apply(base, DayOverrides.Override.list(emptyList()), day = 3)
        assertTrue("把这一天的课全删掉是合法状态，结果应当是空的", out.isEmpty())
        // …但它**不是**"没调过"：没有覆盖时 base 原样返回
        assertEquals(base, DayOverrides.apply(base, null, day = 3))
    }

    @Test
    fun plainClearStillClearsAndIsNotConfusedWithListMode() {
        // 回归：LIST 是独立模式，别把原来那个"清空当天"顺手改成"返回 extra"
        val plain = DayOverrides.Override(DayOverrides.Mode.CLEAR)
        assertFalse(plain.isList)
        assertTrue(DayOverrides.apply(base, plain, day = 3).isEmpty())
    }

    @Test
    fun listModeIsItsOwnModeAndIsNotAdd() {
        val o = DayOverrides.Override.list(listOf(session("物理实验", 3, 3, 4)))
        assertTrue(o.isList)
        assertEquals(DayOverrides.Mode.LIST, o.mode)
        // 不能是 ADD：ui/DayEditDialog 的「加一节课」只对 mode == ADD 放行，
        // 搭在 ADD 上它会把这份列表 + 新加的一节写成 base+extra —— 用户删掉的课会静默冒回来
        assertNotEquals(DayOverrides.Mode.ADD, o.mode)
        // 也不能是 CLEAR：那是"这一天空着"的语义，而 LIST 允许 extra 里有课
        assertNotEquals(DayOverrides.Mode.CLEAR, o.mode)
    }

    @Test
    fun isListIsDerivedFromModeSoTheTwoCanNeverDisagree() {
        // isList 只读、由 mode 推导：没有"mode=ADD 但自称是整份列表"这种自相矛盾的状态
        assertTrue(DayOverrides.Override(DayOverrides.Mode.LIST, extra = emptyList()).isList)
        listOf(DayOverrides.Mode.REPLACE, DayOverrides.Mode.CLEAR, DayOverrides.Mode.ADD).forEach {
            assertFalse("$it 不是列表模式", DayOverrides.Override(it).isList)
        }
    }

    @Test
    fun listForMergesAdjacentSectionsLikeTheRestOfTheApp() {
        // 仓库（TimetableRepository.sessionsOn）走的就是这个函数：
        // extra 里相邻的两段同一门课要合并成「第3-4节」一张卡片，与没调课时一致
        val stored = listOf(session("辅导班", 3, 3, 3), session("辅导班", 3, 4, 4))
        val shown = DayOverrides.listFor(DayOverrides.Override.list(stored), day = 3)
        assertEquals(1, shown.size)
        assertEquals(3, shown[0].startSection)
        assertEquals(4, shown[0].endSection)
    }

    @Test
    fun listForKeepsDifferentLessonsApart() {
        val stored = listOf(session("物理实验", 3, 3, 4), session("形势与政策", 3, 5, 6))
        val shown = DayOverrides.listFor(DayOverrides.Override.list(stored), day = 3)
        assertEquals(
            "课名不同就是两段课，哪怕节次挨着（合并判据是 Session.key）",
            listOf("物理实验", "形势与政策"), shown.map { it.name }
        )
    }

    @Test
    fun listForSortsBySection() {
        val stored = listOf(session("晚一点的", 3, 7, 8), session("早一点的", 3, 1, 2))
        val shown = DayOverrides.listFor(DayOverrides.Override.list(stored), day = 3)
        assertEquals(listOf(1, 7), shown.map { it.startSection })
    }

    @Test
    fun listForOfAnEmptyListIsAnEmptyDay() {
        assertTrue(DayOverrides.listFor(DayOverrides.Override.list(emptyList()), day = 3).isEmpty())
    }

    @Test
    fun listModeOverrideIsStillPrunedByDate() {
        // 列表模式没有绕过"过期覆盖要清掉"这条（它同样是按日期存的）
        val o = DayOverrides.Override.list(listOf(session("旧课", 3, 3, 4)))
        val today = monday.plusDays(20)
        val kept = DayOverrides.aliveKeys(mapOf(monday to o, today to o), today)
        assertEquals(setOf(today), kept.keys)
    }
}
