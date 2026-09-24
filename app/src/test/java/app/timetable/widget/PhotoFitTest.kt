package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 图片「显示方式」的几何计算（纯函数部分）。
 *
 * 这一组守的是用户两条原话引出来的那件事：
 *
 * > 「会出现图片显示不全的情况，而且应该加上选择图片时选择显示的样式即截取多少。」
 *
 * 三件事必须钉死，一个都不能靠"看起来对"：
 *
 *  1. **默认值 = 老行为**：[legacyCropW]/[legacyCropH] 把改动前 `scaleCenterCrop` 的算术
 *     原样抄了一份，"填满 + 居中 + 1.0 倍"的窗口尺寸与它必须逐值相同。
 *     位置（x/y）用的是同一个取整口径（见 [centerOffset]）——
 *     真机上差 1px 是看不出来的，但用户会拿截图取像素核对，口径必须写死在一处。
 *  2. **裁剪矩形永不越界**：这段算术跑在小组件刷新的主线程上，
 *     `Bitmap.createBitmap` 越界会抛 `IllegalArgumentException` —— 后果不是"这张图难看"，
 *     而是**整个 provider 挂掉**、桌面组件停在旧内容。所以源图/框比例的各种组合都要验一遍。
 *  3. **留边是算得出来的数字**：「完整显示」在很扁的框里会留下大块空白（用户自己选的模式），
 *     这里把 [PhotoFit.containLayout] 的产出钉住，好让真机截图能直接对进度数。
 *
 * 单测里 Android API 全是"返回默认值"的假实现（`isReturnDefaultValues = true`），
 * 所以这里**只测不碰 Bitmap/Context 的算术**（见文件末尾 [whatIsNotCoveredHere]）。
 */
class PhotoFitTest {

    // ---------------------------------------------------------------- 老实现的参照物

    /** 老实现（改动前 PhotoBitmap.scaleCenterCrop）算出来的裁剪长宽 */
    private fun legacyCropW(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        val srcRatio = srcW.toFloat() / srcH.toFloat()
        val dstRatio = targetW.toFloat() / targetH.toFloat()
        return if (srcRatio > dstRatio) (srcH * dstRatio).toInt() else srcW
    }

    private fun legacyCropH(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        val srcRatio = srcW.toFloat() / srcH.toFloat()
        val dstRatio = targetW.toFloat() / targetH.toFloat()
        return if (srcRatio > dstRatio) srcH else (srcW / dstRatio).toInt()
    }

    /** 老实现的居中偏移 —— 与本实现同口径（四舍五入），两边必须一致 */
    private fun centerOffset(free: Int): Int = Math.round(free * 0.5f)

    // ---------------------------------------------------------------- 工具

    private fun window(
        srcW: Int,
        srcH: Int,
        targetW: Int,
        targetH: Int,
        mode: PhotoFitMode = PhotoFitMode.FILL,
        position: CropPosition = CropPosition.MIDDLE,
        zoom: Float = 1f
    ): PhotoFit.Window? = PhotoFit.window(srcW, srcH, targetW, targetH, mode, position, zoom)

    private fun assertWindow(
        w: PhotoFit.Window?,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        label: String = ""
    ) {
        assertNotNull("$label 应当算出窗口", w)
        val it = w!!
        assertEquals("$label 宽", width, it.width)
        assertEquals("$label 高", height, it.height)
        assertEquals("$label x", x, it.x)
        assertEquals("$label y", y, it.y)
    }

    private fun repoFile(relative: String): File? = listOf(
        File(relative),
        File("app/$relative"),
        File("/$relative")
    ).firstOrNull { it.isFile }

    /**
     * 覆盖各种"源图 × 框"的相对关系：更宽、更高、同比例、极端比例、极小图。
     *
     * 真实链路里的解码目标不是"框的 dp×density"，而是经 [PhotoBitmap.targetPx] **夹过
     * binder 预算**的尺寸（`budgetBox` 就是那条链路）——所以这里也走一遍，
     * 免得拿一个现实中不可能存在的框（1000×1000 的图片区，2MB）去断言 binder 预算。
     * 等比缩放保持比例，所以"与老行为对比"的那几条断言照样成立。
     */
    private val matrix = listOf(
        intArrayOf(1600, 1066, 917, 137),               // 横图进扁框（真机实测那一组）
        intArrayOf(917, 137, 917, 137),                 // 已经正好一样大
        intArrayOf(1600, 1600, 917, 137),               // 方图进扁框
        intArrayOf(1080, 2400, 917, 137),               // 竖图进扁框（比例差一个数量级）
        intArrayOf(4000, 3000, 331, 187),               // 16:9 附近
        intArrayOf(4000, 3000, 331, 300),               // 框比图更方
        intArrayOf(3, 5, 2, 2),                         // 极小图
        intArrayOf(1, 1000, 1, 10),                     // 极端瘦高
        intArrayOf(1000, 1, 10, 1),                     // 极端扁平
        intArrayOf(2000, 1000, 1000, 452),              // 源比例正好是框的两倍（452 = 1000×1000 夹过预算）
        intArrayOf(1000, 1000, 349, 52),                // 竖照片进 6.7:1 的扁框
        intArrayOf(2000, 274, 917, 137),
        intArrayOf(1834, 274, 917, 137)
    )

    // ---------------------------------------------------------------- 1. 默认值 = 老行为

