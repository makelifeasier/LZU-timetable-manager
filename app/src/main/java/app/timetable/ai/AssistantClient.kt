package app.timetable.ai

import android.content.Context
import android.util.Log
import app.timetable.data.AssistantConfig
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** 一次模型调用的结果 */
internal sealed interface AssistantResult {

    data class Ok(val content: String, val totalTokens: Int) : AssistantResult

    /** [message] 是给用户看的人话；[detail] 是要不要附在下面的原文片段 */
    data class Err(val message: String, val detail: String = "") : AssistantResult
}

/**
 * 模型接口。
 *
 * 抽成接口的唯一目的是**能在设备上注入假模型**：真 Key 只有用户有，而"解析 → 预览 → 应用 →
 * 撤销"整条链路必须能被验证。见 debug 源码集里的 `FakeAssistantModel`。
 */
internal interface AssistantModel {

    fun complete(system: String, user: String): AssistantResult

    /** 用户关掉对话框时调用：尽快放弃这次请求，并把迟到结果丢掉 */
    fun cancel() {}
}

/**
 * DeepSeek / OpenAI 格式的对话补全。
 *
 * ## DeepSeek 的专有字段只对 DeepSeek 发
 *
 * `thinking`（关思考）是 DeepSeek 的字段：它默认**开着**，而开着的时候 `temperature` 完全无效、
 * 还更慢更贵，所以我们一律显式关掉。但**自定义端点不发它** —— 严格的服务商收到未知字段会直接
 * 400，那会变成"换个服务就用不了"。
 *
 * ## Key 只出现在一个地方
 *
 * `Authorization` 头的那一行。请求体里没有 Key，日志里没有 Key（[TAG] 下只记端点、模型、
 * 提示词长度、HTTP 状态码与错误摘要）。`AssistantSecretGuardTest` 会读源码守住这条。
 */
internal object AssistantClient {

    const val TAG = "Assistant"

    /** 输出上限：一份计划最多几十行，1200 tokens 绰绰有余；给太大只会让截断变得更贵 */
    const val MAX_TOKENS = 1200

    /** 给用户的错误里附原文时最多带多少字 */
    private const val DETAIL_CHARS = 300

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    // ------------------------------------------------------------ 纯函数（可单测）

