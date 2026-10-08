package app.timetable.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.timetable.R
import app.timetable.ai.AssistantClient
import app.timetable.ai.AssistantResult
import app.timetable.data.AssistantConfig

/**
 * **AI 助手设置**：隐私说明 + 服务商 + API Key + 服务地址/模型名 + 测试连接。
 *
 * ## 为什么是 Dialog 而不是 AlertDialog
 *
 * 这里要放两段说明（隐私 + Key 去哪儿申请）加五个控件，`AlertDialog` 那三条按钮位
 * 装不下，标题区也没法做小字说明。所以照 [PhotoManagerDialog] 的路子自己拼 View，
 * 内容套一层 `ScrollView`：窄屏横屏时表单会被压缩，能滚才不会把「保存」挤出屏幕。
 *
 * ## 隐私说明为什么不能折进按钮里
 *
 * 「这个功能会把课名发到第三方」是用户**按下同意之前**就必须看懂的。做成一行「我已阅读」，
 * 说明文字就必须一直在同一屏里看得见（而不是收进一个「详情」里）—— 所以它就摆在勾选框上面，
 * 三句话说完：发什么、发给谁、发不到什么。
 *
 * ## Key 永不回显
 *
 * 输入框**不**填已有的 Key（哪怕掩码也不回填），只在下面一行显示 [AssistantConfig.maskedKey]。
 * 理由是"回显"这件事一旦做了，用户就没法区分"我看到的是我存的"还是"App 记住了我的 Key"，
 * 而这里要传达的恰恰是「Key 只在你手机上」。留空保存 = 不改动（`save(key = null)`）。
 */
