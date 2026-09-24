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
    private val ideal = 150                              // 典型：宽扁组件 → 撞到上限

    private fun plan(
        override: Int = 0,
        quote: Boolean = true,
        photo: Boolean = true,
        count: Int = 3,
        availableDp: Int = 200,
        idealPhotoDp: Int = ideal
    ) = ExtrasPlanner.plan(override, quote, photo, count, availableDp, idealPhotoDp)

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
        val p = plan(override = 1, availableDp = 300, idealPhotoDp = 90)
        assertEquals(90, p.photoHeightDp)
    }

    @Test
    fun forceShowStillGivesAVisibleHeightWhenIdealIsUnknown() {
        // 理想高度取不到时（photoHeightDp 返回 56）强制显示也要给个能看见的高度
        val p = plan(override = 1, availableDp = 0, idealPhotoDp = WidgetData.PHOTO_MIN_DP)
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
            val p = plan(availableDp = avail, idealPhotoDp = 400)
            if (!p.photoVisible) continue
            val cost = gap + p.photoHeightDp + if (p.quoteVisible) quoteCost else 0
            assertTrue("余量 ${avail}dp 时总占用 ${cost}dp 超了", cost <= avail)
            assertTrue("图片高度不能低于硬底线", p.photoHeightDp >= minPhoto)
        }
    }

    @Test
    fun idealHeightWinsWhenThereIsPlentyOfRoom() {
        // 余量足够时给的是"理想高度"（按宽高比算出来的），不是"能塞多高塞多高"
        val p = plan(availableDp = 300, idealPhotoDp = 90)
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
    // 这一组是本轮改的：图片框的高度**只由宽度决定**（16:9），不再按组件高度在
    // 0.72 / 1.15 之间跳。老做法让"同一个框在不同高度下比例不同"，而照片比例是固定的，
    // 于是 centerCrop 每次都裁在别处 —— 真机反馈的"显示窗口和图片大小对不上"。

    @Test
    fun photoHeightFollowsTheContentWidthAtSixteenToNine() {
        // 4×3 宽扁格子：格子宽 250 → 内容宽 250-24 = 226 → 226/(16/9) = 127.1 → 127
        assertEquals(127, WidgetData.photoHeightForWidthDp(226))
        // 2 格宽的窄格子：150-24 = 126 → 70.9 → 71
        assertEquals(71, WidgetData.photoHeightForWidthDp(126))
        // 比例必须真的是 16:9（差一点点只是四舍五入）
        val ratio = 226f / WidgetData.photoHeightForWidthDp(226)
        assertTrue("内容宽 226dp 时高度比例应≈16:9，实际 $ratio", Math.abs(ratio - 16f / 9f) < 0.02f)
    }

    @Test
    fun photoHeightUsesContentWidthNotTheCellWidth() {
        // 关键回归：算高度必须用**内容宽度**（格子宽 - 左右各 12dp 内边距）。
        // 老算法按格子宽度 150×1.15 会把一个 126dp 宽的框撑成 150dp 高（近乎正方形），
        // 正是真机反馈里"右边一个近似正方形的块"。现在按内容宽算，只有 71dp。
        assertTrue(
            "按格子宽度算出来的高度必须比按内容宽度算的更高（这就是内边距那 24dp 的作用）",
            WidgetData.photoHeightForWidthDp(150) > WidgetData.photoHeightForWidthDp(126)
        )
        assertEquals(
            "内容宽 126dp 的框，16:9 应当是 71dp（而不是按格子宽 150dp 算出来的 84dp）",
            71, WidgetData.photoHeightForWidthDp(126)
        )
    }

    @Test
    fun photoHeightRespectsFloorAndCeiling() {
        // 极窄格子：16:9 算出来只有 22dp 不到，连一条都算不上 → 抬到下限
        assertEquals(WidgetData.PHOTO_MIN_DP, WidgetData.photoHeightForWidthDp(40))
        // 超宽格子：1000dp 按 16:9 要 562dp 高 —— 那就不是配图而是相册了，压到上限（也省 binder）
        assertEquals(WidgetData.PHOTO_MAX_DP, WidgetData.photoHeightForWidthDp(1000))
    }

    @Test
    fun photoHeightFallsBackWhenTheWidthIsUnknown() {
        // 部分启动器不上报尺寸：给保守值，别按瞎猜的比例乱算
        assertEquals(WidgetData.PHOTO_MIN_DP, WidgetData.photoHeightForWidthDp(0))
        assertEquals(WidgetData.PHOTO_MIN_DP, WidgetData.photoHeightForWidthDp(-1))
    }

    // ------------------------------------------------- 图片框"实际"高度（纯函数）

    @Test
    fun photoBoxTargetKeepsRoomForItsOwnTopMargin() {
        // 余量 78dp（窄格子在 187dp 高度上的实测值）：扣掉 6dp 上边距 → 框 72dp
        assertEquals(72, WidgetData.photoBoxTargetDp(78, 126))
        // 边上刚好只剩上边距：框是 0（不能算出负数 —— coerceIn 会直接抛异常）
        assertEquals(0, WidgetData.photoBoxTargetDp(WidgetData.AREA_GAP_DP, 126))
        assertEquals(0, WidgetData.photoBoxTargetDp(0, 126))
        assertEquals(0, WidgetData.photoBoxTargetDp(-50, 126))
    }

    @Test
    fun photoBoxTargetIsCappedSoThePhotoNeverEatsTheWholeWidget() {
        // 宽格子（内容宽 226dp）：上限就是 PHOTO_MAX_DP，不会无限长高（观感 + binder 预算）
        assertEquals(WidgetData.PHOTO_MAX_DP, WidgetData.photoBoxTargetDp(1000, 226))
        assertTrue(WidgetData.photoBoxTargetDp(1000, 226) <= WidgetData.PHOTO_MAX_DP)
    }

    @Test
    fun photoBoxNeverBecomesTallerThanItIsWide() {
        // 又高又窄的组件（内容宽 126dp、余量却很大）：按余量算想把框拉到 150dp，
        // 那就是个**竖着的框**了 —— 横构图的照片裁成竖构图会切掉大半（用户吐槽过的"近方块"）。
        // 宁可留一点白，也不把框竖过来。
        val box = WidgetData.photoBoxTargetDp(1000, 126)
        assertEquals(126, box)
        assertTrue("框的宽高比不能小于 1:1（宽 $126 vs 高 $box）", box <= 126)
        // 极窄格子（内容宽比 PHOTO_MIN_DP 还小）也不能超过它自己的宽度
        assertTrue(WidgetData.photoBoxTargetDp(1000, 40) <= 40)
    }

    // ------------------------------------------------- 预留空间 ↔ 行数（本轮的核心取舍）

    @Test
    fun reservingThePhotoKeepsTheRowCountFixedWithinOneRowSlot() {
        // 窄格子（内容宽 126dp → 理想图片 71dp）的真实数字：
        //   预留 = 71 + 6（图片上边距）= 77dp；行高 38dp；头部 71dp（150dp 以上不收页脚）
        // 手算：(高度 - 71 - 77) / 38 取整，再夹到 1..4
        val reserve = WidgetData.photoHeightForWidthDp(126) + WidgetData.AREA_GAP_DP
        assertEquals(77, reserve)

        assertEquals("150dp：扣掉预留只剩 2dp → 夹到 1 整行", 1, WidgetData.rowsFor(150, 0, WidgetData.ROW_SLOT_DP, reserve))
        assertEquals("187dp：多出来的 39dp 还不够一行 → 仍然是 1 整行", 1, WidgetData.rowsFor(187, 0, WidgetData.ROW_SLOT_DP, reserve))
        assertEquals("200dp：52dp 也还不够一行 → 1 整行（行数没跟着高度变）", 1, WidgetData.rowsFor(200, 0, WidgetData.ROW_SLOT_DP, reserve))
        assertEquals("225dp：77dp ≥ 一行 → 才多出第 2 行", 2, WidgetData.rowsFor(225, 0, WidgetData.ROW_SLOT_DP, reserve))
        assertEquals("250dp：102dp → 还是 2 行", 2, WidgetData.rowsFor(250, 0, WidgetData.ROW_SLOT_DP, reserve))
        assertEquals("263dp：115dp ≥ 三行 → 3 行", 3, WidgetData.rowsFor(263, 0, WidgetData.ROW_SLOT_DP, reserve))
    }

    @Test
    fun everyReservedRowCountIsAWholeNumber() {
        // 仓库的硬约束：底部不能切出半行。行数永远是整数，列表高度 = 行数 × 38dp（整数倍）。
        val reserve = WidgetData.photoHeightForWidthDp(126) + WidgetData.AREA_GAP_DP
        for (h in 90..400) {
            val rows = WidgetData.rowsFor(h, 0, WidgetData.ROW_SLOT_DP, reserve)
            assertTrue("${h}dp 算出了非法的行数 $rows", rows in 1..WidgetData.AUTO_MAX_ROWS)
            val listHeightDp = rows * WidgetData.ROW_SLOT_DP
            assertEquals(
                "${h}dp 的列表高度必须是行高的整数倍（否则底部会露半行）",
                0f, listHeightDp % WidgetData.ROW_SLOT_DP, 0.001f
            )
        }
    }

    @Test
    fun withoutThePhotoNothingChangesForExistingUsers() {
        // 预留 = 0 时算法必须与老版本逐字相同（不用图片的人不该被这次改动碰到）
        assertEquals(WidgetData.rowsFor(150, 0), WidgetData.rowsFor(150, 0, WidgetData.ROW_SLOT_DP, 0))
        assertEquals(2, WidgetData.rowsFor(150, 0, WidgetData.ROW_SLOT_DP, 0))
        assertEquals(4, WidgetData.rowsFor(250, 0, WidgetData.ROW_SLOT_DP, 0))
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
        val reserve = WidgetData.photoHeightForWidthDp(126) + WidgetData.AREA_GAP_DP

        // 150dp 的小格子：行数 1，列表下方余量 = 150 - 71（头部）- 38（一行）= 41dp
        val rows = WidgetData.rowsFor(150, 0, WidgetData.ROW_SLOT_DP, reserve)
        val chrome = if (WidgetData.compactFor(150, 0)) WidgetData.CHROME_DP - WidgetData.FOOTER_DP else WidgetData.CHROME_DP
        val avail = (150 - chrome - rows * WidgetData.ROW_SLOT_DP).toInt()
        assertEquals(41, avail)

        val plan = ExtrasPlanner.plan(
            override = 0,
            quoteEnabled = false,
            photoEnabled = true,
            photoCount = 3,
            availableDp = avail,
            idealPhotoDp = WidgetData.photoBoxTargetDp(avail, 126)
        )
        assertTrue("150dp 的格子里，只要行数算得出来图片就必须显示（这是本轮要根治的失败模式）", plan.photoVisible)
        assertEquals("框高 = 余量 - 6dp 上边距", 35, plan.photoHeightDp)
        // 而且整条链路加起来不能超出表格子：头部 71 + 列表 38 + 上边距 6 + 框 35 ≤ 150
        assertTrue(chrome + rows * WidgetData.ROW_SLOT_DP + WidgetData.AREA_GAP_DP + plan.photoHeightDp <= 150f)
    }

    @Test
    fun thePhotoBoxAbsorbsTheLeftoverSoTheFrameGrowsWithTheWidget() {
        // "向下拉 → 多出来的高度归图片"：同一档行数（1 行）下，格子越高框越高
        val reserve = WidgetData.photoHeightForWidthDp(126) + WidgetData.AREA_GAP_DP
        fun boxAt(h: Int): Int {
            val rows = WidgetData.rowsFor(h, 0, WidgetData.ROW_SLOT_DP, reserve)
            val chrome = if (WidgetData.compactFor(h, 0)) WidgetData.CHROME_DP - WidgetData.FOOTER_DP else WidgetData.CHROME_DP
            val avail = (h - chrome - rows * WidgetData.ROW_SLOT_DP).toInt()
            val p = ExtrasPlanner.plan(0, false, true, 3, avail, WidgetData.photoBoxTargetDp(avail, 126))
            return p.photoHeightDp
        }
        // 150dp → 35dp（比 16:9 扁：小格子只能这样，比例不匹配的锅由解码尺寸一起承担）
        assertEquals(35, boxAt(150))
        // 187dp → 72dp，正好是 126dp 宽的 16:9（71dp）+ 1dp 取整
        assertEquals(72, boxAt(187))
        // 200dp → 85dp：多出来的 13dp 全给了图片，行数仍然是 1
        assertEquals(85, boxAt(200))
        assertTrue("同一档行数内，格子越高图片越大", boxAt(150) < boxAt(187) && boxAt(187) < boxAt(200))
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
