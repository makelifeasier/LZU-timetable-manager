package app.timetable.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.timetable.R
import app.timetable.widget.WidgetPhotos
import java.io.File

/**
 * **管理小组件图片**：一次看到全部照片，点一下选中，多选后一起删；长按删单张。
 *
 * 用户原话：「同时需要自由删除任意图片，可多选删除」。
 *
 * ## 为什么上限提到 50 张就必须要这个界面
 *
 * 老版本只有「清除图片」一条路 —— 那是"全删"。张数少（5 张）时用户还能靠"全删重挑"绕过去，
 * 到了 50 张就完全不可用了：想删掉第 37 张，难道要清空再挑 49 张？所以这个界面的第一要求是
 * **任何一张都能单独删掉**，多选只是顺手（删 20 张时不必点 20 次确认）。
 *
 * ## 下标语义（最容易错的地方）
 *
 * 界面里的第 i 格**就是** `WidgetPhotos.recorded()` 里的第 i 项，两者一一对应：
 *  · 这里**不做"文件不存在就跳过"的过滤** —— 过滤一次，用户点的第 3 格就会删掉第 4 张；
 *  · 文件丢了的格子显示成「已丢失」但**仍然可选可删**（正好让用户把坏记录清掉）。
 * 删除统一走 [WidgetPhotos.removeAt]（先改记录、再删文件），所以不会出现"记录了但文件没了"。
 *
 * ## 缩略图为什么一次全解出来
 *
 * 50 张缩略图按 [THUMB_PX] 解码（RGB_565，约 200×200）合计约 4 MB，一次性解码完再交给界面，
 * 比"边滑边解"简单得多，也不会出现"滑回去又重新解一遍"。解码在**后台线程**，
 * 期间界面显示「正在载入缩略图 n/m」，且用 [generation] 令牌作废过期结果
 * —— 删完一批立刻重建时，上一轮的线程还在跑，不作废的话会把已删照片的缩略图贴到新格子上。
 *
 * ## 位图刻意不 recycle
 *
 * 关窗（或重建）时只把引用丢掉，交给 GC。原因：`recycle()` 之后若还有一次迟到的 draw
 * 就会抛「Canvas: trying to use a recycled bitmap」直接崩，而"迟到的 draw"在这里是常态
 * （关窗动画、滚动惯性）。丢掉引用就能回收，风险为零。
 */
