package app.timetable.widget

import android.content.Context

/**
 * 「现在显示的是第几张图」这个下标 —— 点击换图与自动换图都要推进它。
 *
 * ## 为什么单独开一个 SharedPreferences，而不是加到 data/Prefs.kt
 *
 * 这个下标是**小组件自己的界面状态**（换了哪张图），不是用户的设置项，语义上就不属于 Prefs；
 * 而且 `data/Prefs.kt` 不在本轮允许改动的文件里。所以在这里另开一个极小的存储
 * （文件 `widget_photo_state`）。代价是多一个文件；好处是小组件的状态与用户设置互不干扰，
 * 将来要"重置小组件状态"只需删这一个文件，不会碰到用户的其他选项。
 *
 * ## 只有一份下标，不按组件 id 分
 *
 * 桌面上同时钉两个组件时它们会**同步**换图。按组件 id 存看起来更"正确"，但组件 id 在重启、
 * 重装、换启动器之后都会变（变了下标就丢，用户看到的是"图又跳回第一张"）；
 * 共用一份下标的失败模式轻得多：两个组件看到同一张，谁也不出错。
 *
 * ## 存值一律先自愈再用
 *
 * 用户随时可能删图、清图、再挑图（张数从 5 变 1），所以存下来的数字**从不直接使用**，
 * 一律先 [clamp] 回当前合法范围 —— 负数、超界、张数变了都能算出合法下标。
 */
internal object PhotoCursor {

    private const val FILE = "widget_photo_state"
    private const val KEY_INDEX = "photoIndex"

    /**
     * 纯函数（可单测）：把任意存值夹进 `0 until count`。
     *
     * 用取模而不是 `coerceIn`：取模对"张数变小"也是对的方向（图从 5 张删到 2 张时，
     * 旧下标 3 会绕到 1，而不是每次都停在最后一张 —— 停在最后一张的话，用户按"下一张"
     * 会看到图片先跳到第一张，像是随机跳而不是顺序翻）。
     */
    fun clamp(index: Int, count: Int): Int =
        if (count <= 0) 0 else ((index % count) + count) % count

    /**
     * 纯函数（可单测）：点一下之后的下一张 —— 到末尾**绕回第一张**。
     *
     * 这里刻意不处理"这张图解不出来"：坏图由 [WidgetExtras.decodeFrom] 在解码时按顺序跳过。
     * 下标推进保持纯算术，才能在单测里把边界（0 张 / 1 张 / 末尾 / 越界存值）全覆盖。
     */
    fun advance(index: Int, count: Int): Int =
        if (count <= 0) 0 else (clamp(index, count) + 1) % count

    /** 现在该显示第几张（没存过就是第一张） */
    fun current(context: Context, count: Int): Int =
        clamp(prefs(context).getInt(KEY_INDEX, 0), count)

    /** 推进到下一张并立刻落盘（点击换图与自动换图都走这里） */
    fun next(context: Context, count: Int): Int {
        val at = advance(prefs(context).getInt(KEY_INDEX, 0), count)
        prefs(context).edit().putInt(KEY_INDEX, at).apply()
        return at
    }

    /**
     * 把"这次真正显示出来的那一张"写回。
     *
     * 为什么必须写回：解码时会跳过坏图（顺序往后找第一张能用的），如果下标不跟着走，
     * 用户每点一次都会先撞一次同一张坏图，表现为"按两下才换一张"。
     */
    fun remember(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_INDEX, index).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