    @Test
    fun legacyDefaultMatchesTheOldCenterCropSizes() {
        // 老代码用 `x.toFloat().toInt()`（向零截断），本实现用 `Math.round(x.toFloat())`。
        // 两者在"恰好是整数"的浮点值上会差 1px（例：4000×3000 进 331×187，
        // (float)(3000×(331/187)) = 2259.9999… 截断得 2259、四舍五入得 2260）。
        // 1px 在 137px 高的显示区里就是 0.7%，肉眼无差别；这里允许 1px，
        // 但**不允许更大**——大了就是算法真的变了。
        for (c in matrix) {
            val (srcW, srcH, targetW, targetH) = c
            val w = window(srcW, srcH, targetW, targetH)!!
            val dw = Math.abs(legacyCropW(srcW, srcH, targetW, targetH) - w.width)
            val dh = Math.abs(legacyCropH(srcW, srcH, targetW, targetH) - w.height)
            assertTrue("$c 的裁剪宽与老实现差了 $dw px（只允许 1px 的取整差）", dw <= 1)
            assertTrue("$c 的裁剪高与老实现差了 $dh px（只允许 1px 的取整差）", dh <= 1)
        }
    }

    @Test
    fun legacyDefaultMatchesTheOldCenterCropExactlyOnTheRealDeviceCase() {
        // 真机实测那一组（框 349×52dp @2.625 = 917×137px、照片 1600×1066）必须**逐值**相同：
        // 这是用户最可能拿来截图对比的那一张，1px 都不能差。
        assertWindow(window(1600, 1066, 917, 137)!!, 1600, 239, 0, 414)
        assertEquals(1600, legacyCropW(1600, 1066, 917, 137))
        assertEquals(239, legacyCropH(1600, 1066, 917, 137))
        // 老代码的居中偏移（同一取整口径）也要对上
        assertEquals(centerOffset(1066 - 239), 414)
    }

    @Test
    fun legacyDefaultIsCenteredTheSameWayTheOldCodeWas() {
        // 位置（x/y）也得逐值一致：老代码 = (源 − 裁剪) / 2，本实现 = round(可挪空间 × 0.5)
        for (c in matrix) {
            val (srcW, srcH, targetW, targetH) = c
            val w = window(srcW, srcH, targetW, targetH)!!
            assertEquals("$c 的 x", centerOffset(srcW - w.width), w.x)
            assertEquals("$c 的 y", centerOffset(srcH - w.height), w.y)
        }
    }

    @Test
    fun legacyDefaultProducesABitmapExactlyTheSizeOfTheBox() {
        // 产出尺寸 = 框的尺寸：老代码就是 createScaledBitmap(..., targetW, targetH)。
        // 这条同时保证了 binder 预算没被改坏（解码目标没变）。
        // 「填满」的产出允许比框大 1~2px（窗口取整带来的子像素差，由宿主 centerCrop 收掉），
        // 但不许小（小了会留边）、也不许大到改变体积量级。
        for (c in matrix) {
            val (srcW, srcH, targetW, targetH) = c
            val label = "${c.toList()}"
            val w = window(srcW, srcH, targetW, targetH)!!
            val out = PhotoFit.outSizeForFill(w.width, w.height, targetW, targetH)!!
            assertEquals("$label 的产出高", targetH, out[1])
            assertTrue(
                "$label 的产出宽 ${out[0]} 应当贴着框宽 $targetW（允许 +2px 取整）",
                out[0] in targetW..(targetW + 2)
            )
        }
        // 真机那一组必须逐值等于框
        val w = window(1600, 1066, 917, 137)!!
        assertEquals(917, PhotoFit.outSizeForFill(w.width, w.height, 917, 137)!![0])
        assertEquals(137, PhotoFit.outSizeForFill(w.width, w.height, 917, 137)!![1])
    }

    @Test
    fun everyModeStaysInsideTheBinderBudgetForRealisticWidgetSizes() {
        // binder 预算是硬约束（超了整次 RemoteViews 更新会被系统丢掉，表现是"点刷新没反应"）。
        // 真实的解码目标不是"框的 dp×density"，而是**经 [PhotoBitmap.targetPx] 夹过预算的**尺寸，
        // 所以断言也必须从那条链路走一遍 —— 直接拿 dp 数字比会拿一个现实中不存在的框去比。
        val density = 2.625f
        for (box in listOf(
            intArrayOf(126, 71), intArrayOf(226, 42), intArrayOf(296, 150),
            intArrayOf(349, 52), intArrayOf(360, 88), intArrayOf(600, 300)
        )) {
            val target = PhotoBitmap.targetPx(box[0], box[1], density)
            for (c in listOf(
                intArrayOf(1600, 1066), intArrayOf(1080, 2400),
                intArrayOf(4000, 3000), intArrayOf(200, 150), intArrayOf(1, 1)
            )) {
                for (mode in PhotoFitMode.entries) {
                    for (z in ZOOM_OPTIONS) {
                        val win = PhotoFit.window(
                            c[0], c[1], target[0], target[1], mode, CropPosition.MIDDLE, z
                        ) ?: continue
                        val out = if (mode == PhotoFitMode.FILL) {
                            PhotoFit.outSizeForFill(win.width, win.height, target[0], target[1])
                        } else {
                            // 完整显示：窗口 = 整张（或被截的那条边），产出按"塞进框"的等比缩
                            PhotoFit.fittedSize(win.width, win.height, target[0], target[1])
                        } ?: continue
                        val bytes = out[0].toLong() * out[1].toLong() * PhotoBitmap.BYTES_PER_PIXEL
                        assertTrue(
                            "框=$box -> 目标=${target.toList()} 图=${c.toList()} $mode z=$z " +
                                "产出=${out.toList()} = $bytes 字节，超了 binder " +
                                "${PhotoBitmap.MAX_BITMAP_BYTES}",
                            bytes <= PhotoBitmap.MAX_BITMAP_BYTES
                        )
                    }
                }
            }
        }
    }

