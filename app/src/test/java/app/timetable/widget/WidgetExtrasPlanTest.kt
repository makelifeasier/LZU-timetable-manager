package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 底部扩展区（现在只剩图片）的取舍规则 + 图片框高度 + **预留空间与行数的关系**。
 *
 * 这一组规格全部来自真机反馈：
 *  - **每日一句拼在副标题那一行**（用户原话："把话放在第 n 周接下来第 n 节同一行"）：
 *    它不占列表格子、也不占固定高度，连底部那条「X 分钟前同步」的页脚也按用户要求整行删掉了。
 *    于是扩展区只剩图片，"句子和图片抢同一个预算、谁让谁"那套规则整条删掉了 ——
 *    "两个都开时句子被图片挤没"那类 bug 从此不可能再出现，因为两块不再共用一个位置。
 *  - **图片框高度 = 列表下方剩下的全部空间**（− 6dp 上边距）：本轮的核心。
 *    行数先按整行定死（[WidgetData.photoRowsFor]），剩下的**全部**（含那条不足一整行的零头）
 *    都归图片框 —— 于是底部零头恒为 0（判据 [ExtrasPlanner.leftoverBelowBoxDp]）。
 *    照片"想要"多高（[WidgetData.photoWantedHeightDp]）只用来决定排行数时的预留，
 *    以及在强制显示档里当框高用。
 *  - **比例一致 → 整张；不一致 → 居中取一块填满**：框高由空间决定之后，框与照片的比例不一定相等，
 *    这一档由 [PhotoFit.layout] 统一处理（绝不拉伸、绝不留白）。
 *  - **行数先把图片预留扣掉**（`rowsFor(..., reserveDp)`）：这样"向下拉组件"时多出来的高度
 *    先变成整行课（一档高度内行数不变），最后那点零头才归图片框。
 *  - **框的高度就是解码尺寸**：位图逐值等于框 → 宿主端 fitCenter 是恒等变换。
 *
 * 只测纯函数：单测里 Android API 全是"返回默认值"的假实现
 * （`testOptions.unitTests.isReturnDefaultValues = true`），碰 Context 的代码一律不进这里。
 */
class WidgetExtrasPlanTest {

    private val minPhoto = WidgetData.PHOTO_HARD_MIN_DP  // 32
    private val gap = WidgetData.AREA_GAP_DP             // 6

    /**
     * "照片自己要的框高"的典型值：150dp = [WidgetData.PHOTO_MAX_DP]（照片比例很大时撞上限）。
     *
     * 注意这个参数**不是**"空间能给多高"（老名字叫 idealPhotoDp）：自动档的框高由余量决定，
     * 传进来的这个数只表示"照片想要多高"，用来验证"框高 ≥ 它"这条下界。
     */
    private val wantedPhoto = WidgetData.PHOTO_MAX_DP

    private fun plan(
        override: Int = 0,
        photo: Boolean = true,
        count: Int = 3,
        availableDp: Int = 200,
        wantedPhotoDp: Int = wantedPhoto
    ) = ExtrasPlanner.plan(override, photo, count, availableDp, wantedPhotoDp)

    // ------------------------------------------------- 图片独占：余量全归它，一级不留

    @Test
    fun thePhotoTakesTheWholeLeftoverAndNothingElseCompetesForIt() {
        // 余量 80dp：扣掉 6dp 上边距之后**全部**归图片框（句子已经搬进列表，不在这里抢）
        val p = plan(availableDp = 80)
        assertTrue(p.photoVisible)
        assertEquals("剩下的全部空间都该归图片框（一格都不留）", 74, p.photoHeightDp)
        assertEquals("底部零头 = 0", 0, ExtrasPlanner.leftoverBelowBoxDp(p))
    }

    @Test
    fun aTooSmallPhotoAreaIsHiddenInsteadOfShownAsASliver() {
        // 余量 30dp：扣掉 6dp 上边距只剩 24dp，低于硬底线 32dp → 不显示
        // （宁可什么都不显示，也不要一条 24dp 的彩条；更不能给 0 高度让宿主量出 0）
        val p = plan(availableDp = 30)
        assertFalse(p.photoVisible)
        assertEquals(0, p.photoHeightDp)
        // 刚好够的那一档：38dp 余量 → 32dp 框，正好等于硬底线
        val just = plan(availableDp = gap + minPhoto)
        assertTrue(just.photoVisible)
        assertEquals(minPhoto, just.photoHeightDp)
    }

    // ------------------------------------------------- override 的三种语义

    @Test
    fun forceHideHidesThePhotoArea() {
        val p = plan(override = -1, availableDp = 500)
        assertFalse(p.photoVisible)
        assertEquals(0, p.photoHeightDp)
    }

    @Test
    fun forceShowBeatsTheAutoRuleWhenSpaceSaysNothing() {
        // 真机/模拟器实测：启动器上报的高度偏小，余量只有 20dp —— 自动规则下放不下
        val auto = plan(override = 0, availableDp = 20)
        assertFalse("20dp 余量本来就不该显示图片（不挤课程是底线）", auto.photoVisible)
        // 少数机型上报的高度根本是错的（荣耀把声明值当高度回显），这时靠"强制显示"救场
        val forced = plan(override = 1, availableDp = 20)
        assertTrue("强制显示必须把图片摆上去", forced.photoVisible)
        assertTrue("高度至少得能看见", forced.photoHeightDp >= minPhoto)
        // 与老代码的宽松度对齐：老是 min(理想, 余量 + 22)，不能比它更小
        assertTrue("不能比老代码显示得更小", forced.photoHeightDp >= 20 + WidgetData.EXTRA_MIN_DP)
    }

    @Test
    fun forceShowUsesTheIdealHeightWhenSpaceIsPlentiful() {
        val p = plan(override = 1, availableDp = 300, wantedPhotoDp = 90)
        assertEquals(90, p.photoHeightDp)
    }

    @Test
    fun forceShowStillGivesAVisibleHeightWhenIdealIsUnknown() {
        // 理想高度取不到时（photoHeightDp 返回 56）强制显示也要给个能看见的高度
        val p = plan(override = 1, availableDp = 0, wantedPhotoDp = WidgetData.PHOTO_MIN_DP)
        assertTrue(p.photoVisible)
        assertTrue("不能出现 0dp 的图片框", p.photoHeightDp >= minPhoto)
    }

