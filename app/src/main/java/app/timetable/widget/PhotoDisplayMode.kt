package app.timetable.widget

import android.content.Context
import app.timetable.data.Prefs

/**
 * 图片区的「显示方式」——用户原话：
 *
 * > 「会出现图片显示不全的情况，而且应该加上选择图片时选择显示的样式即截取多少。」
 *
 * ## 两个模式各解决什么问题（什么时候该用哪个）
 *
 * - **[FILL] 填满（裁剪）**：按框的比例把照片裁一块铺满，**不留边**。
 *   适合"把照片当背景铺底"的用法：整块图片区都是画面，视觉最满。
 *   代价就是"显示不全"——照片里被框比例排掉的部分永远看不到。
 *   **这是历史行为，也是默认值**：老用户的观感不会因为这次改动而变。
 *
 * - **[CONTAIN] 完整显示（不裁剪）**：整张照片等比缩到框里，**一个像素都不裁**，
 *   多出来的地方留边（底色用布局里图片框自己的背景 `widget_row_bg`，不新增 drawable）。
 *   适合"我就是想看看这张照片长什么样/照片里有字或有人"的用法。
 *   代价是留边可能很大：框 349×52dp（≈6.7:1）配 16:9 的照片时，**左右各留约 128px 空白**，
 *   约 73% 的框是空的（数字见 [PhotoFit.containLayout] 的注释与单测）。
 *   这是用户自己选的模式，所以这里**不擅自改框的高度**去迁就它。
 */
internal enum class PhotoFitMode {
    /** 填满：按框比例裁剪，铺满不留边（默认，与老行为一致） */
    FILL,

    /** 完整显示：整张缩进框，等比不裁剪，多出来的地方留边 */
    CONTAIN;

    /** 日志里的一行中文（用户看不到画面，只能看日志，所以这一列必须自解释） */
    val label: String
        get() = when (this) {
            FILL -> "填满"
            CONTAIN -> "完整显示"
        }

    /**
     * 该给 ImageView 设的 `scaleType` 序号（`RemoteViews.setInt(..., "setScaleType", n)`）。
     *
     * 为什么要显式设：布局里写死的是 `centerCrop`（老行为）。[CONTAIN] 模式下位图已经按
     * "塞得进框"的尺寸做好了，宿主要是还按 centerCrop 放大，会**再裁掉一两个像素** ——
     * 而"完整显示"模式的全部意义就是"一个像素都不裁"，差一像素也说不通。
     *
     * 序号取值来自 Android 的 `ImageView.ScaleType` 枚举顺序
     * （`MATRIX=0, FIT_XY=1, FIT_START=2, FIT_CENTER=3, FIT_END=4, CENTER=5, CENTER_CROP=6`），
     * 这里写字面量而**不引用 `ImageView.ScaleType` 常量**：单测里（`isReturnDefaultValues = true`）
     * Android 常量全是 0，引用它们等于把"序号对不对"这件最该被钉住的事测成假绿；
     * 改用字面量后，单测可以直接断言这几个数字。
     * 万一日后宿主不支持这个 `setInt`：调用点全部包了 `runCatching`，退化成布局里的 `centerCrop`，
     * 位图本身仍是"塞得进框"的尺寸，最多差一两个像素边缘。
     */
    val scaleTypeOrdinal: Int
        get() = when (this) {
            FILL -> SCALE_TYPE_CENTER_CROP
            CONTAIN -> SCALE_TYPE_FIT_CENTER
        }

    companion object {
        /** `ImageView.ScaleType.FIT_CENTER` 的序号 */
        const val SCALE_TYPE_FIT_CENTER = 3

        /** `ImageView.ScaleType.CENTER_CROP` 的序号 */
        const val SCALE_TYPE_CENTER_CROP = 6

        /**
         * 设置页存的下标 → 模式。**越界或脏值一律回落 [FILL]**：
         * 老用户没这个偏好项，读出来就是"填满"，与改动前逐字一致。
         */
        fun fromIndex(index: Int): PhotoFitMode =
            entries.firstOrNull { it.ordinal == index } ?: FILL
    }
}

/** 显示方式选项下单（等宽），下标即写进偏好的取值 */
internal val PHOTO_FIT_MODE_NAMES = listOf("填满（裁剪）", "完整显示（不裁剪）")

/**
 * 这一次刷新要用的**图片显示规格**：显示方式 + 裁剪位置 + 放大倍数。
 *
 * ## 为什么单独一个不可变对象
 *
 * 这三个值必须**同时**决定同一张位图（[PhotoBitmap.decode]）与同一个 ImageView 的属性
 * （`scaleType`）。以前它们散在各处手写一遍，就出过"自动换图的那张和手点的那张尺寸不一致"
 * 这种问题（见 [PhotoAutoAdvance.repaintPhoto]）。合成一个值对象之后，
 * 「整块重画」和「只换图片的局部刷新」走的是同一个来源，不可能再对不上。
 */
