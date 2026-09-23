package app.timetable.donate

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * 捐赠收款码。
 *
 * **为什么收款码不进源码仓库**：收款码是作者的真实身份信息（微信实名），
 * 一旦提交进公开仓库，等于亲手把"这个项目是谁做的"写在了门口 ——
 * 既暴露隐私，也和"让项目中立一点"的目标正好相反。
 *
 * 所以它只放在 `app/src/main/assets/donate_qr.png`，而该路径写在 `.gitignore` 里：
 * - 你本地打包：文件在，APK 里带着收款码，同学点设置里的按钮就能扫；
 * - 别人 clone 源码构建：没有这个文件，`hasQr` 为 false，设置里**整个入口都不显示**
 *   （不留一个点不动的死按钮）。
 *
 * 想改成自己的码：把图片存成 `app/src/main/assets/donate_qr.png` 再重新打包即可。
 */
object Donations {

    const val ASSET = "donate_qr.png"

    /** 相册里的子目录名（存收款码用） */
    private const val ALBUM = "Timetable"

    /** 是否内置了收款码 */
    fun hasQr(context: Context): Boolean = runCatching {
        context.assets.list("")?.contains(ASSET) == true
    }.getOrDefault(false)

    /**
     * 读出收款码。
     * 先读尺寸再按需降采样 —— 用户很可能直接存了一张手机拍的图，
     * 原图几千像素，按原尺寸解码既浪费内存也没意义。
     */
    fun loadQr(context: Context): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(ASSET).use { BitmapFactory.decodeStream(it, null, bounds) }

        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= MAX_QR_PX) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.assets.open(ASSET).use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    /** 存进相册，方便转发给别人（Android 10+ 存自己的图片不需要权限） */
    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "donate-qr-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** 解码上限：240dp 的显示尺寸下，1000px 已经绰绰有余 */
    private const val MAX_QR_PX = 1000
}
