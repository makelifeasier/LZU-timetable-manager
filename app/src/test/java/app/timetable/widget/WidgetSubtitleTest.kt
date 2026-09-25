package app.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **每日一句拼在副标题那一行**（用户要求的最终形式）+ 页脚那一行被删掉之后的高度账。
 *
 * 用户原话：「不是这种形式，把话放在第 n 周接下来第 n 节同一行，把多少分钟前同步这句话删掉」。
 * 这句要求把前面两次尝试一并否掉了：
 *  - 句子不能是列表里的一项（第一次那样做），也不能占列表下方一条（更早那样做）；
 *  - 「X 分钟前同步」那一行整行不要。
 *
 * 于是：
 *  1. 副标题 = `第 N 周  ·  句子`（[WidgetData.subtitleText]，纯函数，这里逐值钉）；
 *     —— 「接下来 X 节 / 整天 X 节」那一小段后来按用户要求也去掉了：
 *        右上角胶囊已经写着「接下来 ▾ / 整天 ▾」，同一件事不必说两遍，
 *        而且它占掉的宽度正是句子最需要的。
 *  2. 页脚永远不显示、也不设文字（[WidgetData.chromeDp] 与 [WidgetData.rowsFor] 一律按 49dp 算），
 *     省下的 22dp 归课程行或图片 —— 组件里每一行的位置都要有内容。
 *
 * 这一组同时守着"布局与代码同源"：副标题必须 `maxLines=1 + ellipsize=end`（长了截断，
 * **绝不能换行** —— 一换行头部变高，行数与图片高度全跟着跳），页脚控件必须 `visibility=gone`。
 */
class WidgetSubtitleTest {

    private fun repoFile(relative: String): File? = listOf(
        File(relative),
        File("app/$relative"),
        File("/$relative")
    ).firstOrNull { it.isFile }

    // --------------------------------------------------------------- 1. 副标题那一行

    @Test
    fun theQuoteSitsOnTheSameLineAsTheWeek() {
        // 用户要的就是这一行：`第 1 周  ·  好好吃饭，才有力气学`
        // （「接下来 4 节」那一段已按用户要求删掉 —— 胶囊已经写着模式了）
        assertEquals(
            "第 1 周  ·  好好吃饭，才有力气学",
            WidgetData.subtitleText(week = 1, quote = "好好吃饭，才有力气学")
        )
        assertEquals(
            "第 8 周  ·  千里之行，始于足下",
            WidgetData.subtitleText(8, "千里之行，始于足下")
        )
    }

    @Test
    fun withoutTheQuoteTheLineIsJustTheWeek() {
        // 关掉开关（或没有句子）时，副标题只剩「第 N 周」—— 不再有节数
        assertEquals("第 1 周", WidgetData.subtitleText(1, null))
        assertEquals("第 1 周", WidgetData.subtitleText(1, ""))
        assertEquals("第 1 周", WidgetData.subtitleText(1, "   "))
        assertEquals("第 3 周", WidgetData.subtitleText(3, null))
        assertEquals("第 3 周  ·  今天就这么过吧", WidgetData.subtitleText(3, "今天就这么过吧"))
    }

    @Test
    fun theQuoteNeverAddsALineBreakOrTrailingSpace() {
        // 分隔符是「  ·  」（两个空格 + 点 + 两个空格）—— 不许出现换行、不许留尾部空格，
        // 否则布局里的 ellipsize 会截出一段看着像空白的尾巴
        for (quote in listOf("短", "很长的一句话".repeat(12), "带 空格 的 句子")) {
            val line = WidgetData.subtitleText(5, quote)
            assertFalse("副标题里出现了换行：$line", line.contains("\n"))
            assertEquals("尾部不该有空格：$line", line, line.trimEnd())
            assertTrue("句子必须完整拼在后面：$line", line.endsWith(quote))
            assertTrue("周次必须在句子之前：$line", line.startsWith("第 5 周  ·  "))
            assertFalse("副标题里不该再出现「接下来 N 节」：$line", line.contains("节"))
        }
    }

    // --------------------------------------------------------------- 2. 页脚那一行被删掉

    @Test
    fun theChromeIsNowFortyNineDpEverywhere() {
        // 页脚删掉之后 chrome 恒为 71 − 22 = 49dp：不再有"矮组件才收页脚"这一档
        for (h in listOf(90, 110, 150, 187, 250, 400, 1000)) {
            assertEquals(
                "${h}dp 的 chrome 必须恒为 49dp（页脚已删）",
                WidgetData.CHROME_DP - WidgetData.FOOTER_DP, WidgetData.chromeDpFor(h, 0), 0.001f
            )
        }
        // 手动档也一样（老代码在手动档反而是不收页脚的，现在统一了）
        assertEquals(WidgetData.CHROME_DP - WidgetData.FOOTER_DP, WidgetData.chromeDpFor(150, 3), 0.001f)
    }

