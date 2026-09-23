package app.timetable.donate

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 收款码弹窗。白底圆角承载 + 保存到相册。 */
object DonateDialog {

    /** 收款码显示边长（dp） */
    private const val QR_DP = 240

    fun show(activity: Activity) {
        val qr = Donations.loadQr(activity)
        if (qr == null) {
            Toast.makeText(activity, "这一版没有内置收款码", Toast.LENGTH_SHORT).show()
            return
        }

        val density = activity.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val side = (QR_DP * density).toInt()

        // 收款码必须落在**白底**上才能被相机识别。
        // 深色模式下不能直接跟随主题，否则白底黑码变成深底黑码，扫不出来。
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 16 * density
            }
        }

        card.addView(
            ImageView(activity).apply {
                setImageBitmap(qr)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(side, side)
            }
        )
        card.addView(
            TextView(activity).apply {
                text = "微信扫码 · 感谢支持"
                setTextColor(Color.parseColor("#66000000"))
                textSize = 12f
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (8 * density).toInt()
                layoutParams = lp
            }
        )

        val wrapper = LinearLayout(activity).apply {
            setPadding(pad, pad, pad, 0)
            addView(card)
        }

        AlertDialog.Builder(activity)
            .setTitle("支持一下")
            .setMessage("扫码请作者喝杯奶茶。\n完全自愿，不影响任何功能。")
            .setView(wrapper)
            .setPositiveButton("保存到相册") { _, _ ->
                val uri = Donations.saveToGallery(activity, qr)
                Toast.makeText(
                    activity,
                    if (uri != null) "已保存到相册（Pictures/兰大课表）" else "保存失败，可直接截图",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
