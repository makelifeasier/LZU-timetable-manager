package app.timetable.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import app.timetable.data.Prefs

/** 通用 UI 工具 + 深色模式覆盖 + edge-to-edge 适配 */
object Ui {

    fun isNight(context: Context): Boolean = when (Prefs.darkMode) {
        1 -> false
        2 -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun dpf(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    /**
     * 处理 edge-to-edge 安全区。
     *
     * targetSdk 35+ 起 Android 强制 edge-to-edge：窗口内容会从 (0,0) 开始铺满，
     * 被状态栏 / 刘海 / 导航栏盖住。实测后果很严重 —— 系统 ActionBar 会压住
     * 页面顶部的横幅按钮，用户根本点不到（点击会落到 ActionBar 上）。
     * 所以这里显式把系统栏尺寸变成根布局的 padding。
     */
    fun applyEdgeToEdge(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root: View = if (content.childCount > 0) content.getChildAt(0) else return

        activity.window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.requestApplyInsets()

        // 浅色模式下把状态栏图标调暗，否则白底白字看不见
        activity.window.insetsController?.setSystemBarsAppearance(
            if (isNight(activity)) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        )
    }
}

/**
 * 不用 AppCompat 也能强制深/浅色：改写 uiMode 后重建 Context。
 * Prefs 在 App.onCreate 里已初始化，所以这里可直接读。
 */
abstract class BaseActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        val mode = Prefs.darkMode
        if (mode == 0) {
            super.attachBaseContext(newBase)
            return
        }
        val cfg = Configuration(newBase.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (mode == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    // 必须在 setContentView 之后才能拿到根布局，所以在这里挂钩子，
    // 免得每个 Activity 都要记得手动调一次。
    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        Ui.applyEdgeToEdge(this)
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        Ui.applyEdgeToEdge(this)
    }
}
