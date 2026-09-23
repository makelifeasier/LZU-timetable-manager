package app.timetable.widget

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.timetable.R
import app.timetable.data.Prefs
import app.timetable.ui.TimetableRenderer

/**
 * 给桌面小组件的 ListView 供数据。
 *
 * RemoteViewsService 运行在**本应用进程**里（RemoteViews 再序列化给桌面宿主），
 * 所以这里可以直接读 Prefs 与仓库单例。
 */
class TimetableWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        AgendaFactory(applicationContext)
}

/** 列表项工厂；两种显示模式共用同一套行渲染 */
class AgendaFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<WidgetData.Row> = emptyList()
    private var colors = WidgetColors.of(context)

    override fun onCreate() {
        load()
    }

    override fun onDataSetChanged() {
        load()
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    private fun load() {
        colors = WidgetColors.of(context)
        Prefs.init(context)
        rows = WidgetData.rows(context)
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val item = rows.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_today_row)
        return buildRow(context, item, colors)
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false

    companion object {
        /** 单个列表项的 RemoteViews；自检工具也会直接调它来验证渲染 */
        internal fun buildRow(
            context: Context,
            item: WidgetData.Row,
            colors: WidgetColors
        ): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_today_row)
            // 不要在这里 setViewLayoutHeight：ListView 会用自己的 LayoutParams 覆盖掉，
            // 白写。行高统一由「行布局固定 38dp」+「列表高度 = 整行数 × 38dp」保证，
            // 见 widget_today_row.xml 的注释与 WidgetData.listHeightDp。
            row.setInt(
                R.id.row_card, "setBackgroundResource",
                if (item.ongoing) colors.rowNow else colors.rowBg
            )

            // 圆点用课程配色着色，与 App 内网格一致
            row.setInt(
                R.id.row_dot, "setColorFilter",
                TimetableRenderer.colorFor(item.name, Prefs.colorSeed)
            )

            row.setTextViewText(R.id.row_name, item.name)

            val place = listOf(item.spanLabel, item.room)
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            row.setTextViewText(R.id.row_place, place)
            row.setViewVisibility(R.id.row_place, if (place.isEmpty()) View.GONE else View.VISIBLE)

            // 右侧**固定两行**：时间在上、提示在下。
            // 以前把 [正在上]+[提示]+时间 拼进一个 TextView，行高会在 1~3 行之间跳，
            // 同一列表里行高不一致 → 列表底部必然切出半行（"第三节课露半个"）。
            row.setTextViewText(R.id.row_time, item.timeRange)
            val hintText = buildList {
                if (item.ongoing) add("正在上")
                if (item.hint.isNotEmpty()) add(item.hint)
            }.joinToString(" · ")
            row.setTextViewText(R.id.row_hint, hintText)
            row.setViewVisibility(R.id.row_hint, if (hintText.isEmpty()) View.INVISIBLE else View.VISIBLE)

            row.setTextColor(R.id.row_name, colors.text)
            row.setTextColor(R.id.row_place, colors.sub)
            row.setTextColor(R.id.row_time, colors.accent)
            row.setTextColor(R.id.row_hint, colors.accent)

            // 点击某一节课：模板 PendingIntent 会补齐组件，打开 App
            row.setOnClickFillInIntent(
                R.id.row_root,
                Intent().putExtra(TodayWidgetProvider.EXTRA_COURSE, item.name)
            )
            return row
        }
    }
}
