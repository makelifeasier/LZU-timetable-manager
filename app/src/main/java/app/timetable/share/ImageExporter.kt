package app.timetable.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.timetable.R
import app.timetable.data.Prefs
import app.timetable.data.TimetableRepository
import app.timetable.ui.Backgrounds
import app.timetable.ui.TimetableRenderer

/** 把整周课表画成 PNG 存进相册并分享（Android 10+ 免存储权限） */
object ImageExporter {

    // 导出图比屏幕宽：屏幕 360dp 里 7 列每列只有 ~45dp，文字必然挤；
    // 导出到 1440px（360dp @4x）后每列约 62dp，课名能完整显示。
    private const val EXPORT_WIDTH = 1440
    private const val BASE_DP = 360f

    // ------------------------------------------------------------- 页眉排版
    //
    // ★ 单位约定（这一整块**只有一种单位：px**）★
    // 下面所有 `HEAD_*_DP` 常量都在用之前乘 `density`（本图固定 1440px 宽 = 360dp × 4.0），
    // 算出来的 left / baseline / 高度一律是 px，直接喂 Canvas。
    //
    // 为什么要把这条写死在注释里：这里原来是**两套坐标混用** —— 字号和页眉区高度按 density
    // 缩放（26dp→104px、96dp→384px），而 `left = 92f` 和两个基线 `y = 84f / 114f` 用的是
    // 原始 px。于是 104px 高的字被放在 84px 的基线上（字身上沿跑到图外，第一行被上边缘裁掉）、
    // 两行基线只差 30px 互相压住，而 `left = 92f` 又落在竖条自己的范围里（竖条 x 是 60..100px），
    // 标题的前几个字直接压在竖条上。
    // 现在整个页眉由 [headerLayout] 一处算出，字号/基线/竖条/页眉高度同源，
    // 不可能再出现"一半缩放、一半不缩放"。
    private const val HEAD_PAD_LEFT_DP = 22f      // 文字（与竖条）距图左边缘
    private const val HEAD_BAR_WIDTH_DP = 4f      // 左侧强调竖条宽度
    private const val HEAD_GAP_DP = 12f           // 竖条右沿到文字的间距
    private const val HEAD_PAD_TOP_DP = 16f       // 第一行字**上沿**距图顶
    private const val HEAD_PAD_BOTTOM_DP = 14f    // 第二行字**下沿**到课表网格
    private const val HEAD_LINE_GAP_DP = 8f       // 两行字之间的最小空隙
    private const val HEAD_TITLE_SIZE_DP = 26f
    private const val HEAD_SUB_SIZE_DP = 14f

    /** 页眉排版结果。**所有字段都是 px**（见上面的单位约定） */
    internal class HeaderLayout(
        /** 页眉区高度，也就是课表网格的起始 y */
        val height: Float,
        val textLeft: Float,
        val barLeft: Float,
        val barTop: Float,
        val barRight: Float,
        val barBottom: Float,
        val titleBaseline: Float,
        val subBaseline: Float
    )

    /**
     * 纯函数：算页眉排版（参数用**真实字体度量**，见下）。
     *
     * 基线不是"字号 × 某个系数"估出来的，而是用 Paint.fontMetrics 反推：
     * 字形上沿 = 基线 + ascent（ascent 是负值），所以 `基线 = 上沿 − ascent`
     * 能让第一行的字身**恰好**从上沿那条线开始 —— 系统换成任何字体，第一行都不会被裁。
     * 第二行同理：先取第一行的下沿（基线 + descent），再留一段空隙，才是第二行的上沿。
     *
     * 于是三条不变量成立（单测要钉的就是这三条）：
     *  1. `titleBaseline + titleAscent >= 0`                    第一行完整可见
     *  2. `subBaseline + subAscent > titleBaseline + titleDescent` 两行不重叠
     *  3. `0 <= barTop < barBottom <= height`                   竖条只覆盖标题区
     */
    internal fun headerLayout(
        density: Float,
        titleAscent: Float,
        titleDescent: Float,
        subAscent: Float,
        subDescent: Float
    ): HeaderLayout {
        val padLeft = HEAD_PAD_LEFT_DP * density
        val barWidth = HEAD_BAR_WIDTH_DP * density
        val gap = HEAD_GAP_DP * density
        val padTop = HEAD_PAD_TOP_DP * density
        val padBottom = HEAD_PAD_BOTTOM_DP * density

        val titleBaseline = padTop - titleAscent
        val titleBottom = titleBaseline + titleDescent
        val subBaseline = titleBottom + HEAD_LINE_GAP_DP * density - subAscent
        val subBottom = subBaseline + subDescent

        return HeaderLayout(
            height = subBottom + padBottom,
            textLeft = padLeft + barWidth + gap,
            barLeft = padLeft,
            barTop = padTop,
            barRight = padLeft + barWidth,
            barBottom = subBottom,
            titleBaseline = titleBaseline,
            subBaseline = subBaseline
        )
    }

