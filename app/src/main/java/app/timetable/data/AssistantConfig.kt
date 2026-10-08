package app.timetable.data

import android.content.Context

/**
 * AI 助手的用户配置：服务商 / API Key / 服务地址 / 模型名 / 是否已同意。
 *
 * ## 为什么单独开一个 prefs 文件，而不是加到 [Prefs]
 *
 * 两条硬理由：
 *  1. **Key 是秘密**。`Prefs.CACHE_KEYS` 的语义是"清空本地缓存"，键一旦混进 `timetable.xml`，
 *     以后任何"把 prefs 整个打出来看看"的诊断路径都会顺手把 Key 打出来 —— 单独一个文件，
 *     至少让它不落在最容易泄漏的那条路上。
 *  2. `data/PrefsCacheKeysTest` 会**读源码**比对键清单（这是本仓库既有的土办法）。
 *     不碰 Prefs，那条测试一行都不用改，也就不会因为这次改动而"顺手放宽"它的约束。
 *
 * ## Key 只由用户自己填（硬约束）
 *
 * 这里**没有任何默认 Key**，也没有"示例 Key"这一类会被人误当成能用的东西：
 *  - 首次安装后 `apiKey` 是空串，上层据此**一个请求都不发**；
 *  - [save] 的 key 参数为 null 表示"不改动"，传空串表示"清掉"；
 *  - `ai/AssistantSecretGuardTest` 会读源码，确保**非测试代码里不出现 `sk-` 字面量**。
 */
internal object AssistantConfig {

    private const val FILE = "ai_assistant"

    private const val K_PROVIDER = "provider"
    private const val K_BASE = "baseUrl"
    private const val K_MODEL = "model"
    private const val K_KEY = "apiKey"
    private const val K_CONSENT = "consented"

    /** DeepSeek 的 OpenAI 格式地址（官方文档：base_url = https://api.deepseek.com） */
    const val DEEPSEEK_BASE = "https://api.deepseek.com"

    /** 官方文档点名"模型名请使用 deepseek-flash"；`deepseek-v4-pro` 作为可选 */
    const val DEEPSEEK_MODEL = "deepseek-flash"
    const val DEEPSEEK_MODEL_PRO = "deepseek-v4-pro"

    /** 申请 Key 的地方（配置页文案里给用户看到） */
    const val KEY_PAGE = "https://platform.deepseek.com/api_keys"

    /** 自定义端点时给个默认模型名没意义（各家不同），留空让用户自己填 */
    const val CUSTOM_MODEL_HINT = ""

    /** 把地址拼成真正的请求端点 */
    private const val PATH = "/chat/completions"

    /** 服务商。"主要 DeepSeek，但不只 DeepSeek" 就落在这里：默认为 DEEPSEEK，可切到 CUSTOM */
    enum class Provider(val id: String) {
        DEEPSEEK("DEEPSEEK"),
        CUSTOM("CUSTOM");

        companion object {
            fun of(id: String?): Provider =
                entries.firstOrNull { it.id == id } ?: DEEPSEEK
        }
    }

    /** 助手相关的所有本地状态都放这一个文件（配置 + 撤销快照；都不进 `timetable.xml`） */
    internal fun store(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun sp(context: Context) = store(context)

    private fun s(context: Context, key: String, def: String = ""): String =
        sp(context).getString(key, def) ?: def

    fun provider(context: Context): Provider = Provider.of(s(context, K_PROVIDER))

    /**
     * 服务地址（不带路径）。DeepSeek 有默认值；自定义端点返回用户填的内容（可能是空串）。
     *
     * 刻意**不**在自定义时兜底成 DeepSeek：那会让"我明明填了别的服务、它却去请求 DeepSeek"
     * 变成一次静默的数据外流 —— 填错了就报错，比猜要好。
     */
    fun baseUrl(context: Context): String {
        val stored = s(context, K_BASE).trim()
        if (stored.isNotEmpty()) return stored
        return if (provider(context) == Provider.DEEPSEEK) DEEPSEEK_BASE else ""
    }

    fun model(context: Context): String {
        val stored = s(context, K_MODEL).trim()
        if (stored.isNotEmpty()) return stored
        return if (provider(context) == Provider.DEEPSEEK) DEEPSEEK_MODEL else CUSTOM_MODEL_HINT
    }

    /** 用户自己填的 Key；没填就是空串（上层因此不发请求） */
    fun apiKey(context: Context): String = s(context, K_KEY).trim()

    fun hasKey(context: Context): Boolean = apiKey(context).isNotEmpty()

    /** 首次同意（隐私说明）是否已确认。默认 false —— 没同意过就不发请求 */
    fun consented(context: Context): Boolean = sp(context).getBoolean(K_CONSENT, false)

    fun setConsented(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(K_CONSENT, value).apply()
    }

    /**
     * 保存配置。
     *
     * @param key null = 不改动 Key（只改地址/模型时用），空串 = 清掉 Key
     */
    fun save(
        context: Context,
        provider: Provider,
        baseUrl: String,
        model: String,
        key: String? = null
    ) {
        val e = sp(context).edit()
            .putString(K_PROVIDER, provider.id)
            .putString(K_BASE, baseUrl.trim())
            .putString(K_MODEL, model.trim())
        if (key != null) e.putString(K_KEY, key.trim())
        e.apply()
    }

    fun clearKey(context: Context) {
        sp(context).edit().remove(K_KEY).apply()
    }

    /** UI 上显示用的掩码：只露末 4 位。绝不返回原文 */
    fun maskedKey(context: Context): String {
        val key = apiKey(context)
        if (key.isEmpty()) return ""
        if (key.length <= 4) return "••••"
        return "••••" + key.takeLast(4)
    }

    /** 能不能发请求：填了 Key + 同意过 + 地址合法 */
    fun ready(context: Context): Boolean =
        hasKey(context) && consented(context) && validateBaseUrl(baseUrl(context)) == null

    /** 真正的请求端点 */
    fun endpoint(context: Context): String = joinEndpoint(baseUrl(context))

    /** 纯函数（可单测）：base_url → 端点；用户把 `/chat/completions` 也填进来时不重复拼 */
    fun joinEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        if (base.endsWith(PATH)) return base
        return base + PATH
    }

    /**
     * 纯函数（可单测）：校验用户填的地址，返回错误文案；null = 合法。
     *
     * **只允许 https**：本 App 的 `network_security_config.xml` 全局 `cleartextTrafficPermitted=false`
     * （只为兰大教务放行明文）。与其让用户填个 http 地址、发出去报一个看不懂的
     * `CLEARTEXT communication not permitted`，不如在这里就拦住并说清原因 ——
     * 也顺带守住"最小放行面"，不为了这个功能给 release 配置开口子。
     */
    fun validateBaseUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return "请填服务地址"
        if (!url.startsWith("https://", ignoreCase = true)) {
            return "只支持 https 地址（本 App 全局禁止明文 HTTP，只对兰大教务放行）"
        }
        val host = url.removePrefix("https://").substringBefore('/').substringBefore('?')
        if (host.isBlank() || !host.contains('.')) return "地址看起来不对，例如 https://api.deepseek.com"
        if (url.length > 200) return "地址太长"
        return null
    }
}
