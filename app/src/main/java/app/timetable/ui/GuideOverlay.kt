package app.timetable.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.timetable.R
import app.timetable.data.Prefs

/**
 * 首次使用的「指点式」引导。
 *
 * 为什么是这种形式（而不是一个说明书弹窗）：
 *  - 说明书没人看；**指着那个按钮说"点这里"** 才有人跟着做；
 *  - 所以每一步都尽量挂在一个真实的控件上：把它周围压暗、留一个亮框，
 *    卡片自动放到目标下方或上方（哪边够就放哪边），不会盖住要点的东西；
 *  - 点屏幕任意处 / 点「下一步」都继续，右上角随时「跳过」。
 *
 * 触发时机由调用方决定：本类只负责"显示一次引导"，不碰偏好。
 * 关掉动效的用户会看到瞬间出现（没有淡入），这也是刻意的。
 */
internal object GuideOverlay {

    private const val DIM = 0xB8000000.toInt()

    /** 一步引导：anchor 返回要指的那个控件（返回 null 就是居中说明卡） */
    class Step(
        val title: String,
        val body: String,
        val anchor: () -> View? = { null }
    )

    private var host: View? = null

    fun isShowing(): Boolean = host != null

    fun dismiss() {
        val h = host ?: return
        host = null
        (h.parent as? ViewGroup)?.removeView(h)
    }

