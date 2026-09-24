package app.timetable.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 13+ 通知权限的判定与「去设置页」Intent。
 *
 * 背景（第 3 条）：Manifest 里只声明 POST_NOTIFICATIONS 是不够的 —— 它是 API 33 起的
 * 运行时权限。只声明、不申请，通知会在系统那一层被直接丢掉：不报错、不提示，
 * 而本 App 的提醒**只有通知这一种表现形式**，等于提醒整个哑掉。
 *
 * 这里只能测纯判定：`Build.VERSION.SDK_INT` 在 JVM 单测里恒为 0（返回默认值），
 * 真的去调 [Notifications.needsPermission] 永远只会走到"不需要"那一支。
 * 所以分支被摊成 `needsPermission(sdkInt, enabled)` 这个 static 形式。
 */
class NotificationsPermissionTest {

    /** API 33 = Android 13 = TIRAMISU，运行时权限从这一版开始 */
    private val tiramisu = 33
    private val beforeTiramisu = 32
    private val afterTiramisu = 34

    @Test
    fun olderPlatformsNeedNoRuntimePrompt() {
        // 32 及以下：Manifest 里声明就够，弹窗反而会让用户莫名其妙
        assertFalse(Notifications.needsPermission(beforeTiramisu, notificationsEnabled = true))
        assertFalse(Notifications.needsPermission(beforeTiramisu, notificationsEnabled = false))
    }

    @Test
    fun android13AndUpNeedPermissionOnlyWhenNotificationsAreBlocked() {
        assertTrue(Notifications.needsPermission(tiramisu, notificationsEnabled = false))
        assertTrue("更高版本沿用同一规则", Notifications.needsPermission(afterTiramisu, notificationsEnabled = false))
    }

    @Test
    fun alreadyGrantedPermissionIsNotAskedAgain() {
        // 已经能发通知就不该再弹：这正是"每次进设置页都弹一次授权"的成因
        assertFalse(Notifications.needsPermission(tiramisu, notificationsEnabled = true))
        assertFalse(Notifications.needsPermission(afterTiramisu, notificationsEnabled = true))
    }

    @Test
    fun settingsIntentTargetsThisAppAndNotTheGlobalList() {
        // 不带 EXTRA_APP_PACKAGE 的 ACTION_APP_NOTIFICATION_SETTINGS 会落到"所有应用"，
        // 用户还得自己找一遍本 App —— 那不如别跳。
        assertEquals(
            "android.settings.APP_NOTIFICATION_SETTINGS",
            android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
        )
        assertEquals(
            "android.provider.extra.APP_PACKAGE",
            android.provider.Settings.EXTRA_APP_PACKAGE
        )
    }
}
