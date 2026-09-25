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
 * 但框里画的是**两件事的合成**：
 *
 * 1. **整张照片**（[Layout.photo]，[containRect]）—— contain：整张、居中、尽量大，
 *    **永远不越出框**。这一条是硬的：照片一个像素都不许被遮住；
 * 2. **背景**（[Layout.backdrop] 为真时才画）—— 把同一张照片按 cover 取一块（[centerWindow]）
 *    缩到很小的尺寸再用双线性放大铺满整框 = 一张廉价的模糊底图，用来补齐照片与框之间
 *    那几条边（见 [PhotoBitmap.decode]）。
 *
 * 记 `r框 = 框宽/框高`、`r图 = 图宽/图高`，两种可观察的结果：
 *
 * 1. **比例一致**（`r图 == r框`，在 dp→px 取整的误差内）→ 整张正好等于框
 *    → [Layout.whole] = true，日志写「整张(铺满)」：用户按裁剪框裁出来的照片原样出现，
 *    一个像素都不裁、也不需要背景（这也是导入时那条框与组件同形带来的常态）；
 * 2. **比例不一致**（框被拉高/压扁之后）→ 整张按 contain 缩进框里居中，四周那几条边由
 *    **同一张照片的模糊版本**填满 → [Layout.whole] = false，日志写「整张(居中+模糊底)」。
 *
 * ### 为什么这一轮把"裁掉一条边"换成"模糊底"（用户原话）
 *
 * > 「下方图片显示依然有被遮住一小部分的问题，你能不能一次性解决」
 *
 * 上一轮的规则是"框比图高/矮 → 从源图内部居中取一块，宁可裁一点、也不留白"。问题在于
 * **框的形状由组件格子决定，照片的形状由用户裁剪时决定**，两者一旦不一致（改过组件尺寸、
 * 换过手机、裁剪框比例本身与框不同），用户就会看见"我的照片被遮住了一部分"——
 * 而这是不可能靠调数字躲开的，只能改规则：**照片永远整张**，差出来的那点面积拿照片自己的
 * 模糊版本去补。于是"被遮住"与"留一条空白"这两件事同时不再可能发生。
 *
 * 代价说清楚：比例不一致时照片会比"裁满"小一圈（比例差多少，就小多少），换来的是**整张都在**。
 *
 * ### 为什么产出必须逐值等于框（而不是"宽等于框宽、高按图自身的比例"）
 *
 * 位图逐值等于框，宿主 `fitCenter`（见 `widget_today.xml`）就是**恒等变换**：
 * 既不缩放也不裁剪，画面完全由我们这边的算术决定 —— 这也意味着"组件里看到的那一块"
 * 与"这里算出来的矩形"逐像素对应，用户拿截图取像素核对才有意义。
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
     * 整张照片**落在框里的哪一块**（px）—— 坐标是相对图片框左上角的。
     *
     * 这是"整张都在"这个承诺的**判据**：`PhotoRender` 一定把整张源图缩到这个矩形里，
     * 所以只要 [PhotoRect.width]/[PhotoRect.height] ≤ 框，照片就没有一个像素被裁掉。
     */
    internal class PhotoRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    ) {
        override fun toString() = "${width}x$height@($x,$y)"
    }

    /**
     * 这一次渲染的结论：走哪条分支、整张照片摆在哪、背景从源图上取哪一块、产出多大的位图。
     */
    internal class Layout(
        /**
         * true = **整张正好铺满框**（框与照片比例一致 → 不需要背景填充）；
         * false = **整张居中 + 两侧（或上下）用同一张照片的模糊版本填满**。
         *
         * 判据仍然是"取到的那块背景窗口是否等于整张源图"，也就是"比例是否一致"：
         * 比例一致时 [PhotoRect] 正好等于框，产出与上一版逐像素相同；
         * 比例不一致时**照片本身不再被裁**（见 [photo]），只是它旁边那几条边由模糊背景补齐。
         */
        val whole: Boolean,
        /** 背景（模糊填充）从源图上取的那一块 = 覆盖整框的那一块（cover 规则） */
        val window: Window,
        /** **整张**照片在框里的矩形（contain 规则，居中） */
        val photo: PhotoRect,
        val outWidth: Int,
        val outHeight: Int,
        /** 这一次的框（像素），日志与单测都要能对上号 */
        val targetW: Int,
        val targetH: Int
    ) {
        /**
         * **纯底色**留白合计（px）—— 现在**恒为 0**：整块照片区要么是照片本身，要么是这张照片
         * 自己的模糊版本，没有一条平底色的空白。
         *
         * 保留这个字段是有意的 —— 日志里 `留边=0px(0%)` 就是"照片区里没有平底色空白"这条结论的
         * **判据**，而它曾经是 74px(28%)（用户："图片保持不变会流出很多空白"）。哪一天它又变成正数，
         * 说明"整块区域必须被内容填满"这条规则被人改回去了。
         *
         * 注意它与 [photo] 的尺寸是两件事：照片**完整**（[photo] 永远 ≤ 框）与照片**铺满**
         * 只有在比例一致时才同时成立；比例不一致时靠 [backdrop] 补齐，仍然不是留白。
         */
        val padY: Int get() = (targetH - outHeight).coerceAtLeast(0)

        /** 留边占框的比例（日志用：0 = 没有平底色，0.27 = 27% 的框是平底色） */
        val padShare: Float
            get() = if (targetH <= 0) 0f else padY.toFloat() / targetH.toFloat()

        /**
         * 照片没铺满框 → 四周那几条边用**同一张照片的模糊版本**填满（见 [PhotoBitmap.decode]）。
         *
         * 为什么不是"留白"也不是"裁掉"：用户要的是"整张都在"（不裁），而组件又不能让照片区里
         * 出现一条平底色（那是他上一轮反馈的"空白"）。剩下的唯一做法就是拿照片自己的像素去填。
         */
        val backdrop: Boolean get() = !whole

        /** 日志/单测里那一列结论（用户看不到画面，只能看日志，所以这一列必须自解释） */
        val verdict: String get() = if (whole) "整张(铺满)" else "整张(居中+模糊底)"

        override fun toString(): String = if (whole) {
            "$verdict ${outWidth}x$outHeight 留边=${padY}px"
        } else {
            "$verdict 照片=$photo 底窗=$window ${outWidth}x$outHeight"
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
     * 纯函数（可单测）：**整张照片摆在框里的哪个矩形** —— contain 规则（整张、居中、尽量大）。
     *
     * 这一条是"照片一个像素都不许被遮住"的实现：它产出的矩形**永远不越出框**，
     * 而且是[整张]源图缩进去 —— 与 [centerWindow]（cover：铺满框、要裁掉一条边）正好互补：
     *
     * ```
     * 图比框宽/同比例（fitsWhole）→ 宽度顶满：宽 = 框宽，高 = round(源高 × 框宽 ÷ 源宽)，上下居中
     * 否则（图比框高/更窄）        → 高度顶满：高 = 框高，宽 = round(源宽 × 框高 ÷ 源高)，左右居中
     * ```
     *
     * 比例一致时（用户按裁剪框裁出来的图落进与它同形的框）这个矩形**正好等于框**
     * —— 于是那一条路与上一版产出的位图逐像素相同，模糊背景也根本不会被画。
     *
     * 取整用 `Math.round` 并夹在 1..框内：与 [centerWindow] 同一套口径（否则那 1px 的差额
     * 会让"整张"的判定在同一档高度上忽真忽假），并且保证画布不会越界
     * （`Canvas.drawBitmap` 的 dst 矩形越界不抛异常，但会把照片切掉一条 —— 那正是要根治的观感）。
     */
    fun containRect(srcW: Int, srcH: Int, boxW: Int, boxH: Int): PhotoRect? {
        if (srcW <= 0 || srcH <= 0 || boxW <= 0 || boxH <= 0) return null
        return if (fitsWhole(srcW, srcH, boxW, boxH)) {
            // 图比框宽（或同比例）→ 宽度顶满：高 = 框宽 × 源高 ÷ 源宽
            val w = boxW
            val h = Math.round(boxW.toFloat() * srcH.toFloat() / srcW.toFloat()).coerceIn(1, boxH)
            PhotoRect(0, (boxH - h) / 2, w, h)
        } else {
            // 图比框高/更窄 → 高度顶满：宽 = 框高 × 源宽 ÷ 源高
            val h = boxH
            val w = Math.round(boxH.toFloat() * srcW.toFloat() / srcH.toFloat()).coerceIn(1, boxW)
            PhotoRect((boxW - w) / 2, 0, w, h)
        }
    }

    /**
     * 纯函数（可单测）：这一次渲染的完整结论（[Layout]）—— 上面两条规则的合成。
     *
     * 这是渲染链路上**唯一**决定"整张照片摆在哪、背景取哪一块、缩到多大"的地方
     * （[PhotoBitmap.decode] 只负责把这里的算术喂给 Bitmap 工厂），所以
     * "整张都在、比例一致就铺满、不一致就用模糊底补齐"这件事能在一个纯函数上被穷举验证。
     *
     * 产出**逐值等于框**：宿主端 `fitCenter` 于是是恒等变换（不缩放、不裁剪、不留边）。
     */
    fun layout(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Layout? {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return null
        val window = centerWindow(srcW, srcH, targetW, targetH) ?: return null
        val photo = containRect(srcW, srcH, targetW, targetH) ?: return null
        // 窗口 = 整张源图 ⟺ 比例一致（取整误差之内）→ 日志写"整张(铺满)"，此时 photo 也正好等于框
        // （单测逐值钉住这一条：比例一致那一档，产出与上一版完全相同）。
        val whole = window.width >= srcW && window.height >= srcH
        return Layout(
            whole = whole,
            window = window,
            photo = photo,
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
