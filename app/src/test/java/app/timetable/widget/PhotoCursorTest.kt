package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「点一下换下一张」的下标推进与边界回绕（纯函数部分）。
 *
 * 为什么这一组必须钉住：这是用户第 6 条要求（"切换图片从上下滑动改正为点击切换"）的全部逻辑 ——
 * 换图不再有任何手势，只有"下标 +1"。下标算错的表现是"点了没反应"或"跳到随机的一张"，
 * 而这两种在真机上都不好复现（要凑张数、要删图）。
 *
 * 存值全部先夹再用（[PhotoCursor.clamp]）：用户随时会删图/清图，张数从 5 掉到 1，
 * 或者预置状态被写坏（负数、超界）—— 这些都必须算回合法下标，而不是崩或者显示空白。
 *
 * 只测纯函数：真读 SharedPreferences 的那三个方法（current/next/remember）碰 Context，
 * 不进单测（单测里 Android API 是"返回默认值"的假实现，测了也是假结果）。
 */
class PhotoCursorTest {

    // ------------------------------------------------------- 前进一格

    @Test
    fun advanceMovesExactlyOneStepForward() {
        assertEquals(1, PhotoCursor.advance(0, 5))
        assertEquals(2, PhotoCursor.advance(1, 5))
        assertEquals(4, PhotoCursor.advance(3, 5))
    }

    @Test
    fun advanceWrapsAroundAtTheEnd() {
        // 到最后一张再点 → 回到第一张（不绕回就会停在最后一张，看起来像"点了没反应"）
        assertEquals(0, PhotoCursor.advance(4, 5))
        assertEquals(0, PhotoCursor.advance(9, 5))
    }

    @Test
    fun advanceIsStableWhenThereIsNothingToSwitchTo() {
        // 0 张（图被删光）：停在 0，不能出现负数下标
        assertEquals(0, PhotoCursor.advance(0, 0))
        assertEquals(0, PhotoCursor.advance(3, 0))
        // 1 张：点上去了还是它自己（这时点图片不会有变化，但也不能出错）
        assertEquals(0, PhotoCursor.advance(0, 1))
        assertEquals(0, PhotoCursor.advance(7, 1))
    }

    // ------------------------------------------------------- 存值自愈

    @Test
    fun clampBringsAnyStoredValueBackInRange() {
        assertEquals(0, PhotoCursor.clamp(0, 3))
        assertEquals(2, PhotoCursor.clamp(2, 3))
        assertEquals(1, PhotoCursor.clamp(4, 3))    // 张数从 5 缩到 3（删了两张图）
        assertEquals(3, PhotoCursor.clamp(3, 5))
    }

    @Test
    fun clampSurvivesGarbageStoredValues() {
        // 负数（被写坏、或旧版本换过算法）
        assertEquals(2, PhotoCursor.clamp(-1, 3))
        assertEquals(1, PhotoCursor.clamp(-5, 3))
        // 张数为 0（清空图片）：一律落到 0，绝不能是负数
        assertEquals(0, PhotoCursor.clamp(4, 0))
        assertEquals(0, PhotoCursor.clamp(-4, 0))
    }

    @Test
    fun clampKeepsTheSequenceWalkableAfterPhotosShrink() {
        // 回归场景：存的是第 4 张（下标 3），用户删到只剩 2 张。
        // 夹回合法值之后，"再点一下"必须走到**另一张**，而不是停在原地。
        val shrunk = PhotoCursor.clamp(3, 2)
        assertEquals(1, shrunk)
        assertEquals(0, PhotoCursor.advance(shrunk, 2))
    }

    @Test
    fun aFullCycleVisitsEveryPhotoExactlyOnce() {
        // 5 张图点 5 下：正好把 5 张各看一遍，第 6 下回到起点（用户点一圈不会漏图/重复）
        val seen = ArrayList<Int>()
        var at = 0
        repeat(5) {
            seen += at
            at = PhotoCursor.advance(at, 5)
        }
        assertEquals(listOf(0, 1, 2, 3, 4), seen)
        assertEquals(0, at)
    }
}
