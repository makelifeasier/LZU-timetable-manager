package app.timetable.debug

import android.content.Context
import android.util.Log
import app.timetable.ai.AssistantClient
import app.timetable.ai.AssistantModel
import app.timetable.ai.AssistantResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * 自检用的**假模型**与**回环假服务**。
 *
 * 为什么必须有它：AI 助手的真 Key 只有用户有 —— 我不该（也不能）拿用户的额度去验功能。
 * 但"解析 → 预览 → 应用 → 撤销"这条链路、以及 `ai/AssistantClient` 的
 * 真实 socket 行为（Authorization 头、请求体里的字段、401/402/429/500/超时各分支）
 * 都不能靠"看起来对"就交付。所以：
 *
 *  - [FakeAssistantModel]：`complete()` 直接返回注入的文本（走的是和真模型**同一个接口**），
 *    于是"模型返回 → 解析 → 解释 → 预览 → 应用 → 撤销"整条链路都能在设备上跑真代码；
 *  - [FakeHttp]：App 进程内的回环 HTTP 服务，让**生产用的** `HttpURLConnection` 真发一次请求，
 *    并断言"发出去的东西"与"回来的东西怎么解析/怎么报错"。
 *
 * 假服务只回环、只收一条连接、只用 dummy Key（`sk-test-dummy`），**不碰任何真实服务**。
 */
internal object FakeAssistantModel {

    private const val TAG = AssistantClient.TAG

    /** 注入的下一次回复（由广播 `.AIPLAN -e reply "…"` 设置） */
    @Volatile
    var reply: String? = null

    /** 记下最后一次真正发出去的用户提示，便于自检核对提示词里有什么/没有什么 */
    @Volatile
    var lastUserPrompt: String = ""

    @Volatile
    var lastSystemPrompt: String = ""
}

/** 走"生产接口"的假模型：不发网络，直接把注入的文本当回复返回 */
internal class InjectedAssistantModel : AssistantModel {

    override fun complete(system: String, user: String): AssistantResult {
        FakeAssistantModel.lastSystemPrompt = system
        FakeAssistantModel.lastUserPrompt = user
        val text = FakeAssistantModel.reply
        Log.i(
            AssistantClient.TAG,
            "假模型被调用：系统提示=${system.length}字 用户提示=${user.length}字 " +
                "注入回复=${text?.length ?: 0}字"
        )
        return if (text.isNullOrBlank()) {
            AssistantResult.Err("自检没有注入回复（先发 broadcast .AIPLAN -e reply \"PLAN…\"）")
        } else {
            AssistantResult.Ok(text, totalTokens = 42)
        }
    }
}

/**
 * App 进程内的回环 HTTP 假服务：**只为了把生产的 HttpURLConnection 链路真跑一遍**。
 *
 * 它做三件事：
 *  1. 收一条请求，把请求行、`Authorization`（只记**长度**，不记内容）、`Content-Type`、
 *     以及请求体里是否含 `"model"` / `"thinking"` / `"temperature"` 记进日志；
 *  2. 按 [Case] 回一个响应（200 正常 / 401 / 402 / 429 / 500 / 不响应以触发读超时）；
 *  3. 断言目标分支真的走到了（由调用方看日志与自检结论）。
 */
internal object FakeHttp {

    enum class Case { OK, BAD_KEY, NO_BALANCE, RATE_LIMIT, SERVER_ERROR, TIMEOUT }

