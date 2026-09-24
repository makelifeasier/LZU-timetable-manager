package app.timetable.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.timetable.widget.CropFrame
import app.timetable.widget.CropQueue
import app.timetable.widget.PhotoBitmap
import app.timetable.widget.PhotoCrop
import app.timetable.widget.WidgetData
import app.timetable.widget.WidgetPhotos

/**
 * **导入图片时的裁剪界面**（用户原话："
 * 「导入图片出问题……我不是要你有显示倍率，而是导入图片的时候可以自己裁剪，
 *   而且在空间足够的时候图片完整显示，不够的时候显示自己裁剪的部分，注意组件是几乘几。」
 *
 * ## 这个框是从哪来的
 *
 * 框的比例 = **这个小组件当前尺寸下图片区的形状**（内容宽 : 图片区可用高），
 * 由 [WidgetData.photoFrame] 从 `AppWidgetManager.getAppWidgetOptions` 报上来的
 * 真实 dp 尺寸算出来（"几乘几"这件事只有启动器知道，不能写死）。取不到尺寸时用 2:1 兜底，
 * 理由写在 [PhotoCrop] 的注释里，并且会在界面文案里**说明这是兜底比例**。
 *
 * 框是**固定不动**的：用户移动/缩放的是照片。这样"框里看到的那一块"就是保存在文件里的那一块
 * —— 预览与结果逐像素对应（[PhotoCrop.sourceFraction] 用的是归一化坐标，
 * 与预览分辨率、与存盘解码分辨率都无关）。
 *
 * ## 为什么一次只裁一张
 *
 * 用户明确同意"允许先只支持一次一张"，但**取消/确认都要能推进到下一张**，
 * 否则用户取消第一张就卡死了。推进逻辑放在纯逻辑 [CropQueue] 里（可单测），
 * 这里只管弹窗与存盘。
 *
 * ## 交互
 *
 *  · 单指拖动 = 平移照片；
 *  · 双指捏合 = 缩放（[ScaleGestureDetector]，系统自带，不引第三方库）；
 *  · ＋/－ 按钮 = 同样的缩放（模拟器的捏合手势不好用，也必须能操作）；
 *  · 边界：任何时刻照片都必须**盖住**整个框（[PhotoCrop.clampOffset]）——
 *    一旦露白，用户确认后存下来的图里就会有空白像素。
 *
 * 所有几何算术都在 [PhotoCrop]（纯函数、有单测）；这里只负责把 Android 的触摸事件
 * 换算成它的入参，以及把位图画到屏幕上。
 */
