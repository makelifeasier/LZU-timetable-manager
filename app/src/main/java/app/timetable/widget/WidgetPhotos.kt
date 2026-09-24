package app.timetable.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import app.timetable.data.Prefs
import java.io.File
import java.io.FileOutputStream

/**
 * 小组件图片框的图片管理。
 *
 * 设计取舍：**选图时就把图压缩并复制进 App 私有目录**，之后小组件只读本地文件。
 * 为什么不用 `ACTION_OPEN_DOCUMENT` 的持久化 URI 权限直接显示相册图：
 *  1. 持久化授权在某些机型/相册上会被回收，重启后小组件变空白（最难查的一类 bug）；
 *  2. 相册原图动辄 4000×3000，交给启动器解码既慢又吃内存，桌面滑动会卡；
 *  3. 复制进私有目录后，卸载 App 图片一起清掉，不会在相册里留垃圾。
 *
 * ## 这一轮的变化：导入时先让用户**自己裁**
 *
 * 用户原话：「导入图片出问题……我不是要你有显示倍率，而是导入图片的时候可以自己裁剪」。
 * 所以私有目录里存的那张图，**就是用户在裁剪界面里选中的那一块**（[saveCropped]）：
 *
 * - 源图先按 2 的幂降采样到长边 ≤ [MAX_EDGE]（与老路径同一套，内存与体积都可控）；
 * - 再按裁剪界面给出的**归一化矩形**（[PhotoCrop.sourceFraction]）切下来；
 * - 最后缩到 [PhotoCrop.encodeSize] 算出的目标尺寸（按裁剪框比例）存成 JPEG。
 *
 * 归一化矩形的好处是"预览用什么分辨率"与"存盘用什么分辨率"解耦：用户在图库里可能被缩到
 * 1000px 预宽的预览上取景，而存盘时解码的是 1600px 的图 —— 两者用同一个比例，
 * 存下来的那一块和预览里看到的**逐像素对应**（这正是裁剪界面存在的意义）。
 *
 * [import] 这条"不裁剪"的老路径**保留**：它是还没有接裁剪界面的调用点（与自检）的兜底，
 * 而且与 [saveCropped] 共用同一套"入库（[adopt]）"逻辑，上限与替换语义完全一致。
 */
internal object WidgetPhotos {

    private const val TAG = "WidgetPhoto"

    /** JPEG 质量：88 是"看不出压缩痕迹"与"体积可控"的平衡点（与老版本一致） */
    private const val QUALITY = 88

    /** 私有目录：filesDir/photos */
    private fun dir(context: Context): File =
        File(context.filesDir, "photos").apply { if (!exists()) mkdirs() }

    /**
     * 最长边上限（px）。导入与裁剪存盘都用它 —— 裁剪不该让文件比"没裁剪时"更大。
     */
    const val MAX_EDGE = 1600

    /**
     * 把选中的图片**原样**（不裁剪）导入私有目录。
     *
     * 返回**本次新导入且被保留**的路径（调用方拿 `size` 当"本次新增几张"显示）；
     * `Prefs.photoUris` 里是合并后的完整列表。
     *
     * 取舍（这里以前是坏的）：**满 5 张之后，新选的优先留下**，挤掉最旧的。
     * 原来的循环在 `existing.size >= MAX_PHOTOS` 时直接 break，于是"已经 5 张时再挑 1~3 张"
     * 一张都进不来，作者本意写的 `takeLast(5)` 永远没机会生效：组件还是老 5 张、
     * Toast 却说「已导入 5 张图片」，反复重试都无效（只能先「清除图片」）。
     * 同时返回值以前是**全部**路径，调用方用 `size` 显示，就把"库存 5 张"说成了"本次导入 5 张"。
     *
     * 必须在**后台线程**调用（解码大图会阻塞几百毫秒）。
     */
    fun import(context: Context, uris: List<Uri>): List<String> {
        val added = ArrayList<String>()
        for (uri in uris) {
            val saved = saveOne(context, uri) ?: continue
            added.add(saved.absolutePath)
        }
        return adopt(context, added)
    }

    /**
     * 把**已经裁好的一批文件**并入图片列表（上限、替换、孤儿文件清理都在这里，只有这一处）。
     *
     * [added] 是本次真正落盘的新文件路径（[saveCropped] / [saveOne] 的产物）。
     *
     * @return 本次真正留在列表里的新增路径（挑的图多于上限时，被挤掉的新图不算"导入成功"，
     *         否则 Toast 又会虚报 —— 见 [keepAfterImport]）
     */
    fun adopt(context: Context, added: List<String>): List<String> {
        val existing = Prefs.photoUris.split('\n').map { it.trim() }
            .filter { it.isNotEmpty() && File(it).exists() }

        val keep = keepAfterImport(existing, added, WidgetData.MAX_PHOTOS)
        Prefs.photoUris = keep.joinToString("\n")

        // 被挤掉的图要**真的删掉**：既包括被新图挤走的**旧图**，也包括"一次挑太多、
        // 连自己都没留下"的**新图** —— 这两种都是 filesDir/photos 里的孤儿文件。
        // 不指望调用方的 prune()：它删的是"目录里存在、记录里没有"的文件，
        // 而这些图在写回 photoUris 的同一刻就从记录里消失了，prune() 得等下一次调用才跑；
        // 在那之前用户看到的是"清除图片"清不干净、空间白占。
        for (stale in staleAfterImport(existing + added, keep)) {
            runCatching { File(stale).delete() }
        }

        return added.filter { it in keep }
    }

