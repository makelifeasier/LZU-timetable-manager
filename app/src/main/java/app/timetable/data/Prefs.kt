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

    /**
     * 行数选项文案，下标即取值（0 = 自动）。
     *
     * 为什么列到 6 行（而 [WidgetData.MANUAL_MAX_ROWS] 也正是 6、[widgetRows] 的校验也夹在
     * 0..6）：这里以前只列到「4 行」，于是设置页最多只能选到 4 行，而组件本身明明能放 6 行
     * —— 开关与校验对不上，用户在大格子上永远铺不满，只能看着底部空一块。
     * 这个列表只影响设置页多出哪几个 chip，取值口径与组件侧完全一致。
     */
    val WIDGET_ROWS_NAMES = listOf("自动", "1 行", "2 行", "3 行", "4 行", "5 行", "6 行")

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

    /**
     * 这份课表属于哪个学期，形如 `"2026 秋"`（由 [app.timetable.data.TermInfo.year] +
     * [app.timetable.data.TermInfo.term] 拼成）。空 = 还没有学期信息。
     *
     * 为什么要单独存一个学期签名：**周次基准只对某一个学期成立**。
     * 上学期装过、下学期重新导入时，第 1 周周一还是上学期那个日期，
     * 当前周次会被算成 30+ —— 课表页、小组件、提醒会一起变空，
     * 而且「本周」按钮也救不回来（算出来的周次本来就是错的）。
     * 有了签名，重新导入时一比就知道学期换了没有，见 TimetableRepository.commit。
     *
     * 它**不是缓存**：清空缓存不会清它 —— 清完之后往往是同一学期重新抓一遍，
     * 那时候必须保持原有周次基准不变。
     */
    var termSignature: String
        get() = s("termSig")
        set(v) = sp.edit().putString("termSig", v).apply()

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

    // 这里原来还有一个 lastRawHtml（key = "rawHtml"）。它是死属性：全工程没有任何读写者，
    // 诊断页用的是 diagnosticsHtml（key = "diagHtml"）—— 而 clearCache() 却在 remove "rawHtml"，
    // 清一个永远不会存在的 key，真正堆在盘上的 diagHtml 反而没人清。已删除。

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
    var diagnosticsHtml: String        get() = s("diagHtml")
        set(v) = sp.edit().putString("diagHtml", v).apply()

    // ------------------------------------------------ 小组件扩展（全部默认关闭）

    /**
     * 小组件底部的「每日一句」。
     * 默认**关闭** —— 只在你愿意把列表下方那块空白用起来时才打开。
     */
    var quoteEnabled: Boolean
        get() = b("quoteEnabled")
        set(v) = sp.edit().putBoolean("quoteEnabled", v).apply()

    /** 小组件底部的图片框（最多 5 张，存 app 私有目录路径） */
    var photoEnabled: Boolean
        get() = b("photoEnabled")
        set(v) = sp.edit().putBoolean("photoEnabled", v).apply()

    /** 图片列表，换行分隔。图片在选图时就已压缩并复制进私有目录，卸载即清 */
    var photoUris: String
        get() = s("photoUris")
        set(v) = sp.edit().putString("photoUris", v).apply()

    /** 自动轮播（小组件里唯一可行的自动切换方式） */
    var photoFlipEnabled: Boolean
        get() = b("photoFlipEnabled")
        set(v) = sp.edit().putBoolean("photoFlipEnabled", v).apply()

    /** 自动换图间隔（秒），只允许下面列出的几档 */
    var photoFlipSeconds: Int
        get() = i("photoFlipSeconds", 10)
        set(v) = sp.edit().putInt("photoFlipSeconds", if (v in PHOTO_FLIP_CHOICES) v else 10).apply()

    // ------------------------------------------------ 节日祝福（默认全部关闭）

    /** 祝福总开关。默认关闭 */
    var greetEnabled: Boolean
        get() = b("greetEnabled")
        set(v) = sp.edit().putBoolean("greetEnabled", v).apply()

    /** 公历节日（总开关打开后默认也是开的） */
    var greetSolar: Boolean
        get() = b("greetSolar", true)
        set(v) = sp.edit().putBoolean("greetSolar", v).apply()

    /** 农历节日（春节/中秋…），默认关闭 */
    var greetLunar: Boolean
        get() = b("greetLunar")
        set(v) = sp.edit().putBoolean("greetLunar", v).apply()

    /** 校历节点（开学/考试周/假期…），默认关闭 */
    var greetSchool: Boolean
        get() = b("greetSchool")
        set(v) = sp.edit().putBoolean("greetSchool", v).apply()

    /** 上一次弹过祝福的日期（yyyy-MM-dd），保证当天只弹一次 */
    var greetLastShownDate: String
        get() = s("greetLastShownDate")
        set(v) = sp.edit().putString("greetLastShownDate", v).apply()

    /** 新手引导是否已看过 */
    var guideShown: Boolean
        get() = b("guideShown")
        set(v) = sp.edit().putBoolean("guideShown", v).apply()

    // ------------------------------------------------ 当日调整（点周几表头）

    /**
     * 当日调课覆盖，JSON：`{"2026-10-08":{"mode":"REPLACE","week":7}}`。
     *
     * 只存「引用第几周」而不是整份课表：数据小、换学期重新导入后自动跟随；
     * 只有手动"加一节课"才把那一节的字段存进去。
     */
    var dayOverrides: String
        get() = s("dayOverrides")
        set(v) = sp.edit().putString("dayOverrides", v).apply()

    /** 小组件底部区域的强制开关：-1 强制隐藏 / 0 自动（按剩余高度判断）/ 1 强制显示 */
    var widgetExtrasOverride: Int
        get() = i("widgetExtrasOverride", 0)
        set(v) = sp.edit().putInt("widgetExtrasOverride", v.coerceIn(-1, 1)).apply()

    /**
     * 「清空本地缓存」要清掉哪些 key。
     *
     * 判断标准只有一条：**这些值全部是派生数据** —— 抓回来的网页、解析出来的课表、
     * 上一次同步的结果与诊断数字。清完下次同步会原样重新生成一遍。
     *
     * 所以下面这些**刻意不清**：
     *  - `url` / `portalUrl`：用户导入/改过的来源，清了就得重新登录一遍；
     *  - `termSig`：学期签名，清了同一学期重新抓一遍会白重算一次周次基准；
     *  - `remindOn` / `bgType` / `style` / `dark` …：用户的设置，跟缓存无关；
     *  - `photoUris` 指向的文件由相册管理，清 key 只会留下一堆读不到的文件。
     *
     * 以前这份清单是散在 [clearCache] 里的一串 `.remove(...)`：既写漏了
     * `week1` / `manualWeek` / `dayOverrides`（换学期后清缓存再导入，旧基准会留着），
     * 又在删一个根本没人写的 `rawHtml`。抽成常量是为了能被单元测试直接比对。
     */
    internal val CACHE_KEYS = listOf(
        "result", "lastSyncAt", "lastSyncMsg",
        "httpCode", "finalUrl", "htmlBytes",
        "diagHtml", "loginTrace", "loginExpired",
        "week1", "manualWeek", "dayOverrides"
    )

    /**
     * 清空派生缓存。
     *
     * 注意 `week1` / `manualWeek` 也在清单里：周次基准是「这份课表的解释方式」，
     * 跟着课表一起清掉，下次导入才会按**新**学期重新估算 —— 这正是修掉
     * 「换了学期但周次基准还是上学期」那条链路的另一半。
     * 不清 `termSig`：清完往往是同一学期重抓，签名留着才不会误判成换学期。
     */
    fun clearCache() {
        var e = sp.edit()
        for (k in CACHE_KEYS) e = e.remove(k)
        e.apply()
    }

    /**
     * 自动换图间隔可选值（秒）。注意：属性初始化有先后顺序，所以这两行必须放在引用它们之前。
     *
     * 下限是 **10 秒**：更快的档位（例如 5 秒）看着像"更流畅"，实际是把小组件变成每 5 秒
     * 唤醒一次的后台定时器 —— 桌面组件不是动图，耗电换来的观感提升并不值。所以 5 秒档去掉了，
     * 想要"手动换"就用点击（图片区点一下换下一张，不耗电）。
     */
    private val PHOTO_FLIP_CHOICES = listOf(10, 30, 60)

    /** 供设置页渲染 chips 用 */
    val photoFlipChoices = PHOTO_FLIP_CHOICES

    /**
     * 界面动效（切周淡入、风格预览切换等），默认开。
     *
     * 留这个开关是因为动效对少数人（前庭敏感、老机器）是负担，
     * 他们关掉之后应当立刻变回"瞬间切换"，而不是"动得更快"。
     */
    var animEnabled: Boolean
        get() = b("animEnabled", true)
        set(v) = sp.edit().putBoolean("animEnabled", v).apply()
}
