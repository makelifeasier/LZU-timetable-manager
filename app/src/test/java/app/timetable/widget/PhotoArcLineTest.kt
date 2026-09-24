package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圆角那条极细弧线的颜色。
 *
 * 为什么单独测它：四角填的是组件背景色（明色主题 = 纯白）。如果照片角落的内容也偏亮
 * （白墙、天空、纸张），圆角就会"融进背景"、看着仍像直角 —— 用户实测反馈过这一条。
 * 这条线必须在**任何**填色下都能被看出来，同时又不能变成一圈明显的边框。
 *
 * 真机实测值（明色主题、填色 #FFFFFF）：弧线像素 #DEDADE ≈ 87% 白，
 * 沿对角线是 `…#FFFFFF → #DEDBDE → #3981DE（抗锯齿过渡）→ #186DDE（照片）`。
 */
class PhotoArcLineTest {

    private fun rgb(argb: Int): Triple<Int, Int, Int> =
        Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    @Test
    fun lightFillGetsADarkerLine() {
        // 明色主题的真实填色就是纯白
        val line = PhotoBitmap.arcLineArgb(0xFFFFFFFF.toInt())
        val (r, g, b) = rgb(line)
        assertTrue("浅底要往暗偏，实际 #${Integer.toHexString(line)}", r < 255 && g < 255 && b < 255)
        // 但又不能偏成一条黑边：压暗幅度应当很小（10%~20%）
        val drop = 255 - r
        assertTrue("压暗幅度 $drop 太小看不出", drop >= 20)
        assertTrue("压暗幅度 $drop 太大会变成边框", drop <= 60)
        assertEquals("弧线必须不透明（RGB_565 没有 alpha）", 0xFF, (line ushr 24) and 0xFF)
    }

    @Test
    fun darkFillGetsALighterLine() {
        val dark = 0xFF171A1F.toInt()   // 暗色主题的组件背景
        val line = PhotoBitmap.arcLineArgb(dark)
        val (r, g, b) = rgb(line)
        assertTrue("深底要往亮偏，实际 #${Integer.toHexString(line)}", r > 0x17 && g > 0x1A && b > 0x1F)
        assertTrue("不能亮到变成白边", r < 120)
        assertEquals(0xFF, (line ushr 24) and 0xFF)
    }

    @Test
    fun lineAlwaysDiffersFromTheFillItSitsOn() {
        // 中途的灰也要有可分辨的差；一条"和填色一模一样"的线等于没画
        for (v in intArrayOf(0x00, 0x40, 0x80, 0xC0, 0xFF)) {
            val fill = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            val line = PhotoBitmap.arcLineArgb(fill)
            assertNotEquals("灰度 $v 上的弧线看不出来", fill, line and 0x00FFFFFF)
            val (r, _, _) = rgb(line)
            assertTrue("差得太小", Math.abs(r - v) >= 8)
        }
    }
}
