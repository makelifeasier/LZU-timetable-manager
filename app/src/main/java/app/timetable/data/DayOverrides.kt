package app.timetable.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 「当日调课」的覆盖记录。
 *
 * 场景：某天的课临时变了 —— 单双周被调、停课、补一节讲座。用户点课表上方的星期标题
 * 就能把**那一天**换成另一周同一星期几的课，或者直接清空 / 加一节课。
 *
 * 设计取舍（很重要）：
 *  - **REPLACE 只存"引用第几周"**，不复制整份课表：数据小，而且下学期重新导入后自动跟随新数据；
 *  - **ADD 才存那一节的字段**（课名/教室/节次），因为那是凭空加出来的，没有来源可引用；
 *  - **LIST 存"这一天完整的课表"**：给"点某一节课、单独改掉/删掉它"用（语义见 [Mode.LIST]）。
 *  - 键是**日期**（`2026-10-08`）而不是"第几周星期几"：这样跨周后旧覆盖自然不生效，
 *    也不会因为用户手动切周次而串味。
 */
internal object DayOverrides {

    /**
     * 覆盖的四种形态。
     *
     * [LIST] 是"整份列表"：这一天的课表**完全**由 [Override.extra] 给出 ——
     * "点某一节课、单独改掉或删掉它"就是这么记的。ADD 不行（它是在原课之上再加，
     * 盖不住原来那节），REPLACE 也不行（它只存"换成第几周的那一天"，装不下任意列表）。
     *
     * 它一度搭在 CLEAR 上（当时 `ui/DayEditDialog` 对 mode 用的是**穷尽 when**，
     * 多一个枚举值那个文件立刻编不过）；那个 when 后来改成了带 else 的分支，
     * 于是这里补成真正的枚举值。**解析时仍然认老的 `CLEAR + "l": true`**，
     * 免得同一台设备上早先版本写下的覆盖读不回来（见 [parse]）。
     */
    enum class Mode { REPLACE, CLEAR, ADD, LIST }

    data class Override(
        val mode: Mode,
        /** 只在 REPLACE 时有意义：引用第几周 */
        val sourceWeek: Int = 0,
        /**
         * 只在 REPLACE 时有意义：引用**星期几**（1=周一 … 7=周日）；0 = 与被替换的那天同一天。
         *
         * 用户实测后的原话："换一整天的课是换到其他天，不是周四都换到周四"。
         * 原来这里只有 [sourceWeek]，于是"替换"被硬编码成**同一个星期几**——
         * 而真实场景里最常见的恰恰是"这周四上的是周三的课"（调课/补课就是这么来的）。
         * 0 表示"同一天"，是为了让老数据（只有 week 字段）读回来语义不变。
         */
        val sourceDay: Int = 0,
        /** ADD / LIST 时有意义：手动加的那几节；LIST 时是**整份**课表 */
        val extra: List<Session> = emptyList()
    ) {
        /**
         * 是不是「整份列表」模式：这一天的课表完全由 [extra] 给出，不看 base、也不与它合并。
         *
         * 为什么由 [mode] 推导而不是单独存一个布尔字段：两者一旦能不一致，就会出现
         * "说好了整份替换、结果又把原来的课并了进来"这种最难查的错。
         * 保留这个只读属性纯粹是让调用方读起来直白（仓库、DayEditDialog 都是读它）。
         */
        val isList: Boolean get() = mode == Mode.LIST

        companion object {
            /** 「这一天的课表就是这几段」的覆盖。语义见 [Mode.LIST] 与 [isList] */
            fun list(extra: List<Session>): Override = Override(Mode.LIST, extra = extra)
        }
    }

    // ------------------------------------------------------------- 纯逻辑（可单测）

    /**
     * REPLACE 模式真正要取的那些课：**第 [Override.sourceWeek] 周的 [Override.sourceDay] 那一天**。
     *
     * 抽成纯函数是为了能单测 —— 这里曾经错过：最早只按"同一个星期几"去取，
     * 于是"周四换成周三的课"这种最常见的调课根本做不到（用户当场指出）。
     * 现在 [Override.sourceDay] 为 0 时表示"同一天"（老数据语义）。
     *
     * 取回来的课 `day` 会**改写成 [targetDay]**：否则画网格、排提醒时会按源星期几那一列去放。
     */
    fun replaceSource(result: ParseResult, o: Override, targetDay: Int): List<Session> {
        if (o.sourceWeek < 1) return WeekCalc.sessionsFor(result, targetDay, 0) // 空表：周次非法
        val srcDay = if (o.sourceDay in 1..7) o.sourceDay else targetDay
        return WeekCalc.sessionsFor(result, srcDay, o.sourceWeek)
            .map { it.copy(day = targetDay) }
    }

    /**
     * 把覆盖应用到某一天的课表上。
     *
     * @param base 该日期"本来"的课（由星期 + 周次算出来）
     * @param day  星期几（1..7），ADD 的课需要
     */
    fun apply(base: List<Session>, o: Override?, day: Int = 1): List<Session> = when {
        o == null -> base

        // 列表模式：这一天就是 extra 里的这几段，与 base 无关（不叠加、不合并）。
        // day 一律改写成被覆盖的那一天 —— 存进去的 day 只是记录，
        // 不该盖过"这个覆盖挂在哪个日期上"这个事实（与 ADD 的归一化口径一致）。
        o.isList -> o.extra.map { it.copy(day = day) }

        // REPLACE 的"换成哪一周"只有仓库知道（它才拿得到课表和周次基准），
        // 所以这里**原样返回**：万一将来有调用方误用这个函数，看到的也是"没改过"，
        // 而不是原来的"返回空表"——那会把用户的"替换"静默变成"停课"。
        o.mode == Mode.REPLACE -> base

        o.mode == Mode.CLEAR -> emptyList()
        else -> mergeSorted(base, o.extra, day)
    }