    /**
     * 裸 socket 回环自测：只用 `ServerSocket` / `Socket`，不经过 HTTP。
     *
     * 为什么要这层：HTTP 自检"卡住"时，故障可能在 HTTP 层，也可能在"这个环境里的回环本身不通"
     * ——两者看起来都是"日志停在请求那一行"。逐步打点（监听 / accept / 连上 / 读到）能把它们分开，
     * 不必靠猜。
     */
    fun rawSocketCheck(onDone: (String) -> Unit) {
        runCatching {
            val server = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
            val port = server.localPort
            Log.i(AssistantClient.TAG, "AIFAKE-RAW 已监听 127.0.0.1:$port")

            Thread {
                runCatching {
                    Log.i(AssistantClient.TAG, "AIFAKE-RAW 服务端等待 accept…")
                    val s = server.accept()
                    Log.i(AssistantClient.TAG, "AIFAKE-RAW accept 返回")
                    val line = s.getInputStream().bufferedReader().readLine()
                    Log.i(AssistantClient.TAG, "AIFAKE-RAW 服务端读到：$line")
                    s.getOutputStream().write("pong\n".toByteArray(Charsets.UTF_8))
                    s.getOutputStream().flush()
                    s.close()
                }.onFailure {
                    Log.w(AssistantClient.TAG, "AIFAKE-RAW 服务端失败 ${it.javaClass.simpleName}: ${it.message}")
                }
            }.apply { name = "raw-server"; isDaemon = true }.start()

            Thread {
                runCatching {
                    Log.i(AssistantClient.TAG, "AIFAKE-RAW 客户端连接 127.0.0.1:$port …")
                    val s = Socket()
                    s.connect(java.net.InetSocketAddress("127.0.0.1", port), 3_000)
                    Log.i(AssistantClient.TAG, "AIFAKE-RAW 客户端已连上")
                    s.getOutputStream().write("ping\n".toByteArray(Charsets.UTF_8))
                    s.getOutputStream().flush()
                    val reply = s.getInputStream().bufferedReader().readLine()
                    Log.i(AssistantClient.TAG, "AIFAKE-RAW 客户端收到：$reply")
                    s.close()
                    onDone("RAW OK")
                }.onFailure {
                    Log.w(AssistantClient.TAG, "AIFAKE-RAW 客户端失败 ${it.javaClass.simpleName}: ${it.message}")
                    onDone("RAW FAIL ${it.javaClass.simpleName}")
                }
            }.apply { name = "raw-client"; isDaemon = true }.start()
        }.onFailure {
            Log.w(AssistantClient.TAG, "AIFAKE-RAW 起不来 ${it.javaClass.simpleName}: ${it.message}")
            onDone("RAW FAIL ${it.javaClass.simpleName}")
        }
    }

    /** 只发请求、不起本地服务：给"宿主机上跑假服务（10.0.2.2）"这种联调用 */
    fun callHost(port: Int, onDone: (String) -> Unit) {
        Thread {
            val result = AssistantClient.chat(
                call = AssistantClient.Call(
                    endpoint = "http://10.0.2.2:$port/chat/completions",
                    apiKey = "sk-test-dummy",
                    model = "fake-model",
                    deepSeekExtras = true,
                    providerName = "DeepSeek",
                    system = "你是测试助手。",
                    user = "ping",
                    maxTokens = 8
                ),
                connectTimeoutMs = 5_000,
                readTimeoutMs = 10_000
            )
            val line = when (result) {
                is AssistantResult.Ok -> "OK content=${result.content.take(80)} tokens=${result.totalTokens}"
                is AssistantResult.Err -> "ERR message=${result.message}"
            }
            Log.i(AssistantClient.TAG, "AIFAKE-HOST → $line")
            onDone(line)
        }.apply { name = "host-client"; isDaemon = true }.start()
    }

    /** 一次自检的结果，日志里会打成一行行 `AIFAKE` 前缀 */
    fun run(agent: Case, readTimeoutMs: Int = 60_000, onDone: (String) -> Unit) {
        val server = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val endpoint = "http://127.0.0.1:$port/chat/completions"
        Log.i(AssistantClient.TAG, "AIFAKE 回环服务已起：$endpoint 场景=$agent")

        val serverThread = Thread {
            runCatching {
                Log.i(AssistantClient.TAG, "AIFAKE 服务端等待 accept…")
                server.accept().use { sock ->
                    Log.i(
                        AssistantClient.TAG,
                        "AIFAKE 服务端已 accept：来自 ${sock.inetAddress.hostAddress}:${sock.port}"
                    )
                    // 读超时：万一客户端连上却不发请求，这里要**报错**而不是永远挂着
                    // —— 自检卡住时"到底是没连上还是没发数据"必须能分开
                    sock.soTimeout = 8_000
                    serve(sock, agent)
                }
            }.onFailure {
                Log.w(AssistantClient.TAG, "AIFAKE 服务端异常：${it.javaClass.simpleName}: ${it.message}")
            }
        }.apply { name = "fake-openai"; isDaemon = true }
        serverThread.start()

        Thread {
            val result = AssistantClient.chat(
                call = AssistantClient.Call(
                    endpoint = endpoint,
                    apiKey = "sk-test-dummy",          // 只为验"头发对了没"，不是真 Key
                    model = "fake-model",
                    deepSeekExtras = true,             // 验"thinking 字段真的发出去了"
                    providerName = "DeepSeek",
                    system = "你是测试助手。",
                    user = "ping",
                    maxTokens = 8
                ),
                connectTimeoutMs = 3_000,
                readTimeoutMs = readTimeoutMs
            )
            val line = when (result) {
                is AssistantResult.Ok -> "OK content=${result.content.take(80)} tokens=${result.totalTokens}"
                is AssistantResult.Err -> "ERR message=${result.message} detail=${result.detail.take(80)}"
            }
            Log.i(AssistantClient.TAG, "AIFAKE RESULT 场景=$agent → $line")
            runCatching { server.close() }
            onDone(line)
        }.apply { name = "fake-openai-client"; isDaemon = true }.start()
    }

