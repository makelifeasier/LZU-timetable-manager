package app.timetable.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.timetable.R
import app.timetable.data.Prefs
import java.io.File
import kotlin.math.ceil

/**
 * 给小组件底部的图片轮播（AdapterViewFlipper）供数据。
 *
 * 为什么用集合视图而不是"每次刷新换一张图"：小组件能自主刷新最快也就 30 分钟一轮，
 * 想做到"每 10 秒换一张"只能靠宿主的集合视图自身轮播 —— 这是安卓小组件里唯一可行的手段。
 *
 * **这里传的是 Bitmap，不是 URI**：集合项同样是由启动器进程 inflate 的，
 * 用 file:// 读我们的私有目录必然失败，而 content:// 又没法定向授权（拿不到宿主包名）。
 * 把"我们自己解码好的图"直接交给宿主，是唯一不需要任何权限的路径 —— 详见 [PhotoBitmap]。
 */
class PhotoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        PhotoFactory(
            applicationContext,
            // 由 Provider 传进来的"这次实际分给图片框的高度"：轮播项按它解码，
            // 就不会因为"按理想高度解码"而多传一堆宿主根本不会显示的行
            intent.getIntExtra(EXTRA_PHOTO_HEIGHT_DP, 0)
        )

    companion object {
        const val EXTRA_PHOTO_HEIGHT_DP = "photo_height_dp"
    }
}

/** 图片项工厂 */
class PhotoFactory(
    private val context: Context,
    private val extraHeightDp: Int = 0
) : RemoteViewsService.RemoteViewsFactory {

    private var photos: List<String> = emptyList()

    /** 这一轮该解成多少像素（与单图路径同一套算法，保证轮播与单图观感一致） */
    private var targetW = 0
    private var targetH = 0

    override fun onCreate() = load()

    override fun onDataSetChanged() = load()

    override fun onDestroy() {
        photos = emptyList()
    }

    private fun load() {
        Prefs.init(context)
        photos = WidgetData.photoList(context).filter { File(it).exists() }
        val heightDp = if (extraHeightDp > 0) extraHeightDp else WidgetData.photoHeightDp(context)
        val target = PhotoBitmap.targetPx(
            WidgetData.contentWidthDp(context),
            heightDp,
            context.resources.displayMetrics.density
        )
        targetW = target[0]
        targetH = target[1]
    }

    override fun getCount(): Int = photos.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (photos.isEmpty()) return null
        // 坏图（文件被删/损坏/不是图片）不能让整个轮播空掉：按"当前这张 → 其余各张"的顺序
        // 找第一张能解码的顶上，实在都不行才返回 null（宿主显示空位）
        val order = listOf(position) + photos.indices.filter { it != position }
        for (index in order) {
            val path = photos.getOrNull(index) ?: continue
            val bitmap = PhotoBitmap.decode(context, path, targetW, targetH) ?: continue
            val views = RemoteViews(context.packageName, R.layout.widget_photo_item)
            return runCatching {
                views.setImageViewBitmap(R.id.photo_item, bitmap)
                // 点图片 → 打开 App（具体由 Provider 的 pendingIntentTemplate 决定）
                views.setOnClickFillInIntent(R.id.photo_item, Intent())
                Log.i(
                    TAG,
                    "轮播项 position=$position 用第 $index 张 " +
                        "bitmap=${bitmap.width}x${bitmap.height}/${PhotoBitmap.sizeLabel(PhotoBitmap.byteCount(bitmap))}"
                )
                views
            }.onFailure {
                Log.w(TAG, "轮播项构建失败 position=$position path=$path 原因=${it.javaClass.simpleName}: ${it.message}")
                bitmap.recycle()
            }.getOrNull()
        }
        Log.w(TAG, "轮播项全部解码失败 position=$position（共 ${photos.size} 张）")
        return null
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false

    private companion object {
        const val TAG = "WidgetPhoto"
    }
}

/**
 * 把"每日一句 / 图片"两块扩展内容挂到小组件的 RemoteViews 上。
 *
 * 单独抽出来是因为这段逻辑全在跟 RemoteViews 的限制打交道，放在 build() 里会很吵。
 */
internal object WidgetExtras {

    private const val TAG = "WidgetPhoto"

