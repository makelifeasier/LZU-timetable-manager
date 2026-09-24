package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 底部扩展区的取舍规则（每日一句 / 图片）+ 图片框高度 + **预留空间与行数的关系**。
 *
 * 这一组规格全部来自真机反馈：
 *  - **开了图片之后「每日一句」就没了**：老逻辑是"图片优先"，`quoteOn` 里带了一个
 *    `&& !photoOn`，等于无条件把句子藏起来。现在两块用**同一个预算**一起决策：
 *    放得下就都显示（句子在上、图片在下），放不下才退让。
 *  - **退让顺序**：先保图片（用户主动挑的、信息量大），句子是装饰；
 *    但连图片的硬底线（32dp）都占不下时反过来只留句子（一句话只要 22dp）。
 *  - **图片框高度按内容宽度算，且只由宽度定**（16:9）：老算法按组件高度在 0.72 / 1.15 之间跳，
 *    同一个框换个高度就换个比例，`centerCrop` 于是每次都裁在别处 ——
 *    真机反馈"显示窗口和图片大小对不上"就是这么来的。
 *  - **行数先把图片预留扣掉**（`rowsFor(..., reserveDp)`）：这样"向下拉组件"多出来的高度
 *    归图片、行数在一档高度内不变，而不是又挤出一行课（用户原话第 3 条）。
 *    整条链路的数字在 [reservingThePhotoKeepsTheRowCountFixedWithinOneRowSlot] 里逐值钉死。
 *  - **框的高度就是解码尺寸**：框和图永远同比例，既不会拉伸也不会露缝。
 *
 * 只测纯函数：单测里 Android API 全是"返回默认值"的假实现
 * （`testOptions.unitTests.isReturnDefaultValues = true`），碰 Context 的代码一律不进这里。
 */
class WidgetExtrasPlanTest {

    private val quoteCost = WidgetData.EXTRA_MIN_DP      // 22
    private val minPhoto = WidgetData.PHOTO_HARD_MIN_DP  // 32
    private val gap = WidgetData.AREA_GAP_DP             // 6

    /**
     * "照片自己要的框高"的典型值：150dp = [WidgetData.PHOTO_MAX_DP]（照片比例很大时撞上限）。
     *
     * 注意这个参数**不是**"空间能给多高"（老名字叫 idealPhotoDp）—— 见
     * [WidgetData.photoWantedHeightDp] 里 4×2 变 4×3 那段账，那个语义正是本次要修的病。
     */
    private val wantedPhoto = WidgetData.PHOTO_MAX_DP

    private fun plan(
        override: Int = 0,
        quote: Boolean = true,
        photo: Boolean = true,
        count: Int = 3,
        availableDp: Int = 200,
        wantedPhotoDp: Int = wantedPhoto
    ) = ExtrasPlanner.plan(override, quote, photo, count, availableDp, wantedPhotoDp)

    // ------------------------------------------------- 两个都开：这是本轮要修的那条

    @Test
    fun bothAreShownWhenThereIsRoomForBoth() {
        // 余量充足（4×3 组件实测有 80dp 左右）：句子与图片**同时**显示
        val p = plan(availableDp = 80)
        assertTrue("有地方就得把句子显示出来（用户的抱怨就是它被图片挤没了）", p.quoteVisible)
        assertTrue(p.photoVisible)
        // 总占用不能超预算：句子(含边距) + 6dp 间距 + 图片框
        assertTrue("句子+边距+图片不能超出余量", quoteCost + gap + p.photoHeightDp <= 80)
    }

    @Test
    fun photoKeepsPriorityOnlyWhenBothDoNotFit() {
        // 余量只够一样：按"图片是用户主动挑的、句子是装饰"保图片、弃句子
        val avail = quoteCost + gap + minPhoto - 1      // 差 1dp 放不下两个
        val p = plan(availableDp = avail)
        assertTrue("放不下两个时图片优先", p.photoVisible)
        assertFalse("此时才轮到句子让位", p.quoteVisible)
        assertTrue("图片不能小于硬底线", p.photoHeightDp >= minPhoto)
        assertTrue("图片也不能超出余量", gap + p.photoHeightDp <= avail)
    }