    @Test
    fun forceShowWithNoPhotosShowsNothing() {
        // 开关开着但图被删光了（很常见）：不能显示一个空框
        assertFalse(plan(override = 1, count = 0).photoVisible)
        assertFalse(plan(override = 0, count = 0).photoVisible)
        assertFalse(plan(photo = false, count = 3).photoVisible)
    }

    // ------------------------------------------------- 预算的算术边界

    @Test
    fun photoHeightNeverExceedsTheBudget() {
        // 把所有可能的余量都扫一遍：只要图片可见，就绝不能超出余量（否则会被裁掉一截），
        // 而且**正好用光**余量（框吃掉剩下的每一 dp → 底部零头恒为 0）
        for (avail in 0..300) {
            val p = plan(availableDp = avail, wantedPhotoDp = 400)
            if (!p.photoVisible) continue
            val cost = gap + p.photoHeightDp
            assertTrue("余量 ${avail}dp 时总占用 ${cost}dp 超了", cost <= avail)
            assertEquals("余量 ${avail}dp 时还剩 $cost → ${avail - cost}dp 没被用上", avail, cost)
            assertTrue("图片高度不能低于硬底线", p.photoHeightDp >= minPhoto)
            assertEquals("底部零头必须是 0", 0, ExtrasPlanner.leftoverBelowBoxDp(p))
        }
    }

    @Test
    fun theBoxEatsTheWholeLeftoverWhenThereIsPlentyOfRoom() {
        // 余量远大于照片想要的高度（300dp vs 90dp）：框**不再**停在 90dp ——
        // 停在那个数上就会在组件底部漏出 210dp 的空白（用户反馈的"下拉之后依然有空白"）。
        // 现在它拿走剩下的全部（300 − 6 上边距 = 294），底部零头 = 0；
        // 多出来的高度由 PhotoFit"居中取一块填满"消化，绝不拉伸、也绝不留白。
        val p = plan(availableDp = 300, wantedPhotoDp = 90)
        assertEquals(294, p.photoHeightDp)
        assertEquals("底部零头 = 0", 0, ExtrasPlanner.leftoverBelowBoxDp(p))
    }

    @Test
    fun negativeSpaceIsTreatedAsZero() {
        // 上报高度可能小于头部（极端窄格子）：不能出现负预算
        val p = plan(availableDp = -50)
        assertFalse(p.photoVisible)
        assertEquals(0, p.photoHeightDp)
    }

    // ------------------------------------------------- 图片框高度（纯函数）
    //
    // 这一组是本轮改的：图片框高度**由照片自己的比例决定**。
    //
    // 上一版是"只由宽度决定（写死 16:9）"，再上一版是"按组件高度在 0.72 / 1.15 之间跳"。
    // 两个都错在同一个地方：**框的比例不等于用户裁好的照片比例**。
    //  - 后者每次换个高度就换个比例，`centerCrop` 于是每次裁在别处（"显示窗口和图片大小对不上"）；
    //  - 前者看起来只是"定一个好看的形状"，但用户裁出来的照片往往是别的比例（例如 3.14:1），
    //    框比照片高出来的每一 dp 都会变成图片上下的空白。真机反馈"图片保持不变会流出很多空白"
    //    就是 16:9 加上"框高按余量算"两件事叠出来的（4×3 时框 1.67:1、照片 3.14:1 → 留边 28%）。
    //    详见 WidgetData.photoWantedHeightDp 的注释。

    @Test
    fun theBoxKeepsThePhotoShapeNotSomeFixedAspect() {
        // 同一张照片：内容宽翻倍，框高就该翻倍（比例守恒），而不是各自去凑某个固定比例
        val aspect = 3.14f
        assertEquals(144, WidgetData.photoWantedHeightDp(452, aspect))
        assertEquals(72, WidgetData.photoWantedHeightDp(226, aspect))
        // 与比例的关系必须精确（差一点点只是四舍五入）；注意窄格子上会撞下限，见 clamp 那条测试
        assertEquals(71, WidgetData.photoWantedHeightDp(126, 16f / 9f))
        assertEquals(127, WidgetData.photoWantedHeightDp(226, 16f / 9f))
        assertEquals(113, WidgetData.photoWantedHeightDp(226, 2f))
        for (width in listOf(226, 300, 400)) {
            val h = WidgetData.photoWantedHeightDp(width, aspect)
            assertEquals(
                "内容宽 ${width}dp 时框高比例应≈$aspect，实际 ${width.toFloat() / h}",
                aspect, width.toFloat() / h, 0.05f
            )
        }
    }

    @Test
    fun theBoxUsesTheContentWidthNotTheCellWidth() {
        // 关键回归：算框高必须用**内容宽度**（格子宽 − 左右各 12dp 内边距）。
        // 老算法按格子宽度 ×1.15 会把一个 126dp 宽的框撑成 150dp 高（近乎正方形），
        // 正是真机反馈里"右边一个近似正方形的块"。
        val aspect = 16f / 9f
        assertTrue(
            "按格子宽度算出来的高度必须比按内容宽度算的更高（这就是内边距那 24dp 的作用）",
            WidgetData.photoWantedHeightDp(150, aspect) > WidgetData.photoWantedHeightDp(126, aspect)
        )
        assertEquals(
            "内容宽 126dp 的框，16:9 应当是 71dp（而不是按格子宽 150dp 算出来的 84dp）",
            71, WidgetData.photoWantedHeightDp(126, aspect)
        )
    }

    // ------------------------------------------------- 什么都不会去猜：没有照片就没有框

    @Test
    fun withoutARatioTheBoxIsZeroInsteadOfAGuessedAspect() {
        // 上一版在这里退回 16:9（"取不到尺寸就给保守值"）。现在一律给 0，因为**任何**固定比例
        // 都会让框与用户裁好的照片不同形 —— 那正是本次要修的病。0 的语义是"没有图片框"：
        // 不预留高度、不显示图片区，与"没开图片"完全同一条路（下一条测试逐值验证）。
        assertEquals(0, WidgetData.photoWantedHeightDp(0, 16f / 9f))
        assertEquals(0, WidgetData.photoWantedHeightDp(-1, 16f / 9f))
        assertEquals(0, WidgetData.photoWantedHeightDp(226, null))
        // 裁剪界面那边仍然有它自己的兜底（2:1 只是**画**一个框，不参与任何排版数字），
        // 见 PhotoCrop.FALLBACK_ASPECT_W/H 的注释
        assertTrue(PhotoCrop.frame(226, WidgetData.photoWantedHeightDp(226, null)).fallback)
    }

    // ------------------------------------------------- 图片框"实际"高度（纯函数）