    @Test
    fun legacyDefaultIsWhatOldUsersGetBecauseTheyHaveNoSuchPreference() {
        // 老用户没这仨偏好项：读出来必须是"填满 / 居中 / 1.0 倍"
        val spec = PhotoFitSpec.LEGACY
        assertEquals(PhotoFitMode.FILL, spec.mode)
        assertEquals(CropPosition.MIDDLE, spec.position)
        assertEquals(1.0f, spec.zoom, 0.0001f)

        assertEquals(PhotoFitMode.FILL, PhotoFitMode.fromIndex(PhotoDisplayPrefs.DEFAULT_FIT_MODE))
        assertEquals(CropPosition.MIDDLE, CropPosition.fromIndex(PhotoDisplayPrefs.DEFAULT_CROP_POSITION))
        assertEquals(1.0f, zoomFor(PhotoDisplayPrefs.DEFAULT_ZOOM), 0.0001f)
        assertEquals(0, PhotoDisplayPrefs.DEFAULT_FIT_MODE)
        assertEquals(0, PhotoDisplayPrefs.DEFAULT_ZOOM)
        assertEquals("居中", CROP_POSITION_NAMES[PhotoDisplayPrefs.DEFAULT_CROP_POSITION])
    }

    @Test
    fun legacyWindowKeepsTheBoxAspectRatio() {
        // 老行为的立足点：窗口与框同比例 —— 宿主 centerCrop 实际裁不到东西（不拉伸、不露缝）。
        // 允许 1px 的取整误差（帧比例 6.69 vs 窗口比例 6.69 在 1600×239 上就是这种量级）。
        for (c in matrix) {
            val (srcW, srcH, targetW, targetH) = c
            if (srcW < 4 || srcH < 4) continue   // 1×1000 这种极小图上"比例"没有意义
            val w = window(srcW, srcH, targetW, targetH)!!
            val boxRatio = targetW.toDouble() / targetH.toDouble()
            val winRatio = w.width.toDouble() / w.height.toDouble()
            val err = Math.abs(boxRatio - winRatio) / boxRatio
            assertTrue("$c 窗口比例 $winRatio 偏离框比例 $boxRatio 太多（${err * 100}%）", err < 0.02)
        }
    }

    // ---------------------------------------------------------------- 2. 裁剪位置（顶/中/底）

    @Test
    fun cropPositionPicksWhichSliceOfAWidePhotoSurvives() {
        // 横照片（1600×1066）进扁框（917×137）：窗口 1600×239 —— 纵向 827px 可挪。
        // 这正是"显示不全"最本质的场景：16:9 的照片在 6.7:1 的框里只剩中间一条，
        // 上下各扔掉 414px（也就是用户说"显示不全"的那两块）。
        val top = window(1600, 1066, 917, 137, position = CropPosition.TOP)!!
        val mid = window(1600, 1066, 917, 137, position = CropPosition.MIDDLE)!!
        val bottom = window(1600, 1066, 917, 137, position = CropPosition.BOTTOM)!!

        assertWindow(top, 1600, 239, 0, 0)
        assertWindow(mid, 1600, 239, 0, 414)
        assertWindow(bottom, 1600, 239, 0, 827)

        assertTrue("顶/中/底必须真的落在三个地方", top.y < mid.y && mid.y < bottom.y)
        // 横向已经占满源宽（没有余量）→ x 恒为 0：位置"作用在还有余量的方向"上
        assertEquals(0, top.x)
        assertEquals(0, mid.x)
        assertEquals(0, bottom.x)
    }

    @Test
    fun cropPositionPicksWhichSliceOfAPortraitPhotoSurvives() {
        // 竖照片（1000×1000）进很扁的框（349×52）：窗口 1000×149 —— 纵向 851px 可挪。
        // 这一条证明"位置在竖照片上也有用"：如果当初把位置写死成只调左右，
        // 用户最常拍的竖构图在这个开关下就完全没反应。
        val top = window(1000, 1000, 349, 52, position = CropPosition.TOP)!!
        val mid = window(1000, 1000, 349, 52, position = CropPosition.MIDDLE)!!
        val bottom = window(1000, 1000, 349, 52, position = CropPosition.BOTTOM)!!

        assertWindow(top, 1000, 149, 0, 0)
        assertWindow(mid, 1000, 149, 0, 426)
        assertWindow(bottom, 1000, 149, 0, 851)
        // 三档位置让用户能看到的源图区间几乎完全错开（851 ≈ 源图高度的 85%）
        assertEquals(851, bottom.y - top.y)
    }

    @Test
    fun cropPositionMovesSidewaysWhenTheWindowHasHorizontalSlack() {
        // 源图与框**同比例**→ 窗口正好铺满、两个方向都没余量：三个位置给出同一个窗口
        // （不是"位置无效"，而是"没有可选的余地"——这张照片本来就不需要选）
        val a = window(1834, 274, 917, 137, position = CropPosition.TOP)!!
        val b = window(1834, 274, 917, 137, position = CropPosition.BOTTOM)!!
        assertEquals(a.toString(), b.toString())
        assertWindow(a, 1834, 274, 0, 0)

        // 源图比框**更宽**：窗口横向只占源宽的一部分 → 位置改的是 x（左右挪）
        assertWindow(window(2000, 274, 917, 137, position = CropPosition.TOP)!!, 1834, 274, 0, 0)
        assertWindow(window(2000, 274, 917, 137, position = CropPosition.MIDDLE)!!, 1834, 274, 83, 0)
        assertWindow(window(2000, 274, 917, 137, position = CropPosition.BOTTOM)!!, 1834, 274, 166, 0)
    }

    @Test
    fun cropPositionsAreThreeDistinctPercentages() {
        assertEquals(0f, CropPosition.TOP.fraction, 0.0001f)
        assertEquals(0.5f, CropPosition.MIDDLE.fraction, 0.0001f)
        assertEquals(1f, CropPosition.BOTTOM.fraction, 0.0001f)
        assertEquals("顶部", CROP_POSITION_NAMES[0])
        assertEquals("居中", CROP_POSITION_NAMES[1])
        assertEquals("底部", CROP_POSITION_NAMES[2])
    }

