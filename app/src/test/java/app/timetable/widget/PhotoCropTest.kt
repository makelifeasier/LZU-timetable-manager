package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裁剪界面的**几何算术**（纯函数部分）+ 裁剪框比例的来源 + 多图推进。
 *
 * 这一组守的是用户三条要求：
 *
 * > 「导入图片的时候可以自己裁剪」「注意组件是几乘几」「取消 = 不导入这张」
 *
 * 三件事必须钉死：
 *
 *  1. **框的比例来自真实格子尺寸**（[PhotoCrop.frame]）：小部件占几列几行是启动器说了算，
 *     比例只能用 `getAppWidgetOptions` 报上来的 dp 算；取不到时退到兜底 2:1，
 *     而且必须**标记成兜底**（日志里要能看出来，不能把猜的当实测的）。
 *  2. **平移/缩放任何操作之后照片都必须盖住框**（[PhotoCrop.clampOffset] / [place]）：
 *     一旦露白，用户确认后存下来的图里就会出现**不是照片的空白像素** ——
 *     预览里看不到、存下来却有，这是最不可接受的失败模式。
 *  3. **预览与结果逐像素对应**（[PhotoCrop.sourceFraction] 用归一化坐标）：
 *     预览是按屏幕缩过的位图，存盘是在 1600px 的解码图上做的，两者分辨率不同，
 *     靠归一化矩形才可能一致。
 *
 * 单测里 Android API 全是"返回默认值"的假实现，所以这里只测不碰 Bitmap/Context 的算术。
 */
class PhotoCropTest {

    // ---------------------------------------------------------------- 1. 裁剪框比例从哪来

    @Test
    fun theFrameRatioComesFromTheRealCellSize() {
        // 4 列 × 2 行的格子（内容宽 226dp、图片区 72dp）→ 框 226:72 ≈ 3.14:1
        val frame = PhotoCrop.frame(226, 72)
        assertFalse(frame.fallback)
        assertEquals(226, frame.widthDp)
        assertEquals(72, frame.heightDp)
        assertEquals(3.1389f, frame.ratio, 0.001f)
        assertEquals("226:72dp(3.14:1)", frame.label)

        // 4 列 × 3 行的格子（图片区 135dp）→ 框明显更扁，比例必须跟着变
        val tall = PhotoCrop.frame(226, 135)
        assertEquals(1.6741f, tall.ratio, 0.001f)
        assertTrue("格子更高 → 框更扁（比例更小）", tall.ratio < frame.ratio)

        // 窄格子（8 列 × 1 行那种）：内容宽大、图片区矮 → 比例很大
        val wide = PhotoCrop.frame(600, 80)
        assertEquals(7.5f, wide.ratio, 0.001f)
    }

    @Test
    fun theFrameFallsBackToTwoToOneWhenTheSizeIsUnknown() {
        // 部分启动器不上报 options；"强制隐藏"档位也会把图片区压成 0
        for (pair in listOf(0 to 0, 0 to 72, 226 to 0, -5 to 100, 100 to -5)) {
            val f = PhotoCrop.frame(pair.first, pair.second)
            assertTrue("${pair.first}:${pair.second} 应当落到兜底", f.fallback)
            assertEquals(2, f.widthDp)
            assertEquals(1, f.heightDp)
            assertEquals(2f, f.ratio, 0.0001f)
            // 兜底必须写在标签里：以后在这个比例下裁出来的图，得知道它是猜的
            assertTrue("兜底标签要自解释：${f.label}", f.label.contains("兜底"))
            assertEquals("2:1dp(2.00:1)兜底", f.label)
        }
        // 常量本身也是契约的一部分（改兜底比例必须同时改这条）
        assertEquals(2, PhotoCrop.FALLBACK_ASPECT_W)
        assertEquals(1, PhotoCrop.FALLBACK_ASPECT_H)
    }

    // ---------------------------------------------------------------- 2. 框在预览区里怎么摆

