package app.timetable.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求体、响应解析、错误映射。
 *
 * 这三块是"和真实服务商打交道"的全部接缝，而且全都能在 JVM 上测 —— 正好用来在
 * 真机联调之前把"发出去什么、回来怎么理解"钉死。真机那边只需要验 socket 真的通了
 * （见 debug 源码集的 `FakeHttp`）。
 */
class AssistantClientTest {

    // ------------------------------------------------------------ 请求体

    @Test
    fun bodyCarriesTheModelMessagesAndSamplingKnobs() {
        val body = AssistantClient.buildBody(
            model = "deepseek-flash",
            system = "你是助手",
            user = "把这周四换成周三的课",
            deepSeekExtras = true
        )
        val root = JsonMini.parse(body)
        assertEquals("deepseek-flash", JsonMini.str(JsonMini.at(root, "model")))
        assertEquals("你是助手", JsonMini.str(JsonMini.at(root, "messages", 0, "content")))
        assertEquals("system", JsonMini.str(JsonMini.at(root, "messages", 0, "role")))
        assertEquals("把这周四换成周三的课", JsonMini.str(JsonMini.at(root, "messages", 1, "content")))
        assertEquals(0.2, JsonMini.at(root, "temperature") as Double, 0.0001)
        assertEquals(AssistantClient.MAX_TOKENS, JsonMini.int(JsonMini.at(root, "max_tokens")))
        assertEquals(false, JsonMini.at(root, "stream"))
    }

    @Test
    fun thinkingIsDisabledForDeepSeekOnly() {
        // 开着思考时 temperature 无效、还更慢更贵；但这是 DeepSeek 的字段，
        // 发给别的服务商可能直接 400（"不认这个参数"）—— 所以必须按服务商分岔
        val deepseek = AssistantClient.buildBody("m", "s", "u", deepSeekExtras = true)
        assertEquals("disabled", JsonMini.str(JsonMini.at(JsonMini.parse(deepseek), "thinking", "type")))

        val custom = AssistantClient.buildBody("m", "s", "u", deepSeekExtras = false)
        assertFalse("自定义端点不该带 DeepSeek 专有字段", custom.contains("thinking"))
    }

    @Test
    fun userTextCannotBreakOutOfTheJsonString() {
        // 用户完全可以让课名/输入里带上引号甚至 "model":"x" 这种片段；
        // 拼接不当就会变成"用户改了我们的请求体"
        val evil = "\"},\"model\":\"hacked\",\"x\":\""
        val body = AssistantClient.buildBody(
            model = "deepseek-flash",
            system = "sys",
            user = evil,
            deepSeekExtras = false
        )
        val root = JsonMini.parse(body)
        assertEquals("deepseek-flash", JsonMini.str(JsonMini.at(root, "model")))
        assertEquals(evil, JsonMini.str(JsonMini.at(root, "messages", 1, "content")))
        assertTrue("转义后不该出现裸的 model 键", body.contains("\\\"model\\\":\\\"hacked"))
    }

    @Test
    fun quoteEscapesTheDangerousCharacters() {
        assertEquals("\"a\\\"b\"", AssistantClient.quote("a\"b"))
        assertEquals("\"a\\nb\"", AssistantClient.quote("a\nb"))
        assertEquals("\"a\\\\b\"", AssistantClient.quote("a\\b"))
        assertEquals("\"\\u0001\"", AssistantClient.quote("\u0001"))
    }

    @Test
    fun keyNeverAppearsInTheRequestBody() {
        // Key 只允许出现在 Authorization 头那一处；请求体里出现即视为泄漏
        val body = AssistantClient.buildBody("m", "s", "u", deepSeekExtras = true)
        assertFalse(body.contains("Authorization"))
        assertFalse(body.contains("Bearer"))
        assertFalse(body.contains("sk-"))
    }

    // ------------------------------------------------------------ 响应