    @Test
    fun quoteSurvivesWhenEvenTheSmallestPhotoDoesNotFit() {
        // 余量只有 40dp：图片硬底线是 32dp+6dp 边距 = 38dp —— 刚好能放图；
        // 再小一点（30dp）就该反过来只留句子（一句话 22dp 比图片便宜）
        val p = plan(availableDp = 30)
        assertFalse(p.photoVisible)
        assertTrue("一句话仍然值得显示", p.quoteVisible)
    }

    @Test
    fun nothingIsShownWhenNothingFits() {
        val p = plan(availableDp = quoteCost - 1)
        assertFalse(p.photoVisible)
        assertFalse(p.quoteVisible)
    }

    @Test
    fun quoteIsNotHiddenJustBecauseThePhotoIsOn() {
        // 回归：老代码里只要 photoOn，quoteOn 一定是 false。
        // 现在同一个输入必须给出"两个都显示"。
        val p = plan(availableDp = 200, count = 5)
        assertTrue(p.photoVisible && p.quoteVisible)
    }

    // ------------------------------------------------- override 的三种语义

    @Test
    fun forceHideHidesBoth() {
        val p = plan(override = -1, availableDp = 500)
        assertFalse(p.photoVisible)
        assertFalse(p.quoteVisible)
        assertEquals(0, p.photoHeightDp)
    }

    @Test
    fun forceShowBeatsTheAutoRuleWhenSpaceSaysNothing() {
        // 真机/模拟器实测：启动器上报的高度偏小，余量只有 20dp —— 自动规则下一块都放不下
        val auto = plan(override = 0, availableDp = 20)
        assertFalse(
            "20dp 余量本来就不该显示任何一块（不挤课程是底线）",
            auto.photoVisible || auto.quoteVisible
        )
        // 少数机型上报的高度根本是错的（荣耀把声明值当高度回显），这时靠"强制显示"救场
        val forced = plan(override = 1, availableDp = 20)
        assertTrue("强制显示必须两块都出来", forced.photoVisible && forced.quoteVisible)
        assertTrue("高度至少得能看见", forced.photoHeightDp >= minPhoto)
        // 与老代码的宽松度对齐：老是 min(理想, 余量+22)，不能比它更小
        assertTrue("不能比老代码显示得更小", forced.photoHeightDp >= 20 + quoteCost)
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
        val p = plan(override = 1, count = 0)
        assertFalse(p.photoVisible)
        assertTrue(p.quoteVisible)
    }

    // ------------------------------------------------- 开关 / 图片张数

    @Test
    fun enablingTheSwitchWithoutAnyPhotoShowsNothing() {
        // 开关开着但图被删光了（很常见）：不能显示一个空框
        assertFalse(plan(count = 0).photoVisible)
        assertFalse(plan(photo = false, count = 3).photoVisible)
    }

    @Test
    fun quoteOnlyModeUsesTheSameBudget() {
        // 只开句子：22dp 就够，但不够就得藏（宁可没有，也不要显示半截字）
        assertTrue(plan(photo = false, availableDp = quoteCost).quoteVisible)
        assertFalse(plan(photo = false, availableDp = quoteCost - 1).quoteVisible)
    }

    // ------------------------------------------------- 预算的算术边界

    @Test
    fun photoHeightNeverExceedsTheBudget() {
        // 把所有可能的余量都扫一遍：只要图片可见，就绝不能超出余量（否则会被裁掉一截）
        for (avail in 0..300) {
            val p = plan(availableDp = avail, wantedPhotoDp = 400)
            if (!p.photoVisible) continue
            val cost = gap + p.photoHeightDp + if (p.quoteVisible) quoteCost else 0
            assertTrue("余量 ${avail}dp 时总占用 ${cost}dp 超了", cost <= avail)
            assertTrue("图片高度不能低于硬底线", p.photoHeightDp >= minPhoto)
        }
    }

    @Test
    fun idealHeightWinsWhenThereIsPlentyOfRoom() {
        // 余量足够时给的是"理想高度"（按宽高比算出来的），不是"能塞多高塞多高"
        val p = plan(availableDp = 300, wantedPhotoDp = 90)
        assertEquals(90, p.photoHeightDp)
    }

