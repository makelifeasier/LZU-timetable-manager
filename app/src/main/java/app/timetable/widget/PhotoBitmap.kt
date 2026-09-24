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
 */
internal object PhotoBitmap {

    private const val TAG = "WidgetPhoto"

    /**
     * 单张图进 RemoteViews 的**真实字节**上限。
     *
     * 为什么是 300KB 而不是 400KB：binder 事务上限约 1MB，而 RemoteViews 里除了这张位图
     * 还有列表、文案、PendingIntent 等一堆指令，位图是按 Parcel 序列化过去的（还带一份头部）。
     * 用户真机上一次 917×137 的图就已经因为 ARGB 变成 491KB —— 预算留得越松，
     * "整次更新被系统丢掉"这种最难查的失败模式就越容易出现。300KB 对 917×137 的
     * RGB_565（245KB）仍然够用（这也是实测最常见的框尺寸）。
     */
    const val MAX_BITMAP_BYTES = 300 * 1024

    /** [Bitmap.Config.RGB_565] 每像素 2 字节 */
    const val BYTES_PER_PIXEL = 2

    /** 像素总数上限（由字节上限换算，**只对 RGB_565 成立**，所以解码后还要按真实 byteCount 复验） */
    const val MAX_PIXELS = MAX_BITMAP_BYTES / BYTES_PER_PIXEL

    /**
     * 纯函数（可单测）：图片框要显示多大（dp）→ 该解码成多少像素。
     *
     * 这两个 dp 值就是**框的真实尺寸**（框宽 = [WidgetData.contentWidthDp]，
     * 框高 = [WidgetData.photoBoxTargetDp] 经 [ExtrasPlanner] 分下来的结果），
     * 所以解出来的位图与框**逐值同比例** —— 宿主那边什么裁剪也不需要做。
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
     * 必须出现的列（缺一列就少一个判据）：`框=`、`比例=`、`裁剪框=`、`完整显示/取中间块`、
     * `bitmap=`、`byteCount=`、`config=`。有了它们，"组件里那张图是不是我们要的那一块"
     * 就能直接对着截图数像素。
     *
     * 入参是 [RenderFacts] 而不是 Bitmap：单测里 Android 的 `Bitmap` 是"返回默认值"的假实现
     * （`Bitmap.createBitmap` 直接给 null），拿它当入参的话这段格式化代码一行都测不到 ——
     * 而日志是本项目唯一的核对手段，格式串必须被钉住。
     */
    fun renderLabel(
        facts: RenderFacts,
        frame: CropFrame,
        boxWidthDp: Int,
        boxHeightDp: Int
    ): String {
        val layout = facts.layout
        val edge = if (layout.whole) {
            "留边=${layout.padY}px(${Math.round(layout.padShare * 100)}%)"
        } else {
            "窗=${layout.window}"
        }
        return "框=${boxWidthDp}x${boxHeightDp}dp 比例=${ratioLabel(boxWidthDp, boxHeightDp)} " +
            "裁剪框=${frame.label} 源=${facts.sourceW}x${facts.sourceH}" +
            "(${ratioLabel(facts.sourceW, facts.sourceH)}) " +
            "裁剪图=${layout.window.width}x${layout.window.height} " +
            "${layout.verdict} $edge " +
            "bitmap=${facts.bitmapW}x${facts.bitmapH} " +
            "byteCount=${facts.byteCount}B(${sizeLabel(facts.byteCount)}) " +
            "config=${facts.config}"
    }

    /** [renderLabel] 的便捷入口（真机路径用这个） */
    fun renderLabel(
        render: PhotoRender,
        frame: CropFrame,
        boxWidthDp: Int,
        boxHeightDp: Int
    ): String = renderLabel(render.facts(), frame, boxWidthDp, boxHeightDp)

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

        // 这一次"裁哪一块、缩到多大"—— 全部来自纯算术（PhotoFit.layout），这里只喂 Bitmap 工厂
        val layout = PhotoFit.layout(decoded.width, decoded.height, targetW, targetH)
        if (layout == null) {
            decoded.recycle()
            return@runCatching null
        }
        // 尺寸要在 recycle 之前记下来（[PhotoRender] 只存数字，不持有已被回收的 Bitmap）
        val srcW = decoded.width
        val srcH = decoded.height

        val sliced = slice(decoded, layout.window)
        val scaled = scaleTo(sliced, layout.outWidth, layout.outHeight)
        if (sliced !== decoded && sliced !== scaled) sliced.recycle()
        if (decoded !== scaled) decoded.recycle()

        // 真实的 byteCount 兜底：上面那条路按"2 字节/像素"估算，万一配置又不是 RGB_565
        // （某些 ROM 的 copy 也会失败），这里按实际字节数再压一次，宁可糊一点也不能让整次更新被丢掉
        val budgeted = fitBudget(scaled)
        PhotoRender(bitmap = budgeted, layout = layout, sourceW = srcW, sourceH = srcH)
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
     * 缩放到目标尺寸。
     *
     * 为什么用 `createScaledBitmap` 而不是 `createBitmap(..., matrix)`：这是唯一一条
     * 走 `filter=true`（双线性）的路径，缩放后的照片不会出现锯齿；尺寸已经正好时直接返回原对象。
     */
    private fun scaleTo(src: Bitmap, outW: Int, outH: Int): Bitmap {
        if (outW <= 0 || outH <= 0 || (src.width == outW && src.height == outH)) return src
        return Bitmap.createScaledBitmap(src, outW, outH, true)
    }

    /**
     * 最后一道闸：**按真实 `byteCount`** 判定，超了就等比缩到预算内。
     *
     * 这一步是这次真机事故直接加出来的：[targetPx] 的预算是在解码**前**按 2 字节/像素估的，
     * 而解码器不保证给 RGB_565。到手之后必须用真实字节数验一次，
     * 因为它要跟着 RemoteViews 过 binder，超了是"整次更新被系统丢掉"，不是"图糊一点"。
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
 * 一次渲染的完整结果：位图 + 这一次的几何结论 + 源图尺寸。
 *
 * 为什么要连 [layout] 一起带出来：[WidgetExtras] / [PhotoAutoAdvance] 的日志要靠它写
 * "完整显示 / 取中间块、留边多少、裁剪窗口在哪"，而单测里能验的只有 [layout]
 * （纯算术）那一半 —— 位图那半只能靠真机日志核对。
 */
internal class PhotoRender(
    val bitmap: Bitmap,
    val layout: PhotoFit.Layout,
    /** 解码后的源图尺寸（= 用户裁好那张裁剪图，经 inSampleSize 降采样后的尺寸） */
    val sourceW: Int,
    val sourceH: Int
) {
    /** 日志要的那几个数字（不含 Bitmap 本体，所以单测能直接构造来验证日志文本） */
    fun facts(): RenderFacts = RenderFacts(
        layout = layout,
        sourceW = sourceW,
        sourceH = sourceH,
        bitmapW = bitmap.width,
        bitmapH = bitmap.height,
        byteCount = PhotoBitmap.byteCount(bitmap),
        config = PhotoBitmap.configLabel(bitmap)
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
    val config: String
)