    @Test
    fun photoBoxTargetIsDerivedFromThePhotoNotFromTheLeftoverSpace() {
        // 本轮的根：框高**不再**是"余量 − 6dp"，而是"照片需要多高"（比例 × 内容宽度）。
        // 真机事故（用户原话"图片保持不变会流出很多空白"）：用户按 4×2 的形状裁好照片后把组件
        // 下拉变高，老算法把框拉到 135dp，而照片只要 72dp → 完整显示 + 上下各留一大段空白。
        val ratio = 720f / 229f        // 用户按 4×2 的 226:72 裁出来的那张（3.14:1）
        assertEquals(72, WidgetData.photoBoxTargetDp(78, 226, ratio))
        // 同一张照片：余量再怎么变，框高一个 dp 都不动（这就是"下拉不再出空白"的全部机制）
        for (space in listOf(0, 38, 78, 120, 240, 1000)) {
            assertEquals(
                "余量 ${space}dp 不该改变框高：框听照片的，不听空间的",
                72, WidgetData.photoBoxTargetDp(space, 226, ratio)
            )
        }
        // 又高又窄的组件（内容宽 126dp）：3.14:1 算出来 40dp —— 老算法那条"别把框竖过来"的硬上限
        // 现在**自动成立**（窄组件里框只会更扁），不需要再单独写一条 min(框宽) 的夹取
        assertTrue("框的宽高比不能小于 1:1", WidgetData.photoBoxTargetDp(1000, 126, ratio) <= 126)
    }

    @Test
    fun photoBoxHeightIsThePhotoNeedsAndIsClampedBetweenTheFloorAndTheCeiling() {
        // 比例矩阵 × 内容宽度矩阵：高度 = round(宽 ÷ 比例)，再夹到 56..150dp。
        // 上下限各自的取舍写在 WidgetData.photoWantedHeightDp 的注释里（一条是"太小就不是图了"，
        // 一条是"再高就喧宾夺主 + 白送像素过 binder"）。
        val cases = listOf(
            Triple(226, 3.14f, 72),   // 4 列宽、用户按 4×2 裁好的那张 → 72
            Triple(226, 16f / 9f, 127),
            Triple(226, 2f, 113),
            Triple(226, 1f, 150),     // 方图：226dp 高 → 撞上限
            Triple(226, 0.67f, 150),  // 竖图（相册里的竖屏截图）：同样撞上限
            Triple(126, 3.14f, 56),   // 2 列宽的格子：40dp 连"一条图"都算不上 → 抬到下限
            Triple(126, 16f / 9f, 71),
            Triple(1000, 3.14f, 150), // 超宽：比例算出来 318dp → 压到上限
            Triple(100, 10f, 56),     // 极扁的照片 → 抬到下限
            Triple(40, 16f / 9f, 56)  // 极窄的格子 → 抬到下限
        )
        for ((width, aspect, expected) in cases) {
            assertEquals(
                "内容宽 ${width}dp ÷ 比例 $aspect",
                expected, WidgetData.photoWantedHeightDp(width, aspect)
            )
        }
        // 夹取是**恒定**成立的，不依赖上面那张表（防止以后改表时把边界一起改掉）
        for (width in listOf(40, 100, 126, 226, 400, 1000)) {
            for (aspect in listOf(0.4f, 0.67f, 1f, 1.5f, 16f / 9f, 2f, 3.14f, 6f)) {
                val h = WidgetData.photoWantedHeightDp(width, aspect)
                assertTrue("${width}dp × $aspect 算出了 $h，超出 56..150", h in 56..150)
            }
        }
    }

    @Test
    fun photoBoxHeightFallsBackToZeroWhenThereIsNoPhotoOrNoRatio() {
        // 取不到照片比例（一张都没有 / 文件读不出来 / 宽度取不到）→ 0 = "没有图片框"。
        // 0 是让调用方按"没有图片"处理（不预留、不显示），而不是退回某个固定比例 ——
        // 老代码退回 16:9，那正是"框的形状与用户裁好的照片不一致"的另一个入口。
        assertEquals(0, WidgetData.photoWantedHeightDp(226, null))
        assertEquals(0, WidgetData.photoWantedHeightDp(226, Float.NaN))
        assertEquals(0, WidgetData.photoWantedHeightDp(226, 0f))
        assertEquals(0, WidgetData.photoWantedHeightDp(226, -3.14f))
        assertEquals(0, WidgetData.photoWantedHeightDp(0, 3.14f))
        assertEquals(0, WidgetData.photoWantedHeightDp(-10, 3.14f))
        // 顺着这条路：框高 0 → 预留 0 → 行数与"没开图片"逐值相同（见下面那条测试）
        assertEquals(0, WidgetData.photoBoxTargetDp(78, 226, null))
    }

    @Test
    fun rowsAreCountedAfterTheBoxHeightIsFixedAndAlwaysWhole() {
        // 用户要求的口径：**先定框高，再用剩下的高度排整行**。
        // 内容宽 226dp、用户那张 3.14:1 的照片 → 框高 72dp、预留 78dp（含 6dp 上边距）、行高 38dp。
        val aspect = 3.14f
        assertEquals(72, WidgetData.photoWantedHeightDp(226, aspect))

        // 4×2（实测 187dp）：(187 − 49 − 78) / 38 = 1.57 → 1 行
        assertEquals(1, WidgetData.photoRowsFor(187, 226, 0, aspect))
        // 4×3（实测 250dp）：(250 − 49 − 78) / 38 = 3.23 → 3 行（下拉多显示课）
        assertEquals(3, WidgetData.photoRowsFor(250, 226, 0, aspect))
        // 最矮的格子（150dp）：(150 − 49 − 78) < 0 → 夹到 1 整行
        assertEquals(1, WidgetData.photoRowsFor(150, 226, 0, aspect))

        // 硬约束：90..400dp 全区间都必须落在 1..AUTO_MAX_ROWS，且列表高度 = 行数 × 38dp（整行）
        var last = 0
        for (h in 90..400) {
            val rows = WidgetData.photoRowsFor(h, 226, 0, aspect)
            assertTrue("${h}dp 算出了非法的行数 $rows", rows in 1..WidgetData.AUTO_MAX_ROWS)
            val listHeightDp = rows * WidgetData.ROW_SLOT_DP
            assertEquals(
                "${h}dp 的列表高度必须是行高的整数倍（否则底部会露半行）",
                0f, listHeightDp % WidgetData.ROW_SLOT_DP, 0.001f
            )
            assertTrue("行数不该随高度倒退：$h dp", rows >= last)
            last = rows
        }
        assertEquals("到 400dp 时应当已经封顶", WidgetData.AUTO_MAX_ROWS, last)
    }

