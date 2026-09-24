package app.timetable.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import app.timetable.R
import app.timetable.data.Prefs
import java.io.File

/**
 * 自动换图（"每隔 N 秒换一张"）。
 *
 * ## 为什么必须自己定时，不能再"轮播"
 *
 * 老实现是布局里放 `AdapterViewFlipper` + `setRemoteAdapter`：宿主自己会翻页，看起来很省事，
 * 但它同时是一个**可滚动的 AdapterView** —— 竖直手势被它吃掉，课程列表就滑不动了，
 * 这就是用户原话"自动轮播启动后小组件无法使用"。那条路径已经整条删除
 * （见 `widget_today.xml` 与 [WidgetExtras] 的注释），自动换图只能靠我们自己定时重画。
 *
 * ## 定时刷新的代价，以及这套做法怎么把代价压住
 *
 *  - **不唤醒设备**：用 [AlarmManager.RTC]（不是 RTC_WAKEUP，也不是 setExactAndAllowWhileIdle）。
 *    息屏后设备进入休眠，非唤醒闹钟**叫不醒它**，闹钟会顺延到下次亮屏才发 ——
 *    "用户没在看的时候不空转"就是这么实现的，代价是息屏那段间隔不精确。
 *  - **不碰用户的设置语义**：只认已有的 [Prefs.photoFlipEnabled]（默认**关闭**）与
 *    [Prefs.photoFlipSeconds]；最短间隔夹到 [MIN_INTERVAL_SEC] 秒（见 [intervalMs]）。
 *    注意设置页给的 5 秒档会被夹到 10 秒 —— 5 秒一次意味着每分钟 12 次位图过 binder，
 *    对电池和桌面流畅度都不值得，而 `data/Prefs.kt` / `SettingsActivity` 不在本轮可改范围内。
 *  - **只重画图片一个控件**：走 [AppWidgetManager.partiallyUpdateAppWidget]。
 *    整块重画（build + updateAppWidget）会重新 setRemoteAdapter，列表滚动位置可能被顶回顶部；
 *    每隔几秒来一次，等于换个方式再犯一次"组件没法用"。
 *    （点击换图那条路仍然整块重画：那一下用户正看着，必须保证"点了就变"。）
 *  - **该排才排**：只有"开关开着 + 至少 2 张图 + 图片区真的显示着 + 桌面上还有组件"才排闹钟；
 *    其余情况一律 [cancel]（见 [sync]）。组件全被移除时 [TodayWidgetProvider.onDisabled] 也会取消。
 *
 * ## 已知的不完美（真机上需要确认）
 *
 *  - 非精确闹钟会被系统批量延迟：间隔的实际语义是"**至少** N 秒"，忙时可能晚几十秒；
 *  - 个别启动器对 partiallyUpdateAppWidget 支持不好 → 自动换图可能不生效（**点击换图不受影响**，
 *    它走的是和"刷新"按钮完全相同的整块重画路径）；
 *  - App 被强行停止后系统会清掉闹钟，要等下一次组件刷新（30 分钟 / 上课边界 / 打开 App）才会恢复。
 */
internal object PhotoAutoAdvance {

    private const val TAG = "WidgetPhoto"

    /** 自动换图的闹钟用独立 action（不能和点击换图共用 PendingIntent：取消会把点击也一起废掉） */
    const val ACTION_PHOTO_ALARM = "app.timetable.action.WIDGET_PHOTO_ALARM"

    /**
     * 自动换图的最短间隔（秒）。
     *
     * 用户可选的 5 秒太密：每分钟 12 次"唤醒我们的进程 + 解一张图 + 传一次位图"，
     * 桌面在滑动时的流畅度与电池都会受影响，而小组件照片本来也不是视频。
     * 10 秒是"看得出在动"与"不至于费电"的折中（30 秒档原样保留）。
     */
    const val MIN_INTERVAL_SEC = 10

    /** 闹钟的 requestCode。刷新=1 / 模式=2 / 点击换图=3，这里用 4，互不干扰 */
    private const val REQ_ALARM = 4

    /**
     * 纯函数（可单测）：设置里的秒数 → 真正的间隔（毫秒）。
     *
     * 比 [MIN_INTERVAL_SEC] 小的值（设置页的 5 秒档、或被写坏的值）一律抬到下限；
     * 负数/0 同样落到下限，不会变成"死循环式刷新"。
     */
    fun intervalMs(seconds: Int): Long = maxOf(seconds, MIN_INTERVAL_SEC) * 1000L

    /**
     * 纯函数（可单测）：现在该不该挂着自动换图的闹钟。
     *
     * 四条缺一不可：用户开着自动、桌面上还有组件、图片区真的显示着（被强制隐藏时不算）、
     * 至少两张图（一张图"换"了还是同一张，白跑）。
     */
    fun shouldRun(
        autoEnabled: Boolean,
        photoCount: Int,
        photoVisible: Boolean,
        widgetCount: Int
    ): Boolean = autoEnabled && widgetCount > 0 && photoVisible && photoCount > 1