    @Test
    fun parsesANormalAnswer() {
        val r = AssistantClient.parseResponse(
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"PLAN\nOVERRIDE 2026-10-15 CLEAR"}}],
                "usage":{"total_tokens":42}}"""
        )
        assertTrue(r is AssistantResult.Ok)
        assertEquals("PLAN\nOVERRIDE 2026-10-15 CLEAR", (r as AssistantResult.Ok).content)
        assertEquals(42, r.totalTokens)
    }

    @Test
    fun everyNonStopFinishReasonIsReportedInItsOwnWords() {
        fun body(finish: String, content: String = "PLAN") =
            """{"choices":[{"finish_reason":"$finish","message":{"content":"$content"}}]}"""

        // 截断的处理最关键：一份被截断的计划如果被"应用"，就是把用户的课表改了一半
        assertTrue((AssistantClient.parseResponse(body("length")) as AssistantResult.Err).message.contains("截断"))
        assertTrue((AssistantClient.parseResponse(body("content_filter")) as AssistantResult.Err).message.contains("策略"))
        assertTrue((AssistantClient.parseResponse(body("insufficient_system_resource")) as AssistantResult.Err).message.contains("算力"))
        assertTrue((AssistantClient.parseResponse(body("aborted")) as AssistantResult.Err).message.contains("中断"))
    }

    @Test
    fun thinkingOnlyAnswersAreReportedAsSuch() {
        val r = AssistantClient.parseResponse(
            """{"choices":[{"finish_reason":"stop","message":{"content":null,"reasoning_content":"我先想想…"}}]}"""
        )
        assertTrue(r is AssistantResult.Err)
        assertTrue((r as AssistantResult.Err).message.contains("思考"))
    }

    @Test
    fun malformedAndEmptyAnswersAreReportedNotGuessed() {
        assertTrue(AssistantClient.parseResponse("") is AssistantResult.Err)
        assertTrue(AssistantClient.parseResponse("<html>502 Bad Gateway</html>") is AssistantResult.Err)
        assertTrue(AssistantClient.parseResponse("""{"choices":[]}""") is AssistantResult.Err)
        assertTrue(AssistantClient.parseResponse("""{"choices":[{"message":{"content":"   "}}]}""") is AssistantResult.Err)
    }

    @Test
    fun anErrorObjectInsideA200IsReported() {
        val r = AssistantClient.parseResponse("""{"error":{"message":"Invalid model"}}""")
        assertTrue(r is AssistantResult.Err)
        assertTrue((r as AssistantResult.Err).message.contains("Invalid model"))
    }

    // ------------------------------------------------------------ 错误码

    @Test
    fun mapsDeepSeeksErrorCodesToHumanWords() {
        // 官方错误码表：401 认证失败 / 402 余额不足 / 422 参数 / 429 限流 / 500 服务端 / 503 过载
        assertTrue(AssistantClient.mapHttpError(401, "", "DeepSeek").contains("Key"))
        assertTrue("余额不足必须说清是充值，否则用户以为 App 坏了", AssistantClient.mapHttpError(402, "", "DeepSeek").contains("余额"))
        assertTrue(AssistantClient.mapHttpError(429, "", "DeepSeek").contains("限流"))
        assertTrue(AssistantClient.mapHttpError(500, "", "DeepSeek").contains("服务端"))
        assertTrue(AssistantClient.mapHttpError(503, "", "DeepSeek").contains("繁忙"))
        assertTrue(AssistantClient.mapHttpError(422, "{}", "DeepSeek").contains("App"))
    }

    @Test
    fun errorBodyIsQuotedBackToTheUser() {
        val body = """{"error":{"message":"Invalid parameter: temperature"}}"""
        assertTrue(AssistantClient.mapHttpError(400, body, "DeepSeek").contains("temperature"))

        // 状态码我们没见过的：也要把服务商的原话带上，而不是只报一个数字
        assertTrue(AssistantClient.mapHttpError(418, body, "某某").contains("Invalid parameter"))
    }

    @Test
    fun providerNameIsUsedInTheMessage() {
        assertTrue(AssistantClient.mapHttpError(401, "", "某某服务").contains("某某服务"))
    }
}