internal class PhotoFitSpec(
    val mode: PhotoFitMode,
    val position: CropPosition,
    val zoom: Float
) {
    override fun toString() = "${mode.label}/${position.name.lowercase()}/${zoom}"

    companion object {
        /** 与改动前逐字一致的规格：填满 + 居中 + 1.0 倍 */
        val LEGACY = PhotoFitSpec(PhotoFitMode.FILL, CropPosition.MIDDLE, 1.00f)
    }
}

/**
 * 显示规格的**读盘方**（偏好项）。
 *
 * ## 为什么从 widget 侧直接读、而不是加到 `Prefs.kt`
 *
 * `Prefs.kt` 与设置页在本次改动范围之外（用户明确说"设置页 UI 我自己接"）。
 * 小组件刷新是**独立进程里的广播**，读偏好必须每次现读、读到什么用什么，
 * 所以这里直接按**同一份偏好文件**（`Prefs.FILE = "timetable"`）与同一套键名取值。
 *
 * ## 接线契约（给 `Prefs.kt` / 设置页用，改一行即可）
 *
 * | 键名 | 类型 | 默认 | 取值 |
 * |---|---|---|---|
 * | [KEY_FIT_MODE] | Int | `0` | `0` 填满（裁剪，老行为）/ `1` 完整显示（不裁剪） |
 * | [KEY_CROP_POSITION] | Int | `1` | `0` 顶部 / `1` 居中（老行为）/ `2` 底部 |
 * | [KEY_ZOOM] | Int | `0` | 下标 → [ZOOM_OPTIONS] = 1.00 / 1.25 / 1.50 / 2.00 / 3.00 倍 |
 *
 * 键名与默认值都是本文件的常量，设置页只要写
 * `sp.edit().putInt(PhotoDisplayPrefs.KEY_FIT_MODE, v).apply()` 就能对上，
 * 不必再到别处抄一遍字符串。
 *
 * ## 三条不变量（单测钉住）
 *
 * 1. **键名与 `Prefs.kt` 口径一致**：三者都在 `Prefs.FILE` 这份文件里；
 * 2. **缺省时 = 老行为**（[PhotoFitSpec.LEGACY]）：没这个偏好的老用户看到的图一个字都不变；
 * 3. **脏值不许炸也不许改变画面**：越界下标回落默认值，`zoom < 1` 回落到 1.0。
 */
internal object PhotoDisplayPrefs {

    /** 与 [Prefs.FILE] 相同 —— `Prefs` 是 private 的，只能照抄字面量，单测会核对两边一致 */
    const val FILE = "timetable"

    /** 显示方式：Int，默认 0 = 填满（老行为） */
    const val KEY_FIT_MODE = "widgetPhotoFitMode"

    /** 裁剪位置：Int，默认 1 = 居中（老行为） */
    const val KEY_CROP_POSITION = "widgetPhotoCropPosition"

    /** 放大倍数下标：Int，默认 0 = 1.00 倍（老行为） */
    const val KEY_ZOOM = "widgetPhotoZoom"

    /** 默认显示方式下标（= [PhotoFitMode.FILL]） */
    const val DEFAULT_FIT_MODE = 0

    /** 默认裁剪位置下标（= [CropPosition.MIDDLE]） */
    const val DEFAULT_CROP_POSITION = 1

    /** 默认放大倍数下标（= [ZOOM_OPTIONS][0] = 1.00 倍） */
    const val DEFAULT_ZOOM = 0

    /**
     * 读一次当前的显示规格。
     *
     * 每次构建 RemoteViews 都会调它（[WidgetExtras.apply] / [PhotoAutoAdvance.repaintPhoto]），
     * 所以用户在设置页改完、调一次 [TodayWidgetProvider.refreshAll] 就能看到新样式，
     * 不需要重启、也不依赖任何缓存 —— 缓存是"改了没反应"这类问题最常见的来源。
     *
     * 这里**不写回默认值**（读操作无副作用）：老用户没选过，`contains` 为 false，
     * Android 的 `getInt(key, def)` 照样给默认值，行为一致。
     */
    fun read(context: Context): PhotoFitSpec {
        val sp = runCatching {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }.getOrNull() ?: return PhotoFitSpec.LEGACY
        return PhotoFitSpec(
            mode = PhotoFitMode.fromIndex(sp.getInt(KEY_FIT_MODE, DEFAULT_FIT_MODE)),
            position = CropPosition.fromIndex(sp.getInt(KEY_CROP_POSITION, DEFAULT_CROP_POSITION)),
            zoom = zoomFor(sp.getInt(KEY_ZOOM, DEFAULT_ZOOM))
        )
    }
}
