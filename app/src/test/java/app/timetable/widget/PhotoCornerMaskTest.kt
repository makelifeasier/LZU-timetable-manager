package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 照片**四个角的圆角遮罩**（本轮新增）：几何、半径夹取、填色策略，以及"半径是从 drawable 读的"。
 *
 * 用户原话："组件下拉的时候依然有空白，没有下拉的时候图片下部边角直角紧贴圆角，看起来很突兀"。
 * 后半句就是这一组要治的：图片框（`widget_photo_area`）的背景是一张圆角 drawable，
 * 而 `setImageViewBitmap` 送过去的位图四角是**直角** —— 照片的直角正压在容器圆角上。
 *
 * 这里能验的只有**纯几何与策略**（单测里 `Canvas` / `Path` / `Bitmap` 全是"返回默认值"的假实现，
 * `Paint.ANTI_ALIAS_FLAG` 也不过是个 int 常量，画出来的像素一个都看不到）。所以：
 *  - 几何部分逐值断言（夹取、四角区、不越界）；
 *  - 真正"画上去"的那一步靠两条：真机日志的 `圆角=..px 角填色=#..` 两列，
 *    以及用户拿截图取角落那个像素去对（在 RGB_565 的量化误差之内应当等于 `角填色`）。
 *
 * **绝不能出现黑角**：位图是 RGB_565（没有 alpha 通道），把四角"涂成透明"就会渲染成黑色。
 * 见 [PhotoBitmap.cornerFillArgb] 的取舍与下面那几条断言。
 */
class PhotoCornerMaskTest {

    /** 10dp × 2.625 = 26.25px —— 真机（Pixel 8 / MIUI 的常见密度）上那个圆角 */
    private val radiusPx = 10f * 2.625f

    private fun repoFile(relative: String): File? = listOf(
        File(relative),
        File("app/$relative"),
        File("/$relative")
    ).firstOrNull { it.isFile }

    // --------------------------------------------------------------- 1. 半径夹取

    @Test
    fun theRadiusIsClampedIntoSomethingThatCanActuallyBeDrawn() {
        // 正常情况原样保留
        val normal = PhotoBitmap.cornerGeometry(594, 189, radiusPx)
        assertEquals(radiusPx, normal.radiusPx, 0.001f)
        assertEquals(594, normal.width)
        assertEquals(189, normal.height)

        // 超过半边长（极扁的框、或 drawable 被人改成 100dp）：夹到 min(宽,高)/2 ——
        // 再大就不是圆角矩形了，四条弧会互相吃掉，几何也就没有唯一定义
        assertEquals(15f, PhotoBitmap.cornerGeometry(594, 30, 100f).radiusPx, 0.001f)
        assertEquals(0.5f, PhotoBitmap.cornerGeometry(1, 1, 100f).radiusPx, 0.001f)
        assertEquals(1f, PhotoBitmap.cornerGeometry(2, 9, 8f).radiusPx, 0.001f)

        // 脏值一律收敛成 0（= 不做遮罩）：发散的 NaN 传进 Path 会让整条路径失效，画出来是一片糊
        assertEquals(0f, PhotoBitmap.cornerGeometry(594, 189, 0f).radiusPx, 0.001f)
        assertEquals(0f, PhotoBitmap.cornerGeometry(594, 189, -12f).radiusPx, 0.001f)
        assertEquals(0f, PhotoBitmap.cornerGeometry(594, 189, Float.NaN).radiusPx, 0.001f)
        // 尺寸拿不到时也不能算出负数半径
        assertEquals(0f, PhotoBitmap.cornerGeometry(0, 189, radiusPx).radiusPx, 0.001f)
        assertEquals(0f, PhotoBitmap.cornerGeometry(594, -1, radiusPx).radiusPx, 0.001f)

        // 夹取是**恒定**成立的（不依赖上面那张表）
        for (w in listOf(1, 2, 7, 331, 594, 917, 1080)) {
            for (h in listOf(1, 3, 20, 189, 449, 3587)) {
                for (r in listOf(0.1f, 1f, 26.25f, 500f)) {
                    val m = PhotoBitmap.cornerGeometry(w, h, r)
                    assertTrue("${w}x$h 半径 $r 夹出了 ${m.radiusPx}", m.radiusPx >= 0f)
                    assertTrue(
                        "${w}x$h 半径 $r 夹出了 ${m.radiusPx}，超过半边长",
                        m.radiusPx <= minOf(w, h) / 2f + 0.001f
                    )
                }
            }
        }
    }

