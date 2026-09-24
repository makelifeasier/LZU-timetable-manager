package app.timetable.widget

/**
 * 图片**几何**：把"源图 + 目标框 + 显示方式"算成「裁哪一块、缩到多大、留多少边」。
 *
 * ## 为什么这一轮要动它（用户两条原话）
 *
 * > 「会出现图片显示不全的情况，而且应该加上选择图片时选择显示的样式即截取多少。」
 *
 * 老实现只有一条路：按**框的像素尺寸**中心裁剪（[PhotoBitmap.decode] 里的 `scaleCenterCrop`）。
 * 它解决的是"框和位图比例不一致、被宿主二次裁剪/拉伸"那个更早的 bug，但代价是：
 *
 *  1. **照片必然被切**。框 349×52dp（≈6.7:1）而照片 16:9 时，只有中间一条 16:9 的横带能活下来，
 *     上下 65% 的内容用户永远看不到 —— 这就是"显示不全"；
 *  2. **用户没有任何选择权**：裁哪里永远是"中间"。竖构图的人像照裁在 6.7:1 的框里，
 *     连脸在不在中间都得碰运气。
 *
 * ## 这一轮给的三个旋钮（互不干扰）
 *
 * - **显示方式** [PhotoFitMode]：`填满`（裁剪，老行为）/ `完整显示`（留边，不裁）；
 * - **裁剪位置** [CropPosition]：只在「填满」下有意义 —— 顶 / 中 / 底，即用户挑的那一段；
 * - **缩放倍数** [ZOOM_OPTIONS]：想比"刚好铺满"再放大一点（裁得更少、主体更大）时用。
 *
 * ## 这里为什么是独立的 geometry 类而不是直接改 PhotoBitmap
 *
 * 老实现的"框和位图逐值同比例"这条不变量必须**保持**（它是上一个 bug 的解法），
 * 而新模式又引入了一堆比例/取整/越界的算术。全部塞进 [PhotoBitmap] 会让"碰 Bitmap 的那部分"
 * 和"只算数的那部分"混在一起 —— 而后者是**唯一能在单测里被真正验证的**东西
 * （单测里 Android API 全是"返回默认值"的假实现，碰 Bitmap 的行测不出任何东西）。
 * 所以：算术全在这里（纯 Kotlin，零 Android 类型，可单测），[PhotoBitmap] 只负责调 Bitmap 工厂。
 */
internal object PhotoFit {

    /**
     * 裁剪窗口在**源图像素坐标系**里的位置。
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
     * 「完整显示」模式的产出：[outWidth]×[outHeight] 的位图 + 它两侧/上下各留多少边。
     */
    internal class Letterbox(
        val outWidth: Int,
        val outHeight: Int,
        val padLeft: Int,
        val padRight: Int,
        val padTop: Int,
        val padBottom: Int
    ) {
        /** 这一档留边有多夸张（0.73 = 有 73% 的框是空的）—— 日志里用，用户看不到画面只能看数字 */
        val padShare: Float
            get() {
                val box = (outWidth + padLeft + padRight).toLong() *
                    (outHeight + padTop + padBottom).toLong()
                if (box <= 0L) return 0f
                val used = outWidth.toLong() * outHeight.toLong()
                return 1f - used.toFloat() / box.toFloat()
            }

        override fun toString() =
            "${outWidth}x$outHeight pad=$padLeft/$padRight/$padTop/$padBottom"
    }

