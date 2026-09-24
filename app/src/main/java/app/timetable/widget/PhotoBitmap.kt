package app.timetable.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * 表现是"更新了但桌面没变化"，极难查。对策有三层，全部在下面落实：
 *  1. **按显示尺寸解码**：目标就是图片框的内容宽 × 高度（换算成 px），不多解一个像素；
 *  2. `inSampleSize` 先粗降采样（2 的幂，且保证解码后**不小于**目标，避免解出马赛克）；
 *  3. `RGB_565`（2 字节/像素）+ 像素总数硬上限 [MAX_BITMAP_BYTES] —— 照片没有透明通道，
 *     用 ARGB_8888 等于白送一半体积给 binder。
 */
internal object PhotoBitmap {

    private const val TAG = "WidgetPhoto"

    /** 单张图进 RemoteViews 的字节上限。1MB 是 binder 事务上限，留一半余量给其它指令 */
    const val MAX_BITMAP_BYTES = 400 * 1024

    /** [Bitmap.Config.RGB_565] 每像素 2 字节 */
    const val BYTES_PER_PIXEL = 2

    /** 像素总数上限（由字节上限换算） */
    const val MAX_PIXELS = MAX_BITMAP_BYTES / BYTES_PER_PIXEL

    /**
     * 纯函数（可单测）：图片框要显示多大（dp）→ 该解码成多少像素。
     *
     * 这两个 dp 值就是**框的真实尺寸**（框宽 = [WidgetData.contentWidthDp]，
     * 框高 = [WidgetData.photoBoxTargetDp] 经 [ExtrasPlanner] 分下来的结果），
     * 所以解出来的位图与框**逐值同比例** —— 宿主那边 `centerCrop` 实际裁不到东西，
     * 既不会被拉伸，也不会露缝。"窗口和图片大小不匹配"就是这么消掉的。
     *
     * @param widthDp  图片框的内容宽度（[WidgetData.contentWidthDp]）
     * @param heightDp 这次分给图片框的高度（[WidgetData.photoBoxTargetDp] / [ExtrasPlanner] 的结论）
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
     */
    fun sampleSizeAtLeast(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return 1
        var sample = 1
        while (srcW / (sample * 2) >= targetW && srcH / (sample * 2) >= targetH) sample *= 2
        return sample
    }

    /** 这张图解码出来的字节数（RGB_565），用来写日志、核对 binder 预算 */
    fun byteCount(bitmap: Bitmap): Int = bitmap.byteCount

    /** 人类可读的体积（日志里用） */
    fun sizeLabel(bytes: Int): String = "${(bytes + 512) / 1024}KB"

    /**
     * 纯函数（可单测）：人类可读的宽高比（日志里用），例如 126×71dp → "1.77:1"。
     *
     * 为什么要打这个：用户看不到画面，只能看日志。而"框和图比例对不对"这件事
     * 在日志里就是一串数字 —— 框的比例、bitmap 的像素数、byteCount 三者放在一起，
     * 就能判断是"框算错了"还是"解码没跟上"。宽高取不到时给 "?"（不掺假的默认值）。
     */
    fun ratioLabel(widthDp: Int, heightDp: Int): String {
        if (widthDp <= 0 || heightDp <= 0) return "?"
        return String.format(java.util.Locale.CHINA, "%.2f:1", widthDp.toFloat() / heightDp)
    }

    /**
     * 解码一张图到"显示所需的最小尺寸"。
     *
     * 返回 null 表示这张图用不了（文件被删、损坏、不是图片……）—— 调用方应当**跳过它、
     * 换下一张**，而不是让整块图片区（甚至整个小组件）跟着空白。
     *
     * 会在**调用线程**上解码。删掉图片轮播（AdapterViewFlipper + RemoteViewsService）之后，
     * 调用点就是小组件刷新本身（广播接收器的主线程）—— 已经降采样到"框的像素尺寸"，
     * 单张是几毫秒级（相册原图 1600px 先按 2 的幂降到接近目标，再裁再缩）。
     * 这是删轮播的必然结果：那套是在集合视图的 binder 线程上按项解码的，代价是它顺手把
     * 竖直手势也吃掉了（"开了轮播小组件就没法用"）。宁可主线程多花几毫秒，也不要一个滑不动的小组件。
     */
    fun decode(context: Context, path: String, targetW: Int, targetH: Int): Bitmap? = runCatching {
        val file = File(path)
        if (!file.isFile || !file.canRead()) return@runCatching null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeAtLeast(bounds.outWidth, bounds.outHeight, targetW, targetH)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeFile(path, opts) ?: return@runCatching null
        scaleCenterCrop(decoded, targetW, targetH)
    }.onFailure {
        Log.w(TAG, "解码失败 path=$path -> ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /**
     * 按 `centerCrop` 语义缩到目标尺寸：**先取源图中间那块（与目标同比例），再缩到目标**。
     *
     * 为什么不用 `createScaledBitmap` 直接拉：那样会把图压扁变形。之所以能在这里裁，
     * 是因为图片框的 `scaleType` 本来就是 `centerCrop` —— 我们现在裁掉的，正是宿主接下来
     * 也会裁掉的那部分，所以观感完全一致，但传过去的像素少了一截。
     */
    private fun scaleCenterCrop(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (src.width == targetW && src.height == targetH) return src
        val srcRatio = src.width.toFloat() / src.height.toFloat()
        val dstRatio = targetW.toFloat() / targetH.toFloat()
        val cropW: Int
        val cropH: Int
        if (srcRatio > dstRatio) {
            cropH = src.height
            cropW = (src.height * dstRatio).toInt()
        } else {
            cropW = src.width
            cropH = (src.width / dstRatio).toInt()
        }
        val cropped = if (cropW == src.width && cropH == src.height) {
            src
        } else {
            Bitmap.createBitmap(
                src,
                ((src.width - cropW) / 2).coerceAtLeast(0),
                ((src.height - cropH) / 2).coerceAtLeast(0),
                cropW.coerceIn(1, src.width),
                cropH.coerceIn(1, src.height)
            )
        }
        val out = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
        if (cropped !== src && out !== cropped) cropped.recycle()
        if (out !== src) src.recycle()
        return out
    }
}