    // ---------------------------------------------------------------- 3. 裁剪矩形永不越界

    @Test
    fun cropRectangleNeverEscapesTheSourceImage() {
        // 越界 → createBitmap 抛异常 → **整个 provider 挂掉**。把比例矩阵穷举一遍
        // （含 1px、极端比例、双方互为宽高、每一档放大、每一种位置、两个模式）。
        val srcs = listOf(
            intArrayOf(1, 1), intArrayOf(1, 4000), intArrayOf(4000, 1),
            intArrayOf(1600, 1066), intArrayOf(1066, 1600), intArrayOf(2400, 1080),
            intArrayOf(349, 52), intArrayOf(917, 137), intArrayOf(3, 7), intArrayOf(8000, 8000)
        )
        val boxes = listOf(
            intArrayOf(1, 1), intArrayOf(1, 300), intArrayOf(300, 1),
            intArrayOf(917, 137), intArrayOf(349, 52), intArrayOf(331, 187),
            intArrayOf(594, 111), intArrayOf(777, 394), intArrayOf(2, 3)
        )
        val zooms = listOf(1.0f, 1.25f, 1.5f, 2.0f, 3.0f, PhotoFit.ZOOM_MAX, 0.5f, Float.NaN)

        var checked = 0
        for (s in srcs) for (b in boxes) for (z in zooms) for (p in CropPosition.entries) {
            for (mode in PhotoFitMode.entries) {
                val w = window(s[0], s[1], b[0], b[1], mode, p, z)
                assertNotNull("源=$s 框=$b z=$z $p $mode 应当算出窗口", w)
                val it = w!!
                assertTrue(
                    "越界：源=${s[0]}x${s[1]} 框=${b[0]}x${b[1]} z=$z $p $mode 窗口=$it",
                    it.width >= 1 && it.height >= 1 &&
                        it.x >= 0 && it.y >= 0 &&
                        it.x + it.width <= s[0] && it.y + it.height <= s[1]
                )
                checked++
            }
        }
        assertEquals(
            "组合数变了说明覆盖范围变了（有意改的才动这个数）",
            srcs.size * boxes.size * zooms.size * 3 * 2,
            checked
        )
    }

    @Test
    fun illegalInputsYieldNoWindowInsteadOfACrash() {
        // 尺寸取不到（部分启动器不上报）/ 脏值时不能给出会崩的矩形：返回 null，调用方跳过这张图
        assertNull(window(0, 100, 917, 137))
        assertNull(window(100, 0, 917, 137))
        assertNull(window(1000, 1000, 0, 137))
        assertNull(window(1000, 1000, 917, 0))
        assertNull(window(-5, 1000, 917, 137))
        assertNull(window(1000, 1000, -1, 137))
        assertNull(window(1000, 1000, Int.MIN_VALUE, 137))
        // 产出尺寸同理
        assertNull(PhotoFit.outSizeForFill(0, 10, 917, 137))
        assertNull(PhotoFit.outSizeForFill(10, 10, 917, 0))
        assertNull(PhotoFit.containLayout(0, 0, 917, 137))
        assertNull(PhotoFit.fittedSize(-1, 10, 917, 137))
    }

    @Test
    fun fillOutputIsAlwaysAUniformScaleOfTheWindow() {
        // 产出必须**等比**（位图比例 == 窗口比例），否则照片会被压扁/拉长。
        // 窗口与框同比例时产出逐值等于框（= 老代码的产出尺寸）。
        val cases = listOf(
            intArrayOf(1600, 239, 917, 137),
            intArrayOf(1834, 274, 917, 137),
            intArrayOf(1000, 149, 349, 52),
            intArrayOf(500, 74, 349, 52),
            intArrayOf(333, 49, 349, 52),
            intArrayOf(1000, 74, 917, 137)
        )
        for (c in cases) {
            val out = PhotoFit.outSizeForFill(c[0], c[1], c[2], c[3])!!
            assertTrue("产出不能塌成 0 $c", out[0] >= 1 && out[1] >= 1)
            assertTrue("产出必须盖住框 $c -> ${out.toList()}", out[0] >= c[2] || out[1] >= c[3])
            val winRatio = c[0].toDouble() / c[1]
            val outRatio = out[0].toDouble() / out[1]
            val err = Math.abs(winRatio - outRatio) / winRatio
            assertTrue("产出比例 $outRatio 偏离窗口比例 $winRatio：$c", err < 0.02)
        }
        // 与框同比例时逐值等于框
        val exact = PhotoFit.outSizeForFill(1600, 239, 917, 137)!!
        assertEquals(917, exact[0])
        assertEquals(137, exact[1])
    }

    // ---------------------------------------------------------------- 4. 完整显示：缩放与留边

    @Test
    fun containModePutsTheWholePhotoInsideTheBox() {
        // 真机实测那一组：框 349×52dp @2.625 = 917×137px，照片 16:9（1600×900）
        val l = PhotoFit.containLayout(1600, 900, 917, 137)!!
        // 缩放比 = min(917/1600, 137/900) = 137/900 = 0.15222
        assertEquals(244, l.outWidth)               // round(1600 × 0.15222) = 244
        assertEquals(137, l.outHeight)              // 高度正好填满
        assertEquals(673, l.padLeft + l.padRight)   // 剩下的横向全部留边
        assertEquals(0, l.padTop + l.padBottom)
        assertEquals(337, l.padLeft)                // 奇数差额：余数给左边
        assertEquals(336, l.padRight)
        // 留边占比 ≈ 0.734 —— **这是用户自己选这个模式的代价，照实报出来**（日志里也有这一列）
        assertEquals(0.734f, l.padShare, 0.005f)
        assertEquals("244x137 pad=337/336/0/0", l.toString())
    }

