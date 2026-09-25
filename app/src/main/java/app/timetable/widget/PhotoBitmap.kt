package app.timetable.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.Log
import java.io.File

/**
 * 把私有目录里的图片**解码成"正好够显示"的 Bitmap**，直接塞进 RemoteViews。
 *
 * ## 为什么是 Bitmap，而不是让启动器去读一个 URI
 *
 * 小组件的 RemoteViews 是**由桌面宿主（另一个进程、另一个 uid）执行**的。以前用
 * `setImageViewUri(Uri.fromFile(...))` 指向 `filesDir/photos/`：宿主拿自己的 uid 读我们的私有目录
 * —— **必然 EACCES**，图片永远是一块空白的圆角矩形（这就是真机反馈的根因）。
 *
 * 换成 `content://` + 自己发 `grantUriPermission` 这条路也验证过：**走不通**。
 * `AppWidgetManager` 里没有任何 API 能问出"某个小组件 id 的宿主是谁" ——
 * `getAppWidgetInfo(id).provider.packageName` 返回的是**我们自己的 provider 组件**
 * （实测日志就是"授权=（没有宿主）"：解析出来的包名等于自己，被过滤掉了）。
 * 拿不到宿主包名，就没法定向授权。
 *
 * 于是改成 [android.widget.RemoteViews.setImageViewBitmap]：Bitmap 是**我们自己解码好的数据**，
 * 跟着 RemoteViews 一起过 binder 交给宿主，宿主不需要任何权限、我们也不需要 provider /
 * 授权 / content:// —— 路径最短，也就没有"哪一环没授权"这种失败模式。
 *
 * ## 代价与对策：binder 上限（约 1MB）
 *
 * 直接把相册原图塞进去必然超（一张 4000×3000 的 ARGB 就是 48MB），超了整次更新会被丢掉，
 * 表现是"更新了但桌面没变化"，极难查。对策有五层，全部在下面落实：
 *  1. **按显示尺寸解码**：目标就是图片框的内容宽 × 高度（换算成 px），不多解一个像素；
 *  2. `inSampleSize` 先粗降采样（2 的幂，且保证解码后**不小于**目标，避免解出马赛克）；
 *  3. `inPreferredConfig = RGB_565`；
 *  4. **解码后强制统一成 RGB_565**（[toRgb565]）—— 见下面那段真机事故；
 *  5. 像素总数硬上限 [MAX_BITMAP_BYTES]，而且**按真实 `byteCount` 判**（不是估算）。
 *
 * ## 真机事故：`inPreferredConfig` 只是**建议**，不是保证
 *
 * 用户反馈"导入图片出问题，小组件无法显示"，真机日志：
 *
 * ```
 * 扩展区 image=52dp ... 框=349x52dp(6.71:1) 窗口=窗=917x137@(0,0) 占源=100% bitmap=917x137/491KB set=ok
 * ```
 *
 * 框是 917×137px，RGB_565 应该是 917×137×2 = 251KB，日志却是 **491KB**
 * —— 正好是 917×137×4 = 502,516 字节（ARGB_8888）。也就是说：**源图带 alpha 通道时
 * （PNG、相册里的截图、部分编辑过的 JPEG），解码器会忽略 `inPreferredConfig`，照样给 ARGB_8888**。
 * 而用户在相册里最容易选的就是截图，所以这条命中率很高。
 *
 * 后果很严重：预算是按 2 字节/像素估的，实际翻倍 → 真正进 RemoteViews 的载荷远超预期 →
 * 撞上 binder 上限时系统**整次更新直接丢弃**（表现就是"点了没反应、组件一直停在旧内容"）。
 * 所以这里做两件事：解码后不管三七二十一 [toRgb565] 一次，以及每一次判断都用
 * [Bitmap.byteCount]（真实字节数）而不是"像素数 × 2"。
 *
 * ## 四角圆角遮罩（写在同一个解码流程的最后一步）
 *
 * 图片框是圆角容器（`widget_row_bg`），而送到宿主的是一张**方角**位图 —— 照片的直角会正压在
 * 容器的圆角上（用户原话："图片下部边角直角紧贴圆角，看起来很突兀"）。所以 [decode] 的最后一步是
 * [maskCorners]：把四角涂成**图片区背后那层的颜色**（不是透明 —— RGB_565 没有 alpha 通道，
 * 涂透明只会变成黑角），带抗锯齿，半径直接读容器 drawable 的 `<corners>`。
 *
 * 它**不动**上面那套预算：位图仍是 RGB_565、尺寸不变（见 [cornerFillArgb] 里"为什么不用 ARGB"）。
 * 画完之后还会读一个探针像素自查一次（[maskProbePixel]）—— 单测里画布是假的，
 * 那一步是真机上唯一能证明"遮罩真的画上去了"的东西。
 */
internal object PhotoBitmap {

    private const val TAG = "WidgetPhoto"

    /**
     * 单张图进 RemoteViews 的**真实字节**上限。
     *
     * 为什么是 600KB：binder 事务上限约 1MB，而 RemoteViews 里除了这张位图还有列表、文案、
     * PendingIntent 等指令（合计几十 KB）。**曾经压到 300KB，但那会把大组件上的图压小**：
     * 真机实测框 349dp 宽（916px）的 3.14:1 图需要 916×291×2 = 533KB，300KB 直接把它缩成
     * 694×221（再由宿主放大 → 变糊）。600KB 能让常见大图原尺寸过去，同时给
     * "ARGB 意外翻倍"和"其余指令"都留了余量（真正的第一道防线是解码后强制 RGB_565）。
     */
    const val MAX_BITMAP_BYTES = 600 * 1024

    /** [Bitmap.Config.RGB_565] 每像素 2 字节 */
    const val BYTES_PER_PIXEL = 2

    /**
     * 模糊底图的分辨率除数：背景是"把同一张照片缩到框的 1/[BLUR_DIVISOR]，
     * 再用双线性放大回整框"得到的 —— 这一步就是"廉价模糊"的全部实现
     * （见 [decode] 里那段注释：为什么需要一个模糊底，而不是留白或者裁掉）。
     *
     * 8 是实测的折中：再小（16）就糊成色块、看不出照片的内容；再大（4）边缘还看得出细节，
     * 反而像"两张照片拼在一起"。它只影响背景那几条边的观感，不影响照片本身。
     */
    const val BLUR_DIVISOR = 8

    /**
     * 圆角半径的**兜底值**（dp）—— 与 `res/drawable/widget_row_bg.xml` /
     * `widget_row_bg_dark.xml` 里的 `<corners android:radius="10dp"/>` 一致
     * （单测 [app.timetable.widget.PhotoCornerMaskTest] 会读那两个 XML 核对，防止两边漂移）。
     *
     * 平时**不用**这个数：运行时会直接从 drawable 里读（[cornerRadiusPx]）。它只在
     * `getDrawable` 返回 null / 不是 shape drawable 时兜底（少数 ROM、资源被裁剪的构建）。
     * 兜底时给一个明确的 10dp，而不是 0：0 等于"没有圆角遮罩"，照片的直角又会压在容器圆角上，
     * 那就回到了用户反馈的那个观感问题。
     */
    const val FALLBACK_CORNER_RADIUS_DP = 10

    /** 读不到根背景色时的兜底（明色）：与 `res/drawable/widget_bg.xml` 的 `<solid>` 一致 */
    const val FALLBACK_BEHIND_LIGHT = 0xFFFFFFFF.toInt()

    /** 读不到根背景色时的兜底（暗色）：与 `res/drawable/widget_bg_dark.xml` 的 `<solid>` 一致 */
    const val FALLBACK_BEHIND_DARK = 0xFF171A1F.toInt()

    /** 真透明（ARGB 全 0）：只在位图真的带 alpha 通道时才有意义（见 [cornerFillArgb]） */
    const val TRANSPARENT_ARGB = 0