    @Test
    fun afterTheWholeRowsAreFixedTheRestGoesIntoTheBoxSoTheBottomIsNeverBlank() {
        // 本轮的核心不变量（用户要求"任何高度下底部都没有空白"）：
        //  1. 行数**永远是整数**，且落在 1..AUTO_MAX_ROWS（硬约束，见上一条）；
        //  2. 排完整行之后剩下的那条零头（真机实测 24dp）**全部归图片框** → 底部零头 = 0。
        //
        // 这条零头以前是留在组件底部的（用户："下拉组件的时候依然有空白"），现在它变成了
        // 图片框的一部分（多出来的部分由 PhotoFit 居中取一块填满消化）。下面把 90..400dp 全扫一遍。
        val aspect = 3.14f
        val wanted = WidgetData.photoWantedHeightDp(226, aspect)       // 72
        val reserve = wanted + WidgetData.AREA_GAP_DP                  // 78
        var visible = 0
        for (h in 90..400) {
            // chrome 现在恒为 49dp（页脚那一行删掉了，见 WidgetData.chromeDp）
            val chrome = WidgetData.chromeDpFor(h, 0)
            val rows = WidgetData.photoRowsFor(h, 226, 0, aspect)
            assertEquals(
                "${h}dp 的列表高度必须是行高的整数倍（否则底部会露半行）",
                0f, (rows * WidgetData.ROW_SLOT_DP) % WidgetData.ROW_SLOT_DP, 0.001f
            )
            val space = (h - chrome - rows * WidgetData.ROW_SLOT_DP).toInt()
            val p = ExtrasPlanner.plan(
                override = 0,
                photoEnabled = true,
                photoCount = 1,
                availableDp = space,
                wantedPhotoDp = wanted
            )
            if (!p.photoVisible) {
                // 极小格子：余量扣掉 6dp 上边距之后连硬底线都不够 → 不显示（宁可不显示也不要彩条）
                assertTrue("${h}dp 的空间 ${space}dp 不该显不出图片", space - 6 < WidgetData.PHOTO_HARD_MIN_DP)
                continue
            }
            visible++
            assertEquals("${h}dp：底部零头必须是 0", 0, ExtrasPlanner.leftoverBelowBoxDp(p))
            // 框高 = 余量 − 6dp 上边距；而且**恒 ≥ 照片想要的高度**（行数是按它预留的）
            assertEquals("${h}dp 的框高", space - WidgetData.AREA_GAP_DP, p.photoHeightDp)
            assertTrue(
                "${h}dp：框高 ${p.photoHeightDp} 不该小于照片想要的 $wanted（空间够的档位）",
                p.photoHeightDp >= wanted || space < reserve
            )
            // 整条链路加起来不能超出格子：头部 + 列表 + 上边距 + 框 ≤ 高度
            assertTrue(
                "${h}dp：${chrome} + ${rows * 38f} + 6 + ${p.photoHeightDp} 超了",
                chrome + rows * WidgetData.ROW_SLOT_DP + WidgetData.AREA_GAP_DP + p.photoHeightDp <= h
            )
        }
        // 311 个高度里，图片可见的是 276 个；剩下 35 个（90..124dp 那些极小格子）连 32dp 的硬底线
        // 都占不下 → 不显示图片（宁可不显示，也不要一条彩条）。数字变了说明覆盖范围变了。
        assertEquals("90..400dp 里能显示图片的高度数", 276, visible)
    }

    // ------------------------------------------------- 没有照片时与老行为逐值一致

    @Test
    fun withoutThePhotoNothingChangesForExistingUsers() {
        // 预留 = 0 时算法必须与老版本逐字相同（不用图片的人不该被这次改动碰到）。
        // 注意 [WidgetData.rowsFor] 本身一个字都没改，本轮改的只是 reserveDp 的来源。
        assertEquals(WidgetData.rowsFor(150, 0), WidgetData.rowsFor(150, 0, WidgetData.ROW_SLOT_DP, 0))
        assertEquals(2, WidgetData.rowsFor(150, 0, WidgetData.ROW_SLOT_DP, 0))
        assertEquals(4, WidgetData.rowsFor(250, 0, WidgetData.ROW_SLOT_DP, 0))

        // 比例取不到 → photoRowsFor 与"没有预留"逐值相同（90..400dp 全扫一遍）
        for (h in 90..400) {
            for (manual in listOf(0, 1, 3, 6)) {
                assertEquals(
                    "${h}dp（手动 $manual 行）在比例缺失时必须与老行为逐值一致",
                    WidgetData.rowsFor(h, manual),
                    WidgetData.photoRowsFor(h, 226, manual, null)
                )
            }
            assertEquals(
                "${h}dp：宽度取不到时同样退化成老行为",
                WidgetData.rowsFor(h, 0),
                WidgetData.photoRowsFor(h, 0, 0, 3.14f)
            )
        }
    }

    /**
     * 本轮 extras 计划的语义变化（删掉 flipper 之后的核心差别）：
     *
     * 老做法是"行数先按整个高度算完，剩下的零头再看够不够放图" —— 于是零头经常只有 3dp、
     * 20dp，用户开了图片却什么都看不到。现在行数是**先扣掉图片预留**算出来的，
     * 所以传给 planner 的余量里天然含着一块图片高度：只要行数算得出来，图片就一定显示。
     */
    @Test
    fun withThePhotoOnTheBudgetAlwaysCarriesTheReserve() {
        // 内容宽 126dp、用户那张 16:9 的照片 → 框高 71dp、预留 77dp
        val aspect = 16f / 9f
        val box = WidgetData.photoWantedHeightDp(126, aspect)
        assertEquals(71, box)
        val reserve = box + WidgetData.AREA_GAP_DP
        assertEquals(77, reserve)

        // 150dp 的小格子：行数 1，列表下方余量 = 150 - 49（头部，页脚已删）- 38（一行）= 63dp
        val rows = WidgetData.rowsFor(150, 0, WidgetData.ROW_SLOT_DP, reserve)
        assertEquals(1, rows)
        val chrome = WidgetData.chromeDpFor(150, 0)
        val avail = (150 - chrome - rows * WidgetData.ROW_SLOT_DP).toInt()
        assertEquals(63, avail)

        val plan = ExtrasPlanner.plan(
            override = 0,
            photoEnabled = true,
            photoCount = 3,
            availableDp = avail,
            wantedPhotoDp = WidgetData.photoBoxTargetDp(avail, 126, aspect)
        )
        assertTrue("150dp 的格子里，只要行数算得出来图片就必须显示（这是本轮要根治的失败模式）", plan.photoVisible)
        // 余量 63dp、照片要 71dp → 框 = 63 − 6 = 57dp（比照片想要的矮一点 → 居中取一块），
        // 渲染时在这张裁剪图里居中取一块。这一档的行为**与本轮改动之前完全一致**。
        assertEquals("框高 = 余量 − 6dp 上边距", 57, plan.photoHeightDp)
        // 而且整条链路加起来不能超出表格子：头部 49 + 列表 38 + 上边距 6 + 框 57 ≤ 150
        assertTrue(chrome + rows * WidgetData.ROW_SLOT_DP + WidgetData.AREA_GAP_DP + plan.photoHeightDp <= 150f)
    }

