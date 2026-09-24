package app.timetable.widget

/**
 * **裁剪框的比例** —— 用户在导入图片时看到的那个框，形状就是小组件里图片区的形状。
 *
 * 用户原话：「注意组件是几乘几」。
 *
 * ## 为什么是"内容宽 : 图片区可用高"，而不是写死 16:9 或 1:1
 *
 * 小组件占桌面上的 `W 列 × H 行` 格子，但**一格有多少像素是启动器说了算**（同一台机器上
 * 小米和 OPPO 的"一格"就不一样大）。唯一可靠的做法是从
 * `AppWidgetManager.getAppWidgetOptions(id)` 拿到**当前这个组件真实的 dp 尺寸**，
 * 再按小组件自己的排版链路算出"图片区实际能有多高"（[WidgetData.photoFrameHeightDp]）：
 *
 * ```
 * 裁剪框比例 = 图片区内容宽（格子宽 − 左右内边距） : 图片区可用高
 * ```
 *
 * 这样裁出来的图和组件里那一块**同形**：渲染时"放得下"，一分不裁、一秒不浪费。
 * 4×2 与 4×3 的格子高度不同 → 比例自然不同，不需要用户去理解像素。
 *
 * ## 取不到尺寸时的兜底：2:1（[PhotoCrop.fallback]）
 *
 * 部分启动器不上报尺寸（或上报 0 / 被"强制隐藏"档位压成 0），此时按 2:1。
 * 选 2:1 的三个理由：
 *  1. 图片区是组件**底部的一条**，真实比例几乎总 > 1，兜底不能小于 1（否则框会变竖）；
 *  2. 16:9（1.78）与 3:2（1.5）都更扁：用户从相册里最常选的是**竖屏截图/人像**
 *     （例：1440×3587 ≈ 0.40:1），框越扁，裁掉的就越多；2:1 在"看得出是横条"与
 *     "竖图还能留住上半身"之间；
 *  3. 兜底**只影响裁剪界面里框的形状**，不影响渲染正确性 —— 渲染时是按"裁剪后那张图自己的
 *     比例"重新判定放得下/放不下的（[PhotoFit.layout]），所以兜底选得不准最多让用户
 *     在裁剪时少留/多留一点边，不会让显示变形或裁错。这条性质很重要：宁可兜底保守，
 *     也不要为了"猜准比例"去引入一个会改变渲染结果的魔法数。
 */
internal class CropFrame(
    /** 图片区内容宽（dp，> 0） */
    val widthDp: Int,
    /** 图片区可用高（dp，> 0） */
    val heightDp: Int,
    /** true = 尺寸取不到，这里是兜底比例（日志里要看得出来，别把兜底当实测） */
    val fallback: Boolean
) {
    /** 宽 : 高 */
    val ratio: Float
        get() = if (widthDp > 0 && heightDp > 0) widthDp.toFloat() / heightDp.toFloat() else 1f

    /** 日志里那一列（"226:87dp(2.60:1)"） */
    val label: String
        get() = "${widthDp}:${heightDp}dp(${PhotoBitmap.ratioLabel(widthDp, heightDp)})" +
            if (fallback) "兜底" else ""

    override fun toString() = "${widthDp}:$heightDp" + if (fallback) "(兜底)" else ""
}