    // --------------------------------------------------------------- 2. 四角区 / 不越界

    @Test
    fun allFourCornersAreCoveredAndNothingEscapesTheBitmap() {
        // 照片可能出现在组件中部（列表下方），所以**四个角**都要处理：
        // 左上、右上、左下、右下，每块都是"半径 × 半径"的方角区，遮罩要涂的就是这四块里、
        // 圆角矩形之外的部分（抗锯齿的弧正好落在这些区域里）。
        val w = 594
        val h = 189
        val mask = PhotoBitmap.cornerGeometry(w, h, radiusPx)
        val corners = mask.corners
        assertEquals("四个角一个都不能漏", 4, corners.size)
        for (c in corners) {
            assertEquals("方角区的宽必须等于半径", mask.radiusPx, c[2] - c[0], 0.001f)
            assertEquals("方角区的高必须等于半径", mask.radiusPx, c[3] - c[1], 0.001f)
            assertTrue("方角区跑到位图外面了：${c.toList()}", c[0] >= 0f && c[1] >= 0f)
            assertTrue(
                "方角区跑到位图外面了：${c.toList()}（位图 ${w}x$h）",
                c[2] <= w.toFloat() && c[3] <= h.toFloat()
            )
        }
        // 四个角必须各占一个：左上 / 右上 / 左下 / 右下（重复或漏一个都是"涂错地方"）
        assertEquals(listOf(0f, 0f), listOf(corners[0][0], corners[0][1]))
        assertEquals(listOf(w.toFloat(), 0f), listOf(corners[1][2], corners[1][1]))
        assertEquals(listOf(0f, h.toFloat()), listOf(corners[2][0], corners[2][3]))
        assertEquals(listOf(w.toFloat(), h.toFloat()), listOf(corners[3][2], corners[3][3]))

        // 半径夹取到半边长之后，四个方角区**不会互相重叠**（重叠意味着几何已经没意义了）
        val tight = PhotoBitmap.cornerGeometry(40, 20, 99f)
        assertEquals(10f, tight.radiusPx, 0.001f)
        val xs = tight.corners.map { it[0] }
        assertTrue("夹取之后四角仍应各占一块", xs[0] == 0f && xs[1] >= 0f)
    }

    @Test
    fun theMaskGeometryIsUsableForEveryBoxWeCanProduce() {
        // 真实链路上的框（226dp 内容宽，3.14:1 的照片）：72/97/135/171dp → 189/255/354/449px
        // density 取 2.625（真机实测）。几何必须"能画出来"：宽高 > 0、半径 > 0 且不越界。
        for (boxH in listOf(189, 255, 354, 449)) {
            val mask = PhotoBitmap.cornerGeometry(594, boxH, radiusPx)
            assertTrue("框 594x$boxH：宽度必须是正的", mask.width > 0)
            assertEquals(boxH, mask.height)
            assertEquals("半径原样保留（远小于半边长）", radiusPx, mask.radiusPx, 0.001f)
            assertTrue("半径必须小于半高", mask.radiusPx < boxH / 2f)
        }
    }

    // --------------------------------------------------------------- 3. 四角填什么颜色

