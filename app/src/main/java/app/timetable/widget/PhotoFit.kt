package app.timetable.widget

/**
 * 图片**几何**：把「一张（用户已经自己裁好的）图片 + 小组件这一轮给图片区的框」算成
 * 「裁哪一块、缩到多大」。全是纯算术（零 Android 类型），所以它才是唯一能在单测里
 * 真正被验证的那部分 —— 单测里 Android API 全是"返回默认值"的假实现，
 * 碰 Bitmap/Canvas 的行测不出任何东西（见 `testOptions.unitTests.isReturnDefaultValues`）。
 *
 * ## 这一轮为什么这么改（用户原话）
 *
 * > 「我不是要你有显示倍率，而是导入图片的时候可以自己裁剪，而且在空间足够的时候图片完整显示，
 * >   不够的时候显示自己裁剪的部分，注意组件是几乘几。」
 *
 * 上一轮把"裁哪一块"交给用户（显示方式 / 裁哪一段 / 放大倍数三个开关）。用户把这条路否掉了：
 * **裁哪一块由用户在图库导入时自己裁一次**（[PhotoCrop] + `ui/PhotoCropDialog.kt`），
 * 之后渲染只做程序能判断的事 —— 组件给图片区留的高度够不够。
 *
 * ## 渲染的两条分支（[layout]）—— 这就是全部规则
 *
 * 记 `r框 = 框宽/框高`、`r图 = 图宽/图高`：
 *
 * 1. **放得下**（`r图 ≥ r框`，等价于"框的高度 ≥ 按图的比例算出来的高度"）→ **完整显示**：
 *    整张图按框宽等比缩进去，一个像素都不裁，多出来的高度就是上下留边（[Layout.padY]）；
 * 2. **放不下**（`r框 > r图`，框比图更扁）→ 在**这张（已经裁过的）图内部**取**居中**的一块，
 *    保持框的比例，缩到框的像素尺寸填满。
 *
 * 两种情况都**不拉伸**、都**不再二次裁剪**（占满那条分支的产出就是框本身，另一条分支的留边
 * 由布局自己的圆角底色承担），而且产出的宽度**逐像素等于框宽** —— 宿主 ImageView 的
 * `fitCenter`（见 `widget_today.xml`）在这两条路上的缩放因子都恰好是 1：
 *
 * - 放不下：产出 = 框的像素尺寸 → `fitCenter` 是恒等变换；
 * - 放得下：产出宽 = 框宽、高 = 按图自身比例算出来的高（≤ 框高）→
 *   `scale = min(框宽/产出宽, 框高/产出高) = min(1, ≥1) = 1`，同样一个像素都不动。
 *
 * ### 为什么"放得下"那条路不把留边也画进位图
 *
 * 那需要把留白绘制成一个**不透明矩形**，而图片框的底色是布局里那张圆角 drawable
 * （明 `widget_row_bg` / 暗 `widget_row_bg_dark` 两套，见 [WidgetColors]）。
 * 在位图里涂一块死色，必然在某个主题下和圆角底色对不上（还会盖住圆角）；
 * 让位图比框窄/矮一点、由宿主居中，留边正好是布局自己的圆角底色 —— 这也是上一版
 * 「完整显示」模式的同一条思路。代价只有一个：留边时传过去的字节数更少（更省 binder）。
 */
internal object PhotoFit {

    /**
     * 从（解码后的）源图上取的那一块，单位是源图像素。
     *
     * @param width  裁剪宽度（px，≥ 1 且 ≤ 源宽）
     * @param height 裁剪高度（px，≥ 1 且 ≤ 源高）
     * @param x      左上角 x（≥ 0 且 x + width ≤ 源宽）—— 不越界是硬约束：
     *               `Bitmap.createBitmap` 越界会抛 [IllegalArgumentException]，
     *               而这段代码跑在**小组件刷新的广播接收器主线程**上，抛了就是整个 provider 挂掉。
     * @param y      左上角 y（≥ 0 且 y + height ≤ 源高）
     */
    internal class Window(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int
    ) {
        override fun toString() = "${width}x$height@($x,$y)"
    }

