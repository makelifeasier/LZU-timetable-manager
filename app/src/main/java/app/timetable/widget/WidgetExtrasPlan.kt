package app.timetable.widget

/**
 * 底部扩展区（每日一句 / 图片）**该显示哪几块、图片给多高**的决策结果。
 *
 * 刻意做成不含任何 Android 类型的纯数据：单测里可以直接喂数字、直接断言，
 * 不必造 Context（单测里 Android API 全是"返回默认值"的假实现，拿它们当判据只会测出假结果）。
 */
internal class ExtrasPlan(
    val photoVisible: Boolean,
    /** 图片框高度（dp）；[photoVisible] 为 false 时无意义 */
    val photoHeightDp: Int,
    val quoteVisible: Boolean,
    /**
     * 这份计划是**基于多少余量**算出来的（[WidgetData.extraSpaceDp]，负数已夹成 0）。
     *
     * 带上它只有一个目的：让 [ExtrasPlanner.leftoverBelowBoxDp]（日志里的 `底部零头=`）**只有一处输入**
     * —— 那个数必须与这份计划同源，否则又变成"两个地方各算一次同一个数"，
     * 而这个组件已经在"框高/预留各算一次"上吃过一次亏（注释里记着的那两次反复）。
     */
    val availableDp: Int
) {
    override fun toString() =
        "image=${if (photoVisible) "${photoHeightDp}dp" else "off"} quote=${if (quoteVisible) "on" else "off"}"
}

/**
 * 每日一句与图片框的**取舍规则**（纯函数，可单测）。
 *
 * ## 这里以前是错的
 *
 * 老逻辑是"图片优先"：`quoteOn = allowed && quoteEnabled && !photoOn && ...` —— 只要图片开着，
 * 句子就被无条件藏起来。用户的理解（也是对的）是"我两个都开了，凭什么都显示不出来的那个是我要的"。
 * 现在改成：
 *  1. 两个开关都开、**余量真的够** → **两个都显示**（句子在上、图片在下，与布局里的顺序一致）；
 *  2. 余量确实不够 → 才退让，而且**保图片、弃句子**：
 *     图片是用户从相册里一张张挑进来的（主动行为、信息量大），句子是内置文本（装饰性、
 *     明天还有一句）—— 空间不够时先砍装饰，符合直觉；
 *  3. 连图片的最低高度都占不下 → 反过来只显示句子（一句话只要 22dp，比图片便宜得多，
 *     这时候"有字"总比"空白"强）。
 *
 * ## 为什么两块必须一起算
 *
 * 以前两块各自判断（图片看余量、句子看"图片没开"），于是会互相打架：图片挤掉句子、
 * 或者反过来。现在**只有一个预算**（[availableDp]），两块从同一个预算里扣，
 * 总高度 = 句子 + 图片 + 各自的 6dp 上边距，不可能超。
 *
 * ## 与 [Prefs.widgetExtrasOverride] 的关系（语义保持不变）
 *
 * - `-1` 强制隐藏：两块都不显示；
 * - `0` 自动：上面的余量规则；
 * - `1` 强制显示：**跳过余量判断**（少数机型上报的高度根本是错的，按它算必然是"显示不出来"），
 *   按理想尺寸把开着的都摆上去。放不下的部分交给系统裁 —— 这是用户自己选的档位。
 *
 * ## 本轮：框把**剩下的一整条零头**也吃掉
 *
 * 上一轮的做法是"框高 = 照片需要的高"（[WidgetData.photoWantedHeightDp]），多出来的高度还给课程列表。
 * 那个方向没错（下拉组件时该多显示课，而不是把照片撑大），但**课程行只能整行加**（行高 38dp，
 * 一跳就是一行）：加到不能再加之后，剩下不足一行的零头（真机实测 24dp）就留在组件底部 ——
 * 用户看到的还是"下拉之后依然有空白"。
 *
 * 所以现在的分工是：
 *  - **行数**仍然先按"整行"定死（[WidgetData.rowsFor]，整数行是硬约束：底部绝不能切出半行）；
 *  - **图片框**拿走剩下的**全部**空间：`框高 = 余量 − 6dp 上边距`（两个开关都开时还要先扣掉
 *    每日一句的 22dp）。于是底部零头恒为 0（判据见 [leftoverBelowBoxDp]）。
 *
 * 为什么宁可把照片框拉高、也不把零头留在底部：那条空白是**纯粹的浪费**（它既不是课、也不是图，
 * 就是一块底色）；而框拉高之后照片会按 [PhotoFit.layout] 的规则**居中取一块填满**（比例一致时
 * 就是整张原样显示）—— 至少那块空间里是用户自己的照片。用户原话："比留一条空白好看，也比拉伸好"。
 *
 * 注意这一档**不会**把照片拉伸：取的是"与框同比例、居中、尽可能大"的一块（见 [PhotoFit]）。
 *
 * **强制显示档（override = 1）不参与"吃零头"**：那个档位是"少数机型上报的高度根本是错的"时的
 * 逃生舱，输入本身就是不可信的数字，按照片理想高度摆上去才是它的语义（见下面 plan 里的注释）。
 */
