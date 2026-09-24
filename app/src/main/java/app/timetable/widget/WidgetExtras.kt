package app.timetable.widget

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import app.timetable.R
import app.timetable.data.Prefs
import java.io.File

/**
 * 把图片区（以及剩下的附加内容）挂到小组件的 RemoteViews 上。
 *
 * 单独抽出来是因为这段逻辑全在跟 RemoteViews 的限制打交道，塞进 build() 会很吵。
 *
 * ## 一路走到这一轮，图片区踩过哪些坑
 *
 * 0. **每日一句已经搬走了**：先试过"放进 ListView 当第一项"，用户看过真机之后否掉了那种形式
 *    （原话：「不是这种形式，把话放在第 n 周接下来第 n 节同一行」）—— 现在它**拼在副标题那一行**
 *    （[WidgetData.subtitleText]；句子长了由布局 `maxLines=1 + ellipsize=end` 截断，不换行、不撑高头部）。
 *    于是它一个像素都不占：扩展区只剩图片，"两块内容抢同一个预算、谁让谁"那套规则整条删掉了 ——
 *    图片框比以前高 22dp（原来那 22dp 是句子的固定位置），底部那条「X 分钟前同步」的页脚
 *    也按用户要求整行删掉，那 22dp 同样归了图片框（chrome 从 71dp 恒为 49dp）。
 * 1. **图片区没有"轮播控件"**。以前开关打开时这里会 `setRemoteAdapter` 到一个
 *    `AdapterViewFlipper`：它是个可滚动的 AdapterView，会吃掉竖直手势，
 *    课程列表就滑不动了（"自动轮播启动后小组件无法使用"）。现在图片区永远是同一个
 *    ImageView，**点一下换下一张**（[TodayWidgetProvider.ACTION_NEXT_PHOTO]）。
 * 2. **图片框高度 = 列表下方剩下的全部空间**（[ExtrasPlanner] 的结论），于是底部不留空白。
 *    中间的两次反复值得记下来：
 *     · 最早是"剩多少就用多少"→ 框比照片高、照片完整显示，上下各一大段留白；
 *     · 上一轮改成"框高 = 照片需要的高"、多出来的高度还给课程列表 → 方向对，但**课程行只能整行加**，
 *       加到不能再加之后那条不足一整行的零头（真机 24dp）还是留在底部，用户反馈"下拉之后依然有空白"；
 *     · 现在是"行数先按整行定死（底部不许切半行），剩下的**全部**归图片框"，照片按
 *       [PhotoFit.layout] 的规则**居中取一块填满**：比例一致（用户导入时裁的那条框与组件同形）
 *       就是整张原样显示，比例不一致才裁一点 —— 用户原话："比留一条空白好看，也比拉伸好"。
 *    判据是日志里的 `底部零头=0dp` 与 `留边=0px(0%)` 两列。
 * 3. **位图不再交给宿主缩放或裁剪**：解码尺寸就是框的像素尺寸（[PhotoBitmap.targetPx]），
 *    四个角也在位图里做好圆角遮罩（[PhotoBitmap.maskCorners]）—— 容器是圆角 drawable，
 *    照片的直角压在上面就是用户说的"直角紧贴圆角，很突兀"。
 * 4. 上一轮加过"显示方式 / 裁哪一段 / 放大倍数"三个用户开关。用户否掉了：
 *    「我不是要你有显示倍率，而是导入图片的时候可以自己裁剪」。于是这一轮改成：
 *    **用户在图库导入时自己裁一次**（`ui/PhotoCropDialog.kt`），**程序只按空间决定怎么放**
 *    （[PhotoFit.layout]：比例一致 → 整张完整显示；不一致 → 居中取一块填满）。
 *    三个偏好键从此不再被读取（说明写在 `widget/PhotoDisplayMode.kt` 里：
 *    那个文件现在只剩三个常量的空壳，只为一处编译保留，没有任何读写路径）。
 *
 * ## 日志是有意留的
 *
 * 这一段出问题在真机上极难定位（只有一张图 / 一张坏图 / 宿主不支持某个 RemoteViews 方法），
 * 所以每次刷新都打一行：显示了几张、余量多少、**框多大多少比例、裁剪框多少比例、
 * 走的是哪条分支（完整显示 / 取中间块）、位图多少像素多少字节、什么格式、set 成功没有、
 * 四个角的圆角半径与填色、底部还剩几 dp**。
 * "我在真机上看不到画面"的时候，这一行就是唯一判据。
 */
internal object WidgetExtras {

    private const val TAG = "WidgetPhoto"

    /** 现有的、真的还在磁盘上的图片（开关开着但文件被删光是很常见的情况） */
    fun photos(context: Context): List<String> =
        WidgetData.photoList(context).filter { File(it).exists() }

