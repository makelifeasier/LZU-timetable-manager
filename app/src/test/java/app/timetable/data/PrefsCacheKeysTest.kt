package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「清空本地缓存」到底清了哪些 key。
 *
 * 为什么用「读源码」这种土办法：本 App 的定位是零第三方运行时依赖，
 * 测试里也就没有 SharedPreferences 的真实现（unitTests.isReturnDefaultValues = true
 * → 一律返回默认值，读不出写过的东西）。而这里要防的恰恰是**清单写错**这件事本身：
 *   - 以前漏了 week1 / manualWeek / dayOverrides（换学期后清缓存再导入，旧基准会留着）；
 *   - 还在 remove 一个根本没人写的 "rawHtml"。
 * 所以直接把 Prefs.kt 当数据源解析一遍，比对"真正在被读写的 key"与清单。
 *
 * 仓库里 test 任务的工作目录就是 app/（Gradle 的默认值），路径从那里开始算。
 */
class PrefsCacheKeysTest {

    private val source: String by lazy {
        val f = File("src/main/java/app/timetable/data/Prefs.kt")
        assertTrue("找不到 Prefs.kt，测试工作目录是 ${File(".").absolutePath}", f.isFile)
        f.readText()
    }

    /** 从源码里抓出所有"有人在读/写"的 key */
    private fun keysUsedByPrefs(): Set<String> {
        val out = LinkedHashSet<String>()
        val getters = Regex("""\b[sbile]\("([A-Za-z0-9_]+)"""")
        val setters = Regex("""\.put(?:String|Int|Long|Boolean)\("([A-Za-z0-9_]+)"""")
        for (re in listOf(getters, setters)) {
            for (m in re.findAll(source)) out += m.groupValues[1]
        }
        return out
    }

    @Test
    fun parseSawTheRealKeySet() {
        val keys = keysUsedByPrefs()
        assertTrue("解析出的 key 太少，正则大概失效了：$keys", keys.size >= 30)
        // 几个一定存在的 key，防止正则悄悄匹配不到东西还"全绿"
        listOf("week1", "manualWeek", "dayOverrides", "result", "url", "remindOn").forEach {
            assertTrue("应当解析到 key: $it", it in keys)
        }
    }

    @Test
    fun everyCacheKeyIsARealPrefsKey() {
        val used = keysUsedByPrefs()
        val bogus = Prefs.CACHE_KEYS.filterNot { it in used }
        assertEquals("清单里有根本不是 Prefs key 的条目（以前是 rawHtml）：$bogus", emptyList<String>(), bogus)
    }

    @Test
    fun cacheKeysCoverTheWeekBasisAndOverrides() {
        // 换学期/清缓存后重新导入这条链路上必须一起清掉的三个 key
        listOf("week1", "manualWeek", "dayOverrides").forEach {
            assertTrue("clearCache 应当清掉 $it", it in Prefs.CACHE_KEYS)
        }
    }

    @Test
    fun cacheKeysCoverTheParsedResultAndDiagnostics() {
        // 真正占地方的那几个：解析结果、诊断原始页面、抽出来的课表链接、同步诊断数字
        listOf("result", "diagHtml", "lastSyncAt", "loginTrace").forEach {
            assertTrue("clearCache 应当清掉 $it", it in Prefs.CACHE_KEYS)
        }
    }

    @Test
    fun cacheKeysKeepUserConfiguration() {
        // 缓存清单一旦手滑把设置项写进去，用户"清一下缓存"就丢了登录链接和外观设置
        val forbidden = listOf(
            "url", "portalUrl", "termSig", "remindOn", "remindMin",
            "dark", "style", "colorSeed", "bgType", "bgColor", "bgUri",
            "quoteEnabled", "photoEnabled", "photoUris", "greetEnabled", "animEnabled"
        )
        val leaked = forbidden.filter { it in Prefs.CACHE_KEYS }
        assertEquals("这些是用户设置/来源，不能当缓存清：$leaked", emptyList<String>(), leaked)
    }

    @Test
    fun cacheKeyListHasNoDuplicatesOrBlanks() {
        assertEquals(Prefs.CACHE_KEYS.size, Prefs.CACHE_KEYS.toSet().size)
        assertTrue(Prefs.CACHE_KEYS.none { it.isBlank() })
    }

    @Test
    fun deadRawHtmlPropertyIsGone() {
        // 第 4 条：lastRawHtml 没有任何读写者，诊断用的是 diagnosticsHtml("diagHtml")。
        // 它要是被谁加回来，这里先红 —— 提醒那个 key 没人维护。
        //
        // 判据是"源码里还有没有拿 rawHtml 当 key 用的代码"，而不是裸字符串匹配：
        // Prefs 里留了一行注释解释它为什么被删掉，那行注释里当然写着这个词，
        // 所以先把注释剥掉再看。
        val code = source.lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        assertTrue(
            "Prefs 里不该再出现读写 rawHtml 这个 key 的代码",
            !Regex("""["']rawHtml["']""").containsMatchIn(code)
        )
        assertTrue("rawHtml 这个 key 也不该再出现在清单里", "rawHtml" !in Prefs.CACHE_KEYS)
    }
}
