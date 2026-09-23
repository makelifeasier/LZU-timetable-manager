package app.timetable.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import app.timetable.data.Prefs

/**
 * 自定义背景（纯色 / 相册图片）。
 *
 * 图片会先按屏幕宽度缩放再纵向平铺 —— 课表画布又高又窄，
 * 直接 cover 裁切会只露出一条竖缝。上面再叠一层蒙版保证文字可读。
 */
object Backgrounds {

    private var cachedUri: String? = null
    private var cached: Bitmap? = null

    /** 换过图片/清空后要调用，丢掉解码缓存 */
    fun invalidate() {
        cachedUri = null
        cached = null
    }

    /** 需要自己画底才返回 true；否则调用方走默认底色 */
    fun drawInto(canvas: Canvas, width: Float, height: Float, dark: Boolean, context: Context): Boolean {
        when (Prefs.bgType) {
            1 -> {
                canvas.drawColor(Prefs.bgColor)
                return true
            }

            2 -> {
                val bmp = load(context) ?: return false
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                val scale = width / bmp.width.toFloat()
                val dh = bmp.height * scale
                if (dh <= 0f) return false
                var y = 0f
                while (y < height) {
                    canvas.drawBitmap(bmp, null, RectF(0f, y, width, y + dh), paint)
                    y += dh
                }
                canvas.drawColor(scrimColor(dark))
                return true
            }
        }
        return false
    }

    /** Activity 根布局用的背景 */
    fun buildDrawable(context: Context, dark: Boolean): Drawable? {
        return when (Prefs.bgType) {
            1 -> ColorDrawable(Prefs.bgColor)
            2 -> {
                val bmp = load(context) ?: return null
                val screenW = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
                val scaledH = (bmp.height.toFloat() * screenW / bmp.width).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, screenW, scaledH, true)
                val tile = BitmapDrawable(context.resources, scaled).apply {
                    tileModeX = Shader.TileMode.REPEAT
                    tileModeY = Shader.TileMode.REPEAT
                    isFilterBitmap = true
                }
                LayerDrawable(arrayOf(tile, ColorDrawable(scrimColor(dark))))
            }

            else -> null
        }
    }

    private fun scrimColor(dark: Boolean): Int {
        val alpha = (Prefs.bgScrim.coerceIn(0, 90) * 255) / 100
        return if (dark) Color.argb(alpha, 15, 17, 21) else Color.argb(alpha, 255, 255, 255)
    }

    /** 解码相册图片，按需降采样，最多缓存一张 */
    private fun load(context: Context): Bitmap? {
        val uriText = Prefs.bgUri
        if (uriText.isBlank()) return null
        cached?.let { if (uriText == cachedUri && !it.isRecycled) return it }

        val uri = Uri.parse(uriText)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxDim <= 0) return null

        var sample = 1
        while (maxDim / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }.getOrNull()

        cachedUri = uriText
        cached = bmp
        return bmp
    }
}