    /**
     * 纯函数（可单测）：请求体。
     *
     * 手写而不是用 `org.json`：单测里 `org.json` 是假的（见 [JsonMini] 的注释）。
     * 字符串转义自己来，顺序固定 —— 于是"发出去的到底是什么"能被断言。
     */
    fun buildBody(
        model: String,
        system: String,
        user: String,
        deepSeekExtras: Boolean,
        maxTokens: Int = MAX_TOKENS
    ): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"model\":").append(quote(model)).append(',')
        sb.append("\"messages\":[")
        sb.append("{\"role\":\"system\",\"content\":").append(quote(system)).append("},")
        sb.append("{\"role\":\"user\",\"content\":").append(quote(user)).append("}")
        sb.append("],")
        sb.append("\"temperature\":0.2,")
        sb.append("\"max_tokens\":").append(maxTokens.coerceIn(1, 8192)).append(',')
        sb.append("\"stream\":false")
        if (deepSeekExtras) {
            // 关思考：否则 temperature 无效、还慢还贵
            sb.append(",\"thinking\":{\"type\":\"disabled\"}")
        }
        sb.append('}')
        return sb.toString()
    }

    /**
     * 纯函数（可单测）：响应体 → 内容 / token 数 / 人话错误。
     *
     * 这里刻意把所有"服务商返回了但不是我们要的"情形分开报：截断、被内容过滤、
     * 思考模式只回了 reasoning、choices 为空 —— 它们对用户是不同的事，混成一句
     * "模型返回异常"就没人能修。
     */
    fun parseResponse(body: String): AssistantResult {
        if (body.isBlank()) return AssistantResult.Err("服务商返回了空内容", "")

        val root = JsonMini.parse(body)
            ?: return AssistantResult.Err("服务商返回的不是合法 JSON（可能被中间设备改写）", short(body))

        // 有的服务商把错误放在 200 里
        JsonMini.str(JsonMini.at(root, "error", "message"))?.let {
            return AssistantResult.Err("服务商报错：$it", "")
        }

        val choices = JsonMini.arr(JsonMini.at(root, "choices"))
        if (choices.isNullOrEmpty()) {
            return AssistantResult.Err("服务商没有返回任何候选结果", short(body))
        }
        val finish = JsonMini.str(JsonMini.at(root, "choices", 0, "finish_reason"))
        val content = JsonMini.str(JsonMini.at(root, "choices", 0, "message", "content"))
        val tokens = JsonMini.int(JsonMini.at(root, "usage", "total_tokens")) ?: 0

        when (finish) {
            "length" -> return AssistantResult.Err("模型输出被截断（超过长度限制），请把改动分几次说")
            "content_filter" -> return AssistantResult.Err("这段内容被服务商的内容策略拦下了")
            "insufficient_system_resource" -> return AssistantResult.Err("服务商算力不足，稍后再试")
            "aborted" -> return AssistantResult.Err("这次生成被中断了，再试一次")
        }

        if (content.isNullOrBlank()) {
            val reasoning = JsonMini.str(JsonMini.at(root, "choices", 0, "message", "reasoning_content"))
            return if (!reasoning.isNullOrBlank()) {
                AssistantResult.Err("模型只回了思考过程、没给最终答案，再试一次")
            } else {
                AssistantResult.Err("模型返回了空内容", short(body))
            }
        }
        return AssistantResult.Ok(content, tokens)
    }

    /**
     * 纯函数（可单测）：HTTP 状态码 → 人话。
     *
     * 401/402/422/429/500/503 是 DeepSeek 官方错误码表里的六个（402 = 余额不足，
     * 这一条必须说清"去充值"，否则用户会以为是 App 坏了）。
     */
    fun mapHttpError(code: Int, body: String, providerName: String): String = when (code) {
        400 -> "请求格式被拒绝（App 这边的问题）：${apiMessage(body) ?: "400"}"
        401 -> "$providerName 说 Key 不对或已失效，请到「AI 助手」里重新填"
        402 -> "$providerName 账户余额不足，请先充值"
        422 -> "请求参数被拒绝（App 这边的问题）：${apiMessage(body) ?: "422"}"
        429 -> "请求太频繁被限流了，等一会儿再试"
        500 -> "$providerName 服务端出错，稍后再试"
        502, 503, 504 -> "$providerName 服务繁忙，稍后再试"
        else -> "$providerName 返回 HTTP $code：${apiMessage(body) ?: short(body)}"
    }

    /** 从错误响应里挖出服务商给的那句人话 */
    fun apiMessage(body: String): String? {
        val root = JsonMini.parse(body) ?: return null
        return JsonMini.str(JsonMini.at(root, "error", "message"))
            ?: JsonMini.str(JsonMini.at(root, "message"))
    }

    private fun short(text: String): String =
        text.replace('\n', ' ').trim().take(DETAIL_CHARS)

    /** JSON 字符串转义（手写，见 [buildBody] 的注释） */
    fun quote(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    // ------------------------------------------------------------ 真正发请求

    data class Call(
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val deepSeekExtras: Boolean,
        val providerName: String,
        val system: String,
        val user: String,
        val maxTokens: Int = MAX_TOKENS
    )

    /**
     * 发一次请求。**必须在后台线程调用**（读超时 60 秒）。
     *
     * [cancelled] 由调用方在关窗时置真：这里在写请求前、读响应前各查一次，
     * 并把连接断开，尽量少占着网络与电量。
     */
    fun chat(
        call: Call,
        cancelled: () -> Boolean = { false },
        /**
         * 超时可以被覆盖：自检/联调要在几秒内跑完"读超时"这条分支，
         * 而生产默认值必须是"服务商慢的时候也等得起"的 60 秒。
         */
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): AssistantResult {
        var conn: HttpURLConnection? = null
        return try {
            val body = buildBody(
                model = call.model,
                system = call.system,
                user = call.user,
                deepSeekExtras = call.deepSeekExtras,
                maxTokens = call.maxTokens
            )
            Log.i(
                TAG,
                "请求模型=${call.model} 端点=${call.endpoint} 系统提示=${call.system.length}字 " +
                    "用户提示=${call.user.length}字 请求体=${body.length}字节"
            )

            conn = (URL(call.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "LZU-Timetable-Android")
                // Key 只出现在这一行
                setRequestProperty("Authorization", "Bearer ${call.apiKey}")
            }
            if (cancelled()) {
                runCatching { conn.disconnect() }
                return AssistantResult.Err("已取消")
            }

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { stream -> BufferedReader(stream.reader(Charsets.UTF_8)).readText() }
                .orEmpty()
            if (cancelled()) return AssistantResult.Err("已取消")

            if (code !in 200..299) {
                val msg = mapHttpError(code, text, call.providerName)
                Log.w(TAG, "HTTP $code：$msg")
                return AssistantResult.Err(msg, short(text).takeIf { code !in listOf(401, 402, 429) }.orEmpty())
            }
            return parseResponse(text)
        } catch (e: Exception) {
            val msg = when (e) {
                is java.net.SocketTimeoutException -> "请求超时（网络慢或服务商没响应）"
                is java.net.UnknownHostException -> "域名解析不了，检查网络或服务地址"
                is javax.net.ssl.SSLException -> "HTTPS 连接失败：${e.message ?: "证书问题"}"
                else -> "网络请求失败：${e.javaClass.simpleName} ${e.message ?: ""}".trim()
            }
            // 给人看的是上面那句人话；给排查看的是这一行的异常类名 ——
            // "连接超时"和"读超时"都是 SocketTimeoutException，不带类名/阶段就分不清是网络问题还是服务商不回
            Log.w(TAG, "$msg · ${e.javaClass.name}: ${e.message}")
            AssistantResult.Err(msg)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}

/**
 * 生产用的模型实现：从 [AssistantConfig] 读配置，按服务商决定发不发 DeepSeek 专有字段。
 *
 * 没填 Key 时**直接返回错误、不发请求** —— "没配就别联网"这条要落在这里，
 * 而不是靠调用方自觉。
 */
internal class ConfiguredAssistantModel(private val context: Context) : AssistantModel {

    @Volatile
    private var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    override fun complete(system: String, user: String): AssistantResult {
        val base = AssistantConfig.baseUrl(context)
        AssistantConfig.validateBaseUrl(base)?.let {
            return AssistantResult.Err(it)
        }
        val key = AssistantConfig.apiKey(context)
        if (key.isEmpty()) {
            return AssistantResult.Err("还没有填 API Key：请到「AI 助手」里填一个（只存在这台手机上）")
        }
        if (!AssistantConfig.consented(context)) {
            return AssistantResult.Err("还没有同意隐私说明，先把说明看完并同意")
        }
        val model = AssistantConfig.model(context)
        if (model.isBlank()) return AssistantResult.Err("还没有填模型名")

        val provider = AssistantConfig.provider(context)
        return AssistantClient.chat(
            AssistantClient.Call(
                endpoint = AssistantConfig.endpoint(context),
                apiKey = key,
                model = model,
                deepSeekExtras = provider == AssistantConfig.Provider.DEEPSEEK,
                providerName = if (provider == AssistantConfig.Provider.DEEPSEEK) "DeepSeek" else "服务商",
                system = system,
                user = user
            ),
            cancelled = { cancelled }
        )
    }
}
