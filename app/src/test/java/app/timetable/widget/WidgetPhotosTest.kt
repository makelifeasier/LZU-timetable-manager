package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件图片的取舍逻辑（关键场景：满 5 张之后再选图）。
 *
 * 这里只测**纯函数**，不碰 Context、不真去解码图片：单测里 Android API 全是"返回默认值"的
 * 假实现（`testOptions.unitTests.isReturnDefaultValues = true`），拿它们当判据只会测出假结果。
 * 所以 import() 里"哪些留下、哪些该删"这一步被抽成 keepAfterImport / staleAfterImport。
 *
 * 背景（真机反馈）：已经有 5 张图时再挑 1~3 张，一张都进不来、组件里还是老 5 张，
 * Toast 却说「已导入 5 张图片」，反复重试都没用，只能先点「清除图片」再来一遍。
 * 原因是 import() 在 `existing.size >= MAX_PHOTOS` 处直接 break，
 * 作者本意"新选的优先留下"的 `takeLast(5)` 永远没机会执行；而返回值又是**全部**路径，
 * 调用方拿 size 显示，就把"库存 5 张"说成了"本次导入 5 张"。这些取舍规格就是下面这几条。
 */
class WidgetPhotosTest {

    private val max = WidgetData.MAX_PHOTOS

    private fun paths(n: Int, prefix: String = "old") = (1..n).map { "/data/photos/$prefix$it.jpg" }

    @Test
    fun newPhotosWinWhenAlreadyFull() {
        // 已有 5 张，再挑 3 张 → 留 3 张新的 + 最旧的 2 张（新的优先，旧的被挤掉）
        val existing = paths(5)
        val added = paths(3, "new")
        val keep = WidgetPhotos.keepAfterImport(existing, added, max)

        assertEquals(max, keep.size)
        assertEquals("新选的必须全部留下", added, keep.takeLast(3))
        assertEquals("留下的旧图只能是最近的两张", existing.takeLast(2), keep.take(2))
        // 被挤掉的正是最旧的 3 张 —— 调用方要**真的删掉**它们（别在私有目录里堆孤儿）
        assertEquals(existing.take(3), WidgetPhotos.staleAfterImport(existing, keep))
    }

    @Test
    fun newPhotosJustFillTheRemainingSlotsWhenNotFull() {
        // 还没满：已有的 + 新选的，一共 5 张，谁也不被挤掉
        val existing = paths(2)
        val added = paths(3, "new")
        val keep = WidgetPhotos.keepAfterImport(existing, added, max)

        assertEquals(max, keep.size)
        assertEquals(existing + added, keep)
        assertTrue(WidgetPhotos.staleAfterImport(existing, keep).isEmpty())
    }

    @Test
    fun pickingMoreThanTheLimitKeepsTheLastOnes() {
        // 一次挑 7 张：只能留下最后 5 张，前 2 张新图**自己也被挤掉** ——
        // 所以"本次新增几张"只能数真正留在列表里的，不能数存了几张
        val added = paths(7, "new")
        val keep = WidgetPhotos.keepAfterImport(emptyList(), added, max)

        assertEquals(added.takeLast(max), keep)
        assertEquals(2, WidgetPhotos.staleAfterImport(added, keep).size)
    }

    @Test
    fun bothEvictedOldFilesAndDroppedNewFilesAreStale() {
        // 调用方实际传进来的是「已有的 + 本次新导入的」合并列表：
        // 被挤走的 5 张老图、以及连自己都没留下的 2 张新图，都是要删的孤儿文件
        val existing = paths(5)
        val added = paths(7, "new")
        val keep = WidgetPhotos.keepAfterImport(existing, added, max)
        val stale = WidgetPhotos.staleAfterImport(existing + added, keep)

        assertEquals(existing, stale.take(5))
        assertEquals(added.take(2), stale.drop(5))
        assertEquals(7, stale.size)
    }

    @Test
    fun nothingImportedLeavesTheListAlone() {
        // 一张都没存下来（URI 权限被回收、格式不支持…）：列表原样，也不能误删任何文件
        val existing = paths(4)
        val keep = WidgetPhotos.keepAfterImport(existing, emptyList(), max)

        assertEquals(existing, keep)
        assertTrue(WidgetPhotos.staleAfterImport(existing, keep).isEmpty())
    }

    @Test
    fun neverKeepsMoreThanTheLimit() {
        // 记录被写坏（超过上限）时也要收敛回来，多的当孤儿删掉
        assertEquals(max, WidgetPhotos.keepAfterImport(paths(9), emptyList(), max).size)
        assertEquals(max, WidgetPhotos.keepAfterImport(paths(12), paths(3, "new"), max).size)
    }

    @Test
    fun decodeSampleSizeKeepsLongEdgeUnderTheLimit() {
        // 相册原图动辄 4000×3000，交给启动器解码既慢又吃内存 —— 复制进私有目录前先按 2 的幂降采样
        assertEquals(1, WidgetPhotos.sampleSizeFor(800, 600, 1600))
        assertEquals(2, WidgetPhotos.sampleSizeFor(3200, 2400, 1600))
        assertEquals(4, WidgetPhotos.sampleSizeFor(6400, 4800, 1600))
    }
}
