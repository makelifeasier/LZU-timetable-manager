package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 图片渲染的**几何决策**（纯函数部分）：放得下 / 放不下、两条产出路径、binder 预算与日志数字。
 *
 * 这一组守的是用户两条原话引出来的那件事：
 *
 * > 「我不是要你有显示倍率，而是导入图片的时候可以自己裁剪，而且在空间足够的时候图片完整显示，
 * >   不够的时候显示自己裁剪的部分，注意组件是几乘几。」
 *
 * 三件事必须钉死，一个都不能靠"看起来对"：
 *
 *  1. **判据**：[PhotoFit.fitsWhole] 用整数交叉相乘（先转 Long）而不是两个 float 相除相比 ——
 *     "框和图比例完全相等"（= 用户按裁剪框裁出来的图，最常见的情况）必须落进"完整显示"，
 *     浮点误差把它判反就会平白切掉一条；
 *  2. **产出**：放不下 → 产出**逐值等于框**（917×137 这种）；放得下 → 产出**宽等于框宽**、
 *     高按图自身比例且 ≤ 框高（宿主 `fitCenter` 的缩放因子恰好是 1，等于零缩放零裁剪）；
 *  3. **永不越界**：裁剪矩形完全来自这里，`Bitmap.createBitmap` 越界会抛
 *     `IllegalArgumentException`，而这段代码跑在小组件刷新的主线程上 —— 抛了就是整个 provider 挂掉。
 *
 * 单测里 Android API 全是"返回默认值"的假实现（`isReturnDefaultValues = true`），
 * 所以这里**只测不碰 Bitmap/Context 的算术**（见文件末尾 [whatIsNotCoveredHere]）。
 */
class PhotoFitTest {

    // ---------------------------------------------------------------- 真机实测的框

    /**
     * 真机实测：4 列 × 2 行的格子 → 内容宽 349dp、图片区 52dp，density 2.625
     * → 框 917×137 px。这一组数字是用户拿截图取像素核对时用的那套，逐值钉住。
     */
    private val boxW = 917
    private val boxH = 137

    private fun repoFile(relative: String): File? = listOf(
        File(relative),
        File("app/$relative"),
        File("/$relative")
    ).firstOrNull { it.isFile }

    // ---------------------------------------------------------------- 1. 判据

    @Test
    fun fitsWholeIsTrueExactlyWhenTheBoxIsTallEnoughForTheImageRatio() {
        // 用户那句话的直译：框给的高度 ≥ 按图的比例算出来的高度。
        // 等号这一档（框和图同比例）算"放得下"—— 这正是"用户按裁剪框裁出来的图"那种最常见的情况，
        // 判成"放不下"就会把刚裁好的图再切一条，属于最不可接受的偏差。
        assertTrue("同比例：整除放得下（零留边）", PhotoFit.fitsWhole(917, 137, 917, 137))
        assertTrue("图比框更扁：也放得下（留边）", PhotoFit.fitsWhole(917, 100, 917, 137))
        assertTrue("用户按裁剪框（226:72）裁出来、解码成 594×189", PhotoFit.fitsWhole(720, 229, 594, 189))

        // 放不下：框比图更扁 —— 这是相册里最常见的两种图（横屏截图、竖屏截图）
        assertFalse("1600×900 的横片进 6.7:1 的框", PhotoFit.fitsWhole(1600, 900, boxW, boxH))
        assertFalse("1440×3587 的竖屏截图（真机实测那张）", PhotoFit.fitsWhole(1440, 3587, boxW, boxH))
        assertFalse("方图", PhotoFit.fitsWhole(1000, 1000, boxW, boxH))

        // 极端比例
        assertFalse(PhotoFit.fitsWhole(1, 1000, 1000, 1))
        assertTrue(PhotoFit.fitsWhole(1000, 1, 1, 1000))
    }

    @Test
    fun fitsWholeUsesLongArithmeticSoBigImagesCannotOverflow() {
        // 交叉相乘在 Int 上会溢出：65536×65536 = 4.29e9 > Int.MAX_VALUE。
        // 溢出会让判断随机翻转 —— 大图上"该完整显示却切成中间块"，或者反过来，
        // 而用户手里随便一张相册图就是 4000px 级别的。
        assertTrue(PhotoFit.fitsWhole(65536, 65536, 65536, 65536))
        assertTrue(PhotoFit.fitsWhole(65536, 65535, 65536, 65536))
        assertFalse(PhotoFit.fitsWhole(65536, 65537, 65536, 65536))
    }

