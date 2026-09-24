package app.timetable.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import app.timetable.R
import app.timetable.data.ParseResult
import app.timetable.data.Prefs
import app.timetable.data.Session
import java.time.LocalDate
import java.time.LocalTime

/**
 * App 内的周课表网格。高度按内容自适应，放进 ScrollView 里滚动。
 *
 * 点击某一格会回调 [onSessionTap] —— 卡片放不下的信息
 * （完整课名、教师、周次、上下课时间）从那里看。
 */
class TimetableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var result: ParseResult = ParseResult()
    private var week: Int = 1
    private var todayDay: Int = 0
    private var nowTime: LocalTime? = null
    private var weekMonday: LocalDate? = null
    private var style: Int = 0
    private var weekendTint: Boolean = true
    /** 已应用当日覆盖的网格（由 TimetableRepository 算好传进来；null = 用原始课表） */
    private var grid: Map<Int, List<Session>>? = null

    /** 点中某个课块时回调 */
    var onSessionTap: ((Session) -> Unit)? = null

    /** 点中星期标题（表头）时回调列号 1..7 —— 用来打开「当日调课」 */
    var onDayHeaderTap: ((Int) -> Unit)? = null

    fun setData(
        result: ParseResult,
        week: Int,
        todayDay: Int,
        nowTime: LocalTime?,
        weekMonday: LocalDate?,
        style: Int,
        weekendTint: Boolean = true,
        grid: Map<Int, List<Session>>? = null
    ) {
        this.result = result
        this.week = week
        this.todayDay = todayDay
        this.nowTime = nowTime
        this.weekMonday = weekMonday
        this.style = style
        this.weekendTint = weekendTint
        this.grid = grid
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val density = resources.displayMetrics.density
        val h = TimetableRenderer.metrics(w.toFloat(), density, result).height().toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0) return
        TimetableRenderer.draw(
            canvas = canvas,
            width = width.toFloat(),
            density = resources.displayMetrics.density,
            result = result,
            week = week,
            dark = Ui.isNight(context),
            accent = context.getColor(R.color.accent),
            todayDay = todayDay,
            nowTime = nowTime,
            weekMonday = weekMonday,
            seed = Prefs.colorSeed,
            style = style,
            // 自定义背景由 Activity 根布局铺（含蒙版），画布这层就不要再铺底色，
            // 否则会盖住图片、蒙版还会叠两次
            drawBackground = Prefs.bgType == 0,
            weekendTint = weekendTint,
            grid = grid
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val metrics = TimetableRenderer.metrics(
                    width.toFloat(), resources.displayMetrics.density, result
                )
                // 先看是不是点在"星期标题"那一条上：那里没有课块，但可以调整当天的课表
                val day = TimetableRenderer.hitDayHeader(metrics, event.x, event.y)
                if (day != null && onDayHeaderTap != null) {
                    performClick()
                    onDayHeaderTap?.invoke(day)
                    return true
                }
                val hit = TimetableRenderer.hitTest(result, week, metrics, event.x, event.y, grid)
                if (hit != null) {
                    performClick()
                    onSessionTap?.invoke(hit)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