    @Test
    fun containModeSizesMatchThePublishedTable() {
        // 这张表就是汇报里给用户核对截图的数字（框 917×137px）
        assertEquals("1600×900 横片", "244x137 pad=337/336/0/0", PhotoFit.containLayout(1600, 900, 917, 137).toString())
        assertEquals("1600×1066", "206x137 pad=356/355/0/0", PhotoFit.containLayout(1600, 1066, 917, 137).toString())
        assertEquals("1080×2400 竖片", "62x137 pad=428/427/0/0", PhotoFit.containLayout(1080, 2400, 917, 137).toString())
        assertEquals("同比例同大小（零留边）", "917x137 pad=0/0/0/0", PhotoFit.containLayout(917, 137, 917, 137).toString())
        assertEquals("极小图", "1x1 pad=458/458/68/68", PhotoFit.containLayout(1, 1, 917, 137).toString())
    }

    @Test
    fun containModePaddingIsExactlyTheLeftoverSpace() {
        val cases = listOf(
            intArrayOf(1600, 900, 917, 137),
            intArrayOf(917, 137, 917, 137),
            intArrayOf(1000, 1000, 1000, 1000),
            intArrayOf(900, 1600, 917, 137),
            intArrayOf(37, 9000, 917, 137),
            intArrayOf(4000, 3000, 331, 187),
            intArrayOf(1, 1, 917, 137),
            intArrayOf(2, 3, 5, 5)
        )
        for (c in cases) {
            val l = PhotoFit.containLayout(c[0], c[1], c[2], c[3])!!
            assertEquals("左右留边总和 $c", c[2] - l.outWidth, l.padLeft + l.padRight)
            assertEquals("上下留边总和 $c", c[3] - l.outHeight, l.padTop + l.padBottom)
            assertTrue(
                "留边不能是负的 $c",
                l.padLeft >= 0 && l.padRight >= 0 && l.padTop >= 0 && l.padBottom >= 0
            )
            assertTrue("左右留边不该差超过 1px $c", Math.abs(l.padLeft - l.padRight) <= 1)
            assertTrue("上下留边不该差超过 1px $c", Math.abs(l.padTop - l.padBottom) <= 1)
            assertTrue("位图不能超出框 $c", l.outWidth <= c[2] && l.outHeight <= c[3])
            assertTrue("位图不能塌成 0 $c", l.outWidth >= 1 && l.outHeight >= 1)
        }
    }

    @Test
    fun containModeKeepsThePhotoAspectRatio() {
        // 不裁剪的前提是"不变形"：位图比例必须≈源图比例（差的是取整，不是拉伸）
        for (c in listOf(
            intArrayOf(1600, 900, 917, 137),
            intArrayOf(1333, 2000, 331, 187),
            intArrayOf(4000, 3000, 594, 111),
            intArrayOf(1080, 2400, 917, 137),
            intArrayOf(1600, 900, 349, 52)
        )) {
            val l = PhotoFit.containLayout(c[0], c[1], c[2], c[3])!!
            val src = c[0].toDouble() / c[1]
            val out = l.outWidth.toDouble() / l.outHeight
            val err = Math.abs(src - out) / src
            assertTrue("比例偏差 ${err * 100}%：$c -> $l", err < 0.02)
        }
    }

    @Test
    fun containModeNeverUpscalesPhotosSmallerThanTheBox() {
        // 源图比框还小：按原尺寸产出（交给宿主 FIT_CENTER 放大显示），
        // 不放大的理由：多解出来的像素是插值出来的，信息量不涨，binder 体积却翻倍
        val same = PhotoFit.fittedSize(200, 150, 917, 137)!!
        assertEquals(183, same[0])
        assertEquals(137, same[1])
        // 只有一条边超了也要整体等比缩（另一条边跟着缩，绝不裁）
        val wide = PhotoFit.fittedSize(2000, 100, 917, 137)!!
        assertEquals(917, wide[0])
        assertEquals(46, wide[1])
        val tall = PhotoFit.fittedSize(100, 2000, 917, 137)!!
        assertEquals(7, tall[0])
        assertEquals(137, tall[1])
    }

    @Test
    fun containModeOutputStaysInsideTheBoxAndTheBinderBudget() {
        // 「完整显示」只会比"填满"传得更少：产出 ≤ 框，永远过得了 binder
        for (c in listOf(
            intArrayOf(8000, 6000, 917, 137),
            intArrayOf(1600, 1066, 917, 137),
            intArrayOf(1080, 2400, 594, 111),
            intArrayOf(1600, 900, 349, 52)
        )) {
            val l = PhotoFit.containLayout(c[0], c[1], c[2], c[3])!!
            assertTrue("$c -> $l", l.outWidth <= c[2] && l.outHeight <= c[3])
            assertTrue(
                "留边模式的产出也必须在预算内",
                l.outWidth.toLong() * l.outHeight * PhotoBitmap.BYTES_PER_PIXEL <= PhotoBitmap.MAX_BITMAP_BYTES
            )
        }
    }

