package app.timetable

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.widget.Toast
import app.timetable.data.Prefs
import app.timetable.data.TimetableRepository
import app.timetable.databinding.ActivityDiagnosticsBinding
import app.timetable.ui.BaseActivity
import app.timetable.widget.WidgetData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 为什么需要这一页：解析规则是从一份真实页面反推出来的，
 * 万一教务系统改版导致解析为空，这里能导出原始 HTML 以便快速适配。
 */
class DiagnosticsActivity : BaseActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.diagRefetch.setOnClickListener {
            binding.diagText.text = "正在抓取…"
            TimetableRepository.refresh(this) { render() }
        }
        binding.diagExport.setOnClickListener { exportHtml() }
        binding.diagCopy.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("诊断", binding.diagText.text))
            toast("已复制诊断信息")
        }
        render()
    }

    private fun render() {
        val status = TimetableRepository.status
        val result = TimetableRepository.result()
        val html = Prefs.diagnosticsHtml
        val time = if (status.at > 0)
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(status.at))
        else "从未"

        binding.diagText.text = buildString {
            appendLine("=== 抓取状态 ===")
            appendLine("时间：$time")
            appendLine("结果：${if (status.ok) "成功" else "失败"}")
            appendLine("消息：${status.message}")
            appendLine("HTTP：${status.httpCode}")
            appendLine("HTML 字节：${status.bytes}")
            appendLine("最终 URL：${status.finalUrl.ifBlank { "—" }}")
            appendLine("登录态：${if (Prefs.loginExpired) "已过期" else "正常"}")
            appendLine("课表链接：${Prefs.timetableUrl}")
            appendLine()
            appendLine("=== 登录流程轨迹（最近 20 步）===")
            appendLine(Prefs.loginTrace.ifBlank { "（还没有记录 —— 去「登录导入」走一遍）" })
            appendLine()
            appendLine("=== 桌面小组件 ===")
            appendLine(WidgetData.placementSummary(this@DiagnosticsActivity))
            appendLine()
            appendLine("=== 解析结果 ===")
            appendLine("节次：${result.sections.size}")
            appendLine("课程段：${result.sessions.size}")
            appendLine("无时间地点课程：${result.unscheduled.size}")
            appendLine("学年学期：${result.term.display}")
            appendLine("班级：${result.term.className}")
            appendLine("学号：${result.term.studentNo}")
            appendLine("第 1 周周一：${Prefs.week1Monday.ifBlank { "未设置" }}")
            appendLine()
            appendLine("=== 节次表 ===")
            result.sections.forEach { s ->
                appendLine("  行序 ${s.index}  ${s.label}  ${s.start}-${s.end}")
            }
            appendLine()
            appendLine("=== 前 10 段课 ===")
            result.sessions.take(10).forEach { s ->
                appendLine("  周${s.day} ${s.startSection}-${s.endSection} ${s.name} | ${s.room} | ${s.teacher} | ${s.weeks}")
            }
            appendLine()
            appendLine("=== 页面源码（前 3000 字，共 ${html.length} 字）===")
            append(html.take(3000))
        }
    }

    private fun exportHtml() {
        val html = Prefs.diagnosticsHtml
        if (html.isEmpty()) {
            toast("还没有抓到页面，先点「重新抓取」")
            return
        }
        val resolver = contentResolver
        val name = "timetable-${System.currentTimeMillis()}.html"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/兰大课表")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            toast("导出失败：无法创建文件")
            return
        }
        try {
            resolver.openOutputStream(uri)?.use { it.write(html.toByteArray(Charsets.UTF_8)) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            toast("导出失败：${e.message}")
            return
        }

        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "兰大课表页面源码")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        toast("已导出到「下载/兰大课表」")
        runCatching { startActivity(Intent.createChooser(share, "分享页面源码")) }
    }

    private fun toast(message: String): Unit =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    @Suppress("unused")
    private fun appContext(): Context = this
}