internal object ExtrasPlanner {

    /**
     * @param override       [Prefs.widgetExtrasOverride]：-1 隐藏 / 0 自动 / 1 强制
     * @param quoteEnabled   每日一句开关
     * @param photoEnabled   图片开关
     * @param photoCount     实际存在的图片张数（0 张等于没开）
     * @param availableDp    列表下方的真实余量（[WidgetData.extraSpaceDp]）。**图片开着时它已经
     *                       包含给图片预留的那一块**（见 [WidgetData.photoReserveDp]）
     * @param wantedPhotoDp  **照片自己要的框高**（[WidgetData.photoWantedHeightDp]：比例 × 内容宽度）。
     *                       自动档里它**不直接决定框高**（框高 = 余量 − 上边距，见类注释）；
     *                       它真正的用途是**排行数时的预留来源**（[WidgetData.photoReserveDp] 用同一个数），
     *                       所以只要格子装得下"预留 + 一整行的课"，框高就恒 ≥ 它；
     *                       装不下（最矮的那档，例如 150dp）时框会被压扁 → 走"居中取一块"，
     *                       这是唯一允许压扁的情形。强制显示档里它直接就是框高。
     *                       注意它**不是**"空间能给我多高"：后者是上一版的输入，也是
     *                       "下拉组件 → 照片没变、下边流出一大片空白"的来源。
     * @param quoteCostDp    一句话的占用（含它自己的 6dp 上边距）
     * @param minPhotoDp     图片框的**硬底线**：低于这个高度就不是"一张图"而是一条彩条了
     * @param gapDp          图片框的 6dp 上边距（布局里写死的，必须算进预算）
     */
    fun plan(
        override: Int,
        quoteEnabled: Boolean,
        photoEnabled: Boolean,
        photoCount: Int,
        availableDp: Int,
        wantedPhotoDp: Int,
        quoteCostDp: Int = WidgetData.EXTRA_MIN_DP,
        minPhotoDp: Int = WidgetData.PHOTO_HARD_MIN_DP,
        gapDp: Int = WidgetData.AREA_GAP_DP
    ): ExtrasPlan {
        val wantPhoto = photoEnabled && photoCount > 0
        val wantQuote = quoteEnabled
        val avail = availableDp.coerceAtLeast(0)

        fun result(photo: Boolean, heightDp: Int, quote: Boolean) =
            ExtrasPlan(photo, heightDp, quote, avail)

        // -1：用户明确要求隐藏（Settings 里的「强制隐藏」）
        if (override < 0) return result(false, 0, false)

        // 照片自己要的高度（强制显示档用；自动档里它是"排行数时要预留多少"的来源，见 [WidgetData.photoReserveDp]）
        val wanted = wantedPhotoDp.coerceAtLeast(minPhotoDp)

        // +1：强制显示。这个档位存在的理由是"少数机型上报的高度根本是错的"
        //（荣耀把声明值当高度回显），所以这里**故意不听余量**：按照片理想高度摆上去，
        // 放不下的部分交给系统裁 —— 这是用户自己选的档位。
        // 也因此它**不参与"吃零头"**（见类注释）：余量本身就是不可信的数字，
        // 拿它去把框撑满，会把照片拉成一条没有信息量的彩条。
        if (override > 0) {
            if (!wantPhoto) return result(false, 0, wantQuote)
            val room = maxOf(avail + quoteCostDp, gapDp + minPhotoDp)
            return result(true, wanted.coerceIn(minPhotoDp, room), wantQuote)
        }

        // 0：自动。没开图片就只剩句子这一件事
        if (!wantPhoto) {
            return result(false, 0, wantQuote && avail >= quoteCostDp)
        }

        // 图片自身的可用高度要扣掉它的上边距。
        // **这就是框高**（不是"照片要多高"）：剩余空间全给它，底部零头因此恒为 0
        // （判据 [leftoverBelowBoxDp]）。行数已经在上一层按整行定死了，这里再多的行数也排不出来。
        val photoRoom = (avail - gapDp).coerceAtLeast(0)
        if (photoRoom < minPhotoDp) {
            // 连最低限度的图片都摆不下：退成句子（若句子也放不下，就什么都不显示 —— 宁缺勿挤）。
            // 注意这里**必须**返回"不显示图片"而不是一个 32dp 以下的框：0 高的 setViewLayoutHeight
            // 会让宿主侧量出 0 高度，RemoteViews 那边就是"图没了"，日志里也看不出为什么。
            return result(false, 0, wantQuote && avail >= quoteCostDp)
        }
        if (!wantQuote) {
            return result(true, photoRoom, false)
        }

        // 两个都开：从同一个预算里先扣句子的份额，剩下的**全部**归图片
        val roomForBoth = (avail - quoteCostDp - gapDp).coerceAtLeast(0)
        return if (roomForBoth >= minPhotoDp) {
            result(true, roomForBoth, true)
        } else {
            // 放不下两个 → 保图片、弃句子（理由见类注释第 2 条）
            result(true, photoRoom, false)
        }
    }

