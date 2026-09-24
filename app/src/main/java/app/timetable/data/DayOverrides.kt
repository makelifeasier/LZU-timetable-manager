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
 *  - 键是**日期**（`2026-10-08`）而不是"第几周星期几"：这样跨周后旧覆盖自然不生效，
 *    也不会因为用户手动切周次而串味。
 */
internal object DayOverrides {

    enum class Mode { REPLACE, CLEAR, ADD }

    data class Override(
        val mode: Mode,
        /** 只在 REPLACE 时有意义：引用第几周的同一星期几 */
        val sourceWeek: Int = 0,
        /** 只在 ADD 时有意义：手动加的那几节 */
        val extra: List<Session> = emptyList()
    )

    // ------------------------------------------------------------- 纯逻辑（可单测）

    /**
     * 把覆盖应用到某一天的课表上。
     *
     * @param base 该日期"本来"的课（由星期 + 周次算出来）
     * @param day  星期几（1..7），ADD 的课需要
     */
    fun apply(base: List<Session>, o: Override?, day: Int = 1): List<Session> = when (o?.mode) {
        null -> base
        // REPLACE 的"换成哪一周"只有仓库知道（它才拿得到课表和周次基准），
        // 所以这里**原样返回**：万一将来有调用方误用这个函数，看到的也是"没改过"，
        // 而不是原来的"返回空表"——那会把用户的"替换"静默变成"停课"。
        Mode.REPLACE -> base
        Mode.CLEAR -> emptyList()
        Mode.ADD -> mergeSorted(base, o.extra, day)
    }

    /** ADD：把额外课并进原课表，按起始节排序、同节去重（同一节被加了两次只留一个） */
    fun mergeSorted(base: List<Session>, extra: List<Session>, day: Int): List<Session> {
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
                    else -> continue
                }
                val extra = ArrayList<Session>()
                item.optJSONArray("x")?.let { arr -> extra.addAll(readSessions(arr)) }
                out[date] = Override(mode, item.optInt("w", 0), extra)
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
            if (o.mode == Mode.REPLACE) item.put("w", o.sourceWeek)
            if (o.mode == Mode.ADD && o.extra.isNotEmpty()) {
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
