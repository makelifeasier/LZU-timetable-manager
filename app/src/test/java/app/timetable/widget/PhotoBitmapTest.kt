package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 图片解码尺寸的计算（纯函数部分）。
 *
 * 为什么这一组必须钉住：改图片传输方式的根因是"启动器进程读不到我们的私有文件"，
 * 换成 `setImageViewBitmap` 之后，**每一张图都要跟着 RemoteViews 一起过 binder**，
 * 而 binder 单次事务上限就是 1MB —— 超了整次更新会被系统丢掉，表现是
 * "点刷新没反应/桌面一直停在旧内容"，在真机上极难定位。
 *
 * 所以这里守两条线：
 *  1. **按显示尺寸解码**（不多解一个像素）；
 *  2. 像素总数有硬上限（RGB_565 每像素 2 字节，见 [PhotoBitmap.MAX_BITMAP_BYTES]）。
 *
 * 另外守一条观感线：`inSampleSize` 取"解码后仍然不小于目标"的那一档 ——
 * 解小了会让宿主把图放大，画面直接糊掉。
 *
 * 单测里 Android API 全是"返回默认值"的假实现，所以这里只测不碰 Bitmap/Context 的算术。
 */
class PhotoBitmapTest {

    // ------------------------------------------------------ 目标像素

    @Test
    fun targetPixelsFollowTheDisplaySize() {
        // 模拟器实测：内容宽 226dp × 图片框 42dp，density 2.625
        val p = PhotoBitmap.targetPx(226, 42, 2.625f)
        assertEquals(594, p[0])   // ceil(226 × 2.625) = 594
        assertEquals(111, p[1])   // ceil(42 × 2.625) = 111
        // 594×111×2B ≈ 129KB —— 远在 400KB 预算内，不需要再压
        assertTrue(p[0] * p[1] * PhotoBitmap.BYTES_PER_PIXEL <= PhotoBitmap.MAX_BITMAP_BYTES)
    }

    @Test
    fun targetPixelsAreClampedToTheBinderBudget() {
        // 高密度 + 大图片框：1440×600 的 RGB_565 是 1.7MB，直接超 binder 上限
        val p = PhotoBitmap.targetPx(360, 150, 4f)
        val bytes = p[0].toLong() * p[1].toLong() * PhotoBitmap.BYTES_PER_PIXEL
        assertTrue("解码后的字节数必须留在预算内（否则整次更新会被丢掉）", bytes <= PhotoBitmap.MAX_BITMAP_BYTES)
        // 超预算时是"等比缩小"，不是压扁：宽高比基本保持不变
        val ratio = p[0].toFloat() / p[1]
        assertTrue("宽高比不能被压变形：$ratio", Math.abs(ratio - 2.4f) < 0.05f)
    }

    @Test
    fun targetPixelsNeverCollapseToZero() {
        // 尺寸取不到（部分启动器不上报）时不能算出 0×0：Bitmap 建不出来，图就永远不显示
        val unknown = PhotoBitmap.targetPx(0, 0, 3f)
        assertTrue("未知尺寸也不能给出 0：${unknown.toList()}", unknown[0] >= 1 && unknown[1] >= 1)
        // density 取到 0（理论上不该发生）时同样不能塌成 0
        val noDensity = PhotoBitmap.targetPx(226, 42, 0f)
        assertTrue("density=0 也不能给出 0：${noDensity.toList()}", noDensity[0] >= 1 && noDensity[1] >= 1)
    }

    @Test
    fun budgetConstantsStayConsistent() {
        assertEquals(
            PhotoBitmap.MAX_BITMAP_BYTES / PhotoBitmap.BYTES_PER_PIXEL,
            PhotoBitmap.MAX_PIXELS
        )
        // 留一半余量给 RemoteViews 的其它指令与 parcelled 头部
        assertTrue(PhotoBitmap.MAX_BITMAP_BYTES <= 512 * 1024)
    }