/**
 * 裁剪界面里的**几何算术**：缩放、平移的边界钳制，以及"框里那一块"落到源图上的矩形。
 *
 * 为什么单独抽一个纯对象：[PhotoBitmap] 那类代码碰 Bitmap/Canvas，单测里 Android API 全是
 * "返回默认值"的假实现，测不出任何东西。而"不能把照片拖出裁剪框露出空白"这件事
 * 全靠算术保证，必须能被穷举验证 —— 用户拖到边上、连点十下 ＋、双指乱捏，
 * 都不该出现白边（一旦出现，用户看到的裁剪结果就会和预览不一致）。
 *
 * ## 坐标系的约定（下面所有函数共用）
 *
 * ```
 * (dx, dy) ──► ┌──────────────────────────┐  照片画成 (photoW×scale) × (photoH×scale)
 *              │ 照片（超出框的部分被裁掉）│  它的左上角在**框坐标系**里的位置就是 (dx, dy)
 *              │      ┌────────────┐      │
 *              │      │  裁剪框    │      │  裁剪框固定在 (0,0)，尺寸 frameW × frameH
 *              │      └────────────┘      │
 *              └──────────────────────────┘
 * ```
 *
 * 于是"框里能看到的那一块"= 照片坐标系里的矩形
 * `(-dx/scale, -dy/scale)` 起、`(frameW/scale) × (frameH/scale)` 大。
 */
internal object PhotoCrop {

    /** 兜底比例的宽（见 [CropFrame] 的注释：为什么是 2:1） */
    const val FALLBACK_ASPECT_W = 2

    /** 兜底比例的高 */
    const val FALLBACK_ASPECT_H = 1

    /**
     * "+/− 按钮"与"双指捏合"共同的缩放上限，单位是"相对刚好盖住框的倍数"。
     *
     * 为什么要有上限：源图是我们自己压缩存下来的（长边 ≤ 1600px），放进几百像素宽的框里，
     * 放大 5 倍时参与显示的就只剩源图的 1/5 边长（4% 的面积）—— 再往里放必然是一团糊，
     * 而存盘时还会把它缩回目标尺寸，等于白糊。这个上限不是安全边界，是**清晰度**边界。
     */
    const val MAX_RELATIVE_ZOOM = 5f

    /** ＋/− 每按一次的倍数 */
    const val ZOOM_STEP = 1.25f

    /**
     * 存盘宽度的下限（px）。
     *
     * 存的是**文件**，不受 binder 预算约束；小组件以后可能被拉大、或者手机换到更高密度的机型，
     * 按"此刻框的像素宽"存（例如 594px）会让那时的图被放大着显示。720px 是"一条横图还看得清"
     * 与"文件别太大"之间的折中（按 2.6:1 算约 720×277 的 JPEG，几十 KB 量级）。
     */
    const val ENCODE_MIN_WIDTH = 720

    /**
     * 纯函数（可单测）：小组件这一轮的格子尺寸 → 裁剪框。
     *
     * 取不到（≤ 0）时给兜底比例，并且**标记出来**（[CropFrame.fallback]）：
     * 日志里"226:87dp"与"2:1dp(兜底)"必须能一眼分开，否则以后没人知道那块比例是猜的。
     */
    fun frame(contentWidthDp: Int, photoHeightDp: Int): CropFrame =
        if (contentWidthDp > 0 && photoHeightDp > 0) {
            CropFrame(contentWidthDp, photoHeightDp, false)
        } else {
            CropFrame(FALLBACK_ASPECT_W, FALLBACK_ASPECT_H, true)
        }

    /**
     * 纯函数（可单测）：在 `viewW × viewH` 的预览区里，放下这个比例的最大**居中**矩形。
     *
     * 这就是用户看到的那个框。取"能放下的最大"是有意的：框越大，用户越看得清自己在裁什么。
     */
    fun frameSizeIn(viewW: Int, viewH: Int, aspect: Float): IntArray {
        if (viewW <= 0 || viewH <= 0) return intArrayOf(1, 1)
        val a = if (aspect.isNaN() || aspect <= 0f) 1f else aspect
        val byHeightW = Math.round(viewH * a.toDouble()).toInt()
        return if (byHeightW <= viewW) {
            // 框比预览区更"竖"（或正好）：高度顶满，宽度按比例缩进来
            intArrayOf(byHeightW.coerceAtLeast(1), viewH)
        } else {
            // 框比预览区更"扁"：宽度顶满，高度按比例缩进来
            intArrayOf(viewW, Math.round(viewW / a.toDouble()).toInt().coerceAtLeast(1))
        }
    }