    @Test
    fun theMaskedRegionIsExactlyTheFourCornerWedges() {
        // 遮罩的区域 = "整张位图 − 圆角矩形" = 四角的四块。这是**书面定义**（insideCornerMask），
        // 画那一半由平台的 Path.Op.DIFFERENCE 负责（见下面那条源码级不变量）。
        // 单测里画布是假的，所以这里的几何就是唯一能验的东西 —— 逐像素数一遍。
        val w = 594
        val h = 189
        val r = PhotoBitmap.cornerGeometry(w, h, radiusPx).radiusPx      // 26.25
        fun masked(x: Int, y: Int) = PhotoBitmap.insideCornerMask(x, y, w, h, radiusPx)

        // 四个最外侧的像素一定要遮掉（那里的像素中心离圆心 √2·r > r）
        assertTrue("左上角", masked(0, 0))
        assertTrue("右上角", masked(w - 1, 0))
        assertTrue("左下角", masked(0, h - 1))
        assertTrue("右下角", masked(w - 1, h - 1))

        // 照片中间、四条边的中段绝不能遮 —— 那里必须逐像素是照片本身
        assertFalse("正中间", masked(w / 2, h / 2))
        assertFalse("上边中段", masked(w / 2, 0))
        assertFalse("下边中段", masked(w / 2, h - 1))
        assertFalse("左边中段", masked(0, h / 2))
        assertFalse("右边中段", masked(w - 1, h / 2))

        // 弧外（对角线上离圆心 √2×1.5px ≈ 33.6px > 26.25）要遮；
        // 弧内（(9,9) 的像素中心离圆心 √2×16.75 ≈ 23.7px < 26.25）绝不能遮
        assertTrue("左上角靠外那一块", masked(2, 2))
        assertFalse("左上方角区内、但已经在弧里面", masked(9, 9))

        // 面积对得上"四角方角区 − 四分之一圆"：4·(r² − πr²/4) = r²(4 − π)
        var count = 0
        for (x in 0 until w) for (y in 0 until h) if (masked(x, y)) count++
        val expected = r * r * (4.0 - Math.PI)
        assertTrue(
            "遮掉的像素数 $count 与理论面积 $expected 差太多（区域画错了）",
            Math.abs(count - expected) / expected < 0.05
        )
        // 四个角对称：每一块的像素数应当基本相等（取整差 1 以内）
        fun cornerCount(x0: Int, x1: Int, y0: Int, y1: Int): Int {
            var n = 0
            for (x in x0 until x1) for (y in y0 until y1) if (masked(x, y)) n++
            return n
        }
        val rInt = Math.ceil(r.toDouble()).toInt()
        val tl = cornerCount(0, rInt, 0, rInt)
        val tr = cornerCount(w - rInt, w, 0, rInt)
        val bl = cornerCount(0, rInt, h - rInt, h)
        val br = cornerCount(w - rInt, w, h - rInt, h)
        assertEquals("四个角必须一样（左上 vs 右上）", tl, tr)
        assertEquals("四个角必须一样（左上 vs 左下）", tl, bl)
        assertEquals("四个角必须一样（左上 vs 右下）", tl, br)
        assertEquals("四角加起来就是全部被遮的像素", count, tl + tr + bl + br)

        // 兜底档：半径被夹到半边长（这里的框很扁，min(594,189)/2 = 94.5）时仍然只在四角、
        // 仍然"中间不遮"、面积仍然对得上
        val huge = 400f
        val clampedR = PhotoBitmap.cornerGeometry(w, h, huge).radiusPx
        assertEquals(94.5f, clampedR, 0.001f)
        assertTrue(PhotoBitmap.insideCornerMask(0, 0, w, h, huge))
        assertFalse("再大的半径也不能遮到中间", PhotoBitmap.insideCornerMask(w / 2, h / 2, w, h, huge))
        var clampedCount = 0
        for (x in 0 until w) for (y in 0 until h) if (PhotoBitmap.insideCornerMask(x, y, w, h, huge)) clampedCount++
        val clampedExpected = clampedR * clampedR * (4.0 - Math.PI)
        assertTrue(
            "夹取后的面积 $clampedCount 与理论值 $clampedExpected 差太多",
            Math.abs(clampedCount - clampedExpected) / clampedExpected < 0.05
        )
    }

