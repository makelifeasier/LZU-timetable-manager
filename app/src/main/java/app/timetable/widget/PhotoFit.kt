package app.timetable.widget

/**
 * 图片**几何**：把「一张（用户已经自己裁好的）图片 + 小组件这一轮给图片区的框」算成
 * 「裁哪一块、缩到多大」。全是纯算术（零 Android 类型），所以它才是唯一能在单测里
 * 真正被验证的那部分 —— 单测里 Android API 全是"返回默认值"的假实现，
 * 碰 Bitmap/Canvas 的行测不出任何东西（见 `testOptions.unitTests.isReturnDefaultValues`）。
 *
 * ## 这一轮为什么这么改（用户原话）
 *
 * > 「不错，效果好，但是图片上下怎么有模糊的像素图片块？其实我觉得也可以适当调大裁剪的大小
 * >   让这些模糊色块消失」
 *
 * 也就是说，"照片整张居中 + 差出来的那点面积拿它自己的模糊版本补边"这个方案被真机观感否掉了。
 *
 * ## 现在只有一条规则：**填满**（cover），框里只画一层
 *
 * 从（用户已经裁好的）源图里取一块**与框同比例、居中、尽可能大**的矩形（[centerWindow]），
 * 缩到框的像素尺寸**铺满**整框 —— 没有第二层、绝不留白、绝不拉伸。
 * 记 `r框 = 框宽/框高`、`r图 = 图宽/图高`，两种可观察的结果：
 *
 * 1. **比例一致**（`r图 == r框`，dp→px 取整的误差之内）→ 取到的就是**整张源图**
 *    → [Layout.whole] = true，日志写「整张(铺满)」：照片原样出现，一个像素都不裁。
 *    导入时那条裁剪框（[PhotoCrop.frame]）就是按"内容宽 : 照片需要的高"画出来的，
 *    所以在**导入时那个组件尺寸**上框与照片同形 → 走这一条；
 * 2. **比例不一致**（用户后来拖过组件尺寸：框高 = 列表下方的剩余空间，随组件高度变）
 *    → 取源图内部**正中**那一块 → [Layout.whole] = false，日志写「取中间块」：
 *    只裁一条边（能取到的最大那一块）、居中，画面仍然铺满、比例仍然不变形。
 *
 * ### 为什么**不要**模糊底（这一整段就是为了防止它被加回来）
 *
 * 上一版的规则是"照片永远整张（contain），照片与框之间差出来的面积用**同一张照片缩到 1/8
 * 再双线性放大**得到的模糊版本补齐"。它的动机是"既不裁、又不留白" —— 而问题是：
 *
 *  - 1/8 再放大回来，像素被插值拉成了**肉眼可见的块状色块**，正好落在照片上下那两条边上
 *    （用户原话："图片上下怎么有模糊的像素图片块"）；
 *  - 为了这几条边要多解一块 cover 位图、多造一张 1/8 的临时位图、多画一遍整框，
 *    换来的还是一块"假照片"。
 *
 * 现在的取舍写清楚：**宁可多裁一点，也要画面干净**。而"裁多少"这件事**不归程序拍**：
 * 导入时那条裁剪框的形状来自"内容宽 : 照片需要的高"，而渲染这一侧**永远取能取到的最大那一块**
 * （只裁一条边、居中）—— 想少裁、想多留，唯一的做法是让用户在导入时把裁剪框里的东西选多一点：
 * **裁量归用户**，渲染侧只负责"填满且不变形"。
 *
 * **结论：框里永远只有一层（那张照片自己），不要再加第二层背景** —— 模糊底不行，
 * 纯色底、第二张图、"先铺一层再压一层"的写法都不行。见 PhotoBitmap 类注释里那段
 * "框里只画一层"，以及 PhotoFitTest 里那条源码级断言（整个 widget 包只许有一次 `drawBitmap`）。
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
     * @param width  取块宽度（px，≥ 1 且 ≤ 源宽）
     * @param height 取块高度（px，≥ 1 且 ≤ 源高）
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
     *
     * 注意这里**没有**"照片摆在框里哪个矩形"这种东西了：框里只有一层、而且铺满，
     * 所以那一层在框坐标系里永远就是整框（0,0,outWidth,outHeight）—— 唯一值得记下来的是
     * [window]（**在源图坐标系里**取的是哪一块）。见类注释"为什么不要模糊底"。
     */
    internal class Layout(
        /**
         * true = **取到的就是整张源图**（框与照片比例一致 → 一个像素都没裁，日志写「整张(铺满)」）；
         * false = **取了源图内部正中那一块**（比例不一致 → 裁掉长出来的那两条边，日志写「取中间块」）。
         *
         * 判据是"取块是否等于整张源图"，也就是"比例是否一致" —— 它不再决定"要不要画背景"
         * （背景已经整条删掉了），只决定日志里那句话、以及"用户有没有被裁掉东西"这一条结论。
         */
        val whole: Boolean,
        /** 从源图上取的那一块（[centerWindow] 的结论）—— 与框同比例、居中、尽可能大 */
        val window: Window,
        /** 产出宽（px）—— **逐值等于框宽**，见类注释最后一段 */
        val outWidth: Int,
        /** 产出高（px）—— **逐值等于框高** */
        val outHeight: Int,
        /** 这一次的框（像素），日志与单测都要能对上号 */
        val targetW: Int,
        val targetH: Int
    ) {
        /**
         * **纯底色**留白合计（px）—— 现在**恒为 0**，而且是"结构上不可能不为 0"：
         * 框里只画一层铺满的照片，没有任何"没被画到"的地方。
         *
         * 保留这个字段（而不是把它写死成 0）是有意的 —— 日志里 `留边=0px(0%)` 就是
         * "照片区里没有平底色"这条结论的**判据**，而它曾经是 74px(28%)（用户："图片保持不变会流出
         * 很多空白"）。哪一天它又变成正数，说明"整块区域必须被内容填满"这条规则被人改回去了。
         */
        val padY: Int get() = (targetH - outHeight).coerceAtLeast(0)

        /** 留边占框的比例（日志用：0 = 没有平底色，0.27 = 27% 的框是平底色） */
        val padShare: Float
            get() = if (targetH <= 0) 0f else padY.toFloat() / targetH.toFloat()

        /**
         * 日志/单测里那一列结论（用户看不到画面，只能看日志，所以这一列必须自解释）。
         *
         * 只有这两句话，**没有第三种**：要么"整张(铺满)"（比例一致、零裁剪），
         * 要么"取中间块"（比例不一致、裁掉长出来的边、仍然铺满）。
         */
        val verdict: String get() = if (whole) "整张(铺满)" else "取中间块"

        override fun toString(): String =
            "$verdict 取块=$window ${outWidth}x$outHeight 留边=${padY}px"
    }

    /**
     * 纯函数（可单测）：**图比框更宽（或同比例）吗** —— 也就是"要填满框的话，该裁的是哪条边"。
     *
     * `r图 ≥ r框` ⟺ `图宽/图高 ≥ 框宽/框高` ⟺ `图宽 × 框高 ≥ 框宽 × 图高`。
     * 这里用**整数交叉相乘**（并且先转 Long）而不是两个 float 相除相比：
     *  - float 比较在"两边比例完全相等"时会被浮点误差判反（例：720×229 的图进 594×189 的框，
     *    两边都是 3.14:1），那会让本该"一个像素都不裁"的情况平白切掉一条边 —— 用户按裁剪框
     *    裁好的照片，落进组件里就少一列像素；
     *  - 交叉相乘在 Int 上会溢出（4000×4000×… 很容易越过 2^31），所以先转 Long。
     *
     * 成立 → 保住**整高**、左右各裁一条（框比图更高时就是这一档）；不成立 → 保住**整宽**、
     * 上下各裁一条。它只决定"裁哪条边"，不决定"要不要留边"（现在已经没有留边这一档了）。
     */
    fun fitsWhole(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Boolean {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return false
        return srcW.toLong() * targetH.toLong() >= targetW.toLong() * srcH.toLong()
    }

    /**
     * 纯函数（可单测）：**从源图上取哪一块** —— 与框同比例、居中、尽可能大（填满框的那一块）。
     *
     * 本轮的规则只有这一条（框里只画它一层），所以窗口**永远**是与框同比例的矩形：
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
     * @return 取块窗口；**参数非法时返回 null**（调用方应当跳过这张图、换下一张，
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
     * 纯函数（可单测）：这一次渲染的完整结论（[Layout]）—— 上面那条规则的落点。
     *
     * 这是渲染链路上**唯一**决定"从源图取哪一块、缩到多大"的地方（[PhotoBitmap.decode]
     * 只负责把这里的算术喂给 Bitmap 工厂），所以"比例一致就零裁剪、不一致就取中间那一块、
     * 两种都铺满不变形"这件事能在一个纯函数上被穷举验证。
     *
     * 产出**逐值等于框**：宿主端 `fitCenter` 于是是恒等变换（不缩放、不裁剪、不留边）。
     */
    fun layout(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Layout? {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return null
        val window = centerWindow(srcW, srcH, targetW, targetH) ?: return null
        // 取块 = 整张源图 ⟺ 比例一致（取整误差之内）→ 日志写"整张(铺满)"，此时一个像素都不裁；
        // 否则写"取中间块"（单测逐值钉住这两档：见 PhotoFitTest 的两条产出路径）。
        val whole = window.width >= srcW && window.height >= srcH
        return Layout(
            whole = whole,
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
     * 预算本身**不因为"少画了一层背景"而放宽**：[PhotoBitmap.MAX_BITMAP_BYTES] 仍然是
     * 600KB/张（那是 binder 事务余量算出来的，与画面有几层无关）。
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
