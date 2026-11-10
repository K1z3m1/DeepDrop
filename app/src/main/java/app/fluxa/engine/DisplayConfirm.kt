package app.fluxa

import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * Safety net after the real screen size / DPI was changed: shows a 10 s countdown with a confirm
 * button. If the user does not confirm (e.g. the screen became unreadable), the original size and
 * DPI are restored automatically. Used by the "try now" button and when a session starts.
 *
 * From an Activity it is a normal dialog; from a service it is an accessibility overlay window.
 */
object DisplayConfirm {
    const val SECONDS = 10

    private val main = Handler(Looper.getMainLooper())
    private var cancelActive: (() -> Unit)? = null

    /** [onResult] gets true when the user kept the new screen, false when it was reverted. */
    fun show(ctx: Context, onResult: ((kept: Boolean) -> Unit)? = null) {
        main.post { showNow(ctx, onResult) }
    }

    private fun showNow(ctx: Context, onResult: ((Boolean) -> Unit)?) {
        cancelActive?.invoke() // a newer change supersedes the previous countdown
        cancelActive = null

        val isActivity = ctx is android.app.Activity
        val host: Context = if (isActivity) ctx else OverlayAccessibilityService.instance ?: run {
            // nowhere to draw the prompt: keep the change, the user still has "คืนค่าจอเดิม"
            onResult?.invoke(true)
            return
        }
        val kit = Kit(host)

        var left = SECONDS
        var finished = false
        var dismiss: () -> Unit = {}

        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(Palette.PANEL_CARD, 24, Palette.PANEL_STROKE, 1)
            setPadding(kit.dp(22), kit.dp(22), kit.dp(22), kit.dp(16))
        }
        card.addView(kit.text("ยืนยันการปรับจอ", 18f, Palette.TEXT, bold = true))
        val msg = kit.text("", 14f, Palette.MUTED).apply { setLineSpacing(0f, 1.25f) }
        card.addView(msg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(12) })

        fun render() {
            msg.text = I18n.f(
                "หากเห็นหน้าจอนี้ชัดเจน กด \"ยืนยัน\" เพื่อใช้ค่าใหม่ต่อ\nไม่เช่นนั้นจะคืนค่าจอเดิมให้อัตโนมัติใน {0} วินาที",
                left,
            )
        }

        fun finish(keep: Boolean) {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(this)
            cancelActive = null
            dismiss()
            if (!keep) DisplayTuner.restore(ctx.applicationContext)
            onResult?.invoke(keep)
        }

        fun button(label: String, color: Int, keep: Boolean) = kit.text(label, 15f, color, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(kit.dp(8), kit.dp(13), kit.dp(8), kit.dp(13))
            background = kit.glassBtn(16)
            isClickable = true
            setOnClickListener { finish(keep) }
        }

        val row = LinearLayout(host).apply { setPadding(0, kit.dp(18), 0, 0) }
        row.addView(button("คืนค่าเดิม", Palette.DANGER, false), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(
            button("ยืนยัน", Palette.OK, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(10) },
        )
        card.addView(row)
        render()

        val tick = object : Runnable {
            override fun run() {
                if (finished) return
                left--
                if (left <= 0) {
                    finish(false)
                } else {
                    render()
                    main.postAtTime(this, this@DisplayConfirm, android.os.SystemClock.uptimeMillis() + 1000)
                }
            }
        }

        if (isActivity) {
            val dialog = Dialog(ctx)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCancelable(false) // only the two buttons or the timeout decide
            dialog.setContentView(card)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(0))
                setLayout((ctx.resources.displayMetrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            dismiss = { runCatching { dialog.dismiss() } }
            dialog.show()
        } else {
            val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val lp = WindowManager.LayoutParams(
                (host.resources.displayMetrics.widthPixels * 0.9f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.CENTER
            val ok = runCatching { wm.addView(card, lp) }.isSuccess
            if (!ok) {
                onResult?.invoke(true)
                return
            }
            dismiss = { runCatching { wm.removeView(card) } }
        }

        cancelActive = {
            if (!finished) {
                finished = true
                main.removeCallbacksAndMessages(this)
                dismiss()
            }
        }
        main.postAtTime(tick, this, android.os.SystemClock.uptimeMillis() + 1000)
    }
}
