package app.timetable.data

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** 全部持久化都走这里。轻量、同步读、异步写。 */
object Prefs {

    /**
     * 课表链接 —— **故意没有默认值**。
     *
     * 每个学生的 `id`/`yearid`/`termid` 都不同，且每学期会变，硬编码任何一条别人的
     * 链接都会让其他同学装上后打不开自己的课表。所以首次使用为空，由「登录导入」
     * 自动发现并写回这里（见 LoginActivity.discoverTimetable）。
     */
    const val DEFAULT_URL = ""

    /** 教务系统门户入口。所有人一致，且会 302 到统一身份认证 */
    const val DEFAULT_PORTAL_URL = "https://jwk.lzu.edu.cn/academic/"

    /** 课表页路径前缀，用于拼接兜底链接与识别链接 */
    const val TIMETABLE_PATH = "/manager/coursearrange/showTimetable.do"

    private const val FILE = "timetable"

    /** 周末底色模式：淡底色（现在） */
    const val WEEKEND_TINT = 0

    /** 周末底色模式：与工作日相同 */
    const val WEEKEND_SAME = 1

    /** 设置页里的两个选项文案 */
    val WEEKEND_NAMES = listOf("淡底色（现在）", "与工作日相同")

    /**
     * 桌面小组件显示几行课：0 = 自动（跟随系统给的高度），1..6 = 手动指定。
     *
     * 加这个开关的原因：**系统回报的高度并不可信**。少数启动器（实测荣耀 MagicOS）
     * 回报的不是当前格子高度，而是一直回报我们声明的最小值 —— 于是组件明明很高，
     * 应用却只算得出 1~2 行课，下面留一大片空白（"把课表遮住一半"）。
     * 自动模式已经做了校正，但各家 ROM 行为无法穷举，留一个用户能直接定死的开关
     * 比继续猜可靠。
     */
    var widgetRows: Int
        get() = i("widgetRows", 0)
        set(v) = sp.edit().putInt("widgetRows", v.coerceIn(0, 6)).apply()

    /** 行数选项文案，下标即取值（0 = 自动） */
    val WIDGET_ROWS_NAMES = listOf("自动", "1 行", "2 行", "3 行", "4 行")
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun s(key: String, def: String = ""): String = sp.getString(key, def) ?: def
    private fun b(key: String, def: Boolean = false): Boolean = sp.getBoolean(key, def)
    private fun i(key: String, def: Int = 0): Int = sp.getInt(key, def)
    private fun l(key: String, def: Long = 0L): Long = sp.getLong(key, def)

    // ------------------------------------------------------------- 课表来源
    /** 已导入的课表链接。空 = 还没导入过，此时自动刷新会提示先去登录导入 */
    var timetableUrl: String
        get() = s("url", DEFAULT_URL)
        set(v) = sp.edit().putString("url", v.trim()).apply()

    /** 登录入口（门户）。默认教务处门户，正常不需要改；留成可配置以便门户地址变更时应急 */    var portalUrl: String
        get() = s("portalUrl", DEFAULT_PORTAL_URL)
        set(v) = sp.edit().putString("portalUrl", v.trim().ifBlank { DEFAULT_PORTAL_URL }).apply()

    /** 是否已经导入过至少一次课表 */
    val hasTimetable: Boolean get() = timetableUrl.isNotBlank()

    // ------------------------------------------------------------- 周次
    /** 第 1 周周一（ISO yyyy-MM-dd）。空表示尚未设置 */
    var week1Monday: String
        get() = s("week1")
        set(v) = sp.edit().putString("week1", v).apply()

    fun week1MondayDate(): LocalDate? =
        runCatching { LocalDate.parse(week1Monday) }.getOrNull()

    fun setWeek1FromCurrentWeek(today: LocalDate, currentWeek: Int) {
        week1Monday = WeekCalc.week1Monday(today, currentWeek).toString()
    }

    /** 手动预览周次；0 表示跟随今天 */
    var manualWeek: Int
        get() = i("manualWeek", 0)
        set(v) = sp.edit().putInt("manualWeek", v).apply()

    // ------------------------------------------------------------- 缓存
    var resultJson: String
        get() = s("result")
        set(v) = sp.edit().putString("result", v).apply()

    var lastSyncAt: Long
        get() = l("lastSyncAt")
        set(v) = sp.edit().putLong("lastSyncAt", v).apply()

    var lastSyncMessage: String
        get() = s("lastSyncMsg")
        set(v) = sp.edit().putString("lastSyncMsg", v).apply()

    var loginExpired: Boolean
        get() = b("loginExpired")
        set(v) = sp.edit().putBoolean("loginExpired", v).apply()