    /**
     * 遮罩自查读不到像素时的哨兵值（[MaskedPhoto.cornerPixel]）。
     *
     * **不能是 -1**：-1 就是 0xFFFFFFFF（白色），而明色主题下四角填的正是白色 ——
     * 哨兵与"读回来是白色"会撞车，日志里就会把一次成功的遮罩写成 `角像素=?`
     * （这个坑是单测抓出来的：把探针的实测值填成白色 → 日志输出成了问号）。
     * `Int.MIN_VALUE` 是 0x80000000，alpha=128 —— RGB_565 读回来的像素 alpha 恒为 255，
     * 不可能出现这个值。
     */
    const val NO_PIXEL = Int.MIN_VALUE

    /**
     * 遮罩自查的探针要求半径至少这么大（px）。
     *
     * 为什么是 5：探针取的是角落那个像素（例如 (0,0)），它的中心到圆心的距离是
     * `(r − 0.5) × √2`，离弧线还有 `≈ 0.41r − 0.7` px 的富余 —— 半径小于 5px 时这点富余不足 1px，
     * 探针会踩在抗锯齿的过渡带上（读出来的颜色本来就介于照片与填色之间），
     * 拿它做判断只会产生假警报。真实半径是 10dp×密度（≥ 20px），永远在阈值之上。
     */
    const val PROBE_MIN_RADIUS_PX = 5f

    /**
     * RGB_565 的量化容差（每通道）：R/B 5 位（误差 ≤ 8）、G 6 位（误差 ≤ 4），
     * 取 8 覆盖两者。见 [sameArgbWithin565]。
     */
    const val TOLERANCE_565 = 8

    /** 像素总数上限（由字节上限换算，**只对 RGB_565 成立**，所以解码后还要按真实 byteCount 复验） */
    const val MAX_PIXELS = MAX_BITMAP_BYTES / BYTES_PER_PIXEL