    @Test
    fun theBoxEatsTheLeftoverInsteadOfLeavingItAtTheBottom() {
        // 本轮的核心回归：**下拉组件 → 多出来的高度先变成整行课，最后那点零头归图片框**，
        // 于是组件底部一条空白都不留。
        // 老行为是"零头留在底部"（真机实测 24dp）—— 用户原话："组件下拉的时候依然有空白"。
        val aspect = 3.14f
        val wanted = WidgetData.photoWantedHeightDp(226, aspect)   // 72dp

        fun rowsAt(h: Int) = WidgetData.photoRowsFor(h, 226, 0, aspect)
        fun spaceAt(h: Int): Int {
            // chrome 恒为 49dp（页脚那一行删掉了）
            val chrome = WidgetData.chromeDpFor(h, 0)
            return (h - chrome - rowsAt(h) * WidgetData.ROW_SLOT_DP).toInt()
        }
        fun planAt(h: Int): ExtrasPlan =
            ExtrasPlanner.plan(0, true, 3, spaceAt(h), wanted)

        // 187dp（真机 4×2 那一档）：1 行课 + 剩下的 100dp 全给框 → 框 94dp
        // 照片本身只想要 72dp，页脚删掉后白赚回来的 22dp 也一并落在框上（72 + 22 = 94）：
        // 多出来的高度**给框而不是留在底部**，框比照片自己的比例高一点时由 [PhotoFit]
        // 居中取一块填满 —— 底部一个像素都不留。
        assertEquals(1, rowsAt(187))
        assertEquals(94, planAt(187).photoHeightDp)
        assertEquals(wanted + 22, planAt(187).photoHeightDp)
        // 250dp：多出来的高度先变成**整行课**（一口气排到 3 行），剩下的零头才归框 → 81dp
        assertEquals(3, rowsAt(250))
        assertEquals(81, planAt(250).photoHeightDp)
        // 再往上拉到 288dp：刚好攒够第 4 行课 —— 多出来的高度全变成了课，所以这一档的框**仍是 81dp**
        assertEquals(4, rowsAt(288))
        assertEquals(81, planAt(288).photoHeightDp)
        // 4 行已经排满（AUTO_MAX_ROWS）之后没有行可加了：再拉高，多出来的高度全部归框 → 119dp
        assertEquals(WidgetData.AUTO_MAX_ROWS, rowsAt(326))
        assertEquals(119, planAt(326).photoHeightDp)
        // 继续拉高同理 → 193dp。
        // （这一档是"框变高、照片居中取一块"的最极端情况，但至少那块空间里是用户的照片，
        //   而不是一条底色 —— 用户："比留一条空白好看，也比拉伸好"）
        assertEquals(WidgetData.AUTO_MAX_ROWS, rowsAt(400))
        assertEquals(193, planAt(400).photoHeightDp)

        // 上面每一档的底部零头都必须正好是 0
        for (h in listOf(187, 250, 288, 326, 400)) {
            assertEquals("${h}dp 的底部零头", 0, ExtrasPlanner.leftoverBelowBoxDp(planAt(h)))
        }

        // 最矮的格子（90dp）保持现状：连"框 + 一整行课"都放不下 → 不显示图片（不是显示一条彩条）
        assertTrue(
            "90dp 放不下框 + 一整行课",
            spaceAt(90) - WidgetData.AREA_GAP_DP < WidgetData.PHOTO_HARD_MIN_DP
        )
        assertEquals(0, planAt(90).photoHeightDp)
    }

    // ------------------------------------------------- 裁剪框：组件是几乘几

    /**
     * 用户原话：「注意组件是几乘几」。
     *
     * 裁剪框的**高**只由"照片的比例 × 内容宽度"决定（[WidgetData.photoFrameHeightDp]）：
     * **它没有高度参数**，换组件高度对它没有任何影响。理由：同一个组件里混进两种形状的照片
     * 是最难查的一类问题（用户在 4×3 上导入的图和 4×2 时导入的图不同形，点一下换一张就变形状）。
     *
     * 注意它**不等于**组件里那个框的高（本轮改过）：图片框现在吃掉列表下方的全部剩余空间，
     * 只有"导入时那个尺寸"上两者才相等；用户后来改过尺寸时由 [PhotoFit.layout] 居中取一块填满。
     */
    @Test
    fun theCropFrameHeightIsTheBoxHeightWhateverTheWidgetHeight() {
        val aspect = 3.14f        // 用户那张照片（4×2 的 226:72 裁出来的）
        val height = WidgetData.photoFrameHeightDp(226, aspect)
        assertEquals(72, height)
        // 关键：**它没有高度参数** —— 换组件高度对它没有任何影响（4×2 / 4×3 / 最矮的 90dp 都一样）
        for (h in listOf(90, 150, 187, 250, 400, 1000)) {
            // 组件高度只影响排几行课（见下一条），裁剪框高逐值不变
            WidgetData.photoRowsFor(h, 226, 0, aspect)      // 不炸即可，行数在别处断言
            assertEquals("组件 ${h}dp 时裁剪框高不该变", 72, height)
        }
        // [WidgetData.photoBoxTargetDp] 给的是**同一个数**（照片想要的高度 / 排行数时的预留来源），
        // 它同样不听余量参数（第一个参数是留着的线索，不参与计算）
        assertEquals(72, WidgetData.photoBoxTargetDp(78, 226, aspect))
        // 每日一句也开着 / 手动指定行数：都只影响"排几行课"，不影响这个数（老算法会把图片压到 50dp）
        assertEquals(72, WidgetData.photoBoxTargetDp(0, 226, aspect))
        // 换一张 16:9 的照片：数目跟着照片走（这才叫"注意组件是几乘几"的正确解法 ——
        // 用户看到的那条框，形状由照片自己的比例决定）
        assertEquals(127, WidgetData.photoFrameHeightDp(226, 16f / 9f))
    }