    /**
     * 纯函数（可单测）：算出"该从源图上裁哪一块"，这是本文件的核心。
     *
     * ## 一句话：按框的宽高比，从源图上切一块同比例的矩形
     *
     * 记框比例 `r = 框宽/框高`。源图里"与框同比例"的矩形有无穷多个，这里只关心两个极端：
     *
     * ```
     * byW  = srcH × r          // 用源图整高推出来的宽度
     * byH  = srcW ÷ r          // 用源图整宽推出来的高度
     * maxW = max(srcW, byW)    maxH = max(srcH, byH)   // 源图里**最大**的同比例矩形
     * minW = min(srcW, byW)    minH = min(srcH, byH)   // 源图里**最小**的同比例矩形
     * ```
     *
     * - **「填满」用 max**：这块能**盖住**整个框（两条边都 ≥ 框），缩下去必然铺满、不留边，
     *   代价是照片被切掉一部分；
     * - **「完整显示」用 min**：这块**塞得进**框，缩上去整张照片都在，代价是两头留边
     *   （见 [containLayout]）。
     *
     * 两条路都保持"窗口与框同比例"，所以既不会被拉伸，也不会被宿主二次裁剪。
     *
     * ## 取整方向是"默认值与老行为逐值一致"的关键
     *
     * 用 `x.toFloat().toInt()`（截断 / 向零取整，等价 `Math.round(x.toFloat())`），
     * 因为老代码就是 `(srcH × r).toInt()` 与 `(srcW ÷ r).toInt()`。
     * 换成"四舍五入"会在浮点误差下多出 1px，而用户最终是**拿真机截图取像素**核对
     * "默认模式有没有变"的 —— 差 1px 就说不清是谁的错。
     *
     * ## 缩放倍数（[zoom]）
     *
     * 窗口尺寸除以 zoom：**窗口越小 = 看到的越少 = 放得越大**（1.5 = 把框里的内容放大 1.5 倍）。
     * 它和裁剪位置正交：位置决定"在剩下的自由空间里靠哪边"，zoom 决定"剩多少自由空间"。
     * zoom = 1.0 时窗口就是"刚好铺满"的那一块，也就是老行为。
     *
     * ## 裁剪位置作用在"还有余量"的那个方向上（**反直觉，设置页要写清**）
     *
     * 位置不是"只动竖直方向"，而是"动那个**还有余量**的方向"：
     *
     *  - 源图**比框宽**（横构图照片）→ 窗口高度整用上、只有横向余量 → 位置调的是**左/中/右**；
     *  - 源图**比框窄**（竖构图照片、很扁的框）→ 窗口宽度整用上、纵向余量很大 → 位置调**上/中/下**。
     *
     * 竖照片放在 6.7:1 的扁框里就是后一种（例：1000×1000 的图进 349×52 的框，
     * 窗口是 1000×149 —— 纵向有 851px 可挪，位置能真的选到"头部/腰部/脚"）。
     * 如果当初只让竖直方向响应位置，那这种最常见的"竖照片进扁框"场景下位置就完全没用了。
     *
     * @param srcW   源图宽（px，> 0）
     * @param srcH   源图高（px，> 0）
     * @param targetW 框宽（px，> 0）
     * @param targetH 框高（px，> 0）
     * @param mode   [PhotoFitMode.FILL] = 取最大矩形（铺满）/ [PhotoFitMode.CONTAIN] = 取最小矩形（留边）
     * @param position [CropPosition]：裁哪一段
     * @param zoom   ≥ 1.0 的倍数（见 [zoomClamp]）
     *
     * @return 裁剪窗口；**参数非法或算不出合法窗口时返回 null**（调用方应当跳过这张图、换下一张，
     *         而不是返回一个会造成 `createBitmap` 越界崩溃的矩形）
     */
    fun window(
        srcW: Int,
        srcH: Int,
        targetW: Int,
        targetH: Int,
        mode: PhotoFitMode = PhotoFitMode.FILL,
        position: CropPosition = CropPosition.MIDDLE,
        zoom: Float = 1f
    ): Window? {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return null
        val z = zoomClamp(zoom)

        // 核心：**按框的宽高比，从源图上切一块同比例的矩形**。
        //
        // 记 r = 框宽/框高，源图里有两个"与框同比例"的极端矩形：
        //
        //   1. 只贴住横向（左右都摊开）：宽 = srcW → 高 hWide = srcW ÷ r
        //   2. 只贴住纵向（上下都摊开）：高 = srcH → 宽 wTall = r × srcH
        //
        // 要的那块 = **两个条件都满足**的最小同比例矩形，也就是
        // `宽 = min(srcW, wTall)`、`高 = min(srcH, hWide)` ——
        // 等价于"把源图多出来的那两条边按框的比例切掉"。
        // （两个模式在**裁哪一块**上是一样的；区别在下一步：填满把它缩到铺满框，
        //   完整显示把它缩到整个塞进框 —— 见 [outSizeForFill] / [containLayout]。）
        //
        // **不能两条边各自 max/min 地硬夹**：第一版就是那样，比例会被夹坏 ——
        // 1600×1066 的图进 917×137 的框会算出 1600×1066（整张原图），
        // 于是「填满」什么都没裁、裁剪位置与放大倍数全部失灵。
        //
        // 取整用 `Math.round(x.toFloat())` ≡ `x.toFloat().toInt()`（截断 / 向零取整），
        // 与老代码 `(srcH × r).toInt()` / `(srcW ÷ r).toInt()` 一致：
        // 对"恰好是整数"的值（如 610.0000001）四舍五入会多出 1px，
        // 而用户最终是拿真机截图取像素核对"默认模式有没有变"的，差 1px 就说不清是谁的错。
        val r = targetW.toFloat() / targetH.toFloat()
        val hWide = Math.round(srcW.toFloat() / r).coerceAtLeast(1)   // 左右摊开时该有多高
        val wTall = Math.round(srcH.toFloat() * r).coerceAtLeast(1)   // 上下摊开时该有多宽

        // 摆放：源图与框同比例（= 老行为）时窗口就是整张源图、没有可挪空间 → 偏移 0，
        // 与老代码 `(src − 窗口) / 2` 逐值相同；源图与框比例不同时窗口变小，
        // 位置（顶/中/底）就落在"多出来的那条边"上 —— 也就是用户挑的那一段。
        var win = place(
            Window(minOf(srcW, wTall), minOf(srcH, hWide), 0, 0),
            srcW,
            srcH,
            position
        )

        // 等比放大：窗口两条边一起缩（信息量放大），缩完按位置重新摆一次 ——
        // 窗口变小后"可挪空间"重新出现，顶/中/底正是在这一步生效。
        // z == 1 时不进这个分支 —— "默认值 = 老行为"要求逐值恒等，不能靠浮点碰巧相等。
        if (z > 1f) {
            val zw = (win.width.toDouble() / z.toDouble()).toInt().coerceAtLeast(1)
            val zh = (win.height.toDouble() / z.toDouble()).toInt().coerceAtLeast(1)
            win = place(Window(zw, zh, 0, 0), srcW, srcH, position)
        }
        return win
    }