    /**
     * 纯函数（可单测）：**刚好盖住框**的缩放（照片的初始比例，也是允许的最小缩放）。
     *
     * 用 `max` 而不是 `min`：`min` 是"整张塞进框"（会露出空白），而裁剪界面里露白边意味着
     * 用户确认后存下来的图里会有**空白像素**——那不是照片的一部分，必须禁止。
     * 这就是"不能把照片拖出裁剪框露出空白"这条约束的起点。
     */
    fun coverScale(frameW: Int, frameH: Int, photoW: Int, photoH: Int): Float {
        if (frameW <= 0 || frameH <= 0 || photoW <= 0 || photoH <= 0) return 1f
        return maxOf(frameW.toFloat() / photoW.toFloat(), frameH.toFloat() / photoH.toFloat())
    }

    /** 纯函数（可单测）：缩放允许的范围（下界 = 刚好盖住，上界 = 下界 × [MAX_RELATIVE_ZOOM]） */
    fun scaleRange(frameW: Int, frameH: Int, photoW: Int, photoH: Int): FloatArray {
        val min = coverScale(frameW, frameH, photoW, photoH)
        return floatArrayOf(min, min * MAX_RELATIVE_ZOOM)
    }

    /** 纯函数（可单测）：把缩放夹回合法区间（NaN / 脏值一律落到下界，绝不返回 0 或负数） */
    fun clampScale(scale: Float, minScale: Float, maxScale: Float): Float {
        val lo = if (minScale.isNaN() || minScale <= 0f) 1f else minScale
        val hi = if (maxScale.isNaN() || maxScale < lo) lo else maxScale
        if (scale.isNaN()) return lo
        return scale.coerceIn(lo, hi)
    }

    /**
     * 纯函数（可单测）：把平移量夹进"照片始终盖住框"的范围 —— 拖到边上、继续拖，都不会露白。
     *
     * 合法范围是 `x ∈ [框宽 − 画出来的宽, 0]`（照片比框大，所以上界是 0、下界是负数）。
     * 两处细节：
     *  - `minOf(0f, …)` 兜住"照片比框小"这种理论上不该出现的情况（下界变成正数时
     *    `coerceIn(正, 0)` 会直接抛 `IllegalArgumentException`，那是在用户手指底下崩）；
     *  - NaN 传入时按 0 处理（0 一定合法：照片左上角贴住框左上角）。
     */
    fun clampOffset(
        dx: Float,
        dy: Float,
        drawW: Float,
        drawH: Float,
        frameW: Int,
        frameH: Int
    ): FloatArray {
        val minX = minOf(0f, frameW - drawW)
        val minY = minOf(0f, frameH - drawH)
        val x = (if (dx.isNaN()) 0f else dx).coerceIn(minX, 0f)
        val y = (if (dy.isNaN()) 0f else dy).coerceIn(minY, 0f)
        return floatArrayOf(x, y)
    }

    /**
     * 纯函数（可单测）：把一组 (缩放, 平移) 归一成**一定合法**的状态。
     *
     * 裁剪界面的每一次交互（拖动、捏合、± 按钮、旋转屏幕后重建）都必须走这里再落到绘制上，
     * 不存在"先画了再修"的路径 —— 预览与最终存盘用的是同一组数字（[sourceFraction]），
     * 所以预览里看不到白边，存下来的图里就也不会有。
     *
     * @return `[scale, dx, dy]`
     */
    fun place(
        scale: Float,
        dx: Float,
        dy: Float,
        frameW: Int,
        frameH: Int,
        photoW: Int,
        photoH: Int
    ): FloatArray {
        val range = scaleRange(frameW, frameH, photoW, photoH)
        val s = clampScale(scale, range[0], range[1])
        val off = clampOffset(dx, dy, photoW * s, photoH * s, frameW, frameH)
        return floatArrayOf(s, off[0], off[1])
    }