    /**
     * 这一轮"该显示哪几块、图片框多高"。
     *
     * 不解码、也不碰 RemoteViews —— 所以日志与自动换图（[PhotoAutoAdvance]）都能直接用它，
     * 不必先造一个 RemoteViews 出来。
     */
    fun plan(context: Context): ExtrasPlan {
        Prefs.init(context)
        val space = WidgetData.extraSpaceDp(context)
        val contentDp = WidgetData.contentWidthDp(context)
        // 比例只取一处：照片列表第一张（见 PhotoBitmap.photoAspect）。
        // 排行数（WidgetData.photoReserveDp）与这里算框高用的是同一个输入，
        // 否则"预留的高度"与"框实际的高度"会差出一截，底部又开始漏空白。
        val aspect = PhotoBitmap.photoAspect(context)
        return ExtrasPlanner.plan(
            override = Prefs.widgetExtrasOverride,
            photoEnabled = Prefs.photoEnabled,
            photoCount = photos(context).size,
            availableDp = space,
            // 这里给的是"**照片需要**多高"（比例 × 内容宽度，夹在 56..150dp），用来决定
            // **排行数时要给图片留多少**（[WidgetData.photoReserveDp] 是同一个数）。
            // 自动档的**框高不取这个值**：框吃掉列表下方的全部剩余空间 —— 只要格子装得下
            // "预留 + 一整行课"（≥187dp 那档），框高就 ≥ 它，照片不会被压到"需要的高度"以下；
            // 只有最矮的格子（150dp）会被压扁，那时走"居中取一块"。
            // 它只在强制显示档里直接当框高用（那个档位故意不听余量）。
            wantedPhotoDp = WidgetData.photoBoxTargetDp(space, contentDp, aspect)
        )
    }

    /** 图片框这次的高度（dp）；≤ 0 表示图片区不该显示（自动换图用它判断"还有没有必要转"） */
    fun photoBoxDp(context: Context): Int = plan(context).photoHeightDp