    /**
     * 列表模式在**显示层**的样子：这一天的课 = extra，排序并合并相邻节次。
     *
     * 仓库 `TimetableRepository.sessionsOn` 直接调它 —— 所以"列表模式进来之后到底长什么样"
     * 这件事本身是可单测的（sessionsOn 要 Context，测不了；这里把它的那一步抽出来）。
     * 仍然要合并的原因：extra 是"当时界面上看得见的那几段"，
     * 相邻的两段同一门课（第3节 + 第4节）在课表上应该是「第3-4节」一张卡片，
     * 与没有调课时 `WeekCalc.merge` 的表现保持一致。
     */
    fun listFor(o: Override, day: Int): List<Session> =
        WeekCalc.merge(o.extra.map { it.copy(day = day) })

    /** ADD：把额外课并进原课表，按起始节排序、同节去重（同一节被加了两次只留一个） */    fun mergeSorted(base: List<Session>, extra: List<Session>, day: Int): List<Session> {
        val normalized = extra.map { it.copy(day = day) }
        val seen = base.map { it.startSection to it.endSection to it.name }.toHashSet()
        val add = normalized.filter {
            seen.add(it.startSection to it.endSection to it.name)
        }
        return (base + add).sortedWith(compareBy({ it.startSection }, { it.name }))
    }

    /** 过去的日期不再需要（每天最多留 8 条，避免无限增长） */
    fun aliveKeys(map: Map<LocalDate, Override>, today: LocalDate, keepDays: Long = 3): Map<LocalDate, Override> =
        map.filterKeys { !it.isBefore(today.minusDays(keepDays)) }

    // ------------------------------------------------------------- 存取（JSON）

    private fun parse(raw: String): Map<LocalDate, Override> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<LocalDate, Override>()
        runCatching {
            val obj = JSONObject(raw)
            for (key in obj.keys()) {
                val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
                val item = obj.optJSONObject(key) ?: continue
                val mode = when (item.optString("m")) {
                    "REPLACE" -> Mode.REPLACE
                    "CLEAR" -> Mode.CLEAR
                    "ADD" -> Mode.ADD
                    "LIST" -> Mode.LIST
                    else -> continue
                }
                val extra = ArrayList<Session>()
                item.optJSONArray("x")?.let { arr -> extra.addAll(readSessions(arr)) }
                // 兼容：整份列表最早是记成 CLEAR + "l": true 的，读到就归一化成 LIST。
                // 不兼容的话，同一台设备上早先版本存下的"改过的那几节"会读成"当天课全没了"。
                val normalized = if (item.optBoolean("l", false)) Mode.LIST else mode
                out[date] = Override(normalized, item.optInt("w", 0), item.optInt("sd", 0), extra)
            }
        }
        return out
    }

    private fun readSessions(arr: JSONArray): List<Session> {
        val list = ArrayList<Session>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list += Session(
                name = o.optString("n"),
                room = o.optString("r"),
                teacher = o.optString("t"),
                day = o.optInt("d", 1),
                startSection = o.optInt("s", 1),
                endSection = o.optInt("e", o.optInt("s", 1))
            )
        }
        return list
    }

    private fun write(map: Map<LocalDate, Override>): String {
        val obj = JSONObject()
        for ((date, o) in map) {
            val item = JSONObject()
            item.put("m", o.mode.name)
            if (o.mode == Mode.REPLACE) {
            item.put("w", o.sourceWeek)
            // 只在"换到别的星期几"时才写：0（同一天）不写，老版本读到的还是原来的语义
            if (o.sourceDay != 0) item.put("sd", o.sourceDay)
        }
            // LIST 的 extra 就算是空的也不影响：此时只写 m=LIST，读回来就是"这一天没课"，
            // 与"没调过"（键不存在）分得清清楚楚。
            if ((o.mode == Mode.ADD || o.isList) && o.extra.isNotEmpty()) {
                val arr = JSONArray()
                for (s in o.extra) {
                    arr.put(
                        JSONObject()
                            .put("n", s.name).put("r", s.room).put("t", s.teacher)
                            .put("d", s.day).put("s", s.startSection).put("e", s.endSection)
                    )
                }
                item.put("x", arr)
            }
            obj.put(date.toString(), item)
        }
        return obj.toString()
    }

    // ------------------------------------------------------------- 面向调用方

    fun all(context: Context): Map<LocalDate, Override> {
        Prefs.init(context)
        return parse(Prefs.dayOverrides)
    }

    fun get(context: Context, date: LocalDate): Override? = all(context)[date]

    fun put(context: Context, date: LocalDate, override: Override) {
        Prefs.init(context)
        val map = LinkedHashMap(all(context))
        map[date] = override
        Prefs.dayOverrides = write(map)
    }

    fun remove(context: Context, date: LocalDate) {
        Prefs.init(context)
        val map = LinkedHashMap(all(context))
        if (map.remove(date) == null) return
        Prefs.dayOverrides = write(map)
    }

    /** 启动/导入时调用：清掉过期的覆盖 */
    fun prune(context: Context, today: LocalDate = LocalDate.now()) {
        Prefs.init(context)
        val map = all(context)
        val alive = aliveKeys(map, today)
        if (alive.size != map.size) Prefs.dayOverrides = write(alive)
    }

    /** 诊断用：当前有几条覆盖 */
    fun size(context: Context): Int = all(context).size
}