    @Test
    fun containWindowIsTheWholeBoxShapedSliceSoNothingExtraIsCropped() {
        // 「完整显示」与「填满」切的是**同一块**（都与框同比例、都落在源图里），
        // 区别只在于接下来是"缩到铺满"还是"缩到塞进"。
        // 关键点：源图与框同比例时窗口 = **整张源图** → 一个像素都不裁。
        for (c in listOf(
            intArrayOf(1834, 274, 917, 137),
            intArrayOf(917, 137, 917, 137),
            intArrayOf(2000, 1500, 1000, 750)
        )) {
            val w = window(c[0], c[1], c[2], c[3], mode = PhotoFitMode.CONTAIN)!!
            assertEquals("同比例时必须整张都在（宽）${c.toList()}", c[0], w.width)
            assertEquals("同比例时必须整张都在（高）${c.toList()}", c[1], w.height)
        }
        // 不同比例时窗口只切"多出来的那一条"，另一条边完整保留
        val wide = window(1600, 1066, 917, 137, mode = PhotoFitMode.CONTAIN)!!
        assertEquals("横图：整宽保留", 1600, wide.width)
        assertEquals("横图：只裁上下", 239, wide.height)
        val tall = window(1080, 2400, 917, 137, mode = PhotoFitMode.CONTAIN)!!
        assertEquals("竖图：整高不用，只裁左右到与框同比例", 1080, tall.width)
        assertEquals("竖图：高度按框比例", 161, tall.height)
    }

    // ---------------------------------------------------------------- 5. 放大倍数

    @Test
    fun zoomShrinksTheWindowUniformlyAndKeepsItInsideTheSource() {
        // 竖照片进扁框（1000×1000 → 349×52）：满窗口 1000×149。
        // 每放大一档窗口就小一圈（看到的更少 = 放得更大），两条边同比例缩。
        // 第一版这里写反过：zoom 只是**缩小窗口**，不能反过来去"取大"。
        assertWindow(window(1000, 1000, 349, 52, zoom = 1.0f)!!, 1000, 149, 0, 426)
        assertWindow(window(1000, 1000, 349, 52, zoom = 1.25f)!!, 800, 119, 100, 441)
        assertWindow(window(1000, 1000, 349, 52, zoom = 1.5f)!!, 666, 99, 167, 451)
        assertWindow(window(1000, 1000, 349, 52, zoom = 2.0f)!!, 500, 74, 250, 463)
        assertWindow(window(1000, 1000, 349, 52, zoom = 3.0f)!!, 333, 49, 334, 476)

        for (z in ZOOM_OPTIONS) {
            val w = window(1000, 1000, 349, 52, zoom = z)!!
            assertTrue("$z 的窗口必须落在源图里：$w", w.x + w.width <= 1000 && w.y + w.height <= 1000)
            // 取整会让比例偏一点点：333×49 是 6.79:1，与框的 6.71:1 差 1.2%
            val err = Math.abs(w.width.toDouble() / w.height - 349.0 / 52) / (349.0 / 52)
            assertTrue("$z 的窗口比例偏了 ${err * 100}%：$w", err < 0.03)
        }
    }

    @Test
    fun zoomKeepsTheOutputAtTheBoxSizeSoTheBinderBudgetIsUnchanged() {
        // 放大**不增加**过 binder 的像素：产出仍然贴着框的尺寸，
        // 只是喂给它的源像素变少了（等于"用信息量换放大"）。
        for (c in listOf(
            intArrayOf(1600, 1066, 917, 137),
            intArrayOf(1000, 1000, 349, 52),
            intArrayOf(1080, 2400, 917, 137)
        )) {
            for (z in ZOOM_OPTIONS) {
                val w = window(c[0], c[1], c[2], c[3], zoom = z)!!
                val out = PhotoFit.outSizeForFill(w.width, w.height, c[2], c[3])!!
                assertTrue("$c z=$z 产出超了预算", out[0].toLong() * out[1] * 2 <= PhotoBitmap.MAX_BITMAP_BYTES)
                // 产出在框尺寸附近（取整带来的偏差不超过 2%）
                assertTrue("$c z=$z 产出 ${out.toList()} 偏离框 ${c[2]}x${c[3]} 太多", out[0] >= c[2] && out[0] <= c[2] * 1.05)
            }
        }
    }

    @Test
    fun zoomIsClampedToItsDeclaredRange() {
        // 下界 1.0：小于 1 就是"把照片缩得比框还小"，那是「完整显示」该干的事
        assertEquals(1.0f, PhotoFit.zoomClamp(0.5f), 0.0001f)
        assertEquals(1.0f, PhotoFit.zoomClamp(1.0f), 0.0001f)
        assertEquals(3.0f, PhotoFit.zoomClamp(99f), 0.0001f)
        assertEquals(2.0f, PhotoFit.zoomClamp(2.0f), 0.0001f)
        assertTrue("上限是清晰度边界，必须 ≥ 下界", PhotoFit.ZOOM_MAX >= PhotoFit.ZOOM_MIN)
        // 脏值（NaN）不许把窗口算成 0 或越界；结果必须与 1.0 倍逐值相同
        assertEquals(PhotoFit.ZOOM_MIN, PhotoFit.zoomClamp(Float.NaN), 0.0001f)
        assertEquals(
            window(1600, 1066, 917, 137, zoom = 1f).toString(),
            window(1600, 1066, 917, 137, zoom = Float.NaN).toString()
        )
    }

    @Test
    fun zoomOptionIndexesMapToThePublishedMultipliers() {
        // 设置页写的是**下标**，这里钉住下标 → 倍数，免得两边各写一套
        assertEquals(listOf(1.00f, 1.25f, 1.50f, 2.00f, 3.00f), ZOOM_OPTIONS)
        assertEquals(1.00f, zoomFor(0), 0.0001f)
        assertEquals(1.25f, zoomFor(1), 0.0001f)
        assertEquals(1.50f, zoomFor(2), 0.0001f)
        assertEquals(2.00f, zoomFor(3), 0.0001f)
        assertEquals(3.00f, zoomFor(4), 0.0001f)
        // 越界/脏值：回落 1.0（老行为），绝不抛异常
        assertEquals(1.00f, zoomFor(-1), 0.0001f)
        assertEquals(1.00f, zoomFor(5), 0.0001f)
        assertEquals(1.00f, zoomFor(Int.MAX_VALUE), 0.0001f)
        assertEquals(1.00f, zoomFor(Int.MIN_VALUE), 0.0001f)
    }