    @Test
    fun fitsWholeIsFalseForIllegalSizes() {
        // 尺寸取不到（部分启动器不上报）或脏值时不能给出"能放下"这种乐观结论
        assertFalse(PhotoFit.fitsWhole(0, 100, boxW, boxH))
        assertFalse(PhotoFit.fitsWhole(100, 0, boxW, boxH))
        assertFalse(PhotoFit.fitsWhole(100, 100, 0, boxH))
        assertFalse(PhotoFit.fitsWhole(100, 100, boxW, 0))
        assertFalse(PhotoFit.fitsWhole(-5, 1000, boxW, boxH))
    }

    // ---------------------------------------------------------------- 2. 放不下 → 取中间块

    @Test
    fun whenItDoesNotFitWeTakeTheCentredSliceOfTheCroppedPhoto() {
        // 真机那张 1440×3587 的竖屏截图：框 917×137（6.69:1）比图扁得多
        // → 窗口 = 整宽 × 按框比例算出的高（1440 ÷ 6.6934 = 215），上下居中
        val l = PhotoFit.layout(1440, 3587, boxW, boxH)!!
        assertFalse("这一档必须是取中间块", l.whole)
        assertEquals(1440, l.window.width)
        assertEquals(215, l.window.height)
        assertEquals(0, l.window.x)
        assertEquals(1686, l.window.y)          // (3587 − 215) ÷ 2 = 1686
        assertEquals("产出逐值等于框宽", boxW, l.outWidth)
        assertEquals("产出逐值等于框高", boxH, l.outHeight)
        assertEquals("这一档没有留边", 0, l.padY)
        assertEquals("取中间块", l.verdict)

        // 16:9 的横片同理：窗口 1600×239，纵向 661px 里居中 → 331
        val wide = PhotoFit.layout(1600, 900, boxW, boxH)!!
        assertEquals(1600, wide.window.width)
        assertEquals(239, wide.window.height)
        assertEquals(331, wide.window.y)
        assertEquals(boxW, wide.outWidth)
        assertEquals(boxH, wide.outHeight)
    }

    @Test
    fun theSliceIsReallyInTheMiddleOfTheLeftover() {
        // "居中"不能只对一两组数字成立：余量左右/上下之差最多 1px（取整）
        for (c in listOf(
            intArrayOf(1600, 900), intArrayOf(1440, 3587), intArrayOf(4000, 3000),
            intArrayOf(1080, 2400), intArrayOf(1000, 1000), intArrayOf(2000, 274)
        )) {
            val l = PhotoFit.layout(c[0], c[1], boxW, boxH)!!
            if (l.whole) continue
            val left = l.window.y
            val right = c[1] - l.window.height - l.window.y
            assertTrue("竖直方向不居中：$left vs $right（${c.toList()}）", Math.abs(left - right) <= 1)
        }
    }

    @Test
    fun theSliceKeepsTheBoxRatioSoNothingGetsStretched() {
        // 窗口与框同比例 ⇒ 缩到框尺寸时不会变形。允许 2% 的取整误差。
        // （2000×274 那一组其实是"放得下"—— 图比框更扁；这里只验走"取中间块"的那几组）
        for (c in listOf(
            intArrayOf(1600, 900), intArrayOf(1440, 3587), intArrayOf(1080, 2400),
            intArrayOf(4000, 3000), intArrayOf(1000, 1000)
        )) {
            val l = PhotoFit.layout(c[0], c[1], boxW, boxH)!!
            assertFalse("${c.toList()} 这一组本来就该走取中间块", l.whole)
            val boxRatio = boxW.toDouble() / boxH.toDouble()
            val winRatio = l.window.width.toDouble() / l.window.height.toDouble()
            val err = Math.abs(boxRatio - winRatio) / boxRatio
            assertTrue("${c.toList()} 的窗口比例 $winRatio 偏了 ${err * 100}%", err < 0.02)
            // 产出必须逐值等于框：它就是"填满"的尺寸，误差一点都不要
            assertEquals(boxW, l.outWidth)
            assertEquals(boxH, l.outHeight)
        }
    }

    // ---------------------------------------------------------------- 3. 放得下 → 完整显示

