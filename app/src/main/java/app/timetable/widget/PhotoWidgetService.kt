package app.timetable.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.timetable.R
import app.timetable.data.Prefs
import java.io.File

/**
 * 给小组件底部的图片轮播（AdapterViewFlipper）供数据。
 *
 * 为什么用集合视图而不是"每次刷新换一张图"：小组件能自主刷新最快也就 30 分钟一轮，
 * 想做到"每 10 秒换一张"只能靠宿主的集合视图自身轮播 —— 这是安卓小组件里唯一可行的手段。
 *
 * 注意：这里**只传 URI**，Bitmap 由启动器按 ImageView 尺寸自己解码。
 * 直接把 Bitmap 塞进 RemoteViews 会撞上 1MB 的 binder 上限（两三张大图就超），
 * 表现是"更新了但桌面没变化"，极难排查。
 */
class PhotoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        PhotoFactory(applicationContext)
}

/** 图片项工厂 */
class PhotoFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var photos: List<String> = emptyList()

    override fun onCreate() = load()

    override fun onDataSetChanged() = load()

    override fun onDestroy() {
        photos = emptyList()
    }

    private fun load() {
        Prefs.init(context)
        photos = WidgetData.photoList(context).filter { File(it).exists() }
    }

    override fun getCount(): Int = photos.size

    override fun getViewAt(position: Int): RemoteViews? {
        val path = photos.getOrNull(position) ?: return null
        val views = RemoteViews(context.packageName, R.layout.widget_photo_item)
        runCatching {
            views.setImageViewUri(R.id.photo_item, Uri.fromFile(File(path)))
        }.onFailure {
            // 单张图坏了不该让整个轮播空掉：跳过这一项，交给启动器显示空位
            return null
        }
        // 点图片 → 打开 App（具体由 Provider 的 pendingIntentTemplate 决定）
        views.setOnClickFillInIntent(R.id.photo_item, Intent())
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}

/**
 * 把"每日一句 / 图片"两块扩展内容挂到小组件的 RemoteViews 上。
 *
 * 单独抽出来是因为这段逻辑全在跟 RemoteViews 的限制打交道，放在 build() 里会很吵。
 */
internal object WidgetExtras {

    /** 至少需要多少 dp 才值得显示一句（约一行 11sp 文字） */
    private const val QUOTE_MIN_DP = WidgetData.EXTRA_MIN_DP

    fun apply(context: Context, views: RemoteViews, openApp: android.app.PendingIntent) {
        Prefs.init(context)
        val allowed = WidgetData.extrasAllowed(context)
        val space = WidgetData.extraSpaceDp(context)

        // ---- 图片：优先级高于每日一句（图更值钱；两者都开时把余量让给图）----
        val photos = WidgetData.photoList(context).filter { File(it).exists() }
        val photoOn = allowed && Prefs.photoEnabled && photos.isNotEmpty()
        if (photoOn) {
            val h = WidgetData.photoHeightDp(context).coerceAtMost(space + WidgetData.EXTRA_MIN_DP)
            runCatching {
                views.setViewLayoutHeight(
                    R.id.widget_photo_area,
                    h.toFloat(),
                    android.util.TypedValue.COMPLEX_UNIT_DIP
                )
            }
            views.setViewVisibility(R.id.widget_photo_area, View.VISIBLE)

            val flip = Prefs.photoFlipEnabled && photos.size > 1
            views.setViewVisibility(
                R.id.widget_photo_flipper,
                if (flip) View.VISIBLE else View.GONE
            )
            views.setViewVisibility(
                R.id.widget_photo_image,
                if (flip) View.GONE else View.VISIBLE
            )
            if (flip) {
                views.setRemoteAdapter(R.id.widget_photo_flipper, Intent(context, PhotoWidgetService::class.java))
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
            } else {
                runCatching {
                    views.setImageViewUri(R.id.widget_photo_image, Uri.fromFile(File(photos.first())))
                }
            }
        } else {
            views.setViewVisibility(R.id.widget_photo_area, View.GONE)
        }

        // ---- 每日一句：图片占了就不抢位置，只有单独开启且确实有余量时才显示 ----
        val quoteOn = allowed && Prefs.quoteEnabled && !photoOn && space >= QUOTE_MIN_DP
        if (quoteOn) {
            val quote = app.timetable.greet.QuoteOfDay.forDate(java.time.LocalDate.now())
            views.setTextViewText(R.id.widget_quote, quote)
            views.setViewVisibility(R.id.widget_quote, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_quote, View.GONE)
        }
    }
}