    @Test
    fun theCropFrameFallsBackWhenThereIsNoRatioAtAll() {
        // 比例取不到（照片被删光 / 文件读不出来 / 内容宽取不到）→ 框高 0 → [PhotoCrop.frame] 退兜底 2:1。
        // 这条兜底只影响**裁剪界面里画出来的那个框**，不参与任何排版数字（渲染时按裁剪图自己的
        // 比例重新判定放得下/放不下，见 PhotoFit.layout），所以猜得不准也不会显示错乱。
        assertEquals(0, WidgetData.photoFrameHeightDp(226, null))
        assertEquals(0, WidgetData.photoFrameHeightDp(0, 3.14f))
        val fallback = PhotoCrop.frame(226, WidgetData.photoFrameHeightDp(226, null))
        assertTrue("框高取不到时必须退兜底", fallback.fallback)
        assertEquals(2f, fallback.ratio, 0.0001f)
        // 内容宽取不到（启动器不上报）时同样退兜底
        assertTrue(PhotoCrop.frame(0, 72).fallback)
        // "强制隐藏"档位（-1）也一样：图片区根本不该显示 → 没有框高 → 兜底；
        // WidgetData.photoReserveDp 会把预留也压成 0，所以课程行数不受影响
        assertTrue(PhotoCrop.frame(226, 0).fallback)
    }

    @Test
    fun aPhotoCroppedWithThisFrameIsShownWholeAndFillsTheBox() {
        // 整条链路（纯函数版）：内容宽 226dp + 用户那张 3.14:1 的照片 → 裁剪框 226:72dp →
        // 用户按这个框裁一张 → 存盘 720×229 → 组件要解码 594×189 → 渲染判定"放得下"
        // → **整张、铺满、零留边**。这就是这次改动的全部目的：用户裁的那一块，在组件里一字不差地出现。
        val aspect = 3.14f
        val frameHeight = WidgetData.photoFrameHeightDp(226, aspect)
        val frame = PhotoCrop.frame(226, frameHeight)
        assertEquals("226:72dp(3.14:1)", frame.label)

        val target = PhotoBitmap.targetPx(226, frameHeight, 2.625f)
        assertEquals("组件要解码的宽", 594, target[0])
        assertEquals("组件要解码的高", 189, target[1])

        val saved = PhotoCrop.encodeSize(frame, target[0])
        assertEquals("存盘尺寸（按裁剪框比例，长边不超过导入链路的 1600）", "720x229", "${saved[0]}x${saved[1]}")

        val layout = PhotoFit.layout(saved[0], saved[1], target[0], target[1])!!
        assertTrue("必须是完整显示，不能再裁", layout.whole)
        assertEquals("产出逐值等于框宽", 594, layout.outWidth)
        assertEquals("产出逐值等于框高", 189, layout.outHeight)
        assertEquals("零留边", 0, layout.padY)
        assertEquals("整张都在", "720x229@(0,0)", layout.window.toString())

        // **换一张不同比例的照片也一样**：比例取自照片 → 数目随照片变 → 用户按这条框裁出来的图
        // 落到组件里仍然是"整张都在、铺满、零留边"。
        //
        // 这里**必须**走生产同源的那几个函数（照片比例 → 裁剪框高 → 解码目标 → 存盘尺寸），
        // 不能自己写一串理想值去凑，两个坑都踩过一次：
        //  1. 裁剪框高是**整数 dp**。16:9 的严格值是 127.125 → 取整成 127，而 226/127 = 1.7795
        //     比 16:9 **宽**，于是"窗口 == 整张源图"这条判据会差一点点。所以这条用 99dp
        //     （226/99 与 16:9 的差只有 0.3%）来说明"换比例也照样零留边"这件事；
        //  2. `PhotoBitmap.targetPx` 在像素总数超预算时会**等比缩**一次（600KB ÷ 2 字节）。
        //     这一档 594×260 远在预算内，所以没有缩 —— 但断言仍然按它给出的数走，
        //     免得哪天预算变了这条用例变成假绿。
        // 想表达的结论只有一条：**整张都在**（不裁、不变形、产出逐值等于框）。
        val box99 = 99
        val frame99 = PhotoCrop.frame(226, box99)
        val target99 = PhotoBitmap.targetPx(226, box99, 2.625f)
        val saved99 = PhotoCrop.encodeSize(frame99, target99[0])
        val layout99 = PhotoFit.layout(saved99[0], saved99[1], target99[0], target99[1])!!
        assertTrue(
            "换一张不同比例的照片，按这条框裁出来的图同样必须整张都在：框=${frame99.label} " +
                "存盘=${saved99.toList()} 目标=${target99.toList()} 结论=${layout99.verdict}",
            layout99.whole
        )
        assertEquals("产出宽必须逐值等于框宽（宿主端零缩放）", target99[0], layout99.outWidth)
        assertEquals("产出高必须逐值等于框高（填满、零留边）", target99[1], layout99.outHeight)
        assertEquals(0, layout99.padY)
        // 换比例之后框高确实跟着照片走（不是钉死的某个数）
        assertTrue(
            "框高必须随照片比例变化：${box99}dp（16:9）vs ${frameHeight}dp（3.14:1）",
            box99 != frameHeight
        )
    }