    @Test
    fun whenItFitsTheWholeCroppedPhotoIsShown() {
        // 框比图"更高"（7.5:1 的图进 6.7:1 的框）：整张都在，上下留边
        val l = PhotoFit.layout(917, 100, boxW, boxH)!!
        assertTrue(l.whole)
        assertEquals("窗口 = 整张源图，一个像素都不裁", "917x100@(0,0)", l.window.toString())
        assertEquals("产出宽 = 框宽（宿主缩放因子恒为 1）", boxW, l.outWidth)
        assertEquals("产出高按图自己的比例", 100, l.outHeight)
        assertEquals("留边 37px", 37, l.padY)
        assertEquals(0.27f, l.padShare, 0.005f)
        assertEquals("完整显示", l.verdict)
    }

    @Test
    fun aPhotoCroppedAtTheFrameRatioFillsTheBoxExactly() {
        // 这一条是整轮改动的目的：用户在裁剪界面里按"组件图片区的形状"裁出来的图，
        // 落到组件里应该是**整张、铺满、零留边、零裁剪**。
        // 组件 4×2 → 内容宽 226dp、图片区 72dp；解码目标 = 226×72dp @2.625 = 594×189px
        val frame = PhotoCrop.frame(226, 72)
        assertEquals(3.14f, frame.ratio, 0.01f)
        val target = PhotoBitmap.targetPx(226, 72, 2.625f)
        assertEquals(594, target[0])
        assertEquals(189, target[1])

        // 按这个框的比例存下来的文件（720×229 是"框比例 × 长边 ≤1600"的存盘尺寸）
        val l = PhotoFit.layout(720, 229, target[0], target[1])!!
        assertTrue("按裁剪框比例裁出来的图必须走完整显示", l.whole)
        assertEquals(594, l.outWidth)
        assertEquals("零留边（只是取整）", 189, l.outHeight)
        assertEquals(0, l.padY)
        assertEquals("整张都在", "720x229@(0,0)", l.window.toString())
    }

    @Test
    fun theWholeBranchNeverUpscalesBeyondTheBox() {
        // 源图比框小时宽度会跟着框走（宽度=框宽是不变量），但高度永远不超过框高 ——
        // 否则就不再是"放得下"
        val l = PhotoFit.layout(200, 20, boxW, boxH)!!
        assertTrue(l.whole)
        assertEquals(boxW, l.outWidth)
        assertEquals(92, l.outHeight)     // round(917 × 20 ÷ 200) = 92
        assertTrue("高不能超过框高", l.outHeight <= boxH)
        assertEquals(45, l.padY)
    }

    // ---------------------------------------------------------------- 4. 永不越界 / 覆盖各种比例

    @Test
    fun theCropRectangleNeverEscapesTheSourceImage() {
        // 越界 → createBitmap 抛异常 → **整个 provider 挂掉**。把比例矩阵穷举一遍
        // （含 1px、极端比例、双方互为宽高、真机那几组框）。
        val srcs = listOf(
            intArrayOf(1, 1), intArrayOf(1, 4000), intArrayOf(4000, 1),
            intArrayOf(1600, 1066), intArrayOf(1066, 1600), intArrayOf(2400, 1080),
            intArrayOf(1440, 3587), intArrayOf(3, 7), intArrayOf(8000, 8000)
        )
        val boxes = listOf(
            intArrayOf(1, 1), intArrayOf(1, 300), intArrayOf(300, 1),
            intArrayOf(917, 137), intArrayOf(349, 52), intArrayOf(331, 187),
            intArrayOf(594, 189), intArrayOf(777, 394), intArrayOf(2, 3)
        )
        var checked = 0
        for (s in srcs) for (b in boxes) {
            val l = PhotoFit.layout(s[0], s[1], b[0], b[1])
            assertNotNull("源=$s 框=$b 应当算出结论", l)
            val it = l!!
            val w = it.window
            assertTrue(
                "越界：源=${s[0]}x${s[1]} 框=${b[0]}x${b[1]} 窗口=$w",
                w.width >= 1 && w.height >= 1 &&
                    w.x >= 0 && w.y >= 0 &&
                    w.x + w.width <= s[0] && w.y + w.height <= s[1]
            )
            assertTrue("产出不能塌成 0：$it", it.outWidth >= 1 && it.outHeight >= 1)
            assertEquals("产出宽必须逐值等于框宽", b[0], it.outWidth)
            assertTrue("产出高不能超过框高：$it", it.outHeight <= b[1])
            checked++
        }
        assertEquals(
            "组合数变了说明覆盖范围变了（有意改的才动这个数）",
            srcs.size * boxes.size,
            checked
        )
    }