    /**
     * 纯函数（可单测）：图片框要显示多大（dp）→ 该解码成多少像素。
     *
     * 这两个 dp 值就是**框的真实尺寸**（框宽 = [WidgetData.contentWidthDp]，框高 = [ExtrasPlanner]
     * 分给图片的那一份 = 自动档里的"余量 − 6dp 上边距"），所以解出来的位图与框**逐值同比例**
     * —— 宿主那边什么裁剪、缩放、留边都不需要做。
     *
     * @param widthDp  图片框的内容宽度（[WidgetData.contentWidthDp]）
     * @param heightDp 这一次图片框分到的高度（[ExtrasPlanner] 的结论 = [ExtrasPlan.photoHeightDp]）
     */
    fun targetPx(widthDp: Int, heightDp: Int, density: Float): IntArray {
        val w = Math.ceil(widthDp.coerceAtLeast(1) * density.toDouble()).toInt().coerceAtLeast(1)
        val h = Math.ceil(heightDp.coerceAtLeast(1) * density.toDouble()).toInt().coerceAtLeast(1)
        val pixels = w.toLong() * h.toLong()
        if (pixels <= MAX_PIXELS) return intArrayOf(w, h)
        // 超预算就等比缩小：宁可让宿主把图放大一点点（略糊），也不能让整次更新被 binder 丢掉
        val scale = Math.sqrt(MAX_PIXELS.toDouble() / pixels.toDouble())
        return intArrayOf(
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1)
        )
    }

    /**
     * 纯函数（可单测）：挑 `inSampleSize`（必须是 2 的幂）。
     *
     * 取的是"**解码后仍然不小于目标**"的最大降采样倍数：多解一点像素只是多几十 KB，
     * 解小了（比显示尺寸还小）就是肉眼可见的糊 —— 这个方向不能省错。
     *
     * 两条边都必须满足，哪怕实际只会用到其中一条：裁剪窗口是"与框同比例"的，
     * 缩到框尺寸之后，两条边都会参与显示，所以两条边都得够。
     */
    fun sampleSizeAtLeast(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return 1
        var sample = 1
        while (srcW / (sample * 2) >= targetW && srcH / (sample * 2) >= targetH) sample *= 2
        return sample
    }

    /** 这张图**真实**的字节数（日志与预算判据都用它，绝不按"像素数 × 2"估算） */
    fun byteCount(bitmap: Bitmap): Int = bitmap.byteCount

    /** 人类可读的体积（日志里用） */
    fun sizeLabel(bytes: Int): String = "${(bytes + 512) / 1024}KB"

    /** 真实像素格式（日志里必须能看出来"解码器有没有把 RGB_565 改掉"） */
    fun configLabel(bitmap: Bitmap): String = bitmap.config?.name ?: "?"

    /**
     * **照片（第一张）的宽高比** `宽/高` —— 图片框该有多高就是由它决定的
     * （[WidgetData.photoWantedHeightDp]）。取不到时返回 null。
     *
     * ## 为什么是"第一张"，而不是"当前要显示的那一张"
     *
     * 因为框高必须**稳定**。用户点一下图片区就换下一张，如果比例跟着当前那张走：
     *  - 两张照片比例不同（比如一张 3.14:1、一张 1.5:1）→ 框高在 72dp / 150dp 之间来回跳，
     *    组件高度跟着跳，课程行数也跟着跳 —— 点一下换图，组件整体变形；
     *  - 更糟的是"当前那张"在解码失败时会被跳过（[WidgetExtras.decodeFrom]），
     *    于是框高还取决于哪张图坏了。
     *
     * 而第一张是可以当"基准形状"用的：**导入时的裁剪框形状是统一的**
     * （`ui/PhotoCropDialog` 对所有照片用同一个 [CropFrame]），所以这些照片本来就该同比例 ——
     * 拿第一张当基准，等于拿用户当初裁的那条框当基准。
     *
     * ## 为什么是"bounds"而不是把图解码出来
     *
     * `inJustDecodeBounds` 只读文件头（不分配像素），所以每次刷新多读几个字节而已；
     * 真要解码一张 4000×3000 的图来问比例，代价是几十 MB 内存 —— 而这条路径在
     * 小组件刷新的主线程上（见 [decode] 的注释）。
     *
     * @return 宽高比；第一张读不出来时按顺序往后找（被删/坏掉的第一张不该让整个图片区消失），
     *         一张都读不出来时返回 null（调用方按"没有照片"处理）
     */
    fun photoAspect(context: Context): Float? {
        // 一律 runCatching：这条路径在小组件刷新的主线程上，读文件头的失败模式（权限、坏文件、
        // 被云同步占住）比想象的多，而"取不到比例"本来就有明确兜底（不预留、不显示图片）。
        val bounds = aspectOfPhotos(WidgetData.photoList(context)) { path ->
            runCatching { boundsOf(path) }.getOrNull()
        }
        if (bounds == null) return null
        return bounds[0].toFloat() / bounds[1].toFloat()
    }

    /**
     * 纯函数（可单测）：**选哪一张照片的尺寸当框高的基准** —— 第一张读得出尺寸的就是它。
     *
     * 抽出来的理由与 [WidgetData.photoWantedHeightDp] 那类纯函数一样：单测里 Android API 全是
     * "返回默认值"的假实现，不把"挑哪张"这条决策与 `BitmapFactory` 解耦，就一行都测不到 ——
     * 而"用第一张"正是本次的稳定性要求（点一下换下一张时组件高度不能跳）。
     *
     * @param paths 候选照片路径（按"第一张在前"的顺序）
     * @param boundsOf 取某张照片的 `[宽, 高]`；读不出来返回 null（真实实现是 [boundsOf]）
     * @return 尺寸数组（`[宽, 高]`，都 > 0）；一张都读不出来时 null
     */
    fun aspectOfPhotos(paths: List<String>, boundsOf: (String) -> IntArray?): IntArray? {
        for (path in paths) {
            val bounds = boundsOf(path) ?: continue
            if (bounds.size < 2 || bounds[0] <= 0 || bounds[1] <= 0) continue
            return bounds
        }
        return null
    }

    /**
     * 只读文件头取尺寸（不分配像素）。返回 `[宽度, 高度]`；读不出来返回 null。
     *
     * 抽成独立函数是为了让 [photoAspect] 的"往后找"那条循环一眼能看懂，也让日志/自检将来能复用。
     */
    fun boundsOf(path: String): IntArray? {
        val file = File(path)
        if (!file.isFile || !file.canRead()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return intArrayOf(bounds.outWidth, bounds.outHeight)
    }

    // ---------------------------------------------------- 四角圆角遮罩（与容器圆角对齐）

    /**
     * 纯数据（可单测）：圆角遮罩的**几何**。
     *
     * [radiusPx] 是**夹取之后**的半径：恒在 `0..min(width,height)/2` 之内。
     * 夹取不是为了好看，是为了让几何有唯一定义 —— 半径超过半边长时，"圆角矩形"不再是圆角矩形
     * （四条弧会互相吃掉），`Path.addRoundRect` 内部自己也会夹，但那套夹法不在我们的可断言范围内。
     */
    internal class CornerMask(
        val width: Int,
        val height: Int,
        /** 夹取后的半径（px），恒 ≥ 0 且 ≤ min(宽,高)/2 */
        val radiusPx: Float
    ) {
        /**
         * 四角的四个**方角区**（左上 / 右上 / 左下 / 右下），每块是 `半径 × 半径`。
         *
         * 遮罩要填色的就是"这四块里、圆角矩形之外"的部分（抗锯齿的弧正好落在这个方角区里）。
         * 把它们算出来是为了能被单测断言：**四角一个都不能漏**（照片也可能出现在组件中部，
         * 四个角都是"照片的直角"），而且每一块都必须落在位图内（越界了就是画到别人的地盘上）。
         */
        val corners: List<FloatArray>
            get() = listOf(
                floatArrayOf(0f, 0f, radiusPx, radiusPx),
                floatArrayOf(width - radiusPx, 0f, width.toFloat(), radiusPx),
                floatArrayOf(0f, height - radiusPx, radiusPx, height.toFloat()),
                floatArrayOf(width - radiusPx, height - radiusPx, width.toFloat(), height.toFloat())
            )
    }

    /**
     * 纯函数（可单测）：这一次遮罩的几何 —— **半径夹取**规则的全部定义。
     *
     * 三种脏值都必须收敛成"能安全画出来"的数：
     *  - 半径 ≤ 0（drawable 读不到、密度为 0）→ 0：调用方据此**跳过遮罩**（宁可方角，
     *    也不要在不知道半径时瞎糊一个角）；
     *  - 半径 NaN → 0（NaN 传进 Path 会让整条路径失效，画出来是一片糊）；
     *  - 半径 > 半边长（极扁的框、或 drawable 被人改成 100dp）→ `min(宽,高)/2`：
     *    再大就不是圆角矩形了，取半边长是唯一有定义的极限。
     */
    fun cornerGeometry(width: Int, height: Int, radiusPx: Float): CornerMask {
        if (width <= 0 || height <= 0) return CornerMask(0, 0, 0f)
        if (radiusPx.isNaN() || radiusPx <= 0f) return CornerMask(width, height, 0f)
        val limit = minOf(width, height) / 2f
        return CornerMask(width, height, minOf(radiusPx, limit))
    }

    /**
     * 纯函数（可单测）：**四角该填什么颜色** —— 这是本轮最需要想清楚的一处取舍。
     *
     * [Bitmap.Config.RGB_565] **没有 alpha 通道**：把四角"涂成透明"，渲染出来就是
     * **黑角**（用户的话："不能出现黑角"），比原来的直角更难看。两条去路：
     *
     * 1. 改用 ARGB_8888 换真透明 —— 载荷**翻倍**（每像素 4 字节而不是 2）：本轮的框比以前高
     *    （吃掉零头之后 4×3 那一档是 594×255），ARGB 下就是 605KB，直接越过 [MAX_BITMAP_BYTES]
     *    （600KB）被 [fitBudget] 缩小，宿主再把缩小的位图放大回框 → 整张照片变糊。
     *    为了四个角让整张照片变糊，不划算；
     * 2. 用**颜色**填充（本轮选择）—— 填的是"图片区**背后那一层**"的颜色（[behindColorArgb]，
     *    也就是组件根背景 `widget_bg`），于是角上的像素与组件背景**逐值相同**，看上去就是
     *    "照片自己带了圆角"。
     *
     * ### 为什么填"背后那层"而不是容器自己那层（`widget_row_bg`）
     *
     * 容器是**圆角矩形**，它四个角那一小块根本没被它涂到 —— 露出来的是它背后的根背景。
     * 如果把角填成容器色，等于把那块"补成直角"：容器自己的圆角反而被抹掉了，用户看到的还是直角。
     * 填根背景色则相反：角上的像素和背景连成一片，**视觉上的轮廓是圆的**。
     *
     * 位图将来若真带上 alpha（例如 [toRgb565] 在某个 ROM 上失败、位图留在 ARGB_8888），
     * 这一档就挖成**真透明** —— 那时候透明是能表达的，也最干净。
     *
     * @param configName   位图的像素格式（[configLabel] 的产物，如 "RGB_565"）
     * @param behindArgb   图片区背后那一层的颜色（[behindColorArgb]）
     */
    fun cornerFillArgb(configName: String, behindArgb: Int): Int =
        if (configName.contains("ARGB") || configName.contains("8888")) TRANSPARENT_ARGB else behindArgb

    /** 纯函数（可单测）：ARGB 十六进制写法（日志里给用户核对截图像素用），如 `FFFFFFFF` */
    fun argbLabel(argb: Int): String =
        String.format(java.util.Locale.CHINA, "%08X", argb)

    /**
     * 图片框里那条圆角的半径（**px**）：直接读容器那张 drawable 的 `<corners android:radius>`。
     *
     * 为什么不在代码里写死 10dp：布局/主题里那个数字随时可能被改（暗色那套就是另一个文件），
     * 而"照片的圆角与容器的圆角对齐"这件事只有在两边取同一个数时才成立。读不到时退
     * [FALLBACK_CORNER_RADIUS_DP]（与 drawable 一致，且有单测读 XML 盯着）。
     */
    fun cornerRadiusPx(context: Context, colors: WidgetColors): Float {
        val declared = runCatching {
            (context.getDrawable(colors.rowBg) as? GradientDrawable)?.cornerRadius ?: 0f
        }.getOrDefault(0f)
        if (declared > 0f) return declared
        val density = context.resources?.displayMetrics?.density ?: 0f
        return FALLBACK_CORNER_RADIUS_DP * density
    }

    /**
     * 图片区**背后那一层**的颜色（ARGB）—— 根布局的背景（[WidgetColors.widgetBg]）。
     *
     * 这一层就是照片四个角"本该露出来"的东西（容器是圆角矩形，四个角它没涂到，见 [cornerFillArgb]）。
     * 读法是从 shape drawable 的 solid 里取。
     *
     * ## 只认**完全不透明**的实色
     *
     * 读不到（null）、读到 0、或者 alpha 不是 255 时一律退兜底常量。理由是踩过的坑：
     * `Canvas.drawPath` 用的是 src-over，**alpha=0 的"颜色"等于什么都不画**（不报错、不留痕），
     * 表现就是"照片四角还是直角" —— 而这正是用户反馈的现象。宁可退一个已知的实色，
     * 也绝不让填色变成"空操作"。
     */
    fun behindColorArgb(context: Context, colors: WidgetColors): Int {
        val solid = solidColorOf(context, colors.widgetBg)
        if (solid != null && (solid ushr 24) == 0xFF) return solid
        return if (colors.dark) FALLBACK_BEHIND_DARK else FALLBACK_BEHIND_LIGHT
    }

    /**
     * 纯函数（可单测）：把填色**强制成不透明**。
     *
     * 为什么必须强制：位图是 RGB_565（没有 alpha 通道），而 `Canvas.drawPath` 走 src-over ——
     * 传进去的颜色如果 alpha=0（读不到颜色、CSL 没有默认色、兜底值被写成 0……），
     * 绘制就是**空操作**：不报错、不留痕，照片四角保持直角。这类"静默失败"在真机上极难定位
     * （用户只看到"还是直角"），所以在这里把 alpha 顶成 255：反正图片区背后那一层是不透明的，
     * 填色本来就不该带透明。
     */
    fun opaqueArgb(argb: Int): Int = argb or (0xFF shl 24)

    /**
     * 纯函数（可单测）：圆角那条**极细弧线**的颜色 —— 由填色按亮度往对面偏一点。
     *
     * 为什么要它：四角填的是组件背景色（明色主题是纯白）。照片角落里如果也偏亮
     * （白墙、天空、纸张），圆角就会"融进背景"、看起来仍像直角 —— 用户实测反馈过这一条。
     * 这条线跟着填色走：浅底往暗偏 14%、深底往亮偏 14%，所以任何照片上都能看出圆角，
     * 又细到不会变成一圈边框。一律不透明（RGB_565 没有 alpha，半透明等于没画）。
     */
    fun arcLineArgb(fillArgb: Int): Int {
        val r = (fillArgb shr 16) and 0xFF
        val g = (fillArgb shr 8) and 0xFF
        val b = fillArgb and 0xFF
        // 感知亮度：决定"往黑偏"还是"往白偏"
        val lum = (r * 299 + g * 587 + b * 114) / 1000
        val toward = if (lum > 140) 0 else 255
        fun mix(c: Int) = (c + (toward - c) * 0.14f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
    }

    /** 读一个 shape drawable 的 `solid` 颜色；不是 [GradientDrawable] / 读不到 → null */
    private fun solidColorOf(context: Context, resId: Int): Int? {
        val drawable = runCatching { context.getDrawable(resId) }.getOrNull() ?: return null
        val shape = drawable as? GradientDrawable ?: return null
        return runCatching { shape.color?.defaultColor }.getOrNull()
    }

    /**
     * 纯函数（可单测）：某个像素**是否落在"要被填掉"的那四块里**（= 方角区 − 圆角矩形）。
     *
     * 这是圆角遮罩那个区域的**书面定义**（[maskCorners] 画的就是它，用的是平台算出来的
     * `Path.Op.DIFFERENCE`）。写成纯函数是为了能真的验一遍：单测里画布、`Path` 全是假实现，
     * "涂在哪几块"这件事只能靠这个判据来钉（面积、四角、弧内弧外各取几个点，见 PhotoCornerMaskTest）。
     *
     * 判据用**像素中心** `(x + 0.5, y + 0.5)`：与光栅化时抗锯齿的口径一致。
     * 落在方角区之外（也就是圆角矩形内部）的像素一律不遮 —— 那里必须是照片本身。
     */
    fun insideCornerMask(x: Int, y: Int, width: Int, height: Int, radiusPx: Float): Boolean {
        val mask = cornerGeometry(width, height, radiusPx)
        val r = mask.radiusPx
        if (r <= 0f) return false
        if (x < 0 || y < 0 || x >= mask.width || y >= mask.height) return false
        // 这个像素落在哪个角的方角区里？（四个方角区互不重叠，r ≤ min(w,h)/2 保证了这一点）
        val cx: Float
        val cy: Float
        when {
            x < r && y < r -> { cx = r; cy = r }                                  // 左上
            x >= mask.width - r && y < r -> { cx = mask.width - r; cy = r }        // 右上
            x < r && y >= mask.height - r -> { cx = r; cy = mask.height - r }      // 左下
            x >= mask.width - r && y >= mask.height - r -> {
                cx = mask.width - r; cy = mask.height - r                          // 右下
            }
            else -> return false                                                   // 不在任何角上
        }
        val dx = (x + 0.5f) - cx
        val dy = (y + 0.5f) - cy
        return dx * dx + dy * dy > r * r
    }

    /**
     * 纯函数（可单测）：挑一个**"遮罩一定填过色"**的探针像素（离弧线还有富余，不会被抗锯齿混到），
     * 挑不出来时返回 null。
     *
     * 用途是 [maskCorners] 画完之后**自查一次**：把那个像素读回来，如果它还是照片的颜色，
     * 就说明遮罩根本没画上（画布/几何异常）。单测里画布是假的，"画上去没有"只能在真机上验 ——
     * 这一处就是真机上唯一的自证手段（日志里 `圆角=..px` 与 `角像素=..` 两列）。
     *
     * 半径太小时**返回 null 而不是硬读**：`r < 5px` 时角落那个像素半只脚踩在弧线的抗锯齿带上，
     * 读出来的颜色本来就介于照片与填色之间 —— 拿它报警只会产生假警报。
     */
    fun maskProbePixel(width: Int, height: Int, radiusPx: Float): IntArray? {
        val mask = cornerGeometry(width, height, radiusPx)
        if (mask.radiusPx < PROBE_MIN_RADIUS_PX) return null
        // 四个角落各试一下，取第一个"判据说该被遮掉"的（(0,0) 对真实半径总是第一个命中）
        val candidates = arrayOf(
            intArrayOf(0, 0),
            intArrayOf(mask.width - 1, 0),
            intArrayOf(0, mask.height - 1),
            intArrayOf(mask.width - 1, mask.height - 1)
        )
        for (c in candidates) {
            if (insideCornerMask(c[0], c[1], mask.width, mask.height, mask.radiusPx)) return c
        }
        return null
    }

    /**
     * 纯函数（可单测）：两个 ARGB 颜色在 **RGB_565 的量化误差之内**算不算同一个颜色。
     *
     * 为什么要有容差：`widget_bg` 的 #FFFFFFFF 在 565 里能精确表示，但暗色的 #FF171A1F
     * 只能落成 #FF101818 这样的近似值（R/B 各差 ≤ 8、G 差 ≤ 4）。遮罩自查就是拿这个容差比的。
     */
    fun sameArgbWithin565(a: Int, b: Int): Boolean {
        if (a == b) return true
        val dr = Math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = Math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = Math.abs((a and 0xFF) - (b and 0xFF))
        return dr <= TOLERANCE_565 && dg <= TOLERANCE_565 && db <= TOLERANCE_565
    }

    /**
     * 一个角的**楔形几何**（纯数据，可单测）：圆心 / 半径 / 弧的起始角与扫过角 / 方角区的角点。
     *
     * 把它做成纯数据是为了**能在单测里验角度**：弧的两个端点由 `圆心 + 半径 × (cos 角, sin 角)`
     * 推出来，单测反过来用端点反查角度 —— 手写的起始角/扫过角一旦写反（那是最容易犯、
     * 且只能在真机上看出来的错），这里立刻红。
     */
    internal class CornerWedge(
        val centerX: Float,
        val centerY: Float,
        val radiusPx: Float,
        /** 弧的起始角（度，0° = +x 方向，顺时针为正 —— 与 Android 的坐标系一致） */
        val startAngle: Float,
        /** 弧扫过的角度（度，正数 = 顺时针） */
        val sweepAngle: Float,
        /** 方角区里那个直角顶点（四角的角点，圆心在它的对角方向上） */
        val cornerX: Float,
        val cornerY: Float
    ) {
        /** 弧的起点 = 圆心 + 半径 × (cos 起始角, sin 起始角) */
        val fromX: Float get() = pointAt(startAngle)[0]
        val fromY: Float get() = pointAt(startAngle)[1]

        /** 弧的终点 = 圆心 + 半径 × (cos(起始角+扫过角), sin(起始角+扫过角)) */
        val toX: Float get() = pointAt(startAngle + sweepAngle)[0]
        val toY: Float get() = pointAt(startAngle + sweepAngle)[1]

        fun pointAt(angleDeg: Float): FloatArray {
            val rad = Math.toRadians(angleDeg.toDouble())
            return floatArrayOf(
                (centerX + radiusPx * Math.cos(rad)).toFloat(),
                (centerY + radiusPx * Math.sin(rad)).toFloat()
            )
        }
    }

    /**
     * 纯函数（可单测）：四个角那四段"方角区 − 四分之一圆"的几何（左上 / 右上 / 右下 / 左下）。
     *
     * 记 `w × h` 是位图、`r` 是夹取后的半径：
     * ```
     * 左上：圆心 (r, r)      弧 180° → 270°（扫 +90）  角点 (0, 0)
     * 右上：圆心 (w−r, r)    弧 270° → 360°（扫 +90）  角点 (w, 0)
     * 右下：圆心 (w−r, h−r)  弧   0° →  90°（扫 +90）  角点 (w, h)
     * 左下：圆心 (r, h−r)    弧 180° →  90°（扫 −90）  角点 (0, h)
     * ```
     * 四段合起来就是 [insideCornerMask] 描述的区域（也就是说：遮罩的区域只有这一处定义，
     * 画的那一步只是把这四段的坐标喂给 `Path`）。
     */
    fun cornerWedges(mask: CornerMask): List<CornerWedge> {
        val w = mask.width.toFloat()
        val h = mask.height.toFloat()
        val r = mask.radiusPx
        if (mask.width <= 0 || mask.height <= 0 || r <= 0f) return emptyList()
        return listOf(
            CornerWedge(r, r, r, 180f, 90f, 0f, 0f),                 // 左上
            CornerWedge(w - r, r, r, 270f, 90f, w, 0f),               // 右上
            CornerWedge(w - r, h - r, r, 0f, 90f, w, h),              // 右下
            CornerWedge(r, h - r, r, 180f, -90f, 0f, h)               // 左下
        )
    }

    /**
     * 遮罩的结果：位图 + **真正画上去**的半径（px）+ 角落里读回来的像素。
     *
     * 为什么要带出半径：位图可能因为不可变（`decodeFile` 给的就是不可变位图）而 copy 失败，
     * 那时遮罩并没有生效 —— 日志里必须能看出来"这次没遮上"，否则用户拿着截图
     * 取像素会以为是自己的眼睛有问题。0 = 没遮罩。
     *
     * [cornerPixel] 是遮罩之后从探针像素**实测**读回来的 ARGB（[NO_PIXEL] = 没读）：
     * 用户要拿截图取角落的像素来核对，这一列就是"我这边的实测值"，两边应当对得上。
     */
    internal class MaskedPhoto(
        val bitmap: Bitmap,
        val radiusPx: Float,
        val cornerPixel: Int = NO_PIXEL
    )

    /**
     * 把位图的四角按圆角**遮罩**掉（与容器圆角对齐），返回遮罩后的位图。
     *
     * ## 为什么必须在位图里做
     *
     * 图片框的背景是圆角 drawable（`widget_row_bg`），而 `setImageViewBitmap` 送过去的是
     * **一张方角位图**（RemoteViews 没有任何"圆角裁剪"指令）。于是照片的四个直角正压在容器的
     * 圆角上 —— 用户原话："图片下部边角直角紧贴圆角，看起来很突兀"。照片自己带上圆角，
     * 两边就对齐了。
     *
     * ## 怎么画（这段写法改过两次，两次都是因为"静默失败"，别再简化）
     *
     * 要涂的是"整张位图 − 圆角矩形"= 四角那四块（[insideCornerMask] 是它的定义，
     * [cornerWedges] 把它拆成四段可验证的几何）。这里**不用**任何"路径集合运算 / 填充规则"：
     *  - 嵌套子路径 + `INVERSE_EVEN_ODD` 得到的是圆角矩形**内部**（会把整张照片糊成一块底色）；
     *  - `Path.Op.DIFFERENCE` 语义没问题，但**它是个返回值**：失败时我这里只能"放弃遮罩"，
     *    用户看到的就是"还是直角"，而日志里只有一行半径 0 —— 真机上极难定位（用户实测反馈
     *    "4×4 的时候图片还是直角"就是这么来的）。
     *
     * 现在改成一劳永逸的写法：**四个角各画一段显式弧**（`moveTo 角点 → lineTo 弧起点 →
     * arcTo 弧 → close`），坐标全部来自 [cornerWedges] 的纯算术 —— 没有分支、没有返回值、
     * 不依赖平台几何运算，画不出来才是见鬼。
     *
     * 填色用 [opaqueArgb] 强制不透明：`drawPath` 是 src-over，**alpha=0 等于什么都不画**
     * （同样静默），而图片区背后那一层本来就不透明。
     *
     * 抗锯齿用 `Paint.ANTI_ALIAS_FLAG`（弧边是曲线的，不抗锯齿就是一圈阶梯状锯齿）。
     * 位图不可变时先 `copy(RGB_565, true)` 一次（`decodeFile` 给的位图是只读的）；copy 失败就
     * **原样返回**（方角照旧显示，不影响任何其它功能），半径记 0 并且**打一条警告**。
     *
     * @param radiusPx 容器那张 drawable 的圆角半径（px）；≤ 0 / NaN → 不做遮罩
     * @param fillArgb 角落填什么颜色（[cornerFillArgb] 的结论）
     */
    fun maskCorners(src: Bitmap, radiusPx: Float, fillArgb: Int): MaskedPhoto {
        val mask = cornerGeometry(src.width, src.height, radiusPx)
        if (mask.radiusPx <= 0f) {
            // 半径读不到（drawable 读不出来且密度为 0）：不遮罩。这一档只能靠日志的 `圆角=0px` 看出来
            Log.w(TAG, "不做圆角遮罩：半径 ${radiusPx}px 无效（位图 ${src.width}x${src.height}）")
            return MaskedPhoto(src, 0f)
        }

        // RGB_565 是有意的：照片没有透明通道，ARGB 等于白送一半体积给 binder（见类注释）。
        // 不可变位图（decodeFile / createBitmap 的产物）要先转成可写的副本才能往上画。
        val target = if (src.isMutable) {
            src
        } else {
            runCatching { src.copy(Bitmap.Config.RGB_565, true) }.getOrNull()
                ?: run {
                    Log.w(
                        TAG,
                        "不做圆角遮罩：位图不可变且 copy 失败（${src.width}x${src.height}，" +
                            "config=${configLabel(src)}）→ 照片四角会是直角"
                    )
                    return MaskedPhoto(src, 0f)
                }
        }

        val fillColorForArc = opaqueArgb(fillArgb)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = fillColorForArc
            style = Paint.Style.FILL
        }

        // 画之前先记两个像素（画完比对用）：角落那个"该被填掉"的，与正中间那个"绝不该被填掉"的。
        // 单测里画布是假的，这两次比对就是真机上唯一能证明"遮罩真的按预期画上去了"的东西。
        val probe = maskProbePixel(mask.width, mask.height, mask.radiusPx)
        val cornerBefore = probe?.let { runCatching { target.getPixel(it[0], it[1]) }.getOrNull() }
        val centerX = mask.width / 2
        val centerY = mask.height / 2
        val centerBefore = runCatching { target.getPixel(centerX, centerY) }.getOrNull()

        val canvas = Canvas(target)
        for (wedge in cornerWedges(mask)) {
            val path = Path().apply {
                moveTo(wedge.cornerX, wedge.cornerY)                  // 四角的角点（直角顶点）
                lineTo(wedge.fromX, wedge.fromY)                      // 沿着位图边走到弧的起点
                arcTo(
                    wedge.centerX - wedge.radiusPx,
                    wedge.centerY - wedge.radiusPx,
                    wedge.centerX + wedge.radiusPx,
                    wedge.centerY + wedge.radiusPx,
                    wedge.startAngle, wedge.sweepAngle, false
                )
                close()                                               // 从弧的终点直着回到角点
            }
            canvas.drawPath(path, paint)
        }

        // 再沿弧线描一条**极细**的线。
        //
        // 为什么需要：四角填的是组件背景色（明色主题 = 纯白）。如果照片角落的内容也偏亮
        // （白墙、天空、纸张），圆角就会"融进背景"、看起来仍像直角 —— 用户实测反馈过这一条。
        // 这条线跟着填色走（浅底往暗、深底往亮），所以任何照片上都能看出圆角，
        // 又细到不会变成一圈边框。
        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // 注意 this.：外面那个 fillColor 的局部变量名就是 color，不加 this. 会被解析成对局部 val 赋值
            this.color = arcLineArgb(fillColorForArc)
            style = Paint.Style.STROKE
            strokeWidth = (mask.radiusPx * 0.08f).coerceIn(1f, 2f)
        }
        for (wedge in cornerWedges(mask)) {
            canvas.drawArc(
                wedge.centerX - wedge.radiusPx,
                wedge.centerY - wedge.radiusPx,
                wedge.centerX + wedge.radiusPx,
                wedge.centerY + wedge.radiusPx,
                wedge.startAngle, wedge.sweepAngle, false, arcPaint
            )
        }

        // ---- 画完的两个自查（对不上就打警告；平时不刷屏，正常值由日志的 `角像素=` 那一列表达）----
        val cornerAfter = probe?.let { runCatching { target.getPixel(it[0], it[1]) }.getOrNull() }
        if (probe != null && cornerAfter != null && !sameArgbWithin565(cornerAfter, fillColorForArc)) {
            Log.w(
                TAG,
                "圆角遮罩没生效：角落像素 #${argbLabel(cornerAfter)} ≠ 填色 #${argbLabel(fillColorForArc)}" +
                    "（探针 ${probe[0]},${probe[1]}，位图 ${mask.width}x${mask.height}，" +
                    "半径 ${mask.radiusPx}px）→ 照片四角会是直角"
            )
        }
        val centerAfter = runCatching { target.getPixel(centerX, centerY) }.getOrNull()
        if (centerBefore != null && centerAfter != null && centerBefore != centerAfter) {
            // 这条防的是"方向画反了"：把圆角矩形**内部**填掉，整张照片会变成一块纯色。
            // 比对的是"画之前 / 画之后"，所以照片本身恰好是背景色也不会误报。
            Log.w(
                TAG,
                "圆角遮罩画反了：位图正中间 ($centerX,$centerY) 被涂成了 " +
                    "#${argbLabel(centerAfter)}（画之前是 #${argbLabel(centerBefore)}）"
            )
        }
        return MaskedPhoto(target, mask.radiusPx, cornerAfter ?: NO_PIXEL)
    }

    /**
     * 纯函数（可单测）：人类可读的宽高比（日志里用），例如 226×87dp → "2.60:1"。
     *
     * 为什么要打这个：用户看不到画面，只能看日志。而"框和图比例对不对"这件事
     * 在日志里就是一串数字 —— 框的比例、裁剪框的比例、位图的像素数与字节数放在一起，
     * 就能判断是"框算错了"还是"解码没跟上"。宽高取不到时给 "?"（不掺假的默认值）。
     */
    fun ratioLabel(widthDp: Int, heightDp: Int): String {
        if (widthDp <= 0 || heightDp <= 0) return "?"
        return String.format(java.util.Locale.CHINA, "%.2f:1", widthDp.toFloat() / heightDp)
    }

    /**
     * 这一次渲染的全部日志字段（用户在真机上看不到画面，只能核对这一行数字）。
     *
     * 必须出现的列（缺一列就少一个判据）：`框=`、`照片比例=`、`比例=`、`需要的框高=`、
     * `裁剪框=`、`完整显示/取中间块`、`留边=`、`行数=`、`bitmap=`、`byteCount=`、`config=`、
     * `圆角=`、`角填色=`、`底部零头=`。
     * 有了它们，"组件里那张图是不是我们要的那一块、留了多少白、排了几行课、四个角是怎么处理的、
     * 底部还有没有空白"就能直接对着截图数像素。
     *
     * ## `框=` 与 `需要的框高=` 的关系（本轮变过，别看错）
     *
     * 上一轮这两列**应该相等**（框刚好装下照片、零留边）。本轮框改成"吃掉列表下方的全部剩余空间"
     * 之后，自动档里 `框 ≥ 需要的框高` 是**正常**的（多出来的部分靠"取中间块"填满，
     * 所以 `留边=` 仍然是 0）。真正要盯的不变量换成了两列：
     *  - `留边=0px(0%)` —— 照片区里一条白都不该有；
     *  - `底部零头=0dp` —— 图片框下方（也就是整个组件的最下面）一条白都不该有。
     * 哪一天 `底部零头` 变成正数，说明"框吃掉零头"那条规则被人改回去了。
     *
     * 行数是**排完框之后**实际排出来的整行数（[WidgetData.rowsFor]），与组件里显示的课程行数
     * 用的是同一个纯函数 —— 日志说 2 行、画面就该是 2 行。
     *
     * 入参是 [RenderFacts] 而不是 Bitmap：单测里 Android 的 `Bitmap` 是"返回默认值"的假实现
     * （`Bitmap.createBitmap` 直接给 null），拿它当入参的话这段格式化代码一行都测不到 ——
     * 而日志是本项目唯一的核对手段，格式串必须被钉住。
     *
     * @param wantedHeightDp  照片在这个宽度下**需要**的框高（[WidgetData.photoWantedHeightDp]）
     * @param rows            这一轮排出来的整行课程数（[WidgetData.rowsFor]）
     * @param photoRatioLabel 当前显示的那张照片的比例（[ratioLabel] 的产物）
     * @param leftoverDp      图片框**下方**还剩多少 dp（[ExtrasPlanner.leftoverBelowBoxDp]）：
     *                        自动档里恒为 0，> 0 就是用户说的"下拉之后依然有空白"
     */
    fun renderLabel(
        facts: RenderFacts,
        frame: CropFrame,
        boxWidthDp: Int,
        boxHeightDp: Int,
        wantedHeightDp: Int,
        rows: Int,
        photoRatioLabel: String,
        leftoverDp: Int
    ): String {
        val layout = facts.layout
        // 用户要求的那一行必须自带"留边"的**像素数**（不带百分比）：真机核对是拿截图数像素的，
        // 百分比只是给人一眼看的粗细（0% 与 1px 是同一档，1px 肉眼看不出来）。
        // 非"铺满"档打的是模糊底的取块窗口 —— 这一列回答"照片旁边那几条边是从源图哪一块糊出来的"。
        val geometry = if (layout.whole) {
            "留边=${layout.padY}px(${Math.round(layout.padShare * 100)}%)"
        } else {
            "底窗=${layout.window}"
        }
        // 圆角这几列是给"截图取像素"用的：拿到角落那个像素，它应该等于 `角填色=`，
        // 而 `角像素=` 是**遮罩之后从位图上实测读回来**的值（用户拿截图对的就是它，两边应当一致）。
        // `圆角=0px` = 这次没遮上（位图不可变 + copy 失败、或几何运算失败），
        // 那就又回到"照片的直角压在容器圆角上"的观感；`角像素=?` = 没读（半径太小或读失败）。
        val cornerPixel = if (facts.cornerPixelArgb == NO_PIXEL) {
            "?"
        } else {
            "#${argbLabel(facts.cornerPixelArgb)}"
        }
        return "框=${boxWidthDp}x${boxHeightDp}dp 比例=${ratioLabel(boxWidthDp, boxHeightDp)} " +
            "照片比例=$photoRatioLabel 需要的框高=${wantedHeightDp}dp " +
            "裁剪框=${frame.label} 源=${facts.sourceW}x${facts.sourceH}" +
            "(${ratioLabel(facts.sourceW, facts.sourceH)}) " +
            // `照片=` 是**整张照片落在框里的矩形**（px）：它永远不越出框，
            // 用户拿截图量"照片有没有被遮住"就对这一列（见 PhotoFit.containRect）
            "照片=${layout.photo} " +
            "${layout.verdict} $geometry 行数=$rows " +
            "bitmap=${facts.bitmapW}x${facts.bitmapH} " +
            "byteCount=${facts.byteCount}B(${sizeLabel(facts.byteCount)}) " +
            "config=${facts.config} " +
            "圆角=${facts.cornerRadiusPx}px 角填色=#${argbLabel(facts.cornerFillArgb)} " +
            "角像素=$cornerPixel " +
            "底部零头=${leftoverDp}dp"
    }

    /**
     * [renderLabel] 的便捷入口（真机路径用这个）：把"框该多高、这张图什么比例、排了几行、
     * 底部还剩多少"几个数字按当前状态算出来再格式化。几个数都是纯算术，没有 Context 依赖，
     * 也没有可变的进程状态 —— 单测能直接构造 [RenderFacts] 验这一行。
     *
     * @param wantedHeightDp 照片在这个宽度下**需要**的框高（[WidgetData.photoWantedHeightDp]）。
     *        必须传**决定裁剪框的那个比例**（照片列表第一张）算出来的值 —— 否则日志里
     *        `需要的框高` 与 `裁剪框` 这两列自己就对不上号了。
     * @param rows 这一轮实际排出来的整行课程数（[WidgetData.visibleRows]），与列表用的是同一次计算
     * @param leftoverDp 图片框下方剩下的 dp（[ExtrasPlanner.leftoverBelowBoxDp]）
     */
    fun renderLabel(
        render: PhotoRender,
        frame: CropFrame,
        boxWidthDp: Int,
        boxHeightDp: Int,
        wantedHeightDp: Int,
        rows: Int,
        leftoverDp: Int
    ): String = renderLabel(
        facts = render.facts(),
        frame = frame,
        boxWidthDp = boxWidthDp,
        boxHeightDp = boxHeightDp,
        wantedHeightDp = wantedHeightDp,
        rows = rows,
        // 这一列说的是**当前显示的这一张**什么比例：与"决定裁剪框的那张"不同时，
        // `取中间块` 那一列会把它放大成看得见的数字（哪天真的混进了不同比例的照片，一眼能看出来）
        photoRatioLabel = ratioLabel(render.sourceW, render.sourceH),
        leftoverDp = leftoverDp
    )

    /**
     * 解码一张图到"显示所需的最小尺寸"，并按 [PhotoFit.layout] 的结论裁+缩。
     *
     * 返回 null 表示这张图用不了（文件被删、损坏、不是图片……）—— 调用方应当**跳过它、
     * 换下一张**，而不是让整块图片区（甚至整个小组件）跟着空白。
     *
     * 会在**调用线程**上解码。删掉图片轮播（AdapterViewFlipper + RemoteViewsService）之后，
     * 调用点就是小组件刷新本身（广播接收器的主线程）—— 已经降采样到"框的像素尺寸"，
     * 单张是几毫秒级。这是删轮播的必然结果：那套是在集合视图的 binder 线程上按项解码的，
     * 代价是它顺手把竖直手势也吃掉了。宁可主线程多花几毫秒，也不要一个滑不动的小组件。
     *
     * @param targetW 框的像素宽（[targetPx] 的结果，已经过 binder 预算夹取）
     * @param targetH 框的像素高
     */
    fun decode(context: Context, path: String, targetW: Int, targetH: Int): PhotoRender? = runCatching {
        val file = File(path)
        if (!file.isFile || !file.canRead()) return@runCatching null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeAtLeast(bounds.outWidth, bounds.outHeight, targetW, targetH)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val raw = BitmapFactory.decodeFile(path, opts) ?: return@runCatching null

        // 关键一步：inPreferredConfig 只是建议（带 alpha 的 PNG/截图会被给成 ARGB_8888，
        // 真机日志里那张 917×137 的图是 491KB 而不是 251KB）。这里统一成 RGB_565。
        val decoded = toRgb565(raw)

        // 这一次"整张照片摆在哪、背景取哪一块、缩到多大"—— 全部来自纯算术（PhotoFit.layout），
        // 这里只负责把它的结论喂给 Bitmap 工厂。
        val layout = PhotoFit.layout(decoded.width, decoded.height, targetW, targetH)
        if (layout == null) {
            decoded.recycle()
            return@runCatching null
        }
        // 尺寸要在 recycle 之前记下来（[PhotoRender] 只存数字，不持有已被回收的 Bitmap）
        val srcW = decoded.width
        val srcH = decoded.height

        // ---- 产出一块"与框同像素尺寸"的画布：先铺模糊底（只在需要时），再画**整张**照片 ----
        // 顺序不能反：模糊底是背景，照片必须压在它上面。
        val produced = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565)
        val canvas = Canvas(produced)
        // FILTER_BITMAP_FLAG = 双线性采样：缩小时不会出现锯齿，放大时也不会出现硬边
        // （以前那条路走 Bitmap.createScaledBitmap(..., true)，内部就是同一个开关）
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        if (layout.backdrop) {
            // 模糊底 = 同一张照片按 cover 取一块 → 缩到框的 1/8 → 双线性放大回整框。
            // 1/8 再放大这一下就是"廉价模糊"：不需要 RenderScript，也不会多花几百毫秒。
            val cover = slice(decoded, layout.window)
            val blurW = (targetW / BLUR_DIVISOR).coerceAtLeast(1)
            val blurH = (targetH / BLUR_DIVISOR).coerceAtLeast(1)
            val blur = runCatching { Bitmap.createScaledBitmap(cover, blurW, blurH, true) }.getOrNull()
            val fill = if (blur != null && blur !== cover) blur else cover
            canvas.drawBitmap(fill, null, Rect(0, 0, targetW, targetH), paint)
            // 临时位图（1/8 那张很小，cover 那块与源图同量级）用完立刻回收
            if (blur != null && blur !== cover) runCatching { blur.recycle() }
            if (cover !== decoded) runCatching { cover.recycle() }
        }

        // 前景：**整张**照片（contain、居中）—— 这一步是"一个像素都不被遮住"的落点，
        // 也是这一轮改动的全部目的（见 PhotoFit 的类注释）。
        // 缩放交给画布（同一个双线性开关），所以这里不需要再造一张中间位图。
        val photo = layout.photo
        canvas.drawBitmap(
            decoded,
            null,
            Rect(photo.x, photo.y, photo.x + photo.width, photo.y + photo.height),
            paint
        )
        decoded.recycle()

        // 真实的 byteCount 兜底：上面那条路按"2 字节/像素"估算，万一配置又不是 RGB_565
        // （某些 ROM 的 copy 也会失败），这里按实际字节数再压一次，宁可糊一点也不能让整次更新被丢掉
        val budgeted = fitBudget(produced)

        // ---- 四角圆角遮罩（与容器圆角对齐），必须放在最后一步 ----
        // 顺序是有意的：[fitBudget] 可能把位图缩小，位图最终是被宿主**放大**回框尺寸显示的，
        // 所以半径要按"位图相对框缩了多少"等比缩，否则弧会比容器的圆角小一圈、对不齐。
        // 放在 fitBudget 之后（而不是之前）还有一个好处：只画一次，缩小的位图不重复走一遍 Canvas。
        val colors = WidgetColors.of(context)
        val radiusPx = cornerRadiusPx(context, colors) *
            (if (targetW > 0) budgeted.width.toFloat() / targetW.toFloat() else 1f)
        val fillArgb = cornerFillArgb(configLabel(budgeted), behindColorArgb(context, colors))
        val masked = maskCorners(budgeted, radiusPx, fillArgb)

        PhotoRender(
            bitmap = masked.bitmap,
            layout = layout,
            sourceW = srcW,
            sourceH = srcH,
            cornerRadiusPx = Math.round(masked.radiusPx).toInt(),
            cornerFillArgb = fillArgb,
            cornerPixelArgb = masked.cornerPixel
        )
    }.onFailure {
        Log.w(TAG, "解码失败 path=$path -> ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /**
     * 强制统一成 RGB_565。
     *
     * 为什么必须做：照片没有透明通道，留成 ARGB_8888 等于白送一半体积给 binder（见类注释里
     * 那条真机日志：917×137 的图 491KB vs 251KB）。转换失败（极少数 ROM）时**照原样用** ——
     * 宁可多传一点字节，也不能因为"转不了"就让用户的图直接消失（那更难查）。
     */
    private fun toRgb565(src: Bitmap): Bitmap {
        if (src.config == Bitmap.Config.RGB_565) return src
        val copy = runCatching { src.copy(Bitmap.Config.RGB_565, false) }.getOrNull()
        if (copy == null || copy === src) return src
        src.recycle()
        return copy
    }

    /**
     * 按 [window] 从 [src] 上切一块。整张都要时直接返回原对象（不复制）。
     *
     * 这里有两个"以前会崩"的坑，都补上了（它们都在小组件刷新的主线程上）：
     *
     * 1. `Bitmap.createBitmap` 的 `width/height` 传 0 会抛 `IllegalArgumentException`；
     * 2. 越界（`x + width > src.width`）同样抛异常。
     *
     * 现在的写法：裁剪矩形完全来自 [PhotoFit]（已保证 1..源尺寸 且不越界），
     * 这里再夹一次 —— 抛异常的代价不是"这张图难看"，而是**整个小组件 provider 挂掉**。
     */
    private fun slice(src: Bitmap, window: PhotoFit.Window): Bitmap {
        val full = window.width >= src.width && window.height >= src.height
        if (full) return src
        return Bitmap.createBitmap(
            src,
            window.x.coerceIn(0, maxOf(src.width - 1, 0)),
            window.y.coerceIn(0, maxOf(src.height - 1, 0)),
            window.width.coerceIn(1, src.width),
            window.height.coerceIn(1, src.height)
        )
    }

    /**
     * 最后一道闸：**按真实 `byteCount`** 判定，超了就等比缩到预算内。
     *
     * 这一步是这次真机事故直接加出来的：[targetPx] 的预算是在解码**前**按 2 字节/像素估的，
     * 而解码器不保证给 RGB_565。到手之后必须用真实字节数验一次，
     * 因为它要跟着 RemoteViews 过 binder，超了是"整次更新被系统丢掉"，不是"图糊一点"。
     *
     * （上一版这里还有一个 `scaleTo`：那时渲染是"切一块 → 缩到框尺寸"两步。
     *  这一轮改成"在一张与框同尺寸的画布上先铺模糊底、再画整张照片"，
     *  缩放直接内联在 [decode] 里，因为那里还要把临时位图登记下来统一回收。）
     */
    private fun fitBudget(src: Bitmap): Bitmap {
        val bytes = src.byteCount
        if (bytes <= MAX_BITMAP_BYTES) return src
        val bpp = if (bytes > 0 && src.width > 0 && src.height > 0) {
            Math.max(1, Math.round(bytes.toFloat() / (src.width.toFloat() * src.height)))
        } else {
            BYTES_PER_PIXEL
        }
        val size = PhotoFit.budgetSize(src.width, src.height, MAX_BITMAP_BYTES, bpp)
        if (size[0] == src.width && size[1] == src.height) return src
        val scaled = runCatching { Bitmap.createScaledBitmap(src, size[0], size[1], true) }.getOrNull()
        if (scaled == null || scaled === src) return src
        src.recycle()
        Log.w(
            TAG,
            "位图超预算 ${src.width}x${src.height}/$bytes B（配置可能不是 RGB_565），" +
                "已缩到 ${size[0]}x${size[1]}"
        )
        return scaled
    }
}

/**
 * 一次渲染的完整结果：位图 + 这一次的几何结论 + 源图尺寸 + 四个角怎么处理的。
 *
 * 为什么要连 [layout] 一起带出来：[WidgetExtras] / [PhotoAutoAdvance] 的日志要靠它写
 * "完整显示 / 取中间块、留边多少、裁剪窗口在哪"，而单测里能验的只有 [layout]
 * （纯算术）那一半 —— 位图那半只能靠真机日志核对。
 *
 * 圆角那两个字段同理：遮罩是**画**在位图上的，单测碰不到画布，只能在日志里留下
 * "这次半径多少像素、角落填了什么颜色"，让用户拿截图取像素去对。
 */
internal class PhotoRender(
    val bitmap: Bitmap,
    val layout: PhotoFit.Layout,
    /** 解码后的源图尺寸（= 用户裁好那张裁剪图，经 inSampleSize 降采样后的尺寸） */
    val sourceW: Int,
    val sourceH: Int,
    /** 这一次四角遮罩**真正用上**的半径（px）；0 = 没遮上（位图不可变且 copy 失败） */
    val cornerRadiusPx: Int,
    /** 四角填的颜色（ARGB）：角落那个像素应当等于它（RGB_565 的量化误差之内） */
    val cornerFillArgb: Int,
    /** 遮罩之后从探针像素**实测**读回来的颜色（ARGB）；[PhotoBitmap.NO_PIXEL] = 没读 */
    val cornerPixelArgb: Int = PhotoBitmap.NO_PIXEL
) {
    /** 日志要的那几个数字（不含 Bitmap 本体，所以单测能直接构造来验证日志文本） */
    fun facts(): RenderFacts = RenderFacts(
        layout = layout,
        sourceW = sourceW,
        sourceH = sourceH,
        bitmapW = bitmap.width,
        bitmapH = bitmap.height,
        byteCount = PhotoBitmap.byteCount(bitmap),
        config = PhotoBitmap.configLabel(bitmap),
        cornerRadiusPx = cornerRadiusPx,
        cornerFillArgb = cornerFillArgb,
        cornerPixelArgb = cornerPixelArgb
    )
}

/**
 * 日志用的**纯数字**快照（[PhotoRender] 去掉 Bitmap 之后的那一半）。
 *
 * 存在的唯一理由：单测能构造它。单测里 Android 的 `Bitmap` 全是假实现，
 * 而"日志里到底写了哪些列、数字对不对"是用户在真机上唯一能核对的东西。
 */
internal class RenderFacts(
    val layout: PhotoFit.Layout,
    val sourceW: Int,
    val sourceH: Int,
    val bitmapW: Int,
    val bitmapH: Int,
    val byteCount: Int,
    val config: String,
    /** 四角遮罩的半径（px），0 = 没遮罩 */
    val cornerRadiusPx: Int = 0,
    /** 四角填的颜色（ARGB） */
    val cornerFillArgb: Int = 0,
    /** 角落那个像素的**实测**值（ARGB）；[PhotoBitmap.NO_PIXEL] = 没读 */
    val cornerPixelArgb: Int = PhotoBitmap.NO_PIXEL
)
