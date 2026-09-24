package app.timetable.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import app.timetable.MainActivity
import app.timetable.R
import app.timetable.data.Prefs
import app.timetable.data.Session
import app.timetable.data.TimetableRepository
import app.timetable.data.WeekCalc
import app.timetable.reminder.WidgetTicker

/**
 * 桌面小组件：可上下滑动的课程列表 + 底部「每日一句 / 图片」扩展区，
 * 右上角两个按钮分别切模式（接下来 / 整天）与刷新。
 *
 *  - **接下来**：正在上 + 接下来要上的（跨天）
 *  - **整天**：今天全部课，从早到晚
 *
 * 两种模式都在右侧显示**起止时间**。列表用 ListView + [TimetableWidgetService]，
 * 这是安卓上做可滚动小组件的唯一正路。
 *
 * 图片区（[WidgetExtras]）是**普通 ImageView**：点一下换下一张（[ACTION_NEXT_PHOTO]）。
 * 曾经用 `AdapterViewFlipper` 自动轮播，但它会吃掉竖直手势、让课程列表滑不动
 * （用户："自动轮播启动后小组件无法使用"），那条路已整条删除；
 * 自动换图改由 [PhotoAutoAdvance] 用非唤醒闹钟定时重画图片那一个控件。
 *
 * 刻意**不再提示「登录已过期」**：缓存数据照样能看，反复弹提示只会烦人。
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) update(appWidgetManager, context, id)
        // 系统每 30 分钟唤醒一次；超过 15 分钟没同步才真的发网络请求
        val stale = System.currentTimeMillis() - TimetableRepository.status.at > 15 * 60 * 1000L
        if (stale) TimetableRepository.refresh(context.applicationContext)
    }

    /** 缩放小组件时重算「能放下几个整行」，否则高度会停留在旧值 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        update(appWidgetManager, context, appWidgetId)
        runCatching {
            appWidgetManager.notifyAppWidgetViewDataChanged(
                intArrayOf(appWidgetId), R.id.widget_list
            )
        }.onFailure {
            Log.w(TAG, "列表重新取数失败 id=$appWidgetId -> ${it.javaClass.simpleName}: ${it.message}", it)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                refreshAll(context)
                TimetableRepository.refresh(context.applicationContext) { refreshAll(context) }
            }

            // 上课/下课边界到了：文案里的「还剩 N 分」需要立刻更新
            ACTION_TICK -> {
                refreshAll(context)
                WidgetTicker.reschedule(context)
            }

            // 图标按钮：在「接下来 / 整天」之间切换
            ACTION_TOGGLE_MODE -> {
                Prefs.init(context)
                Prefs.widgetMode = if (Prefs.widgetMode == WidgetData.MODE_TODAY) {
                    WidgetData.MODE_NEXT
                } else {
                    WidgetData.MODE_TODAY
                }
                refreshAll(context)
            }

            // 点图片：换下一张。老实现是 AdapterViewFlipper 自己翻页（用户："开了轮播小组件就没法用"），
            // 那条路已经删掉了，现在换图只有这一个入口 —— 点一下。
            ACTION_NEXT_PHOTO -> nextPhoto(context, partial = false)

            // 自动换图（我们自己排的非唤醒闹钟，见 PhotoAutoAdvance）
            PhotoAutoAdvance.ACTION_PHOTO_ALARM -> nextPhoto(context, partial = true)
        }
    }

    /** 最后一个组件被移除：把自动换图的闹钟也撤掉，别让它在没人看的时候继续排 */
    override fun onDisabled(context: Context) {
        PhotoAutoAdvance.cancel(context)
    }

    /**
     * 换到下一张图并重画。
     *
     * @param partial true = 自动换图那条路：只把图片那一个 ImageView 换掉
     *   （[PhotoAutoAdvance.repaintPhoto]）。整块重画会重新 setRemoteAdapter，列表滚动位置
     *   可能被顶回顶部 —— 每隔几秒来一次，用户就又要说"组件没法用"了。
     *   false = 用户点的：**必须**整块重画（和"刷新"按钮同一条路径），"点了就变"比省那几毫秒重要。
     *
     * 无论哪条路，下标都会先推进（[PhotoCursor.next]），所以即使局部刷新失败退化成整块重画，
     * 显示的也是新的一张。
     */
    private fun nextPhoto(context: Context, partial: Boolean) {
        val photos = WidgetData.photoList(context)
        // 没有图片（被删光/清空）时没什么可换的：只记一行日志。图片区这时本来就是隐藏的，
        // 点不到；能走到这里的只有"闹钟到点了但图刚被删"这种空档期。
        if (photos.isEmpty()) {
            Log.i(TAG, "换图请求被忽略：当前没有任何图片")
            return
        }
        val at = PhotoCursor.next(context, photos.size)
        Log.i(
            TAG,
            "换图 → 第 ${at + 1}/${photos.size} 张（${if (partial) "自动" else "点击"}）"
        )
        if (partial && PhotoAutoAdvance.repaintPhoto(context, at)) {
            // 一次性闹钟：触发过就没了，这里排下一次（条件不满足时会顺手撤掉）
            PhotoAutoAdvance.sync(context)
        } else {
            updateViews(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "app.timetable.action.WIDGET_REFRESH"
        const val ACTION_TICK = "app.timetable.action.WIDGET_TICK"
        const val ACTION_TOGGLE_MODE = "app.timetable.action.WIDGET_TOGGLE_MODE"

        /** 点图片区 → 换下一张（[WidgetExtras] 里挂到 widget_photo_area 上） */
        const val ACTION_NEXT_PHOTO = "app.timetable.action.WIDGET_NEXT_PHOTO"
        const val EXTRA_COURSE = "course"

        private const val TAG = "WidgetPhoto"

        /**
         * 每行高度与头部/边距的换算统一放在 WidgetData 里（provider 与列表工厂共用），
         * 见 WidgetData.ROW_SLOT_DP / slotDp / visibleRows。
         */

        /** 刷新外观 + 通知集合视图重新取数 */
        fun refreshAll(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(app, TodayWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) update(mgr, app, id)
            runCatching { mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list) }
                .onFailure {
                    Log.w(TAG, "列表重新取数失败 -> ${it.javaClass.simpleName}: ${it.message}", it)
                }
        }

        /**
         * 只重画外观，**不**通知列表重新取数。
         *
         * 换图（点击、自动）走这条路：课程数据一个字都没变，没有理由让列表服务重跑一遍 ——
         * 那一次往返除了慢，还可能把用户的滚动位置顶回顶部。
         */
        fun updateViews(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(app, TodayWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) update(mgr, app, id)
        }

        /**
         * 更新一个小组件，**并把失败原因写进日志**。
         *
         * ## 为什么必须这么写（真机事故）
         *
         * 以前这里是 `runCatching { mgr.updateAppWidget(id, build(context, id)) }`：
         * `build()` 或 `updateAppWidget()` 一旦抛异常，异常被 `runCatching` 静静吃掉 ——
         * **`updateAppWidget` 根本不会执行**，界面停在旧内容，而日志里一个字母都没有。
         * 用户反馈的"导入图片后小组件无法显示"就是这样查不出原因的：
         * 位图太大过不了 binder、某个 RemoteViews 方法不被宿主支持、解码抛异常……
         * 全都会在 `build()` 里冒出来，然后被这条 `runCatching` 吞掉。
         *
         * 这里的取舍是：**保留 `runCatching`（去掉它等于让异常变成崩溃），但把异常变成日志**。
         * 崩溃比"组件不刷新"严重得多，而"静默不刷新"根本没法查 —— 两者都不是想要的，
         * 所以保留容错 + 强制留痕。
         */
        private fun update(mgr: AppWidgetManager, context: Context, id: Int) {
            val views = runCatching { build(context, id) }.getOrElse { t ->
                Log.w(TAG, "widget 构建失败 id=$id -> ${t.javaClass.simpleName}: ${t.message}", t)
                return
            }
            runCatching { mgr.updateAppWidget(id, views) }.onFailure { t ->
                Log.w(TAG, "widget 更新失败 id=$id -> ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }

        fun build(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            val colors = WidgetColors.of(context)

            views.setInt(R.id.widget_root, "setBackgroundResource", colors.widgetBg)
            views.setTextColor(R.id.widget_title, colors.text)
            views.setTextColor(R.id.widget_subtitle, colors.sub)
            views.setTextColor(R.id.widget_refresh, colors.accent)
            views.setTextColor(R.id.widget_empty, colors.sub)
            views.setTextColor(R.id.widget_footer, colors.sub)
            // 模式切换按钮：显示"现在看的是哪一种"，点一下切到另一种。
            // （以前是纯图标 + setColorFilter，用户看不懂那是什么 —— 见布局里的注释）
            val modeIsToday = Prefs.widgetMode == WidgetData.MODE_TODAY
            runCatching {
                views.setTextViewText(R.id.widget_mode, if (modeIsToday) "整天 ▾" else "接下来 ▾")
                views.setTextColor(R.id.widget_mode, colors.accent)
            }

            // 列表数据由服务提供（可滚动）
            views.setRemoteAdapter(
                R.id.widget_list,
                Intent(context, TimetableWidgetService::class.java)
            )
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)

            // 列表高度设成「整行」的整数倍（按 px 向上取整，避免换算时的亚像素截断）：
            // 直接让它填满剩余空间的话，底部会切出半行 —— 就是那个「第三节课露半截」。
            val listHeightPx = WidgetData.listHeightPx(context)
            runCatching {
                views.setViewLayoutHeight(
                    R.id.widget_list,
                    listHeightPx.toFloat(),
                    TypedValue.COMPLEX_UNIT_PX
                )
            }

            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, openApp)
            views.setPendingIntentTemplate(R.id.widget_list, openApp)
            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                broadcast(context, 1, ACTION_REFRESH)
            )
            views.setOnClickPendingIntent(
                R.id.widget_mode,
                broadcast(context, 2, ACTION_TOGGLE_MODE)
            )

            // 列表下方的扩展区：每日一句 / 图片（开关默认关闭，见 WidgetExtras）。
            // 图片区整块可点 → **换下一张**：这里用广播而不是"打开 App" ——
            // 用户点的是那张图，只想看下一张，不该被拽进 App。
            // （requestCode 3 是这一条专用的：PendingIntent 的身份由 (requestCode, Intent) 决定，
            //  与刷新/模式/闹钟分开，才不会互相覆盖或取消。）
            WidgetExtras.apply(context, views, broadcast(context, 3, ACTION_NEXT_PHOTO))

            val status = TimetableRepository.status
            val result = TimetableRepository.result()
            val rows = WidgetData.rows(context)
            val mode = WidgetData.mode(context)
            val week = WidgetData.currentWeek(context)
            val today = java.time.LocalDate.now()
            val modeLabel = if (mode == WidgetData.MODE_TODAY) "今天" else "接下来"
            // 每日一句**拼在副标题那一行**（用户要求："把话放在第 n 周接下来第 n 节同一行"）。
            // 它不再占列表的一格、也不再占列表下方一条 —— 一个像素的位置都不多花。
            // 开关（Prefs.quoteEnabled）与「强制隐藏」档位（-1）都照旧管着它。
            val quote = if (WidgetData.quoteShown(context)) {
                app.timetable.greet.QuoteOfDay.forDate(today)
            } else {
                null
            }

            views.setTextViewText(
                R.id.widget_title,
                "${today.monthValue}月${today.dayOfMonth}日 ${Session.dayLabel(WeekCalc.dayOf(today))}"
            )

            when {
                // 还没有任何数据：唯一需要引导登录的情况
                result.sessions.isEmpty() -> {
                    views.setTextViewText(R.id.widget_subtitle, "未同步")
                    views.setTextViewText(
                        R.id.widget_empty, context.getString(R.string.widget_never_synced)
                    )
                    views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                }

                // 有数据但当前模式没内容
                rows.isEmpty() -> {
                    views.setTextViewText(R.id.widget_subtitle, WidgetData.subtitleText(week, null, 0, quote))
                    views.setTextViewText(
                        R.id.widget_empty,
                        context.getString(
                            if (mode == WidgetData.MODE_TODAY) R.string.widget_no_class
                            else R.string.widget_no_upcoming
                        )
                    )
                    views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                }

                else -> {
                    views.setTextViewText(
                        R.id.widget_subtitle,
                        WidgetData.subtitleText(week, modeLabel, rows.size, quote)
                    )
                    views.setViewVisibility(R.id.widget_empty, View.GONE)
                }
            }

            // 底部那条「X 分钟前同步 · 可上下滑动」**整行删掉了**（用户要求）。
            // 控件本身留在布局里（debug 自检会引用它的 id），但永远不显示、也不设文字 ——
            // 空状态原来靠它显示登录状态，现在那条信息由「未同步/还没数据」的提示语承担。
            views.setViewVisibility(R.id.widget_footer, View.GONE)
            return views
        }

        private fun broadcast(context: Context, requestCode: Int, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, TodayWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        // syncHint() 已删除：它就是「X 分钟前同步」那句话的唯一来源，而用户要求把这一行去掉
        // （见上面 setViewVisibility(widget_footer, GONE) 的注释）。
        // 保留这段说明是为了下一个人别再把它加回来："多久没同步"在 App 里能看到，
        // 小组件上那一行只是白占 22dp。
    }
}