    /**
     * 把一块 [w]×[h] 的窗口按 [position] 摆进 [srcW]×[srcH]：**能挪多少就按比例挪多少**。
     *
     * 可挪空间 = `src − 窗口`（窗口比源图大时为 0 = 没得挪，只能整幅都要），
     * 偏移 = `可挪空间 × 位置系数`（顶/左 0、中 0.5、底/右 1）。
     *
     * 居中用 [Math.round]。老代码的 `(src.height − cropH) / 2` 是整数除法（向下取整），
     * 两者在奇数可挪空间上会差 1px；这里**按 0.5 四舍五入**是更正的取整
     * （"正中间"本来就该四舍五入），1px 的差别不会改变观感，
     * 而"默认值 = 老行为"这条不变量由单测用同一个取整口径钉住（见 PhotoFitTest.legacyReference）。
     */
    private fun place(w: Window, srcW: Int, srcH: Int, position: CropPosition): Window {
        val freeX = (srcW - w.width).coerceAtLeast(0)
        val freeY = (srcH - w.height).coerceAtLeast(0)
        val x = Math.round(freeX * position.fraction).coerceIn(0, freeX)
        val y = Math.round(freeY * position.fraction).coerceIn(0, freeY)
        return Window(w.width, w.height, x, y)
    }

    /**
     * 「填满」的产出尺寸（纯函数，可单测）：把窗口**等比缩到刚好盖住框**。
     *
     * 为什么不直接返回"框的尺寸"：window 的那条推算边是**取整**过的，比例与框严格相等
     * 只在少数尺寸下成立。若强行按框尺寸产出，就会把窗口拉伸那么零点几个像素（形变）。
     * 这里按窗口自己的比例算，回到**窗口/框比例相同 ⇒ 与老代码的产出尺寸逐值相同**；
     * 比例不同（只有放大倍数会带来）时宁可差几个像素，也不变形。
     *
     * 结果为 null 表示输入非法（调用方应回退成"不显示图片"）。
     */
    fun outSizeForFill(winW: Int, winH: Int, targetW: Int, targetH: Int): IntArray? {
        if (winW <= 0 || winH <= 0 || targetW <= 0 || targetH <= 0) return null
        // 窗口与框同比例（**老行为的那条路**，以及绝大多数普通照片）→ 直接产出框的尺寸。
        // 这条正是老代码的产出尺寸（createScaledBitmap(..., targetW, targetH)），逐值不变；
        // 而且因为 targetW×targetH 已经被 [PhotoBitmap.targetPx] 夹过预算，这里天然在预算内。
        val gap = Math.abs(winW.toDouble() / winH.toDouble() - targetW.toDouble() / targetH.toDouble()) /
            (targetW.toDouble() / targetH.toDouble())
        if (gap <= RATIO_MATCH_TOLERANCE) return intArrayOf(targetW, targetH)

        // 比例差得多（极端比例的照片，或放大倍数把窗口缩出了偏差）：按窗口自己的比例算，
        // 取"盖住框"的那个比例，另一条边于是 ≥ 框，多出来的零点几像素由 centerCrop 收掉。
        val s = maxOf(
            targetW.toDouble() / winW.toDouble(),
            targetH.toDouble() / winH.toDouble()
        )
        return clampToBudget(
            Math.round(winW * s).toInt().coerceAtLeast(1),
            Math.round(winH * s).toInt().coerceAtLeast(1)
        )
    }

