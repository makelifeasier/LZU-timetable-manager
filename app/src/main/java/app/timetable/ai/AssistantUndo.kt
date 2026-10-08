package app.timetable.ai

import android.content.Context
import android.util.Log
import app.timetable.data.AssistantConfig
import app.timetable.data.DayOverrides
import app.timetable.data.Prefs
import app.timetable.data.TimetableRepository
import app.timetable.data.UserCourses
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 「撤销上次 AI 改动」——把应用前的状态**逐字节**还原。
 *
 * ## 为什么必须做，而且必须逐字节
 *
 * 助手一次能改好几天，还可能顺手删掉一门自定义课。三天的课表被改错而撤不回来，
 * 用户对这个功能的信任就一次用完了。所以：
 *
 *  - 快照存的是**两处存储的原文串**（`dayOverrides` 与自定义课程），不是"解析后的结构"——
 *    结构经过一次解析/序列化就可能与原来不一样（键的顺序、老版本兼容字段），
 *    而"还原成原样"这件事不应该有第二种答案；
 *  - **只留最近一次**（一键撤销，不是版本管理）；
 *  - 恢复后立刻 `notifyDataChanged`，让课表页/小组件/提醒一起回去。
 *
 * ## 明文存在 prefs 里，行不行
 *
 * 行。它本来就是这台手机上已有的数据（课表覆盖 + 自定义课程），快照只是同一份数据的副本，
 * 没有引入任何新的敏感信息。存的是"应用前"而不是"助手的回复"，所以也不违反
 * "默认不保存对话原文"那条。
 */
internal object AssistantUndo {

    private const val K_DAYS = "undoDayOverrides"
    private const val K_COURSES = "undoUserCourses"
    private const val K_AT = "undoAt"

    data class Snapshot(val dayOverrides: String, val userCourses: String, val at: Long)

    /** 应用**之前**调用：把当前状态记下来（覆盖上一个快照） */
    fun save(context: Context) {
        AssistantConfig.store(context).edit()
            .putString(K_DAYS, Prefs.dayOverrides)
            .putString(K_COURSES, UserCourses.raw(context))
            .putLong(K_AT, System.currentTimeMillis())
            .apply()
        Log.i(AssistantClient.TAG, "已记录撤销快照（dayOverrides=${Prefs.dayOverrides.length}字）")
    }

    fun peek(context: Context): Snapshot? {
        val prefs = AssistantConfig.store(context)
        if (!prefs.contains(K_DAYS)) return null
        return Snapshot(
            dayOverrides = prefs.getString(K_DAYS, "").orEmpty(),
            userCourses = prefs.getString(K_COURSES, "").orEmpty(),
            at = prefs.getLong(K_AT, 0L)
        )
    }

    /** 有没有可撤销的东西 */
    fun available(context: Context): Boolean = peek(context) != null

    /** 界面上一行说明："上次改动：10月15日 14:03" */
    fun describe(context: Context): String? {
        val s = peek(context) ?: return null
        val t = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(s.at),
            java.time.ZoneId.systemDefault()
        )
        return "上次改动：${t.monthValue}月${t.dayOfMonth}日 " +
            String.format("%02d:%02d", t.hour, t.minute)
    }

    /**
     * 撤销。返回是否真的动了。
     *
     * 先写两处存储、再刷一次：中间失败就保持原样（并让调用方报错），
     * 不做"尽力而为的部分还原"——那种状态比不还原更难解释。
     */
    fun restore(context: Context): Boolean {
        val snap = peek(context) ?: return false
        return try {
            Prefs.dayOverrides = snap.dayOverrides
            UserCourses.replaceRaw(context, snap.userCourses)
            TimetableRepository.notifyDataChanged(context)
            clear(context)
            Log.i(AssistantClient.TAG, "已撤销上次 AI 改动")
            true
        } catch (t: Throwable) {
            Log.w(AssistantClient.TAG, "撤销失败：${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    fun clear(context: Context) {
        AssistantConfig.store(context).edit()
            .remove(K_DAYS).remove(K_COURSES).remove(K_AT).apply()
    }
}

/**
 * 把解释结果真正写进去。
 *
 * 顺序：**先存快照 → 一次写完当天覆盖 → 再写自定义课程 → 最后只刷一次**
 * （`notifyDataChanged` 会重读自定义课程 + 刷小组件 + 重排提醒，刷多次纯属浪费电）。
 *
 * 失败时用快照回滚：宁可"什么都没发生"，也不要"改了一半"。
 */
internal object AssistantApplier {

    /** @return null = 成功；否则是错误说明 */
    fun apply(context: Context, outcome: AssistantInterpreter.Outcome): String? {
        if (!outcome.ok) return outcome.errors.firstOrNull() ?: "改动不合法"
        if (!outcome.changed) return "没有需要修改的内容"

        AssistantUndo.save(context)
        try {
            val changes = LinkedHashMap<LocalDate, DayOverrides.Override?>()
            for (d in outcome.days) {
                // 只有真的变了才写：没变的日期留着原来的覆盖（可能是用户手动调的）
                if (d.changed) changes[d.date] = d.override
            }
            if (changes.isNotEmpty()) DayOverrides.putAll(context, changes)

            for (c in outcome.courses) {
                c.add?.let { UserCourses.save(context, it) }
                c.deleteId?.let { UserCourses.remove(context, it) }
            }

            TimetableRepository.notifyDataChanged(context)
            Log.i(
                AssistantClient.TAG,
                "已应用：${changes.size} 天 / 自定义课程 ${outcome.courses.size} 项"
            )
            return null
        } catch (t: Throwable) {
            Log.w(AssistantClient.TAG, "应用失败，回滚：${t.javaClass.simpleName}: ${t.message}")
            runCatching { AssistantUndo.restore(context) }
            return "写入失败，已回到改之前的状态：${t.message ?: t.javaClass.simpleName}"
        }
    }
}