internal class AssistantConfigDialog private constructor(
    private val host: Activity,
    /** 保存成功时回调一次（调用方据此刷新依赖配置的界面） */
    private val onSaved: () -> Unit = {}
) : Dialog(host) {

    private lateinit var consent: TextView
    private lateinit var keyField: EditText
    private lateinit var keyState: TextView
    private lateinit var baseField: EditText
    private lateinit var modelField: EditText
    private lateinit var testButton: TextView
    private lateinit var status: TextView

    /** 服务商 chip 的选中态重画（选中 = 实心强调色，与设置页 chip 一致） */
    private lateinit var paintProviders: () -> Unit

    private var provider: AssistantConfig.Provider = AssistantConfig.Provider.DEEPSEEK

    /**
     * 已勾选同意。**点一下 toggle 而不是用 CheckBox**：CheckBox 的方框是纯图形，
     * 勾没勾在浅色小字旁边几乎看不出来，用户会盯着它怀疑自己点了没点上。
     * 一颗药丸的"实心/淡底"对比度足够一眼判断。
     */
    private var agreed = false

    /** 关窗令牌：关窗之后一切迟到回调（尤其是测试连接）都不许再碰任何 View */
    private var closed = false

    /** 测试连接是否还在跑（避免连点两次；关窗时它只是不再有界面效果，请求由系统超时收尾） */
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildContentView())
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (host.resources.displayMetrics.heightPixels * 0.88f).toInt()
        )
        // 键盘弹起时压缩内容而不是盖住输入框（表单有五行，盖住 Key 行会让用户以为点错了）
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setCanceledOnTouchOutside(true)
        setOnDismissListener { closed = true }
        loadFromConfig()
    }

    // ---------------------------------------------------------------- 界面

    private fun buildContentView(): View {
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.surface))
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }

        root.addView(title("AI 助手设置", 17f))

        // ---- 隐私说明（同意之前必须看得见） ----
        root.addView(
            body(
                "这个功能会把「课程名、星期、节次、教室 + 你输入的话」发送到**你自己配置的服务商**。" +
                    "App 自己**没有服务器**：这次请求是这台手机直连服务商的。" +
                    "**不会**发送学号、姓名、班级、课表链接、登录 Cookie。"
            ).apply { setPadding(0, dp(8), 0, 0) }
        )

        consent = pill("我已阅读并同意") {
            agreed = !agreed
            paintConsent()
        }
        root.addView(
            consent,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) }
        )

        // ---- 可滚的表单区 ----
        val form = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

        form.addView(sectionTitle("服务商"))
        form.addView(buildProviderRow())

        form.addView(sectionTitle("API Key"))
        keyField = EditText(host).apply {
            hint = "粘贴你自己的 API Key"
            // 密码型：输入过程不回显。注意这里**不** setText 已有 Key（见类注释）
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_tertiary))
            background = host.getDrawable(R.drawable.bg_card_alt)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setHorizontallyScrolling(true)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) }
        }
        form.addView(keyField)

        keyState = body("")
        keyState.setPadding(dp(2), dp(6), dp(2), 0)
        form.addView(keyState)

        form.addView(
            body("只在 DeepSeek 开放平台申请：${AssistantConfig.KEY_PAGE}")
                .apply { setPadding(dp(2), dp(4), dp(2), 0) }
        )
        form.addView(
            body("Key 只存在这台手机上，App 不会上传它。")
                .apply { setPadding(dp(2), dp(2), dp(2), 6) }
        )
        form.addView(
            pill("清除已保存的 Key") { confirmClearKey() },
            LinearLayout.LayoutParams(WRAP, WRAP)
        )

        form.addView(sectionTitle("服务地址"))
        baseField = field("https://api.deepseek.com")
        form.addView(baseField)

        form.addView(sectionTitle("模型名"))
        modelField = field(AssistantConfig.DEEPSEEK_MODEL)
        form.addView(modelField)

        root.addView(
            ScrollView(host).apply {
                isFillViewport = false
                addView(
                    form,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            },
            LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) }
        )

        // ---- 按钮行 ----
        root.addView(
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    pill("取消") { dismiss() },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(6) }
                )
                addView(
                    primaryPill("保存") { save() },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(6) }
                )
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        )

        // 注意顺序：status 必须先建出来再 addView —— 这里传的是真实对象，
        // 不是"先占位后填充"（lateinit 字段在 addView 之前被读会直接抛异常）
        status = body("")
        root.addView(
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                testButton = pill("测试连接") { testConnection() }
                addView(testButton)
                addView(
                    status,
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                        marginStart = dp(8)
                        gravity = Gravity.CENTER_VERTICAL
                    }
                )
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        )
        return root
    }

    private fun buildProviderRow(): LinearLayout {
        val box = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>(2)
        val labels = listOf("DeepSeek（推荐）", "其它服务（OpenAI 格式）")
        val values = listOf(AssistantConfig.Provider.DEEPSEEK, AssistantConfig.Provider.CUSTOM)

        fun restyle() {
            views.forEachIndexed { i, v ->
                val on = values[i] == provider
                // 选中态用 bg_accent_chip / bg_pill（淡底）而不是实心：这一排本身不是"主操作"，实心会在
                // 一行里出现两块蓝底，把下面的「保存」压得不像主按钮。
                v.background = host.getDrawable(
                    if (on) R.drawable.bg_accent_chip else R.drawable.bg_pill
                )
                v.setTextColor(color(if (on) R.color.accent else R.color.text_secondary))
            }
        }

        labels.forEachIndexed { i, text ->
            val v = pill(text) {
                pickProvider(values[i])
                restyle()
            }
            views += v
            box.addView(v)
        }
        restyle()
        paintProviders = { restyle() }
        return box
    }

    // ---------------------------------------------------------------- 初值

    private fun loadFromConfig() {
        provider = AssistantConfig.provider(host)
        baseField.setText(AssistantConfig.baseUrl(host))
        modelField.setText(AssistantConfig.model(host))
        agreed = AssistantConfig.consented(host)
        paintConsent()
        paintKeyState()
        paintProviders()
    }

    private fun paintConsent() {
        consent.background = host.getDrawable(
            if (agreed) R.drawable.bg_accent_chip else R.drawable.bg_pill
        )
        consent.setTextColor(color(if (agreed) R.color.accent else R.color.text_secondary))
    }

    private fun paintKeyState() {
        keyState.text = if (AssistantConfig.hasKey(host)) {
            "已保存 ${AssistantConfig.maskedKey(host)}（留空保存 = 不改动它）"
        } else {
            "还没有 Key。没有 Key 时助手一个请求都不发。"
        }
    }

    /**
     * 换服务商。
     *
     * 只在"用户没自己改过"的前提下回填默认值：把自定义端点的地址返回时顺手覆盖成
     * `api.deepseek.com`，等于用户填了半天的东西被一次点按抹掉。所以这里的判据是
     * "是不是还等于上一家的默认值"（或干脆空着）。
     */
    private fun pickProvider(next: AssistantConfig.Provider) {
        if (next == provider) return
        val prevDefaultBase =
            if (provider == AssistantConfig.Provider.DEEPSEEK) AssistantConfig.DEEPSEEK_BASE else ""
        val prevDefaultModel =
            if (provider == AssistantConfig.Provider.DEEPSEEK) AssistantConfig.DEEPSEEK_MODEL else ""

        val baseUntouched = baseField.text.toString().trim().let {
            it.isEmpty() || it == prevDefaultBase
        }
        val modelUntouched = modelField.text.toString().trim().let {
            it.isEmpty() || it == prevDefaultModel
        }

        provider = next
        if (next == AssistantConfig.Provider.DEEPSEEK) {
            if (baseUntouched) baseField.setText(AssistantConfig.DEEPSEEK_BASE)
            if (modelUntouched) modelField.setText(AssistantConfig.DEEPSEEK_MODEL)
        }
        // 切到自定义时不动任何字段：留空由校验拦住，猜一个别的服务地址比报错更糟
    }

    // ---------------------------------------------------------------- 动作

    private fun save() {
        val base = baseField.text.toString().trim()
        val model = modelField.text.toString().trim()
        val typedKey = keyField.text.toString().trim()

        // 每个拒绝分支都留一行日志：这些提示是 Toast，用户看一眼就没了，
        // 而"点了保存没反应"到底是哪一条拦住的，事后只能靠日志回答
        Log.i(TAG, "保存被按下：同意=$agreed 已存Key=${AssistantConfig.hasKey(host)} 新填Key=${typedKey.isNotEmpty()}")

        if (!agreed && !AssistantConfig.consented(host)) {
            Log.i(TAG, "保存被拒：还没同意隐私说明")
            toast("请先阅读并同意")
            return
        }
        AssistantConfig.validateBaseUrl(base)?.let {
            Log.i(TAG, "保存被拒：地址不合法（$it）")
            toast(it)
            return
        }
        if (model.isEmpty()) {
            Log.i(TAG, "保存被拒：模型名为空")
            toast("请填模型名")
            return
        }
        if (typedKey.isEmpty() && !AssistantConfig.hasKey(host)) {
            Log.i(TAG, "保存被拒：既没填新 Key、也没有已存的 Key")
            toast("请先填 API Key")
            return
        }

        AssistantConfig.save(
            context = host,
            provider = provider,
            baseUrl = base,
            model = model,
            // null = 不改动已有 Key（用户只改地址/模型时最容易踩：空串会把 Key 清掉）
            key = typedKey.ifEmpty { null }
        )
        AssistantConfig.setConsented(host, true)
        Log.i(TAG, "AI 配置已保存：服务商=${provider.id} 模型=$model 地址=$base 新填Key=${typedKey.isNotEmpty()}")
        toast("已保存")
        dismiss()
        runCatching { onSaved() }
    }

    private fun confirmClearKey() {
        if (!AssistantConfig.hasKey(host)) {
            toast("还没有保存过 Key")
            return
        }
        AlertDialog.Builder(host)
            .setTitle("清除已保存的 Key？")
            .setMessage("清除后助手不会再发请求，直到你填一个新的。")
            .setPositiveButton("清除") { _, _ ->
                AssistantConfig.clearKey(host)
                keyField.setText("")
                paintKeyState()
                toast("已清除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 测试连接：**必须后台线程**（`AssistantClient.chat` 的读超时是 60 秒，放主线程会 ANR）。
     *
     * 用界面上**当前填的**值算端点与 Key，而不是读已存的配置 —— 用户点「测试连接」想验证的
     * 就是"我还没保存的这套值能不能用"。所以 Key 留空时回退到已存的 Key（不回显的那份）。
     */
    private fun testConnection() {
        if (testing) return
        val base = baseField.text.toString().trim()
        AssistantConfig.validateBaseUrl(base)?.let {
            status.text = it
            return
        }
        val model = modelField.text.toString().trim()
        if (model.isEmpty()) {
            status.text = "请填模型名"
            return
        }
        val key = keyField.text.toString().trim().ifEmpty { AssistantConfig.apiKey(host) }
        if (key.isEmpty()) {
            status.text = "请先填 API Key"
            return
        }

        testing = true
        testButton.isEnabled = false
        testButton.alpha = 0.5f
        testButton.text = "测试中…"
        status.text = ""
        val deepSeek = provider == AssistantConfig.Provider.DEEPSEEK
        val providerName = if (deepSeek) "DeepSeek" else "服务商"

        Thread {
            // maxTokens = 8：只要证明"这个 Key + 这个地址能换回一句话"就够了，多花钱没意义
            val result = AssistantClient.chat(
                AssistantClient.Call(
                    endpoint = AssistantConfig.joinEndpoint(base),
                    apiKey = key,
                    model = model,
                    deepSeekExtras = deepSeek,
                    providerName = providerName,
                    system = "你是测试助手，只回一个字。",
                    user = "ping",
                    maxTokens = 8
                )
            )
            host.runOnUiThread {
                // 关窗之后绝不再碰 View（Dialog 已 dismiss，改它只会留下一个不会消失的面板）
                if (closed) return@runOnUiThread
                testing = false
                testButton.isEnabled = true
                testButton.alpha = 1f
                testButton.text = "测试连接"
                status.text = when (result) {
                    is AssistantResult.Ok ->
                        "连接正常：$model（${result.totalTokens} 个 token）"

                    is AssistantResult.Err -> result.message
                }
                // 失败时只把 message 显示在状态行里：它是人话（"Key 不对""余额不足"），
                // 而 detail 是给开发者看的服务商原文，这个位置放不下也不该放
            }
        }.apply { name = "ai-test-connection"; isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- 小工具

    private fun title(text: String, size: Float): TextView = TextView(host).apply {
        this.text = text
        textSize = size
        setTextColor(color(R.color.text_primary))
    }

    private fun sectionTitle(text: String): TextView = TextView(host).apply {
        this.text = text
        textSize = 12f
        setTextColor(color(R.color.text_secondary))
        setPadding(0, dp(12), 0, dp(2))
    }

    /** 说明小字。文案里用 `**` 标记的地方在这里被摘掉 —— 不引 Markdown 库，只借它的强调写法 */
    private fun body(text: String): TextView = TextView(host).apply {
        this.text = text.replace("**", "")
        textSize = 12f
        setTextColor(color(R.color.text_secondary))
        // 说明有两段、每段两三行，行距不放开的话小字会挤成一坨。
        // 注意：TextView 上没有 `lineSpacing` 这个属性（那是 TextPaint 的），只能调这个方法
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun field(hintText: String): EditText = EditText(host).apply {
        hint = hintText
        textSize = 14f
        setTextColor(color(R.color.text_primary))
        setHintTextColor(color(R.color.text_tertiary))
        background = host.getDrawable(R.drawable.bg_card_alt)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setHorizontallyScrolling(true)
        maxLines = 1
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    /** 淡底药丸（次要动作 / 标签），和设置页、课程编辑弹窗里的是同一颗 */
    private fun pill(text: String, onClick: () -> Unit): TextView = TextView(host).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(color(R.color.text_primary))
        background = host.getDrawable(R.drawable.bg_pill)
        isClickable = true
        isFocusable = true
        setPadding(dp(12), dp(9), dp(12), dp(9))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            marginStart = dp(3); marginEnd = dp(3); topMargin = dp(4)
        }
        setOnClickListener { onClick() }
    }

    /** 实心强调色：主操作（保存 / 去填 Key）。淡底会被看成禁用态 */
    private fun primaryPill(text: String, onClick: () -> Unit): TextView = pill(text, onClick).apply {
        setTextColor(color(R.color.page_bg))
        background = host.getDrawable(R.drawable.bg_btn_primary)
    }

    private fun toast(msg: String) =
        runCatching { Toast.makeText(host, msg, Toast.LENGTH_SHORT).show() }

    private fun color(res: Int): Int = host.getColor(res)

    private fun dp(v: Int): Int = Ui.dp(host, v.toFloat())

    companion object {

        private const val TAG = "Assistant"

        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /**
         * 打开设置界面。
         *
         * @param onSaved 保存成功时回调一次（**不是**关窗回调）：用户点了保存才需要刷新
         *                依赖配置的界面；点了取消/点外面关掉就不该有任何副作用。
         */
        fun show(activity: Activity, onSaved: () -> Unit = {}) {
            runCatching { AssistantConfigDialog(activity, onSaved).show() }
                .onFailure { Log.w(TAG, "打不开 AI 设置：${it.javaClass.simpleName}: ${it.message}") }
        }
    }
}
