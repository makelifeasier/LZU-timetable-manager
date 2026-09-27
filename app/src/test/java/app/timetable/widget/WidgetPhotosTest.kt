package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件图片的取舍逻辑（关键场景：已经满上限之后再选图、以及**用户自己删图**）。
 *
 * 这里只测**纯函数**，不碰 Context、不真去解码图片：单测里 Android API 全是"返回默认值"的
 * 假实现（`testOptions.unitTests.isReturnDefaultValues = true`），拿它们当判据只会测出假结果。
 * 所以 import() 里"哪些留下、哪些该删"这一步被抽成 keepAfterImport / staleAfterImport，
 * 删除这一步被抽成 keepAfterRemoval / keepAfterRemovalByPath。
 *
 * 背景一（真机反馈）：已经有满上限的图时再挑 1~3 张，一张都进不来、组件里还是老那几张，
 * Toast 却说「已导入 N 张图片」，反复重试都没用，只能先点「清除图片」再来一遍。
 * 原因是 import() 在 `existing.size >= MAX_PHOTOS` 处直接 break，
 * 作者本意"新选的优先留下"的 `takeLast(max)` 永远没机会执行；而返回值又是**全部**路径，
 * 调用方拿 size 显示，就把"库存 N 张"说成了"本次导入 N 张"。这些取舍规格就是下面这几条。
 *
 * 背景二（用户要求）：上限从 5 提到 50，并且"自由删除任意图片、可多选删除"。
 * 删除的正确性全在 [WidgetPhotos.keepAfterRemoval] 这个纯函数上 —— 尤其是**下标语义**：
 * 删除用下标表达，界面看到的第 i 格就必须是记录里的第 i 项，差一格就是"点 3 删掉 4"这种
 * 用户一眼能发现、但代码里极难自查的 bug。所以这里把下标版本与路径版本的**等价性**也钉住。
 *
 * 所有用例都用 `max = WidgetData.MAX_PHOTOS` 动态表达（而不是写死 5 或 50）：
 * 这些规格属于"满上限时的行为"，上限改成多少都该成立。
 */
class WidgetPhotosTest {

    private val max = WidgetData.MAX_PHOTOS

    private fun paths(n: Int, prefix: String = "old") = (1..n).map { "/data/photos/$prefix$it.jpg" }

    // ------------------------------------------------------------ 导入时的上限取舍

    @Test
    fun newPhotosWinWhenAlreadyFull() {
        // 已经满上限，再挑 3 张 → 留 3 张新的 + 最旧的（max-3）张（新的优先，旧的被挤掉）
        val existing = paths(max)
        val added = paths(3, "new")
        val keep = WidgetPhotos.keepAfterImport(existing, added, max)

        assertEquals(max, keep.size)
        assertEquals("新选的必须全部留下", added, keep.takeLast(3))
        assertEquals(
            "留下的旧图只能是最近的那几张",
            existing.takeLast(max - 3),
            keep.take(max - 3)
        )
        // 被挤掉的正是最旧的 3 张 —— 调用方要**真的删掉**它们（别在私有目录里堆孤儿）
        assertEquals(existing.take(3), WidgetPhotos.staleAfterImport(existing, keep))
    }

    @Test
    fun newPhotosJustFillTheRemainingSlotsWhenNotFull() {
        // 还没满：已有的 2 张 + 新选的（max-2）张，刚好填满，谁也不被挤掉
        val existing = paths(2)
        val added = paths(max - 2, "new")
        val keep = WidgetPhotos.keepAfterImport(existing, added, max)

        assertEquals(max, keep.size)
        assertEquals(existing + added, keep)
        assertTrue(WidgetPhotos.staleAfterImport(existing, keep).isEmpty())
    }

    @Test
    fun pickingMoreThanTheLimitKeepsTheLastOnes() {
        // 一次挑超过上限 2 张：只能留下最后 max 张，前 2 张新图**自己也被挤掉** ——
        // 所以"本次新增几张"只能数真正留在列表里的，不能数存了几张
        val added = paths(max + 2, "new")
        val keep = WidgetPhotos.keepAfterImport(emptyList(), added, max)

        assertEquals(added.takeLast(max), keep)
        assertEquals(2, WidgetPhotos.staleAfterImport(added, keep).size)
    }