    /**
     * 把「裁剪框里的那一块」解码、裁下来、缩到目标尺寸，存成一个新的 JPEG。
     *
     * @param fraction 裁剪框在源图上的**归一化**矩形 `[fx, fy, fw, fh]`（[PhotoCrop.sourceFraction]）
     * @param outW/outH 存盘尺寸（[PhotoCrop.encodeSize]：按裁剪框比例算出来）
     *
     * @return 新文件；null = 这张图读不出来（URI 权限被回收、格式不支持、文件损坏），
     *         调用方应当跳过它、继续下一张，而不是让整次导入失败。
     *
     * 必须在**后台线程**调用（这里会解码一张长边 1600px 的图）。
     */
    fun saveCropped(
        context: Context,
        uri: Uri,
        fraction: FloatArray,
        outW: Int,
        outH: Int
    ): File? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_EDGE)
            // 源图带 alpha（PNG/截图）时解码器会给 ARGB_8888；这里只是**减少内存峰值**，
            // 存盘用 JPEG，本来也没有透明通道可存
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val src = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return@runCatching null

        val rect = PhotoCrop.pixelRect(fraction, src.width, src.height)
        val cropped = if (rect[2] >= src.width && rect[3] >= src.height) {
            src
        } else {
            Bitmap.createBitmap(src, rect[0], rect[1], rect[2], rect[3])
        }
        val w = outW.coerceAtLeast(1)
        val h = outH.coerceAtLeast(1)
        val out = if (cropped.width == w && cropped.height == h) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, w, h, true)
        }

        // 文件名用时间戳：避免与已有图片重名，也方便按新旧排序
        val file = File(dir(context), "p_${System.currentTimeMillis()}_${out.hashCode() and 0xFFFF}.jpg")
        FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        Log.i(
            TAG,
            "裁剪存盘 ${file.name} 源=${src.width}x${src.height} 框=${rect[2]}x${rect[3]}@(${rect[0]},${rect[1]}) " +
                "→ ${out.width}x${out.height} / ${file.length()}B config=${out.config?.name ?: "?"}"
        )

        if (out !== cropped && out !== src) out.recycle()
        if (cropped !== src) cropped.recycle()
        src.recycle()
        file
    }.onFailure {
        Log.w(TAG, "裁剪存盘失败 uri=$uri -> ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /**
     * 纯函数（可单测）：把「已有的」和「本次新导入的」合并成最终保留的列表。
     *
     * 新选的接在末尾 + `takeLast(max)` ⇒ **新选的优先留下**，最旧的先被挤掉。
     */
    fun keepAfterImport(existing: List<String>, added: List<String>, max: Int): List<String> =
        (existing + added).takeLast(max.coerceAtLeast(1))

    /**
     * 纯函数（可单测）：[candidates] 里不再被 [keep] 引用的路径 —— 全都是调用方要删的文件。
     *
     * 传入的应当是「已有的 + 本次新导入的」合并列表：**一次挑太多时，连新图自己也可能是孤儿**。
     */
    fun staleAfterImport(candidates: List<String>, keep: List<String>): List<String> {
        val kept = keep.toHashSet()
        return candidates.filter { it !in kept }
    }

    /** 清空：删文件 + 清记录 */
    fun clear(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
        Prefs.photoUris = ""
    }

    /** 删掉不再引用的文件（换图后留下的孤儿） */
    fun prune(context: Context) {
        val keep = Prefs.photoUris.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        runCatching {
            dir(context).listFiles()?.forEach { f -> if (f.absolutePath !in keep) f.delete() }
        }
    }

    /** 不裁剪的存盘（[import] 用）：按 [MAX_EDGE] 降采样后原样复制进私有目录 */
    private fun saveOne(context: Context, uri: Uri): File? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return@runCatching null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_EDGE)
        }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return@runCatching null

        // 文件名用时间戳：避免与已有图片重名，也方便按新旧排序
        val out = File(dir(context), "p_${System.currentTimeMillis()}_${bmp.hashCode() and 0xFFFF}.jpg")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        bmp.recycle()
        out
    }.getOrNull()

    /** 计算 inSampleSize：保证最长边不超过 maxEdge（2 的幂，解码器要求） */
    internal fun sampleSizeFor(w: Int, h: Int, maxEdge: Int): Int {
        var sample = 1
        var longEdge = maxOf(w, h)
        while (longEdge / 2 >= maxEdge) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }
}