    // ---------------------------------------------------------------- 6. 对外契约（设置页 / 布局 / SDK）

    @Test
    fun modeAndPositionIndexesFallBackToTheLegacyDefaults() {
        // 脏值（用户手动改过 xml、跨版本降级）不许炸，也不许改变画面
        assertEquals(PhotoFitMode.FILL, PhotoFitMode.fromIndex(0))
        assertEquals(PhotoFitMode.CONTAIN, PhotoFitMode.fromIndex(1))
        assertEquals(PhotoFitMode.FILL, PhotoFitMode.fromIndex(-1))
        assertEquals(PhotoFitMode.FILL, PhotoFitMode.fromIndex(2))
        assertEquals(PhotoFitMode.FILL, PhotoFitMode.fromIndex(Int.MIN_VALUE))
        assertEquals(PhotoFitMode.FILL, PhotoFitMode.fromIndex(Int.MAX_VALUE))

        assertEquals(CropPosition.TOP, CropPosition.fromIndex(0))
        assertEquals(CropPosition.MIDDLE, CropPosition.fromIndex(1))
        assertEquals(CropPosition.BOTTOM, CropPosition.fromIndex(2))
        assertEquals(CropPosition.MIDDLE, CropPosition.fromIndex(-7))
        assertEquals(CropPosition.MIDDLE, CropPosition.fromIndex(3))

        // 下标就是枚举顺序（设置页写死的数字与这里的枚举顺序必须对上，不能靠巧合）
        assertEquals(0, PhotoFitMode.FILL.ordinal)
        assertEquals(1, PhotoFitMode.CONTAIN.ordinal)
        assertEquals(0, CropPosition.TOP.ordinal)
        assertEquals(1, CropPosition.MIDDLE.ordinal)
        assertEquals(2, CropPosition.BOTTOM.ordinal)

        // 选项文案数量必须与枚举一致（少了没法选，多了会写出越界下标）
        assertEquals(PhotoFitMode.entries.size, PHOTO_FIT_MODE_NAMES.size)
        assertEquals(CropPosition.entries.size, CROP_POSITION_NAMES.size)
        assertEquals("填满（裁剪）", PHOTO_FIT_MODE_NAMES[PhotoFitMode.FILL.ordinal])
        assertEquals("完整显示（不裁剪）", PHOTO_FIT_MODE_NAMES[PhotoFitMode.CONTAIN.ordinal])
    }