    @Test
    fun negativeSpaceIsTreatedAsZero() {
        // 上报高度可能小于头部（极端窄格子）：不能出现负预算
        val p = plan(availableDp = -50)
        assertFalse(p.photoVisible)
        assertFalse(p.quoteVisible)
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

        // 4×2（实测 187dp）：(187 − 71 − 78) / 38 = 1.0 → 1 行
        assertEquals(1, WidgetData.photoRowsFor(187, 226, 0, aspect))
        // 4×3（实测 250dp）：(250 − 71 − 78) / 38 = 2.66 → 2 行（下拉多显示一节课）
        assertEquals(2, WidgetData.photoRowsFor(250, 226, 0, aspect))
        // 最矮的格子（150dp）：(150 − 71 − 78) < 0 → 夹到 1 整行
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
    fun afterTheBoxHeightIsFixedTheLeftoverNeverEatsHalfARow() {
        // 修复后的完整口径：剩下的高度要么变成一整行课，要么是**排不下一整行**的零头（≤ 37dp），
        // **不会**再变成照片内部的留白（那正是本次要治的现象）。
        // 两个例外，都是设计如此，下面逐条断言：
        //  1. 行数封顶（AUTO_MAX_ROWS = 4）之后还有余量 —— 那部分是给用户继续拉高用的留白，
        //     框并不会去把它吃掉（照片比例决定框高，这是本次改动的核心）；4 行封顶见 WidgetData；
        //  2. 格子小到"连框带一行都放不下"—— 框会被 ExtrasPlanner 压到可用高度，走"取中间块"。
        val aspect = 3.14f
        val box = WidgetData.photoWantedHeightDp(226, aspect)          // 72
        val reserve = box + WidgetData.AREA_GAP_DP                     // 78
        var cappedLeftover = 0f
        for (h in 90..400) {
            // 页脚按 compactFor 收不收（与 rowsFor 内部同源），保证算术与真机链路一致
            val chrome = if (WidgetData.compactFor(h, 0)) {
                WidgetData.CHROME_DP - WidgetData.FOOTER_DP
            } else {
                WidgetData.CHROME_DP
            }
            val rows = WidgetData.photoRowsFor(h, 226, 0, aspect)
            val leftover = h - (chrome + rows * WidgetData.ROW_SLOT_DP + reserve)
            if (leftover < 0) {
                // 只有"算出来的行数被夹到 1"的极小格子才会溢出：框会被 plan 压下来说明原因
                assertTrue(
                    "${h}dp 溢出 ${leftover}dp，但这个高度不该溢出",
                    h < chrome + reserve + WidgetData.ROW_SLOT_DP
                )
            } else if (rows < WidgetData.AUTO_MAX_ROWS) {
                assertTrue(
                    "${h}dp 剩下 ${leftover}dp —— 够一整行了却没排进去（等于白白浪费）",
                    leftover < WidgetData.ROW_SLOT_DP
                )
            } else {
                // 4 行封顶：多出来的高度确实留着（老代码里这一步是"给图片"，现在是"谁也不给"）
                cappedLeftover = leftover
            }
        }
        assertTrue("封顶之后确实还有余量（否则这条用例失去了它要守的那一档）", cappedLeftover > 0)
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

        // 150dp 的小格子：行数 1，列表下方余量 = 150 - 71（头部）- 38（一行）= 41dp
        val rows = WidgetData.rowsFor(150, 0, WidgetData.ROW_SLOT_DP, reserve)
        assertEquals(1, rows)
        val chrome = if (WidgetData.compactFor(150, 0)) WidgetData.CHROME_DP - WidgetData.FOOTER_DP else WidgetData.CHROME_DP
        val avail = (150 - chrome - rows * WidgetData.ROW_SLOT_DP).toInt()
        assertEquals(41, avail)

        val plan = ExtrasPlanner.plan(
            override = 0,
            quoteEnabled = false,
            photoEnabled = true,
            photoCount = 3,
            availableDp = avail,
            wantedPhotoDp = WidgetData.photoBoxTargetDp(avail, 126, aspect)
        )
        assertTrue("150dp 的格子里，只要行数算得出来图片就必须显示（这是本轮要根治的失败模式）", plan.photoVisible)
        // 空间不够（余量只有 41dp、而照片要 71dp）→ 框被压到 35dp（41 − 6dp 上边距），
        // 渲染时在这张裁剪图里居中取一块。这一档的行为**与本轮改动之前完全一致**。
        assertEquals("框高 = 余量 - 6dp 上边距（空间不够，压扁它）", 35, plan.photoHeightDp)
        // 而且整条链路加起来不能超出表格子：头部 71 + 列表 38 + 上边距 6 + 框 35 ≤ 150
        assertTrue(chrome + rows * WidgetData.ROW_SLOT_DP + WidgetData.AREA_GAP_DP + plan.photoHeightDp <= 150f)
    }

    @Test
    fun theBoxStaysFixedWhileTheExtraSpaceBecomesWholeRows() {
        // 本轮的核心回归：**下拉组件 → 框不动、多显示一整行课**。
        // 老行为是"框跟着余量长高"，于是同一张照片在 4×3 里被塞进一个比它高 63dp 的框 →
        // 完整显示 + 上下各留一大段空白，而课程一行都没多（用户原话："图片保持不变会流出很多空白"）。
        val aspect = 3.14f                                   // 用户按 4×2（226:72）裁出来的那张
        val box = WidgetData.photoWantedHeightDp(226, aspect)  // 72dp
        val reserve = box + WidgetData.AREA_GAP_DP             // 78dp

        fun rowsAt(h: Int) = WidgetData.rowsFor(h, 0, WidgetData.ROW_SLOT_DP, reserve)
        fun availAt(h: Int): Int {
            val chrome = if (WidgetData.compactFor(h, 0)) {
                WidgetData.CHROME_DP - WidgetData.FOOTER_DP
            } else {
                WidgetData.CHROME_DP
            }
            return (h - chrome - rowsAt(h) * WidgetData.ROW_SLOT_DP).toInt()
        }
        fun boxAt(h: Int) = ExtrasPlanner.plan(
            0, false, true, 3, availAt(h), WidgetData.photoBoxTargetDp(availAt(h), 226, aspect)
        ).photoHeightDp

        // 4×2（187dp）：预留 78 → 1 行课；剩下 78dp 全给框，框要 72dp → 给 72（多出的 1dp 留白可忽略）
        assertEquals(1, rowsAt(187))
        assertEquals(72, boxAt(187))
        // 4×3（250dp）：框**一寸都没变**，多出来的 63dp 是整行课
        assertEquals(2, rowsAt(250))
        assertEquals("框必须仍是 72dp：照片比例没变，框就不该变", 72, boxAt(250))
        // 再往上拉：到 288dp 才轮到第 3 行，而框始终是 72dp —— 不会出现"下拉只是把空白拉大"
        assertEquals(3, rowsAt(288))
        assertEquals(72, boxAt(288))
        assertEquals(4, rowsAt(326))
        assertEquals(72, boxAt(326))
        assertEquals("4 行封顶后框仍然不动", 72, boxAt(400))

        // 空间不够那一档（90dp 的最小格子）保持现状：框被压到可用高度之下 → 图片区不显示
        assertTrue("90dp 放不下框 + 一整行课", availAt(90) < WidgetData.PHOTO_HARD_MIN_DP)
        assertEquals(0, boxAt(90))
    }

    // ------------------------------------------------- 裁剪框：组件是几乘几

    /**
     * 用户原话：「注意组件是几乘几」。
     *
     * 裁剪框的**高**现在只由"照片的比例 × 内容宽度"决定（[WidgetData.photoFrameHeightDp]）。
     * 于是"用户按这个框裁出来的照片" 与 "组件里那个框" **恒等** —— 换尺寸、换行数都不会变，
     * 因为裁剪框的形状本来就不该随组件的行数变化：那正是上一版（把渲染链路抄一遍）的病，
     * 它让用户在 4×2 上裁好的照片在 4×3 里两边各留一大段空白。
     */
    @Test
    fun theCropFrameHeightIsTheBoxHeightWhateverTheWidgetHeight() {
        val aspect = 3.14f        // 用户那张照片（4×2 的 226:72 裁出来的）
        val height = WidgetData.photoFrameHeightDp(226, aspect)
        assertEquals(72, height)
        // 关键：**它没有高度参数** —— 换组件高度对它没有任何影响（4×2 / 4×3 / 最矮的 90dp 都一样）
        for (h in listOf(90, 150, 187, 250, 400, 1000)) {
            // 组件高度只影响排几行课（见下一条），框高逐值不变
            WidgetData.photoRowsFor(h, 226, 0, aspect)      // 不炸即可，行数在别处断言
            assertEquals("组件 ${h}dp 时裁剪框高不该变", 72, height)
        }
        // 与渲染链路对齐：组件里那个框用的就是这个高度
        assertEquals(72, WidgetData.photoBoxTargetDp(78, 226, aspect))
        // 每日一句也开着 / 手动指定行数：都只影响"排几行课"，不影响框高（老算法会把图片压到 50dp）
        assertEquals(72, WidgetData.photoBoxTargetDp(0, 226, aspect))
        // 换一张 16:9 的照片：框高跟着照片走（这才叫"注意组件是几乘几"的正确解法 ——
        // 用户看到的那条框，形状等于组件里图片区的形状）
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

        // **换一张不同比例的照片也一样**：比例取自照片 → 框高随照片变 → 用户按这条框裁出来的图
        // 落到组件里仍然是"完整显示、一个像素都不裁"。
        //
        // 这里**必须**走生产同源的那几个函数（框高 → 解码目标 → 存盘尺寸），不能自己写一串
        // 理想值去凑，两个坑都踩过一次：
        //  1. 框高是**整数 dp**。16:9 的严格值是 127.125 → 取整成 127，而 226/127 = 1.7795
        //     比 16:9 **宽**：一个像素都不裁的"真 16:9 图"放进去反而不满足 `fitsWhole`
        //     （整数交叉相乘），会落到"取中间块"裁掉一条。所以这条用 99dp（226/99 与 16:9 的
        //     差只有 0.3%）来说明"换比例也照样零留边"这件事；
        //  2. `PhotoBitmap.targetPx` 还会按 binder 预算（300KB ÷ 2 字节）**等比缩**一次 ——
        //     box 高 99dp 时原始目标是 594×260，缩成 592×259。缩的是"解码多少像素"，
        //     不是"图放不放得下"，所以结论不变（下面断言）。
        // 想表达的结论只有一条：**放得下**（不裁、不变形、产出宽逐值等于框宽）。
        val box99 = 99
        val frame99 = PhotoCrop.frame(226, box99)
        val target99 = PhotoBitmap.targetPx(226, box99, 2.625f)
        val saved99 = PhotoCrop.encodeSize(frame99, target99[0])
        val layout99 = PhotoFit.layout(saved99[0], saved99[1], target99[0], target99[1])!!
        assertTrue(
            "换一张不同比例的照片，按这条框裁出来的图同样必须完整显示：框=${frame99.label} " +
                "存盘=${saved99.toList()} 目标=${target99.toList()} 结论=${layout99.verdict}",
            layout99.whole
        )
        assertEquals("产出宽必须逐值等于框宽（宿主端零缩放）", target99[0], layout99.outWidth)
        assertEquals("产出高按图自身比例、不超过框高", target99[1], layout99.outHeight)
        // 换比例之后框高确实跟着照片走（不是钉死的某个数）
        assertTrue(
            "框高必须随照片比例变化：${box99}dp（16:9）vs ${frameHeight}dp（3.14:1）",
            box99 != frameHeight
        )
    }

    @Test
    fun resizingTheWidgetLaterOnlyEverShowsMoreOrCropsCentred() {
        // 用户裁完以后又拖了组件尺寸：这时"框"与"裁剪图"的比例不再相等，
        // 两种结果都必须是可预期的：更高 → 完整显示（留边）；更扁 → 居中取一块。
        val frame = PhotoCrop.frame(226, 72)
        val saved = PhotoCrop.encodeSize(frame, 594)          // 720×229（3.14:1）

        // 组件被拉高（图片区 100dp → 框 594×263px，2.26:1）：放得下 → 整张 + 上下留边
        val taller = PhotoFit.layout(saved[0], saved[1], 594, 263)!!
        assertTrue("框比图高 → 完整显示", taller.whole)
        assertEquals("宽仍逐值等于框宽", 594, taller.outWidth)
        assertEquals(189, taller.outHeight)                   // 按图自身比例
        assertEquals(74, taller.padY)

        // 组件被压扁（图片区 40dp → 框 594×105px，5.66:1）：放不下 → 居中取一块填满
        val flatter = PhotoFit.layout(saved[0], saved[1], 594, 105)!!
        assertFalse("框比图扁 → 取中间块", flatter.whole)
        assertEquals(594, flatter.outWidth)
        assertEquals(105, flatter.outHeight)
        // 窗口仍是整宽、比例与框一致、居中
        assertEquals(720, flatter.window.width)
        assertEquals(127, flatter.window.height)              // round(720 ÷ 5.657)
        assertEquals(51, flatter.window.y)                    // round((229 − 127) ÷ 2)
    }

    /**
     * 用户要的可核对数字：**4×2 / 4×3 两种组件高度**下，框高、照片需要的高、留边、行数。
     *
     * 全部由生产同源的函数算出来（没有一处是手抄的理想值），density 取真机实测的 2.625。
     * 用户会拿这套数字去对着桌面截图数像素，所以这里逐值钉住。
     *
     * 前提：照片是用户按 4×2 那条框（226:72）裁出来的那张 → 宽高比 ≈ 3.14:1。
     */
    @Test
    fun theNumbersForFourByTwoAndFourByThreeWidgets() {
        val density = 2.625f
        val ratio = 720f / 229f
        val contentDp = 226
        val wanted = WidgetData.photoWantedHeightDp(contentDp, ratio)
        assertEquals("照片需要的框高（226dp ÷ 3.14）", 72, wanted)

        fun report(heightDp: Int): String {
            val rows = WidgetData.photoRowsFor(heightDp, contentDp, 0, ratio)
            val chrome = if (WidgetData.compactFor(heightDp, 0)) {
                WidgetData.CHROME_DP - WidgetData.FOOTER_DP
            } else {
                WidgetData.CHROME_DP
            }
            val space = (heightDp - chrome - rows * WidgetData.ROW_SLOT_DP).toInt()
            val plan = ExtrasPlanner.plan(
                0, false, true, 1, space, WidgetData.photoBoxTargetDp(space, contentDp, ratio)
            )
            val target = PhotoBitmap.targetPx(contentDp, plan.photoHeightDp, density)
            val frame = PhotoCrop.frame(contentDp, wanted)
            val saved = PhotoCrop.encodeSize(frame, target[0])
            val layout = PhotoFit.layout(saved[0], saved[1], target[0], target[1])!!
            assertTrue("框高必须等于照片需要的高（空间够）", plan.photoHeightDp == wanted)
            assertEquals("必须完整显示、零留边", 0, layout.padY)
            return "组件=${heightDp}dp 行数=$rows 余量=${space}dp 框=${contentDp}x${plan.photoHeightDp}dp " +
                "框高=${plan.photoHeightDp} 需要的框高=$wanted 留边=${layout.padY}px target=${target.toList()} " +
                "存盘=${saved.toList()}"
        }

        // 4 列 × 2 行（实测 187dp）：下拉前 —— 1 行课 + 正好装下照片的框
        val fourByTwo = report(187)
        assertEquals(
            "组件=187dp 行数=1 余量=78dp 框=226x72dp 框高=72 需要的框高=72 留边=0px " +
                "target=[594, 189] 存盘=[720, 229]",
            fourByTwo
        )
        // 4 列 × 3 行（实测 250dp）：下拉后 —— 多出一整行课，框**一个 dp 都没变**、仍然零留边
        val fourByThree = report(250)
        assertEquals(
            "组件=250dp 行数=2 余量=103dp 框=226x72dp 框高=72 需要的框高=72 留边=0px " +
                "target=[594, 189] 存盘=[720, 229]",
            fourByThree
        )
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
        // 预算里给图片/句子各留了 AREA_GAP_DP 的上边距，必须与布局一致
        assertEquals(
            "AREA_GAP_DP 必须等于布局里 widget_photo_area 的 layout_marginTop",
            WidgetData.AREA_GAP_DP, dpAttr(tagOf(xml, "widget_photo_area"), "layout_marginTop")
        )
        assertEquals(
            "每日一句的上边距同样要算进预算",
            WidgetData.AREA_GAP_DP, dpAttr(tagOf(xml, "widget_quote"), "layout_marginTop")
        )
    }

    @Test
    fun quoteCostIsEnoughForOneLinePlusItsGap() {
        // 一句话 11sp ≈ 16dp，加上 6dp 上边距 → 22dp。只要它掉到 16dp 以下，
        // 预算就不够放一行字，"显示出来"会变成"显示半截"
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