    /**
     * 显示引导。
     *
     * @return 是否**真的显示出来了**。调用方要据此决定要不要把"已看过引导"记下来 ——
     *         如果没显示出来也记账，这次机会就永久丢了（用户再也等不到自动引导）。
     *         已经显示着的时候返回 false，避免旋转/onResume 反复叠加出好几层遮罩。
     * [onFinish] 在"看完"或"跳过"后各调用一次。
     */
    fun show(activity: Activity, steps: List<Step>, onFinish: () -> Unit = {}): Boolean {
        if (steps.isEmpty() || host != null) return false
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false

        val hostView = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true          // 吃掉底层点击，引导期间不误触
            setBackgroundColor(0)
        }
        host = hostView
        // 视图被移除（旋转、Activity 销毁）时必须把静态引用放掉，
        // 否则下一次 show() 会因为"已经有一个 host"而直接返回，引导再也弹不出来
        hostView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                if (host === v) host = null
            }
        })

        // 四块遮罩拼出一个"洞"，洞的位置每步重算
        val dims = List(4) {
            View(activity).apply {
                setBackgroundColor(DIM)
                hostView.addView(this, FrameLayout.LayoutParams(0, 0))
            }
        }

        val card = buildCard(activity)
        hostView.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        var index = 0

        fun finish() {
            dismiss()
            onFinish()
        }

        fun layoutStep() {
            val step = steps[index]
            val target = runCatching { step.anchor() }.getOrNull()
            val hostW = hostView.width.takeIf { it > 0 }
                ?: activity.resources.displayMetrics.widthPixels
            val hostH = hostView.height.takeIf { it > 0 }
                ?: activity.resources.displayMetrics.heightPixels

            val pad = Ui.dp(activity, 8f)
            val hole = if (target != null && target.isShown) {
                val a = IntArray(2)
                val b = IntArray(2)
                target.getLocationOnScreen(a)
                hostView.getLocationOnScreen(b)
                val left = (a[0] - b[0] - pad).coerceAtLeast(0)
                val top = (a[1] - b[1] - pad).coerceAtLeast(0)
                val right = (left + target.width + pad * 2).coerceAtMost(hostW)
                val bottom = (top + target.height + pad * 2).coerceAtMost(hostH)
                intArrayOf(left, top, right, bottom)
            } else {
                // 没有目标：把洞收到中间一条细线，视觉上等于整屏压暗
                intArrayOf(0, hostH / 2, hostW, hostH / 2)
            }

            dims[0].layoutParams = FrameLayout.LayoutParams(hostW, hole[1].coerceAtLeast(0))
            dims[1].layoutParams =
                FrameLayout.LayoutParams(hostW, (hostH - hole[3]).coerceAtLeast(0))
            dims[2].layoutParams =
                FrameLayout.LayoutParams(hole[0].coerceAtLeast(0), (hole[3] - hole[1]).coerceAtLeast(0))
            dims[3].layoutParams =
                FrameLayout.LayoutParams((hostW - hole[2]).coerceAtLeast(0), (hole[3] - hole[1]).coerceAtLeast(0))
            (dims[1].layoutParams as FrameLayout.LayoutParams).topMargin = hole[3]
            (dims[3].layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = hole[1]
                leftMargin = hole[2]
            }
            dims.forEach { it.requestLayout() }

            // 卡片：量完再决定放目标上方还是下方
            val gap = Ui.dp(activity, 14f)
            val side = Ui.dp(activity, 16f)
            card.measure(
                View.MeasureSpec.makeMeasureSpec(hostW - side * 2, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val cardH = card.measuredHeight
            val below = hole[3] + gap
            val y = if (below + cardH <= hostH - side) {
                below
            } else {
                (hole[1] - gap - cardH).coerceAtLeast(side)
            }
            (card.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = side
                rightMargin = side
                topMargin = y
                width = hostW - side * 2
            }
            card.requestLayout()
        }

        // 文案与进度
        val title = card.findViewWithTag<TextView>(TAG_TITLE)
        val body = card.findViewWithTag<TextView>(TAG_BODY)
        val dots = card.findViewWithTag<TextView>(TAG_DOTS)
        val primary = card.findViewWithTag<TextView>(TAG_PRIMARY)

        fun paint() {
            val step = steps[index]
            title.text = step.title
            body.text = step.body
            dots.text = steps.indices.joinToString(" ") { if (it == index) "●" else "○" }
            primary.text = if (index == steps.lastIndex) "知道了" else "下一步"
            layoutStep()
        }

        hostView.setOnClickListener {
            if (index < steps.lastIndex) {
                index++
                paint()
            } else {
                finish()
            }
        }
        primary.setOnClickListener {
            if (index < steps.lastIndex) {
                index++
                paint()
            } else {
                finish()
            }
        }
        card.findViewWithTag<TextView>(TAG_SKIP)?.setOnClickListener { finish() }

        content.addView(hostView)
        // 宽高要等一次布局才准，所以第一帧之后再做定位
        hostView.post {
            paint()
            if (Prefs.animEnabled) {
                card.alpha = 0f
                card.animate().alpha(1f).setDuration(160).start()
            }
        }
        return true
    }

    // ------------------------------------------------------------- 视图

    private const val TAG_TITLE = "guide_title"
    private const val TAG_BODY = "guide_body"
    private const val TAG_DOTS = "guide_dots"
    private const val TAG_PRIMARY = "guide_primary"
    private const val TAG_SKIP = "guide_skip"

    private fun buildCard(activity: Activity): LinearLayout {
        val ctx = activity
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.getDrawable(R.drawable.bg_card)
            elevation = Ui.dpf(ctx, 6f)
            setPadding(Ui.dp(ctx, 16f), Ui.dp(ctx, 14f), Ui.dp(ctx, 16f), Ui.dp(ctx, 14f))

            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(ctx).apply {
                            tag = TAG_TITLE
                            textSize = 16f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(ctx.getColor(R.color.text_primary))
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        TextView(ctx).apply {
                            tag = TAG_SKIP
                            text = "跳过"
                            textSize = 13f
                            setTextColor(ctx.getColor(R.color.text_secondary))
                            setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 4f), Ui.dp(ctx, 2f), Ui.dp(ctx, 4f))
                            isClickable = true
                        }
                    )
                }
            )
            addView(
                TextView(ctx).apply {
                    tag = TAG_BODY
                    textSize = 13.5f
                    setTextColor(ctx.getColor(R.color.text_secondary))
                    setLineSpacing(Ui.dpf(ctx, 3f), 1f)
                    setPadding(0, Ui.dp(ctx, 6f), 0, 0)
                }
            )
            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, Ui.dp(ctx, 12f), 0, 0)
                    addView(
                        TextView(ctx).apply {
                            tag = TAG_DOTS
                            textSize = 11f
                            setTextColor(ctx.getColor(R.color.accent))
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        TextView(ctx).apply {
                            tag = TAG_PRIMARY
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                            gravity = Gravity.CENTER
                            setTextColor(ctx.getColor(R.color.page_bg))
                            background = ctx.getDrawable(R.drawable.bg_accent_chip)
                            setPadding(
                                Ui.dp(ctx, 18f), Ui.dp(ctx, 9f),
                                Ui.dp(ctx, 18f), Ui.dp(ctx, 9f)
                            )
                            isClickable = true
                        }
                    )
                }
            )
        }
    }
}