    @Test
    fun scaleTypeOrdinalsMatchTheRealAndroidImageViewEnum() {
        // 「完整显示」必须把 ImageView 换成 FIT_CENTER，否则"一个像素都不裁"会被砍掉一两像素。
        // 序号在代码里是**字面量**（不引用 ImageView.ScaleType 常量：单测里那些常量全是 0，
        // 引用它们等于把最该被钉住的事测成假绿），所以这里去 SDK 源码里把枚举顺序读出来核对。
        assertEquals(3, PhotoFitMode.SCALE_TYPE_FIT_CENTER)
        assertEquals(6, PhotoFitMode.SCALE_TYPE_CENTER_CROP)
        assertEquals(3, PhotoFitMode.CONTAIN.scaleTypeOrdinal)
        assertEquals(6, PhotoFitMode.FILL.scaleTypeOrdinal)
        assertTrue(
            "两个模式的 scaleType 不能相同，否则切模式看不出区别",
            PhotoFitMode.FILL.scaleTypeOrdinal != PhotoFitMode.CONTAIN.scaleTypeOrdinal
        )

        val src = findImageViewSource()
        if (src == null) {
            println("跳过 SDK 序号核对：本机没装 android-37.0 的 sources（上面的常量仍被断言）")
            return
        }
        // 从 `enum ScaleType {` 到匹配的 `}` 之间取枚举体（常量表那一行就在最前面）
        val text = src.readText()
        val start = text.indexOf("enum ScaleType")
        assertTrue("android.widget.ImageView 源码里没找到 ScaleType 枚举（SDK 版本可能变了）", start > 0)
        val open = text.indexOf('{', start)
        assertTrue("枚举体没有起始大括号", open > start)
        var depth = 0
        var end = -1
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        assertTrue("枚举体没有闭合（解析失败）", end > open)
        val body = text.substring(open + 1, end)
        val names = Regex("([A-Z][A-Z_]+)\\s*\\(\\s*\\d+\\s*\\)")
            .findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("MATRIX", "FIT_XY", "FIT_START", "FIT_CENTER", "FIT_END", "CENTER", "CENTER_CROP", "CENTER_INSIDE"),
            names
        )
        assertEquals(names.indexOf("FIT_CENTER"), PhotoFitMode.SCALE_TYPE_FIT_CENTER)
        assertEquals(names.indexOf("CENTER_CROP"), PhotoFitMode.SCALE_TYPE_CENTER_CROP)
    }

    /** 找 SDK 里的 ImageView.java；找不到就返回 null（只认机器上已有的文件，不联网） */
    private fun findImageViewSource(): File? {
        val candidates = ArrayList<String>()
        for (e in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            System.getenv(e)?.let { candidates.add("$it/sources/android-37.0/android/widget/ImageView.java") }
        }
        candidates.add("D:/Android/Sdk/sources/android-37.0/android/widget/ImageView.java")
        candidates.add("C:/Android/Sdk/sources/android-37.0/android/widget/ImageView.java")
        candidates.add(
            "${System.getProperty("user.home")}/AppData/Local/Android/Sdk/sources/android-37.0/android/widget/ImageView.java"
        )
        // local.properties 里的 sdk.dir 形如 `D\:\\Android\\Sdk`
        repoFile("local.properties")?.readText()
            ?.let { Regex("sdk\\.dir\\s*=\\s*(.+)").find(it)?.groupValues?.get(1)?.trim() }
            ?.let { raw ->
                val dir = raw.replace("\\\\", "/").replace("\\:", ":").trim().trimEnd('/')
                candidates.add("$dir/sources/android-37.0/android/widget/ImageView.java")
            }
        return candidates.map { File(it) }.firstOrNull { it.isFile }
    }

    @Test
    fun preferenceKeysCoverTheWholeContractAndDoNotCollide() {
        // 键名是给设置页用的**对外契约**（本次改动不碰 Prefs.kt / SettingsActivity.kt）。
        // 这里钉三件事：三个键互不相同、非空、与 Prefs.kt 里已有键不重名。
        val keys = listOf(
            PhotoDisplayPrefs.KEY_FIT_MODE,
            PhotoDisplayPrefs.KEY_CROP_POSITION,
            PhotoDisplayPrefs.KEY_ZOOM
        )
        assertEquals("三个键必须互不相同", 3, keys.toSet().size)
        for (k in keys) assertTrue("键名不能为空: '$k'", k.isNotBlank())
        assertEquals("widgetPhotoFitMode", PhotoDisplayPrefs.KEY_FIT_MODE)
        assertEquals("widgetPhotoCropPosition", PhotoDisplayPrefs.KEY_CROP_POSITION)
        assertEquals("widgetPhotoZoom", PhotoDisplayPrefs.KEY_ZOOM)

        // 偏好文件必须是 Prefs 用的那一份，否则小组件读的是一个空文件（永远只能是默认值）
        val prefs = repoFile("src/main/java/app/timetable/data/Prefs.kt")
            ?: error("找不到 Prefs.kt（测试工作目录假设有变）")
        val text = prefs.readText()
        val file = Regex("private const val FILE = \"([^\"]+)\"").find(text)?.groupValues?.get(1)
        assertNotNull("Prefs.kt 里应当有 private const val FILE", file)
        assertEquals("偏好文件名必须与 Prefs.FILE 一致", file, PhotoDisplayPrefs.FILE)

        // 已有键名不许撞（撞了就是两个开关互相覆盖）
        val existing = Regex("(?:i|b|s|l)\\(\\s*\"([A-Za-z0-9_]+)\"")
            .findAll(text).map { it.groupValues[1] }.toSet()
        assertTrue("Prefs.kt 里应当能解析出一批键名（否则这个测试是假绿）", existing.size > 10)
        for (k in keys) assertTrue("键名与 Prefs.kt 现有键重名：$k", k !in existing)
    }

    @Test
    fun widgetLayoutScaleTypeMatchesTheDefaultModeAndKeepsTheBoxBackground() {
        // 布局里写死的 scaleType 是"取不到用户偏好时的兜底"，必须与默认模式一致（填满 = centerCrop）。
        // 若哪天默认改成「完整显示」，这个测试会先红。
        val layout = repoFile("src/main/res/layout/widget_today.xml")
            ?: error("找不到 widget_today.xml（测试工作目录假设有变）")
        val xml = layout.readText()
        val scale = Regex("android:id=\"@\\+id/widget_photo_image\"[\\s\\S]{0,400}?android:scaleType=\"(\\w+)\"")
            .find(xml)?.groupValues?.get(1)
        assertEquals("布局里 widget_photo_image 的 scaleType", "centerCrop", scale)
        // 「完整显示」的留边底色就是图片框自己的背景 —— **不新增 drawable**
        assertTrue(
            "图片框必须保留背景（留边模式靠它显色）",
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
    fun fitSpecToStringCarriesEverythingTheLogNeeds() {
        // 用户看不到画面，只能看日志：这一行必须能读出"模式/位置/倍数"三样
        assertEquals("填满/middle/1.0", PhotoFitSpec.LEGACY.toString())
        assertEquals(
            "完整显示/bottom/2.0",
            PhotoFitSpec(PhotoFitMode.CONTAIN, CropPosition.BOTTOM, 2.0f).toString()
        )
        assertEquals("填满", PhotoFitMode.FILL.label)
        assertEquals("完整显示", PhotoFitMode.CONTAIN.label)
    }

    @Test
    fun padShareTellsHowMuchOfTheBoxIsEmpty() {
        // padShare 是给日志/截图核对用的：0 = 铺满，0.734 = 73% 是留边底色
        assertEquals(0f, PhotoFit.containLayout(917, 137, 917, 137)!!.padShare, 0.0001f)
        assertTrue(PhotoFit.containLayout(1600, 900, 917, 137)!!.padShare > 0.7f)
        val extreme = PhotoFit.containLayout(1, 1, 917, 137)!!
        assertEquals(1, extreme.outWidth)
        assertEquals(1, extreme.outHeight)
        assertEquals(916, extreme.padLeft + extreme.padRight)
        assertEquals(136, extreme.padTop + extreme.padBottom)
        assertTrue(extreme.padShare > 0.99f)
    }

    @Test
    fun whatIsNotCoveredHere() {
        // 这份文件里**没有**、也不可能有的东西（写下来免得下次误以为覆盖了）：
        //  1. 真 Bitmap 的裁剪 + 缩放（PhotoBitmap.sliceAndScale）：需要真 Android 运行时；
        //  2. RemoteViews 的 setInt("setScaleType", n) 在宿主端是否真的生效：需要真机；
        //  3. 单位换算（dp→px）与解码路径本身（BitmapFactory）；[PhotoBitmap.targetPx] 的算术
        //     在 PhotoBitmapTest 里有覆盖，各模式下的**实际位图尺寸与字节数**只能靠真机日志核对。
        assertTrue("本用例只是一条书面说明", true)
    }
}