    @Test
    fun theBudgetIsThreeHundredKilobytesBecauseRealDeviceBitmapsWereLargerThanEstimated() {
        // 真机事故：917×137 的图日志里是 491KB（ARGB_8888），而按 2 字节/像素估是 251KB。
        // 预算因此压到 300KB —— binder 上限约 1MB，位图只是 RemoteViews 里的**一项**。
        assertEquals(300 * 1024, PhotoBitmap.MAX_BITMAP_BYTES)
        // 但不能紧到把最常见的那个框（4 列 × 2 行 → 917×137 的 RGB_565 = 245KB）判出去
        assertTrue(
            "917×137 的 RGB_565 必须塞得进预算",
            917 * 137 * PhotoBitmap.BYTES_PER_PIXEL <= PhotoBitmap.MAX_BITMAP_BYTES
        )
    }

    @Test
    fun decodeEnforcesRgb565AndJudgesByTheRealByteCount() {
        // `inPreferredConfig = RGB_565` 只是**建议**：源图带 alpha 通道时（PNG/相册截图），
        // 解码器照样给 ARGB_8888 —— 这就是真机上"图明明不大、载荷却翻倍"的原因，
        // 也是"小组件无法显示"（整次 RemoteViews 更新被 binder 丢掉）的直接来源。
        //
        // 单测里没有真 Bitmap（Android API 全是"返回默认值"的假实现），所以这里守的是
        // **源码级不变量**：解码后必须有一步强制转 RGB_565，预算必须按真实 byteCount 判。
        // 真正的验证在真机日志的 `config=` 与 `byteCount=` 两列上。
        val src = listOf(
            File("src/main/java/app/timetable/widget/PhotoBitmap.kt"),
            File("app/src/main/java/app/timetable/widget/PhotoBitmap.kt")
        ).firstOrNull { it.isFile } ?: error("找不到 PhotoBitmap.kt（测试工作目录假设有变）")
        val text = src.readText()
        assertTrue(
            "解码后必须强制统一成 RGB_565（copy 的那一步）",
            text.contains("copy(Bitmap.Config.RGB_565")
        )
        assertTrue(
            "预算兜底必须按真实 byteCount 判，而不是按「像素数 × 2」估算",
            text.contains("src.byteCount") && text.contains("fitBudget")
        )
        assertTrue("日志里必须打真实像素格式", text.contains("config?.name"))
    }

    // ------------------------------------------------------ inSampleSize

    @Test
    fun sampleSizeKeepsTheDecodedImageAtLeastAsLargeAsTheTarget() {
        // 1600×1066 的相册图、目标是 594×111：
        // 降 2 倍后是 800×533（仍 ≥ 目标 ✓），降 4 倍就只有 400 宽（< 594 ✗）→ 只能取 2
        assertEquals(2, PhotoBitmap.sampleSizeAtLeast(1600, 1066, 594, 111))
        // 原图本来就不比目标大 → 不降采样
        assertEquals(1, PhotoBitmap.sampleSizeAtLeast(600, 400, 594, 111))
        // 大图可以降得狠一点，但同样不许降到目标以下
        assertEquals(4, PhotoBitmap.sampleSizeAtLeast(4000, 3000, 594, 111))
    }

    @Test
    fun sampleSizeIsAlwaysAPowerOfTwoAndNeverZero() {
        // BitmapFactory 只接受 2 的幂；给 0 会被当成 1（多余的解码开销）
        for (w in listOf(1, 37, 600, 1600, 4000, 8000)) {
            val s = PhotoBitmap.sampleSizeAtLeast(w, w, 100, 100)
            assertTrue("inSampleSize 必须是正的 2 的幂：$s", s >= 1 && (s and (s - 1)) == 0)
        }
        // 取不到源尺寸 / 目标非法时的兜底
        assertEquals(1, PhotoBitmap.sampleSizeAtLeast(0, 0, 594, 111))
        assertEquals(1, PhotoBitmap.sampleSizeAtLeast(1600, 1066, 0, 0))
    }

    // ------------------------------------------------------ 框与位图必须同比例