    @Test
    fun illegalInputsYieldNoLayoutInsteadOfACrash() {
        // 尺寸取不到 / 脏值时不能给出会崩的矩形：返回 null，调用方跳过这张图
        assertNull(PhotoFit.layout(0, 100, boxW, boxH))
        assertNull(PhotoFit.layout(100, 0, boxW, boxH))
        assertNull(PhotoFit.layout(1000, 1000, 0, boxH))
        assertNull(PhotoFit.layout(1000, 1000, boxW, 0))
        assertNull(PhotoFit.layout(-5, 1000, boxW, boxH))
        assertNull(PhotoFit.layout(1000, 1000, -1, boxH))
        assertNull(PhotoFit.layout(1000, 1000, Int.MIN_VALUE, boxH))
        assertNull(PhotoFit.centerWindow(0, 100, boxW, boxH))
        assertNull(PhotoFit.wholeSize(1000, 1000, boxW, 0))
    }

    @Test
    fun outputNeverExceedsTheBinderBudgetForRealisticWidgetSizes() {
        // binder 预算是硬约束（超了整次 RemoteViews 更新会被系统丢掉，表现是"点刷新没反应"）。
        // 真实的解码目标不是"框的 dp×density"，而是**经 [PhotoBitmap.targetPx] 夹过预算的**尺寸，
        // 所以断言也必须从那条链路走一遍。
        // 字节数按 2 字节/像素算 —— 这是**解码后强制转 RGB_565** 之后才成立的前提
        // （见 PhotoBitmap 的类注释：inPreferredConfig 只是建议，带 alpha 的 PNG 会给 ARGB_8888）。
        val density = 2.625f
        var checked = 0
        for (dpBox in listOf(
            intArrayOf(126, 71), intArrayOf(226, 72), intArrayOf(296, 150),
            intArrayOf(349, 52), intArrayOf(360, 88), intArrayOf(600, 300)
        )) {
            val target = PhotoBitmap.targetPx(dpBox[0], dpBox[1], density)
            for (src in listOf(
                intArrayOf(1600, 1066), intArrayOf(1080, 2400), intArrayOf(4000, 3000),
                intArrayOf(200, 150), intArrayOf(1, 1), intArrayOf(1440, 3587)
            )) {
                val l = PhotoFit.layout(src[0], src[1], target[0], target[1]) ?: continue
                val bytes = l.outWidth.toLong() * l.outHeight.toLong() * PhotoBitmap.BYTES_PER_PIXEL
                assertTrue(
                    "框=$dpBox 目标=${target.toList()} 源=${src.toList()} " +
                        "产出=${l.outWidth}x${l.outHeight} = $bytes 字节，超了 binder ${PhotoBitmap.MAX_BITMAP_BYTES}",
                    bytes <= PhotoBitmap.MAX_BITMAP_BYTES
                )
                checked++
            }
        }
        assertEquals("每个组合都要被验到", 6 * 6, checked)
    }

    // ---------------------------------------------------------------- 5. 真实字节数的预算兜底

    @Test
    fun budgetSizeOnlyShrinksWhatReallyDoesNotFit() {
        // 917×137 的 RGB_565 是 251,258 字节 ≤ 300KB → 一个像素都不动
        val fits = PhotoFit.budgetSize(917, 137, PhotoBitmap.MAX_BITMAP_BYTES, 2)
        assertEquals(917, fits[0])
        assertEquals(137, fits[1])

        // 真机事故那一档：同一张图被解码器给成了 ARGB_8888（4 字节/像素 = 502,516 字节）
        // 按 2 字节估的预算完全挡不住它，这里必须按真实字节数缩
        val argbBytes = 917L * 137L * 4L
        assertTrue("这一档本来就超预算（否则这条测试是假绿）", argbBytes > PhotoBitmap.MAX_BITMAP_BYTES)
        val shrunk = PhotoFit.budgetSize(917, 137, PhotoBitmap.MAX_BITMAP_BYTES, 4)
        assertEquals(716, shrunk[0])
        assertEquals(107, shrunk[1])
        assertTrue(
            "缩完之后必须真的过关：${shrunk[0]}x${shrunk[1]}",
            shrunk[0].toLong() * shrunk[1].toLong() * 4 <= PhotoBitmap.MAX_BITMAP_BYTES
        )
        assertTrue("比例不能压变形", Math.abs(shrunk[0].toFloat() / shrunk[1] - 917f / 137f) < 0.05f)
    }

