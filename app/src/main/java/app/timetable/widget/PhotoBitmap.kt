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
     * 为什么是 600KB：binder 事务上限约 1MB，而 RemoteViews 里除了这张位图还有列表、文案、
     * PendingIntent 等指令（合计几十 KB）。**曾经压到 300KB，但那会把大组件上的图压小**：
     * 真机实测框 349dp 宽（916px）的 3.14:1 图需要 916×291×2 = 533KB，300KB 直接把它缩成
     * 694×221（再由宿主放大 → 变糊）。600KB 能让常见大图原尺寸过去，同时给
     * "ARGB 意外翻倍"和"其余指令"都留了余量（真正的第一道防线是解码后强制 RGB_565）。
     */
    const val MAX_BITMAP_BYTES = 600 * 1024

    /** [Bitmap.Config.RGB_565] 每像素 2 字节 */
    const val BYTES_PER_PIXEL = 2

    /** 像素总数上限（由字节上限换算，**只对 RGB_565 成立**，所以解码后还要按真实 byteCount 复验） */
    const val MAX_PIXELS = MAX_BITMAP_BYTES / BYTES_PER_PIXEL

    /**
     * 纯函数（可单测）：图片框要显示多大（dp）→ 该解码成多少像素。
     *
     * 这两个 dp 值就是**框的真实尺寸**（框宽 = [WidgetData.contentWidthDp]，
     * 框高 = [WidgetData.photoWantedHeightDp] 算出来的"照片需要的高"，空间不够时再由
     * [ExtrasPlanner] 降下来），所以解出来的位图与框**逐值同比例** —— 宿主那边什么裁剪也不需要做。
     *
     * @param widthDp  图片框的内容宽度（[WidgetData.contentWidthDp]）
     * @param heightDp 这一次图片框分到的高度（[ExtrasPlanner] 的结论 = [WidgetData.photoBoxTargetDp]）
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
     * `裁剪框=`、`完整显示/取中间块`、`留边=`、`行数=`、`bitmap=`、`byteCount=`、`config=`。
     * 有了它们，"组件里那张图是不是我们要的那一块、留了多少白、排了几行课"就能直接对着截图数像素。
     *
     * ## `需要的框高=` 与 `框=` 必须能放在一起比
     *
     * 这两列**应该相等**：相等 = 框刚好装下照片（零留边），这是本次改动要的结果；
     * `框 < 需要的框高` = 空间不够、框被压扁（走"取中间块"，属于允许的兜底）；
     * `框 > 需要的框高` = 又回到了"框高由剩余空间定"的老病（下拉组件时一大片空白），
     * 看到这一列不等就该去查 [WidgetData.photoBoxTargetDp] 是不是又被改回"按余量算"了。
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
     */
    fun renderLabel(
        facts: RenderFacts,
        frame: CropFrame,
        boxWidthDp: Int,
        boxHeightDp: Int,
        wantedHeightDp: Int,
        rows: Int,
        photoRatioLabel: String
    ): String {
        val layout = facts.layout
        // 用户要求的那一行必须自带"留边"的**像素数**（不带百分比）：真机核对是拿截图数像素的，
        // 百分比只是给人一眼看的粗细（0% 与 1px 是同一档，1px 肉眼看不出来）
        val edge = if (layout.whole) {
            "留边=${layout.padY}px(${Math.round(layout.padShare * 100)}%)"
        } else {
            "窗=${layout.window}"
        }
        return "框=${boxWidthDp}x${boxHeightDp}dp 比例=${ratioLabel(boxWidthDp, boxHeightDp)} " +
            "照片比例=$photoRatioLabel 需要的框高=${wantedHeightDp}dp " +
            "裁剪框=${frame.label} 源=${facts.sourceW}x${facts.sourceH}" +
            "(${ratioLabel(facts.sourceW, facts.sourceH)}) " +
            "裁剪图=${layout.window.width}x${layout.window.height} " +
            "${layout.verdict} $edge 行数=$rows " +
            "bitmap=${facts.bitmapW}x${facts.bitmapH} " +
            "byteCount=${facts.byteCount}B(${sizeLabel(facts.byteCount)}) " +
            "config=${facts.config}"
    }

    /**
     * [renderLabel] 的便捷入口（真机路径用这个）：把"框该多高、这张图什么比例、排了几行"
     * 三个数字按当前状态算出来再格式化。三个数都是纯算术，没有任何 Context 依赖，
     * 也没有可变的进程状态 —— 单测能直接构造 [RenderFacts] 验这一行。
     *
     * @param wantedHeightDp 图片框**应该**有的高度。调用方必须传**决定框高的那个比例**
     *        （[WidgetData.photoWantedHeightDp]，即照片列表第一张的比例）算出来的值，
     *        不能按当前显示那张算 —— 否则日志里的 `框 == 需要的框高` 这条不变量会被自己破坏，
     *        而那条不变量正是"零留边"的判据。
     * @param rows 这一轮实际排出来的整行课程数（[WidgetData.visibleRows]），与列表用的是同一次计算
     */
    fun renderLabel(
        render: PhotoRender,
        frame: CropFrame,
        boxWidthDp: Int,
        boxHeightDp: Int,
        wantedHeightDp: Int,
        rows: Int
    ): String = renderLabel(
        facts = render.facts(),
        frame = frame,
        boxWidthDp = boxWidthDp,
        boxHeightDp = boxHeightDp,
        wantedHeightDp = wantedHeightDp,
        rows = rows,
        // 这一列说的是**当前显示的这一张**什么比例：与"决定框高的那张"不同时，
        // 上面的 `留边=` 会把它放大成看得见的数字（哪天真的混进了不同比例的照片，日志里一眼能看出来）
        photoRatioLabel = ratioLabel(render.sourceW, render.sourceH)
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