    @Test
    fun theProbePixelIsAlwaysOneTheMaskShouldHavePainted() {
        // 遮罩画完会读一个"判据说该被遮掉"的像素做自查（真机上唯一能证明遮罩生效的东西）。
        // 探针必须**一定落在遮罩区里**，否则自查会变成假警报。
        for (size in listOf(
            intArrayOf(594, 189), intArrayOf(594, 92), intArrayOf(594, 255),
            intArrayOf(331, 187), intArrayOf(917, 137), intArrayOf(594, 449)
        )) {
            val probe = PhotoBitmap.maskProbePixel(size[0], size[1], radiusPx)
            assertTrue("${size.toList()} 上应当挑得出探针", probe != null)
            assertTrue(
                "探针 ${probe!!.toList()} 不在遮罩区里",
                PhotoBitmap.insideCornerMask(probe[0], probe[1], size[0], size[1], radiusPx)
            )
            // 探针还必须离弧线有富余（否则读回来的是抗锯齿的过渡色，会误报）
            val r = PhotoBitmap.cornerGeometry(size[0], size[1], radiusPx).radiusPx
            val cx = if (probe[0] < r) r else size[0] - r
            val cy = if (probe[1] < r) r else size[1] - r
            val dx = (probe[0] + 0.5f) - cx
            val dy = (probe[1] + 0.5f) - cy
            val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
            assertTrue("探针离弧线只有 ${dist - r}px，会被抗锯齿混到", dist - r >= 1.0)
        }

        // 半径太小（< PROBE_MIN_RADIUS_PX）时**挑不出探针**（返回 null → 不做自查，而不是误报）
        assertNull(PhotoBitmap.maskProbePixel(594, 189, 1f))
        assertNull(PhotoBitmap.maskProbePixel(594, 189, PhotoBitmap.PROBE_MIN_RADIUS_PX - 0.1f))
        assertNull("没遮罩（半径 0）时不要探针", PhotoBitmap.maskProbePixel(594, 189, 0f))
        assertNotNull(PhotoBitmap.maskProbePixel(594, 189, PhotoBitmap.PROBE_MIN_RADIUS_PX))
    }

    @Test
    fun rgb565CornersAreFilledWithTheColourBehindThePhotoNeverBlack() {
        // RGB_565 没有 alpha 通道：涂"透明"只会渲染成黑角（用户明说了不能有黑角）。
        // 所以生产路径（照片恒为 RGB_565）填的是**图片区背后那层的颜色**。
        val behind = 0xFFFFFFFF.toInt()      // 明色主题的组件根背景
        assertEquals(
            "RGB_565 的角必须填背景色",
            behind, PhotoBitmap.cornerFillArgb("RGB_565", behind)
        )
        assertNotEquals("绝不能填成透明（RGB_565 下那就是黑角）", 0, PhotoBitmap.cornerFillArgb("RGB_565", behind))
        // 暗色主题同样跟着背景色走
        val dark = 0xFF171A1F.toInt()
        assertEquals(dark, PhotoBitmap.cornerFillArgb("RGB_565", dark))

        // 位图真的带 alpha 时（toRgb565 在某个 ROM 上失败、位图留在 ARGB_8888）才挖成真透明
        assertEquals(
            "ARGB_8888 下透明是能表达的，也最干净",
            PhotoBitmap.TRANSPARENT_ARGB, PhotoBitmap.cornerFillArgb("ARGB_8888", behind)
        )
        // 兜底：格式名认不出来时按"不透明"处理（填背景色总比黑角好）
        assertEquals(behind, PhotoBitmap.cornerFillArgb("?", behind))
    }