internal class PhotoManagerDialog private constructor(
    private val host: Activity,
    /** 每删一批回调一次：`(本次删掉几张, 还剩几张)` —— 调用方据此刷新桌面组件 */
    private val onDeleted: (Int, Int) -> Unit,
    /** 关闭时回调一次（调用方据此重建设置页） */
    private val onClosed: () -> Unit
) : Dialog(host) {

    private lateinit var header: TextView
    private lateinit var hint: TextView
    private lateinit var grid: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var selectAllButton: Button
    private lateinit var deleteButton: Button

    /** 记录（顺序 = 显示顺序 = 下标语义），每次重建都重新读，不缓存 */
    private var photos: List<String> = emptyList()

    /** 选中的下标。用 LinkedHashSet：日志与"删除所选"的打印顺序是稳定的 */
    private val selected = linkedSetOf<Int>()

    /** 每格的控件（重建时整体替换），用于"只重画被点的那一格" */
    private val cells = ArrayList<View>(0)

    /** 作废过期解码：每次 [rebuild] 自增，后台线程发现自己的令牌过期就直接丢弃结果 */
    private var generation = 0

    /** 关窗标志：关窗之后绝不再碰任何 View */
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildContentView())
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setCanceledOnTouchOutside(true)
        setOnDismissListener {
            closed = true
            runCatching { onClosed() }
        }
        rebuild()
    }

    // ---------------------------------------------------------------- 界面骨架

    private fun buildContentView(): View {
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.surface))
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }

        root.addView(
            TextView(host).apply {
                header = this
                textSize = 17f
                setTextColor(color(R.color.text_primary))
            }
        )

        hint = TextView(host).apply {
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(6), 0, dp(10))
            // **固定两行高**：这行文案在"操作说明"与"已选 N 张：…"之间切换，字数差很多
            // （选中 50 张时会写成好几行）。高度跟着变的话，弹窗会在用户点第一下时整体上下跳
            // —— 实测：点一下格子后整个网格与按钮会挪 20~45px，手感很差，而且点击目标在手指下面移走。
            minLines = 2
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = HINT_IDLE
        }
        root.addView(hint)

        grid = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        scroller = ScrollView(host).apply {
            isFillViewport = false
            addView(
                grid,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(
            scroller,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        )

        root.addView(
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                selectAllButton = textButton("全选") { toggleSelectAll() }
                addView(selectAllButton, LinearLayout.LayoutParams(0, WRAP, 1f))
                deleteButton = textButton("删除所选") { confirmDelete(selected.toList()) }
                addView(deleteButton, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
                addView(textButton("完成") { dismiss() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP).apply { topMargin = dp(10) }
        )
        return root
    }

    /**
     * 重新读记录 + 重建网格。
     *
     * 每次删除后都走这里（而不是"把删掉的格子藏起来"）：张数、序号、全选态、按钮可用性
     * 全都依赖记录本身，从记录重算一遍最不容易出错 —— 尤其是**序号**，
     * 它是用户在"我删了第 37 张"这件事上唯一的参照。
     */
    private fun rebuild() {
        generation++
        photos = runCatching { WidgetPhotos.recorded(host) }.getOrDefault(emptyList())
        selected.clear()
        cells.clear()
        grid.removeAllViews()

        // 高度按"内容需要多少"给，但不超过屏幕的 55%：3 张图时不占满整屏，50 张时能滚
        val rows = (photos.size + COLS - 1) / COLS
        val cell = dp(CELL_DP)
        val wanted = rows * (cell + dp(6)) - dp(6)
        val maxH = (host.resources.displayMetrics.heightPixels * 0.55f).toInt()
        scroller.layoutParams = (scroller.layoutParams as LinearLayout.LayoutParams).apply {
            height = wanted.coerceIn(0, maxH)
        }

        for (row in 0 until rows) {
            val line = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until COLS) {
                val index = row * COLS + col
                if (index >= photos.size) {
                    line.addView(View(host), LinearLayout.LayoutParams(0, cell, 1f).apply { marginEnd = dp(6) })
                    continue
                }
                val view = buildCell(index)
                cells.add(view)
                line.addView(
                    view,
                    LinearLayout.LayoutParams(0, cell, 1f).apply { marginEnd = dp(6) }
                )
            }
            grid.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP).apply { bottomMargin = dp(6) })
        }

        paintHeader()
        loadThumbnails(generation)
    }

    /**
     * 一格的控件：缩略图 + 序号 + 选中标记。
     *
     * 「已丢失」也要有格子：记录里有、文件没了的那种（用户手动清过目录、或存储不足写失败），
     * 正好是用户最需要能删掉的东西。
     */
    private fun buildCell(index: Int): View {
        val path = photos[index]
        val holder = FrameLayout(host).apply {
            isClickable = true
            isFocusable = true
            background = cellBackground(false)
            setOnClickListener { toggle(index) }
            setOnLongClickListener { confirmDelete(listOf(index)); true }
        }

        if (File(path).isFile) {
            holder.addView(
                ImageView(host).apply {
                    id = R.id.photo_manager_thumb
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            )
        } else {
            holder.addView(
                TextView(host).apply {
                    id = R.id.photo_manager_thumb
                    text = "已丢失"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(color(R.color.text_tertiary))
                },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            )
        }

        // 序号：删第几张这件事必须有个可对齐的编号（0 基还是 1 基？给用户看的一律 1 基）
        holder.addView(
            TextView(host).apply {
                text = "${index + 1}"
                textSize = 10f
                setTextColor(color(R.color.text_secondary))
                setPadding(dp(4), dp(2), dp(4), dp(2))
            },
            FrameLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.BOTTOM or Gravity.START }
        )

        holder.addView(
            TextView(host).apply {
                id = R.id.photo_manager_check
                text = "✓"
                textSize = 13f
                setTextColor(color(R.color.accent))
                visibility = View.GONE
                setPadding(dp(4), dp(2), dp(4), dp(2))
            },
            FrameLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.TOP or Gravity.END }
        )
        return holder
    }

    /** 选中态只重画对应那一格（不做整页重建：点选要立刻有反馈，重建会闪） */
    private fun toggle(index: Int) {
        if (index in selected) selected.remove(index) else selected.add(index)
        val cell = cells.getOrNull(index) ?: return
        paintCell(cell, index in selected)
        paintHeader()
    }

    /**
     * 全选 / 取消选择。
     *
     * 刻意**不重建网格**：`rebuild()` 会重新解一遍 50 张缩略图（几百毫秒 + 图片闪一下白），
     * 而"选中的格子变多"这件事只需要把已有的格子重画一遍背景。
     */
    private fun toggleSelectAll() {
        val selectAll = !(photos.isNotEmpty() && selected.size == photos.size)
        selected.clear()
        if (selectAll) {
            for (i in photos.indices) selected.add(i)
        }
        cells.forEachIndexed { index, cell -> paintCell(cell, index in selected) }
        paintHeader()
    }

    /** 把一格的选中态画出来（背景边框 + 右上角 ✓） */
    private fun paintCell(cell: View, on: Boolean) {
        cell.background = cellBackground(on)
        cell.findViewById<View>(R.id.photo_manager_check)?.visibility =
            if (on) View.VISIBLE else View.GONE
    }

    private fun paintHeader() {
        header.text = "管理图片（共 ${photos.size} 张）"
        hint.text = if (selected.isEmpty()) {
            HINT_IDLE
        } else {
            // 只列前 10 个序号：50 张全选时列全了这行会变成 4 行（虽然已被 maxLines 截断，
            // 但截断后的"1、2、…、25"读起来像只选了 25 张，不如自己收口）
            val sorted = selected.sorted()
            val head = sorted.take(10).joinToString("、") { "${it + 1}" }
            val tail = if (sorted.size > 10) "…等 ${sorted.size} 张" else ""
            "已选 ${sorted.size} 张：$head$tail"
        }
        selectAllButton.text = if (photos.isNotEmpty() && selected.size == photos.size) "取消选择" else "全选"
        selectAllButton.isEnabled = photos.isNotEmpty()
        deleteButton.text = if (selected.isEmpty()) "删除所选" else "删除所选（${selected.size}）"
        deleteButton.isEnabled = selected.isNotEmpty()
        deleteButton.alpha = if (selected.isNotEmpty()) 1f else 0.45f
    }

    // ---------------------------------------------------------------- 删除

    private fun confirmDelete(indices: List<Int>) {
        val valid = indices.filter { it in photos.indices }
        if (valid.isEmpty()) return
        val remaining = photos.size - valid.size
        AlertDialog.Builder(host)
            .setTitle(if (valid.size == 1) "删除这张图片？" else "删除这 ${valid.size} 张图片？")
            .setMessage(
                if (remaining > 0) {
                    "删除后不可恢复，其余 $remaining 张不受影响。"
                } else {
                    "删除后不可恢复，删完就没有图片了（图片开关会留在原位）。"
                }
            )
            .setPositiveButton("删除") { _, _ -> doDelete(valid) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doDelete(indices: List<Int>) {
        val deleted = runCatching { WidgetPhotos.removeAt(host, indices) }.getOrDefault(0)
        Log.i(
            TAG,
            "删除图片 ${indices.size} 张（下标 ${indices.sorted()}）→ 实际删掉 $deleted 张，" +
                "剩余 ${photos.size - deleted} 张"
        )
        if (deleted > 0) {
            onDeleted(deleted, (photos.size - deleted).coerceAtLeast(0))
        }
        rebuild()
    }

    // ---------------------------------------------------------------- 缩略图

    /**
     * 后台解码全部缩略图，完成后一次性铺到格子上；[token] 与 [generation] 不符就丢弃结果。
     *
     * 解码尺寸按 [THUMB_PX]（与格子大小无关地取一个固定值）：格子在不同密度/不同列宽下
     * 会差几个像素，为此多解一档没有意义，而固定值让"50 张一共占多少内存"是可算的。
     */
    private fun loadThumbnails(token: Int) {
        if (photos.isEmpty()) return
        val paths = photos
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = 1
        }
        Thread {
            val thumbs = ArrayList<Bitmap?>(paths.size)
            for ((index, path) in paths.withIndex()) {
                val bitmap = if (File(path).isFile) {
                    runCatching {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                        BitmapFactory.decodeFile(
                            path,
                            BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.RGB_565
                                inSampleSize = WidgetPhotos.sampleSizeFor(bounds.outWidth, bounds.outHeight, THUMB_PX)
                            }
                        )
                    }.getOrNull()
                } else {
                    null
                }
                thumbs.add(bitmap)
                if (index % 5 == 4 || index == paths.lastIndex) {
                    val done = index + 1
                    host.runOnUiThread {
                        if (closed || token != generation) return@runOnUiThread
                        hint.text = "正在载入缩略图 $done/${paths.size}…"
                    }
                }
            }
            host.runOnUiThread {
                if (closed || token != generation) {
                    // 过期结果：只丢引用不 recycle（见类注释）
                    return@runOnUiThread
                }
                thumbs.forEachIndexed { index, bitmap ->
                    val cell = cells.getOrNull(index) ?: return@forEachIndexed
                    val view = cell.findViewById<View>(R.id.photo_manager_thumb)
                    if (view is ImageView && bitmap != null) view.setImageBitmap(bitmap)
                }
                paintHeader()
            }
        }.apply { name = "photo-thumbs"; isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- 小工具

    private fun cellBackground(selected: Boolean): Drawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(color(if (selected) R.color.accent_soft else R.color.surface_alt))
        setStroke(dp(if (selected) 2 else 1), color(if (selected) R.color.accent else R.color.stroke))
    }

    private fun textButton(text: String, onClick: () -> Unit): Button = Button(host).apply {
        this.text = text
        textSize = 14f
        setOnClickListener { onClick() }
        background = host.getDrawable(R.drawable.bg_pill)
        setTextColor(color(R.color.accent))
        isAllCaps = false
    }

    private fun color(res: Int): Int = host.getColor(res)

    private fun dp(v: Int): Int = (v * host.resources.displayMetrics.density + 0.5f).toInt()

    companion object {

        private const val TAG = "WidgetPhoto"

        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT

        /** 一行放几格 */
        private const val COLS = 4

        /** 格子高度（dp）。宽由 4 列均分，所以实际是"略高的方块" */
        private const val CELL_DP = 78

        /**
         * 缩略图解码的目标长边（px）。
         *
         * 取 240：按 3 倍密度屏、4 列约 80dp 的格子算，240px 已经比显示所需的像素多，
         * 而 50 张合计（240×240×2 字节）约 5.8 MB —— 对"设置页里的一个弹窗"是能接受的上限。
         */
        private const val THUMB_PX = 240

        /** 没选中任何东西时的操作说明（固定两行高，见 [PhotoManagerDialog.hint] 的注释） */
        private const val HINT_IDLE = "点一下选中／取消；长按删除这一张。删除只影响选中的那些，其余不动。"

        /**
         * 打开管理界面。
         *
         * @param onDeleted 每删一批回调一次（`(本次删掉几张, 还剩几张)`）—— 调用方据此刷新桌面组件。
         *                  刻意做成"每删一次就回调"而不是"关窗时回调一次"：用户删完会马上按 Home
         *                  去看组件，如果那时才刷新，组件上可能还挂着已经被删掉的那张图。
         * @param onClosed 关闭时回调一次（调用方通常用它重建设置页：张数与开关可用性都变了）
         */
        fun show(
            activity: Activity,
            onDeleted: (Int, Int) -> Unit = { _, _ -> },
            onClosed: () -> Unit = {}
        ) {
            runCatching { PhotoManagerDialog(activity, onDeleted, onClosed).show() }
                .onFailure { Log.w(TAG, "打不开图片管理界面：${it.javaClass.simpleName}: ${it.message}") }
        }
    }
}
