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

    fun export(context: Context, week: Int): Uri? {
        val result = TimetableRepository.result()
        if (result.sessions.isEmpty()) return null

        val density = EXPORT_WIDTH / BASE_DP          // 1440 / 360 = 4.0
        val weekMonday = Prefs.week1MondayDate()?.plusDays(((week - 1) * 7).toLong())
        val titleH = 96f * density
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

        // 标题区：左对齐、带一根强调色竖条
        val accent = context.getColor(R.color.accent)
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        canvas.drawRoundRect(60f, 56f, 60f + 10f * density, 56f + 92f * density, 5f * density, 5f * density, barPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF101828.toInt()
            textSize = 26f * density
            isFakeBoldText = true
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF667085.toInt()
            textSize = 14f * density
        }

        val left = 92f
        val head = buildString {
            append(result.term.display.ifBlank { "学生课表" })
            if (result.term.className.isNotBlank()) append("  ").append(result.term.className)
        }
        canvas.drawText(head, left, 84f, titlePaint)

        val range = if (weekMonday != null) {
            val sun = weekMonday.plusDays(6)
            "第 $week 周  ${weekMonday.monthValue}/${weekMonday.dayOfMonth} – ${sun.monthValue}/${sun.dayOfMonth}"
        } else {
            "第 $week 周"
        }
        canvas.drawText("$range  ·  共 ${result.sessions.size} 段课", left, 114f, subPaint)

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
            weekendTint = Prefs.weekendTint
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