    /**
     * 把两块内容挂上去。
     *
     * @param nextPhoto 点图片区时发的"换下一张"广播（由 Provider 提供，见 [TodayWidgetProvider.broadcast]）
     */
    fun apply(context: Context, views: RemoteViews, nextPhoto: PendingIntent) {
        val space = WidgetData.extraSpaceDp(context)
        val photos = photos(context)
        val plan = plan(context)
        val contentDp = WidgetData.contentWidthDp(context)
        val density = context.resources.displayMetrics.density

        // ---- 图片 ----
        if (plan.photoVisible) {
            // 解码目标 = 框的内容宽 × 这次分到的高度（都换算成像素）。
            // 它是"位图绝不超过这个尺寸"的上限：完整显示那条路只会更小（留边的部分不传像素）。
            val target = PhotoBitmap.targetPx(contentDp, plan.photoHeightDp, density)
            // 裁剪框只用于日志：它回答"用户当初裁剪时的框，和此刻组件里的框差多少"
            val frame = WidgetData.photoFrame(context)
            val index = PhotoCursor.current(context, photos.size)
            val shown = decodeFrom(context, photos, index, target)

            if (shown == null) {
                // 一张都解不出来（文件损坏 / 根本不是图片）：退回"不显示图片"，句子照旧 ——
                // 但不能整块空白。代价：这一轮为图片预留的高度会空着，下一轮刷新（换尺寸/换天）会重新算。
                views.setViewVisibility(R.id.widget_photo_area, View.GONE)
                Log.w(
                    TAG,
                    "扩展区 $plan 余量=${space}dp 图片=${photos.size}张 裁剪框=${frame.label} " +
                        "全部解码失败 set=no（图片区已隐藏）"
                )
            } else {
                val (at, render) = shown
                // 把"真正显示出来的那一张"写回下标：跳过了坏图，下一次点击才是真的下一张
                PhotoCursor.remember(context, at)

                // 高度只能用 setViewLayoutHeight 表达（布局里图片框是 0dp，位置与顺序由布局定）。
                // 这个值 = **列表下方剩下的全部空间 − 6dp 上边距**（[ExtrasPlanner] 的结论）：
                // 课程行数已经按整行定死，剩下的零头全归图片框，于是组件底部不再漏空白。
                // 照片不会因此变形或被拉伸 —— 比例不一致时走"居中取一块填满"（[PhotoFit.layout]）。
                runCatching {
                    views.setViewLayoutHeight(
                        R.id.widget_photo_area,
                        plan.photoHeightDp.toFloat(),
                        TypedValue.COMPLEX_UNIT_DIP
                    )
                }
                views.setViewVisibility(R.id.widget_photo_area, View.VISIBLE)

                // 整块图片区（FrameLayout）可点 → 换下一张。
                // 点挂在**图片区**而不是里面的 ImageView 上：
                //  · ImageView 本身不消费点击（默认不可点），事件照样落到这一层，所以整块都灵；
                //  · 这块的高度就是图片的高度，不会多占一行课表的位置；
                //  · 课程列表、刷新、模式切换各自挂在别的控件上，互不抢 —— 列表的手势由 ListView 自己处理。
                runCatching {
                    views.setOnClickPendingIntent(R.id.widget_photo_area, nextPhoto)
                }

                val set = runCatching {
                    views.setImageViewBitmap(R.id.widget_photo_image, render.bitmap)
                }
                // 这一行是"真机核对"用的唯一判据（用户看不到画面，只能拿截图取像素对数字）：
                //   框=226x72dp 照片比例=3.14:1 需要的框高=72dp 留边=0px 行数=2 底部零头=0dp ...
                // 两条不变量：`留边=0px(0%)`（照片区里一条留白都没有）、`底部零头=0dp`
                // （图片框下方的组件底部也没有留白）。哪一列不对，就去查它对应的规则是不是被改回去了。
                Log.i(
                    TAG,
                    "扩展区 $plan 余量=${space}dp 图片=${photos.size}张 第 ${at + 1} 张 " +
                        PhotoBitmap.renderLabel(
                            render = render,
                            frame = frame,
                            boxWidthDp = contentDp,
                            boxHeightDp = plan.photoHeightDp,
                            // "需要的框高"必须按**决定裁剪框的那个比例**（第一张）算，
                            // 不能按当前显示这张算 —— 否则日志里的两列自己就对不上号了
                            wantedHeightDp = WidgetData.photoWantedHeightDp(context),
                            rows = WidgetData.visibleRows(context),
                            // 底部零头（用户说的"空白"）由这份计划自己算出来：自动档恒为 0
                            leftoverDp = ExtrasPlanner.leftoverBelowBoxDp(plan)
                        ) +
                        " set=${if (set.isSuccess) "ok" else "FAIL:${set.exceptionOrNull()?.javaClass?.simpleName}"}"
                )
            }
        } else {
            views.setViewVisibility(R.id.widget_photo_area, View.GONE)
            Log.i(TAG, "扩展区 $plan 余量=${space}dp 图片=${photos.size}张（图片未显示）")
        }

        // ---- 每日一句不在这里了 ----
        // 它现在拼在副标题那一行（WidgetData.subtitleText）：不吃固定高度、不占列表格子，
        // 也不再和图片抢同一块预算（以前的"保图片、弃句子"那套规则随之删掉）。
        // 底部「X 分钟前同步」的页脚也整行删了。这里剩下的只有图片区，
        // 所以上面那行日志里的 `image=` 就是扩展区的全部内容。

        // 自动换图：按当前状态排/撤闹钟。图片被隐藏、只剩一张、开关关掉，都会在这里被取消 ——
        // 放在这里是因为"图片到底显示没有"只有这里算得出来（见 PhotoAutoAdvance.sync）
        PhotoAutoAdvance.sync(context)
    }

    /**
     * 从第 [start] 张开始按"当前这张 → 其余各张"的顺序，找第一张能解码的图。
     *
     * 返回显示出来的下标与渲染结果。**一定要返回下标**：调用方要靠它把坏图跳过这件事写回状态，
     * 否则用户每点一次都会先撞一次坏图，像"按两下才换一张"。
     *
     * "第一张坏了就整块空白"是最糟的失败模式：用户明明开了图片却什么都看不到，
     * 而实际上只有一张图有问题。
     *
     * 这里**没有**"显示方式"参数了：裁哪一块由用户在导入时决定（图片文件本身就是裁好的），
     * 怎么放由 [PhotoFit.layout] 按当前框决定 —— 同一个框、同一张图必然得到同一个结果，
     * 所以「整块重画」与「只换图片的局部刷新」不可能再对不上。
     */
    fun decodeFrom(
        context: Context,
        photos: List<String>,
        start: Int,
        target: IntArray
    ): Pair<Int, PhotoRender>? {
        if (photos.isEmpty() || target[0] <= 0 || target[1] <= 0) return null
        val from = PhotoCursor.clamp(start, photos.size)
        for (step in photos.indices) {
            val index = (from + step) % photos.size
            val render = PhotoBitmap.decode(context, photos[index], target[0], target[1])
            if (render != null) return index to render
            Log.w(TAG, "跳过一张解不出来的图：${photos[index]}")
        }
        return null
    }
}
