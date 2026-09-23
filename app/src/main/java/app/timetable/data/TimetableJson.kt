package app.timetable.data

import org.json.JSONArray
import org.json.JSONObject

/** ParseResult ⇄ JSON，用于本地缓存（org.json 由 Android 框架提供，零依赖） */
object TimetableJson {

    fun toJson(r: ParseResult): String {
        val root = JSONObject()
        root.put(
            "term", JSONObject()
                .put("year", r.term.year)
                .put("term", r.term.term)
                .put("studentNo", r.term.studentNo)
                .put("className", r.term.className)
        )

        val sections = JSONArray()
        for (s in r.sections) {
            sections.put(
                JSONObject()
                    .put("index", s.index).put("label", s.label)
                    .put("start", s.start).put("end", s.end)
            )
        }
        root.put("sections", sections)

        val sessions = JSONArray()
        for (s in r.sessions) {
            sessions.put(
                JSONObject()
                    .put("name", s.name).put("seqNo", s.seqNo)
                    .put("room", s.room).put("teacher", s.teacher)
                    .put("weekStart", s.weeks.start).put("weekEnd", s.weeks.end)
                    .put("parity", s.weeks.parity.name)
                    .put("category", s.category)
                    .put("day", s.day)
                    .put("startSection", s.startSection).put("endSection", s.endSection)
            )
        }
        root.put("sessions", sessions)

        val unscheduled = JSONArray()
        for (u in r.unscheduled) {
            unscheduled.put(
                JSONObject()
                    .put("code", u.code).put("name", u.name).put("seqNo", u.seqNo)
                    .put("teacher", u.teacher).put("day", u.day).put("room", u.room)
                    .put("weekStart", u.weeks?.start ?: -1)
                    .put("weekEnd", u.weeks?.end ?: -1)
                    .put("parity", u.weeks?.parity?.name ?: "")
            )
        }
        root.put("unscheduled", unscheduled)
        return root.toString()
    }

    fun fromJson(text: String): ParseResult {
        if (text.isBlank()) return ParseResult()
        return try {
            val root = JSONObject(text)
            val termObj = root.optJSONObject("term")
            val term = TermInfo(
                year = termObj?.optString("year").orEmpty(),
                term = termObj?.optString("term").orEmpty(),
                studentNo = termObj?.optString("studentNo").orEmpty(),
                className = termObj?.optString("className").orEmpty()
            )

            val sections = ArrayList<Section>()
            root.optJSONArray("sections")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    sections += Section(
                        o.optInt("index"), o.optString("label"),
                        o.optString("start"), o.optString("end")
                    )
                }
            }

            val sessions = ArrayList<Session>()
            root.optJSONArray("sessions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    sessions += Session(
                        name = o.optString("name"),
                        seqNo = o.optString("seqNo"),
                        room = o.optString("room"),
                        teacher = o.optString("teacher"),
                        weeks = WeekSpan(
                            o.optInt("weekStart", 1),
                            o.optInt("weekEnd", 25),
                            runCatching { Parity.valueOf(o.optString("parity")) }.getOrDefault(Parity.NONE)
                        ),
                        category = o.optString("category"),
                        day = o.optInt("day", 1),
                        startSection = o.optInt("startSection", 1),
                        endSection = o.optInt("endSection", 1)
                    )
                }
            }

            val unscheduled = ArrayList<UnscheduledCourse>()
            root.optJSONArray("unscheduled")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ws = o.optInt("weekStart", -1)
                    val we = o.optInt("weekEnd", -1)
                    unscheduled += UnscheduledCourse(
                        code = o.optString("code"),
                        name = o.optString("name"),
                        seqNo = o.optString("seqNo"),
                        teacher = o.optString("teacher"),
                        weeks = if (ws > 0 && we > 0) WeekSpan(
                            ws, we,
                            runCatching { Parity.valueOf(o.optString("parity")) }.getOrDefault(Parity.NONE)
                        ) else null,
                        day = o.optString("day"),
                        room = o.optString("room")
                    )
                }
            }

            ParseResult(term, sections, sessions, unscheduled)
        } catch (e: Exception) {
            ParseResult()
        }
    }
}