    /**
     * 收一条请求并按场景回一个响应。
     *
     * **刻意不解析请求**：这是回环假服务，它的职责只有两件 ——（1）把"客户端到底发了什么"
     * 原样记下来；（2）按场景回响应。原来的实现用 `readLine()` 严格读"请求行 + 头 + 空行 +
     * 正文"，结果在真机上卡死（客户端连上了、服务端却一直等不到完整的一行），
     * 而且卡住时日志里什么都看不到 —— 自检工具自己把自己变成了不可观测的黑盒。
     *
     * 现在改成"带超时的 read，收到多少算多少，然后照样回响应"：
     * 即使客户端一个字节都没发，服务端也会回响应，于是"HTTP 链路到底通不通"这件事
     * 与"服务端解析得对不对"**彻底解耦**。
     */
    private fun serve(sock: Socket, agent: Case) {
        val buf = ByteArray(8 * 1024)
        var got = 0
        runCatching {
            sock.soTimeout = 3_000
            got = sock.getInputStream().read(buf)
        }.onFailure {
            Log.w(AssistantClient.TAG, "AIFAKE 读请求失败：${it.javaClass.simpleName}: ${it.message}")
        }
        if (got < 0) got = 0
        val raw = String(buf, 0, got, Charsets.UTF_8)

        val headers = LinkedHashMap<String, String>()
        val lines = raw.split("\r\n", "\n")
        val requestLine = lines.firstOrNull().orEmpty()
        lines.drop(1).takeWhile { it.isNotBlank() }.forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val bodyStart = raw.indexOf("\r\n\r\n").let { if (it >= 0) it + 4 else raw.indexOf("\n\n").let { i -> if (i >= 0) i + 2 else -1 } }
        val bodyText = if (bodyStart in 0..raw.length) raw.substring(bodyStart) else ""

        val auth = headers["authorization"].orEmpty()
        Log.i(
            AssistantClient.TAG,
            "AIFAKE 收到 ${got}字节：$requestLine | Content-Type=${headers["content-type"]} | " +
                "Authorization=${if (auth.isNotEmpty()) "Bearer len=${auth.removePrefix("Bearer ").length}" else "缺"} | " +
                "正文=${bodyText.length}字 含model=${bodyText.contains("\"model\"")} " +
                "含thinking=${bodyText.contains("\"thinking\"")} " +
                "含temperature=${bodyText.contains("\"temperature\"")} " +
                "含Key=${bodyText.contains("sk-test-dummy")}"
        )

        when (agent) {
            Case.TIMEOUT -> {
                // 收下请求但不回：让客户端读超时（自检会把 readTimeout 压到 1 秒）
                Thread.sleep(4_000)
                Log.i(AssistantClient.TAG, "AIFAKE 服务端按场景故意不回响应（触发读超时）")
            }

            Case.OK -> respond(
                sock, 200, "OK",
                """
                {"id":"chatcmpl-fake","object":"chat.completion","created":1,"model":"fake-model",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant",
                 "content":"PLAN\nOVERRIDE 2026-10-15 REPLACE DAY=3\nNOTE 自检注入的计划"}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}
                """.trimIndent()
            )

            Case.BAD_KEY -> respond(sock, 401, "Unauthorized",
                """{"error":{"message":"Authentication Fails, Your api key is invalid","type":"authentication_error","code":"invalid_request_error"}}""")

            Case.NO_BALANCE -> respond(sock, 402, "Payment Required",
                """{"error":{"message":"Insufficient Balance","type":"insufficient_quota","code":"invalid_request_error"}}""")

            Case.RATE_LIMIT -> respond(sock, 429, "Too Many Requests",
                """{"error":{"message":"Rate Limit Reached","type":"rate_limit","code":"rate_limit_exceeded"}}""")

            Case.SERVER_ERROR -> respond(sock, 500, "Internal Server Error",
                """{"error":{"message":"server internal error"}}""")
        }
    }

    private fun respond(sock: Socket, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        sock.getOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
        Log.i(AssistantClient.TAG, "AIFAKE 已回响应：$code $reason ${bytes.size}字节")
    }
}