    fun export(context: Context, week: Int): Uri? {
        val result = TimetableRepository.result()
        if (result.sessions.isEmpty()) return null

        val density = EXPORT_WIDTH / BASE_DP          // 1440 / 360 = 4.0
        val weekMonday = Prefs.week1MondayDate()?.plusDays(((week - 1) * 7).toLong())

        // 字体先建好：页眉排版要用**真实字体度量**放基线（见 headerLayout）。
        // textSize 与 headerLayout 内部读的是同两个常量，两边不会各说各话。
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF101828.toInt()
            textSize = HEAD_TITLE_SIZE_DP * density
            isFakeBoldText = true
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF667085.toInt()
            textSize = HEAD_SUB_SIZE_DP * density
        }
        val titleMetrics = titlePaint.fontMetrics
        val subMetrics = subPaint.fontMetrics
        val head = headerLayout(
            density,
            titleMetrics.ascent, titleMetrics.descent,
            subMetrics.ascent, subMetrics.descent
        )

        // 页眉高度就是画出来那两块字的真实下沿 + 一点留白：**先算页眉，再定图高**。
        // 以前这里是手写的 `96f * density`，和另外两个手写基线各算各的，改一个忘一个就会出现裁切。
        val titleH = head.height
        val bottomPad = 20f * density
        val gridH = TimetableRenderer.contentHeight(EXPORT_WIDTH.toFloat(), density, result)
        val height = (titleH + gridH + bottomPad).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(EXPORT_WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        // 导出的图是独立文件，自定义背景要自己铺一遍（屏幕上那层在 Activity 根布局）
        val customBg = Backgrounds.drawInto(
            canvas, EXPORT_WIDTH.toFloat(), height.toFloat(), dark = false, context = context
        )

        // 标题区：左对齐、带一根强调色竖条。
        // 竖条的上下端 = 两行字的身高（titleBaseline+ascent … subBaseline+descent），
        // 所以它**只覆盖标题区**，不会越界压到下面的表头/网格；圆角取条宽的一半（胶囊形）。
        val accent = context.getColor(R.color.accent)
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        val barRadius = (head.barRight - head.barLeft) / 2f
        canvas.drawRoundRect(
            head.barLeft, head.barTop, head.barRight, head.barBottom,
            barRadius, barRadius, barPaint
        )

        val headText = buildString {
            append(result.term.display.ifBlank { "学生课表" })
            if (result.term.className.isNotBlank()) append("  ").append(result.term.className)
        }
        canvas.drawText(headText, head.textLeft, head.titleBaseline, titlePaint)

        val range = if (weekMonday != null) {
            val sun = weekMonday.plusDays(6)
            "第 $week 周  ${weekMonday.monthValue}/${weekMonday.dayOfMonth} – ${sun.monthValue}/${sun.dayOfMonth}"
        } else {
            "第 $week 周"
        }
        canvas.drawText(
            "$range  ·  共 ${result.sessions.size} 段课",
            head.textLeft, head.subBaseline, subPaint
        )

        canvas.save()
        canvas.translate(0f, titleH)
        TimetableRenderer.draw(
            canvas = canvas,
            width = EXPORT_WIDTH.toFloat(),
            density = density,
            result = result,
            week = week,
            dark = false,
            accent = accent,
            todayDay = 0,
            nowTime = null,
            weekMonday = weekMonday,
            seed = Prefs.colorSeed,
            style = Prefs.style,
            drawBackground = !customBg,
            weekendTint = Prefs.weekendTint,
            // 导出的图要和 App 里看到的完全一样，所以带上当日调课的结果
            grid = TimetableRepository.weekGrid(context, week)
        )
        canvas.restore()

        return try {
            saveToGallery(context, bitmap, week)
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveToGallery(context: Context, bitmap: Bitmap, week: Int): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "兰大课表-第${week}周-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/兰大课表"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    fun share(context: Context, uri: Uri, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "分享课表")) }
    }
}
