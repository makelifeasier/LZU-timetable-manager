package app.timetable.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.timetable.R
import app.timetable.ai.AssistantApplier
import app.timetable.ai.AssistantInterpreter
import app.timetable.ai.AssistantModel
import app.timetable.ai.AssistantPlanParser
import app.timetable.ai.AssistantPrompt
import app.timetable.ai.AssistantReply
import app.timetable.ai.AssistantResult
import app.timetable.ai.AssistantUndo
import app.timetable.ai.ConfiguredAssistantModel
import app.timetable.data.AssistantConfig
import app.timetable.data.Prefs
import app.timetable.data.TimetableRepository
import app.timetable.data.WeekCalc
import java.time.LocalDate

/**
 * **AI 助手对话**：说一句人话 → 模型给一份计划 → 预览 → 应用（可撤销）。
 *
 * 界面骨架、后台线程 + 令牌作废、关窗后不碰 View 这几条都照 [PhotoManagerDialog] 的路子，
 * 这里只说明**为什么这么排**。
 *
 * ## 为什么计划必须"先预览再应用"
 *
 * 模型输出的是文本，而文本要变成对课表的写入。中间那道**解释 + 预览**是用户唯一的检查点：
 * `AssistantInterpreter.renderPreview` 给出的是"那一天改前几节 → 改后几节、改后有哪些课"，
 * 而不是"我执行了 3 条命令"。用户判断的是"那天到底上什么课"，所以预览也按天给。
 * 应用之后还能一键撤销（[AssistantUndo]），两条加起来才敢让一次自然语言改动落盘。
 *
 * ## 没配 Key 时一个请求都不发
 *
 * 这是产品约束，不是技术约束：`ConfiguredAssistantModel` 自己也会拦一道，但界面**先**拦，
 * 因为"你点了发送、转了三秒、然后告诉你没配 Key"是最差的体验。所以 `!ready()` 时
 * 消息区只有一条说明 + 一个「去填 API Key」。
 *
 * ## 线程
 *
 * `model.complete(...)` 是阻塞网络调用（60 秒读超时），只能在后台线程。回主线程之后
 * **第一件事是查 [closed]**：用户完全可能在思考中关窗，而关窗之后 `dismiss()` 过的
 * Dialog 上再 addView，轻则留下一个永远不消失的面板，重则 NPE。
 */