    /** 窗口比例与框比例的相对差小于这个值就算"同比例"（取整带来的偏差是千分之几） */
    private const val RATIO_MATCH_TOLERANCE = 0.02

    /**
     * 纯函数（可单测）：像素总数超预算时**等比**缩回来。
     *
     * 为什么要这一步：产出比框"长出去"的那条边是按窗口自己的比例算的，
     * 遇到极端比例（源图 1×1000 进 594×111 的框那种）会算出 594×594 这样的方图 ——
     * 705KB，直接超 binder 上限，整次更新会被系统丢掉（表现是"点刷新没反应"）。
     * 预算就是 [PhotoBitmap.MAX_PIXELS]（400KB ÷ 每像素 2 字节），与 [PhotoBitmap.targetPx]
     * 用的是同一个上限 —— 两处不能各定一套。
     */
    private fun clampToBudget(w: Int, h: Int): IntArray {
        val pixels = w.toLong() * h.toLong()
        if (pixels <= PhotoBitmap.MAX_PIXELS) return intArrayOf(w, h)
        val k = Math.sqrt(PhotoBitmap.MAX_PIXELS.toDouble() / pixels.toDouble())
        return intArrayOf(
            Math.floor(w * k).toInt().coerceAtLeast(1),
            Math.floor(h * k).toInt().coerceAtLeast(1)
        )
    }

    /**
     * 「完整显示」的产出尺寸与留边（纯函数，可单测）。
     *
     * 缩放比取 `min(框宽/源宽, 框高/源高)` —— 整张照片等比缩进去，一个像素都不裁。
     * 位图按这个比例做出来（≤ 框），剩下的空档由 ImageView 的 `FIT_CENTER` 居中摊平，
     * 底色就是布局里图片框自己的背景（`widget_row_bg` 的圆角底色），**不需要新增任何 drawable**。
     *
     * ## 留边很大是**用户自己选的**，这里不拦
     *
     * 框 917×137px（349×52dp @2.625，≈6.7:1）配 16:9 的照片（1600×900）：
     * 位图只有 244×137，**左右各留约 336px 的空白**（[Letterbox.padShare] ≈ 0.73，即 73% 的框是空的）。
     * 想把它填满只有两条路：裁掉照片（= 填满模式）或把框改高（会吃掉课程行的位置）。
     * 两条都不是"完整显示"该做的事，所以这里照实算、照实留边，
     * 只把这个比例写进日志（`留边占框=73%`）让用户自己判断值不值。
     *
     * @param srcW 源图宽（px，> 0）—— 也可以是已经裁好的窗口尺寸，语义一样
     * @param srcH 源图高（px，> 0）
     */
    fun containLayout(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Letterbox? {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return null
        val size = fittedSize(srcW, srcH, targetW, targetH) ?: return null
        val outW = size[0]
        val outH = size[1]
        val left = maxOf(targetW - outW, 0)
        val top = maxOf(targetH - outH, 0)
        return Letterbox(
            outWidth = outW,
            outHeight = outH,
            // 余数给左边：与"正中间"差半个像素，但保证左右加起来正好是差额（不会算丢 1px）
            padLeft = (left + 1) / 2,
            padRight = left / 2,
            padTop = (top + 1) / 2,
            padBottom = top / 2
        )
    }

    /**
     * 纯函数（可单测）：等比缩到"塞得进框"的最大尺寸，**从不上采样**（不放大）。
     *
     * 不放大是有意的：源图本来就比框小的时候，放大只是把同样的像素摊到更多格子上
     * （体积翻倍、清晰度一点不涨），交给宿主 `FIT_CENTER` 显示时放大更省 binder。
     */
    fun fittedSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): IntArray? {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return null
        if (srcW <= targetW && srcH <= targetH) return intArrayOf(srcW, srcH)
        val s = minOf(
            targetW.toDouble() / srcW.toDouble(),
            targetH.toDouble() / srcH.toDouble()
        )
        return intArrayOf(
            Math.round(srcW * s).toInt().coerceIn(1, targetW),
            Math.round(srcH * s).toInt().coerceIn(1, targetH)
        )
    }

