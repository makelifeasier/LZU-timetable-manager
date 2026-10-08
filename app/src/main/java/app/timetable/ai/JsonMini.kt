package app.timetable.ai

/**
 * 迷你 JSON 解析/取值（零依赖，纯 Kotlin）。
 *
 * ## 为什么不直接用 `org.json`
 *
 * `org.json` 是 Android 框架的一部分，**在 JVM 单测里是假的**（本仓库
 * `testOptions.unitTests.isReturnDefaultValues = true`，所有 android.jar 方法返回默认值）。
 * 而"响应怎么解析、错误怎么映射"恰恰是最需要单测的部分 —— 那块逻辑不能依赖在单测里会
 * 返回 null 的东西。自己写一个只支持标准 JSON 的小解析器（约 200 行），换来的是
 * **能在 JVM 上跑真的解析**：截断、转义、嵌套、缺字段、非法输入全都能当用例钉住。
 *
 * ## 值映射
 *
 * object → `Map<String, Any?>`；array → `List<Any?>`；string → `String`；
 * number → `Double`；true/false → `Boolean`；`null` → [JsonNull]（**不用 Kotlin null**：
 * 那样就没法区分"字段不存在"和"字段是 null"，而这两者在错误提示里要说不同的话）。
 */
internal object JsonMini {

    /** JSON 里的 null。用单例表示，才能与"解析失败/字段缺失"分开 */
    object JsonNull {
        override fun toString() = "null"
    }

    /** 解析。[text] 不是合法 JSON 时返回 null */
    fun parse(text: String): Any? {
        val p = Parser(text)
        return try {
            p.skipWs()
            val v = p.value()
            p.skipWs()
            if (!p.eof()) null else v
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- 取值

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(value: Any?): List<Any?>? = value as? List<Any?>

    fun str(value: Any?): String? = value as? String

    fun int(value: Any?): Int? = (value as? Double)?.let {
        if (it.isNaN() || it.isInfinite()) null else it.toInt()
    }

    /**
     * 按路径取值：`at(root, "choices", 0, "message", "content")`。
     * 路径上任一步不存在/类型不符 → null（**不抛异常**：错误提示要由调用方组织成人话）。
     */
    fun at(root: Any?, vararg path: Any): Any? {
        var cur: Any? = root
        for (step in path) {
            cur = when (step) {
                is String -> obj(cur)?.get(step)
                is Int -> arr(cur)?.getOrNull(step)
                else -> null
            } ?: return null
        }
        return cur
    }

    // ---------------------------------------------------------------- 解析器

    private class Parser(private val s: String) {
        private var i = 0

        fun eof(): Boolean = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            if (eof()) throw IllegalArgumentException("空")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); JsonNull }
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("$c")
            }
        }

        private fun expect(word: String) {
            if (!s.startsWith(word, i)) throw IllegalArgumentException(word)
            i += word.length
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw IllegalArgumentException("键")
                val k = string()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException(":")
                i++
                map[k] = value()
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("对象没结束")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> throw IllegalArgumentException("对象里的 ${s[i]}")
                }
            }
        }

        private fun array(): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list += value()
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("数组没结束")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> throw IllegalArgumentException("数组里的 ${s[i]}")
                }
            }
        }

        private fun string(): String {
            val sb = StringBuilder()
            i++ // "
            while (i < s.length) {
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (i >= s.length) break
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) throw IllegalArgumentException("\\u")
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("转义 $e")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            throw IllegalArgumentException("字符串没结束")
        }

        private fun number(): Double {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val text = s.substring(start, i)
            return text.toDoubleOrNull() ?: throw IllegalArgumentException("数字 $text")
        }
    }
}