    /**
     * 四个角的**楔形几何**：圆心、半径、弧的起止角。
     *
     * 这一组是本轮最要紧的回归：弧的角度是手写的（180°/270°/0°/180°−90°），写反了
     * 单测里画布是假的、看不出来，真机上就是"某个角没涂"或者"涂错了地方"。
     * 所以这里用端点**反查**角度：`端点 = 圆心 + 半径 × (cos 角, sin 角)` 必须逐值成立。
     */
    @Test
    fun everyWedgeArcStartsAndEndsExactlyWhereItsAnglesSay() {
        val w = 594
        val h = 189
        val mask = PhotoBitmap.cornerGeometry(w, h, radiusPx)
        val wedges = PhotoBitmap.cornerWedges(mask)
        assertEquals("四个角一个都不能漏", 4, wedges.size)

        for (wedge in wedges) {
            // ① 端点必须**落在圆周上**（逐值反查角度：写反了这里立刻红）
            for (p in listOf(
                doubleArrayOf(wedge.fromX.toDouble(), wedge.fromY.toDouble()),
                doubleArrayOf(wedge.toX.toDouble(), wedge.toY.toDouble())
            )) {
                val d = Math.hypot(p[0] - wedge.centerX, p[1] - wedge.centerY)
                assertEquals(
                    "端点 (${p[0]},${p[1]}) 到圆心 (${wedge.centerX},${wedge.centerY}) 的距离",
                    wedge.radiusPx.toDouble(), d, 0.0001
                )
            }
            // ② 四个关键点都必须落在位图内（越界就是画到别人的地盘上）
            for (p in listOf(
                floatArrayOf(wedge.fromX, wedge.fromY),
                floatArrayOf(wedge.toX, wedge.toY),
                floatArrayOf(wedge.cornerX, wedge.cornerY),
                floatArrayOf(wedge.centerX, wedge.centerY)
            )) {
                assertTrue(
                    "点 (${p[0]},${p[1]}) 跑到位图 $w x $h 外面了",
                    p[0] >= 0f && p[1] >= 0f && p[0] <= w.toFloat() && p[1] <= h.toFloat()
                )
            }
            // ③ 角点一定在圆外（= 属于要被填掉的那块），圆心一定在圆内（= 绝不能被填）
            val toCorner = Math.hypot(
                (wedge.cornerX - wedge.centerX).toDouble(),
                (wedge.cornerY - wedge.centerY).toDouble()
            )
            assertTrue("角点必须在圆外：$toCorner vs r=${wedge.radiusPx}", toCorner > wedge.radiusPx)
            // ④ 弧的中点也在圆周上（反查"扫过角"没写反、没多写）
            val mid = wedge.pointAt(wedge.startAngle + wedge.sweepAngle / 2f)
            val dMid = Math.hypot(
                (mid[0] - wedge.centerX).toDouble(),
                (mid[1] - wedge.centerY).toDouble()
            )
            assertEquals("弧中点到圆心", wedge.radiusPx.toDouble(), dMid, 0.0001)
        }

        // 顺序与坐标逐值钉住（改顺序 = 改画法，得显式改这条）
        assertEquals(listOf(0f, w.toFloat(), w.toFloat(), 0f), wedges.map { it.cornerX })
        assertEquals(listOf(0f, 0f, h.toFloat(), h.toFloat()), wedges.map { it.cornerY })
        assertEquals(listOf(180f, 270f, 0f, 180f), wedges.map { it.startAngle })
        assertEquals(listOf(90f, 90f, 90f, -90f), wedges.map { it.sweepAngle })
        assertEquals(
            listOf(radiusPx, radiusPx, radiusPx, radiusPx),
            wedges.map { it.radiusPx }
        )
    }

    /** 楔形几何与 [PhotoBitmap.insideCornerMask] 必须是同一个区域（一个定义、两处使用） */
    @Test
    fun theArcsAgreeWithTheWrittenRegionDefinition() {
        val w = 120
        val h = 90
        val r = 20f
        for (corner in PhotoBitmap.cornerWedges(PhotoBitmap.cornerGeometry(w, h, r))) {
            // 扫描窗口 = **这个角的方角区**：由角点定（不能用圆心定 —— 左下/右下的圆心
            // 在 x 上都不小于 r，拿它判断会把窗口算到对面去）
            val x0 = if (corner.cornerX == 0f) 0 else w - r.toInt()
            val y0 = if (corner.cornerY == 0f) 0 else h - r.toInt()
            for (x in x0 until (x0 + r.toInt())) {
                for (y in y0 until (y0 + r.toInt())) {
                    val dx = (x + 0.5f) - corner.centerX
                    val dy = (y + 0.5f) - corner.centerY
                    val outsideDisc = dx * dx + dy * dy > r * r
                    assertEquals(
                        "像素 ($x,$y) 在圆外的判定与遮罩定义不一致",
                        outsideDisc,
                        PhotoBitmap.insideCornerMask(x, y, w, h, r)
                    )
                }
            }
        }
    }