    @Test
    fun theFrameIsTheLargestCentredRectOfThatRatio() {
        // 框比预览区更扁 → 宽度顶满
        assertEquals(1000, PhotoCrop.frameSizeIn(1000, 800, 2f)[0])
        assertEquals(500, PhotoCrop.frameSizeIn(1000, 800, 2f)[1])
        // 框比预览区更竖（0.5 = 高是宽的两倍）→ 高度顶满、宽度缩进来
        assertEquals(400, PhotoCrop.frameSizeIn(1000, 800, 0.5f)[0])
        assertEquals(800, PhotoCrop.frameSizeIn(1000, 800, 0.5f)[1])
        // 正好装得下 → 原样
        assertEquals(1000, PhotoCrop.frameSizeIn(1000, 500, 2f)[0])
        assertEquals(500, PhotoCrop.frameSizeIn(1000, 500, 2f)[1])
    }

    @Test
    fun theFrameSizeKeepsTheAspectAndNeverCollapses() {
        for (view in listOf(1080 to 800, 720 to 1280, 320 to 200, 100 to 100)) {
            for (aspect in listOf(2f, 3.14f, 1f, 0.5f)) {
                val s = PhotoCrop.frameSizeIn(view.first, view.second, aspect)
                assertTrue("不能出现 0 尺寸：$s", s[0] >= 1 && s[1] >= 1)
                assertTrue("不能超出预览区：$s vs $view", s[0] <= view.first && s[1] <= view.second)
                val got = s[0].toFloat() / s[1]
                assertTrue("比例偏了：$got vs $aspect（$s）", Math.abs(got - aspect) / aspect < 0.02f)
            }
        }
        // 脏值（0 尺寸 / NaN 比例）不能算出 0 或抛异常
        assertEquals(1, PhotoCrop.frameSizeIn(0, 800, 2f)[0])
        val nan = PhotoCrop.frameSizeIn(800, 800, Float.NaN)
        assertTrue("NaN 比例按 1:1 兜底", nan[0] >= 1 && nan[1] >= 1)
    }

    // ---------------------------------------------------------------- 3. 缩放与平移的边界

    @Test
    fun thePhotoAlwaysCoversTheFrameAfterAnyGesture() {
        // 这条是"不能露白"的核心：把各种脏输入都喂一遍，出来的一定是合法状态
        val frameW = 600
        val frameH = 191
        val photoW = 720
        val photoH = 229
        val scales = listOf(0f, 0.01f, 0.5f, 1f, 3f, 100f, Float.NaN, -2f)
        val offsets = listOf(0f, 1f, -1f, 500f, -500f, 99999f, Float.NaN)
        var checked = 0
        for (s in scales) for (dx in offsets) for (dy in offsets) {
            val t = PhotoCrop.place(s, dx, dy, frameW, frameH, photoW, photoH)
            val scale = t[0]
            val drawW = photoW * scale
            val drawH = photoH * scale
            assertTrue("缩放必须是正的（$s → $scale）", scale > 0f)
            assertTrue("左右必须盖住：dx=${t[1]} drawW=$drawW", t[1] <= 0f && t[1] + drawW >= frameW - 0.01f)
            assertTrue("上下必须盖住：dy=${t[2]} drawH=$drawH", t[2] <= 0f && t[2] + drawH >= frameH - 0.01f)
            checked++
        }
        assertEquals("组合数变了说明覆盖范围变了", 8 * 7 * 7, checked)
    }

    @Test
    fun dragBeyondTheEdgeStopsAtTheEdge() {
        // 照片 720×229（比框大）在框 600×191 里：横向可挪 120，纵向可挪 38
        val t = PhotoCrop.clampOffset(-9999f, -9999f, 720f, 229f, 600, 191)
        assertEquals(-120f, t[0], 0.001f)
        assertEquals(-38f, t[1], 0.001f)
        // 往反方向拖过头 → 停在 0（照片左上角贴住框左上角）
        val r = PhotoCrop.clampOffset(500f, 500f, 720f, 229f, 600, 191)
        assertEquals(0f, r[0], 0.001f)
        assertEquals(0f, r[1], 0.001f)
        // 脏值按 0 处理（0 一定合法）
        val n = PhotoCrop.clampOffset(Float.NaN, Float.NaN, 720f, 229f, 600, 191)
        assertEquals(0f, n[0], 0.001f)
        assertEquals(0f, n[1], 0.001f)
        // 理论上不该出现的"照片比框小"也不能抛异常（coerceIn 的上下界写反会直接崩）
        val small = PhotoCrop.clampOffset(10f, 10f, 50f, 50f, 600, 191)
        assertEquals(0f, small[0], 0.001f)
        assertEquals(0f, small[1], 0.001f)
    }