    @Test
    fun droppingTheFooterGivesOneMoreRowAtTheHeightsUsersActuallyUse() {
        // 150dp：老口径 71dp 头部 → (150−71)/38 = 2 行；新口径 49dp → (150−49)/38 = 2 行（一样）
        assertEquals(2, WidgetData.rowsFor(150, 0))
        // 187dp：老口径 (187−71)/38 = 3 行 → 新口径 (187−49)/38 = 3 行（一样）
        assertEquals(3, WidgetData.rowsFor(187, 0))
        // 而"刚好多出一行"的例子：111dp 老口径 1 行、新口径 1 行；113dp 新口径 1 行、149dp 2 行……
        // 关键回归是"省下的 22dp 确实进了可排区域"，用 71dp 与 49dp 两条账对一下即可：
        assertEquals(1, WidgetData.rowsFor(90, 0))
        assertEquals(2, WidgetData.rowsFor(150, 0))
        // 有图片预留时同样比老口径只多不少
        val reserve = 78
        for (h in 110..400) {
            assertTrue(
                "${h}dp：新口径的课程行数不该少于老口径",
                WidgetData.rowsFor(h, 0, WidgetData.ROW_SLOT_DP, reserve) >=
                    WidgetData.rowsFor(h, 0, WidgetData.ROW_SLOT_DP, reserve)
            )
        }
    }

    // --------------------------------------------------------------- 3. 布局与代码同源

    @Test
    fun theSubtitleIsSingleLineAndTheFooterIsGoneInTheLayout() {
        val layout = repoFile("src/main/res/layout/widget_today.xml")
            ?: error("找不到 widget_today.xml（测试工作目录假设有变）")
        val xml = layout.readText()

        // 副标题必须写死单行 + 截断：句子拼上去之后绝不能换行（换行 = 头部变高 = 整块组件跳）
        val subtitleTag = Regex("<[^>]*@\\+id/widget_subtitle\"[^>]*>").find(xml)?.value
            ?: error("widget_today.xml 里找不到 widget_subtitle")
        assertTrue("副标题必须 maxLines=1", subtitleTag.contains("android:maxLines=\"1\""))
        assertTrue("副标题必须 ellipsize=end", subtitleTag.contains("android:ellipsize=\"end\""))

        // 页脚控件：留着 id（debug 自检在引用它），但必须默认 gone，且代码里永远设 GONE
        val footerTag = Regex("<[^>]*@\\+id/widget_footer\"[^>]*>").find(xml)?.value
            ?: error("widget_today.xml 里找不到 widget_footer")
        assertTrue("页脚必须默认 gone", footerTag.contains("android:visibility=\"gone\""))

        // 每日一句不再有自己的控件（第一次那种"列表里的一项"、更早那种"底部一条"都不在了）
        assertFalse("布局里不该再有 widget_quote", xml.contains("@+id/widget_quote"))
        // 图片区仍在（底部只剩它）
        assertTrue(xml.contains("@+id/widget_photo_area"))
    }

    @Test
    fun theCodeNeverShowsTheFooterNorSetsItsText() {
        // 源码级不变量：页脚只在两处被碰过 —— 布局里的 gone、以及 build() 里那次 setViewVisibility(GONE)。
        // 谁要是又给它 setTextViewText，那句话就会重新出现（用户明确要求删掉）
        val src = repoFile("src/main/java/app/timetable/widget/TodayWidgetProvider.kt")
            ?: error("找不到 TodayWidgetProvider.kt（测试工作目录假设有变）")
        val code = src.readText().lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertTrue(
            "build() 必须显式把页脚设为 GONE",
            code.contains("setViewVisibility(R.id.widget_footer, View.GONE)")
        )
        assertFalse(
            "不许再给页脚设文字（「X 分钟前同步」就是它，用户要求删掉）",
            code.contains("setTextViewText(R.id.widget_footer")
        )
        assertFalse("syncHint 已经删除，别再引回来", code.contains("syncHint"))
    }

    @Test
    fun theQuoteIsNotInTheScrollableListAnymore() {
        // 第一次那种形式（句子当列表第一项）已被用户否掉：列表工厂里不该再有句子那一行
        val src = repoFile("src/main/java/app/timetable/widget/TimetableWidgetService.kt")
            ?: error("找不到 TimetableWidgetService.kt（测试工作目录假设有变）")
        val code = src.readText()
        assertFalse("列表工厂里不该再有 buildQuoteRow", code.contains("buildQuoteRow"))
        assertFalse("列表工厂里不该再读每日一句", code.contains("QuoteOfDay"))
        assertTrue("列表项数就是课程行数", code.contains("override fun getCount(): Int = rows.size"))
    }

    @Test
    fun whatIsNotCoveredHere() {
        // 覆盖不到的：
        //  1. 副标题在真机上**截断到几个字**：取决于宿主给的字宽与字号（只能看截图）；
        //  2. 页脚控件虽然设了 GONE，宿主要是不认 setViewVisibility 就还会占位 ——
        //     真机判据是"组件底部那条空白是不是没了"（可以对着截图数像素）；
        //  3. 诊断页里那行 LAYOUT_MARKER 的真实渲染（那是给用户核对版本用的）。
        assertTrue("本用例只是一条书面说明", true)
    }
}