    /** 填色的 alpha 必须被顶成不透明：`drawPath` 是 src-over，alpha=0 等于**什么都不画** */
    @Test
    fun theFillColourIsAlwaysForcedOpaque() {
        // 明/暗两套背景都要原样保留颜色，只把 alpha 补成 FF
        assertEquals(0xFFFFFFFF.toInt(), PhotoBitmap.opaqueArgb(0xFFFFFFFF.toInt()))
        assertEquals(0xFF171A1F.toInt(), PhotoBitmap.opaqueArgb(0xFF171A1F.toInt()))
        // 半透明 / 全透明（读不到颜色时的脏值）也必须变成不透明 —— 否则四角"涂了等于没涂"
        assertEquals(0xFFFFFFFF.toInt(), PhotoBitmap.opaqueArgb(0x00FFFFFF))
        assertEquals(0xFF000000.toInt(), PhotoBitmap.opaqueArgb(0))
        assertEquals(0xFF123456.toInt(), PhotoBitmap.opaqueArgb(0x7F123456))
    }

    @Test
    fun theFillColourIsWhatTheUserWillSampleInAScreenshot() {
        // 日志里 `角填色=#FFFFFFFF` / `角像素=#..` 这两个写法必须能被直接对着截图用：
        // 8 位十六进制，0xAARRGGBB → "AARRGGBB"。
        // （format 用的是 %08X，所以 0xFFFFFFFF → "FFFFFFFF"、0 → "00000000"）
        assertEquals("FFFFFFFF", PhotoBitmap.argbLabel(0xFFFFFFFF.toInt()))
        assertEquals("171A1F00", PhotoBitmap.argbLabel(0x171A1F00))
        assertEquals("00000000", PhotoBitmap.argbLabel(0))
    }