    @Test
    fun budgetSizeHandlesTheOversizedRgbCaseAndDegenerateInput() {
        // 1000×1000 的 RGB_565 是 2MB，必须缩到预算内（等比）
        val big = PhotoFit.budgetSize(1000, 1000, PhotoBitmap.MAX_BITMAP_BYTES, PhotoBitmap.BYTES_PER_PIXEL)
        assertTrue(
            "${big[0]}x${big[1]} 仍在预算外",
            big[0].toLong() * big[1].toLong() * PhotoBitmap.BYTES_PER_PIXEL <= PhotoBitmap.MAX_BITMAP_BYTES
        )
        assertTrue("不能缩成 0", big[0] >= 1 && big[1] >= 1)
        // 脏值（0 / 负数 / 每像素 0 字节）不能算出 0×0 的位图
        assertEquals(1, PhotoFit.budgetSize(0, 100)[0])
        assertEquals(1, PhotoFit.budgetSize(-5, 100)[1])
        val bpp0 = PhotoFit.budgetSize(1000, 1000, PhotoBitmap.MAX_BITMAP_BYTES, 0)
        assertTrue(bpp0[0] >= 1 && bpp0[1] >= 1)
        assertTrue(
            "bytesPerPixel 传脏值时按 2 字节/像素兜底",
            bpp0[0].toLong() * bpp0[1].toLong() * 2 <= PhotoBitmap.MAX_BITMAP_BYTES
        )
    }

    // ---------------------------------------------------------------- 6. 日志（用户唯一的判据）

    @Test
    fun renderLabelCarriesEveryNumberTheUserNeedsToCheckTheScreenshot() {
        // 取中间块那一档：真机那张 1440×3587 的截屏，框 349×52dp（917×137px），裁剪框 349:136dp
        val l = PhotoFit.layout(1440, 3587, 917, 137)!!
        val facts = RenderFacts(
            layout = l,
            sourceW = 1440,
            sourceH = 3587,
            bitmapW = 917,
            bitmapH = 137,
            byteCount = 917 * 137 * 2,      // 251,258 = RGB_565 的真实字节数
            config = "RGB_565"
        )
        val label = PhotoBitmap.renderLabel(facts, PhotoCrop.frame(349, 136), 349, 52)
        assertEquals(
            "框=349x52dp 比例=6.71:1 裁剪框=349:136dp(2.57:1) 源=1440x3587(0.40:1) " +
                "裁剪图=1440x215 取中间块 窗=1440x215@(0,1686) " +
                "bitmap=917x137 byteCount=251258B(245KB) config=RGB_565",
            label
        )
        // 这几个 token 是给用户（和以后的我）按图索骥用的，缺一列就少一个判据
        for (token in listOf("框=", "比例=", "裁剪框=", "取中间块", "bitmap=", "byteCount=", "config=")) {
            assertTrue("日志缺少 $token：$label", label.contains(token))
        }
    }

    @Test
    fun renderLabelSwitchesToTheWholeVerdictAndReportsThePadding() {
        // 完整显示那一档：226:72 的裁剪图，框 226×72dp（594×189px）→ 零留边
        val l = PhotoFit.layout(720, 229, 594, 189)!!
        val facts = RenderFacts(l, 720, 229, 594, 189, 594 * 189 * 2, "RGB_565")
        val label = PhotoBitmap.renderLabel(facts, PhotoCrop.frame(226, 72), 226, 72)
        assertEquals(
            "框=226x72dp 比例=3.14:1 裁剪框=226:72dp(3.14:1) 源=720x229(3.14:1) " +
                "裁剪图=720x229 完整显示 留边=0px(0%) " +
                "bitmap=594x189 byteCount=224532B(219KB) config=RGB_565",
            label
        )
        assertTrue("完整显示那一档必须打出留边", label.contains("留边="))
    }

    @Test
    fun labelHelpersGiveReadableNumbers() {
        assertEquals("3.14:1", PhotoBitmap.ratioLabel(226, 72))
        assertEquals("6.71:1", PhotoBitmap.ratioLabel(349, 52))
        assertEquals("?", PhotoBitmap.ratioLabel(0, 52))
        assertEquals("245KB", PhotoBitmap.sizeLabel(251_258))
        assertEquals("219KB", PhotoBitmap.sizeLabel(224_532))
    }

