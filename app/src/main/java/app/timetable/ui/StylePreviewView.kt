package app.timetable.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import app.timetable.R
import app.timetable.data.Prefs

/**
 * 设置页里的**课表风格实时预览**。
 *
 * 做法：直接用现成的 [TimetableRenderer.draw] 把 [SampleTimetable] 画在自己的画布上 ——
 * 不生成 Bitmap、不留缓存，换风格只是改一个 int 再重画一帧，
 * 所以既"所见即所得"，又不会在滚动设置页时增加内存抖动。
 *
 * 两个刻意的差异，让预览更干净：
 *  - `nowTime = null`：不画"当前时刻"红线（预览里两条红线反而看不清风格差异）
 *  - `drawBackground = false`：底色交给外层圆角卡片，避免双层底
 *
 * 缩放：真实课表一行 62dp、表头 48dp，整张样张按原始尺寸要 420dp 高 —— 放在设置页里太大。
 * 这里把**密度按比例缩小**（[SCALE]）交给渲染器，于是行高、字号、圆角、间距全部等比缩小，
 * 相当于"把课表拍成一张小图"，而不需要另写一套预览绘制逻辑（两套逻辑必然会漂移）。
 */
class StylePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 当前预览的风格下标（0..5），对应 [TimetableRenderer.Style.ALL] */
    var style: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 渲染用的样张：固定不变，避免每次绘制都重建对象 */
    private val sample = SampleTimetable.result()

    private val renderer = TimetableRenderer

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        if (w <= 0f || height <= 0) return
        // 内容内缩一点，别贴着卡片边缘（渲染器自己不认识 padding，所以在这里平移画布）
        val density = resources.displayMetrics.density
        val pad = PAD_DP * density
        canvas.save()
        canvas.translate(pad, pad)
        renderer.draw(
            canvas = canvas,
            width = w - pad * 2f,
            // 等比缩小：渲染器内部所有尺寸都乘 density，所以这一项就是"缩放比例"
            density = resources.displayMetrics.density * SCALE,
            result = sample,
            week = SampleTimetable.WEEK,
            dark = Ui.isNight(context),
            accent = context.getColor(R.color.accent),
            todayDay = SampleTimetable.TODAY_DAY,
            nowTime = null,
            weekMonday = null,
            seed = Prefs.colorSeed,
            style = style,
            drawBackground = false,
            weekendTint = Prefs.weekendTint,
            report = null,
            minRows = sample.sections.size      // 样张 4 节就画 4 行，不留一大截空网格
        )
        canvas.restore()
    }

    /** 与 [TimetableRenderer] 的 62dp 行高配套：4 行 + 表头 × 0.78 + 上下留白 ≈ 256dp */
    companion object {
        /** 缩放比例。0.62 太小（课名约 7.8sp，糊），0.78 是"看得清 + 不占地方"的平衡点 */
        internal const val SCALE = 0.78f

        /** 内容与卡片边缘的间距（dp，真实密度，不参与缩放） */
        private const val PAD_DP = 12f

        /** 设置页里给预览控件的高度（dp），由上面两个常量算出来 */
        const val HEIGHT_DP = 256
    }
}