    @Test
    fun theInitialPlacementIsExactlyCoveringAndCentred() {
        // 竖图进扁框：cover = max(100/200, 100/400) = 0.5 → 画出来 100×200 → 纵向居中 −50
        val t = PhotoCrop.initial(100, 100, 200, 400)
        assertEquals(0.5f, t[0], 0.0001f)
        assertEquals(0f, t[1], 0.0001f)
        assertEquals(-50f, t[2], 0.0001f)
        // 横图进扁框：cover = max(600/720, 191/229) = 0.8341 → 横向几乎不挪
        val w = PhotoCrop.initial(600, 191, 720, 229)
        assertEquals(0.83406f, w[0], 0.0001f)
        assertTrue("居中：左右余量之差 ≤ 1px", Math.abs(w[1] * 2 + (720 * w[0] - 600)) < 1.5f)
        // 尺寸脏值时不能崩，也不能给出 0 缩放
        val bad = PhotoCrop.initial(0, 0, 0, 0)
        assertTrue("缩放必须是正的", bad[0] > 0f)
    }

    @Test
    fun theScaleIsClampedBetweenCoverAndTheSharpnessCeiling() {
        val range = PhotoCrop.scaleRange(600, 191, 720, 229)
        assertEquals(0.83406f, range[0], 0.0001f)
        assertEquals(range[0] * PhotoCrop.MAX_RELATIVE_ZOOM, range[1], 0.0001f)

        // 想缩得比"刚好盖住"更小 → 拉回下界（否则会露出白边）
        assertEquals(range[0], PhotoCrop.clampScale(0.1f, range[0], range[1]), 0.0001f)
        // 想放得比上限更大 → 拉回上界（再放就是一团糊）
        assertEquals(range[1], PhotoCrop.clampScale(99f, range[0], range[1]), 0.0001f)
        // 脏值（NaN）→ 下界，绝不返回 0/负数
        assertEquals(range[0], PhotoCrop.clampScale(Float.NaN, range[0], range[1]), 0.0001f)
        // 上下界本身是脏值时（NaN）→ 按"不放大也不缩小"的 1.0 兜底，仍然不能是 0
        assertEquals(1f, PhotoCrop.clampScale(1f, Float.NaN, Float.NaN), 0.0001f)
        // 上下界互相矛盾（max < min）→ 采信下界（"至少盖住框"这条比"别超过清晰度上限"重要）
        assertEquals(2f, PhotoCrop.clampScale(1f, 2f, 0.5f), 0.0001f)
    }

    @Test
    fun pinchZoomKeepsTheFocusPointUnderTheFinger() {
        // 1:1 的照片进 1:1 的框：cover = 1，上下界 = [1, 5]
        val t = PhotoCrop.zoomAround(2f, 1f, 0f, 0f, 50f, 50f, 100, 100, 100, 100)
        assertEquals(2f, t[0], 0.0001f)
        assertEquals(-50f, t[1], 0.0001f)
        assertEquals(-50f, t[2], 0.0001f)
        // 焦点（框中心）在照片上的那个点在缩放前后必须是同一个点
        for (f in listOf(1.25f, 1.5f, 2f, 4f, 10f)) {
            val z = PhotoCrop.zoomAround(f, 1f, 0f, 0f, 37f, 61f, 100, 100, 100, 100)
            val before = (37f - 0f) / 1f
            val after = (37f - z[1]) / z[0]
            assertEquals("焦点漂了：$z", before, after, 0.01f)
            assertTrue("缩放不能越过清晰度上限", z[0] <= PhotoCrop.MAX_RELATIVE_ZOOM + 0.0001f)
        }
        // 一路缩到底（连点 − ）：只会停在"刚好盖住"，不会露白
        var state = PhotoCrop.initial(100, 100, 100, 100)
        repeat(10) { state = PhotoCrop.zoomAround(1f / PhotoCrop.ZOOM_STEP, state[0], state[1], state[2], 50f, 50f, 100, 100, 100, 100) }
        assertEquals(1f, state[0], 0.0001f)
        assertTrue("缩到底也必须盖住框", state[1] <= 0f && state[1] + 100 * state[0] >= 100f)
        // 脏 factor（0 / NaN / 负数）→ 状态不变，不许把照片缩没
        val same = PhotoCrop.zoomAround(0f, 2f, -50f, -50f, 50f, 50f, 100, 100, 100, 100)
        assertEquals(2f, same[0], 0.0001f)
        assertEquals(-50f, same[1], 0.0001f)
    }