    /**
     * 纯函数（可单测）：图片框**下方**还剩多少 dp —— 用户说的"底部空白"就是这个数。
     *
     * 判据（本轮的目标）：**自动档、图片显示着**时它必须恒为 **0** —— 框一路吃到组件底边
     * （准确的说是吃到根布局 paddingBottom 那 4dp 的位置，那 4dp 是布局自己的内边距，
     * 不算"空白"）。> 0 = 又漏了一条空白；< 0 只会出现在强制显示档（故意溢出，交给系统裁）。
     *
     * 输入只有一份计划（[ExtrasPlan.availableDp] 就是它算出来时用的那个余量）：
     * "每天一句占的 22dp"也在计划里（[ExtrasPlan.quoteVisible]），不用调用方再算一遍 ——
     * 这个数一旦被两处各算一次，日志里的 `底部零头=` 就不再是画面上的那条空白了。
     *
     * 这个函数是**日志与单测共用的唯一判据**（见 `PhotoBitmap.renderLabel` 的 `底部零头=` 那一列）：
     * 真机上用户拿截图看不到"零头"，只能靠这一行数字。
     */
    fun leftoverBelowBoxDp(plan: ExtrasPlan): Int {
        val avail = plan.availableDp.coerceAtLeast(0)
        if (!plan.photoVisible) return avail
        val quote = if (plan.quoteVisible) WidgetData.EXTRA_MIN_DP else 0
        return avail - WidgetData.AREA_GAP_DP - plan.photoHeightDp - quote
    }
}