    /**
     * 这一次渲染的结论：走哪条分支、从源图上取哪一块、产出多大的位图。
     */
    internal class Layout(
        /** true = 完整显示（放得下）；false = 取裁剪图的中间一块（放不下） */
        val whole: Boolean,
        val window: Window,
        val outWidth: Int,
        val outHeight: Int,
        /** 这一次的框（像素），日志与单测都要能对上号 */
        val targetW: Int,
        val targetH: Int
    ) {
        /** 上下留边合计（只有"完整显示"分支才可能 > 0；左右永远为 0，因为产出宽 = 框宽） */
        val padY: Int get() = (targetH - outHeight).coerceAtLeast(0)

        /** 留边占框的比例（日志用：0 = 铺满，0.27 = 27% 的框是留边底色） */
        val padShare: Float
            get() = if (targetH <= 0) 0f else padY.toFloat() / targetH.toFloat()

        /** 日志/单测里那一列结论（用户看不到画面，只能看日志，所以这一列必须自解释） */
        val verdict: String get() = if (whole) "完整显示" else "取中间块"

        override fun toString(): String = if (whole) {
            "$verdict ${outWidth}x$outHeight 留边=${padY}px"
        } else {
            "$verdict 窗=$window ${outWidth}x$outHeight"
        }
    }

