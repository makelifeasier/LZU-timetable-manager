package app.timetable.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守着"**App 里不内置任何 Key**"这条硬约束。
 *
 * 为什么用"读源码"这种土办法：单测里没有 SharedPreferences 的真实现
 * （`unitTests.isReturnDefaultValues = true`），没法验"存没存 Key"；但这类事故的真正形态是
 * **有人把 Key 写进代码**（图省事写个默认值、调试时留一行），那件事在源码里看得一清二楚。
 * 这与 `PrefsCacheKeysTest` 的做法一致 —— 本仓库就是用这种方式防"清单/约定被悄悄改掉"。
 *
 * 只扫 `app/src/main`：debug 源码集里有一个**故意**的假 Key `sk-test-dummy`（回环自检用），
 * 它不会被编进正式包，扫它反而会让这条测试永远红。
 */
class AssistantSecretGuardTest {

    private val mainDir = File("src/main/java/app/timetable")

    private fun sourceFiles(): List<File> {
        assertTrue("找不到源码目录：${mainDir.absolutePath}（测试工作目录是 ${File(".").absolutePath}）", mainDir.isDirectory)
        return mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun noApiKeyLiteralAnywhereInShippedCode() {
        // DeepSeek/OpenAI 的 Key 形如 sk-xxxxxxxx…（≥8 位）。正式代码里出现就是事故。
        val pattern = Regex("""sk-[A-Za-z0-9_\-]{8,}""")
        val hits = sourceFiles().mapNotNull { f ->
            val text = f.readText()
            val m = pattern.find(text) ?: return@mapNotNull null
            "${f.path}: ${m.value.take(12)}…"
        }
        assertEquals("源码里出现了看着像 API Key 的字符串（Key 只能由用户自己填）", emptyList<String>(), hits)
    }

    @Test
    fun theKeyIsNeverLogged() {
        // 打日志时顺手把 apiKey 打出来是最常见的泄漏路径。Key 只允许出现在
        // AssistantClient 里构造 Authorization 的那一行，以及 AssistantConfig 的存取里。
        val offenders = ArrayList<String>()
        for (f in sourceFiles()) {
            f.readText().lineSequence().forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                val logs = code.contains("Log.") || code.contains("print")
                if (logs && (code.contains("apiKey") || code.contains("K_KEY") || code.contains("maskedKey"))) {
                    offenders += "${f.path}:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertEquals("日志里不许出现 Key（或其存取）：$offenders", emptyList<String>(), offenders)
    }

    @Test
    fun theKeyIsOnlySentInTheAuthorizationHeader() {
        val client = File("src/main/java/app/timetable/ai/AssistantClient.kt").readText()
        // 只看**代码**，不看注释：类注释里正经写着"Key 只出现在 Authorization 那一行"，
        // 连注释一起数会把守规矩的注释当成违规
        val codeLines = client.lineSequence()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
        val headerLines = codeLines.filter { it.contains("Authorization", ignoreCase = true) }.toList()
        assertEquals("Authorization 只该在构造请求头那一处出现：$headerLines", 1, headerLines.size)
        assertTrue(
            "Key 必须是请求头里的 Bearer，不能进请求体",
            headerLines[0].contains("Bearer")
        )
    }

    @Test
    fun configHasNoDefaultKeyAndExposesOnlyTheMaskedOne() {
        val config = File("src/main/java/app/timetable/data/AssistantConfig.kt").readText()
        assertFalse("默认值里不能有 Key", config.contains("DEFAULT_KEY"))
        assertFalse("不能持有 Key 的明文副本", config.contains("KEY_PLAIN"))
        // 对外只给掩码与"有没有"
        assertTrue(config.contains("fun maskedKey(context: Context): String"))
        assertTrue(config.contains("fun hasKey(context: Context): Boolean"))
        // 掩码实现里必须只取末 4 位
        assertTrue(config.contains("takeLast(4)"))
    }
}