    /** 首次使用是否已经自动弹过登录页（只弹一次，避免每次开 App 都跳走） */
    var firstRunPrompted: Boolean
        get() = b("firstRunPrompted")
        set(v) = sp.edit().putBoolean("firstRunPrompted", v).apply()

    // ------------------------------------------------------------- 诊断
    var lastHttpCode: Int
        get() = i("httpCode", 0)
        set(v) = sp.edit().putInt("httpCode", v).apply()

    var lastFinalUrl: String
        get() = s("finalUrl")
        set(v) = sp.edit().putString("finalUrl", v).apply()

    /**
     * 登录流程轨迹（最近 20 条）。
     *
     * 为什么要有：登录后自动导入这条链路只在对方手机上才复现得了，
     * 而 logcat 需要连电脑。把「走了哪一步、发现了几条候选、抓取多少字节」
     * 留在设置里，用户直接念出来就能定位问题。
     */
    var loginTrace: String
        get() = s("loginTrace")
        set(v) = sp.edit().putString("loginTrace", v).apply()

    /** 追加一条轨迹 */
    fun trace(line: String) {
        val kept = loginTrace.lines().filter { it.isNotBlank() }.takeLast(19)
        loginTrace = (kept + line).joinToString("\n")
    }

    var lastHtmlBytes: Int
        get() = i("htmlBytes", 0)
        set(v) = sp.edit().putInt("htmlBytes", v).apply()

    var lastRawHtml: String
        get() = s("rawHtml")
        set(v) = sp.edit().putString("rawHtml", v).apply()

    // ------------------------------------------------------------- 提醒
    var reminderEnabled: Boolean
        get() = b("remindOn", true)
        set(v) = sp.edit().putBoolean("remindOn", v).apply()

    var reminderMinutes: Int
        get() = i("remindMin", 10)
        set(v) = sp.edit().putInt("remindMin", v.coerceIn(0, 120)).apply()

    // ------------------------------------------------------------- 外观
    /** 0 跟随系统 / 1 浅色 / 2 深色 */
    var darkMode: Int
        get() = i("dark", 0)
        set(v) = sp.edit().putInt("dark", v.coerceIn(0, 2)).apply()

    var colorSeed: Int
        get() = i("colorSeed", 0)
        set(v) = sp.edit().putInt("colorSeed", v).apply()

    /** 课表卡片风格 0..5，见 TimetableRenderer.Style.NAMES */
    var style: Int
        get() = i("style", 0)
        set(v) = sp.edit().putInt("style", v.coerceIn(0, 5)).apply()

    /** 小组件显示模式：0 正在上+接下来 / 1 一整天全部课 */
    var widgetMode: Int
        get() = i("widgetMode", 0)
        set(v) = sp.edit().putInt("widgetMode", v.coerceIn(0, 1)).apply()

    /**
     * 周末列的底色：0 淡底色（现在这样，把周末与工作日区分开）/ 1 与工作日相同。
     * 两种摆在一起让人选 —— 有人喜欢一眼看出周末，有人觉得那两块补丁多余。
     */
    var weekendMode: Int
        get() = i("weekendMode", 0)
        set(v) = sp.edit().putInt("weekendMode", v.coerceIn(0, 1)).apply()

    /** 是否给周末列铺淡底色（渲染器直接吃这个） */
    val weekendTint: Boolean get() = weekendMode == WEEKEND_TINT

    /** 自定义背景：0 默认 / 1 纯色 / 2 图片 */
    var bgType: Int
        get() = i("bgType", 0)
        set(v) = sp.edit().putInt("bgType", v.coerceIn(0, 2)).apply()

    var bgColor: Int
        get() = i("bgColor", 0xFFF5F7FA.toInt())
        set(v) = sp.edit().putInt("bgColor", v).apply()

    var bgUri: String
        get() = s("bgUri")
        set(v) = sp.edit().putString("bgUri", v).apply()

    /** 图片背景上的蒙版强度 0..90（%），越大文字越清楚 */
    var bgScrim: Int
        get() = i("bgScrim", 55)
        set(v) = sp.edit().putInt("bgScrim", v.coerceIn(0, 90)).apply()

    /** 上次抓到的原始页面，供诊断导出 */
    var diagnosticsHtml: String
        get() = s("diagHtml")
        set(v) = sp.edit().putString("diagHtml", v).apply()

    fun clearCache() {
        sp.edit()
            .remove("result").remove("lastSyncAt").remove("lastSyncMsg")
            .remove("httpCode").remove("finalUrl").remove("htmlBytes").remove("rawHtml")
            .apply()
    }
}