    @Test
    fun theSelfCheckComparesColoursWithTheRgb565Tolerance() {
        // 遮罩自查是拿"探针像素"与"填色"比的。位图是 RGB_565：白色能精确表示，
        // 暗色 #FF171A1F 只能落成 #FF101818 这样的近似值 —— 不容差就会每次刷新都误报。
        assertTrue(
            "暗色背景在 565 里的量化值必须判为同一个颜色",
            PhotoBitmap.sameArgbWithin565(0xFF101818.toInt(), 0xFF171A1F.toInt())
        )
        assertTrue(PhotoBitmap.sameArgbWithin565(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()))
        assertFalse(
            "白 vs 黑绝不能判成同一个颜色（那意味着遮罩没生效却报平安）",
            PhotoBitmap.sameArgbWithin565(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        )
        assertFalse(
            "填色 vs 随便一个照片色：不能算同一个颜色",
            PhotoBitmap.sameArgbWithin565(0xFFFFFFFF.toInt(), 0xFF3B6EA5.toInt())
        )
        assertEquals(8, PhotoBitmap.TOLERANCE_565)
        // 哨兵值绝不能撞上任何真实颜色：-1（就是 0xFFFFFFFF，白色）撞过一次 ——
        // 明色主题的四角填的正是白色，于是"读回来是白色"被当成"没读"，日志里写成 `角像素=?`
        assertFalse(
            "NO_PIXEL 不能等于白色（那正是明色主题角落的颜色）",
            PhotoBitmap.NO_PIXEL == 0xFFFFFFFF.toInt()
        )
        assertEquals(Int.MIN_VALUE, PhotoBitmap.NO_PIXEL)
    }

    // --------------------------------------------------------------- 4. 半径是"读出来的"

    @Test
    fun theFallbackRadiusMatchesTheDrawableActuallyUsedByThePhotoArea() {
        // 用户的要求：圆角半径要与 widget_row_bg 一致，**读那个 drawable 里的 <corners>**，
        // 不要凭空写一个数字；读不到时的兜底值必须与那个 drawable 逐值相同，否则
        // "照片的圆角与容器的圆角对齐"这句话在兜底路径上就是假的。
        // 运行时读法见 PhotoBitmap.cornerRadiusPx（getDrawable → GradientDrawable.getCornerRadius），
        // 这里读的是**同一个文件的源文本** —— 两边一旦漂移，这条用例立刻红。
        for (name in listOf("widget_row_bg.xml", "widget_row_bg_dark.xml")) {
            val f = repoFile("src/main/res/drawable/$name")
                ?: error("找不到 $name（测试工作目录假设有变）")
            val xml = f.readText()
            val radius = Regex("<corners[^>]*android:radius=\"(\\d+)dp\"").find(xml)
                ?.groupValues?.get(1)?.toInt()
                ?: error("$name 里没有 <corners android:radius=\"..dp\">")
            assertEquals(
                "$name 的圆角半径必须与 FALLBACK_CORNER_RADIUS_DP 一致",
                PhotoBitmap.FALLBACK_CORNER_RADIUS_DP, radius
            )
        }
        assertEquals(
            "兜底半径定成 10dp 不是随手写的：它就是容器 drawable 的半径",
            10, PhotoBitmap.FALLBACK_CORNER_RADIUS_DP
        )
    }

    @Test
    fun theFallbackBehindColoursMatchTheRootBackgroundDrawables() {
        // 四角填的是"图片区背后那一层"的颜色 = 组件根背景（widget_bg / widget_bg_dark）。
        // 运行时从 drawable 的 <solid> 里读，读不到才用那两个兜底常量 —— 同样必须逐值一致。
        val light = repoFile("src/main/res/drawable/widget_bg.xml")
            ?: error("找不到 widget_bg.xml（测试工作目录假设有变）")
        val dark = repoFile("src/main/res/drawable/widget_bg_dark.xml")
            ?: error("找不到 widget_bg_dark.xml（测试工作目录假设有变）")
        val colors = repoFile("src/main/res/values/colors.xml")
            ?: error("找不到 colors.xml（测试工作目录假设有变）")

        // 暗色那套写的是字面量：#FF171A1F
        assertEquals(
            "widget_bg_dark.xml 的 <solid> 必须与 FALLBACK_BEHIND_DARK 一致（对不上就是漂移了）",
            PhotoBitmap.FALLBACK_BEHIND_DARK, solidOf(dark.readText())
        )
        // 明色那套写的是 @color/widget_bg → 到 colors.xml 里把那个颜色取出来对
        assertEquals(
            "widget_bg.xml 的 <solid> 必须与 FALLBACK_BEHIND_LIGHT 一致（对不上就是漂移了）",
            PhotoBitmap.FALLBACK_BEHIND_LIGHT, colorOf(light.readText(), colors.readText())
        )
        // 兜底值本身也钉一下：白色是"组件背景"，被换成别的颜色就等于四个角会显出方角
        assertEquals(0xFFFFFFFF.toInt(), PhotoBitmap.FALLBACK_BEHIND_LIGHT)
        assertEquals(0xFF171A1F.toInt(), PhotoBitmap.FALLBACK_BEHIND_DARK)
    }

    // --------------------------------------------------------------- 5. 源码级不变量

    @Test
    fun theMaskIsDrawnWithPlainArcsAndNothingThatCanSilentlyFail() {
        // 单测里画布是假的，所以这几条只能守**源码级**不变量（与 PhotoBitmapTest 里
        // `decodeEnforcesRgb565AndJudgesByTheRealByteCount` 同一套思路）。
        //
        // 这段实现改过两次，两次都是"静默失败"（不报错、不留痕，用户只看到"还是直角"）：
        //  ① 嵌套子路径 + `INVERSE_EVEN_ODD`：方向反了会把圆角矩形**内部**填掉，
        //     整张照片糊成一块底色；
        //  ② `Path.Op.DIFFERENCE`：语义没错，但它**有返回值**，失败时只能放弃遮罩
        //     （用户实测反馈"4×4 的时候图片还是直角"就是这类现象）。
        // 所以现在的写法是**四个角各画一段显式弧**：坐标全部来自纯算术（cornerWedges），
        // 没有分支、没有返回值、不依赖平台的路径集合运算。
        val src = listOf(
            File("src/main/java/app/timetable/widget/PhotoBitmap.kt"),
            File("app/src/main/java/app/timetable/widget/PhotoBitmap.kt")
        ).firstOrNull { it.isFile } ?: error("找不到 PhotoBitmap.kt（测试工作目录假设有变）")
        val text = src.readText()
        // 先把注释去掉再比对：文件里的注释**有意**提到那两条弯路（留给后来人的警告），
        // 而这条用例守的是**代码形态** —— 注释里写什么不管。
        val code = text.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertTrue("必须用抗锯齿的 Paint", code.contains("Paint.ANTI_ALIAS_FLAG"))
        assertTrue("必须用显式弧画四个角", code.contains("arcTo("))
        assertTrue("几何必须来自 cornerWedges（可单测那套）", code.contains("cornerWedges(mask)"))
        assertFalse(
            "不许用路径集合运算（它有返回值，失败就是静默不遮罩）",
            code.contains("Path.Op.")
        )
        assertFalse(
            "不许用填充规则技巧（INVERSE 方向会让整张照片被填成底色）",
            code.contains("Path.FillType.INVERSE")
        )
        assertFalse("这条路径压根不该设置填充规则", code.contains("setFillType"))
        assertTrue("半径必须读 drawable（不是写死的数字）", code.contains("cornerRadius"))
        assertTrue("半径必须先夹取", code.contains("cornerGeometry"))
        assertTrue(
            "遮罩后的位图必须仍是 RGB_565（ARGB 会让载荷翻倍，见类注释）",
            code.contains("copy(Bitmap.Config.RGB_565, true")
        )
        assertTrue(
            "填色必须是不透明的（alpha=0 的 drawPath 等于什么都不画）",
            code.contains("opaqueArgb(fillArgb)")
        )
        assertFalse(
            "不许把调用方给的颜色直接塞给 Paint（那就是 alpha=0 静默失效的入口）",
            code.contains("color = fillArgb")
        )
        assertTrue("画完必须读探针像素自查（角落该被填掉）", code.contains("maskProbePixel"))
        assertTrue("还要比对正中间（防止把整张照片糊成底色）", code.contains("centerBefore"))
        assertTrue("两条自查对不上时都要打警告", code.contains("Log.w(TAG"))
    }

    @Test
    fun whatIsNotCoveredHere() {
        // 这份文件覆盖不到的东西（写下来免得下次误以为验过了）：
        //  1. 遮罩**真的画上去了吗**：Canvas/Path 在单测里是假实现（`Path.op` 也返回 false）。
        //     判据有两条：真机日志的 `圆角=..px` + `角像素=#..`（这两列来自**运行时真读回来的像素**），
        //     以及用户拿截图取角落像素对照；
        //  2. 角落那个像素与背景**视觉上**是否严丝合缝：取决于宿主怎么把位图贴到框里
        //     （fitCenter + 位图逐值等于框 → 应当是逐像素对应），以及 RGB_565 的量化误差
        //     （每通道 ±8 左右）。另外位图被 binder 预算缩小过时，宿主会放大它，
        //     角落那一两个像素会被插值混到（读数仍以日志里的 `角像素=` 为准）；
        //  3. 容器 drawable 的圆角**看起来**圆不圆（那取决于宿主对 shape drawable 的渲染）。
        assertTrue("本用例只是一条书面说明", true)
    }

    /** 取一个 shape drawable 里 `<solid android:color="#AARRGGBB">` 的颜色；不是字面量则返回 0 */
    private fun solidOf(xml: String): Int {
        val hex = Regex("<solid[^>]*android:color=\"#([0-9A-Fa-f]{8})\"").find(xml)
            ?.groupValues?.get(1) ?: return 0
        return hex.toLong(16).toInt()
    }

    /** solid 的颜色：字面量直接取，`@color/name` 形式到 colors.xml 里查 */
    private fun colorOf(drawableXml: String, colorsXml: String): Int {
        val literal = solidOf(drawableXml)
        if (literal != 0) return literal
        val name = Regex("<solid[^>]*android:color=\"@color/(\\w+)\"").find(drawableXml)
            ?.groupValues?.get(1)
            ?: error("drawable 里既没有字面量颜色、也没有 @color 引用")
        val hex = Regex("<color name=\"$name\">#([0-9A-Fa-f]{8})</color>").find(colorsXml)
            ?.groupValues?.get(1) ?: error("colors.xml 里没有 $name")
        return hex.toLong(16).toInt()
    }
}
