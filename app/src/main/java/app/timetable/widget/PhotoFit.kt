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
 * 裁哪一块由用户在图库导入时自己裁一次（[PhotoCrop] + `ui/PhotoCropDialog.kt`），
 * 渲染只做程序能判断的事。
 *
 * ## 渲染的规则（[layout]）—— 这就是全部
 *
 * **产出永远逐值等于框**（[Layout.outWidth] / [Layout.outHeight] 就是框的像素尺寸），
 * 也就是"**填满，绝不留白、绝不拉伸**"：从（用户已经裁好的）源图里取一个**与框同比例、
 * 居中、尽可能大**的矩形（[centerWindow]），再缩到框的像素尺寸。
 *
 * 记 `r框 = 框宽/框高`、`r图 = 图宽/图高`，两种可观察的结果：
 *
 * 1. **比例一致**（`r图 == r框`，在 dp→px 取整的误差内）→ 取到的那块**就是整张源图**
 *    → [Layout.whole] = true，日志写「完整显示」：用户按裁剪框裁出来的照片原样出现，
 *    一个像素都不裁（这也是导入时那条框与组件同形带来的常态）；
 * 2. **比例不一致**（框被拉高/压扁之后）→ 取到的是源图**内部**的一块
 *    → [Layout.whole] = false，日志写「取中间块」：保持框的比例、居中、不越界。
 *
 * ### 为什么不再有"放得下就上下留边"这条分支（本轮改掉的）
 *
 * 上一版的规则是"框比图高 → 整张按框宽缩进去 + 上下留边（[Layout.padY]）"。那一版的前提是
 * **框高恒等于照片自己要的高度**（`WidgetData.photoWantedHeightDp`），所以当时"框比图高"并不常见。
 * 本轮把框高改成"吃掉课程列表下方的**全部**剩余空间"之后（用户反馈：下拉组件时底部还剩一条
 * 不足一整行的空白），框**天然**会比照片高 —— 那条留边就又变成了用户看到的"照片下方一条空白"。
 *
 * 现在的取舍是**宁可裁一点、也不留白**：照片是用户自己选的，留白是纯粹的浪费。
 * 而"裁"这件事对用户是可预期的 —— 裁剪框的形状 = 导入那一刻组件的形状，比例天然一致，
 * 所以只有在他后来改过组件尺寸时才会真的裁到，而且裁的是**正中间**那一块。
 *
 * ### 为什么产出必须逐值等于框（而不是"宽等于框宽、高按图自身的比例"）
 *
 * 位图逐值等于框，宿主 `fitCenter`（见 `widget_today.xml`）就是**恒等变换**：
 * 既不缩放也不裁剪，画面完全由我们这边的算术决定 —— 这也意味着"组件里看到的那一块"
 * 与"这里算出来的窗口"逐像素对应，用户拿截图取像素核对才有意义。
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
        /**
         * true = **完整显示**：取到的那块就是整张源图（比例一致，一个像素都没裁）；
         * false = **取中间块**：框被拉高/压扁过，取的是源图内部居中那一块。
         *
         * 注意它现在的含义是"窗口是否等于整张源图"，而**不是**上一版的"放得下"：
         * 产出永远等于框（见类注释），所以"不裁"只可能发生在"比例一致"这一档。
         */
        val whole: Boolean,
        val window: Window,
        val outWidth: Int,
        val outHeight: Int,
        /** 这一次的框（像素），日志与单测都要能对上号 */
        val targetW: Int,
        val targetH: Int
    ) {
        /**
         * 上下留边合计（px）。现在**恒为 0**：产出逐值等于框，照片区里没有一条留白。
         *
         * 保留这个字段是有意的 —— 日志里 `留边=0px(0%)` 就是"没有留白"这条结论的**判据**，
         * 而它曾经是 74px(28%)（用户："图片保持不变会流出很多空白"）。哪一天它又变成正数，
         * 说明"框被填满"这条规则被人改回去了。
         */
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
     * 纯函数（可单测）：**图比框更宽（或同比例）吗** —— 也就是"要填满框的话，该裁的是哪条边"。
     *
     * `r图 ≥ r框` ⟺ `图宽/图高 ≥ 框宽/框高` ⟺ `图宽 × 框高 ≥ 框宽 × 图高`。
     * 这里用**整数交叉相乘**（并且先转 Long）而不是两个 float 相除相比：
     *  - float 比较在"两边比例完全相等"时会被浮点误差判反（例：720×229 的图进 594×189 的框，
     *    两边都是 3.14:1），那会让本该"整张都在"的情况平白切掉一条边 —— 用户按裁剪框裁好的
     *    照片，落进组件里就少一列像素；
     *  - 交叉相乘在 Int 上会溢出（4000×4000×… 很容易越过 2^31），所以先转 Long。
     *
     * 成立 → 保住**整高**、左右各裁一条（框比图更高时就是这一档）；不成立 → 保住**整宽**、
     * 上下各裁一条。这一档判据只决定"裁哪条边"，**不再决定"要不要留边"**（见类注释）。
     */
    fun fitsWhole(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Boolean {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return false
        return srcW.toLong() * targetH.toLong() >= targetW.toLong() * srcH.toLong()
    }

    /**
     * 纯函数（可单测）：**从源图上取哪一块** —— 与框同比例、居中、尽可能大（填满框的那一块）。
     *
     * 本轮的规则只有这一条：产出永远是"框被填满"，所以窗口**永远**是与框同比例的矩形：
     *
     * ```
     * fitsWhole（图比框宽/同比例）→ 保住整高：宽 = round(srcH × r)，左右居中
     * 否则（图比框高/更窄）        → 保住整宽：高 = round(srcW ÷ r)，上下居中
     * ```
     *
     * 两种极端都各有一半会被裁掉，取"能取到的最大的那一块"（也就是只裁一条边）。
     * 比例一致时（用户按裁剪框裁出来的图）这个矩形恰好**等于整张源图** → 一个像素都不裁，
     * 宿主那边也就什么都不用做。
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
        return if (fitsWhole(srcW, srcH, targetW, targetH)) {
            // 图比框更宽（或同比例）：保住整高，左右各裁一条
            val w = minOf(srcW, Math.round(srcH.toFloat() * r).coerceAtLeast(1))
            Window(w, srcH, Math.round((srcW - w) * 0.5f), 0)
        } else {
            // 图比框更高/更窄：保住整宽，上下各裁一条
            val h = minOf(srcH, Math.round(srcW.toFloat() / r).coerceAtLeast(1))
            Window(srcW, h, 0, Math.round((srcH - h) * 0.5f))
        }
    }

    /**
     * 纯函数（可单测）：这一次渲染的完整结论（[Layout]）—— 上面那条唯一的规则。
     *
     * 这是渲染链路上**唯一**决定"裁哪一块、缩到多大"的地方（[PhotoBitmap.decode] 只负责
     * 把这里的算术喂给 Bitmap 工厂），所以"填满框、比例一致就整张显示、不一致就居中取一块"
     * 这件事能在一个纯函数上被穷举验证。
     *
     * 产出**逐值等于框**：宿主端 `fitCenter` 于是是恒等变换（不缩放、不裁剪、不留边）。
     */
    fun layout(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Layout? {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return null
        val window = centerWindow(srcW, srcH, targetW, targetH) ?: return null
        // 窗口 = 整张源图 ⟺ 比例一致（取整误差之内）→ 日志写"完整显示"；否则"取中间块"。
        // 注意这里**不是**上一版的"放得下"：产出永远等于框，没有留边那一档了。
        return Layout(
            whole = window.width >= srcW && window.height >= srcH,
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