    fun apply(context: Context, views: RemoteViews, openApp: android.app.PendingIntent) {
        Prefs.init(context)
        val override = Prefs.widgetExtrasOverride
        val space = WidgetData.extraSpaceDp(context)

        // 真有一张以上图片才谈得上显示 —— 开关开着但图被删光的场景很常见
        val photos = WidgetData.photoList(context).filter { File(it).exists() }
        val plan = ExtrasPlanner.plan(
            override = override,
            quoteEnabled = Prefs.quoteEnabled,
            photoEnabled = Prefs.photoEnabled,
            photoCount = photos.size,
            availableDp = space,
            idealPhotoDp = WidgetData.photoHeightDp(context)
        )

        // ---- 图片 ----
        if (plan.photoVisible) {
            // 高度只能用 setViewLayoutHeight 表达（布局文件里图片框是 0dp，位置/顺序由布局定）
            val flip = Prefs.photoFlipEnabled && photos.size > 1
            val target = PhotoBitmap.targetPx(
                WidgetData.contentWidthDp(context),
                plan.photoHeightDp,
                context.resources.displayMetrics.density
            )
            // 单图路径要在这里就把图解出来；轮播路径由 PhotoFactory 逐项解
            val single = if (flip) null else firstDecodable(context, photos, target[0], target[1])

            if (!flip && single == null) {
                // 一张都解不出来（文件被删/损坏）：退回"不显示图片"，但句子照旧 —— 不能整块空白
                views.setViewVisibility(R.id.widget_photo_area, View.GONE)
                Log.w(
                    TAG,
                    "扩展区 $plan 余量=${space}dp 覆盖=$override 图片=${photos.size}张 " +
                        "bitmap=全部解码失败 set=no（图片区已隐藏）"
                )
            } else {
                runCatching {
                    views.setViewLayoutHeight(
                        R.id.widget_photo_area,
                        plan.photoHeightDp.toFloat(),
                        TypedValue.COMPLEX_UNIT_DIP
                    )
                }
                views.setViewVisibility(R.id.widget_photo_area, View.VISIBLE)
                views.setViewVisibility(R.id.widget_photo_flipper, if (flip) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.widget_photo_image, if (flip) View.GONE else View.VISIBLE)

                if (flip) {
                    views.setRemoteAdapter(
                        R.id.widget_photo_flipper,
                        Intent(context, PhotoWidgetService::class.java)
                            .putExtra(
                                PhotoWidgetService.EXTRA_PHOTO_HEIGHT_DP,
                                plan.photoHeightDp
                            )
                    )
                    // 这两个方法在 RemoteViews 白名单里；万一某个启动器不支持，runCatching 兜住，
                    // 退化成"显示第一张"，而不是整块不显示。
                    runCatching {
                        views.setInt(
                            R.id.widget_photo_flipper,
                            "setFlipInterval",
                            Prefs.photoFlipSeconds * 1000
                        )
                    }
                    runCatching { views.setBoolean(R.id.widget_photo_flipper, "setAutoStart", true) }
                    views.setPendingIntentTemplate(R.id.widget_photo_flipper, openApp)
                    Log.i(
                        TAG,
                        "扩展区 $plan 余量=${space}dp 覆盖=$override 图片=${photos.size}张 " +
                            "bitmap=轮播每项 ${target[0]}x${target[1]}（由 PhotoFactory 逐项解码） set=ok"
                    )
                } else {
                    val bitmap = single!!
                    val set = runCatching {
                        views.setImageViewBitmap(R.id.widget_photo_image, bitmap)
                    }
                    Log.i(
                        TAG,
                        "扩展区 $plan 余量=${space}dp 覆盖=$override 图片=${photos.size}张 " +
                            "bitmap=${bitmap.width}x${bitmap.height}/" +
                            "${PhotoBitmap.sizeLabel(PhotoBitmap.byteCount(bitmap))} " +
                            "set=${if (set.isSuccess) "ok" else "FAIL:${set.exceptionOrNull()?.javaClass?.simpleName}"}"
                    )
                }
            }
        } else {
            views.setViewVisibility(R.id.widget_photo_area, View.GONE)
            Log.i(
                TAG,
                "扩展区 $plan 余量=${space}dp 覆盖=$override 图片=${photos.size}张（图片未显示）"
            )
        }

        // ---- 每日一句：不再因为"图片开着"就无条件让位，两块由 ExtrasPlanner 一起决策 ----
        if (plan.quoteVisible) {
            val quote = app.timetable.greet.QuoteOfDay.forDate(java.time.LocalDate.now())
            views.setTextViewText(R.id.widget_quote, quote)
            views.setViewVisibility(R.id.widget_quote, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_quote, View.GONE)
        }
    }

    /**
     * 依次解码，返回第一张成功的（连带它的路径）。
     *
     * "第一张坏了就整块空白"是最糟的失败模式：用户看到的是自己开了图片却什么都没有，
     * 而日志里其实只有一张图有问题。所以这里按顺序往后找。
     */
    private fun firstDecodable(
        context: Context,
        photos: List<String>,
        targetW: Int,
        targetH: Int
    ): Bitmap? {
        for (path in photos) {
            val bitmap = PhotoBitmap.decode(context, path, targetW, targetH)
            if (bitmap != null) return bitmap
            Log.w(TAG, "跳过一张解不出来的图：$path")
        }
        return null
    }
}