    @Test
    fun decodedPixelsKeepTheFrameAspectRatio() {
        // 本轮的核心（用户："显示窗口和图片大小不匹配"）：解码尺寸 = **框的真实尺寸**。
        // 典型窄格子：内容宽 126dp → 16:9 的框高 71dp，density 2.625
        val boxW = 126
        val boxH = WidgetData.photoHeightForWidthDp(boxW)
        assertEquals(71, boxH)

        val p = PhotoBitmap.targetPx(boxW, boxH, 2.625f)
        assertEquals(331, p[0])   // ceil(126 × 2.625) = 331
        assertEquals(187, p[1])   // ceil(71 × 2.625) = 187

        // 位图比例必须≈框比例（差的这一点只是 dp→px 的向上取整）：
        // 比例一致，宿主的 centerCrop 就裁不到东西 —— 既不会拉伸，也不会露缝
        val frameRatio = boxW.toFloat() / boxH
        val bitmapRatio = p[0].toFloat() / p[1]
        assertTrue(
            "框 $frameRatio vs 位图 $bitmapRatio 不该差出一个像素以上",
            Math.abs(frameRatio - bitmapRatio) < 0.01f
        )
        // 体积：331×187×2B ≈ 121KB，离 400KB 预算还有三倍余量
        assertTrue(p[0] * p[1] * PhotoBitmap.BYTES_PER_PIXEL <= PhotoBitmap.MAX_BITMAP_BYTES)
    }

    @Test
    fun worstCaseFrameStaysInsideTheBinderBudget() {
        // 最坏情况（超宽组件撞上高度上限 + 高密度）：内容宽 296dp、框高 150dp、density 2.625
        // → 777×394 是 612KB，必须等比缩小才过得了 binder
        val p = PhotoBitmap.targetPx(296, 150, 2.625f)
        val bytes = p[0].toLong() * p[1].toLong() * PhotoBitmap.BYTES_PER_PIXEL
        assertTrue("$bytes 字节超了预算（超了整次更新会被系统丢掉）", bytes <= PhotoBitmap.MAX_BITMAP_BYTES)
        // 缩小是**等比**的：比例一变，框和图又不匹配了
        val ratio = p[0].toFloat() / p[1]
        assertTrue("等比缩小不能改掉宽高比：$ratio", Math.abs(ratio - 296f / 150f) < 0.02f)
    }

    // ------------------------------------------------------ 日志里能读的数字

    @Test
    fun labelsGiveReadableNumbersForTheLog() {
        // 用户看不到画面，只能看日志：尺寸 / 体积 / 比例都要能一眼读出来
        assertEquals("1.77:1", PhotoBitmap.ratioLabel(126, 71))
        assertEquals("2.50:1", PhotoBitmap.ratioLabel(300, 120))
        // 尺寸取不到时不给假的默认值，直接问号
        assertEquals("?", PhotoBitmap.ratioLabel(0, 71))
        assertEquals("?", PhotoBitmap.ratioLabel(126, 0))
        // 体积按最接近的 KB 报（121KB 的位图不会显示成 0KB）
        assertEquals("118KB", PhotoBitmap.sizeLabel(121_000))
        assertEquals("1KB", PhotoBitmap.sizeLabel(600))
    }

    // ------------------------------------------------------ 与声明尺寸一致

    @Test
    fun declaredMinWidthMatchesTheCodeConstant() {
        val f = listOf(
            File("src/main/res/xml/widget_today_info.xml"),
            File("app/src/main/res/xml/widget_today_info.xml")
        ).firstOrNull { it.isFile } ?: error("找不到 widget_today_info.xml（测试工作目录假设有变）")
        val minWidth = Regex("android:minWidth=\"(\\d+)dp\"").find(f.readText())
            ?.groupValues?.get(1)?.toInt() ?: error("widget_today_info.xml 里没有 android:minWidth")
        // 取不到格子宽度时，图片解码目标按这个声明值估 —— 两边必须一致
        assertEquals(WidgetData.DECLARED_MIN_WIDTH_DP, minWidth)
    }
}