    /** 纯函数（可单测）：初始状态 = 刚好盖住 + 居中（用户一进来看到的就是"整块填满、不露白"） */
    fun initial(frameW: Int, frameH: Int, photoW: Int, photoH: Int): FloatArray {
        val s = coverScale(frameW, frameH, photoW, photoH)
        return place(s, (frameW - photoW * s) / 2f, (frameH - photoH * s) / 2f, frameW, frameH, photoW, photoH)
    }

    /**
     * 纯函数（可单测）：以 [focusX]/[focusY]（框坐标系里的点）为锚点缩放 [factor] 倍。
     *
     * 锚点不动的做法是把"锚点到照片左上角"的位移同比例放大：
     * `dx' = fx − (fx − dx) × k`。双指捏合时焦点就是两指中心，± 按钮用框中心 ——
     * 用框中心而不是照片中心，是因为用户看的是框里的画面。
     *
     * @return `[scale, dx, dy]`（已钳制）
     */
    fun zoomAround(
        factor: Float,
        scale: Float,
        dx: Float,
        dy: Float,
        focusX: Float,
        focusY: Float,
        frameW: Int,
        frameH: Int,
        photoW: Int,
        photoH: Int
    ): FloatArray {
        val base = place(scale, dx, dy, frameW, frameH, photoW, photoH)
        val f = if (factor.isNaN() || factor <= 0f) 1f else factor
        val range = scaleRange(frameW, frameH, photoW, photoH)
        val next = clampScale(base[0] * f, range[0], range[1])
        val k = if (base[0] <= 0f) 1f else next / base[0]
        val ndx = focusX - (focusX - base[1]) * k
        val ndy = focusY - (focusY - base[2]) * k
        return place(next, ndx, ndy, frameW, frameH, photoW, photoH)
    }

    /**
     * 纯函数（可单测）：框里那一块在**源图上的归一化矩形** `[fx, fy, fw, fh]`（都是 0..1）。
     *
     * 为什么归一化而不是直接给像素：预览用的位图与存盘时解码的位图**分辨率不一样**
     * （预览是按屏幕缩过的，存盘按 [ENCODE_MIN_WIDTH] / [WidgetPhotos.MAX_EDGE]）。
     * 归一化的矩形对两者都成立，于是"预览里看到的那一块"与"真正存下来的那一块"逐像素对应
     * —— 这才是裁剪界面存在的意义，差一点都算骗用户。
     *
     * 内部会先 [place] 一次：即使调用方传进来一组没钳制的数字，这里算出来的矩形也一定落在 0..1 内。
     */
    fun sourceFraction(
        scale: Float,
        dx: Float,
        dy: Float,
        frameW: Int,
        frameH: Int,
        photoW: Int,
        photoH: Int
    ): FloatArray {
        if (photoW <= 0 || photoH <= 0 || frameW <= 0 || frameH <= 0) return floatArrayOf(0f, 0f, 1f, 1f)
        val p = place(scale, dx, dy, frameW, frameH, photoW, photoH)
        val s = p[0]
        val w = (frameW / s).coerceIn(1f, photoW.toFloat())
        val h = (frameH / s).coerceIn(1f, photoH.toFloat())
        val x = ((-p[1]) / s).coerceIn(0f, photoW - w)
        val y = ((-p[2]) / s).coerceIn(0f, photoH - h)
        return floatArrayOf(x / photoW, y / photoH, w / photoW, h / photoH)
    }

