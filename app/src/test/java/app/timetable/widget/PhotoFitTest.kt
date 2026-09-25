package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 图片渲染的**几何决策**（纯函数部分）：整张摆在哪 / 背景取哪一块 / binder 预算与日志数字。
 *
 * 这一组守的是用户两句话引出来的那件事：
 *
 * > 「我不是要你有显示倍率，而是导入图片的时候可以自己裁剪，而且在空间足够的时候图片完整显示，
 * >   不够的时候显示自己裁剪的部分，注意组件是几乘几。」
 * > 「下方图片显示依然有被遮住一小部分的问题，你能不能一次性解决」
 *
 * 三件事必须钉死，一个都不能靠"看起来对"：
 *
 *  1. **判据**：[PhotoFit.fitsWhole] 用整数交叉相乘（先转 Long）而不是两个 float 相除相比 ——
 *     "框和图比例完全相等"（= 用户按裁剪框裁出来的图，最常见的情况）必须落进"整张都在"，
 *     浮点误差把它判偏就会平白切掉一条；
 *  2. **产出**：**永远逐值等于框**，而框里是"**整张**照片（contain、居中）"+
 *     "同一张照片的模糊版本补边"（见 [PhotoFit.containRect] / [PhotoFit.centerWindow]）——
 *     照片**一个像素都不许被遮住**，也不许留一条平底色；
 *  3. **永不越界**：照片矩形与背景窗口都完全来自这里，`Bitmap.createBitmap` 越界会抛
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
        // 这个判据现在的职责是"**该裁哪条边**"：成立 = 图比框更宽（或同比例）→ 保住整高、左右各裁一条；
        // 不成立 = 图比框更高/更窄 → 保住整宽、上下各裁一条。
        // 等号这一档（框和图同比例）必须成立 —— 这正是"用户按裁剪框裁出来的图"那种最常见的情况，
        // 判偏了就会平白切掉一条边。
        assertTrue("同比例：窗口 = 整张源图（整张都在）", PhotoFit.fitsWhole(917, 137, 917, 137))
        assertTrue("图比框更宽（9.17:1 进 6.69:1）：保住整高、左右各裁一条", PhotoFit.fitsWhole(917, 100, 917, 137))
        assertTrue("用户按裁剪框（226:72）裁出来、解码成 594×189", PhotoFit.fitsWhole(720, 229, 594, 189))

        // 不成立：框比图更扁 —— 这是相册里最常见的两种图（横屏截图、竖屏截图），
        // 它们会被"保住整宽、上下各裁一条"
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

    // ---------------------------------------------------------------- 2. 比例不一致 → 整张居中 + 模糊底

    @Test
    fun whenItDoesNotFitTheWholePhotoIsCentredAndTheBackdropTakesTheCoverSlice() {
        // 真机那张 1440×3587 的竖屏截图：框 917×137（6.69:1）比图扁得多。这一档产出两样东西：
        //  · **背景窗口** = 整宽 × 按框比例算出的高（1440 ÷ 6.6934 = 215），上下居中 —— 铺满整框；
        //  · **照片本身** = 整张缩进框里（contain）：高顶满 137，宽 = round(137 × 1440/3587) = 55，水平居中。
        // 用户那一句"图片被遮住一小部分"就是上一版在这里"取中间块"造成的 —— 现在整张都在。
        val l = PhotoFit.layout(1440, 3587, boxW, boxH)!!
        assertFalse("比例不一致 → 要画模糊底", l.whole)
        assertEquals("背景窗口 = 覆盖整框的那一块", 1440, l.window.width)
        assertEquals(215, l.window.height)
        assertEquals(0, l.window.x)
        assertEquals(1686, l.window.y)          // (3587 − 215) ÷ 2 = 1686
        // **整张照片落在框里的矩形**：不越界、居中、与源图同比例
        assertEquals("整张的宽", 55, l.photo.width)
        assertEquals("整张的高 = 框高", boxH, l.photo.height)
        assertEquals("水平居中", (boxW - 55) / 2, l.photo.x)
        assertEquals("垂直顶满", 0, l.photo.y)
        assertEquals("产出逐值等于框宽", boxW, l.outWidth)
        assertEquals("产出逐值等于框高", boxH, l.outHeight)
        assertEquals("这一档也没有平底色留白（边界由模糊底填）", 0, l.padY)
        assertEquals("整张(居中+模糊底)", l.verdict)

        // 16:9 的横片同理：背景窗口 1600×239，纵向 661px 里居中 → 331；
        // 整张照片高顶满 137、宽 = round(137 × 1600/900) = 244、水平居中 336
        val wide = PhotoFit.layout(1600, 900, boxW, boxH)!!
        assertEquals(1600, wide.window.width)
        assertEquals(239, wide.window.height)
        assertEquals(331, wide.window.y)
        assertEquals(244, wide.photo.width)
        assertEquals(137, wide.photo.height)
        assertEquals(336, wide.photo.x)
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
        // （2000×274 那一组按"图比框宽"的口径会走**左右裁**，同样不属于"整张都在"；
        //   这里只验窗口比例与产出尺寸这两条对每一组都成立的不变量）
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
            assertEquals("留边恒为 0", 0, l.padY)
        }
    }

    // ---------------------------------------------------------------- 3. 本轮的两条产出路径
    //
    // 规则只有一条：**产出逐值等于框**，框里永远是"整张照片 + 需要时用它的模糊版本补边"。
    // 两种可观察的结果：
    //  · 比例一致 → 照片正好铺满整块，背景都不用画 → "整张(铺满)"；
    //  · 比例不一致（框被拉高/压扁）→ 照片整张居中，四周那几条边是它的模糊版本 → "整张(居中+模糊底)"。

    @Test
    fun whenTheRatiosMatchTheWholePhotoIsShownAndTheBoxIsFilled() {
        // 用户按裁剪框裁出来的图（最常见的一档）：594×189 的框 vs 720×229 的图。
        // 判定不是"浮点相等"，而是**窗口是否等于整张源图** —— dp→px 的取整余量因此被自动吸收：
        // 交叉相乘并不严格相等（720×189 = 136080 vs 594×229 = 136026），
        // 但 round(229 × 594/189) = 720（正好是源宽）、min(229, round(720 ÷ 3.1428)) = 229，
        // 于是窗口取到整张图：一个像素都不裁。
        val l = PhotoFit.layout(720, 229, 594, 189)!!
        assertTrue("比例一致必须走'整张铺满'", l.whole)
        assertEquals("背景窗口 = 整张源图", "720x229@(0,0)", l.window.toString())
        assertEquals("产出逐值等于框宽", 594, l.outWidth)
        assertEquals("产出逐值等于框高", 189, l.outHeight)
        assertEquals("零留边", 0, l.padY)
        assertEquals("整张(铺满)", l.verdict)
        // 这一档根本不需要背景：整张照片正好等于框，连一个像素的背景填充都不会画
        assertEquals("整张照片铺满整框", "594x189@(0,0)", l.photo.toString())
        assertFalse("比例一致 → 不画模糊底", l.backdrop)
    }

    @Test
    fun whenTheBoxIsTallerThanThePhotoTheWholePanoramaIsShownOnABlurredBackdrop() {
        // 9.17:1 的图进 6.7:1 的框（框比图"高"）：上一版在这里"从中间取一块"（左右各裁 124px），
        // 用户看到的就是"照片被遮住了两边"。现在整张都在：宽顶满 917、高 = round(137 × 100/917) = 15，
        // 上下居中在 y = (137 − 15) ÷ 2 = 61，空出来的上下两条边由同一张图的模糊版本填。
        val l = PhotoFit.layout(917, 100, boxW, boxH)!!
        assertFalse("比例不一致 → 整张居中 + 模糊底", l.whole)
        assertEquals(boxW, l.outWidth)
        assertEquals(boxH, l.outHeight)
        assertEquals("一条平底色留白都没有", 0, l.padY)
        assertEquals(0f, l.padShare, 0.0001f)
        assertEquals("整张(居中+模糊底)", l.verdict)
        assertEquals("整张照片：宽顶满、高按原比例", "917x100@(0,18)", l.photo.toString())
        // 背景窗口与框同比例：宽 = round(100 × 917/137) = 669，左右各裁 (917 − 669) ÷ 2 = 124
        assertEquals(669, l.window.width)
        assertEquals(100, l.window.height)
        assertEquals(124, l.window.x)
        assertEquals(0, l.window.y)
    }

    @Test
    fun aSmallerSourceIsScaledUpToTheBoxWidthAndTheBackdropFillsTheRest() {
        // 源图比框小（相册里的缩略图、或用户裁得很小）：整张被放大到框宽（917），
        // 高 = round(137 × 20/200) = 14、上下居中；剩下那两条边由模糊底填 ——
        // 既不是"图只占一小条 + 一片底色"，也不再裁掉任何东西。
        val l = PhotoFit.layout(200, 20, boxW, boxH)!!
        assertFalse(l.whole)
        assertEquals("产出仍是框宽", boxW, l.outWidth)
        assertEquals("产出仍是框高", boxH, l.outHeight)
        assertEquals(0, l.padY)
        assertEquals("整张照片：宽顶满、高按原比例", "917x92@(0,22)", l.photo.toString())
        assertEquals("背景窗口 = 整高 20、宽 round(20 × 6.6934) = 134", 134, l.window.width)
        assertEquals(20, l.window.height)
        assertEquals("居中", 33, l.window.x)
    }

    @Test
    fun theWholePhotoIsAlwaysInsideTheBoxAndNothingIsEverCropped() {
        // 这一条是本轮的核心不变量（用户："下方图片显示依然有被遮住一小部分的问题"），
        // 直接把"照片 × 框"的各种组合扫一遍：
        //  · 产出宽高**逐值等于框**（宿主 fitCenter = 恒等变换，画面完全由我们决定）；
        //  · **整张照片的矩形永远在框里**（不越界）—— 这就是"一个像素都不被遮住"的判据；
        //  · 照片矩形与源图同比例（不拉伸）、居中；
        //  · `留边=0`：照片区里不会出现平底色（铺满时是照片本身，不铺满时是它的模糊版本）。
        val sources = listOf(
            intArrayOf(720, 229), intArrayOf(1600, 900), intArrayOf(1440, 3587),
            intArrayOf(2000, 274), intArrayOf(917, 100), intArrayOf(200, 20),
            intArrayOf(1, 1), intArrayOf(4000, 3000)
        )
        val boxes = listOf(
            intArrayOf(594, 189), intArrayOf(594, 255), intArrayOf(594, 105),
            intArrayOf(917, 137), intArrayOf(331, 187), intArrayOf(594, 449)
        )
        var checked = 0
        for (s in sources) for (b in boxes) {
            val l = PhotoFit.layout(s[0], s[1], b[0], b[1])!!
            assertEquals("源=$s 框=$b：产出宽必须逐值等于框宽", b[0], l.outWidth)
            assertEquals("源=$s 框=$b：产出高必须逐值等于框高", b[1], l.outHeight)
            assertEquals("源=$s 框=$b：不该有任何平底色留白", 0, l.padY)
            // 整张照片必须**完整地落在框里** —— 越界就是"被遮住"
            val p = l.photo
            assertTrue(
                "源=$s 框=$b：整张照片越出框了 photo=$p",
                p.width >= 1 && p.height >= 1 &&
                    p.x >= 0 && p.y >= 0 &&
                    p.x + p.width <= b[0] && p.y + p.height <= b[1]
            )
            // 照片矩形与源图同比例（不拉伸）。退化源图（短边 1~2px）不参与这一条：
            // 那种尺寸下矩形只能是 1px，比例必然对不上（真实照片不可能这么小）。
            if (s[0] >= 3 && s[1] >= 3) {
                val lhs = p.width.toLong() * s[1]
                val rhs = s[0].toLong() * p.height
                assertTrue(
                    "源=$s 框=$b：照片被拉伸了（交叉相乘差 ${Math.abs(lhs - rhs)}）",
                    Math.abs(lhs - rhs) <= maxOf(s[0], s[1]).toLong()
                )
            }
            // 居中：左右/上下余量之差最多 1px（取整）
            val padLeft = p.x
            val padRight = b[0] - p.width - p.x
            val padTop = p.y
            val padBottom = b[1] - p.height - p.y
            assertTrue("源=$s 框=$b：照片水平没居中 $padLeft/$padRight", Math.abs(padLeft - padRight) <= 1)
            assertTrue("源=$s 框=$b：照片竖直没居中 $padTop/$padBottom", Math.abs(padTop - padBottom) <= 1)
            // 背景窗口（画模糊底用的那块）必须与框同比例，否则背景自己就是拉伸的。
            // 判据用**整数交叉相乘 + 1px 量级的容差**，而不是比值 —— 比值的相对误差在小窗口上
            // 会被放大到没意义。
            if (l.backdrop && s[0] >= 3 && s[1] >= 3) {
                val lhs = l.window.width.toLong() * b[1]
                val rhs = b[0].toLong() * l.window.height
                assertTrue(
                    "源=$s 框=$b：背景窗口与框不同比例（交叉相乘差 ${Math.abs(lhs - rhs)}）",
                    Math.abs(lhs - rhs) <= maxOf(b[0], b[1]).toLong()
                )
            }
            checked++
        }
        assertEquals("组合数变了说明覆盖范围变了（有意改的才动这个数）", sources.size * boxes.size, checked)
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
        assertTrue("按裁剪框比例裁出来的图必须走'整张铺满'", l.whole)
        assertEquals(594, l.outWidth)
        assertEquals("产出逐值等于框高（铺满）", 189, l.outHeight)
        assertEquals(0, l.padY)
        assertEquals("整张都在", "720x229@(0,0)", l.window.toString())
        assertEquals("整张照片铺满整框（这一档连背景都不画）", "594x189@(0,0)", l.photo.toString())
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
            assertEquals("产出高必须逐值等于框高", b[1], it.outHeight)
            // 整张照片也必须落在框里 —— 这是"不被遮住"判据的另一半
            val p = it.photo
            assertTrue(
                "整张照片越出框：源=${s[0]}x${s[1]} 框=${b[0]}x${b[1]} photo=$p",
                p.width >= 1 && p.height >= 1 &&
                    p.x >= 0 && p.y >= 0 &&
                    p.x + p.width <= b[0] && p.y + p.height <= b[1]
            )
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
        assertNull(PhotoFit.centerWindow(1000, 1000, boxW, 0))
        // 整张照片那条路同样不能给出会崩/越界的矩形
        assertNull(PhotoFit.containRect(0, 100, boxW, boxH))
        assertNull(PhotoFit.containRect(100, 0, boxW, boxH))
        assertNull(PhotoFit.containRect(1000, 1000, 0, boxH))
        assertNull(PhotoFit.containRect(1000, 1000, boxW, 0))
        assertNull(PhotoFit.containRect(-5, 1000, boxW, boxH))
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
        // 917×137 的 RGB_565 是 251,258 字节，远在预算内 → 一个像素都不动
        val fits = PhotoFit.budgetSize(917, 137, PhotoBitmap.MAX_BITMAP_BYTES, 2)
        assertEquals(917, fits[0])
        assertEquals(137, fits[1])

        // 同一张图的 ARGB_8888（4 字节/像素 = 502,516 字节）：预算放宽到 600KB 之后它也能过，
        // 这是有意的 —— 常见的大组件图需要 916×291×2 = 533KB，预算太紧会把图压小、宿主再放大就糊。
        // 真正的第一道防线是"解码后强制 RGB_565"（把 4 字节/像素变回 2），这条只是最后一道兜底。
        val argbSmall = 917L * 137L * 4L
        assertTrue(
            "600KB 预算下这一档应当能过（否则说明预算又被收紧了）",
            argbSmall <= PhotoBitmap.MAX_BITMAP_BYTES
        )

        // 真·超预算那一档：1000×400 的 ARGB 是 1.6MB，必须按**真实字节数**等比缩
        val shrunk = PhotoFit.budgetSize(1000, 400, PhotoBitmap.MAX_BITMAP_BYTES, 4)
        assertTrue("缩完必须过关：${shrunk[0]}x${shrunk[1]}",
            shrunk[0].toLong() * shrunk[1].toLong() * 4 <= PhotoBitmap.MAX_BITMAP_BYTES)
        assertTrue("比例不能压变形",
            Math.abs(shrunk[0].toFloat() / shrunk[1] - 1000f / 400f) < 0.05f)
        assertTrue("确实缩了", shrunk[0] < 1000)
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
            config = "RGB_565",
            cornerRadiusPx = 26,            // 10dp × 2.625 = 26.25 → 26px（读 drawable 得到）
            cornerFillArgb = 0xFFFFFFFF.toInt(),   // 明色主题：组件根背景（四角填这个色）
            cornerPixelArgb = 0xFFFFFFFF.toInt()   // 遮罩后从角落读回来的实测值（白，对上了）
        )
        val label = PhotoBitmap.renderLabel(
            facts = facts,
            frame = PhotoCrop.frame(349, 136),
            boxWidthDp = 349,
            boxHeightDp = 52,
            // 这张竖屏截图自己"要"的框高：349 ÷ (1440/3587) = 869dp → 撞 150dp 上限。
            // 也就是说这一张永远走"取中间块"（框不可能给它 150dp 以上的高度）
            wantedHeightDp = WidgetData.photoWantedHeightDp(349, 1440f / 3587f),
            rows = 1,
            photoRatioLabel = PhotoBitmap.ratioLabel(1440, 3587),
            leftoverDp = 0
        )
        assertEquals(
            "框=349x52dp 比例=6.71:1 照片比例=0.40:1 需要的框高=150dp " +
                "裁剪框=349:136dp(2.57:1) 源=1440x3587(0.40:1) " +
                "照片=55x137@(431,0) 整张(居中+模糊底) 底窗=1440x215@(0,1686) 行数=1 " +
                "bitmap=917x137 byteCount=251258B(245KB) config=RGB_565 " +
                "圆角=26px 角填色=#FFFFFFFF 角像素=#FFFFFFFF 底部零头=0dp",
            label
        )
        // 这几个 token 是给用户（和以后的我）按图索骥用的，缺一列就少一个判据。
        // `照片=` 是本轮加的：它是**整张照片落在框里的矩形**，用户拿截图量"照片有没有被遮住"
        // 就对这一列（比例不一致那一档打的是 `底窗=`，即模糊底从源图上取的那一块）；
        // `圆角=` / `角填色=` / `角像素=` / `底部零头=` 是上一轮加的（`角像素=` 是遮罩之后
        // **实测读回来**的角落颜色 —— 用户拿截图取像素对的就是它）
        for (token in listOf(
            "框=", "比例=", "照片比例=", "需要的框高=", "裁剪框=", "整张(居中+模糊底)",
            "照片=", "底窗=", "行数=", "bitmap=", "byteCount=", "config=",
            "圆角=", "角填色=", "角像素=", "底部零头="
        )) {
            assertTrue("日志缺少 $token：$label", label.contains(token))
        }
    }

    /**
     * 用户要拿来在真机上核对的那一行（原话："日志请保留形如
     * `框=226x72dp 照片比例=3.14:1 需要的框高=72dp 留边=0px 行数=2` 的一行"）。
     *
     * 这一条把**两条"没有空白"的结论**与日志文本一起钉死：
     *  - `留边=0px(0%)` —— 照片区内部没有留白；
     *  - `底部零头=0dp` —— 图片框下方的组件底部也没有留白（本轮修的那条）。
     * 真机上只要这两列里有一列不是 0，就说明对应的规则被人改回去了。
     */
    @Test
    fun theLineTheUserAskedForIsExact() {
        val aspect = 720f / 229f                                   // 3.14:1
        val box = WidgetData.photoWantedHeightDp(226, aspect)      // 照片需要的框高 = 72dp
        val target = PhotoBitmap.targetPx(226, box, 2.625f)        // 594×189px
        val saved = PhotoCrop.encodeSize(PhotoCrop.frame(226, box), target[0])   // 720×229
        val layout = PhotoFit.layout(saved[0], saved[1], target[0], target[1])!!
        val facts = RenderFacts(
            layout, saved[0], saved[1], target[0], target[1],
            target[0] * target[1] * 2, "RGB_565",
            cornerRadiusPx = 26, cornerFillArgb = 0xFFFFFFFF.toInt(),
            cornerPixelArgb = 0xFFFFFFFF.toInt()
        )

        val label = PhotoBitmap.renderLabel(
            facts = facts,
            frame = PhotoCrop.frame(226, box),
            boxWidthDp = 226,
            boxHeightDp = box,
            wantedHeightDp = box,
            rows = WidgetData.photoRowsFor(187, 226, 0, aspect),
            photoRatioLabel = PhotoBitmap.ratioLabel(saved[0], saved[1]),
            leftoverDp = 0
        )
        assertEquals(
            "框=226x72dp 比例=3.14:1 照片比例=3.14:1 需要的框高=72dp " +
                "裁剪框=226:72dp(3.14:1) 源=720x229(3.14:1) " +
                "照片=594x189@(0,0) 整张(铺满) 留边=0px(0%) 行数=1 " +
                "bitmap=594x189 byteCount=224532B(219KB) config=RGB_565 " +
                "圆角=26px 角填色=#FFFFFFFF 角像素=#FFFFFFFF 底部零头=0dp",
            label
        )
        // 用户要的那几个字段必须原样出现（顺序也一样，方便拿真机日志逐字比对）。
        // 注意实际日志里 `比例=`（**框**的比例）与 `照片比例=`（当前这张图的比例）各占一列 ——
        // 用户示例里只写了后者，前者是既有的列，保留着才能一眼看出"框和图是不是同形"。
        val wanted = listOf(
            "框=226x72dp", "照片比例=3.14:1", "需要的框高=72dp",
            "留边=0px", "行数=1", "底部零头=0dp"
        )
        for (token in wanted) {
            assertTrue("缺少用户要核对的那一段 $token：$label", label.contains(token))
        }
        assertTrue("用户示例里的那一段必须是连着的", label.startsWith("框=226x72dp 比例=3.14:1 照片比例=3.14:1 需要的框高=72dp "))
    }

    @Test
    fun renderLabelSwitchesToTheWholeVerdictAndReportsThePadding() {
        // 完整显示那一档：226:72 的裁剪图，框 226×72dp（594×189px）→ 零留边
        val l = PhotoFit.layout(720, 229, 594, 189)!!
        val facts = RenderFacts(
            l, 720, 229, 594, 189, 594 * 189 * 2, "RGB_565",
            cornerRadiusPx = 26, cornerFillArgb = 0xFFFFFFFF.toInt(),
            cornerPixelArgb = 0xFFFFFFFF.toInt()
        )
        val label = PhotoBitmap.renderLabel(
            facts = facts,
            frame = PhotoCrop.frame(226, 72),
            boxWidthDp = 226,
            boxHeightDp = 72,
            wantedHeightDp = WidgetData.photoWantedHeightDp(226, 720f / 229f),
            rows = 1,
            photoRatioLabel = PhotoBitmap.ratioLabel(720, 229),
            leftoverDp = 0
        )
        assertEquals(
            "框=226x72dp 比例=3.14:1 照片比例=3.14:1 需要的框高=72dp " +
                "裁剪框=226:72dp(3.14:1) 源=720x229(3.14:1) " +
                "照片=594x189@(0,0) 整张(铺满) 留边=0px(0%) 行数=1 " +
                "bitmap=594x189 byteCount=224532B(219KB) config=RGB_565 " +
                "圆角=26px 角填色=#FFFFFFFF 角像素=#FFFFFFFF 底部零头=0dp",
            label
        )
        assertTrue("铺满那一档必须打出留边", label.contains("留边="))
        // 本轮的规则：铺满那一档的留边**就是 0**（铺满 = 照片自己就是整块区域，没有背景填充）
        assertTrue("铺满必须零留边", label.contains("留边=0px(0%)"))
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
        // 产出**逐值等于框**（本轮）：两种结论都一样 → fitCenter 是恒等变换
        //（不缩放、不裁剪、不留边），画面完全由我们这边的算术决定。
        // 写 centerCrop 也一样是恒等变换，但 fitCenter 是"更弱的假设"：
        // 万一哪天位图比框小了一点，centerCrop 会放大着裁、fitCenter 只会居中 —— 前者更难看。
        val layout = repoFile("src/main/res/layout/widget_today.xml")
            ?: error("找不到 widget_today.xml（测试工作目录假设有变）")
        val xml = layout.readText()
        val scale = Regex("android:id=\"@\\+id/widget_photo_image\"[\\s\\S]{0,400}?android:scaleType=\"(\\w+)\"")
            .find(xml)?.groupValues?.get(1)
        assertEquals("布局里 widget_photo_image 的 scaleType", "fitCenter", scale)
        // 图片框仍必须带背景：四个角的遮罩填的是**背后那层**的颜色，而容器自己的圆角
        // 是这条链路的前提（遮罩半径就是读它的 <corners>），去掉背景整件事就失去参照
        assertTrue(
            "图片框必须保留背景（圆角遮罩的半径与它对齐）",
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
        //  2. **四角圆角遮罩那一步画布**（PhotoBitmap.maskCorners 里的 Canvas.drawPath）：
        //     单测里 Canvas/Path 都是"返回默认值"的假实现，只能验它的几何（见 PhotoCornerMaskTest）
        //     与"真的画上去了没有"（真机日志的 `圆角=..px` / `角填色=#..` 两列 + 截图取像素）；
        //  3. `inPreferredConfig` 会不会被解码器忽略、`copy(RGB_565)` 会不会失败：需要真机
        //     （真机日志里的 `config=` 与 `byteCount=` 就是为这两件事准备的判据）；
        //  4. RemoteViews.setImageViewBitmap 能不能过 binder：需要真机（日志里的 `set=ok`）。
        assertTrue("本用例只是一条书面说明", true)
    }
}