    // ---------------------------------------------------------------- 4. 框里那一块 = 存下来的那一块

    @Test
    fun theSourceFractionIsTheFrameMappedBackOntoThePhoto() {
        // 2:1 的照片（200×100）放在 1:1 的框（100×100）里：cover = 1 → 左右各挪 50
        val f = PhotoCrop.sourceFraction(1f, -50f, 0f, 100, 100, 200, 100)
        assertEquals(0.25f, f[0], 0.0001f)   // x = 50 → 50/200
        assertEquals(0f, f[1], 0.0001f)
        assertEquals(0.5f, f[2], 0.0001f)    // w = 100 → 100/200
        assertEquals(1f, f[3], 0.0001f)      // h 用完
    }

    @Test
    fun theSourceFractionAlwaysStaysInsideThePhoto() {
        // 归一化矩形越界 = 存盘时擦到边界外 = 白边或崩溃。把所有脏输入都喂一遍
        val cases = listOf(
            floatArrayOf(0f, 0f),
            floatArrayOf(1f, 1f),
            floatArrayOf(0.83f, 0f),
            floatArrayOf(3f, -3f),
            floatArrayOf(Float.NaN, Float.NaN),
            floatArrayOf(-1e9f, 1e9f)
        )
        for (s in listOf(0.5f, 0.83f, 1f, 4f, Float.NaN)) {
            for (c in cases) {
                val f = PhotoCrop.sourceFraction(s, c[0], c[1], 600, 191, 720, 229)
                assertTrue("fx 越界：${f.toList()}", f[0] >= 0f && f[0] <= 1f)
                assertTrue("fy 越界：${f.toList()}", f[1] >= 0f && f[1] <= 1f)
                assertTrue("fw 越界：${f.toList()}", f[2] > 0f && f[2] <= 1f)
                assertTrue("fh 越界：${f.toList()}", f[3] > 0f && f[3] <= 1f)
                assertTrue("矩形必须整个落在源图内：${f.toList()}", f[0] + f[2] <= 1.0001f)
                assertTrue("矩形必须整个落在源图内：${f.toList()}", f[1] + f[3] <= 1.0001f)
            }
        }
        // 尺寸脏值 → 整张（"全都要"比"空矩形"安全：空矩形会 createBitmap(0,0) 崩）
        val bad = PhotoCrop.sourceFraction(1f, 0f, 0f, 0, 100, 720, 229)
        assertEquals(1f, bad[2], 0.0001f)
        assertEquals(1f, bad[3], 0.0001f)
    }

    @Test
    fun theSourceFractionKeepsTheFrameAspect() {
        for (frame in listOf(600 to 191, 400 to 400, 300 to 600)) {
            val f = PhotoCrop.sourceFraction(0.9f, -12f, -7f, frame.first, frame.second, 1600, 1200)
            val rectRatio = (f[2] * 1600) / (f[3] * 1200)
            val frameRatio = frame.first.toFloat() / frame.second
            assertTrue(
                "框的比例必须被保住：$rectRatio vs $frameRatio",
                Math.abs(rectRatio - frameRatio) / frameRatio < 0.02f
            )
        }
    }

    @Test
    fun pixelRectMapsTheFractionOntoWhateverResolutionWeDecoded() {
        // 预览 800×1600 与存盘 1600×3200 上，同一个归一化矩形给出**成比例**的两个像素矩形
        val f = floatArrayOf(0.25f, 0.25f, 0.5f, 0.5f)
        val small = PhotoCrop.pixelRect(f, 800, 1600)
        assertEquals(200, small[0])
        assertEquals(400, small[1])
        assertEquals(400, small[2])
        assertEquals(800, small[3])
        val big = PhotoCrop.pixelRect(f, 1600, 3200)
        assertEquals(small[0] * 2, big[0])
        assertEquals(small[2] * 2, big[2])
        // 整张
        val all = PhotoCrop.pixelRect(floatArrayOf(0f, 0f, 1f, 1f), 10, 10)
        assertEquals("0,0,10,10", all.joinToString(","))
        // 换算到 1600px 的解码图上正好是 720×229（与 aPhotoCroppedAtTheFrameRatioFillsTheBoxExactly 同一组数字）
        val saved = PhotoCrop.pixelRect(f, 720, 229)
        assertEquals(180, saved[0])
        assertEquals(57, saved[1])
        assertEquals(360, saved[2])
        assertEquals(115, saved[3])
    }