    @Test
    fun resizingTheWidgetLaterOnlyEverCropsCentredAndNeverLeavesBlanks() {
        // 用户裁完以后又拖了组件尺寸：这时"框"与"裁剪图"的比例不再相等，
        // 两种结果都必须是可预期的：**更高 → 居中取一块（左右各裁一条）；更扁 → 居中取一块（上下各裁一条）**。
        // 两者都不会留白、不会拉伸 —— 这是本轮的核心取舍（用户："比留一条空白好看，也比拉伸好"）。
        val frame = PhotoCrop.frame(226, 72)
        val saved = PhotoCrop.encodeSize(frame, 594)          // 720×229（3.14:1）

        // 组件被拉高（图片区 100dp → 框 594×263px，2.26:1）：保整高、左右各裁一条
        val taller = PhotoFit.layout(saved[0], saved[1], 594, 263)!!
        assertFalse("框比图高 → 取中间块（不再留边）", taller.whole)
        assertEquals("产出逐值等于框宽", 594, taller.outWidth)
        assertEquals("产出逐值等于框高（填满）", 263, taller.outHeight)
        assertEquals("一条留白都没有", 0, taller.padY)
        // 窗口 = 整高 229、宽 round(229 × 594/263) = 517，左右各裁 (720 − 517) ÷ 2 = 102
        assertEquals(517, taller.window.width)
        assertEquals(229, taller.window.height)
        assertEquals(102, taller.window.x)
        assertEquals(0, taller.window.y)

        // 组件被压扁（图片区 40dp → 框 594×105px，5.66:1）：保整宽、上下各裁一条
        val flatter = PhotoFit.layout(saved[0], saved[1], 594, 105)!!
        assertFalse("框比图扁 → 取中间块", flatter.whole)
        assertEquals(594, flatter.outWidth)
        assertEquals(105, flatter.outHeight)
        assertEquals(0, flatter.padY)
        // 窗口仍是整宽、比例与框一致、居中
        assertEquals(720, flatter.window.width)
        assertEquals(127, flatter.window.height)              // round(720 ÷ 5.657)
        assertEquals(51, flatter.window.y)                    // round((229 − 127) ÷ 2)
    }

    /**
     * **用户指定要核对的那张表**：组件 150 / 187 / 250 / 318dp 时的全部数字。
     *
     * 全部由生产同源的函数算出来（没有一处是手抄的理想值），density 取真机实测的 2.625。
     * 用户会拿这套数字去对着桌面截图数像素，所以这里逐值断言，并把整张表打到 stdout
     * （`build/test-results/testDebugUnitTest` 目录里每个测试类一个 XML，
     * `system-out` 节点里能直接读到）。
     *
     * 前提：照片是用户按 4×2 那条框（226:72）裁出来的那张 → 宽高比 ≈ 3.14:1。
     *
     * **每日一句不再占任何空间**（用户要求：拼在「第 N 周 · 接下来 X 节」那一行），
     * 页脚那一行也删了 —— 所以这里的格数就是纯课程行数，chrome 恒 49dp。
     */
    @Test
    fun theNumbersForEveryReferenceHeight() {
        val density = 2.625f
        val ratio = 720f / 229f
        val contentDp = 226
        val wanted = WidgetData.photoWantedHeightDp(contentDp, ratio)
        assertEquals("照片需要的框高（226dp ÷ 3.14）", 72, wanted)

        val heights = listOf(150, 187, 250, 318)
        val photoReserve = wanted + WidgetData.AREA_GAP_DP   // 78dp
        // 与真机链路同源：行数 → 余量 → 计划 → 解码目标 → 渲染结论（一个数字都不手抄）
        fun rowsAt(heightDp: Int) =
            WidgetData.rowsFor(heightDp, 0, WidgetData.ROW_SLOT_DP, photoReserve)
        fun spaceAt(heightDp: Int): Int =
            (heightDp - WidgetData.chromeDpFor(heightDp, 0) -
                rowsAt(heightDp) * WidgetData.ROW_SLOT_DP).toInt()
        fun planAt(heightDp: Int): ExtrasPlan = ExtrasPlanner.plan(
            0, true, 1, spaceAt(heightDp), WidgetData.photoBoxTargetDp(spaceAt(heightDp), contentDp, ratio)
        )
        fun targetAt(heightDp: Int) =
            PhotoBitmap.targetPx(contentDp, planAt(heightDp).photoHeightDp, density)
        fun savedOf(heightDp: Int): IntArray =
            PhotoCrop.encodeSize(PhotoCrop.frame(contentDp, wanted), targetAt(heightDp)[0])
        fun layoutAt(heightDp: Int): PhotoFit.Layout {
            val target = targetAt(heightDp)
            val saved = savedOf(heightDp)
            return PhotoFit.layout(saved[0], saved[1], target[0], target[1])!!
        }

        /** 用户要看的那一行（真机日志里的列与它一一对应） */
        fun report(heightDp: Int): String {
            val rows = rowsAt(heightDp)
            val space = spaceAt(heightDp)
            val plan = planAt(heightDp)
            val leftover = ExtrasPlanner.leftoverBelowBoxDp(plan)
            val target = targetAt(heightDp)
            val saved = savedOf(heightDp)
            val layout = layoutAt(heightDp)
            // 每一档都必须成立的不变量（= 日志里那两列要核对的结论）
            assertEquals("${heightDp}dp 的底部零头必须是 0", 0, leftover)
            assertEquals("${heightDp}dp 的照片区里不许有留边", 0, layout.padY)
            assertEquals("${heightDp}dp 的产出必须逐值等于框宽", target[0], layout.outWidth)
            assertEquals("${heightDp}dp 的产出必须逐值等于框高", target[1], layout.outHeight)
            // 框高 ≥ 照片想要的高度 —— 但只保证在"空间至少够预留"的档位：
            // 更矮的格子（150dp 是最矮那一档）本来就装不下"框 + 一整行课"，
            // 这时框被压扁、照片居中取一块，属于允许的兜底（见 ExtrasPlanner 的类注释）
            assertTrue(
                "${heightDp}dp 的框高 ${plan.photoHeightDp} 不该小于照片想要的 $wanted",
                plan.photoHeightDp >= wanted || space < wanted + WidgetData.AREA_GAP_DP
            )
            return "组件=${heightDp}dp 课程=${rows}行 余量=${space}dp 框=${contentDp}x${plan.photoHeightDp}dp " +
                "框比例=${PhotoBitmap.ratioLabel(contentDp, plan.photoHeightDp)} " +
                "照片比例=${PhotoBitmap.ratioLabel(saved[0], saved[1])} " +
                "结论=${layout.verdict} 取块=${layout.window} 留边=${layout.padY}px " +
                "底部零头=${leftover}dp 存盘=${saved.toList()} target=${target.toList()} " +
                "bitmap=${layout.outWidth}x${layout.outHeight} " +
                "byteCount=${layout.outWidth * layout.outHeight * PhotoBitmap.BYTES_PER_PIXEL}B"
        }

        // 先把表打出来（`build/test-results/testDebugUnitTest/*.xml` 的 system-out 里能读到，
        // 用户就是拿它去对着桌面截图数像素的），再做逐档断言
        println("=== 图片区与底部空白核对表（内容宽 ${contentDp}dp，照片 3.14:1，density $density）===")
        heights.forEach { println(report(it)) }

        // ---- 逐档钉住的数字（这几列是"改动了就要显式改"的判据）----
        // chrome 49dp（页脚删掉了）+ 图片预留 78dp：150dp 装不下"预留+整行课"→夹到 1 行；
        // 187dp 起每多 38dp 多一行课，318dp 撞自动档上限 4 行
        assertEquals(listOf(1, 1, 3, 4), heights.map { rowsAt(it) })
        // 框高 = 余量 − 6dp（余量 = 高度 − 49 − 行数×38）
        assertEquals(listOf(57, 94, 81, 111), heights.map { planAt(it).photoHeightDp })
        // 解码尺寸（框的像素尺寸，density 2.625）
        assertEquals(
            listOf(listOf(594, 150), listOf(594, 247), listOf(594, 213), listOf(594, 292)),
            heights.map { targetAt(it).toList() }
        )
        // 过 binder 的真实字节数（RGB_565，2 字节/像素；上限 600KB，见 PhotoBitmap.MAX_BITMAP_BYTES）
        assertEquals(
            listOf(178_200, 293_436, 253_044, 346_896),
            heights.map { layoutAt(it).let { l -> l.outWidth * l.outHeight * PhotoBitmap.BYTES_PER_PIXEL } }
        )
        // 结论：这几档的框都比照片"扁"（框比例 2.0~4.0:1 vs 照片 3.14:1）→ 取源图内部正中那一块，
        // 缩到框尺寸**铺满**（不留白、不拉伸、也没有第二层背景 —— 模糊底整条删掉了，
        // 用户真机上看到的就是它的块状色块）
        assertEquals(
            listOf("取中间块", "取中间块", "取中间块", "取中间块"),
            heights.map { layoutAt(it).verdict }
        )
        // 而无论哪一档，**取块都完整落在源图里**（不越界 = 不会 createBitmap 崩），
        // 且"尽可能大"（保住整高或整宽）与框同比例（不变形）
        for (h in heights) {
            val l = layoutAt(h)
            val w = l.window
            assertTrue(
                "${h}dp：取块越出源图了 window=$w 源=${savedOf(h).toList()}",
                w.x >= 0 && w.y >= 0 &&
                    w.x + w.width <= savedOf(h)[0] && w.y + w.height <= savedOf(h)[1]
            )
            assertTrue(
                "${h}dp：取块不是能取到的最大那一块 window=$w 源=${savedOf(h).toList()}",
                w.width == savedOf(h)[0] || w.height == savedOf(h)[1]
            )
            val lhs = w.width.toLong() * l.outHeight
            val rhs = l.outWidth.toLong() * w.height
            assertTrue(
                "${h}dp：取块与框不同比例（交叉相乘差 ${Math.abs(lhs - rhs)}）→ 铺满会变形",
                Math.abs(lhs - rhs) <= maxOf(l.outWidth, l.outHeight).toLong()
            )
        }
        // 而无论哪一档，底部零头都是 0（= 日志里的 `底部零头=0dp`）
        assertEquals(
            List(heights.size) { 0 },
            heights.map { ExtrasPlanner.leftoverBelowBoxDp(planAt(it)) }
        )

        // 用户要核对的那几列（文本级钉住，免得日志格式悄悄改了）
        for (h in heights) {
            val line = report(h)
            assertTrue("缺少 组件=${h}dp：$line", line.contains("组件=${h}dp"))
            assertTrue("缺少 底部零头=0dp：$line", line.contains("底部零头=0dp"))
            assertTrue("缺少 留边=0px：$line", line.contains("留边=0px"))
        }
    }

