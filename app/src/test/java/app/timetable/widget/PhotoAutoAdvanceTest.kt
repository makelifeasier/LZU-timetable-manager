package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动换图的**策略**（纯函数部分）：什么时候该挂闹钟、间隔取多少。
 *
 * 背景（用户第 5 条："自动轮播启动后小组件无法使用"）：老实现是布局里的
 * `AdapterViewFlipper` + `setRemoteAdapter`，宿主自己翻页 —— 但它是个可滚动的 AdapterView，
 * 竖直手势被它吃掉，课程列表就滑不动了。整条 flipper 路径已删除，自动换图改成
 * **我们自己排一个非唤醒闹钟**，到点只重画图片那一个控件（见 [PhotoAutoAdvance]）。
 *
 * 这组测试守两条线：
 *  1. **最省**：不该转的时候绝不挂闹钟（开关关着 / 只剩一张图 / 图片区根本没显示 / 桌面上没有组件）
 *     —— 用户没在看的东西不该耗电；
 *  2. **间隔有下限**：设置页给的 5 秒档、或被写坏的 0/负数，都要抬到 [PhotoAutoAdvance.MIN_INTERVAL_SEC]，
 *     否则就变成"每分钟十几次位图过 binder"，桌面滑动会卡、电池也白费。
 *
 * 只测纯函数：真正排闹钟的 sync/repaintPhoto 碰 Context 与系统服务，不进单测。
 */
class PhotoAutoAdvanceTest {

    // ------------------------------------------------------- 间隔

    @Test
    fun intervalIsClampedToTheBatteryFriendlyFloor() {
        // 设置页的 5 秒档太密（每分钟 12 次：唤醒进程 + 解码 + 传位图），抬到下限
        assertEquals(PhotoAutoAdvance.MIN_INTERVAL_SEC * 1000L, PhotoAutoAdvance.intervalMs(5))
        assertEquals(10_000L, PhotoAutoAdvance.intervalMs(5))
    }

    @Test
    fun intervalHonoursTheChoicesThatAreAlreadySlowEnough() {
        // 10 / 30 秒原样保留：设置页的这三档里，后两档是"看得出在动"与"不费电"的平衡点
        assertEquals(10_000L, PhotoAutoAdvance.intervalMs(10))
        assertEquals(30_000L, PhotoAutoAdvance.intervalMs(30))
    }

    @Test
    fun intervalNeverBecomesZeroOrNegative() {
        // 被写坏的值（0 / 负数）：宁可不动得那么勤，也不能变成死循环式刷新
        assertEquals(10_000L, PhotoAutoAdvance.intervalMs(0))
        assertEquals(10_000L, PhotoAutoAdvance.intervalMs(-5))
        // 超大值也不能溢出成负数
        assertTrue(PhotoAutoAdvance.intervalMs(Int.MAX_VALUE) > 0L)
    }

    // ------------------------------------------------------- 该不该转

    @Test
    fun runsOnlyWhenEverythingIsInPlace() {
        assertTrue(PhotoAutoAdvance.shouldRun(autoEnabled = true, photoCount = 3, photoVisible = true, widgetCount = 1))
    }

    @Test
    fun doesNotRunWhenTheSwitchIsOff() {
        // 默认就是关的（Prefs.photoFlipEnabled 默认 false）—— 不点开开关就一点都不该动
        assertFalse(PhotoAutoAdvance.shouldRun(false, 3, true, 1))
    }

    @Test
    fun doesNotRunWithASinglePhoto() {
        // 一张图"换"了还是同一张：白解码一次、白过一次 binder
        assertFalse(PhotoAutoAdvance.shouldRun(true, 1, true, 1))
        assertFalse(PhotoAutoAdvance.shouldRun(true, 0, true, 1))
    }

    @Test
    fun doesNotRunWhenThePhotoAreaIsNotShown() {
        // 图片区被隐藏（关掉开关 / 强制隐藏 / 高度不够）：转起来也看不见，纯耗电
        assertFalse(PhotoAutoAdvance.shouldRun(true, 3, photoVisible = false, widgetCount = 1))
    }

    @Test
    fun doesNotRunWhenNoWidgetIsOnTheHomeScreen() {
        // 组件被移除之后就没人看了（这也是唯一能可靠判断"用户没在看"的信号）
        assertFalse(PhotoAutoAdvance.shouldRun(true, 3, photoVisible = true, widgetCount = 0))
    }
}
