package app.timetable.widget

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.timetable.R
import app.timetable.data.Prefs
import app.timetable.greet.QuoteOfDay
import app.timetable.ui.TimetableRenderer
import java.time.LocalDate

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

/**
 * 列表里第一项（第 0 项）是不是「每日一句」的**纯逻辑**（可单测）。
 *
 * 为什么句子要放进列表里（用户看过真机之后的要求："把每日一句显示在可上下滑动的地方，
 * 并且把原来的这句话（移到）课表上面"）：
 *  - 它以前占着列表下方一块固定的 22dp（[WidgetData.EXTRA_MIN_DP]），和图片抢同一个预算；
 *  - 挪进列表之后，它**跟着列表一起滚**，想多看一眼往下滑即可，不再吃固定位置；
 *  - 图片框因此独占列表下方（比之前高 22dp），两块内容也不再有"谁让谁"的取舍（见 [ExtrasPlanner]）。
 *
 * 三个纯函数刻意分开写：索引映射错了的表现是"滑一下发现某节课不见了"，
 * 这种错在真机上极难定位，而在这里是一条断言就能钉死的东西。
 */
internal object WidgetListPlan {

    /**
     * 这一轮列表里要不要把每日一句排在第一项。
     *
     * 两个条件：用户开着这个开关（[Prefs.quoteEnabled]），且没有在设置里选「强制隐藏」
     * （[Prefs.widgetExtrasOverride] = -1 的意思就是"这些附加内容不要"；句子虽然搬进了列表，
     * 但那个开关的语义没变，所以照样听它）。
     */
    fun quoteRow(quoteEnabled: Boolean, override: Int): Boolean = quoteEnabled && override >= 0

    /** 列表总项数 = 课程行数 + （第一项是句子时 +1） */
    fun itemCount(courseRows: Int, quoteRow: Boolean): Int =
        courseRows.coerceAtLeast(0) + if (quoteRow) 1 else 0

    /**
     * 第 [position] 项对应**第几个课程行**（课程行下标从 0 开始）；
     * 这一项是句子、或者越界时返回 **-1**。
     */
    fun courseIndex(position: Int, quoteRow: Boolean): Int {
        val index = position - if (quoteRow) 1 else 0
        return if (index < 0) -1 else index
    }
}

/** 列表项工厂；两种显示模式共用同一套行渲染 */
class AgendaFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<WidgetData.Row> = emptyList()
    private var colors = WidgetColors.of(context)
    private var quoteRow = false
    private var quoteText = ""

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
        quoteRow = WidgetListPlan.quoteRow(Prefs.quoteEnabled, Prefs.widgetExtrasOverride)
        quoteText = if (quoteRow) QuoteOfDay.forDate(LocalDate.now()) else ""
    }

    override fun getCount(): Int = WidgetListPlan.itemCount(rows.size, quoteRow)

    override fun getViewAt(position: Int): RemoteViews {
        if (quoteRow && WidgetListPlan.courseIndex(position, quoteRow) < 0) {
            return buildQuoteRow(context, quoteText, colors)
        }
        val index = WidgetListPlan.courseIndex(position, quoteRow)
        val item = rows.getOrNull(index)
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

        /**
         * 「每日一句」那一行。
         *
         * **刻意复用课程行的布局**（`widget_today_row.xml`），只把它用不到的部件藏起来：
         *  - 行高必须与课程行**完全一致**（外壳 38dp）。列表高度取的是 38dp 的整数倍，
         *    只要有一行不是这个高度，底部就会切出半行（真机反馈的"第三节课露半个"）；
         *    自检里那条 `rowHeights=.. uniform=true` 也是靠这个成立的；
         *  - 于是它占掉列表的**第一格**：滑到最上面就是"一句话 + 课程"，与用户要的顺序一致。
         *    代价：开着句子时，同一屏里少看到一节课（往下滑一下就有）—— 这是它的取舍，别在别处再补一行。
         *
         * 样式上把它做成"弱化的一行"：藏掉圆点与右侧时间栏，正文用副文本色、11sp，
         * 一眼能看出这不是一节课。颜色/字号都用 RemoteViews 的标准方法改，不需要新布局文件。
         */
        internal fun buildQuoteRow(
            context: Context,
            quote: String,
            colors: WidgetColors
        ): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_today_row)
            row.setInt(R.id.row_card, "setBackgroundResource", colors.rowBg)
            // 用不到的部件全藏掉：圆点、地点、右侧时间与提示
            row.setViewVisibility(R.id.row_dot, View.GONE)
            row.setViewVisibility(R.id.row_place, View.GONE)
            row.setViewVisibility(R.id.row_time, View.GONE)
            row.setViewVisibility(R.id.row_hint, View.GONE)

            row.setTextViewText(R.id.row_name, quote)
            row.setTextColor(R.id.row_name, colors.sub)
            row.setTextViewTextSize(R.id.row_name, TypedValue.COMPLEX_UNIT_SP, 11f)

            // 点这一行也打开 App（列表模板 PendingIntent 需要一个 fill-in intent 才会触发）
            row.setOnClickFillInIntent(R.id.row_root, Intent())
            return row
        }
    }
}