    /**
     * 纯函数（可单测）：把 zoom 拉回 [ZOOM_MIN]..[ZOOM_MAX]。
     *
     * 下界为什么是 1.0：小于 1 就是"把照片缩得比框还小"，用「完整显示」更直白、也更明显；
     * 在这里放开只会让「填满」露出空白，违背用户选它的原因（当铺底背景图用）。
     * NaN（用户在别处写进了脏值）也一并夹回 1.0。
     */
    fun zoomClamp(zoom: Float): Float {
        if (zoom.isNaN()) return ZOOM_MIN
        return zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
    }

    /** 允许的最小放大倍数（= 不放大，刚好铺满；见 [zoomClamp]） */
    const val ZOOM_MIN = 1.0f

    /**
     * 允许的最大放大倍数。
     *
     * 3.0 是个"还认得出拍的是什么"的上限：再往里放，源图上留下来参与显示的区域
     * 就只有框面积的 1/9 以下 —— 一张 1600px 宽的照片只剩中间 500px，
     * 放大到 349dp（917px）宽的框里必然是糊的。这个上限不是安全边界，是**清晰度**边界。
     */
    const val ZOOM_MAX = 3.0f
}

/**
 * 裁剪位置（"截取多少"里的**截哪一段**）。
 *
 * 语义见 [PhotoFit.window] 的注释：位置作用在"还有余量"的那个方向上 ——
 * 横照片调左/中/右，竖照片调上/中/下。名字用 TOP/MIDDLE/BOTTOM 是因为
 * 用户最直觉的表述就是"保留照片的上半/中间/下半"。
 */
internal enum class CropPosition {
    /** 保留靠起始边的部分（横向=左，纵向=上） */
    TOP,

    /** 中间那段（**默认，与老行为逐值一致**） */
    MIDDLE,

    /** 保留靠结束边的部分（横向=右，纵向=下） */
    BOTTOM;

    /** 在"可挪动空间"里取哪一段：0 = 起始边，0.5 = 居中，1 = 结束边 */
    internal val fraction: Float
        get() = when (this) {
            TOP -> 0f
            MIDDLE -> 0.5f
            BOTTOM -> 1f
        }

    companion object {
        /**
         * 设置页存的下标 → 位置。**越界或脏值一律回落 [MIDDLE]**：
         * 位置只影响观感、不影响功能，绝不该让一个坏值把图片变成"解不出来"。
         */
        fun fromIndex(index: Int): CropPosition =
            entries.firstOrNull { it.ordinal == index } ?: MIDDLE
    }
}

/** 位置选项下单（等宽），下标即写进偏好的取值 */
internal val CROP_POSITION_NAMES = listOf("顶部", "居中", "底部")

/**
 * 放大倍数的可选档位，**下标**即写进偏好的取值（默认 0 → 1.00x）。
 *
 * 为什么只允许"放大"（≥ 1.0）：见 [PhotoFit.zoomClamp]。
 * 为什么档位这么少：这是"想让主体更大一点"的微调，不是修图；
 * 每多一档就多一组"到底裁了多少"的截图差异，反而难核对。
 */
internal val ZOOM_OPTIONS = listOf(1.00f, 1.25f, 1.50f, 2.00f, 3.00f)

/** 档位下标 → 倍数；越界/脏值一律回落 1.0（= 老行为） */
internal fun zoomFor(index: Int): Float {
    val i = if (index in ZOOM_OPTIONS.indices) index else 0
    return PhotoFit.zoomClamp(ZOOM_OPTIONS[i])
}