    /**
     * 纯函数（可单测）：**放得下吗** —— 下面这条分支判据的全部定义。
     *
     * `r图 ≥ r框` ⟺ `图宽/图高 ≥ 框宽/框高` ⟺ `图宽 × 框高 ≥ 框宽 × 图高`。
     * 这里用**整数交叉相乘**（并且先转 Long）而不是两个 float 相除相比：
     *  - float 比较在"两边比例完全相等"时会被浮点误差判反（例：917×137 的图进 917×137 的框），
     *    那会让本该"完整显示（零留边）"的情况掉进取中间块、平白切掉一条；
     *  - 交叉相乘在 Int 上会溢出（4000×4000×… 很容易越过 2^31），所以先转 Long。
     *
     * 语义上它就是用户那句"小组件给图片留出的高度 ≥ 按裁剪框比例算出来的高度"：
     * 把落进框宽所需的高度记为 `框宽 × 图高 / 图宽`，它 ≤ 框高 时就是放得下。
     */
    fun fitsWhole(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Boolean {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return false
        return srcW.toLong() * targetH.toLong() >= targetW.toLong() * srcH.toLong()
    }

    /**
     * 纯函数（可单测）：**放不下**时该从源图上取哪一块 —— 居中、与框同比例、贴满源图的一条边。
     *
     * 记 `r = 框宽/框高`，源图里"与框同比例"的矩形有两个极端：
     *
     * ```
     * byW = srcH × r      // 用源图整高推出来的宽度
     * byH = srcW ÷ r      // 用源图整宽推出来的高度
     * ```
     *
     * 取 `宽 = min(srcW, byW)`、`高 = min(srcH, byH)` —— 就是"把源图多出来的那条边按框的比例切掉"。
     * 本分支成立时（框比图更扁）必然切的是**上下**（宽整用），切完居中摆放：
     * 用户裁的是"最重要的一块"（多半在正中间），程序再裁时也只能取中间，不能自作主张偏上或偏下。
     *
     * 取整用 `Math.round`（四舍五入）：居中偏移 `(src − 窗口) ÷ 2` 在奇数差额上本来就该四舍五入，
     * `x.toFloat().toInt()`（截断）会稳定地少 1px —— 1px 肉眼看不出来，但用户会拿截图取像素核对，
     * 口径只能有一处（这条口径由单测逐值钉住）。
     *
     * @return 裁剪窗口；**参数非法时返回 null**（调用方应当跳过这张图、换下一张，
     *         而不是返回一个会造成 `createBitmap` 越界崩溃的矩形）
     */
    fun centerWindow(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Window? {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return null
        val r = targetW.toFloat() / targetH.toFloat()
        val byW = Math.round(srcH.toFloat() * r).coerceAtLeast(1)   // 上下摊开时该有多宽
        val byH = Math.round(srcW.toFloat() / r).coerceAtLeast(1)   // 左右摊开时该有多高
        val w = minOf(srcW, byW)
        val h = minOf(srcH, byH)
        val freeX = (srcW - w).coerceAtLeast(0)
        val freeY = (srcH - h).coerceAtLeast(0)
        return Window(w, h, Math.round(freeX * 0.5f), Math.round(freeY * 0.5f))
    }

    /**
     * 纯函数（可单测）：**放得下**时产出的位图尺寸。
     *
     * 宽度取框宽（所以宿主缩放因子恒为 1），高度按图自身比例算，并且**不许超过框高**
     * （越过了就不再是"放得下"）。`fitsWhole` 成立时 `round(框宽 × 图高 / 图宽) ≤ 框高 + 半个像素`，
     * 这里再 `coerceIn(1, 框高)` 兜住那半个像素的取整误差 —— 它只会让留边少 1px，不会让图被裁。
     */
    fun wholeSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): IntArray? {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return null
        val h = Math.round(targetW.toDouble() * srcH.toDouble() / srcW.toDouble())
            .toInt()
            .coerceIn(1, targetH)
        return intArrayOf(targetW, h)
    }

    /**
     * 纯函数（可单测）：这一次渲染的完整结论（[Layout]）—— 上面的分支判据 + 两条产出路径。
     *
     * 这是渲染链路上**唯一**决定"裁哪一块、缩到多大"的地方（[PhotoBitmap.decode] 只负责
     * 把这里的算术喂给 Bitmap 工厂），所以"空间够就完整显示、不够就取中间"这件事
     * 能在一个纯函数上被穷举验证。
     */
    fun layout(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Layout? {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return null
        if (fitsWhole(srcW, srcH, targetW, targetH)) {
            val size = wholeSize(srcW, srcH, targetW, targetH) ?: return null
            // 整张图都在（窗口 = 整张源图），一个像素都不裁
            return Layout(
                whole = true,
                window = Window(srcW, srcH, 0, 0),
                outWidth = size[0],
                outHeight = size[1],
                targetW = targetW,
                targetH = targetH
            )
        }
        val window = centerWindow(srcW, srcH, targetW, targetH) ?: return null
        // 产出的宽高**逐值等于框**：宿主端 fitCenter 于是是恒等变换（不缩放、不裁剪）
        return Layout(
            whole = false,
            window = window,
            outWidth = targetW,
            outHeight = targetH,
            targetW = targetW,
            targetH = targetH
        )
    }

    /**
     * 纯函数（可单测）：像素总数超预算时**等比**缩回来。
     *
     * 为什么还要这一层（[PhotoBitmap.decode] 结尾会拿**真实** byteCount 再验一次）：
     * [PhotoBitmap.targetPx] 是在**解码前**按"每像素 2 字节"估的预算，而解码器
     * **不一定听 `inPreferredConfig`**（带 alpha 的 PNG/截图会给 ARGB_8888 = 4 字节/像素，
     * 真机日志里那张 917×137 的图就是 491KB 而不是 251KB）。所以到手的位图有可能比预算大一倍，
     * 必须有一个"按真实字节数"的兜底。
     *
     * @param bytesPerPixel 真实配置的每像素字节数（[PhotoBitmap.BYTES_PER_PIXEL] 或 4）
     */
    fun budgetSize(
        w: Int,
        h: Int,
        maxBytes: Int = PhotoBitmap.MAX_BITMAP_BYTES,
        bytesPerPixel: Int = PhotoBitmap.BYTES_PER_PIXEL
    ): IntArray {
        if (w <= 0 || h <= 0) return intArrayOf(1, 1)
        val bpp = if (bytesPerPixel <= 0) PhotoBitmap.BYTES_PER_PIXEL else bytesPerPixel
        val pixels = w.toLong() * h.toLong()
        val budgetPixels = (maxBytes / bpp).coerceAtLeast(1).toLong()
        if (pixels <= budgetPixels) return intArrayOf(w, h)
        val k = Math.sqrt(budgetPixels.toDouble() / pixels.toDouble())
        return intArrayOf(
            Math.floor(w * k).toInt().coerceAtLeast(1),
            Math.floor(h * k).toInt().coerceAtLeast(1)
        )
    }
}
