package app.timetable.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
 * 桌面小组件：可上下滑动的课程列表，右上角一个图标按钮切换两种模式。
 *
 *  - **接下来**：正在上 + 接下来要上的（跨天）
 *  - **整天**：今天全部课，从早到晚
 *
 * 两种模式都在右侧显示**起止时间**。列表用 ListView + [TimetableWidgetService]，
 * 这是安卓上做可滚动小组件的唯一正路。
 *
 * 刻意**不再提示「登录已过期」**：缓存数据照样能看，反复弹提示只会烦人。
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            runCatching { appWidgetManager.updateAppWidget(id, build(context, id)) }
        }
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
        runCatching { appWidgetManager.updateAppWidget(appWidgetId, build(context, appWidgetId)) }
        runCatching {
            appWidgetManager.notifyAppWidgetViewDataChanged(
                intArrayOf(appWidgetId), R.id.widget_list
            )
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
        }
    }

    companion object {
        const val ACTION_REFRESH = "app.timetable.action.WIDGET_REFRESH"
        const val ACTION_TICK = "app.timetable.action.WIDGET_TICK"
        const val ACTION_TOGGLE_MODE = "app.timetable.action.WIDGET_TOGGLE_MODE"
        const val EXTRA_COURSE = "course"

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
            for (id in ids) runCatching { mgr.updateAppWidget(id, build(app, id)) }
            runCatching { mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list) }
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
            // 图标是纯白矢量，用 setColorFilter 上成强调色
            runCatching { views.setInt(R.id.widget_mode, "setColorFilter", colors.accent) }

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

            val status = TimetableRepository.status
            val result = TimetableRepository.result()
            val rows = WidgetData.rows(context)
            val mode = WidgetData.mode(context)
            val week = WidgetData.currentWeek(context)
            val today = java.time.LocalDate.now()

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
                    views.setTextViewText(R.id.widget_footer, status.message)
                }

                // 有数据但当前模式没内容
                rows.isEmpty() -> {
                    views.setTextViewText(R.id.widget_subtitle, "第 $week 周")
                    views.setTextViewText(
                        R.id.widget_empty,
                        context.getString(
                            if (mode == WidgetData.MODE_TODAY) R.string.widget_no_class
                            else R.string.widget_no_upcoming
                        )
                    )
                    views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                    views.setTextViewText(R.id.widget_footer, syncHint(status.at))
                }

                else -> {
                    val label = if (mode == WidgetData.MODE_TODAY) "今天" else "接下来"
                    views.setTextViewText(R.id.widget_subtitle, "第 $week 周  ·  $label ${rows.size} 节")
                    views.setViewVisibility(R.id.widget_empty, View.GONE)
                    views.setTextViewText(
                        R.id.widget_footer,
                        if (rows.size > 2) "${syncHint(status.at)} · 可上下滑动"
                        else syncHint(status.at)
                    )
                }
            }

            // 矮组件（小米的格子普遍更小）把页脚收起来，把这点高度让给课程：
            // 否则页脚自己就被裁掉半行，而课程一行都放不下。
            // 有课程时才收 —— 空状态里页脚那句话是唯一的信息。
            val compact = WidgetData.compact(context)
            views.setViewVisibility(
                R.id.widget_footer,
                if (compact && rows.isNotEmpty()) View.GONE else View.VISIBLE
            )
            return views
        }

        private fun broadcast(context: Context, requestCode: Int, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, TodayWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        private fun syncHint(at: Long): String {
            if (at == 0L) return "未同步"
            val min = (System.currentTimeMillis() - at) / 60000
            return when {
                min < 1 -> "刚刚同步"
                min < 60 -> "$min 分钟前同步"
                min < 60 * 24 -> "${min / 60} 小时前同步"
                else -> "${min / (60 * 24)} 天前同步"
            }
        }
    }
}