    // ------------------------------------------------- 代码常量 ↔ 布局 XML

    @Test
    fun contentInsetMatchesTheLayoutPadding() {
        val xml = readLayout("widget_today.xml")
        // 根布局的左右内边距 → 图片框的宽度就是"格子宽度 - 这一对"
        val inset = dpAttr(xml, "paddingStart") + dpAttr(xml, "paddingEnd")
        assertEquals(
            "图片框高度按「内容宽度 = 格子宽度 - 左右内边距」计算，" +
                "这里的常量必须与 widget_today.xml 的 paddingStart+paddingEnd 一致（改布局就得改常量）",
            WidgetData.CONTENT_INSET_DP, inset
        )
        // 图片框的上边距必须与布局一致（AREA_GAP_DP 是从这里来的）
        assertEquals(
            "AREA_GAP_DP 必须等于布局里 widget_photo_area 的 layout_marginTop",
            WidgetData.AREA_GAP_DP, dpAttr(tagOf(xml, "widget_photo_area"), "layout_marginTop")
        )
        // 每日一句**已经不在这张布局里**了（它拼在副标题那一行，见 WidgetSubtitleTest）：
        // 这条断言守着"别再有人把一块固定高度的句子塞回底部"——那正是本次要删掉的那 22dp
        assertFalse(
            "widget_today.xml 里不该再有独立的每日一句控件（它拼在副标题那一行）",
            xml.contains("@+id/widget_quote")
        )
    }

    @Test
    fun theForcedModeKeepsTheOldGenerosityMargin() {
        // 强制显示档用的是 `余量 + EXTRA_MIN_DP` 的宽松度（老代码就是 min(理想, 余量+22)）。
        // 那个 22dp 原本是"一句话的占用"，句子拼进副标题那一行之后，它只剩"别把救场档位收得比老代码更紧"这一个用途
        assertTrue(WidgetData.EXTRA_MIN_DP >= WidgetData.AREA_GAP_DP + 16)
        assertEquals(WidgetData.AREA_GAP_DP, gap)
    }

    private fun readLayout(name: String): String {
        val f = listOf(
            File("src/main/res/layout/$name"),
            File("app/src/main/res/layout/$name")
        ).firstOrNull { it.isFile } ?: error("找不到 $name（测试工作目录假设有变）")
        return f.readText()
    }

    /** 取出带某个 id 的那个标签（属性可以跨行写，所以用 `[^>]*` 而不是 `.`） */
    private fun tagOf(xml: String, id: String): String =
        Regex("<[^>]*@\\+id/$id\"[^>]*>").find(xml)?.value
            ?: error("widget_today.xml 里找不到 @+id/$id")

    /** 取标签里 `android:<name>="NNdp"` 的数值 */
    private fun dpAttr(tag: String, name: String): Int =
        Regex("android:$name=\"(\\d+)dp\"").find(tag)?.groupValues?.get(1)?.toInt()
            ?: error("标签里没有 android:$name=\"..dp\"：${tag.replace('\n', ' ').take(120)}")
}