    /**
     * 按当前真实状态排/撤闹钟。幂等，随便调 —— 每次重画小组件都会调一次
     * （见 [WidgetExtras.apply]），所以"关掉开关"或"删到只剩一张"能立刻生效。
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        Prefs.init(app)
        val mgr = AppWidgetManager.getInstance(app)
        val ids = if (mgr == null) {
            IntArray(0)
        } else {
            runCatching {
                mgr.getAppWidgetIds(ComponentName(app, TodayWidgetProvider::class.java))
            }.getOrDefault(IntArray(0))
        }
        val photos = WidgetData.photoList(app).count { File(it).exists() }
        val ok = shouldRun(
            autoEnabled = Prefs.photoFlipEnabled,
            photoCount = photos,
            photoVisible = photoBoxDp(app) > 0,
            widgetCount = ids.size
        )
        if (ok) arm(app, intervalMs(Prefs.photoFlipSeconds)) else cancel(app)
    }

    /** 撤掉闹钟（组件被移除、开关关掉、只剩一张图……） */
    fun cancel(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            app, REQ_ALARM, intent(app),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        runCatching {
            am.cancel(pi)
            pi.cancel()
        }
    }

    /**
     * 只把图片那一个 ImageView 换成第 [index] 张（解码失败就返回 false，交给调用方整块重画）。
     *
     * 解码目标与 [WidgetExtras.apply] 用的是**同一套算法同一个框**（[PhotoBitmap.targetPx] +
     * [PhotoFit.layout]），所以自动换的那一张和点击换的那一张尺寸、比例完全一致，
     * 不会出现"自动换的图比手点的小一圈"。
     *
     * 上一轮这里还读一次"显示方式"偏好；这一轮没有这个偏好了 ——
     * 裁哪一块由用户导入时决定（文件本身就是裁好的），怎么放由框和这张图的比例决定。
     * 少一个输入源，就少一类"自动换的图与手点的图不一样"的失败模式。
     */
    fun repaintPhoto(context: Context, index: Int): Boolean {
        val app = context.applicationContext
        val mgr = AppWidgetManager.getInstance(app) ?: return false
        val boxDp = photoBoxDp(app)
        if (boxDp <= 0) return false

        val contentDp = WidgetData.contentWidthDp(app)
        val photos = WidgetExtras.photos(app)
        val target = PhotoBitmap.targetPx(contentDp, boxDp, app.resources.displayMetrics.density)
        val shown = WidgetExtras.decodeFrom(app, photos, index, target) ?: return false
        val (at, render) = shown
        PhotoCursor.remember(app, at)

        val partial = RemoteViews(app.packageName, R.layout.widget_today)
        val set = runCatching { partial.setImageViewBitmap(R.id.widget_photo_image, render.bitmap) }
        if (set.isFailure) {
            Log.w(
                TAG,
                "自动换图：位图塞不进 RemoteViews，退化成整块重画（" +
                    "${set.exceptionOrNull()?.javaClass?.simpleName}: ${set.exceptionOrNull()?.message}）"
            )
            return false
        }

        val ids = runCatching {
            mgr.getAppWidgetIds(ComponentName(app, TodayWidgetProvider::class.java))
        }.getOrDefault(IntArray(0))
        var any = false
        for (id in ids) {
            val r = runCatching { mgr.partiallyUpdateAppWidget(id, partial) }
            if (r.isFailure) {
                Log.w(
                    TAG,
                    "自动换图局部刷新失败 id=$id 原因=" +
                        "${r.exceptionOrNull()?.javaClass?.simpleName}: ${r.exceptionOrNull()?.message}"
                )
            } else {
                any = true
            }
        }
        if (any) {
            val frame = WidgetData.photoFrame(app)
            Log.i(
                TAG,
                "自动换图 → 第 ${at + 1}/${photos.size} 张（局部刷新）" +
                    PhotoBitmap.renderLabel(
                        render = render,
                        frame = frame,
                        boxWidthDp = contentDp,
                        boxHeightDp = boxDp,
                        wantedHeightDp = WidgetData.photoWantedHeightDp(app),
                        rows = WidgetData.visibleRows(app)
                    ) +
                    " set=ok(partial)"
            )
        }
        return any
    }

    /** 排下一次（一次性闹钟，触发后由 receiver 再排下一次 —— 这样"关掉开关"能立刻停） */
    private fun arm(app: Context, intervalMs: Long) {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            am.set(AlarmManager.RTC, System.currentTimeMillis() + intervalMs, pi(app))
        }.onFailure {
            Log.w(TAG, "自动换图闹钟排不上：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun pi(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app, REQ_ALARM, intent(app),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun intent(app: Context): Intent =
        Intent(app, TodayWidgetProvider::class.java).setAction(ACTION_PHOTO_ALARM)

    private fun photoBoxDp(context: Context): Int =
        runCatching { WidgetExtras.photoBoxDp(context) }.getOrDefault(0)
}