internal class PhotoCropDialog private constructor(
    private val host: Activity,
    private val uri: Uri,
    private val frame: CropFrame,
    /** 存盘尺寸（[PhotoCrop.encodeSize]：按裁剪框比例、不小于组件要解码的像素宽） */
    private val outWidth: Int,
    private val outHeight: Int,
    private val at: Int,
    private val total: Int,
    private val onResult: (java.io.File?) -> Unit
) : Dialog(host) {

    private lateinit var cropView: CropView
    private lateinit var hint: TextView
    private lateinit var confirm: Button
    private lateinit var progress: TextView

    /** 这条回调只许走一次：取消/确认/读图失败都可能触发它，走到第二次就会多推进一格 */
    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildContentView())
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setCanceledOnTouchOutside(false)
        // 返回键 / 点外面 = 取消这张（用户原话："取消 = 不导入这张"）
        setOnCancelListener { settle(null) }
        loadPreview()
    }

    private fun buildContentView(): View {
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            // 裁剪界面用深色底：浅色主题下"框外遮罩"几乎看不出来，用户就分不清框里框外了
            setBackgroundColor(PANEL)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(
            TextView(host).apply {
                text = if (total > 1) "裁剪图片（第 ${at + 1}/$total 张）" else "裁剪图片"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 17f
            }
        )

        hint = TextView(host).apply {
            setTextColor(0xFFB9C0CC.toInt())
            textSize = 12f
            setPadding(0, dp(6), 0, dp(10))
            // 框的信息不需要等图解码就能显示（比例是算出来的，不是看出来的）
            text = frameHint()
        }
        root.addView(hint)

        cropView = CropView(host, frame)
        root.addView(
            cropView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        progress = TextView(host).apply {
            text = "正在读取图片…"
            setTextColor(0xFFB9C0CC.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))

        // ---- 缩放：模拟器/手势不灵的时候靠这三个按钮 ----
        root.addView(
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("－") { cropView.zoomBy(1f / PhotoCrop.ZOOM_STEP) })
                addView(button("＋") { cropView.zoomBy(PhotoCrop.ZOOM_STEP) })
                addView(button("重置") { cropView.reset() })
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        )

        // ---- 取消 / 确定 ----
        root.addView(
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("取消") { settle(null) })
                confirm = button("确定") { save() }
                confirm.isEnabled = false
                addView(confirm)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        )
        return root
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(host).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    // ---------------------------------------------------------------- 预览

    /**
     * 读一张**缩过的**预览（长边 ≤ [PREVIEW_MAX_EDGE]）。
     *
     * 为什么预览要缩：相册原图动辄 4000px 宽（几十 MB），既慢又可能 OOM；而裁剪框是
     * **归一化**的（[PhotoCrop.sourceFraction]），预览用什么分辨率都不影响最终结果 ——
     * 存盘时会按同一比例在 1600px 的解码图上重新映射一次。
     */
    private fun loadPreview() {
        Thread {
            val bitmap = decodePreview()
            host.runOnUiThread {
                if (settled) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                if (bitmap == null) {
                    Log.w(TAG, "读不出这张图片：$uri")
                    toast("读不出这张图片，已跳过")
                    settle(null)
                    return@runOnUiThread
                }
                cropView.setPhoto(bitmap)
                confirm.isEnabled = true
                progress.visibility = View.GONE
                hint.text = frameHint()
            }
        }.start()
    }

    private fun decodePreview(): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        host.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = WidgetPhotos.sampleSizeFor(bounds.outWidth, bounds.outHeight, PREVIEW_MAX_EDGE)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        host.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    private fun frameHint(): String {
        val shape = if (frame.fallback) {
            "取不到组件尺寸，按 2:1 兜底"
        } else {
            "= 你这个小组件里图片区的形状"
        }
        return "框的形状 $shape（${frame.label}）。框里那块就是组件里会看到的部分：\n" +
            "拖动平移照片，双指捏合或 ± 缩放；松手后照片一定会盖满框（不会留下空白）。\n" +
            "取消 = 不导入这张。"
    }

    // ---------------------------------------------------------------- 存盘

    private fun save() {
        val bitmap = cropView.photo ?: return
        val fraction = PhotoCrop.sourceFraction(
            scale = cropView.scale,
            dx = cropView.dx,
            dy = cropView.dy,
            frameW = cropView.frameW,
            frameH = cropView.frameH,
            photoW = bitmap.width,
            photoH = bitmap.height
        )
        confirm.isEnabled = false
        confirm.text = "保存中…"
        Thread {
            val file = runCatching {
                WidgetPhotos.saveCropped(host, uri, fraction, outWidth, outHeight)
            }.getOrNull()
            host.runOnUiThread {
                if (file == null) toast("保存失败，已跳过这张")
                settle(file)
            }
        }.start()
    }

    /** 收尾：保证只走一次（取消 / 确定 / 读图失败都会到这里），然后把自己关掉 */
    private fun settle(file: java.io.File?) {
        if (settled) return
        settled = true
        cropView.release()
        runCatching { dismiss() }
        runCatching { onResult(file) }
    }

    private fun toast(text: String) {
        runCatching { Toast.makeText(host, text, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(v: Int): Int = (v * host.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 显示一张照片 + 一个**固定不动**的裁剪框。
     *
     * 所有坐标换算都委托给 [PhotoCrop]：这里不自己算边界，免得"预览里看不到白边、
     * 存下来却有白边"这种最难查的不一致。
     */
    private class CropView(context: Context, private val frame: CropFrame) : View(context) {

        /** 预览位图（由对话框在后台线程解码好后塞进来） */
        var photo: Bitmap? = null
            private set

        /** 照片画多大（`view px / bitmap px`） */
        var scale: Float = 1f
            private set

        /** 照片左上角在**框坐标系**里的偏移（≤ 0：照片比框大，往左上才是合法方向） */
        var dx: Float = 0f
            private set
        var dy: Float = 0f
            private set

        /** 裁剪框在 view 里的位置与尺寸 */
        private val frameRect = RectF()
        var frameW: Int = 0
            private set
        var frameH: Int = 0
            private set

        private val detector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val bmp = photo ?: return false
                    applyZoom(detector.scaleFactor, detector.focusX, detector.focusY, bmp)
                    return true
                }
            }
        )

        private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val scrimPaint = Paint().apply { color = SCRIM }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * context.resources.displayMetrics.density
            color = 0xFFFFFFFF.toInt()
        }
        private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB9C0CC.toInt()
            // 12sp：fontScale 是用户设的字体缩放，density 是屏幕密度（scaledDensity 已废弃）
            val metrics = context.resources.displayMetrics
            textSize = 12f * context.resources.configuration.fontScale * metrics.density
            textAlign = Paint.Align.CENTER
        }

        private var lastX = 0f
        private var lastY = 0f

        fun setPhoto(bitmap: Bitmap) {
            photo?.takeIf { it !== bitmap }?.recycle()
            photo = bitmap
            layoutFrame(width, height)
            invalidate()
        }

        /** 提前释放预览位图（对话框消失时必须调，否则一张 1280px 的图会一直占着内存） */
        fun release() {
            photo?.recycle()
            photo = null
        }

        /** 框：居中、比例固定、尽可能大（[PhotoCrop.frameSizeIn] 是纯函数，有单测） */
        private fun layoutFrame(viewW: Int, viewH: Int) {
            if (viewW <= 0 || viewH <= 0) return
            val pad = 4f * resources.displayMetrics.density
            val size = PhotoCrop.frameSizeIn(
                (viewW - pad * 2).toInt(),
                (viewH - pad * 2 - captionPaint.textSize * 2).toInt(),
                frame.ratio
            )
            frameW = size[0]
            frameH = size[1]
            val left = (viewW - frameW) / 2f
            val top = (viewH - frameH) / 2f
            frameRect.set(left, top, left + frameW, top + frameH)
            reset()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            layoutFrame(w, h)
        }

        /** 回到初始状态：刚好盖住框 + 居中 */
        fun reset() {
            val bmp = photo ?: return
            if (frameW <= 0 || frameH <= 0) return
            val t = PhotoCrop.initial(frameW, frameH, bmp.width, bmp.height)
            scale = t[0]
            dx = t[1]
            dy = t[2]
            invalidate()
        }

        /** ± 按钮：以**框中心**为锚点缩放（用户看的是框里的画面，锚点就该是它） */
        fun zoomBy(factor: Float) {
            val bmp = photo ?: return
            applyZoom(factor, frameW / 2f, frameH / 2f, bmp)
        }

        /** 双指捏合：以两指中心为锚点 */
        private fun applyZoom(factor: Float, focusX: Float, focusY: Float, bitmap: Bitmap) {
            if (frameW <= 0 || frameH <= 0) return
            val t = PhotoCrop.zoomAround(
                factor = factor,
                scale = scale,
                dx = dx,
                dy = dy,
                focusX = focusX - frameRect.left,
                focusY = focusY - frameRect.top,
                frameW = frameW,
                frameH = frameH,
                photoW = bitmap.width,
                photoH = bitmap.height
            )
            scale = t[0]
            dx = t[1]
            dy = t[2]
            invalidate()
        }

        private fun panBy(moveX: Float, moveY: Float) {
            val bmp = photo ?: return
            if (frameW <= 0 || frameH <= 0) return
            // 每次移动都重新钳制：拖到边上继续拖，照片也只会停在边界上（绝不露出框底）
            val t = PhotoCrop.place(
                scale = scale,
                dx = dx + moveX,
                dy = dy + moveY,
                frameW = frameW,
                frameH = frameH,
                photoW = bmp.width,
                photoH = bmp.height
            )
            scale = t[0]
            dx = t[1]
            dy = t[2]
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            detector.onTouchEvent(event)
            if (detector.isInProgress) {
                lastX = event.x
                lastY = event.y
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    // 多指时 event.x 是"第一根手指"，用它拖动会在捏合结束后跳一下；
                    // 简单起见：指针数 > 1 的时候不拖动，只让 ScaleGestureDetector 处理
                    if (event.pointerCount == 1) {
                        panBy(event.x - lastX, event.y - lastY)
                    }
                    lastX = event.x
                    lastY = event.y
                    return true
                }

                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    // 指针数变了：把拖动基准点重置到第一根还按着的手指，避免下一帧跳一截
                    if (event.pointerCount > 0) {
                        lastX = event.getX(0)
                        lastY = event.getY(0)
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = frameRect
            if (r.isEmpty) return

            // 1) 框外压暗：一眼看出"只有框里算数"
            canvas.drawRect(0f, 0f, width.toFloat(), r.top, scrimPaint)
            canvas.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), scrimPaint)
            canvas.drawRect(0f, r.top, r.left, r.bottom, scrimPaint)
            canvas.drawRect(r.right, r.top, width.toFloat(), r.bottom, scrimPaint)

            // 2) 照片（裁剪到框内：框外的部分是"不会被保存的部分"）
            val bmp = photo
            if (bmp != null && !bmp.isRecycled) {
                canvas.save()
                canvas.clipRect(r)
                canvas.translate(r.left + dx, r.top + dy)
                canvas.scale(scale, scale)
                canvas.drawBitmap(bmp, 0f, 0f, photoPaint)
                canvas.restore()
            }

            // 3) 框线 + 说明"这就是组件里图片区的形状"
            canvas.drawRect(r, borderPaint)
            canvas.drawText(
                "这条框 = 小组件里图片区的形状 ${frame.label}",
                width / 2f,
                minOf(height.toFloat() - captionPaint.textSize * 0.4f, r.bottom + captionPaint.textSize * 1.6f),
                captionPaint
            )
        }
    }

    companion object {

        private const val TAG = "WidgetPhoto"

        /** 预览长边上限：够看清楚，又不至于把相册原图整张解进内存 */
        private const val PREVIEW_MAX_EDGE = 1280

        /** 面板底色（深色：浅色主题下"框外遮罩"才看得出来） */
        private const val PANEL = 0xF214161A.toInt()

        /** 框外遮罩 */
        private const val SCRIM = 0xB3000000.toInt()

        /**
         * 逐张弹出裁剪界面，全部处理完后回调"本次**真正留在列表里**的新文件路径"。
         *
         * 设置页的接法（与老的 `WidgetPhotos.import` 同一套语义）：
         *
         * ```kotlin
         * PhotoCropDialog.start(this, uris) { saved ->
         *     toast("已导入 ${saved.size} 张图片")   // = 本次真的留下的张数（会被 5 张上限挤掉的不算）
         *     TodayWidgetProvider.refreshAll(this)
         *     build()
         * }
         * ```
         *
         * 每张确认时立刻入库（[WidgetPhotos.adopt]），所以"裁到第 3 张时取消"不会丢掉前两张 ——
         * 这也是用户那条"取消 = 不导入这张"的直译。
         */
        fun start(activity: Activity, uris: List<Uri>, onFinished: (List<String>) -> Unit) {
            val queue = CropQueue(uris.size)
            val saved = ArrayList<String>()
            if (!queue.hasCurrent) {
                onFinished(saved)
                return
            }
            val frame = WidgetData.photoFrame(activity)
            val density = activity.resources.displayMetrics.density
            val boxPx = PhotoBitmap.targetPx(frame.widthDp, frame.heightDp, density)
            val out = PhotoCrop.encodeSize(frame, boxPx[0])
            if (frame.fallback) {
                Log.w(TAG, "裁剪帧取不到组件尺寸，按兜底 2:1 处理（部分启动器不上报 options）")
            }
            Log.i(
                TAG,
                "裁剪导入开始：${uris.size} 张 裁剪框=${frame.label} 组件要解码的宽=${boxPx[0]}px " +
                    "存盘尺寸=${out[0]}x${out[1]}"
            )

            fun next() {
                if (!queue.hasCurrent) {
                    // 裁到一半取消、或某张存盘失败留下的孤儿文件在这里清掉
                    runCatching { WidgetPhotos.prune(activity) }
                    Log.i(TAG, "裁剪导入结束：本次留在列表里 ${saved.size} 张 $saved")
                    onFinished(saved)
                    return
                }
                val dialog = PhotoCropDialog(
                    host = activity,
                    uri = uris[queue.index],
                    frame = frame,
                    outWidth = out[0],
                    outHeight = out[1],
                    at = queue.index,
                    total = queue.total
                ) { file ->
                    if (file != null) {
                        val kept = runCatching {
                            WidgetPhotos.adopt(activity, listOf(file.absolutePath))
                        }.getOrDefault(emptyList())
                        saved.addAll(kept)
                    }
                    queue.advance()
                    next()
                }
                dialog.show()
            }
            next()
        }
    }
}