    @Test
    fun bothEvictedOldFilesAndDroppedNewFilesAreStale() {
        // 调用方实际传进来的是「已有的 + 本次新导入的」合并列表：
        // 被挤走的旧图、以及连自己都没留下的那 2 张新图，都是要删的孤儿文件
        val existing = paths(max)
        val added = paths(max + 2, "new")
        val keep = WidgetPhotos.keepAfterImport(existing, added, max)
        val stale = WidgetPhotos.staleAfterImport(existing + added, keep)

        assertEquals(existing, stale.take(max))
        assertEquals(added.take(2), stale.drop(max))
        assertEquals(max + 2, stale.size)
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
        assertEquals(max, WidgetPhotos.keepAfterImport(paths(max + 4), emptyList(), max).size)
        assertEquals(max, WidgetPhotos.keepAfterImport(paths(max + 7), paths(3, "new"), max).size)
    }

    @Test
    fun limitLeavesRoomForAWholeAlbum() {
        // 用户要求："把小组件图片显示调到 50 张"。这条钉住需求本身 ——
        // 上限只影响记录长度与设置页缩略图数量，组件里始终只显示一张（见 WidgetData.MAX_PHOTOS 注释）
        assertTrue("上限至少要有 50 张", WidgetData.MAX_PHOTOS >= 50)
    }

    // ------------------------------------------------------------ 用户自己删（单张 / 多选）

    @Test
    fun deleteByIndexRemovesExactlyThoseAndKeepsTheRest() {
        val all = paths(6)
        val keep = WidgetPhotos.keepAfterRemoval(all, listOf(1, 4))

        assertEquals(listOf(all[0], all[2], all[3], all[5]), keep)
        // 顺序必须保持：下标是"界面第 i 格"的语义，重排一次用户就会删错下一张
        assertEquals("留下的顺序不能变", all.filterIndexed { i, _ -> i != 1 && i != 4 }, keep)
    }

    @Test
    fun deleteIgnoresOutOfRangeAndDuplicateIndices() {
        // 界面状态与磁盘记录可能不同步（例如删完一批还没重建）：越界与重复下标都不许抛异常，
        // 也不许把别的格子一起删了
        val all = paths(3)

        assertEquals(all, WidgetPhotos.keepAfterRemoval(all, emptyList()))
        assertEquals(all, WidgetPhotos.keepAfterRemoval(all, listOf(-1, 3, 99)))
        assertEquals(listOf(all[0], all[2]), WidgetPhotos.keepAfterRemoval(all, listOf(1, 1, 1)))
        assertEquals(emptyList<String>(), WidgetPhotos.keepAfterRemoval(all, listOf(0, 1, 2)))
    }

    @Test
    fun deletingEverythingLeavesAnEmptyRecord() {
        // 全选删除：剩下的必须是空列表（写回时 joinToString 得到空串，界面显示 0 张）
        val all = paths(max)
        val keep = WidgetPhotos.keepAfterRemoval(all, all.indices.toList())

        assertTrue(keep.isEmpty())
        assertEquals(max, all.size - keep.size)
    }

    @Test
    fun deleteByPathAgreesWithDeleteByIndexOnTheRecordedList() {
        // 两条删除路径必须等价：界面上"点中的那几格"用下标表达，调用方也可能直接按路径删
        // （自检、以及以后可能的"按文件删"）。两者对同一份记录必须得到同一个结果。
        val all = paths(9)
        val indices = listOf(0, 3, 8)
        val byIndex = WidgetPhotos.keepAfterRemoval(all, indices)
        val byPath = WidgetPhotos.keepAfterRemovalByPath(all, indices.map { all[it] })

        assertEquals(byIndex, byPath)
        assertEquals(paths(9).filterIndexed { i, _ -> i !in indices }, byIndex)
    }

    @Test
    fun deleteByPathKeepsUnknownPathsUntouched() {
        // 路径版本传进来的可能是别人的路径（记录里没有）：不能被误删
        val all = paths(3)
        val keep = WidgetPhotos.keepAfterRemovalByPath(all, listOf("/data/photos/nope.jpg"))

        assertEquals(all, keep)
    }

    // ------------------------------------------------------------ 解码降采样

    @Test
    fun decodeSampleSizeKeepsLongEdgeUnderTheLimit() {
        // 相册原图动辄 4000×3000，交给启动器解码既慢又吃内存 —— 复制进私有目录前先按 2 的幂降采样
        assertEquals(1, WidgetPhotos.sampleSizeFor(800, 600, 1600))
        assertEquals(2, WidgetPhotos.sampleSizeFor(3200, 2400, 1600))
        assertEquals(4, WidgetPhotos.sampleSizeFor(6400, 4800, 1600))
    }
}