    /**
     * 纯函数（可单测）：归一化矩形 → 某张已经解码好的位图上的**像素**矩形 `[x, y, w, h]`。
     *
     * 这里的每一条 `coerce` 都对应一次真实崩溃：`Bitmap.createBitmap` 的
     * `width/height` 传 0、或 `x + width > 位图宽`，都会抛 `IllegalArgumentException`，
     * 而这次解码发生在用户点"确定"之后 —— 崩的是整个 App，不是"这张图难看"。
     *
     * 取整一律 `Math.round`：`fx` 与 `fw` 各自四舍五入，矩形的比例与框的比例最多差半个像素
     * （存盘时还会被缩到目标尺寸，比例误差远小于肉眼阈值）。
     */
    fun pixelRect(fraction: FloatArray, bitmapW: Int, bitmapH: Int): IntArray {
        if (bitmapW <= 0 || bitmapH <= 0 || fraction.size < 4) return intArrayOf(0, 0, 1, 1)
        val fx = fraction[0].coerceIn(0f, 1f)
        val fy = fraction[1].coerceIn(0f, 1f)
        val fw = fraction[2].coerceIn(0f, 1f)
        val fh = fraction[3].coerceIn(0f, 1f)
        val x = Math.round(fx * bitmapW).coerceIn(0, bitmapW - 1)
        val y = Math.round(fy * bitmapH).coerceIn(0, bitmapH - 1)
        val w = Math.round(fw * bitmapW).coerceIn(1, bitmapW - x)
        val h = Math.round(fh * bitmapH).coerceIn(1, bitmapH - y)
        return intArrayOf(x, y, w, h)
    }

    /**
     * 纯函数（可单测）：存盘尺寸 = **按裁剪框的比例**，宽度不小于目标解码宽（[boxWidthPx]）
     * 且不小于 [minWidth]，长边不超过 [maxEdge]。
     *
     * 三个数各有出处，不是随手写的：
     *  - [boxWidthPx]（小组件此刻要解码的像素宽）是**下限**：存下来的文件永远不比组件要的像素少，
     *    这样组件任何一次渲染都不需要"放大我们的文件"（那只会糊）；
     *  - [minWidth]：组件可能被拉大、或者换到高密度机型，见 [ENCODE_MIN_WIDTH]；
     *  - [maxEdge]（[WidgetPhotos.MAX_EDGE] = 1600）：与导入链路上原有的压缩上限同一个数 ——
 *    裁剪不该让文件比"没裁剪时"更大。
     */
    fun encodeSize(
        frame: CropFrame,
        boxWidthPx: Int,
        minWidth: Int = ENCODE_MIN_WIDTH,
        maxEdge: Int = WidgetPhotos.MAX_EDGE
    ): IntArray {
        val r = if (frame.ratio.isNaN() || frame.ratio <= 0f) 1f else frame.ratio
        val w0 = maxOf(boxWidthPx, minWidth, 1)
        val h0 = Math.round(w0 / r.toDouble()).toInt().coerceAtLeast(1)
        val k = minOf(1.0, maxEdge.toDouble() / w0.toDouble(), maxEdge.toDouble() / h0.toDouble())
        return intArrayOf(
            Math.round(w0 * k).toInt().coerceAtLeast(1),
            Math.round(h0 * k).toInt().coerceAtLeast(1)
        )
    }
}

/**
 * 多张图片**逐张裁剪**的推进（纯逻辑，可单测）。
 *
 * 用户一次可能选好几张图，裁剪界面一次只弹一张（用户明确同意"允许先只支持一次一张"）。
 * "取消 = 不导入这张"这条语义意味着：**取消也要推进**，否则用户取消第一张就卡在那里，
 * 后面几张永远轮不到。所以推进逻辑不能写在对话框里（那里碰 Activity，测不了），
 * 抽成这个小状态机，把边界（最后一张、空列表、多问一次）全部钉在单测里。
 */
internal class CropQueue(val total: Int) {

    /** 当前该处理的第几张（从 0 开始）；全部处理完时等于 [total] */
    var index: Int = 0
        private set

    /** 还有没有要处理的（[total] ≤ 0 时一开始就是 false —— 空列表不该弹任何东西） */
    val hasCurrent: Boolean get() = total > 0 && index < total

    /**
     * 当前这张处理完了（确认或取消都一样）→ 推进到下一张。
     *
     * @return 还有没有下一张（false = 整个流程结束）
     */
    fun advance(): Boolean {
        if (index < total) index++
        return hasCurrent
    }

    /** 日志用："第 2/3 张" */
    override fun toString() = "${(index + 1).coerceAtMost(total)}/$total"
}
