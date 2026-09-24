package app.timetable.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
 *  3. 复制进私有目录后，卸载 App 图片一起清掉，不会在用户相册里留垃圾。
 *
 * 压缩目标：最长边 ≤ [MAX_EDGE]，JPEG 质量 88 —— 小组件里最大也就百来 dp 高，够用且省内存。
 */
internal object WidgetPhotos {

    /** 私有目录：filesDir/photos */
    private fun dir(context: Context): File =
        File(context.filesDir, "photos").apply { if (!exists()) mkdirs() }

    /**
     * 把选中的图片导入私有目录。
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
        val existing = Prefs.photoUris.split('\n').map { it.trim() }
            .filter { it.isNotEmpty() && File(it).exists() }

        // 先全部落盘：一次挑的图多于上限时，也得先存下来才知道该挤掉谁
        val added = ArrayList<String>()
        for (uri in uris) {
            val saved = saveOne(context, uri) ?: continue
            added.add(saved.absolutePath)
        }

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

        // 只报"这次真的留在列表里的新增张数"：一次挑超过 5 张时，被 takeLast 挤出去的
        // 也是新图，不能算进新增，否则 Toast 又会虚报。
        return added.filter { it in keep }
    }

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
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
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

    /** 最长边上限（px） */
    private const val MAX_EDGE = 1600
}