    @Test
    fun fallbackFrameRatioIsTwoToOneAndSaysSoInTheLog() {
        // 取不到组件尺寸时（部分启动器不上报 options / 被"强制隐藏"压成 0）用兜底比例，
        // 而且日志里必须一眼看得出来这是兜底、不是实测值
        val f = PhotoCrop.frame(0, 0)
        assertEquals(2, f.widthDp)
        assertEquals(1, f.heightDp)
        assertTrue(f.fallback)
        assertTrue("日志里要标出兜底：${f.label}", f.label.contains("兜底"))
        // 有尺寸时绝不能标成兜底
        assertFalse(PhotoCrop.frame(226, 72).fallback)
    }

    // ---------------------------------------------------------------- 7. 渲染链路与布局必须同源

    @Test
    fun layoutScaleTypeIsCentredSoBothBranchesStayUntouchedByTheHost() {
        // 产出永远是"宽 = 框宽、高 ≤ 框高"：
        //  · 取中间块 → 位图逐值等于框 → fitCenter 是恒等变换；
        //  · 完整显示 → 位图宽等于框宽、高 ≤ 框高 → fitCenter 的缩放因子 = min(1, ≥1) = 1。
        // 所以布局里必须写 fitCenter。写 centerCrop 的话，"完整显示"那一档会被宿主
        // 重新放大铺满、把左右裁掉 —— 正是用户抱怨的"显示不全"。
        val layout = repoFile("src/main/res/layout/widget_today.xml")
            ?: error("找不到 widget_today.xml（测试工作目录假设有变）")
        val xml = layout.readText()
        val scale = Regex("android:id=\"@\\+id/widget_photo_image\"[\\s\\S]{0,400}?android:scaleType=\"(\\w+)\"")
            .find(xml)?.groupValues?.get(1)
        assertEquals("布局里 widget_photo_image 的 scaleType", "fitCenter", scale)
        // 留边（完整显示那一档的上下空档）靠的是**图片框自己的圆角背景**，不新增 drawable
        assertTrue(
            "图片框必须保留背景（留边靠它显色）",
            Regex("android:id=\"@\\+id/widget_photo_area\"[\\s\\S]{0,400}?android:background=\"@drawable/\\w+\"")
                .containsMatchIn(xml)
        )
        // 图片区仍是"整块可点换下一张"的那一层
        assertTrue(
            "图片区应当是 FrameLayout（点击挂在它上面）",
            Regex("FrameLayout[\\s\\S]{0,300}?@\\+id/widget_photo_area").containsMatchIn(xml)
        )
    }

    @Test
    fun noOneSetsScaleTypeAtRuntimeAnyMore() {
        // 布局已经写死 fitCenter（对两条分支都是恒等/零裁变换），运行时再 setInt("setScaleType")
        // 就是纯冗余：多一条 RemoteViews 指令、多一个"宿主不支持"的失败模式。这一轮删掉了它，
        // 这条测试防止它被误加回来（加了就必须同时改布局与这条断言）。
        val dir = listOf(
            File("src/main/java/app/timetable/widget"),
            File("app/src/main/java/app/timetable/widget")
        ).firstOrNull { it.isDirectory } ?: error("找不到 widget 源码目录（测试工作目录假设有变）")
        val files = dir.listFiles { f -> f.name.endsWith(".kt") }?.toList().orEmpty()
        assertTrue("应当能找到 widget 包下的源码（否则这个测试是假绿）", files.size >= 5)
        for (f in files) {
            assertFalse(
                "${f.name} 里还在运行时改 scaleType（布局已写 fitCenter，不需要）",
                f.readText().contains("setScaleType")
            )
        }
    }

    @Test
    fun whatIsNotCoveredHere() {
        // 这份文件里**没有**、也不可能有的东西（写下来免得下次误以为覆盖了）：
        //  1. 真 Bitmap 的裁剪 + 缩放（PhotoBitmap.slice / scaleTo）：需要真 Android 运行时；
        //  2. `inPreferredConfig` 会不会被解码器忽略、`copy(RGB_565)` 会不会失败：需要真机
        //     （真机日志里的 `config=` 与 `byteCount=` 就是为这两件事准备的判据）；
        //  3. RemoteViews.setImageViewBitmap 能不能过 binder：需要真机（日志里的 `set=ok`）。
        assertTrue("本用例只是一条书面说明", true)
    }
}
