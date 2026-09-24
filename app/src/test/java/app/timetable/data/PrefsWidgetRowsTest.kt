package app.timetable.data

import app.timetable.widget.WidgetData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件行数开关的「选项 ↔ 取值」一致性。
 *
 * 对应第 5 条：`WIDGET_ROWS_NAMES` 只列到「4 行」，而 `widgetRows` 的校验是
 * `coerceIn(0, 6)`、组件侧上限 `WidgetData.MANUAL_MAX_ROWS` 也是 6 ——
 * 开关与校验对不上，用户在大格子上永远选不到 5、6 行，只能看着底部空一块。
 *
 * 这里只读 [Prefs.WIDGET_ROWS_NAMES] 这个列表（纯数据，不碰 SharedPreferences），
 * 所以不需要 Context。
 */
class PrefsWidgetRowsTest {

    @Test
    fun everyManualRowCountUpToTheWidgetLimitIsSelectable() {
        // 下标即取值，所以列表长度必须恰好覆盖 0..MANUAL_MAX_ROWS
        assertEquals(
            "可选行数必须覆盖 0（自动）到组件上限 ${WidgetData.MANUAL_MAX_ROWS}",
            WidgetData.MANUAL_MAX_ROWS + 1,
            Prefs.WIDGET_ROWS_NAMES.size
        )
    }

    @Test
    fun automaticIsIndexZero() {
        assertEquals("自动", Prefs.WIDGET_ROWS_NAMES[0])
    }

    @Test
    fun rowLabelsMatchTheirIndex() {
        // 下标 1..N 的文案必须是「N 行」：设置页拿下标直接当取值写回 Prefs，
        // 文案和下标错位就会出现"选了 6 行只显示 3 行"这种没法解释的现象。
        for (rows in 1..WidgetData.MANUAL_MAX_ROWS) {
            assertEquals("$rows 行", Prefs.WIDGET_ROWS_NAMES[rows])
        }
    }

    @Test
    fun labelsAreUnique() {
        assertEquals(Prefs.WIDGET_ROWS_NAMES.size, Prefs.WIDGET_ROWS_NAMES.toSet().size)
        assertTrue(Prefs.WIDGET_ROWS_NAMES.none { it.isBlank() })
    }
}
