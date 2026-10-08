package app.timetable.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迷你 JSON 解析器。
 *
 * 为什么值得给它单独一份测试：它是"服务商返回什么"与"App 怎么理解"之间唯一的翻译层。
 * 而 App 里其它地方用的 `org.json` **在 JVM 单测里是假的**（`isReturnDefaultValues = true`），
 * 所以这一层的正确性只能靠自己的用例——不然"响应解析"这件事在交付前等于没测过。
 */
class JsonMiniTest {

    private fun path(json: String, vararg p: Any): Any? =
        JsonMini.at(JsonMini.parse(json), *p)

    @Test
    fun parsesTheShapeWeActuallyGetBack() {
        val body = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,
             "choices":[{"index":0,"finish_reason":"stop",
                         "message":{"role":"assistant","content":"PLAN\nOVERRIDE 2026-10-15 CLEAR"}}],
             "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}
        """.trimIndent()

        assertEquals("stop", JsonMini.str(path(body, "choices", 0, "finish_reason")))
        assertEquals(
            "PLAN\nOVERRIDE 2026-10-15 CLEAR",
            JsonMini.str(path(body, "choices", 0, "message", "content"))
        )
        assertEquals(18, JsonMini.int(path(body, "usage", "total_tokens")))
    }

    @Test
    fun handlesEscapesAndUnicode() {
        val json = """{"a":"换行\n引号\"斜杠\\制表\t","b":"\u4e2d\u6587"}"""
        assertEquals("换行\n引号\"斜杠\\制表\t", JsonMini.str(path(json, "a")))
        assertEquals("中文", JsonMini.str(path(json, "b")))
    }

    @Test
    fun distinguishesJsonNullFromMissingField() {
        val json = """{"a":null,"b":1}"""
        // JSON 的 null 是一个"单例值"，不是 Kotlin null —— 否则没法区分"字段是 null"和"字段不存在"
        assertTrue(path(json, "a") is JsonMini.JsonNull)
        assertNull(path(json, "nope"))
    }

    @Test
    fun refusesInvalidInputInsteadOfGuessing() {
        // 截断的响应、半截 JSON、空串 —— 都必须返回 null（由上层报"服务商返回异常"）
        assertNull(JsonMini.parse(""))
        assertNull(JsonMini.parse("{"))
        assertNull(JsonMini.parse("""{"a":}"""))
        assertNull(JsonMini.parse("""{"a":1} extra"""))
        assertNull(JsonMini.parse("not json at all"))
    }

    @Test
    fun walksNestedPathsSafely() {
        val json = """{"choices":[{"message":{"content":"x"}}]}"""
        assertEquals("x", JsonMini.str(path(json, "choices", 0, "message", "content")))
        // 越界、类型不符、缺字段：一律 null，不抛异常（错误提示由上层组织成人话）
        assertNull(path(json, "choices", 5))
        assertNull(path(json, "choices", "message"))
        assertNull(path(json, "usage"))
        assertEquals(1, JsonMini.arr(pathOrNull(json, "choices"))?.size)
    }

    private fun pathOrNull(json: String, key: String): Any? = path(json, key)

    @Test
    fun readsNestedArraysAndNumbers() {
        val json = """{"a":[1,2.5,-3],"b":[[1],[2]]}"""
        assertEquals(3, JsonMini.arr(path(json, "a"))?.size)
        assertEquals(1, JsonMini.int(path(json, "a", 0)))
        assertEquals(2, JsonMini.int(path(json, "a", 1)))
        assertEquals(-3, JsonMini.int(path(json, "a", 2)))
        assertEquals(2, JsonMini.int(path(json, "b", 1, 0)))
        // 小数取整：token 数只可能是整数，但服务商真给过 "18.0"
        assertEquals(18, JsonMini.int(JsonMini.parse("18.0")))
    }
}
