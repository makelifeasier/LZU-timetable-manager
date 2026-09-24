package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 底部扩展区的取舍规则（每日一句 / 图片）+ 图片框高度的计算。
 *
 * 这一组规格全部来自真机反馈：
 *  - **开了图片之后「每日一句」就没了**：老逻辑是"图片优先"，`quoteOn` 里带了一个
 *    `&& !photoOn`，等于无条件把句子藏起来。现在两块用**同一个预算**一起决策：
 *    放得下就都显示（句子在上、图片在下），放不下才退让。
 *  - **退让顺序**：先保图片（用户主动挑的、信息量大），句子是装饰；
 *    但连图片的硬底线（32dp）都占不下时反过来只留句子（一句话只要 22dp）。
 *  - **图片框高度按内容宽度算**：根布局左右各有 12dp 内边距，以前拿格子宽度当图片宽度，
 *    在窄组件上会把图片框算成近乎正方形的块。
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

    @Test
    fun photoHeightUsesContentWidthNotCellWidth() {
        // 4×3 的宽扁格子：内容宽 = 250-24 = 226 → 226×0.72 = 162 → 上限 150
        assertEquals(150, WidgetData.photoHeightDpFor(250, 110))
        // 窄格子（2 格宽）：内容宽 = 150-24 = 126 → 竖长比例 1.15 → 144
        // 老算法按格子宽算：150×1.15 = 172 → 撞上限 150，等于把一个 126dp 宽的框撑成 150dp 高
        // —— 出来的就是真机反馈里那种"近似正方形的块"
        assertEquals(144, WidgetData.photoHeightDpFor(150, 200))
        assertTrue(
            "按内容宽算出来的高度必须比按格子宽算的更矮",
            WidgetData.photoHeightDpFor(150, 200) < 150
        )
    }

    @Test
    fun photoHeightRespectsFloorAndCeiling() {
        assertEquals(WidgetData.PHOTO_MIN_DP, WidgetData.photoHeightDpFor(40, 40))
        assertEquals(WidgetData.PHOTO_MAX_DP, WidgetData.photoHeightDpFor(1000, 300))
    }

    @Test
    fun photoHeightFallsBackWhenSizesAreUnknown() {
        // 部分启动器不上报尺寸：给一个保守值，别乱猜
        assertEquals(WidgetData.PHOTO_MIN_DP, WidgetData.photoHeightDpFor(0, 0))
        assertEquals(WidgetData.PHOTO_MIN_DP, WidgetData.photoHeightDpFor(-1, 300))
        assertEquals(WidgetData.PHOTO_MIN_DP, WidgetData.photoHeightDpFor(300, 0))
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
