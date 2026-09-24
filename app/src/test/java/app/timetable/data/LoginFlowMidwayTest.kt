package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「发现中途换页」在 [LoginFlow] 这一层到底意味着什么。
 *
 * Activity 侧的处理在 `LoginActivity.onLoaded`：如果本轮发现是在 A 页发起的
 * （discoveryUrl），链接还没选出来就跳到了 B 页，就把 discoveryDone 清掉、在新页面重新发现。
 *
 * 为什么非清不可 —— 也就是这个文件钉住的契约：
 *  - 发现已经跑过（discoveryDone = true）、但还没选出链接（discoveredUrl 仍为空）时，
 *    门户上的下一页拿到的是 GO_TIMETABLE；
 *  - GO_TIMETABLE 在"手上没有可用链接"时只会显示「没能自动找到课表入口」然后停住 ——
 *    首次用户正好卡在这一步（点开「学生课表」跳到"选择学年"这类中间页就是典型触发场景）；
 *  - 清掉标记后同一个页面会重新给出 DO_DISCOVERY，在新页面上再扫一轮，这条路才算走完。
 *
 * 反过来也要钉住：**已经跳过一次课表页之后不能清**，否则用户随便点一下都会被自动发现抢走。
 */
class LoginFlowMidwayTest {

    private val portal = "https://jwk.lzu.edu.cn/academic/"

    /** 点开「学生课表」后可能落到的中间页（不是课表页，也不在登录路径上） */
    private val midway =
        "https://jwk.lzu.edu.cn/academic/manager/coursearrange/chooseTerm.jsp"

    @Test
    fun discoveredButNothingPickedLeadsToTheDeadEndStep() {
        // 不清标记时就是这一步：GO_TIMETABLE —— Activity 里对应那句
        // 「没能自动找到课表入口」的死胡同文案
        assertEquals(
            LoginFlow.Step.GO_TIMETABLE,
            LoginFlow.next(midway, false, false, discoveryDone = true, wentToPortalAfterTicket = true)
        )
    }

    @Test
    fun clearingTheFlagRestartsDiscoveryOnTheNewPage() {
        // Activity 在"发现中途换页"时清的正是这个标记 → 新页面必须重新发现
        assertEquals(
            LoginFlow.Step.DO_DISCOVERY,
            LoginFlow.next(midway, false, false, discoveryDone = false, wentToPortalAfterTicket = true)
        )
    }

    @Test
    fun landingOnTheRealTimetablePageCapturesRegardlessOfTheFlag() {
        // 点开「学生课表」直接命中课表页时，标记清不清都不影响：CAPTURE 的优先级最高
        val timetable =
            "https://jwk.lzu.edu.cn/academic/manager/coursearrange/showTimetable.do" +
                "?id=900001&yearid=46&termid=2&timetableType=STUDENT"
        assertEquals(LoginFlow.Step.CAPTURE, LoginFlow.next(timetable, false, false, discoveryDone = false))
        assertEquals(LoginFlow.Step.CAPTURE, LoginFlow.next(timetable, false, false, discoveryDone = true))
    }

    @Test
    fun afterTimetableWasOpenedTheFlagMustNotBeCleared() {
        // 已经跳过一次课表页 → 页面上不再乱跳（SHOW_CURRENT）。
        // 假如这时把标记清了，同一个页面就会变成 DO_DISCOVERY —— 用户自己点着看的时候
        // 会被自动发现强行带走，所以 Activity 那边的条件里带着 !autoNavigatedToTimetable。
        assertEquals(
            LoginFlow.Step.SHOW_CURRENT,
            LoginFlow.next(portal, false, true, discoveryDone = true, wentToPortalAfterTicket = true)
        )
        assertEquals(
            LoginFlow.Step.DO_DISCOVERY,
            LoginFlow.next(portal, false, true, discoveryDone = false, wentToPortalAfterTicket = true)
        )
    }
}