    @Test
    fun pixelRectNeverEscapesTheDecodedBitmap() {
        // 越界 → Bitmap.createBitmap 抛 IllegalArgumentException → 用户点"确定"时整个 App 崩
        val fractions = listOf(
            floatArrayOf(0f, 0f, 1f, 1f),
            floatArrayOf(0.999f, 0.999f, 0.5f, 0.5f),
            floatArrayOf(-1f, -1f, 3f, 3f),
            floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN),
            floatArrayOf(2f, 2f, 2f, 2f)
        )
        for (w in listOf(1, 2, 37, 720, 1600)) {
            for (h in listOf(1, 3, 229, 3587)) {
                for (f in fractions) {
                    val r = PhotoCrop.pixelRect(f, w, h)
                    assertTrue(
                        "越界：${f.toList()} on ${w}x$h → ${r.toList()}",
                        r[0] >= 0 && r[1] >= 0 && r[2] >= 1 && r[3] >= 1 &&
                            r[0] + r[2] <= w && r[1] + r[3] <= h
                    )
                }
            }
        }
        // 位图尺寸脏值 / 数组长度不对 → 给一个 1×1 的安全矩形（调用方拿到的至少不是越界值）
        assertEquals("0,0,1,1", PhotoCrop.pixelRect(floatArrayOf(0f, 0f, 1f, 1f), 0, 0).joinToString(","))
        assertEquals("0,0,1,1", PhotoCrop.pixelRect(floatArrayOf(0.5f), 100, 100).joinToString(","))
    }

    @Test
    fun croppingAPhotoThatAlreadyHasTheFrameShapeKeepsAlmostAllOfIt() {
        // 端到端（纯算术）：用户按"组件图片区的形状"选了一张同比例的照片、**没有动它**，
        // 那么存下来的那一块必须就是整张（差 ≤ 1px）。这是裁剪界面最基本的正确性。
        val frame = PhotoCrop.frame(226, 72)
        // 预览区里画出这个比例的框；预览位图是 720×229（与框同比例）
        val box = PhotoCrop.frameSizeIn(600, 200, frame.ratio)
        val t = PhotoCrop.initial(box[0], box[1], 720, 229)
        val fraction = PhotoCrop.sourceFraction(t[0], t[1], t[2], box[0], box[1], 720, 229)
        // 存盘时是在解码出来的 720×229 上做同一件事（真实链路解的是 720×229 的图）
        val rect = PhotoCrop.pixelRect(fraction, 720, 229)
        assertTrue(
            "不该平白裁掉一条：${rect.toList()}",
            rect[2] >= 719 && rect[3] >= 228 && rect[0] <= 1 && rect[1] <= 1
        )
    }

    // ---------------------------------------------------------------- 5. 存盘尺寸

    @Test
    fun theSavedSizeFollowsTheFrameRatioAndHonoursTheFloors() {
        val frame = PhotoCrop.frame(226, 72)
        // 组件此刻要解码 594px 宽：仍按 720px 下限存（组件以后可能被拉大/换高密度机型）
        val a = PhotoCrop.encodeSize(frame, 594)
        assertEquals(720, a[0])
        assertEquals(229, a[1])                        // round(720 ÷ 3.1389)
        // 组件变宽（1036px）：按组件要的宽度存，比例不变
        val b = PhotoCrop.encodeSize(frame, 1036)
        assertEquals(1036, b[0])
        assertEquals(330, b[1])
        // 组件特别宽：长边夹到导入链路的 1600 上限
        val c = PhotoCrop.encodeSize(frame, 4000)
        assertEquals(1600, c[0])
        assertTrue(c[1] <= 1600)
        assertTrue("比例不能被破坏", Math.abs(c[0].toFloat() / c[1] - frame.ratio) / frame.ratio < 0.02f)
        // 兜底比例（2:1）也要能用
        val d = PhotoCrop.encodeSize(PhotoCrop.frame(0, 0), 594)
        assertEquals("720,360", d.joinToString(","))
    }

    @Test
    fun theSavedSizeIsNeverSmallerThanWhatTheWidgetWillDecode() {
        // 存盘宽度永远 ≥ 组件此刻要解码的像素宽（被 1600 上限截住的那一档除外）：
        // 组件任何一次渲染都不需要放大我们的文件（放大只会糊）
        for (boxPx in listOf(1, 100, 331, 594, 777, 1036, 1599)) {
            for (frame in listOf(PhotoCrop.frame(226, 72), PhotoCrop.frame(349, 136), PhotoCrop.frame(0, 0))) {
                val s = PhotoCrop.encodeSize(frame, boxPx)
                assertTrue("存盘尺寸塌了：$s", s[0] >= 1 && s[1] >= 1)
                assertTrue("存盘不能超过导入链路的 1600 上限：$s", s[0] <= 1600 && s[1] <= 1600)
                assertTrue(
                    "存盘宽（${s[0]}）不能小于组件要解码的宽（$boxPx）",
                    s[0] >= Math.min(boxPx, WidgetPhotos.MAX_EDGE)
                )
                val r = s[0].toFloat() / s[1]
                assertTrue("比例偏了：$s vs ${frame.ratio}", Math.abs(r - frame.ratio) / frame.ratio < 0.02f)
            }
        }
        assertEquals(1600, WidgetPhotos.MAX_EDGE)
        assertEquals(720, PhotoCrop.ENCODE_MIN_WIDTH)
    }

    // ---------------------------------------------------------------- 6. 多图推进

    @Test
    fun cancellingStillAdvancesToTheNextPhoto() {
        // "取消 = 不导入这张"而不是"放弃整批"：取消第一张之后必须还能弹第二张，
        // 否则用户一次选 5 张、取消第一张，后面 4 张就永远轮不到了。
        val q = CropQueue(3)
        assertTrue(q.hasCurrent)
        assertEquals(0, q.index)
        assertEquals("标题里的第几张从 1 数起", 1, q.index + 1)
        assertTrue("取消第 1 张后还有第 2 张", q.advance())
        assertEquals(1, q.index)
        assertTrue("取消第 2 张后还有第 3 张", q.advance())
        assertEquals(2, q.index)
        assertFalse("第 3 张处理完就结束了", q.advance())
        assertEquals("结束后下标停在 total（不会越界）", 3, q.index)
        assertFalse("结束后再问一次也还是结束", q.advance())
        assertEquals("3/3", q.toString())
    }

    @Test
    fun anEmptySelectionNeverOpensAnything() {
        // 空列表（用户什么都没选就返回）不该弹任何东西：hasCurrent 一开始就是 false
        val q = CropQueue(0)
        assertFalse(q.hasCurrent)
        assertFalse(q.advance())
        assertEquals(0, q.index)
        // 脏值（负数）同理
        val bad = CropQueue(-3)
        assertFalse(bad.hasCurrent)
        assertFalse(bad.advance())
    }

    @Test
    fun theQueueWalksExactlyOncePerPhoto() {
        for (total in 1..6) {
            val q = CropQueue(total)
            var seen = 0
            while (q.hasCurrent) {
                seen++
                assertTrue("下标不能越界：${q.index} / $total", q.index in 0 until total)
                q.advance()
            }
            assertEquals("每张必须且只能被处理一次", total, seen)
        }
    }

    @Test
    fun whatIsNotCoveredHere() {
        // 这份文件里**没有**的部分（写下来免得下次误以为覆盖了）：
        //  1. 触摸事件到 [PhotoCrop] 的接线（PhotoCropDialog.CropView.onTouchEvent）：需要真机；
        //  2. 预览位图的解码与绘制（Canvas / BitmapFactory）：需要真 Android 运行时；
        //  3. "预览里看到的那一块"与"存下来的那张图"在真机上是否逐像素一致：
        //     靠 [theSourceFractionIsTheFrameMappedBackOntoThePhoto] 与真机日志核对。
        assertTrue("本用例只是一条书面说明", true)
    }
}
