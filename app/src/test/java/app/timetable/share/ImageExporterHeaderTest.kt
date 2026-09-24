package app.timetable.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出图页眉的排版不变量（[ImageExporter.headerLayout]）。
 *
 * 背景（本轮修的事故）：页眉原来是**两套坐标系混用** —— 字号/页眉高度按 density 缩放
 * （26dp→104px、96dp→384px），而 `left = 92f`、两个基线 `y = 84f / 114f` 用的是原始 px。
 * 结果：104px 高的字被放在 84px 的基线上（上沿跑到图外，第一行被裁）、两行基线只差 30px
 * 互相压住、`left = 92f` 又落在竖条自己的范围里（竖条 x 是 60..100px）。
 *
 * 现在页眉由 headerLayout 一处算出来，判据就是下面这三条（外加"整块随 density 等比缩放"）：
 *  1. 第一行字身的上沿 ≥ 0；
 *  2. 第二行的上沿 > 第一行的下沿；
 *  3. 竖条的上下端落在页眉高度内，且文字 x 在竖条右侧。
 *
 * 度量用**手算过的数**（真实 Paint.fontMetrics 会由 export() 传进来）：
 * `ascent` 是负值，"字身上沿 = 基线 + ascent"。
 */
class ImageExporterHeaderTest {

    /**
     * 手算基准（density = 4，本图固定 1440px 宽 = 360dp × 4.0）。
     *
     * dp 常量：左边距 22、竖条宽 4、条到文字间距 12、上边距 16、下边距 14、两行最小空隙 8。
     * 于是（全部 ×4）：padLeft=88、barWidth=16、gap=48、padTop=64、padBottom=56、lineGap=32。
     *
     * 用 26dp / 14dp 的字号配"近似等宽字体"的度量（ascent=-104、descent=26 / -56、14）：
     *   标题基线 = 64 − (−104)                  = 168   （字身上沿 = 64，正好等于上边距）
     *   标题下沿 = 168 + 26                      = 194
     *   副标题基线 = 194 + 32 − (−56)            = 282
     *   副标题下沿 = 282 + 14                    = 296
     *   页眉高度 = 296 + 56                      = 352   （= 课表网格起始 y）
     *   文字 x = 88 + 16 + 48                    = 152
     */
    private val density = 4f
    private val titleAscent = -104f
    private val titleDescent = 26f
    private val subAscent = -56f
    private val subDescent = 14f

    private fun layoutOf(
        density: Float,
        ta: Float, td: Float, sa: Float, sd: Float
    ) = ImageExporter.headerLayout(density, ta, td, sa, sd)

    /** 三条不变量，任何度量 / 任何 density 都必须成立 */
    private fun assertInvariants(
        layout: ImageExporter.HeaderLayout,
        titleAscent: Float,
        titleDescent: Float,
        subAscent: Float,
        subDescent: Float
    ) {
        val titleTop = layout.titleBaseline + titleAscent
        val titleBottom = layout.titleBaseline + titleDescent
        val subTop = layout.subBaseline + subAscent
        val subBottom = layout.subBaseline + subDescent

        assertTrue("第一行字身上沿不能跑到图外：top=$titleTop", titleTop >= 0f)
        assertTrue("第二行上沿($subTop) 必须低于第一行下沿($titleBottom)，否则两行压在一起", subTop > titleBottom)
        assertTrue("页眉高度(${layout.height}) 必须容得下第二行下沿($subBottom)", layout.height > subBottom)

        assertTrue("竖条上端不能跑到页眉外：top=${layout.barTop}", layout.barTop >= 0f)
        assertTrue(
            "竖条下端(${layout.barBottom}) 不能越过页眉底部(${layout.height})，否则会压到表头/网格",
            layout.barBottom <= layout.height
        )
        assertTrue("竖条不能是倒的：${layout.barTop} → ${layout.barBottom}", layout.barTop < layout.barBottom)
        assertTrue("竖条不能是空的：${layout.barLeft} → ${layout.barRight}", layout.barLeft < layout.barRight)
        assertTrue(
            "文字 x(${layout.textLeft}) 必须在竖条右沿(${layout.barRight}) 的右边",
            layout.textLeft > layout.barRight
        )
    }

    // ------------------------------------------------------------ 三条不变量

    @Test
    fun firstLineIsFullyVisible() {
        val head = layoutOf(density, titleAscent, titleDescent, subAscent, subDescent)
        // 手算：基线 = 上边距 − ascent = 64 + 104 = 168；字身上沿正好落在上边距 64 上
        assertEquals(168f, head.titleBaseline, 0.001f)
        assertEquals(64f, head.titleBaseline + titleAscent, 0.001f)
        assertTrue(head.titleBaseline + titleAscent >= 0f)
        assertInvariants(head, titleAscent, titleDescent, subAscent, subDescent)
    }

