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
    val quoteVisible: Boolean
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
 * ## 本轮（删掉图片轮播之后）语义上的一个变化
 *
 * 以前"行数先按整个高度算完，剩下的零头再看够不够放图"，于是零头常常只有几个 dp ——
 * 用户开了图片却什么也看不见。现在算行数时**先把图片预留扣掉**（[WidgetData.rowsFor] 的
 * `reserveDp`），所以传进来的 [plan] 的 `availableDp` 天然含着一块图片的高度：
 * 只要行数算得出来、格子不是被压到最小尺寸，图片就一定会显示。
 * 另外 [plan] 的 `idealPhotoDp` 现在也应传[WidgetData.photoBoxTargetDp]（"这次框实际能有多高"），
 * 而不是固定的 16:9 理想值 —— 多出来的、不够一整行的零头就是靠它归到图片上的。
 */
internal object ExtrasPlanner {

    /**
     * @param override       [Prefs.widgetExtrasOverride]：-1 隐藏 / 0 自动 / 1 强制
     * @param quoteEnabled   每日一句开关
     * @param photoEnabled   图片开关
     * @param photoCount     实际存在的图片张数（0 张等于没开）
     * @param availableDp    列表下方的真实余量（[WidgetData.extraSpaceDp]）。**图片开着时它已经
     *                       包含给图片预留的那一块**（见 [WidgetData.photoReserveDp]）
     * @param idealPhotoDp   这次图片框**实际**能有多高（[WidgetData.photoBoxTargetDp]）；
     *                       不再是"按宽高比算出来的固定理想值"，那样多出来的零头就浪费了
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
        idealPhotoDp: Int,
        quoteCostDp: Int = WidgetData.EXTRA_MIN_DP,
        minPhotoDp: Int = WidgetData.PHOTO_HARD_MIN_DP,
        gapDp: Int = WidgetData.AREA_GAP_DP
    ): ExtrasPlan {
        val wantPhoto = photoEnabled && photoCount > 0
        val wantQuote = quoteEnabled
        val avail = availableDp.coerceAtLeast(0)

        // -1：用户明确要求隐藏（Settings 里的「强制隐藏」）
        if (override < 0) return ExtrasPlan(false, 0, false)

        // +1：强制显示。仍然不要超过"理想高度"，但允许用掉余量之外的 22dp
        //（老代码就是 `ideal.coerceAtMost(space + EXTRA_MIN_DP)`，这里保持同样的宽松度，
        // 免得真要救的机型反而比之前显示得更小）
        if (override > 0) {
            if (!wantPhoto) return ExtrasPlan(false, 0, wantQuote)
            val room = maxOf(avail + quoteCostDp, gapDp + minPhotoDp)
            return ExtrasPlan(true, idealPhotoDp.coerceIn(minPhotoDp, room), wantQuote)
        }

        // 0：自动。没开图片就只剩句子这一件事
        if (!wantPhoto) {
            return ExtrasPlan(false, 0, wantQuote && avail >= quoteCostDp)
        }

        // 图片自身的可用高度要扣掉它的上边距
        val photoRoom = (avail - gapDp).coerceAtLeast(0)
        if (photoRoom < minPhotoDp) {
            // 连最低限度的图片都摆不下：退成句子（若句子也放不下，就什么都不显示 —— 宁缺勿挤）
            return ExtrasPlan(false, 0, wantQuote && avail >= quoteCostDp)
        }
        if (!wantQuote) {
            return ExtrasPlan(true, idealPhotoDp.coerceIn(minPhotoDp, photoRoom), false)
        }

        // 两个都开：从同一个预算里先扣句子的份额，再看图片还剩多少
        val roomForBoth = (avail - quoteCostDp - gapDp).coerceAtLeast(0)
        return if (roomForBoth >= minPhotoDp) {
            ExtrasPlan(true, idealPhotoDp.coerceIn(minPhotoDp, roomForBoth), true)
        } else {
            // 放不下两个 → 保图片、弃句子（理由见类注释第 2 条）
            ExtrasPlan(true, idealPhotoDp.coerceIn(minPhotoDp, photoRoom), false)
        }
    }
}