internal class AssistantDialog private constructor(
    private val host: Activity,
    /** 生产实现；测试注入假模型（见 `AssistantModel` 的注释） */
    private val model: AssistantModel,
    /** 任何"数据被改过"或"对话结束"之后回调一次，调用方据此刷新课表/设置页 */
    private val onDone: () -> Unit = {}
) : Dialog(host) {

    // ---- 消息区 ----
    private lateinit var scroller: ScrollView
    private lateinit var messages: LinearLayout

    // ---- 底部（没配 Key 时整块隐藏） ----
    private lateinit var bottom: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendButton: TextView
    private lateinit var statusLine: TextView
    private lateinit var undoButton: TextView

    // ---- 结果区（有计划时才显示） ----
    private lateinit var resultBox: LinearLayout
    private lateinit var resultLines: LinearLayout
    private lateinit var resultButtons: LinearLayout

    /** 关窗令牌：为真之后一切迟到回调直接丢弃 */
    private var closed = false

    /** 正在等模型回答（发送按钮在此期间禁用，避免连点发出两次请求） */
    private var thinking = false

    /** 当前这一轮发给模型的文本（「再试一次」要原样重发，也不能让用户再打一遍） */
    private var sentText = ""

    /**
     * 迷你多轮上下文：模型反问（`ASK:`）之后，用户答的那句话本身没有主谓宾
     * （"下周三"），单发过去模型不知道在说什么。所以把之前的问答拼进 user 文案。
     *
     * 只留最近 [MAX_CONTEXT_CHARS] 字：拼多了会让每轮都变贵，而"刚才说了什么"只需要最近几句。
     */
    private val turns = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content = buildContentView()
        setContentView(content)
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // 键盘弹出时把内容压上去。
        //
        // **只写 SOFT_INPUT_ADJUST_RESIZE 是不够的**：targetSdk 35 起系统强制 edge-to-edge，
        // 这个标志会被忽略。实测（Pixel_7 / API 37）：键盘弹出后输入框仍在 y=2158，
        // 正好被键盘盖住 —— 点"发送"落在键盘上，功能等于不可用。
        // 所以这里自己处理 IME inset：窗口铺满，按 IME 高度把底部内边距顶起来，
        // 让消息区（weight=1）自己变矮、输入行永远在键盘上方。
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window?.setDecorFitsSystemWindows(false)
        val base = intArrayOf(
            content.paddingLeft, content.paddingTop,
            content.paddingRight, content.paddingBottom
        )
        content.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            v.setPadding(
                base[0] + bars.left,
                base[1] + bars.top,
                base[2] + bars.right,
                base[3] + maxOf(ime, bars.bottom)
            )
            insets
        }
        content.requestApplyInsets()
        setCanceledOnTouchOutside(true)
        setOnDismissListener {
            closed = true
            // 顺手掐掉还在跑的请求：用户在等 20 秒的回复时关窗，不该继续占着网络与电量
            runCatching { model.cancel() }
            runCatching { onDone() }
        }
        refreshUndoButton()
        if (!AssistantConfig.ready(host)) showNotConfigured()
    }

    // ---------------------------------------------------------------- 界面

    private fun buildContentView(): View {
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.surface))
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        // ---- 标题行：右边那行小字是"现在用的是哪个模型"，用户换过之后能一眼确认 ----
        root.addView(
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(title("AI 助手"))
                addView(
                    body(AssistantConfig.model(host).ifBlank { "未配置模型" }, 11f).apply {
                        gravity = Gravity.END
                    },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { gravity = Gravity.CENTER_VERTICAL }
                )
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )

        // ---- 消息区（占满剩余高度，自己滚） ----
        messages = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        scroller = ScrollView(host).apply {
            isFillViewport = true
            addView(
                messages,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(scroller, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // ---- 状态行 ----
        statusLine = body("", 11f).apply { visibility = View.GONE }
        root.addView(statusLine, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- 结果区 ----
        resultLines = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        resultButtons = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        resultBox = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            // 计划内容自成一块浅底卡片：不加这一层，"预览"会和聊天消息混成一片，用户分不清
            // 哪几行是"将要发生的改动"
            background = host.getDrawable(R.drawable.bg_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(resultLines, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(
                resultButtons,
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
            )
            visibility = View.GONE
        }
        root.addView(resultBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

        // ---- 底部：输入区 + 撤销（没配 Key 时整块隐藏，由 showNotConfigured() 负责） ----
        // 注意顺序：input / sendButton / undoButton 都在 addView **之前**建好并赋值，
        // 这样后面给 input 挂 TextWatcher 时字段已指向真实控件（lateinit 字段在读之前必须已赋值）
        input = EditText(host).apply {
            hint = "例如：10 月 15 日按周三的课表上"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_tertiary))
            background = host.getDrawable(R.drawable.bg_card_alt)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxLines = 3
            setHorizontallyScrolling(false)
        }
        sendButton = primaryPill("发送") { send() }
        undoButton = pill("撤销上次 AI 改动") { confirmUndo() }

        val inputRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
            addView(sendButton)
        }
        bottom = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            addView(inputRow, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(undoButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        }
        root.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))

        // 输入框的可用性完全由"有没有字"决定（空文本发送只会骗模型）
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = paintSend()
        })
        paintSend()
        return root
    }

    // ---------------------------------------------------------------- 消息

    /**
     * 追加一条气泡。
     *
     * 一律 `post { fullScroll(FOCUS_DOWN) }` 而不是直接滚：刚 addView 的视图还没测量，
     * 此时滚动位置是按旧高度算的，会停在"差一行"的地方 —— 用户看到的是自己那句话被切掉一半。
     */
    private fun addBubble(text: String, mine: Boolean): View? {
        if (text.isBlank()) return null
        val bubble = TextView(host).apply {
            this.text = text
            textSize = 13f
            setTextColor(color(R.color.text_primary))
            background = host.getDrawable(
                if (mine) R.drawable.bg_accent_chip else R.drawable.bg_pill
            )
            setPadding(dp(12), dp(9), dp(12), dp(9))
            maxWidth = (host.resources.displayMetrics.widthPixels * 0.82f).toInt()
        }
        messages.addView(
            bubble,
            LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = if (mine) Gravity.END else Gravity.START
                topMargin = dp(5)
            }
        )
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
        return bubble
    }

    /** 一条小字说明（原文片段、detail 这类"要能读到但不该抢主角"的内容） */
    private fun addFootnote(text: String) {
        if (text.isBlank()) return
        messages.addView(
            body(text, 11f).apply { setPadding(dp(4), dp(2), dp(4), 0) },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    /** 在消息流最后挂一个次要按钮（「再试一次」「去改 Key」这种只在这一轮有意义的动作） */
    private fun addInlineButton(text: String, onClick: () -> Unit): View {
        val button = pill(text, onClick)
        messages.addView(
            button,
            LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.START
                topMargin = dp(4)
            }
        )
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
        return button
    }

    private fun setStatus(text: String) {
        statusLine.text = text
        statusLine.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- 没配 Key

    /**
     * 未配置时只放一条说明 + 一个入口。**不放大段教程**：用户此刻只有一件事要做，
     * 把"怎么申请 Key"铺在这里只会让人以为这个功能很麻烦。
     */
    private fun showNotConfigured() {
        bottom.visibility = View.GONE
        val guide = ArrayList<View>(2)
        addBubble("先在设置里填一个你自己的 API Key —— 我没法替你带一个。", false)?.let { guide += it }
        guide += addInlineButton("去填 API Key") {
            AssistantConfigDialog.show(host) {
                // 填完回来重新判断：Key 可能刚填上，也可能用户什么都没改就关了
                if (!closed) {
                    // 把上面那两行"还没配 Key"的引导**撤掉**：留着的话界面会同时说
                    // "还没填 Key"和"可以开始说话了"，用户不知道该信哪一句。
                    // （实测就是这么冒出来的：配完 Key 回来，旧气泡与输入框并排躺着。）
                    guide.forEach { runCatching { messages.removeView(it) } }
                    bottom.visibility = View.VISIBLE
                    paintSend()
                    refreshUndoButton()
                    onDone()
                }
            }
        }
    }

    // ---------------------------------------------------------------- 发送

    private fun paintSend() {
        val has = input.text.toString().isNotBlank()
        val on = has && !thinking
        sendButton.isEnabled = on
        sendButton.alpha = if (on) 1f else 0.45f
    }

    private fun send() {
        if (thinking) return
        var text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (text.length > MAX_INPUT_CHARS) {
            // 截断而不是报错：用户想说的话已经说了，让他删到 500 字再发一次是纯粹的阻力
            text = text.take(MAX_INPUT_CHARS)
            toast("一次最多 $MAX_INPUT_CHARS 字，已截断")
        }
        sentText = text
        addBubble(text, true)
        input.setText("")

        val result = TimetableRepository.result()
        if (result.sessions.isEmpty()) {
            // 没课表就没法把"第几节"换算成行序，解释器也拦了这一条；这里先拦是为了不白花一次请求
            addBubble("还没有课表，先登录同步一次再改", false)
            return
        }

        val week1 = Prefs.week1MondayDate()
        val ctx = AssistantPrompt.Ctx(
            today = LocalDate.now(),
            term = result.term,
            week1Monday = week1,
            currentWeek = week1?.let { WeekCalc.weekOf(LocalDate.now(), it) } ?: 0,
            result = result
        )

        thinking = true
        paintSend()
        setStatus("正在思考…")
        // 注意：**必须**把上面那份 ctx 拼进去。只把用户那句话发出去的话，模型看不到
        // 今天几号、现在第几周、节次表长什么样、这周有什么课 —— 它只能瞎猜日期与星期几。
        // （这条是设备自检抓出来的：日志里"用户提示=4字"一眼就不对。）
        val user = AssistantPrompt.user(buildUserText(text), ctx)
        Thread {
            val answer = runCatching { model.complete(AssistantPrompt.system(), user) }
                .getOrElse { AssistantResult.Err("请求失败：${it.javaClass.simpleName} ${it.message ?: ""}".trim()) }
            host.runOnUiThread {
                // 关窗之后结果一律丢弃：Dialog 已 dismiss，任何 addView 都会留在看不见的画布上
                if (closed) return@runOnUiThread
                thinking = false
                paintSend()
                setStatus("")
                onAnswer(answer)
            }
        }.apply { name = "ai-complete"; isDaemon = true }.start()
    }

    /**
     * 把"之前的问答"拼进这一轮的用户消息。
     *
     * 位置在**用户这句话之前**：`AssistantPrompt.user` 最后一段是「【用户说的】」，
     * 模型读到最后一句时，之前的事实与上下文都已经在前面了 —— 把上下文塞在这一句**后面**
     * 会变成"用户说着说着自己开始补背景"，模型更容易忽略最后那句真需求。
     */
    private fun buildUserText(request: String): String {
        if (turns.isEmpty()) return request
        val context = turns.joinToString("\n").takeLast(MAX_CONTEXT_CHARS)
        return "（之前的对话：\n$context）\n\n$request"
    }

    // ---------------------------------------------------------------- 处理回答

    private fun onAnswer(answer: AssistantResult) {
        when (answer) {
            is AssistantResult.Err -> {
                addBubble(answer.message, false)
                if (answer.detail.isNotBlank()) {
                    addFootnote("服务商原文：" + answer.detail.take(DETAIL_CHARS))
                }
                // 401 那类的文案里有「Key」：与其让用户自己找设置在哪，不如就地给个入口
                if (answer.message.contains("Key")) {
                    addInlineButton("去改 Key") {
                        AssistantConfigDialog.show(host) { onDone() }
                    }
                }
            }

            is AssistantResult.Ok -> {
                when (val parsed = AssistantPlanParser.parse(answer.content)) {
                    is AssistantPlanParser.Result.Bad -> {
                        addBubble("我没看懂模型的回答：${parsed.reason}", false)
                        addFootnote("模型原文：" + answer.content.take(DETAIL_CHARS))
                        addInlineButton("再试一次") { retry() }
                    }

                    is AssistantPlanParser.Result.Ok -> when (val reply = parsed.reply) {
                        is AssistantReply.Ask -> {
                            addBubble(reply.question, false)
                            turns += "我：$sentText\n助手：${reply.question}"
                            trimTurns()
                        }

                        is AssistantReply.PlanOps -> {
                            reply.note?.takeIf { it.isNotBlank() }?.let { addBubble(it, false) }
                            val outcome = AssistantInterpreter.interpret(
                                reply.ops,
                                AssistantInterpreter.ProductionEnv(host)
                            )
                            if (!outcome.ok) {
                                // 逐条原样显示解释器的错误：它会说清"这天有哪几节课""匹配到 2 节请用节次指定"，
                                // 改成自己的一句概括反而把用户修下去的依据丢了。这里不显示「应用」 ——
                                // `Outcome.ok == false` 时根本没有任何可应用的东西。
                                outcome.errors.forEach { addBubble(it, false) }
                                return
                            }
                            if (!outcome.changed) {
                                addBubble("没有需要修改的内容", false)
                                return
                            }
                            showResult(outcome, answer.totalTokens)
                        }
                    }
                }
            }
        }
    }

    private fun trimTurns() {
        while (turns.size > 1 && turns.joinToString("\n").length > MAX_CONTEXT_CHARS) {
            turns.removeAt(0)
        }
    }

    private fun retry() {
        val text = sentText
        if (text.isBlank()) return
        input.setText(text)
        send()
    }

    // ---------------------------------------------------------------- 结果区

    private fun showResult(outcome: AssistantInterpreter.Outcome, tokens: Int) {
        resultLines.removeAllViews()
        resultButtons.removeAllViews()
        for (line in AssistantInterpreter.renderPreview(outcome, TimetableRepository.result())) {
            resultLines.addView(
                body(line, 13f).apply { setPadding(0, dp(2), 0, dp(2)) },
                LinearLayout.LayoutParams(MATCH, WRAP)
            )
        }
        resultButtons.addView(
            primaryPill("应用") { applyResult(outcome) },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(5) }
        )
        resultButtons.addView(
            pill("再改改") {
                // "清空结果区 + 把焦点还给输入框" —— 用户要接着说的是"不对，改成…"，
                // 让他再点一次输入框纯属多余
                hideResult()
                input.requestFocus()
            },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(5) }
        )
        resultButtons.addView(
            pill("丢弃") { hideResult() },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(5) }
        )
        resultBox.visibility = View.VISIBLE
        if (tokens > 0) {
            setStatus("用了 $tokens 个 token（费用以服务商价格页为准）")
        }
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideResult() {
        resultBox.visibility = View.GONE
        resultLines.removeAllViews()
        resultButtons.removeAllViews()
    }

    private fun applyResult(outcome: AssistantInterpreter.Outcome) {
        val error = AssistantApplier.apply(host, outcome)
        if (error == null) {
            hideResult()
            toast("已应用")
            addBubble("改好了，桌面组件和提醒都跟着更新了", false)
            refreshUndoButton()
            onDone()
        } else {
            // 应用失败时**不**收起结果区：用户可能想「再改改」重来一次，而结果区里的预览
            // 正是他判断"哪里不对"的依据
            addBubble(error, false)
        }
    }

    // ---------------------------------------------------------------- 撤销

    private fun refreshUndoButton() {
        val available = runCatching { AssistantUndo.available(host) }.getOrDefault(false)
        undoButton.visibility = if (available) View.VISIBLE else View.GONE
        undoButton.isEnabled = available
        undoButton.alpha = if (available) 1f else 0.45f
    }

    private fun confirmUndo() {
        val describe = runCatching { AssistantUndo.describe(host) }.getOrNull()
        AlertDialog.Builder(host)
            .setTitle("撤销上次 AI 改动？")
            .setMessage(
                (describe?.let { "$it。" } ?: "") +
                    "课表会回到那次改动之前的样子（这几天的调课和你自己加的课都一起回去）。"
            )
            .setPositiveButton("撤销") { _, _ ->
                val ok = runCatching { AssistantUndo.restore(host) }.getOrDefault(false)
                toast(if (ok) "已撤销" else "撤销失败，课表保持原样")
                if (ok) {
                    refreshUndoButton()
                    onDone()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- 小工具

    private fun title(text: String): TextView = TextView(host).apply {
        this.text = text
        textSize = 17f
        setTextColor(color(R.color.text_primary))
    }

    private fun body(text: String, size: Float): TextView = TextView(host).apply {
        this.text = text
        textSize = size
        setTextColor(color(R.color.text_secondary))
    }

    /** 淡底药丸（次要动作 / 标签） */
    private fun pill(text: String, onClick: () -> Unit): TextView = TextView(host).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(color(R.color.accent))
        background = host.getDrawable(R.drawable.bg_pill)
        isClickable = true
        isFocusable = true
        setPadding(dp(12), dp(9), dp(12), dp(9))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            marginStart = dp(3); marginEnd = dp(3); topMargin = dp(4)
        }
        setOnClickListener { onClick() }
    }

    /** 实心强调色：主操作（发送 / 应用 / 去填 API Key）。淡底会被看成禁用态 */
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

        /** 一次最多接受多少字的用户输入（模型端也会截，但先在这里截能让用户看到提示） */
        private const val MAX_INPUT_CHARS = 500

        /** 附在气泡下面的原文/服务商 detail 最多显示多少字 */
        private const val DETAIL_CHARS = 500

        /**
         * 迷你多轮上下文的字符上限。
         *
         * 800 字约等于最近三四轮问答：反问（"哪一天？"）之后的追问通常一步就接上了，
         * 再多只是让每轮请求变贵、还可能把当前这句真需求挤出模型的注意力。
         */
        private const val MAX_CONTEXT_CHARS = 800

        /**
         * 打开助手。
         *
         * @param model 生产用 [ConfiguredAssistantModel]；测试/演示可注入假模型
         *              （`AssistantModel` 抽成接口的唯一目的就是这个）
         * @param onDone 课表被改动、或撤销之后回调（调用方据此刷新课表与设置页）
         */
        fun show(
            activity: Activity,
            model: AssistantModel = ConfiguredAssistantModel(activity),
            onDone: () -> Unit = {}
        ) {
            runCatching { AssistantDialog(activity, model, onDone).show() }
                .onFailure { Log.w(TAG, "打不开 AI 助手：${it.javaClass.simpleName}: ${it.message}") }
        }
    }
}