    @Test
    fun twoLinesDoNotOverlap() {
        val head = layoutOf(density, titleAscent, titleDescent, subAscent, subDescent)
        val titleBottom = head.titleBaseline + titleDescent
        val subTop = head.subBaseline + subAscent
        assertEquals(194f, titleBottom, 0.001f)
        assertEquals(282f, head.subBaseline, 0.001f)
        assertEquals(226f, subTop, 0.001f)
        // 两行之间至少留着 lineGap = 8dp × 4 = 32px 的空隙
        assertEquals(32f, subTop - titleBottom, 0.001f)
        assertInvariants(head, titleAscent, titleDescent, subAscent, subDescent)
    }

    @Test
    fun accentBarStaysInsideTheHeaderAndLeftOfTheText() {
        val head = layoutOf(density, titleAscent, titleDescent, subAscent, subDescent)
        // 手算：竖条 x = 22dp×4 = 88 → 88+16 = 104；y = 上边距 64 → 副标题下沿 296
        assertEquals(88f, head.barLeft, 0.001f)
        assertEquals(104f, head.barRight, 0.001f)
        assertEquals(64f, head.barTop, 0.001f)
        assertEquals(296f, head.barBottom, 0.001f)
        // 手算：文字 x = 88 + 16 + 48 = 152（旧代码写死 92px，正好落在竖条里面）
        assertEquals(152f, head.textLeft, 0.001f)
        // 页眉高度 = 副标题下沿 + 下边距 = 296 + 56 = 352 → 网格从这里开始，竖条不会扎进网格
        assertEquals(352f, head.height, 0.001f)
        assertInvariants(head, titleAscent, titleDescent, subAscent, subDescent)
    }

    // ------------------------------------------------------------ 度量变化 / 边界

    @Test
    fun narrowFontStillKeepsTheInvariants() {
        // 对照：字形又扁又矮（ascent 只有 -80、descent 20 的子集字体）。
        // 基线会跟着上移，但三条不变量必须照旧成立 —— 这正是"用 fontMetrics 反推基线"
        // 而不是"字号 × 系数"的理由：换字体不会把第一行裁掉、也不会让两行叠起来。
        val head = layoutOf(density, -80f, 20f, -40f, 10f)
        assertEquals(144f, head.titleBaseline, 0.001f)
        assertEquals(64f, head.titleBaseline - 80f, 0.001f)
        assertEquals(236f, head.subBaseline, 0.001f)
        assertEquals(302f, head.height, 0.001f)
        assertInvariants(head, -80f, 20f, -40f, 10f)
    }

    @Test
    fun invariantsHoldAtBothDensityExtremes() {
        // 导出图固定 density = 4；1.0 是"万一把 EXPORT_WIDTH 改成 360px"的边界，
        // 两侧都必须成立（以前混用坐标时，正是缩放的那一半在小 density 下贴边、大 density 下裁切）
        for (d in listOf(1f, 2f, 3f, 4f, 6f)) {
            assertInvariants(layoutOf(d, -26f * d, 6.5f * d, -14f * d, 3.5f * d), -26f * d, 6.5f * d, -14f * d, 3.5f * d)
        }
    }

    @Test
    fun layoutScalesExactlyWithDensity() {
        // 回归"两套坐标系混用"：度量按同一比例缩放时，整个页眉必须**等比**缩放。
        // 以前 left/baseline 是写死的 px，字号却乘了 density —— 这条等比性一旦不成立，
        // 就说明又有人把某个数写成了原始 px。
        val small = layoutOf(1f, titleAscent / 4f, titleDescent / 4f, subAscent / 4f, subDescent / 4f)
        val big = layoutOf(4f, titleAscent, titleDescent, subAscent, subDescent)
        val ratio = density
        assertEquals(small.height * ratio, big.height, 0.001f)
        assertEquals(small.textLeft * ratio, big.textLeft, 0.001f)
        assertEquals(small.barLeft * ratio, big.barLeft, 0.001f)
        assertEquals(small.barRight * ratio, big.barRight, 0.001f)
        assertEquals(small.barTop * ratio, big.barTop, 0.001f)
        assertEquals(small.barBottom * ratio, big.barBottom, 0.001f)
        assertEquals(small.titleBaseline * ratio, big.titleBaseline, 0.001f)
        assertEquals(small.subBaseline * ratio, big.subBaseline, 0.001f)
    }
}
